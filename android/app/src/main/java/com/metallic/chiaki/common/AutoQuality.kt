// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import com.metallic.chiaki.R
import com.metallic.chiaki.discovery.RttMeter
import kotlin.math.max
import kotlin.math.min

/**
 * The Auto quality profile. What the user wants is the resolution (one, or Auto); what nobody knows
 * beforehand is what the network, the battery and the heat allow, so:
 * - the bitrate always comes from the network (where the console is, Wi-Fi signal, how fast and
 *   steadily the console answers, see [RttMeter]), inside a range of each resolution;
 * - with the resolution on Auto it also comes down with low battery, heat or a very bad network,
 *   and the console is offered one resolution lower too, so it can step down by itself mid-game;
 * - a game that went badly makes the next connection start one step lower, only within the same
 *   sitting (app open and less than 30 min since that game ended): no habits are learned.
 * Everything here is free to compute, so the cards show it all the time and what they show is
 * exactly what the connection uses.
 */
object AutoQuality
{
	/** the user's resolution in the Auto profile: "auto" or a Preferences.Resolution value */
	const val KEY_RESOLUTION = "quality_auto_resolution"
	const val RESOLUTION_AUTO = "auto"

	/** Mbit/s each resolution may use in Auto: lowest, where it starts without knowing more, highest */
	class Range(val floor: Int, val start: Int, val ceiling: Int)

	val ranges = mapOf(
		Preferences.Resolution.RES_1080P to Range(15, 25, 40),
		Preferences.Resolution.RES_720P to Range(8, 15, 20),
		Preferences.Resolution.RES_540P to Range(5, 8, 10),
		Preferences.Resolution.RES_360P to Range(3, 4, 5))

	/** The network's mark: 3 excellent, 2 good, 1 fair (or not known yet), 0 poor; below 0 after penalties */
	private const val GRADE_UNKNOWN_CAP = 1

	/** A short reason for the decision, shown in the quality sheet */
	class Reason(@StringRes val text: Int, val arg: Any? = null)

	data class Decision(
		val resolution: Preferences.Resolution,
		val bitrateMbps: Int,
		/** the console may step down to lower resolutions by itself */
		val allowLower: Boolean,
		/** the console's answers are not known yet (asleep or just found): a guess, shown with "~" */
		val guess: Boolean,
		val reasons: List<Reason>)

	/** What the device says right now (cheap: no waiting, no permissions) */
	class Device(
		val transport: Transport,
		/** Wi-Fi signal, dBm (null = not Wi-Fi or not known) */
		val signalDbm: Int?,
		val batteryPercent: Int?,
		val charging: Boolean,
		/** PowerManager.THERMAL_STATUS_*, 0 = none */
		val thermal: Int,
		/** shorter side of the screen, px */
		val screenShortSide: Int)

	enum class Transport { WIFI, ETHERNET, CELLULAR, OTHER }

	fun device(context: Context): Device
	{
		val app = context.applicationContext
		var transport = Transport.OTHER
		var signal: Int? = null
		try
		{
			val cm = app.getSystemService(ConnectivityManager::class.java)
			val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
			if(caps != null)
			{
				transport = when
				{
					caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
					caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
					caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
					else -> Transport.OTHER
				}
				if(Build.VERSION.SDK_INT >= 29 && transport == Transport.WIFI)
					signal = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it < 0 }
			}
			// a VPN hides the network under it: ask Wi-Fi itself
			if(transport == Transport.OTHER || (transport == Transport.WIFI && signal == null))
			{
				@Suppress("DEPRECATION")
				val info = app.getSystemService(WifiManager::class.java)?.connectionInfo
				@Suppress("DEPRECATION")
				if(info != null && info.networkId != -1 && info.rssi in -120..-1)
				{
					transport = Transport.WIFI
					signal = info.rssi
				}
			}
		}
		catch(e: Exception) { }

