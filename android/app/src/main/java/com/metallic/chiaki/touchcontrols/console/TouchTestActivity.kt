// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.common.GameplayToast
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.databinding.ActivityTouchTestBinding
import com.metallic.chiaki.lib.ControllerState

/**
 * Settings → "Try the touch pad": the console-mode pad over a pretend game, its options and
 * the layout editor (the same editor as over the live game). What is left here is what the stream uses.
 */
class TouchTestActivity : AppCompatActivity()
{
	companion object
	{
		const val KEY_BARS_BOTTOM = PadEditor.KEY_BARS_BOTTOM
	}

	private lateinit var binding: ActivityTouchTestBinding
	private lateinit var editor: PadEditor
	private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		useWholeScreen(window)
		binding = ActivityTouchTestBinding.inflate(layoutInflater)
		setContentView(binding.root)

		gyro = GyroStick(binding.pad.settings)
		gyro.onPausedChanged = { binding.pad.gyroOn = !gyro.paused; refreshGame() }
		binding.pad.gyroOn = !gyro.paused
		binding.pad.onGyroToggle = { gyro.toggle() }
		binding.pad.onRecenter = { gyro.recenter(); GameplayToast.show(this, R.string.gyro_recentered) }
		binding.pad.stateListener = { padState = it; refreshGame() }
		// the options panel changed something: the gyroscope stick reads it (the pad's own settings are the same object)
		binding.pad.onSettingsReloaded = { gripFlat = Preferences(this).motionGripFlat; gyro.reloadPaused(); updateSensors(); refreshGame() }
		// with cinematic immersion the pretend game glides to fill the screen while the controls are hidden
		binding.fakeGame.videoRectProvider = { cinemaRect() }
		binding.pad.onLayoutChanged = { binding.fakeGame.invalidate(); binding.shell.invalidate() }
		binding.shell.pad = binding.pad
		binding.pad.excludeEdgeGestures = com.metallic.chiaki.common.Preferences(this).gameMode
		// the shell fades out with the controls when they go untouched (cutscenes): just the video on black
		binding.pad.onIdleChanged = { hidden ->
			binding.shell.animate().cancel()
			binding.shell.animate().alpha(if(hidden) 0f else 1f).setDuration(if(hidden) 700L else 120L).start()
			cinema(hidden)
		}
		binding.shell.theme = ShellTheme.load(prefs)
		binding.shell.opacity = binding.pad.settings.shellOpacity
		// trying the pad: see where the red and green zones are (a switch in the options hides them)
		binding.pad.showZones = true

		editor = PadEditor(this, binding.editor, binding.pad, binding.shell, testScreen = true,
			extraBars = listOf(binding.toolbar), extraMoveButtons = listOf(binding.toolbarMoveButton))
		editor.onEditingChanged = { editing -> binding.toolbar.isVisible = !editing }
		editor.gyroRecenter = { gyro.recenter() }

		binding.settingsButton.setOnClickListener { editor.showSettings(!editor.panelOpen) }
		binding.editButton.setOnClickListener { editor.startEditing() }
		binding.closeButton.setOnClickListener { finish() }

		onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true)
		{
			override fun handleOnBackPressed()
			{
				if(!editor.handleBack())
					finish()
			}
		})
	}

	// ---------------------------------------------------------------- the gyroscope as a stick

	private lateinit var gyro: GyroStick
	private var padState = ControllerState()
	private val accel = floatArrayOf(0f, 1f, 0f)
	private val axes = FloatArray(3)
	private var lastTimestamp = 0L
	private var sensorsOn = false
	private var gripFlat = false

	/** the pretend game gets what the pad does plus the gyroscope's push, as the stream would send it */
	private fun refreshGame()
	{
		val s = binding.pad.settings
		if(!s.gyroEnabled)
		{
			binding.fakeGame.gyroHud = ""
			binding.fakeGame.state = padState
			return
		}
		val state = gyro.process(padState.copy())
		val button = GyroInputsDialog.summary(this, s.gyroInputs)
		binding.fakeGame.gyroHud = when
		{
			gyro.paused && s.gyroActivation == GyroActivation.TOGGLE -> getString(R.string.gyro_hud_toggle_off, button)
			gyro.paused -> getString(R.string.gyro_hud_off)
			gyro.engaged -> getString(R.string.gyro_hud_on, gyro.outX, gyro.outY)
			s.gyroActivation == GyroActivation.CLUTCH -> getString(R.string.gyro_hud_clutch, button)
			else -> getString(R.string.gyro_hud_waiting, button)
		}
		binding.fakeGame.state = state
	}

	private val sensorListener = object : SensorEventListener
	{
		override fun onSensorChanged(event: SensorEvent)
		{
			val v = event.values
			@Suppress("DEPRECATION")
			val rotation = windowManager.defaultDisplay.rotation
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					val g = SensorManager.GRAVITY_EARTH
					sensorToController(rotation, gripFlat, v[0] / g, v[1] / g, v[2] / g, axes)
					accel[0] = axes[0]; accel[1] = axes[1]; accel[2] = axes[2]
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorToController(rotation, gripFlat, v[0], v[1], v[2], axes)
					val dt = if(lastTimestamp == 0L) 0f else ((event.timestamp - lastTimestamp) / 1e9f).coerceIn(0f, 0.1f)
					lastTimestamp = event.timestamp
					gyro.feed(axes[0], axes[1], axes[2], accel[0], accel[1], accel[2], dt)
					refreshGame()
				}
			}
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	/**
	 * The screen is in front: set by onResume / onPause themselves. The lifecycle's own state cannot tell:
	 * inside onResume it is not RESUMED yet, so coming back to this screen never started the sensors
	 * (they only started when an option was touched), and the gyroscope stick looked dead.
	 */
	private var resumed = false

	/** the sensors run while the screen is open and the option is on */
	private fun updateSensors()
	{
		val want = binding.pad.settings.gyroEnabled && resumed
		if(want == sensorsOn)
			return
		sensorsOn = want
		val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
		if(!want)
		{
			manager.unregisterListener(sensorListener)
			return
		}
		gripFlat = Preferences(this).motionGripFlat
		lastTimestamp = 0L
		listOfNotNull(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE))
			.forEach { manager.registerListener(sensorListener, it, 8000) }
	}

	// ---------------------------------------------------------------- cinematic immersion

	/** 0 = the video in its place, 1 = filling the screen as the immersion's mode does */
	private var cinemaProgress = 0f
	private var cinemaAnimator: android.animation.ValueAnimator? = null

	private fun cinemaRect(): RectF4
	{
		val v = binding.pad.videoRect
		val mode = binding.pad.settings.cinema
		if(cinemaProgress <= 0f || mode == CinemaMode.OFF)
			return v
		val t = mode.target(binding.pad.geometry.width, binding.pad.geometry.height, 16f / 9f)
		val p = cinemaProgress
		fun lerp(a: Float, b: Float) = a + (b - a) * p
		return RectF4(lerp(v.x, t.x), lerp(v.y, t.y), lerp(v.w, t.w), lerp(v.h, t.h))
	}

	private fun cinema(on: Boolean)
	{
		val target = if(on && binding.pad.settings.cinema != CinemaMode.OFF) 1f else 0f
		if(target == cinemaProgress && cinemaAnimator == null)
			return
		cinemaAnimator?.cancel()
		cinemaAnimator = android.animation.ValueAnimator.ofFloat(cinemaProgress, target).apply {
			duration = if(on) 700L else 350L
			interpolator = android.view.animation.AccelerateDecelerateInterpolator()
			addUpdateListener { cinemaProgress = it.animatedValue as Float; binding.fakeGame.invalidate() }
			addListener(object : android.animation.AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: android.animation.Animator) { if(cinemaAnimator === animation) cinemaAnimator = null }
			})
			start()
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		resumed = true
		updateSensors()
		refreshGame()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	override fun onPause()
	{
		binding.pad.cancelAll()
		resumed = false
		updateSensors()
		super.onPause()
	}

	@Suppress("DEPRECATION")
	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}
}
