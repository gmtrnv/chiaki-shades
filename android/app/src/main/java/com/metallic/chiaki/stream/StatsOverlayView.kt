// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.metallic.chiaki.session.LiveStats
import kotlin.math.max
import kotlin.math.min

/**
 * The statistics over the game: the last minute in three small graphs, so that what happened is
 * seen at a glance instead of read from numbers that keep changing.
 *  - Mbps: the video received (filled) and, dashed, the bitrate the console says it aims at.
 *  - Picture: one bar per second, as tall as the pictures drawn; amber when the longest wait
 *    between two was over 50 ms, red over 100 ms (a hitch anyone notices).
 *  - Losses: frames lost (red), repaired by error correction (amber) and packets the phone threw
 *    away itself (violet).
 * A second with trouble gets a faint red stripe through all three, and a change of resolution a
 * white line with the new height. It never takes touches (they go to the game below).
 */
class StatsOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs)
{
	/** pictures per second the stream should have (the top of the picture graph) */
	var targetFps = 60

	private val dp = resources.displayMetrics.density
	private val sp = resources.displayMetrics.scaledDensity

	private val panelW = 172 * dp
	private val pad = 6 * dp
	private val graphH = 24 * dp
	private val textSize = 9 * sp
	private val lineH = textSize * 1.25f
	private val gap = 3 * dp

	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
	private val gridPaint = Paint().apply { color = Color.argb(40, 255, 255, 255); strokeWidth = 1f }
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = this@StatsOverlayView.textSize }
	private val dimText = Paint(textPaint).apply { color = Color.argb(170, 255, 255, 255) }
	private val bigText = Paint(textPaint).apply { textSize = this@StatsOverlayView.textSize * 1.5f; isFakeBoldText = true }
	private val mbpsFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 90, 200, 255); style = Paint.Style.FILL }
	private val mbpsLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 210, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f * dp }
	private val targetLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.argb(200, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f * dp
		pathEffect = DashPathEffect(floatArrayOf(3 * dp, 2 * dp), 0f)
	}
	private val okBar = Paint().apply { color = Color.rgb(80, 200, 120) }
	private val lateBar = Paint().apply { color = Color.rgb(255, 180, 40) }
	private val badBar = Paint().apply { color = Color.rgb(255, 70, 70) }
	private val fecBar = Paint().apply { color = Color.rgb(255, 180, 40) }
	private val dropBar = Paint().apply { color = Color.rgb(190, 120, 255) }
	private val troubleStripe =Paint().apply { color = Color.argb(60, 255, 60, 60) }
	private val resLine = Paint().apply { color = Color.argb(220, 255, 255, 255); strokeWidth = 1f * dp }

	private val rect = RectF()
	private val path = Path()

	private val refresh = object : Runnable
	{
		override fun run()
		{
			invalidate()
			postDelayed(this, 500)
		}
	}

	init
	{
		isClickable = false
		isFocusable = false
	}

	override fun onAttachedToWindow()
	{
		super.onAttachedToWindow()
		if(visibility == VISIBLE)
			post(refresh)
	}

	override fun onDetachedFromWindow()
	{
		removeCallbacks(refresh)
		super.onDetachedFromWindow()
	}

	override fun onVisibilityChanged(changedView: View, visibility: Int)
	{
		super.onVisibilityChanged(changedView, visibility)
		removeCallbacks(refresh)
		if(isShown)
			post(refresh)
	}

	override fun onTouchEvent(event: android.view.MotionEvent?) = false

	override fun onDraw(canvas: Canvas)
	{
		val all = LiveStats.snapshot()
		val shown = all.takeLast(SECONDS)
		val last = shown.lastOrNull()

		val left = 8 * dp
		val top = 8 * dp
		val graphW = panelW - 2 * pad
		val panelH = pad + bigText.textSize + gap + 3 * (lineH + graphH + gap) + pad
		rect.set(left, top, left + panelW, top + panelH)
		canvas.drawRoundRect(rect, 6 * dp, 6 * dp, bgPaint)

		val x0 = left + pad
		var y = top + pad + bigText.textSize

		// headline: Mbps now, resolution and round trip time
		if(last == null)
		{
			canvas.drawText("…", x0, y, bigText)
			return
		}
		canvas.drawText("%.1f Mbps".format(last.mbps), x0, y, bigText)
		// (the console's round trip time stays in the log: it said 139 ms where ping says 8, its
		// meaning is not clear yet)
		val head = if(last.height > 0) "${last.height}p" else ""
		canvas.drawText(head, left + panelW - pad - dimText.measureText(head), y, dimText)
		y += gap

		val barW = graphW / SECONDS
		// slot of sample i: samples fill the graph from the right
		fun xOf(i: Int) = x0 + (SECONDS - shown.size + i) * barW

		// ---- Mbps
		y += lineH
		val target = last.targetMbps
		val mbpsTitle = if(target > 0) context.getString(com.metallic.chiaki.R.string.stats_mbps_target, target)
			else context.getString(com.metallic.chiaki.R.string.stats_mbps)
		canvas.drawText(mbpsTitle, x0, y - textSize * 0.25f, dimText)
		val gTop1 = y
		val gBottom1 = y + graphH
		drawFrame(canvas, x0, gTop1, graphW)
		drawStripes(canvas, shown, ::xOf, barW, gTop1)
		val maxMbps = max(1.0, max(shown.maxOf { it.mbps }, shown.maxOf { it.targetMbps })) * 1.15
		path.reset()
		path.moveTo(xOf(0), gBottom1)
		shown.forEachIndexed { i, s -> path.lineTo(xOf(i) + barW / 2, (gBottom1 - s.mbps / maxMbps * graphH).toFloat()) }
		path.lineTo(xOf(shown.size - 1) + barW, gBottom1)
		path.close()
		canvas.drawPath(path, mbpsFill)
		path.reset()
		shown.forEachIndexed { i, s ->
			val py = (gBottom1 - s.mbps / maxMbps * graphH).toFloat()
			if(i == 0) path.moveTo(xOf(i) + barW / 2, py) else path.lineTo(xOf(i) + barW / 2, py)
		}
		canvas.drawPath(path, mbpsLine)
		if(shown.any { it.targetMbps > 0 })
		{
			path.reset()
			var started = false
			shown.forEachIndexed { i, s ->
				if(s.targetMbps <= 0) return@forEachIndexed
				val py = (gBottom1 - s.targetMbps / maxMbps * graphH).toFloat()
				if(!started) { path.moveTo(xOf(i), py); started = true } else path.lineTo(xOf(i) + barW, py)
			}
			canvas.drawPath(path, targetLine)
		}
		drawResolutionChanges(canvas, shown, ::xOf, gTop1, gBottom1)
		y = gBottom1 + gap

		// ---- picture timing
		y += lineH
		val fpsNow = last.draws
		canvas.drawText(context.getString(com.metallic.chiaki.R.string.stats_picture, fpsNow, last.maxGapMs.toInt()), x0, y - textSize * 0.25f, dimText)
		val gTop2 = y
		val gBottom2 = y + graphH
		drawFrame(canvas, x0, gTop2, graphW)
		drawStripes(canvas, shown, ::xOf, barW, gTop2)
		val fpsTop = max(targetFps, shown.maxOf { it.draws }).toFloat()
		shown.forEachIndexed { i, s ->
			val h = min(1f, s.draws / fpsTop) * graphH
			val paint = when
			{
				s.maxGapMs > 100 -> badBar
				s.maxGapMs > 50 -> lateBar
				else -> okBar
			}
			canvas.drawRect(xOf(i) + barW * 0.15f, gBottom2 - h, xOf(i) + barW * 0.85f, gBottom2, paint)
		}
		y = gBottom2 + gap

		// ---- losses
		y += lineH
		val lossText = context.getString(com.metallic.chiaki.R.string.stats_losses, last.lossPercent, last.framesLost, last.framesRecovered)
		canvas.drawText(lossText, x0, y - textSize * 0.25f, dimText)
		val gTop3 = y
		val gBottom3 = y + graphH
		drawFrame(canvas, x0, gTop3, graphW)
		drawStripes(canvas, shown, ::xOf, barW, gTop3)
		// a few per second already matter: the scale starts at 4 and grows with the worst second
		val lossTop = max(4, shown.maxOf { max(it.framesLost + it.framesRecovered, it.queueDrops) }).toFloat()
		shown.forEachIndexed { i, s ->
			val bx = xOf(i)
			var base = gBottom3
			fun stack(n: Int, paint: Paint, x1: Float, x2: Float)
			{
				if(n <= 0) return
				val h = max(2f * dp, n / lossTop * graphH)
				canvas.drawRect(x1, base - h, x2, base, paint)
				base -= h
			}
			stack(s.framesLost, badBar, bx + barW * 0.1f, bx + barW * 0.9f)
			stack(s.framesRecovered, fecBar, bx + barW * 0.1f, bx + barW * 0.9f)
			if(s.queueDrops > 0)
			{
				base = gBottom3
				stack(s.queueDrops, dropBar, bx + barW * 0.6f, bx + barW)
			}
		}
	}

	private fun drawFrame(canvas: Canvas, x: Float, top: Float, w: Float)
	{
		canvas.drawLine(x, top + graphH, x + w, top + graphH, gridPaint)
		canvas.drawLine(x, top, x + w, top, gridPaint)
	}

	private fun drawStripes(canvas: Canvas, shown: List<LiveStats.Sample>, xOf: (Int) -> Float, barW: Float, top: Float)
	{
		shown.forEachIndexed { i, s ->
			if(s.trouble)
				canvas.drawRect(xOf(i), top, xOf(i) + barW, top + graphH, troubleStripe)
		}
	}

	private fun drawResolutionChanges(canvas: Canvas, shown: List<LiveStats.Sample>, xOf: (Int) -> Float, top: Float, bottom: Float)
	{
		for(i in 1 until shown.size)
		{
			val a = shown[i - 1].height
			val b = shown[i].height
			if(a > 0 && b > 0 && a != b)
			{
				val x = xOf(i)
				canvas.drawLine(x, top, x, bottom, resLine)
				canvas.drawText("${b}p", x + 2 * dp, top + textSize, textPaint)
			}
		}
	}

	companion object
	{
		/** how much of the past the graphs show */
		const val SECONDS = 60
	}
}
