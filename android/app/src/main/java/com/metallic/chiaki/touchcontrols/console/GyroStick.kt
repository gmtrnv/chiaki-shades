// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import com.metallic.chiaki.lib.ControllerState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** how the phone's movement becomes the stick: like a mouse (speed) or like a tilted joystick (angle) */
enum class GyroStyle(val value: String)
{
	/** the speed of the turn is how far the stick goes; stopping the turn lets the stick go back to the middle */
	MOUSE("mouse"),
	/** the angle against the position where it started is how far the stick goes */
	TILT("tilt");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: MOUSE }
}

/** when the gyroscope drives the stick */
enum class GyroActivation(val value: String)
{
	ALWAYS("always"),
	/** only while the chosen button is held */
	HOLD("hold"),
	/** always, except while the chosen button is held (to put the phone back in a comfortable place) */
	CLUTCH("clutch"),
	/** each press of the chosen button switches it on or off (it starts off) */
	TOGGLE("toggle");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: HOLD }
}

/** which turn moves the stick left and right */
enum class GyroHorizontal(val value: String)
{
	/** turning the phone left and right, like aiming a bow */
	YAW("yaw"),
	/** rolling it sideways, like a steering wheel */
	ROLL("roll");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: YAW }
}

/**
 * Turns a vector in the sensor's axes into the controller's ones, into [out] (the stream and the
 * test screen both use it, so they agree on what "turning left" is).
 *
 * Android's sensor axes follow the device's natural orientation (x right, y up, z out of the
 * screen). The controller (DualSense, like SDL) has x right, y up out of its face, z toward
 * the player. First the vector follows the screen's [rotation], so x/y are the screen's right
 * and up as the player sees them (this also covers devices whose natural orientation is
 * landscape); then the grip:
 * - upright (screen facing the player): screen up = controller up, out of the screen = toward
 *   the player. Turning it left/right like aiming a bow is the controller's yaw.
 * - flat ([gripFlat]: lying like a controller, top of the screen away from the player): out of the
 *   screen = up, screen down = toward the player. That is what the fork did (for rotation 90 and 270).
 * Both are proper rotations, so the same map works for the gyroscope, the accelerometer and
 * the vector part of the orientation quaternion.
 */
fun sensorToController(rotation: Int, gripFlat: Boolean, x: Float, y: Float, z: Float, out: FloatArray)
{
	val sx: Float
	val sy: Float
	when(rotation)
	{
		android.view.Surface.ROTATION_90 -> { sx = -y; sy = x }
		android.view.Surface.ROTATION_180 -> { sx = -x; sy = -y }
		android.view.Surface.ROTATION_270 -> { sx = y; sy = -x }
		else -> { sx = x; sy = y }
	}
	out[0] = sx
	if(gripFlat) { out[1] = z; out[2] = -sy }
	else { out[1] = sy; out[2] = z }
}

/**
 * What activates the gyroscope stick (2.5.6): a list of inputs that must all be held at once ("L3 + R3"). An input
 * is a button of the controller (by [PadButton.value]) or a stick being moved ([STICK_L], [STICK_R]).
 */
object GyroInputs
{
	const val STICK_L = "stick_l"
	const val STICK_R = "stick_r"

	fun isStick(id: String) = id == STICK_L || id == STICK_R

	/** the saved text as a list (an unreadable or empty one is L2, the default); order as in the picker */
	fun parse(raw: String?): List<String>
	{
		val ids = raw?.split(',')?.map { it.trim() }?.filter { isStick(it) || PadButton.from(it) != null }?.toSet() ?: emptySet()
		if(ids.isEmpty())
			return listOf(PadButton.L2.value)
		return order(ids)
	}

	fun order(ids: Collection<String>): List<String> =
		listOf(STICK_L, STICK_R).filter { it in ids } + PadButton.values().map { it.value }.filter { it in ids }

	fun encode(ids: Collection<String>) = order(ids).joinToString(",")
}

/**
 * The gyroscope as a stick (2.5.3), for games that do not read the gyroscope of the controller: the
 * phone's movement is added to a stick (the right one by default). Pure maths, with no Android in
 * it: [StreamInput] feeds it the sensors and the test screen too, so what is tuned there is what
 * the stream does.
 *
 * The inputs are in the controller's axes (x right, y up, z toward the player): the gyroscope in
 * rad/s, the accelerometer in g. Turning left is a positive turn about y, nose up a positive turn
 * about x, a wheel turned left a positive turn about z; the stick goes the other way round (left
 * and up are negative), so every sign is flipped once, below.
 *
 * The result is [outX], [outY] in -1..1 to add to the stick's own value; both are 0 while not [engaged].
 */
