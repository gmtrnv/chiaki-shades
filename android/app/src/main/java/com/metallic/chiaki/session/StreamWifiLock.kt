// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps the WiFi radio out of its power saving while a stream runs.
 *
 * Without it Android may let the radio doze between bursts, and the video, which arrives as a burst
 * of packets for every frame, comes in later and more bunched up. Apps that stream video hold this
 * lock; the fork never did. From Android 10 there is a mode made for this ("low latency"); older
 * versions get the older "high performance" one. Both only act while the app is in the foreground.
 */
class StreamWifiLock(context: Context)
{
	private val lock: WifiManager.WifiLock? = try
	{
		val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
		val mode = if(Build.VERSION.SDK_INT >= 29)
			WifiManager.WIFI_MODE_FULL_LOW_LATENCY
		else
			@Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
		wifi?.createWifiLock(mode, "ChiakiShades:stream")?.apply { setReferenceCounted(false) }
	}
	catch(e: Throwable) { null }

	/** @return a short text for the log: what happened */
	fun acquire(): String
	{
		val lock = lock ?: return "WIFILOCK not available"
		return try
		{
			if(!lock.isHeld)
				lock.acquire()
			"WIFILOCK held=${lock.isHeld} mode=${if(Build.VERSION.SDK_INT >= 29) "low-latency" else "high-perf"}"
		}
		catch(e: Throwable)
		{
			"WIFILOCK failed: ${e.javaClass.simpleName} ${e.message}"
		}
	}

	fun release()
	{
		try
		{
			if(lock?.isHeld == true)
				lock.release()
		}
		catch(e: Throwable) { }
	}
}
