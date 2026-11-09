// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.metallic.chiaki.common.Preferences

/**
 * "Do not disturb while playing" (phase 13): the phone's own Do not disturb (priority only: the
 * user's exceptions, like calls or alarms, still come through) while the stream is on screen, and
 * back as it was when it leaves. Only if the user had it off, and only undone if it is still ours.
 */
object StreamDnd
{
	/** we turned it on and have not undone it yet (kept on disk: the app may be killed mid-game) */
	private const val KEY_ACTIVE = "stream_dnd_active"

	private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

	fun granted(context: Context?) = context != null && manager(context)?.isNotificationPolicyAccessGranted == true

	fun start(context: Context)
	{
		val prefs = Preferences(context)
		if(!prefs.streamDnd || !granted(context))
			return
		val nm = manager(context) ?: return
		try
		{
			if(nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
			{
				nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
				prefs.sharedPreferences.edit().putBoolean(KEY_ACTIVE, true).apply()
			}
		}
		catch(e: Exception) { Log.w("StreamDnd", "Could not turn Do not disturb on", e) }
	}

	/** undo it, if it was ours (also called when the app opens, in case it was killed mid-game) */
	fun stop(context: Context)
	{
		val prefs = Preferences(context)
		if(!prefs.sharedPreferences.getBoolean(KEY_ACTIVE, false))
			return
		prefs.sharedPreferences.edit().putBoolean(KEY_ACTIVE, false).apply()
		if(!granted(context))
			return
		val nm = manager(context) ?: return
		try
		{
			// the user may have changed it meanwhile: then it is theirs
			if(nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY)
				nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
		}
		catch(e: Exception) { Log.w("StreamDnd", "Could not turn Do not disturb off", e) }
	}
}