class GyroStick(private val settings: ConsolePadSettings)
{
	companion object
	{
		/** MOUSE: at 100 % sensitivity the stick is all the way over at this speed (degrees per second) */
		const val FULL_SPEED_DEG = 100f
		/** below this the stick counts as not moved */
		private const val EPSILON = 0.005f
		/** with a stick among the inputs, letting go of it takes this long to count (a thumb trembling back to the middle) */
		const val RELEASE_DELAY_MS = 150L
	}

	/** milliseconds, a clock that does not go back (the tests set their own) */
	var clock: () -> Long = { System.nanoTime() / 1_000_000L }
	private var lastHeldAt = Long.MIN_VALUE / 2

	/**
	 * The user's own on/off for this session: the bar's button, the pad's gyroscope button and, with the
	 * Toggle activation, the chosen button of the controller all flip this one.
	 */
	var paused: Boolean
		get() = pausedState
		set(value)
		{
			if(pausedState == value)
				return
			pausedState = value
			// remembered between sessions (this device only)
			settings.storeGyroPaused(value)
			if(value) reset()
			onPausedChanged?.invoke()
		}
	private var pausedState = false

	/**
	 * Takes the remembered state again: what was saved, or the activation's own start (Toggle off, the others on).
	 * Called when the options change: turning the gyroscope stick off and on again clears the saved state,
	 * so the start is forced to be the activation's own.
	 */
	fun reloadPaused() { paused = settings.gyroPausedStored() ?: (settings.gyroActivation == GyroActivation.TOGGLE) }

	/** [paused] changed (whoever changed it): the buttons showing it follow */
	var onPausedChanged: (() -> Unit)? = null

	fun toggle() { paused = !paused }

	private var wasHeld = false
	private var lastActivation = settings.gyroActivation

	init { pausedState = settings.gyroPausedStored() ?: (lastActivation == GyroActivation.TOGGLE) }

	var outX = 0f
		private set
	var outY = 0f
		private set

	/** the gyroscope is driving the stick right now (enabled, not paused, and its button's condition holds) */
	var engaged = false
		private set

	// smoothed what-the-phone-does, already divided by what it takes to push the stick all the way
	private var sx = 0f
	private var sy = 0f
	// TILT: the angles now (smoothed) and where "centre" is
	private var pitch = 0f
	private var roll = 0f
	private var neutralPitch = 0f
	private var neutralRoll = 0f
	private var needNeutral = true
	private var haveAngles = false

	/** the stick's centre is where the phone is now (TILT) */
	fun recenter() { needNeutral = true }

	private fun reset()
	{
		outX = 0f; outY = 0f
		sx = 0f; sy = 0f
		haveAngles = false
		needNeutral = true
	}

	/**
	 * Tells whether the activation button is held (call it whenever the controller's state is put
	 * together). Returns [engaged]. Starting to be engaged starts from rest: no old smoothing, and the
	 * tilt's centre is where the phone is then.
	 */
	fun engage(buttonHeld: Boolean): Boolean
	{
		val act = settings.gyroActivation
		// Toggle starts off, the others start on: choosing another activation puts the switch where it suits
		if(act != lastActivation)
		{
			lastActivation = act
			paused = act == GyroActivation.TOGGLE
		}
		// the press is noted BEFORE switching: whoever hears the switch may call back in here at once (the test
		// screen recomputes its state), and must not see the same press again (that was an endless loop)
		val pressed = act == GyroActivation.TOGGLE && buttonHeld && !wasHeld
		wasHeld = buttonHeld
		if(pressed)
			paused = !paused
		val want = settings.gyroEnabled && !paused && when(act)
		{
			GyroActivation.ALWAYS, GyroActivation.TOGGLE -> true
			GyroActivation.HOLD -> buttonHeld
			GyroActivation.CLUTCH -> !buttonHeld
		}
		if(want != engaged)
		{
			engaged = want
			reset()
		}
		return engaged
	}

