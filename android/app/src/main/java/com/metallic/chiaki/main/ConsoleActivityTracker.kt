// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.Reach
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.session.KeptSession
import com.metallic.chiaki.session.SessionExits

/**
 * What a console is going through right now, by registered host id, shown (and animated) on its card:
 * waking up, letting our last session go, or going to rest.
 *
 * Waking up: the console's answer to discovery says when it is really on (standby -> ready), so
 * there is no fixed wait: the wakeup packet is sent again every few seconds (a single UDP packet
 * can be lost) until the console answers "ready" or the time runs out. With discovery off nothing
 * can answer, so then a fixed time is assumed.
 *
 * Leaving and suspending are fixed times: the console needs a few seconds to let a session go
 * (~8 s measured) and some more to really be at rest. Nothing can be checked there; if the
 * console still is not free afterwards, connecting retries by itself. Going to rest, the PS5 also
 * drops off the network for a while near the end (seen: from ~25 s to somewhere under a minute):
 * if it is not seen when the time is up, the card goes on "falling asleep" until it answers
 * again, up to [SLEEP_EXTRA_MS] more, instead of saying "not visible" about a console that is fine.
 *
 * Only touched from the main thread.
 */
class ConsoleActivityTracker(private val sendWakeup: (host: String, registeredHost: RegisteredHost) -> Unit)
{
	enum class Phase
	{
		WAKING, READY, FAILED, LEAVING, SLEEPING,
		/** our session is kept in the background (quick reconnect): tapping goes back in at once */
		PAUSED;

		/** the card has its animation going */
		val animated get() = this != FAILED
	}

	data class Activity(
		val phase: Phase,
		/** when this phase began (elapsedRealtime) */
		val phaseStartMs: Long,
		/** when the whole activity began, for the seconds on the label */
		val startMs: Long = phaseStartMs,
		/** "wake and connect": connect by itself once the console is ready */
		val connect: Boolean = false,
		/** discovery was on, so readiness is checked instead of assumed */
		val verified: Boolean = false,
		/** PAUSED: when the kept session ends by itself (elapsedRealtime) */
		val endsAtMs: Long = 0)
	{
		fun elapsedSeconds(now: Long = SystemClock.elapsedRealtime()) = ((now - startMs) / 1000).toInt()
		fun remainingSeconds(now: Long = SystemClock.elapsedRealtime()) = ((endsAtMs - now + 999) / 1000).toInt().coerceAtLeast(0)

		/**
		 * Busy doing something that a tap must not interrupt (the card only says "wait").
		 * A wakeup can still be cancelled from the card's menu.
		 */
		val busy get() = phase != Phase.FAILED && phase != Phase.PAUSED && !(phase == Phase.READY && !connect)

		val cancellableWakeup get() = phase == Phase.WAKING || (phase == Phase.READY && connect)
	}

	/** [token] marks this console's posted runnables, so they can all be removed at once */
	private class Target(var address: String?, val registeredHost: RegisteredHost?, val token: Any = Any())

	private val handler = Handler(Looper.getMainLooper())
	private val activities = mutableMapOf<Long, Activity>()
	private val targets = mutableMapOf<Long, Target>()
	private var pendingExit: SessionExits.Exit? = null

	private val stateData = MutableLiveData<Map<Long, Activity>>(emptyMap())
	/** Changes every second while a console is busy, so the cards can show the time */
	val state: LiveData<Map<Long, Activity>> get() = stateData

	/** A console woken with "wake and connect" is ready (and had a moment to settle): connect to it */
	var onReadyToConnect: ((registeredHostId: Long) -> Unit)? = null

	operator fun get(registeredHostId: Long?) = registeredHostId?.let { activities[it] }

	fun startWakeup(host: DisplayHost, connect: Boolean)
	{
		val registeredHost = host.registeredHost ?: return
		val id = registeredHost.id
		cancel(id)
		val verified = host.reach != Reach.DISCOVERY_OFF
		activities[id] = Activity(Phase.WAKING, SystemClock.elapsedRealtime(), connect = connect, verified = verified)
		targets[id] = Target(host.host, registeredHost)
		Log.i(TAG, "Waking ${registeredHost.serverNickname} (connect=$connect, verified=$verified)")
		resend(id)
		later(id, if(verified) TIMEOUT_MS else UNVERIFIED_MS) { giveUp(id) }
		startTicking()
		publish()
	}

	/** A session just ended: shown on the card of its console once the list knows it */
	fun reportExit(exit: SessionExits.Exit)
	{
		pendingExit = exit
	}

	private var pendingKept: KeptSession.Info? = null

	/** The session kept in the background (quick reconnect) changed: its console's card shows it paused */
	fun setKept(info: KeptSession.Info?)
	{
		pendingKept = info
		for((id, activity) in activities.toMap())
			if(activity.phase == Phase.PAUSED)
				cancel(id)
	}

	fun cancel(registeredHostId: Long)
	{
		activities.remove(registeredHostId) ?: return
		targets.remove(registeredHostId)?.let { handler.removeCallbacksAndMessages(it.token) }
		publish()
	}

