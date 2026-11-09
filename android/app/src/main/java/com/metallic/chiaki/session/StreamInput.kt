package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.PadMapping
import com.metallic.chiaki.common.PadTarget
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.touchcontrols.console.ConsolePadSettings
import com.metallic.chiaki.touchcontrols.console.GyroStick
import com.metallic.chiaki.touchcontrols.console.hasGyroscope
import com.metallic.chiaki.touchcontrols.console.sensorToController

class StreamInput(val context: Context, val preferences: Preferences)
{
	companion object
	{
		/** how long a short press of a key with a "held" target stays down (sent on release) */
		const val TAP_MS = 80L
	}

	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	// looked up once, not on every sensor reading
	private val windowManager by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

	val controllerState: ControllerState get()
	{
		// the sensor values are already in the controller's axes (see toController)
		val controllerState = sensorControllerState or keyControllerState or motionControllerState

		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return withGyroStick(controllerState or touchControllerState)
	}

	/** the options of the gyroscope as a stick (the pad's own, read from the same preferences) */
	private val gyroSettings = ConsolePadSettings(preferences.sharedPreferences, hasGyroscope(context))
	val gyroStick = GyroStick(gyroSettings)

	/** the last accelerometer reading in the controller's axes, in g (the gyroscope's tilt mode uses it) */
	private val accel = floatArrayOf(0f, 1f, 0f)

	/** the options changed (the pad's panel, live): read them again, and start or stop the sensors if needed */
	fun reloadGyroSettings()
	{
		gyroSettings.reload()
		gyroStick.reloadPaused()
		val flat = preferences.motionGripFlat
		if(flat != gripFlat)
		{
			gripFlat = flat
			// the axes just turned 90°: the orientation worked out in the old ones would be wrong (and its turn
			// left/right never corrects itself), so it starts again from the next readings
			resetOrientation()
		}
		val motion = preferences.motionEnabled
		if(motion != sendMotion)
		{
			sendMotion = motion
			// switched off: the console gets a controller at rest, not the last reading (the camera would keep turning)
			if(!motion)
				resetMotionState()
			// the sensors start again (and the orientation with them, facing forward)
			restartSensors()
		}
		else
			updateSensors()
	}

	/** the motion part of the state as a controller lying still (gravity straight down its y, as ControllerState starts) */
	private fun resetMotionState()
	{
		val rest = ControllerState()
		sensorControllerState.gyroX = rest.gyroX; sensorControllerState.gyroY = rest.gyroY; sensorControllerState.gyroZ = rest.gyroZ
		sensorControllerState.accelX = rest.accelX; sensorControllerState.accelY = rest.accelY; sensorControllerState.accelZ = rest.accelZ
		sensorControllerState.orientX = rest.orientX; sensorControllerState.orientY = rest.orientY
		sensorControllerState.orientZ = rest.orientZ; sensorControllerState.orientW = rest.orientW
		controllerStateUpdated()
	}

	private fun restartSensors()
	{
		if(sensorsOn)
		{
			(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorEventListener)
			sensorsOn = false
		}
		updateSensors()
	}

	val gyroStickEnabled get() = gyroSettings.gyroEnabled

	/** [state] with the gyroscope's push added to its stick (see [GyroStick.process]) */
	private fun withGyroStick(state: ControllerState) = gyroStick.process(state)

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private var keyControllerState = ControllerState() // from KeyEvents
	private var motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	/** the sensor whose readings send the state (the gyroscope, or the accelerometer without one) */
	private var sendingSensorType = Sensor.TYPE_GYROSCOPE

	/**
	 * The controller's own gyroscope is sent only with "Controller's gyroscope" on (the sensors may run for the
	 * gyroscope stick alone). Both this and the grip can change while playing (the pad's panel): see [reloadGyroSettings].
	 */
	private var sendMotion = preferences.motionEnabled

	/** how the device is held (the pad's panel → Gyroscope) */
	private var gripFlat = preferences.motionGripFlat

	/** the screen's rotation against the device's natural orientation, refreshed with the readings */
	private var rotation = Surface.ROTATION_0

	private val axes = FloatArray(3)

	/** the orientation sent to the console, from the readings already in the controller's axes */
	private val orientation = OrientationTracker()

	/** the seconds since the previous reading of the sending sensor (its own clock), 0 for the first one */
	private var lastSendTimestamp = 0L
	private fun sendDt(timestamp: Long): Float
	{
		val dt = if(lastSendTimestamp == 0L) 0f else ((timestamp - lastSendTimestamp) / 1e9f).coerceIn(0f, 0.1f)
		lastSendTimestamp = timestamp
		return dt
	}

