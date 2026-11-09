// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice
import com.metallic.chiaki.common.Preferences
import kotlin.math.abs
import kotlin.math.max

/**
 * The console's rumble (two motors, 0..255 each) on this device's motor and/or the controller's
 * (2.6.0-rc4). What the fork did, measured on a handheld with a simple motor and a PS4 game:
 * - it averaged the two motors, so a game using only one came out at half strength;
 * - every message restarted the motor and the next one stopped it: pulses of ~65 ms, which a
 *   simple motor (no strength control, it needs a moment to spin up) barely feels;
 * - it vibrated as "touch feedback", gone if the system's touch vibration is off;
 * - each vibration lasted 1 s at most, so a long rumble stopped by itself.
 * Here: the stronger motor (or each motor on a controller that has two), a minimum pulse,
 * vibration of a game (media), and a long vibration kept alive while the console asks for it.
 *
 * A simple motor (no strength control, common on handhelds) ran every value at full power: the
 * soft background hum of PS5 haptics (7–18 %) became a strong shake for seconds. Now the weakest
 * values are not sent, every vibration starts fully on (a short hit stays a hit) and one that goes
 * on turns into a rhythm of on and off as strong as asked for (a part of every [DUTY_PERIOD_MS]).
 */
class Rumble(context: Context, preferences: Preferences, private val controllerId: () -> Int)
{
	enum class Target(val value: String)
	{
		/** the controller if it can vibrate, else this device */
		AUTO("auto"),
		DEVICE("device"),
		CONTROLLER("controller"),
		BOTH("both");

		companion object
		{
			fun fromValue(value: String?) = values().firstOrNull { it.value == value } ?: AUTO
		}
	}

	companion object
	{
		const val STRENGTH_MIN = 10
		const val STRENGTH_MAX = 200
		const val STRENGTH_DEFAULT = 100

		/**
		 * The slider (in %) as a multiplier. A motor is not linear either: it needs a push to spin, and
		 * once it spins it is felt well, so as a straight line (rc4) 10 % already felt like 70 % on a
		 * test handheld. Squared up to 100 % (2.6.5): the low half of the slider gets the room. Above, a boost.
		 */
		fun factor(strength: Int): Float
		{
			val s = strength / 100f
			return if(s <= 1f) s * s else s
		}

		/** below this (after the strength) the motor stops: the console's tiny values are not felt anyway */
		private const val MIN_AMPLITUDE = 8
		/** a smaller change of strength does not restart a motor that can change it */
		private const val AMPLITUDE_STEP = 12
		/** a pulse lasts at least this long, so a simple motor gets to spin */
		private const val MIN_PULSE_MS = 100L

		// A simple motor. Pulsing it from the start made short hits (~80 ms shots in a PS4 game) nearly
		// vanish: it never got to spin. So every vibration starts fully on for KICK_MS; only one that
		// goes on longer turns into the rhythm (on for a part of each period, as much as asked for).
		/** weaker than this (after the strength) it is not sent: the background hum of PS5 haptics */
		private const val SIMPLE_MIN_AMPLITUDE = 40
		private const val KICK_MS = 100L
		private const val DUTY_PERIOD_MS = 80L
		/** on for at least this in each period, or the motor does not spin at all */
		private const val DUTY_MIN_ON_MS = 25L
		/** the rhythm changes only for a clear change, and not more often than this */
		private const val DUTY_STEP = 24
		private const val DUTY_RESTART_MS = 150L
		/** each vibration asked for; renewed while the console keeps asking (it only sends changes) */
		private const val SHOT_MS = 3000L
		private const val KEEP_ALIVE_MS = 2500L
		/** no message from the console for so long: the vibration is not renewed (a lost "stop") */
		private const val STALE_MS = 15000L
	}

	private class Motor(val vibrator: Vibrator)
	{
		val amplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
		var on = false
		var amplitude = 0
		var onSince = 0L
		var startedAt = 0L
		var pendingOff: Runnable? = null
		/** a simple motor past its first fully-on moment: running in the rhythm */
		var sustained = false
		var kick: Runnable? = null
	}

	private val handler = Handler(Looper.getMainLooper())
	private val target = preferences.rumbleTarget

	/** in %, changed live from the stream's sound and vibration panel */
	var strength = preferences.rumbleStrength

	/** off: nothing vibrates (changed live from the same panel, and by the bar's total silence) */
	var enabled = preferences.rumbleEnabled && !preferences.streamSilenced
		set(value)
		{
			field = value
			if(!value)
				stop()
		}

