// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

/**
 * The last two minutes of the session, one [Sample] per second, for the stats overlay. Filled by
 * [SessionStats] (the same numbers that go into the STATS lines of the log), read by the overlay.
 */
object LiveStats
{
	data class Sample(
		/** seconds since the session started */
		val t: Long,
		/** video received, Mbit/s */
		val mbps: Double,
		/** the bitrate the console says it aims at, Mbit/s (0 = it did not say) */
		val targetMbps: Double,
		/** frames that arrived */
		val frames: Int,
		/** frames that never arrived whole, and the ones repaired with error correction */
		val framesLost: Int,
		val framesRecovered: Int,
		/** network packets lost and in all (the share lost is what the console should be told) */
		val packetsLost: Int,
		val packetsTotal: Int,
		/** packets the phone itself threw away (its receive queue was full) */
		val queueDrops: Int,
		/** pictures drawn on the screen, how many came late (> 25 ms) and the longest wait */
		val draws: Int,
		val slowDraws: Int,
		val maxGapMs: Long,
		/** size the decoder is putting out (0 = not known yet) */
		val width: Int,
		val height: Int,
		/** round trip time the console measured, ms (< 0 = not known) */
		val rttMs: Double)
	{
		val lossPercent get() = if(packetsTotal > 0) packetsLost * 100.0 / packetsTotal else 0.0
		/**
		 * something visible may have happened this second. A late picture or two (a frame shown
		 * twice) happens all the time and nobody sees it: only a wait over 100 ms counts.
		 */
		val trouble get() = framesLost > 0 || queueDrops > 0 || maxGapMs > 100
	}

	const val CAPACITY = 120

	private val samples = ArrayDeque<Sample>()

	/** the stream the samples belong to: 0 = none; changes with every session */
	@Volatile var generation = 0
		private set

	@Synchronized fun reset()
	{
		samples.clear()
		generation++
	}

	@Synchronized fun add(sample: Sample)
	{
		samples.addLast(sample)
		while(samples.size > CAPACITY)
			samples.removeFirst()
	}

	@Synchronized fun snapshot(): List<Sample> = samples.toList()
}
