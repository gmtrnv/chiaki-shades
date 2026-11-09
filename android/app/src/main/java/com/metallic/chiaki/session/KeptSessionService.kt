// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.stream.StreamActivity

/**
 * Keeps the app alive while a stream is paused in the background (quick reconnect, see
 * [KeptSession]) and shows it: "Session paused · closes in 0:42", with Back and Close.
 */
class KeptSessionService: Service()
{
	companion object
	{
		private const val CHANNEL_ID = "kept_session"
		private const val NOTIFICATION_ID = 1
		private const val ACTION_CLOSE = "com.metallic.chiaki.KEPT_SESSION_CLOSE"
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
	{
		if(intent?.action == ACTION_CLOSE)
		{
			KeptSession.end() // stops this service too
			stopSelf()
			return START_NOT_STICKY
		}
		val info = KeptSession.info
		if(info == null)
		{
			stopSelf()
			return START_NOT_STICKY
		}
		val notification = buildNotification(info)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
		else
			startForeground(NOTIFICATION_ID, notification)
		return START_NOT_STICKY
	}

	/** The app swiped away from recents: say goodbye to the console instead of leaving it waiting */
	override fun onTaskRemoved(rootIntent: Intent?)
	{
		super.onTaskRemoved(rootIntent)
		KeptSession.end()
		stopSelf()
	}

	private fun buildNotification(info: KeptSession.Info): android.app.Notification
	{
		val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
				getString(R.string.notification_channel_kept_session), NotificationManager.IMPORTANCE_LOW))

		val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		// Back: with the stream screen still there (left with Home) bring the app to front as it
		// was; otherwise open the stream again over the console list
		val back = if(info.screenAlive)
		{
			val launch = packageManager.getLaunchIntentForPackage(packageName)
				?: Intent(this, MainActivity::class.java)
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
			PendingIntent.getActivity(this, 0, launch, flags)
		}
		else
		{
			val main = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
			val stream = Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_CONNECT_INFO, info.connectInfo)
			PendingIntent.getActivities(this, 0, arrayOf(main, stream), flags)
		}
		val close = PendingIntent.getService(this, 1, Intent(this, KeptSessionService::class.java).setAction(ACTION_CLOSE), flags)

		val remainingMs = (info.endsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_console_simple)
			.setContentTitle(getString(R.string.notification_kept_session_title))
			.setContentText(getString(if(info.connectInfo.ps5) R.string.notification_kept_session_text_ps5 else R.string.notification_kept_session_text_ps4))
			.setWhen(System.currentTimeMillis() + remainingMs)
			.setUsesChronometer(true)
			.setChronometerCountDown(true)
			.setShowWhen(true)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(NotificationCompat.CATEGORY_SERVICE)
			.setContentIntent(back)
			.addAction(0, getString(R.string.notification_kept_session_back), back)
			.addAction(0, getString(R.string.notification_kept_session_close), close)
			.build()
	}
}