		val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
		val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
		val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
		val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
		val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
		val thermal = if(Build.VERSION.SDK_INT >= 29) app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0 else 0
		return Device(transport, signal, if(level >= 0 && scale > 0) level * 100 / scale else null, charging, thermal,
			screenShortSide(app))
	}

	/** The whole screen's shorter side in px (not the window: the game fills the screen, bars and all) */
	private fun screenShortSide(context: Context): Int
	{
		try
		{
			val wm = context.getSystemService(android.view.WindowManager::class.java)
			if(wm != null)
			{
				if(Build.VERSION.SDK_INT >= 30)
				{
					val bounds = wm.maximumWindowMetrics.bounds
					return min(bounds.width(), bounds.height())
				}
				val real = android.util.DisplayMetrics()
				@Suppress("DEPRECATION")
				wm.defaultDisplay.getRealMetrics(real)
				if(real.widthPixels > 0 && real.heightPixels > 0)
					return min(real.widthPixels, real.heightPixels)
			}
		}
		catch(e: Exception) { }
		val metrics = context.resources.displayMetrics
		return min(metrics.widthPixels, metrics.heightPixels)
	}

	/** The resolution the user chose in Auto, or null for Auto */
	fun userResolution(prefs: Preferences): Preferences.Resolution? =
		prefs.sharedPreferences.getString(KEY_RESOLUTION, RESOLUTION_AUTO).let { v ->
			Preferences.Resolution.values().firstOrNull { it.value == v } }

	fun setUserResolution(prefs: Preferences, resolution: Preferences.Resolution?) =
		prefs.sharedPreferences.edit().putString(KEY_RESOLUTION, resolution?.value ?: RESOLUTION_AUTO).apply()

	/**
	 * The highest resolution worth asking for on this screen, only with the resolution on Auto (a
	 * resolution the user fixed is always respected): the smallest one with about as many lines as
	 * the screen, or more (10 % short is fine: 720p on a 800 px screen, 720p on a 640 px screen).
	 * More gains nothing on a small screen: the console, the network and the decoder only work harder.
	 */
	fun maxResolution(device: Device): Preferences.Resolution =
		Preferences.Resolution.values() // lowest first
			.firstOrNull { it.value.removeSuffix("p").toInt() >= device.screenShortSide * 0.9 }
			?: Preferences.Resolution.RES_1080P

	fun decide(context: Context, prefs: Preferences, host: DisplayHost): Decision =
		decide(device(context), host.reach, RttMeter.stats(host.host), userResolution(prefs), Memory.penalty())

	fun decide(device: Device, reach: Reach, rtt: RttMeter.Stats?, chosen: Preferences.Resolution?, penalty: Int): Decision
	{
		val reasons = mutableListOf<Reason>()
		val remote = reach != Reach.HOME || device.transport == Transport.CELLULAR

		// where the console is and the link to it
		var grade: Int
		if(!remote)
		{
			reasons.add(Reason(R.string.auto_reason_home))
			grade = when
			{
				device.transport == Transport.ETHERNET -> 3
				device.signalDbm == null -> 2
				device.signalDbm >= -60 -> 3
				device.signalDbm >= -67 -> 2
				device.signalDbm >= -75 -> 1
				else -> 0
			}
			device.signalDbm?.let {
				reasons.add(Reason(when
				{
					it >= -60 -> R.string.auto_reason_signal_good
					it >= -67 -> R.string.auto_reason_signal_ok
					else -> R.string.auto_reason_signal_weak
				}, it))
			}
		}
		else
		{
			reasons.add(Reason(if(device.transport == Transport.CELLULAR) R.string.auto_reason_mobile else R.string.auto_reason_remote))
			grade = 1
		}

		// how the console answers: slow or unsteady takes away, very good gives a remote link a step
		val guess = rtt == null || rtt.samples < 3
		if(rtt != null)
		{
			reasons.add(Reason(R.string.auto_reason_rtt, rtt.medianMs))
			val bad = rtt.medianMs > 60 || rtt.spreadMs > 100 || rtt.lossShare > 0.2
			val meh = rtt.medianMs > 25 || rtt.spreadMs > 40 || rtt.lossShare > 0.1
			when
			{
				bad -> { grade -= 2; reasons.add(Reason(R.string.auto_reason_rtt_unsteady)) }
				meh -> { grade -= 1; reasons.add(Reason(R.string.auto_reason_rtt_unsteady)) }
				remote && rtt.medianMs <= 20 && rtt.spreadMs <= 20 -> grade += 1
			}
		}
		if(guess)
		{
			grade = min(grade, GRADE_UNKNOWN_CAP)
			reasons.add(Reason(R.string.auto_reason_guess))
		}
		grade = min(grade, if(remote) 2 else 3)

		// what the device can afford
		val lowBattery = device.batteryPercent != null && device.batteryPercent <= 20 && !device.charging
		val hot = device.thermal >= PowerManager.THERMAL_STATUS_MODERATE
		if(device.charging)
			reasons.add(Reason(R.string.auto_reason_charging))
		if(lowBattery)
		{
			reasons.add(Reason(R.string.auto_reason_battery_low, device.batteryPercent))
			grade -= 1
		}
		if(hot)
			reasons.add(Reason(R.string.auto_reason_hot))
		if(device.thermal >= PowerManager.THERMAL_STATUS_SEVERE)
			grade -= 1

		// a game that went badly a moment ago
		if(penalty > 0)
		{
			reasons.add(Reason(R.string.auto_reason_penalty))
			grade -= penalty
		}

		val all = Preferences.Resolution.values() // lowest first
		val resolution: Preferences.Resolution
		if(chosen != null)
			resolution = chosen
		else
		{
			var index = when
			{
				grade >= 2 -> all.indexOf(Preferences.Resolution.RES_1080P)
				grade == 1 -> all.indexOf(Preferences.Resolution.RES_720P)
				grade == 0 -> all.indexOf(Preferences.Resolution.RES_540P)
				else -> all.indexOf(Preferences.Resolution.RES_360P)
			}
			if(lowBattery) index--
			if(hot) index--
			val screenMax = maxResolution(device)
			if(index > all.indexOf(screenMax))
			{
				index = all.indexOf(screenMax)
				reasons.add(Reason(R.string.auto_reason_screen, device.screenShortSide))
			}
			resolution = all[max(0, index)]
		}

		val range = ranges.getValue(resolution)
		val bitrate = when
		{
			grade >= 3 -> range.ceiling
			grade == 2 -> (range.start + range.ceiling) / 2
			grade == 1 -> range.start
			else -> range.floor
		}
		return Decision(resolution, bitrate, chosen == null && resolution != Preferences.Resolution.RES_360P, guess, reasons)
	}

	/**
	 * How the last game went, for the next connection of the same sitting only. Lives in memory:
	 * closing the app forgets it, and so do 30 minutes without playing.
	 */
	object Memory
	{
		private const val FORGET_MS = 30 * 60 * 1000L
		private const val MAX_PENALTY = 3

		@Volatile private var penalty = 0
		@Volatile private var lastEndMs = 0L

		fun penalty(): Int =
			if(lastEndMs == 0L || SystemClock.elapsedRealtime() - lastEndMs > FORGET_MS) 0 else penalty

		/** [bad]: lost connection, an error, many losses or the console cut the bitrate a lot; [good]: long enough and clean */
		fun gameEnded(bad: Boolean, good: Boolean)
		{
			val now = penalty()
			penalty = when
			{
				bad -> min(now + 1, MAX_PENALTY)
				good -> 0
				else -> now
			}
			lastEndMs = SystemClock.elapsedRealtime()
		}
	}
}
