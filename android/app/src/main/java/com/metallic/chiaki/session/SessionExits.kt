// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The last time a connected session ended, so the console list can show the console letting
 * it go ("disconnecting") or going to rest ("suspending") on its card. It may happen while the
 * list is already on screen (a paused session running out of time), hence the [listener].
 */
object SessionExits
{
	class Exit(
		/** identifies the registered console (its rp_regist_key) */
		val registKey: ByteArray,
		/** left with "Suspend and quit" */
		val suspended: Boolean,
		val atMs: Long = SystemClock.elapsedRealtime())

	private val handler = Handler(Looper.getMainLooper())
	@Volatile private var last: Exit? = null

	/** Set while the console list is on screen: gets each exit as it happens (main thread) */
	var listener: ((Exit) -> Unit)? = null
		set(value)
		{
			field = value
			if(value != null)
				take()?.let(value)
		}

	fun report(exit: Exit)
	{
		last = exit
		handler.post { listener?.let { l -> take()?.let(l) } }
	}

	/** The exit not shown yet, if any (each exit is shown once) */
	fun take(): Exit?
	{
		val exit = last
		last = null
		return exit
	}
}
