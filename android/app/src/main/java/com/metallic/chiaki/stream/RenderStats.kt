// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Picture counters for the diagnostic log (see SessionStats): how many pictures the decoder delivered,
 * how many the renderer drew, and how long the longest wait between two drawn pictures was. The log
 * had nothing about the frame rate, so "the fps drop" could only be felt, not measured.
 */
object RenderStats
{
	/** pictures the decoder handed to the renderer */
	val newFrames = AtomicInteger()
	/** times the renderer drew to the screen */
	val draws = AtomicInteger()
	/** draws that came more than 25 ms after the one before: a visible hitch at 60 fps */
	val slow = AtomicInteger()
	val maxGapMs = AtomicLong()

	fun noteDraw(gapMs: Long)
	{
		draws.incrementAndGet()
		if(gapMs > 25)
			slow.incrementAndGet()
		maxGapMs.accumulateAndGet(gapMs) { a, b -> if(a > b) a else b }
	}

	data class Pics(val frames: Int, val draws: Int, val slow: Int, val maxGapMs: Long)
	{
		val line get() = "frames=$frames draws=$draws slow=$slow maxgap=${maxGapMs}ms"
	}

	/** the numbers of the last second, then back to zero */
	fun take() = Pics(newFrames.getAndSet(0), draws.getAndSet(0), slow.getAndSet(0), maxGapMs.getAndSet(0))

	// ---- the window's own frames (the interface over the video: touch controls, shell, bar), from
	// Android's frame metrics. The RenderThread was ~25-30 % of a core with the touch controls in use,
	// and nothing said how many frames that was or what each one cost.

	private val uiFrames = AtomicInteger()
	/** UI thread: recording what the views draw (µs, summed) */
	private val uiDrawUs = AtomicLong()
	/** RenderThread: sync + issuing the GPU commands + swap (µs, summed) */
	private val uiRenderUs = AtomicLong()
	private val uiRenderMaxUs = AtomicLong()
	/** GPU time of the frame (Android 12+), µs summed */
	private val uiGpuUs = AtomicLong()
	/** times the touch controls' resting picture was made again (a press, a release, a setting) */
	val padRecords = AtomicInteger()

	private val metricsThread by lazy { android.os.HandlerThread("FrameMetrics").also { it.start() } }

	private val metricsListener = android.view.Window.OnFrameMetricsAvailableListener { _, m, _ ->
		if(android.os.Build.VERSION.SDK_INT < 24)
			return@OnFrameMetricsAvailableListener
		val draw = m.getMetric(android.view.FrameMetrics.DRAW_DURATION)
		val render = m.getMetric(android.view.FrameMetrics.SYNC_DURATION) +
			m.getMetric(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) +
			m.getMetric(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
		uiFrames.incrementAndGet()
		uiDrawUs.addAndGet(draw / 1000)
		uiRenderUs.addAndGet(render / 1000)
		uiRenderMaxUs.accumulateAndGet(render / 1000) { a, b -> if(a > b) a else b }
		if(android.os.Build.VERSION.SDK_INT >= 31)
			uiGpuUs.addAndGet(m.getMetric(android.view.FrameMetrics.GPU_DURATION) / 1000)
	}

	/** counts the frames of this window from now on (it stops by itself with the window) */
	fun watchWindow(window: android.view.Window)
	{
		if(android.os.Build.VERSION.SDK_INT < 24)
			return
		try { window.addOnFrameMetricsAvailableListener(metricsListener, android.os.Handler(metricsThread.looper)) }
		catch(e: Throwable) { }
	}

	/** interface frames of the last second and their average cost in ms, then back to zero */
	fun takeUiLine(): String
	{
		val n = uiFrames.getAndSet(0)
		val draw = uiDrawUs.getAndSet(0)
		val render = uiRenderUs.getAndSet(0)
		val max = uiRenderMaxUs.getAndSet(0)
		val gpu = uiGpuUs.getAndSet(0)
		val p = padRecords.getAndSet(0)
		fun avg(us: Long) = if(n > 0) "%.2f".format(us / 1000.0 / n) else "0"
		return "frames=$n draw=${avg(draw)} rt=${avg(render)} rtmax=${"%.1f".format(max / 1000.0)} gpu=${avg(gpu)} pad=$p"
	}
}