	/** whether [accel] holds a reading taken since the orientation last started (it lines up with gravity first) */
	private var accelFresh = false

	/** the orientation starts again, facing forward, lined up with the next accelerometer reading */
	private fun resetOrientation()
	{
		orientation.reset()
		accelFresh = false
	}

	/**
	 * "Recentre": the controller faces forward again where the device points now (what the console gets), and the
	 * gyroscope stick takes this position as its centre. From the pad's button, the bar, the panel or a physical
	 * key mapped to it ([PadTarget.RECENTER]).
	 */
	fun recenterMotion()
	{
		gyroStick.recenter()
		resetOrientation()
	}

	/** a physical key recentred (the screen shows the notice) */
	var onRecentered: (() -> Unit)? = null

	/** learns the gyroscope's offset while the device is still (see [GyroCalibration]); kept for the whole session */
	private val gyroCalibration = GyroCalibration()
	private var lastAccelTimestamp = 0L

	private fun updateOrientation(gx: Float, gy: Float, gz: Float, dt: Float)
	{
		if(!accelFresh)
			return
		orientation.update(gx, gy, gz, accel[0], accel[1], accel[2], dt)
		sensorControllerState.orientX = orientation.orientX
		sensorControllerState.orientY = orientation.orientY
		sensorControllerState.orientZ = orientation.orientZ
		sensorControllerState.orientW = orientation.orientW
	}