	/** Called with every new list of cards: a waking console that answers "ready" is awake */
	fun update(hosts: List<DisplayHost>)
	{
		lastHosts = hosts
		pendingExit?.let { exit ->
			val registeredHost = hosts.firstNotNullOfOrNull { host ->
				host.registeredHost?.takeIf { it.rpRegistKey.contentEquals(exit.registKey) }
			}
			if(registeredHost != null)
			{
				pendingExit = null
				startExit(registeredHost.id, exit)
			}
		}

		pendingKept?.let { kept ->
			val registeredHost = hosts.firstNotNullOfOrNull { host ->
				host.registeredHost?.takeIf { it.rpRegistKey.contentEquals(kept.connectInfo.registKey) }
			}
			if(registeredHost != null)
			{
				pendingKept = null
				val id = registeredHost.id
				cancel(id)
				activities[id] = Activity(Phase.PAUSED, SystemClock.elapsedRealtime(), endsAtMs = kept.endsAtMs)
				targets[id] = Target(null, null)
				startTicking()
				publish()
			}
		}

		for((id, activity) in activities.toMap())
		{
			if(activity.phase != Phase.WAKING)
				continue
			val matching = hosts.filter { it.registeredHost?.id == id }
			// the address may change while it boots (e.g. found on the local network)
			matching.firstOrNull()?.let { targets[id]?.address = it.host }
			if(matching.any { it.live?.state == DiscoveryHost.State.READY })
				ready(id)
		}

		// at rest and answering again after its time: asleep for real
		val now = SystemClock.elapsedRealtime()
		for((id, activity) in activities.toMap())
			if(activity.phase == Phase.SLEEPING && now - activity.startMs >= SLEEP_MS && isSeen(id))
				cancel(id)
	}

	private var lastHosts: List<DisplayHost> = listOf()

	/**
	 * The console answers discovery right now (on the local network or through its address), or
	 * there is no way to tell (discovery off, no card for it): nothing to wait for then.
	 */
	private fun isSeen(id: Long): Boolean
	{
		val cards = lastHosts.filter { it.registeredHost?.id == id }
		return cards.isEmpty() || cards.any { it.live != null || it.reach == Reach.DISCOVERY_OFF }
	}

	private fun startExit(id: Long, exit: SessionExits.Exit)
	{
		val maxMs = if(exit.suspended) SLEEP_MS + SLEEP_EXTRA_MS else LEAVE_MS
		if(exit.atMs + maxMs <= SystemClock.elapsedRealtime())
			return
		cancel(id)
		activities[id] = Activity(if(exit.suspended) Phase.SLEEPING else Phase.LEAVING, exit.atMs)
		targets[id] = Target(null, null)
		val durationMs = if(exit.suspended) SLEEP_MS else LEAVE_MS
		later(id, (exit.atMs + durationMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)) {
			if(!exit.suspended || isSeen(id))
				cancel(id)
			else
			{
				Log.i(TAG, "The console is not seen after going to rest: still falling asleep")
				later(id, exit.atMs + maxMs - SystemClock.elapsedRealtime()) { cancel(id) }
			}
		}
		startTicking()
		publish()
	}

	private fun ready(id: Long)
	{
		val activity = activities[id] ?: return
		val now = SystemClock.elapsedRealtime()
		Log.i(TAG, "Console ready after ${now - activity.startMs} ms (verified=${activity.verified})")
		targets[id]?.let { handler.removeCallbacksAndMessages(it.token) }
		activities[id] = activity.copy(phase = Phase.READY, phaseStartMs = now)
		if(activity.connect)
			later(id, CONNECT_DELAY_MS) {
				cancel(id)
				onReadyToConnect?.invoke(id)
			}
		else
			later(id, READY_SHOWN_MS) { cancel(id) }
		publish()
	}

	private fun giveUp(id: Long)
	{
		val activity = activities[id] ?: return
		if(activity.phase != Phase.WAKING)
			return
		if(!activity.verified)
		{
			ready(id) // nothing can tell: assume it is up by now
			return
		}
		Log.i(TAG, "Console did not wake up in time")
		targets[id]?.let { handler.removeCallbacksAndMessages(it.token) }
		activities[id] = activity.copy(phase = Phase.FAILED, phaseStartMs = SystemClock.elapsedRealtime())
		later(id, FAILED_SHOWN_MS) { cancel(id) }
		publish()
	}

	private fun resend(id: Long)
	{
		val target = targets[id] ?: return
		val address = target.address ?: return
		val registeredHost = target.registeredHost ?: return
		sendWakeup(address, registeredHost)
		later(id, RESEND_MS) { if(activities[id]?.phase == Phase.WAKING) resend(id) }
	}

	private fun later(id: Long, delayMs: Long, action: () -> Unit)
	{
		val token = targets[id]?.token ?: return
		handler.postAtTime(Runnable { action() }, token, SystemClock.uptimeMillis() + delayMs)
	}

	private val tick = object: Runnable
	{
		override fun run()
		{
			if(activities.values.none { it.phase == Phase.WAKING || it.phase == Phase.LEAVING || it.phase == Phase.SLEEPING || it.phase == Phase.PAUSED })
				return
			publish()
			handler.postDelayed(this, 1000)
		}
	}

	private fun startTicking()
	{
		handler.removeCallbacks(tick)
		handler.postDelayed(tick, 1000)
	}

	private fun publish()
	{
		stateData.value = activities.toMap()
	}

	fun dispose()
	{
		handler.removeCallbacksAndMessages(null)
		activities.clear()
		targets.clear()
	}

	companion object
	{
		private const val TAG = "ConsoleActivity"
		const val RESEND_MS = 3000L
		const val TIMEOUT_MS = 60_000L
		const val UNVERIFIED_MS = 20_000L
		/** the remote play service may need a moment after the console says it is ready */
		const val CONNECT_DELAY_MS = 1500L
		const val READY_SHOWN_MS = 3000L
		const val FAILED_SHOWN_MS = 8000L
		/** the console lets a session go in ~8 s (measured) */
		const val LEAVE_MS = 8000L
		/** time for the console to really be at rest */
		const val SLEEP_MS = 30_000L
		/** at most this much longer while the console, going to rest, is off the network */
		const val SLEEP_EXTRA_MS = 90_000L
		/** wakeup progress is drawn against this (the PS5 took 27 s in the first test) */
		const val WAKE_ESTIMATE_MS = 25_000L
	}
}
