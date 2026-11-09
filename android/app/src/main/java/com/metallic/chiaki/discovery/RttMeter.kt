// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.discovery

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.InetAddress

/**
 * How fast and how steadily the network answers on the way to each console, for the Auto quality
 * profile. Every 2 s, while the console list is on screen, it pings each console once (a plain
 * network ping: the console's own answer to discovery takes ~200 ms of its own, measured on a
 * PS5 at home with a 3 ms network, so it says nothing about the network). Tiny packets, and it
 * stops as soon as the list leaves the screen.
 */
object RttMeter
{
	private const val TAG = "RttMeter"
	private const val ROUND_MS = 2000L
	private const val ANSWER_WAIT_MS = 1000
	/** samples kept per console: the last ~16 s */
	private const val KEEP = 8
	/** an answer older than this says nothing about the network now */
	private const val STALE_MS = 30_000L

	data class Target(val host: String, val ps5: Boolean)

	/** What the last answers of one console say */
	data class Stats(
		/** typical answer time, ms */
		val medianMs: Int,
		/** slowest minus fastest of the recent answers, ms (how unsteady it is) */
		val spreadMs: Int,
		/** share of the recent pings left without an answer, 0..1 */
		val lossShare: Double,
		val samples: Int)

	private class Sample(val atMs: Long, val rttMs: Int?)

	private val handler = Handler(Looper.getMainLooper())
	private val samples = mutableMapOf<String, ArrayDeque<Sample>>() // guarded by this
	@Volatile private var targets: Set<Target> = setOf()
	@Volatile private var thread: Thread? = null
	/** a quick burst of rounds after [remeasure], until this time (elapsedRealtime) */
	@Volatile private var burstUntilMs = 0L
	private const val BURST_ROUND_MS = 250L
	private const val BURST_MS = 1500L
	private val wake = Object()

	/** Called on the main thread after each round, so the cards can show the new estimate */
	var listener: (() -> Unit)? = null

	fun setTargets(value: Set<Target>)
	{
		targets = value
	}

	@Synchronized fun stats(host: String): Stats?
	{
		val now = SystemClock.elapsedRealtime()
		val recent = samples[host]?.filter { now - it.atMs < STALE_MS } ?: return null
		val answered = recent.mapNotNull { it.rttMs }.sorted()
		if(answered.isEmpty())
			return null
		return Stats(
			medianMs = answered[answered.size / 2],
			spreadMs = answered.last() - answered.first(),
			lossShare = 1.0 - answered.size.toDouble() / recent.size,
			samples = answered.size)
	}

	@Synchronized private fun add(host: String, sample: Sample)
	{
		val list = samples.getOrPut(host) { ArrayDeque() }
		list.addLast(sample)
		while(list.size > KEEP)
			list.removeFirst()
	}

	/**
	 * Pulling the list down: forget what was measured and ping a few times quickly, so the estimate
	 * is new within ~1 s (it shows "~" until there are enough answers).
	 */
	fun remeasure()
	{
		synchronized(this) { samples.clear() }
		burstUntilMs = SystemClock.elapsedRealtime() + BURST_MS
		synchronized(wake) { wake.notifyAll() }
		listener?.invoke()
	}

	fun start()
	{
		if(thread != null)
			return
		thread = Thread({ run() }, "RttMeter").also { it.isDaemon = true; it.start() }
	}

	fun stop()
	{
		thread?.interrupt()
		thread = null
	}

	/** One ping: ms, or null without an answer. Android lets apps send it (an ICMP datagram socket). */
	private fun ping(host: String): Int?
	{
		return try
		{
			val address = InetAddress.getByName(host)
			val start = SystemClock.elapsedRealtimeNanos()
			if(address.isReachable(ANSWER_WAIT_MS))
				((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).toInt()
			else
				null
		}
		catch(e: Exception) { null }
	}

	private fun run()
	{
		val me = Thread.currentThread()
		try
		{
			while(thread === me)
			{
				val roundStart = SystemClock.elapsedRealtime()
				for(target in targets)
				{
					if(thread !== me)
						break
					val rtt = ping(target.host)
						add(target.host, Sample(SystemClock.elapsedRealtime(), rtt))
						if(roundStart < burstUntilMs)
							Log.i(TAG, "${target.host}: ${rtt?.let { "$it ms" } ?: "no answer"}")
				}
				handler.post { listener?.invoke() }
				val round = if(SystemClock.elapsedRealtime() < burstUntilMs) BURST_ROUND_MS else ROUND_MS
					val rest = round - (SystemClock.elapsedRealtime() - roundStart)
				if(rest > 0)
					synchronized(wake) { wake.wait(rest) }
			}
		}
		catch(e: InterruptedException) { }
		catch(e: Exception) { Log.e(TAG, "Stopped: $e") }
	}
}
