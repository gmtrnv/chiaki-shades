// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Diagnostic build only: once a second, writes one "STATS" line into the session log with what is
 * needed to tell where heat and stutter come from, with no adb: the real bitrate received, the CPU
 * each thread of the app uses, the phone's thermal state and battery, and the settings in force
 * (the log used to say nothing about them). Lines are written next to the native log lines, so a
 * run of "FEC failed" can be put in time by the lines around it.
 */
class SessionStats(
	private val context: Context,
	private val logLine: (String) -> Unit,
	private val flags: () -> String,
	private val header: String,
	/** fills the native running totals (see Session.getStats); false when there is no session */
	private val nativeStats: (LongArray) -> Boolean = { false })
{
	private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "SessionStats") }
	private val uid = Process.myUid()
	private val clockTicks = try { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() } catch(e: Throwable) { 100.0 }
	private val startMs = SystemClock.elapsedRealtime()

	private var lastMs = startMs
	private var lastRx = TrafficStats.getUidRxBytes(uid)
	private var lastTicks = mapOf<Int, Pair<String, Long>>()
	private val zones: List<Pair<String, File>> = readableZones()

	fun start()
	{
		executor.execute {
			logLine("STATS header $header")
			logLine("STATS zones readable: ${zones.joinToString(" ") { it.first }.ifEmpty { "none" }}")
		}
		executor.scheduleAtFixedRate({ try { tick() } catch(e: Throwable) { } }, 1, 1, TimeUnit.SECONDS)
	}

	/** stops for good; waits a moment so nothing writes after the native session is gone */
	fun stop()
	{
		executor.shutdownNow()
		try { executor.awaitTermination(300, TimeUnit.MILLISECONDS) } catch(e: InterruptedException) { }
	}

	private fun tick()
	{
		val now = SystemClock.elapsedRealtime()
		val dt = (now - lastMs) / 1000.0
		lastMs = now

		val rx = TrafficStats.getUidRxBytes(uid)
		val mbps = if(rx >= 0 && lastRx >= 0 && dt > 0) (rx - lastRx) * 8 / 1e6 / dt else -1.0
		lastRx = rx

		// CPU per thread, from the app's own /proc (no adb needed): % of one core
		val ticks = readThreads()
		val perName = HashMap<String, Double>()
		var total = 0.0
		for((tid, v) in ticks)
		{
			val before = lastTicks[tid]?.second ?: v.second
			val pct = (v.second - before) / clockTicks / dt * 100.0
			total += pct
			perName[v.first] = (perName[v.first] ?: 0.0) + pct
		}
		lastTicks = ticks
		val top = perName.entries.sortedByDescending { it.value }.take(9)
			.joinToString(",") { "${it.key}:${"%.0f".format(it.value)}" }

		val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
		val battTemp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
		val battLevel = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
		val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
		// raw: the unit differs between makers (some give microamps, Samsung seems to give milliamps)
		val currentRaw = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
		val thermal = if(Build.VERSION.SDK_INT >= 29)
			(context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: -1
		else -1
		val zoneTemps = zones.mapNotNull { z -> readTemp(z.second)?.let { "${z.first}=${"%.0f".format(it)}" } }
			.joinToString(" ")

		val hz = try
		{
			(context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
				?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
		}
		catch(e: Throwable) { 0f }

		val t = (now - startMs) / 1000
		// rxkb is the running total: Android refreshes the per-app counter only every ~2 s, so the
		// per-second rate above alternates 0 and double; the true rate is the difference of two totals
		// pics: pictures delivered by the decoder (frames), drawn (draws), draws more than 25 ms after
		// the previous one (slow) and the longest wait between two draws
		val pics = com.metallic.chiaki.stream.RenderStats.take()
		val net = netSample(t, dt, pics)
		logLine("STATS t=$t hz=${"%.0f".format(hz)} pics=[${pics.line}] net=[$net] ui=[${com.metallic.chiaki.stream.RenderStats.takeUiLine()}] rx=${"%.1f".format(mbps)}Mbps rxkb=${if(rx >= 0) rx / 1024 else -1} cpu=${"%.0f".format(total)}% top=[$top] thermal=$thermal " +
			"batt=${"%.1f".format(battTemp)}C/$battLevel% cur=$currentRaw zones=[$zoneTemps] flags=[${flags()}]")
	}

	private val nativeNow = LongArray(14)
	private val nativeBefore = LongArray(14)
	private var nativeFirst = true

	/**
	 * The second's network and video numbers from the native session (running totals turned into
	 * per-second ones): into [LiveStats] for the overlay, and as text for the STATS line.
	 */
	private fun netSample(t: Long, dt: Double, pics: com.metallic.chiaki.stream.RenderStats.Pics): String
	{
		if(!nativeStats(nativeNow))
			return "none"
		if(nativeFirst)
		{
			nativeNow.copyInto(nativeBefore)
			nativeFirst = false
		}
		fun d(i: Int) = (nativeNow[i] - nativeBefore[i]).coerceAtLeast(0)
		val s = LiveStats.Sample(
			t = t,
			mbps = if(dt > 0) d(1) * 8 / 1e6 / dt else 0.0,
			// the console's figure is in bit/s (a 720p stream showed 19417000)
			targetMbps = nativeNow[9] / 1e6,
			frames = d(0).toInt(),
			framesLost = d(2).toInt(),
			framesRecovered = d(3).toInt(),
			packetsLost = d(7).toInt(),
			packetsTotal = (d(6) + d(7)).toInt(),
			queueDrops = d(8).toInt(),
			draws = pics.draws,
			slowDraws = pics.slow,
			maxGapMs = pics.maxGapMs,
			width = nativeNow[4].toInt(),
			height = nativeNow[5].toInt(),
			rttMs = if(nativeNow[11] > 0) nativeNow[10] / 1000.0 else -1.0)
		nativeNow.copyInto(nativeBefore)
		LiveStats.add(s)
		return "video=${"%.1f".format(s.mbps)}Mbps target=${nativeNow[9]} lost=${s.framesLost} fec=${s.framesRecovered} " +
			"pkts=${s.packetsLost}/${s.packetsTotal} qdrop=${s.queueDrops} out=${s.width}x${s.height} rtt=${nativeNow[10]} q=${nativeNow[11]} " +
			// frames waiting for the decoder (most at once this second) and the ones that found its queue full
			"dq=${nativeNow[12]} dqfull=${d(13)}"
	}

	/** tid -> (thread name, user+system CPU ticks) */
	private fun readThreads(): Map<Int, Pair<String, Long>>
	{
		val out = HashMap<Int, Pair<String, Long>>()
		val dirs = File("/proc/self/task").listFiles() ?: return out
		for(d in dirs)
		{
			val tid = d.name.toIntOrNull() ?: continue
			try
			{
				val stat = File(d, "stat").readText()
				val open = stat.indexOf('(')
				val close = stat.lastIndexOf(')')
				if(open < 0 || close < open)
					continue
				val name = stat.substring(open + 1, close)
				val rest = stat.substring(close + 2).split(' ')
				// after ")": state is field 0; utime and stime are fields 11 and 12 here
				val ticks = rest[11].toLong() + rest[12].toLong()
				out[tid] = Pair(name, ticks)
			}
			catch(e: Throwable) { }
		}
		return out
	}

	/** thermal zones the app is allowed to read (Android often forbids it) */
	private fun readableZones(): List<Pair<String, File>>
	{
		val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") } ?: return listOf()
		val out = mutableListOf<Pair<String, File>>()
		for(d in dirs.sortedBy { it.name })
		{
			try
			{
				val type = File(d, "type").readText().trim()
				val tempFile = File(d, "temp")
				if(readTemp(tempFile) != null)
					out.add(Pair(type, tempFile))
			}
			catch(e: Throwable) { }
		}
		// the full list can be long: keep the ones that tell about heat
		val wanted = out.filter { z -> listOf("cpu", "gpu", "soc", "skin", "tsens", "battery", "quiet", "ap_").any { z.first.lowercase().contains(it) } }
		return (if(wanted.isNotEmpty()) wanted else out).take(10)
	}

	private fun readTemp(f: File): Double?
	{
		return try
		{
			val v = f.readText().trim().toDouble()
			if(v > 1000) v / 1000.0 else v
		}
		catch(e: Throwable) { null }
	}
}