	/**
	 * One reading of the sensors, [dt] seconds after the last one. [ax], [ay], [az] is the accelerometer
	 * (the last one received), used by TILT.
	 */
	fun feed(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float)
	{
		if(!engaged || dt <= 0f)
			return
		val s = settings
		val sens = s.gyroSensitivity
		var nx: Float
		var ny: Float
		var tau = s.gyroSmoothing * 0.3f
		if(s.gyroStyle == GyroStyle.MOUSE)
		{
			val full = Math.toRadians(FULL_SPEED_DEG.toDouble()).toFloat()
			nx = -(if(s.gyroHorizontal == GyroHorizontal.YAW) gy else gz) / full * sens
			ny = -gx / full * sens * s.gyroSensitivityY
		}
		else
		{
			// the angles from gravity: no drift (the gyroscope's integral would wander), but they do not
			// know "turned left or right", only "tilted"; hand shake needs a little smoothing
			val p = atan2(-az, ay)
			val r = atan2(ax, ay)
			if(!haveAngles) { pitch = p; roll = r; haveAngles = true }
			val a = 1f - exp(-dt / max(tau, 0.04f))
			pitch += (p - pitch) * a
			roll += (r - roll) * a
			if(needNeutral) { neutralPitch = pitch; neutralRoll = roll; needNeutral = false }
			val range = Math.toRadians(s.gyroTiltRange.toDouble()).toFloat()
			nx = -(roll - neutralRoll) / range * sens
			ny = -(pitch - neutralPitch) / range * sens * s.gyroSensitivityY
			// the angles are smoothed already
			tau = 0f
		}
		if(tau > 0f)
		{
			val a = 1f - exp(-dt / tau)
			sx += (nx - sx) * a
			sy += (ny - sy) * a
		}
		else { sx = nx; sy = ny }

		var x = shape(sx)
		var y = shape(sy)
		if(s.gyroInvertX) x = -x
		if(s.gyroInvertY) y = -y
		// like the thumb sticks: past the game's own dead zone as soon as there is movement, a circle
		var m = hypot(x, y)
		if(m < EPSILON)
		{
			outX = 0f; outY = 0f
			return
		}
		if(m > 1f) { x /= m; y /= m; m = 1f }
		val out = s.gyroAntiDeadzone + (1f - s.gyroAntiDeadzone) * m
		outX = x / m * out
		outY = y / m * out
	}

	private fun buttonHeld(state: ControllerState, b: UInt) = when(b)
	{
		BUTTON_L2 -> state.l2State > 0U
		BUTTON_R2 -> state.r2State > 0U
		else -> state.buttons and b != 0U
	}

	private fun stickMoved(x: Short, y: Short) = hypot(x / 32767f, y / 32767f) >= settings.gyroStickThreshold

	/**
	 * Whether every input of the activation is held in [state]. [state] is what the thumbs and real controllers do,
	 * before the gyroscope's own push is added: a stick the gyroscope moves does not count as moved.
	 */
	fun inputsHeld(state: ControllerState): Boolean = settings.gyroInputs.all { id ->
		when(id)
		{
			GyroInputs.STICK_R -> stickMoved(state.rightX, state.rightY)
			GyroInputs.STICK_L -> stickMoved(state.leftX, state.leftY)
			else -> PadButton.from(id)?.let { buttonHeld(state, it.button) } ?: false
		}
	}

	/** [held] with the little delay on letting go when a stick is among the inputs */
	private fun heldWithDelay(held: Boolean): Boolean
	{
		val now = clock()
		if(held)
		{
			lastHeldAt = now
			return true
		}
		return settings.gyroInputs.any { GyroInputs.isStick(it) } && now - lastHeldAt < RELEASE_DELAY_MS
	}

	/**
	 * [state] (what the thumbs and real controllers do) with the gyroscope's push added to the stick it
	 * moves, and the controller's own gyroscope quiet meanwhile if the options say so. Changes [state]
	 * itself and returns it.
	 */
	fun process(state: ControllerState): ControllerState
	{
		val s = settings
		if(!s.gyroEnabled || !engage(heldWithDelay(inputsHeld(state))))
			return state
		// added to what the thumb or a real stick does: the gyroscope fine-tunes it
		val max = Short.MAX_VALUE.toInt()
		fun add(base: Short, v: Float) = (base + (v * max).toInt()).coerceIn(-max, max).toShort()
		if(s.gyroSide == Side.R)
		{
			state.rightX = add(state.rightX, outX)
			state.rightY = add(state.rightY, outY)
		}
		else
		{
			state.leftX = add(state.leftX, outX)
			state.leftY = add(state.leftY, outY)
		}
		if(s.gyroMuteNative)
		{
			state.gyroX = 0f; state.gyroY = 0f; state.gyroZ = 0f
			state.accelX = 0f; state.accelY = 1f; state.accelZ = 0f
			state.orientX = 0f; state.orientY = 0f; state.orientZ = 0f; state.orientW = 1f
		}
		return state
	}

	/** dead zone (of the full range), then the response curve */
	private fun shape(v: Float): Float
	{
		val dz = settings.gyroDeadzone
		val a = abs(v)
		if(a <= dz)
			return 0f
		val n = min(1f, (a - dz) / (1f - dz)).pow(settings.gyroCurve)
		return if(v < 0f) -n else n
	}
}