	@Suppress("DEPRECATION")
	private val deviceMotor = (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
		?.takeIf { it.hasVibrator() }?.let { Motor(it) }

	private var padMotors: List<Motor> = emptyList()
	private var padMotorsOf = Int.MIN_VALUE
	private var lastMessage = 0L

	private val keepAlive = object: Runnable
	{
		override fun run()
		{
			val now = SystemClock.uptimeMillis()
			if(now - lastMessage > STALE_MS)
			{
				allMotors().forEach { off(it) }
				return
			}
			allMotors().filter { it.on }.forEach { vibrate(it) }
			handler.postDelayed(this, KEEP_ALIVE_MS)
		}
	}

	/** a rumble message from the console (main thread) */
	fun set(left: Int, right: Int)
	{
		if(!enabled)
			return
		lastMessage = SystemClock.uptimeMillis()
		val pad = controllerMotors()
		val strongest = max(left, right)
		val usePad = target != Target.DEVICE && pad.isNotEmpty()
		val useDevice = when(target)
		{
			Target.DEVICE, Target.BOTH -> true
			Target.AUTO -> pad.isEmpty()
			Target.CONTROLLER -> false
		}
		if(usePad)
		{
			// a controller with two motors: left = the strong one, right = the light one, as on the DualShock
			if(pad.size >= 2)
				pad.forEachIndexed { i, motor -> drive(motor, if(i == 0) left else if(i == 1) right else strongest) }
			else
				drive(pad[0], strongest)
		}
		if(useDevice)
			deviceMotor?.let { drive(it, strongest) }
	}

	/** everything off at once (leaving the stream) */
	fun stop()
	{
		handler.removeCallbacksAndMessages(null)
		allMotors().forEach {
			it.pendingOff = null
			it.kick = null
			it.sustained = false
			if(it.on)
				it.vibrator.cancel()
			it.on = false
		}
	}

	private fun allMotors() = listOfNotNull(deviceMotor) + padMotors

	/** the motors of the controller in use (the last one that sent a key or a stick), looked up when it changes */
	private fun controllerMotors(): List<Motor>
	{
		val id = controllerId()
		if(id == padMotorsOf)
			return padMotors
		padMotors.forEach { off(it) }
		padMotorsOf = id
		val device = if(id >= 0) InputDevice.getDevice(id) else null
		padMotors = when
		{
			device == null -> emptyList()
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
				device.vibratorManager.vibratorIds.map { Motor(device.vibratorManager.getVibrator(it)) }
			else ->
				@Suppress("DEPRECATION")
				listOfNotNull(device.vibrator.takeIf { it.hasVibrator() }?.let { Motor(it) })
		}
		return padMotors
	}

	private fun drive(motor: Motor, value: Int)
	{
		val amplitude = (value * factor(strength)).toInt().coerceAtMost(255)
		val now = SystemClock.uptimeMillis()
		val tooWeak = amplitude < (if(motor.amplitudeControl) MIN_AMPLITUDE else SIMPLE_MIN_AMPLITUDE)
		if(tooWeak)
		{
			if(motor.on && motor.pendingOff == null)
			{
				// a pulse too short to be felt is stretched to the minimum
				val left = MIN_PULSE_MS - (now - motor.onSince)
				if(left <= 0)
					off(motor)
				else
					Runnable { motor.pendingOff = null; off(motor) }.also {
						motor.pendingOff = it
						handler.postDelayed(it, left)
					}
			}
			return
		}
		motor.pendingOff?.let { handler.removeCallbacks(it); motor.pendingOff = null }
		if(motor.on)
		{
			val change = abs(amplitude - motor.amplitude)
			if(motor.amplitudeControl)
			{
				// changed only for a real change
				if(change < AMPLITUDE_STEP)
					return
			}
			else if(!motor.sustained)
			{
				// still fully on: the rhythm starts with the latest value
				motor.amplitude = amplitude
				return
			}
			// the rhythm restarts on a change, so only a clear one and not too often
			else if(change < DUTY_STEP || now - motor.startedAt < DUTY_RESTART_MS)
				return
		}
		else
		{
			motor.onSince = now
			motor.sustained = false
			if(!motor.amplitudeControl)
				Runnable {
					motor.kick = null
					if(motor.on)
					{
						motor.sustained = true
						motor.startedAt = SystemClock.uptimeMillis()
						vibrate(motor)
					}
				}.also {
					motor.kick = it
					handler.postDelayed(it, KICK_MS)
				}
		}
		motor.on = true
		motor.amplitude = amplitude
		motor.startedAt = now
		vibrate(motor)
		handler.removeCallbacks(keepAlive)
		handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
	}

	private fun off(motor: Motor)
	{
		motor.pendingOff?.let { handler.removeCallbacks(it); motor.pendingOff = null }
		motor.kick?.let { handler.removeCallbacks(it); motor.kick = null }
		motor.sustained = false
		if(!motor.on)
			return
		motor.on = false
		motor.vibrator.cancel()
	}

	/** how long a simple motor is on in each period for [amplitude] */
	private fun dutyOnMs(amplitude: Int) = (DUTY_PERIOD_MS * amplitude / 255).coerceAtLeast(DUTY_MIN_ON_MS)

	private fun vibrate(motor: Motor)
	{
		val v = motor.vibrator
		val amplitude = motor.amplitude
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
		{
			@Suppress("DEPRECATION")
			v.vibrate(SHOT_MS)
			return
		}
		val onMs = dutyOnMs(amplitude)
		val effect = when
		{
			motor.amplitudeControl -> VibrationEffect.createOneShot(SHOT_MS, amplitude)
			// a simple motor's first moment, or nearly always on: simply on
			!motor.sustained -> VibrationEffect.createOneShot(SHOT_MS, VibrationEffect.DEFAULT_AMPLITUDE)
			onMs >= DUTY_PERIOD_MS - 6 -> VibrationEffect.createOneShot(SHOT_MS, VibrationEffect.DEFAULT_AMPLITUDE)
			// on / off over and over (it repeats from the second step) until stopped or renewed
			else -> VibrationEffect.createWaveform(longArrayOf(0, onMs, DUTY_PERIOD_MS - onMs), 1)
		}
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
		else
			@Suppress("DEPRECATION")
			v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
	}
}
