// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.lib.ConnectInfo

/**
 * Quick reconnect (Settings, off by default): a stream that leaves the screen stays connected for
 * a while (for the console we never left), so coming back to it is instant. A foreground service
 * shows it in a notification (and keeps Android from freezing the app meanwhile). When the time
 * is up, or on "Close", the session ends cleanly.
 *
 * Only touched from the main thread.
 */
object KeptSession
{
	private const val TAG = "KeptSession"
	private const val TRAFFIC_LOG_MS = 10_000L
	private const val WATCHDOG_MS = 2000L

	class Info(
		val connectInfo: ConnectInfo,
		/** when it ends by itself (elapsedRealtime) */
		val endsAtMs: Long,
		/** the stream screen is still there (left with Home): coming back means bringing it to front */
		val screenAlive: Boolean)

	private val handler = Handler(Looper.getMainLooper())
	private var kept: StreamSession? = null
	private var keptAtMs = 0L
	private var appContext: Context? = null

	private val stateData = MutableLiveData<Info?>(null)
	/** The session kept right now, if any (for the console's card and the notification) */
	val state: LiveData<Info?> get() = stateData
	val info: Info? get() = stateData.value

	private val expire = Runnable {
		Log.i(TAG, "Time is up after ${SystemClock.elapsedRealtime() - keptAtMs} ms: ending the session")
		end()
	}

	/** A kept session whose connection died (Wi-Fi lost…) is ended, not left as a zombie */
	private val watchdog = object: Runnable
	{
		override fun run()
		{
			val session = kept ?: return
			if(!isAlive(session))
			{
				Log.w(TAG, "No pictures for ${SystemClock.elapsedRealtime() - session.lastPictureMs} ms: the connection is gone, ending the session")
				end()
				return
			}
			handler.postDelayed(this, WATCHDOG_MS)
		}
	}

	private fun isAlive(session: StreamSession) = session.receivingPictures

	private var lastRxBytes = 0L
	private var lastRxMs = 0L
	private val trafficLog = object: Runnable
	{
		override fun run()
		{
			val rx = TrafficStats.getUidRxBytes(Process.myUid())
			val now = SystemClock.elapsedRealtime()
			if(lastRxMs != 0L && rx >= 0)
			{
				val mbps = (rx - lastRxBytes) * 8.0 / 1_000_000.0 / ((now - lastRxMs) / 1000.0)
				Log.i(TAG, "Receiving %.2f Mbit/s while kept".format(mbps))
			}
			lastRxBytes = rx
			lastRxMs = now
			handler.postDelayed(this, TRAFFIC_LOG_MS)
		}
	}

	/** @return false if the session could not be kept (not connected): it should be ended as usual */
	fun keep(context: Context, session: StreamSession, durationMs: Long, pressPs: Boolean, screenAlive: Boolean): Boolean
	{
		if(kept !== session)
			end()
		if(!session.keepInBackground(pressPs))
			return false
		kept = session
		keptAtMs = SystemClock.elapsedRealtime()
		session.onQuitWhileKept = { handler.post { if(kept === session) end() } }
		handler.removeCallbacks(expire)
		handler.postDelayed(expire, durationMs)
		lastRxMs = 0L
		handler.removeCallbacks(trafficLog)
		handler.post(trafficLog)
		handler.removeCallbacks(watchdog)
		handler.postDelayed(watchdog, WATCHDOG_MS)
		stateData.value = Info(session.connectInfo, keptAtMs + durationMs, screenAlive)

		val app = context.applicationContext
		appContext = app
		try
		{
			ContextCompat.startForegroundService(app, Intent(app, KeptSessionService::class.java))
		}
		catch(e: Exception)
		{
			// e.g. not allowed from the background: the session is still kept, just without notification
			Log.e(TAG, "Could not start the service", e)
		}
		return true
	}

	/** The kept session for this console, if any: it is no longer kept, the caller owns it */
	fun take(connectInfo: ConnectInfo): StreamSession?
	{
		val session = kept ?: return null
		if(!session.connectInfo.registKey.contentEquals(connectInfo.registKey))
		{
			end() // another console: this one must let the console go
			return null
		}
		// a dead connection (not caught by the watchdog yet) must not come back as a black screen
		if(!isAlive(session))
		{
			Log.w(TAG, "The kept session stopped getting pictures: starting a new one instead")
			end()
			return null
		}
		Log.i(TAG, "Taking the kept session back after ${SystemClock.elapsedRealtime() - keptAtMs} ms")
		release()
		return session
	}

	fun holds(session: StreamSession) = kept === session

	/** Ends the kept session now, saying goodbye to the console */
	fun end()
	{
		val session = kept
		release()
		session?.shutdown()
	}

	private fun release()
	{
		handler.removeCallbacks(expire)
		handler.removeCallbacks(trafficLog)
		handler.removeCallbacks(watchdog)
		kept?.onQuitWhileKept = null
		kept = null
		if(stateData.value != null)
			stateData.value = null
		appContext?.let { it.stopService(Intent(it, KeptSessionService::class.java)) }
	}
}