	/**
	 * Turns a vector in the sensor's axes into the controller's ones, into [axes] (see [sensorToController]).
	 *
	 * Android's sensor axes follow the device's natural orientation (x right, y up, z out of the
	 * screen). The controller (DualSense, like SDL) has x right, y up out of its face, z toward
	 * the player. First the vector follows the screen's rotation, so x/y are the screen's right
	 * and up as the player sees them (this also covers devices whose natural orientation is
	 * landscape); then the grip:
	 * - upright (screen facing the player): screen up = controller up, out of the screen = toward
	 *   the player. Turning it left/right like aiming a bow is the controller's yaw.
	 * - flat (lying like a controller, top of the screen away from the player): out of the screen
	 *   = up, screen down = toward the player. That is what the fork did (for rotation 90 and 270).
	 * Both are proper rotations, so the same map works for the gyroscope and the accelerometer.
	 */
	private fun toController(x: Float, y: Float, z: Float) = sensorToController(rotation, gripFlat, x, y, z, axes)

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			val v = event.values
			// the stream is landscape either way round (userLandscape): it can flip mid-game
			if(event.sensor.type == sendingSensorType)
			{
				@Suppress("DEPRECATION")
				val r = windowManager.defaultDisplay.rotation
				// turned round: the axes flipped, as with a change of grip
				if(r != rotation)
				{
					rotation = r
					resetOrientation()
				}
			}
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					val g = SensorManager.GRAVITY_EARTH
					val accelDt = if(lastAccelTimestamp == 0L) 0f else ((event.timestamp - lastAccelTimestamp) / 1e9f).coerceIn(0f, 0.1f)
					lastAccelTimestamp = event.timestamp
					gyroCalibration.feedAccel(v[0] / g, v[1] / g, v[2] / g, accelDt)
					toController(v[0] / g, v[1] / g, v[2] / g)
					accel[0] = axes[0]; accel[1] = axes[1]; accel[2] = axes[2]
					accelFresh = true
					if(sendMotion)
					{
						sensorControllerState.accelX = axes[0]
						sensorControllerState.accelY = axes[1]
						sensorControllerState.accelZ = axes[2]
						// without a gyroscope the orientation follows gravity alone
						if(sendingSensorType == Sensor.TYPE_ACCELEROMETER)
							updateOrientation(0f, 0f, 0f, sendDt(event.timestamp))
					}
				}
				Sensor.TYPE_GYROSCOPE -> {
					// the time between readings (the sensor's own clock, in ns)
					val dt = sendDt(event.timestamp)
					// the offset learnt while still comes off before anything uses the reading (console, orientation, stick)
					gyroCalibration.feedGyro(v[0], v[1], v[2], dt)
					toController(v[0] - gyroCalibration.offsetX, v[1] - gyroCalibration.offsetY, v[2] - gyroCalibration.offsetZ)
					if(sendMotion)
					{
						sensorControllerState.gyroX = axes[0]
						sensorControllerState.gyroY = axes[1]
						sensorControllerState.gyroZ = axes[2]
						updateOrientation(axes[0], axes[1], axes[2], dt)
					}
					gyroStick.feed(axes[0], axes[1], axes[2], accel[0], accel[1], accel[2], dt)
				}
				else -> return
			}
			// Only the gyroscope (what aiming uses) sends the state; the accelerometer rides along with it.
			// Each sensor used to send it, at 250 Hz each: hundreds of calls a second on the main
			// thread, each one a packet to the console.
			if(event.sensor.type == sendingSensorType)
				controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private var resumed = false
	private var sensorsOn = false

	/** the sensors run while the screen is on and something needs them: "Motion", or the gyroscope as a stick */
	private fun updateSensors()
	{
		val want = resumed && (sendMotion || gyroSettings.gyroEnabled)
		if(want == sensorsOn)
			return
		sensorsOn = want
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		if(!want)
		{
			sensorManager.unregisterListener(sensorEventListener)
			return
		}
		// 125 Hz: the library sends the sticks and sensors at most every 8 ms anyway
		val samplingPeriodUs = 8000
		sendingSensorType = if(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
			Sensor.TYPE_GYROSCOPE else Sensor.TYPE_ACCELEROMETER
		lastSendTimestamp = 0L
		lastAccelTimestamp = 0L
		// a fresh start, facing forward (the device may have moved while the sensors were off)
		resetOrientation()
		listOfNotNull(
			sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
			sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
		).forEach {
			sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
		}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume() { resumed = true; mapping = preferences.padMapping; updateSensors() }

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause() { resumed = false; updateSensors() }
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		// always: the gyroscope stick can be switched on while playing
		lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	/**
	 * Lets go of every button, stick and touch (keeps the motion sensors): when the stream leaves
	 * the screen the releases of what was held never arrive, and the console would keep them held.
	 */
	fun releaseAll()
	{
		releaseKeys()
		keyControllerState = ControllerState()
		motionControllerState = ControllerState()
		gyroStick.recenter()
		touchControllerState = ControllerState() // also sends the new state
	}

	/**
	 * Nothing reaches the console while set (a dialog is over the stream): it only gets an idle
	 * controller, whatever is pressed. Some controller events still arrived with a dialog open,
	 * so releasing once was not enough.
	 */
	var blocked = false
		set(value)
		{
			if(field == value)
				return
			field = value
			releaseAll()
		}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(if(blocked) ControllerState() else controllerState) }
	}

	/** read again each time the stream comes back (a kept session can return after Settings changed it) */
	private var mapping = preferences.padMapping

	/** the mapping changed (the physical controller's panel): lets go of everything and reads it again */
	fun reloadMapping()
	{
		releaseAll()
		mapping = preferences.padMapping
	}

	/** whether the device's L2 and R2 are analog (an axis), for the panel */
	fun analogTriggers(device: InputDevice?) = axesOf(device).let { it.analogL2 to it.analogR2 }
	private val keyHandler = Handler(Looper.getMainLooper())

	/** how many keys hold each target down (two keys can press the same button) */
	private val targetHeld = IntArray(PadTarget.values().size)

	/** a key with a "held" target, waiting: its short press goes out on release, the held one after [PadMapping.HOLD_MS] */
	private inner class PendingKey(val keyCode: Int, val press: PadTarget?, val hold: PadTarget): Runnable
	{
		var held = false
		override fun run()
		{
			held = true
			setTarget(hold, true)
		}
	}

	private val pendingKeys = HashMap<Int, PendingKey>()
	/** the target each key that is down pressed right away (to let go of the same one, even if the mapping is odd) */
	private val pressedKeys = HashMap<Int, PadTarget>()

	private fun setTarget(target: PadTarget, down: Boolean)
	{
		val i = target.ordinal
		val wasOn = targetHeld[i] > 0
		targetHeld[i] = if(down) targetHeld[i] + 1 else maxOf(0, targetHeld[i] - 1)
		val on = targetHeld[i] > 0
		when(target)
		{
			// not a button of the console: an action, once per press
			PadTarget.RECENTER -> {
				if(on && !wasOn)
				{
					recenterMotion()
					onRecentered?.invoke()
				}
				return
			}
			PadTarget.L2 -> keyControllerState.l2State = if(on) UByte.MAX_VALUE else 0U
			PadTarget.R2 -> keyControllerState.r2State = if(on) UByte.MAX_VALUE else 0U
			else -> keyControllerState.buttons = keyControllerState.buttons.let {
				if(on) it or target.button else it and target.button.inv()
			}
		}
		controllerStateUpdated()
	}

	private fun releaseKeys()
	{
		pendingKeys.values.forEach { keyHandler.removeCallbacks(it) }
		pendingKeys.clear()
		pressedKeys.clear()
		keyHandler.removeCallbacksAndMessages(null)
		targetHeld.fill(0)
	}

	/** the physical controller that sent the last key or stick (-1: none yet), for its rumble motors */
	@Volatile var lastControllerId = -1
		private set

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		val keyCode = event.keyCode
		val down = event.action == KeyEvent.ACTION_DOWN
		event.device?.let { if(!it.isVirtual && it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) lastControllerId = it.id }

		// Back counts only from a real controller: the system's back gesture is a Back key from a
		// virtual device, and it must keep opening the bar.
		if(keyCode == KeyEvent.KEYCODE_BACK && (event.device == null || event.device.isVirtual))
			return false

		// A controller with analog triggers often sends the L2/R2 key too: its axis is the real
		// value, the key is swallowed (it could jump to full for a moment when letting go).
		if((keyCode == KeyEvent.KEYCODE_BUTTON_L2 && axesOf(event.device).analogL2) ||
			(keyCode == KeyEvent.KEYCODE_BUTTON_R2 && axesOf(event.device).analogR2))
			return true

		if(down)
		{
			// a key held down repeats its down event: only the first one counts
			if(event.repeatCount > 0 || pendingKeys.containsKey(keyCode) || pressedKeys.containsKey(keyCode))
				return mapping.pressTarget(keyCode) != null || mapping.holdTarget(keyCode) != null
			val press = mapping.pressTarget(keyCode)
			val hold = mapping.holdTarget(keyCode)
			when
			{
				hold != null -> {
					val pending = PendingKey(keyCode, press, hold)
					pendingKeys[keyCode] = pending
					keyHandler.postDelayed(pending, PadMapping.HOLD_MS)
				}
				press != null -> {
					pressedKeys[keyCode] = press
					setTarget(press, true)
				}
				else -> return false // not mapped: Android's own use (volume, Back opens the bar…)
			}
			return true
		}

		pendingKeys.remove(keyCode)?.let { pending ->
			keyHandler.removeCallbacks(pending)
			if(pending.held)
				setTarget(pending.hold, false)
			else if(pending.press != null)
			{
				// let go before the hold time: a short press of its own target, long enough for the console to see it
				val press = pending.press
				setTarget(press, true)
				keyHandler.postDelayed({ setTarget(press, false) }, TAP_MS)
			}
			return true
		}
		pressedKeys.remove(keyCode)?.let {
			setTarget(it, false)
			return true
		}
		return mapping.pressTarget(keyCode) != null || mapping.holdTarget(keyCode) != null
	}

	/**
	 * Which axes a controller uses, looked at once per device. The fork read only LTRIGGER/RTRIGGER
	 * for the triggers: many controllers (among them the built-in ones of many handhelds) send them as BRAKE/GAS,
	 * so only their digital key arrived and they worked as plain buttons.
	 */
	private class DeviceAxes(
		/** the trigger's axes the controller has (some report both names: the most pressed one counts) */
		val l2Axes: List<Int>, val r2Axes: List<Int>,
		val rightXAxis: Int, val rightYAxis: Int)
	{
		val analogL2 get() = l2Axes.isNotEmpty()
		val analogR2 get() = r2Axes.isNotEmpty()
	}

	private val deviceAxes = HashMap<Int, DeviceAxes>()

	private fun axesOf(device: InputDevice?): DeviceAxes
	{
		val id = device?.id ?: -1
		return deviceAxes.getOrPut(id) {
			fun has(axis: Int) = device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null
			fun present(vararg axes: Int) = axes.filter { has(it) }
			// the right stick is Z/RZ on almost every controller; RX/RY only when there is no Z/RZ
			val rightOnRx = !has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
			DeviceAxes(
				l2Axes = present(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
				r2Axes = present(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
				rightXAxis = if(rightOnRx) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z,
				rightYAxis = if(rightOnRx) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ)
		}
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		event.device?.let { if(!it.isVirtual) lastControllerId = it.id }
		val axes = axesOf(event.device)
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (coerceIn(0f, 1f) * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(axes.rightXAxis).signedAxis()
		motionControllerState.rightY = event.getAxisValue(axes.rightYAxis).signedAxis()
		fun trigger(list: List<Int>) = (list.maxOfOrNull { event.getAxisValue(it) } ?: 0f).unsignedAxis()
		motionControllerState.l2State = trigger(axes.l2Axes)
		motionControllerState.r2State = trigger(axes.r2Axes)
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}