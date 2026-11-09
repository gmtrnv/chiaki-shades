// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.metallic.chiaki.common.PadTarget
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.min

/**
 * A plain drawing of a controller (our own, no real one's shape) that lights up what the console
 * would get: buttons, triggers as bars filling up, sticks as a dot. Tapping a part picks its button.
 * Drawn in a 200×120 box scaled to fit.
 */
class PadTesterView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	companion object
	{
		private const val W = 200f
		private const val H = 120f
		private const val GOLD = 0xFFD4AB55.toInt()
		private const val IDLE = 0xFF2A2D33.toInt()
		private const val LINE = 0xFF5A5F6A.toInt()
		private const val BODY = 0xFF1A1C20.toInt()
		private const val TEXT = 0xFFE0E0E0.toInt()
		private const val TEXT_ON = 0xFF1A1C20.toInt()
	}

	var state = ControllerState()
		set(value) { field = value; invalidate() }

	/** a part of the drawing was tapped */
	var onPick: ((PadTarget) -> Unit)? = null

	private sealed class Part(val target: PadTarget)
	{
		class Box(target: PadTarget, val rect: RectF, val label: String, val radius: Float = 2.5f): Part(target)
		class Round(target: PadTarget, val x: Float, val y: Float, val r: Float, val label: String): Part(target)
		class Trigger(target: PadTarget, val rect: RectF, val label: String): Part(target)
		class Stick(target: PadTarget, val x: Float, val y: Float, val r: Float, val left: Boolean): Part(target)
	}

	private val parts = listOf(
		Part.Trigger(PadTarget.L2, RectF(14f, 2f, 50f, 14f), "L2"),
		Part.Trigger(PadTarget.R2, RectF(150f, 2f, 186f, 14f), "R2"),
		Part.Box(PadTarget.L1, RectF(14f, 16f, 50f, 24f), "L1", 4f),
		Part.Box(PadTarget.R1, RectF(150f, 16f, 186f, 24f), "R1", 4f),
		Part.Box(PadTarget.TOUCHPAD, RectF(72f, 30f, 128f, 56f), "", 5f),
		Part.Round(PadTarget.SHARE, 60f, 36f, 4.5f, ""),
		Part.Round(PadTarget.OPTIONS, 140f, 36f, 4.5f, ""),
		Part.Box(PadTarget.DPAD_UP, RectF(35f, 44f, 45f, 54f), "▲"),
		Part.Box(PadTarget.DPAD_DOWN, RectF(35f, 64f, 45f, 74f), "▼"),
		Part.Box(PadTarget.DPAD_LEFT, RectF(25f, 54f, 35f, 64f), "◀"),
		Part.Box(PadTarget.DPAD_RIGHT, RectF(45f, 54f, 55f, 64f), "▶"),
		Part.Round(PadTarget.TRIANGLE, 160f, 47f, 6f, "△"),
		Part.Round(PadTarget.CROSS, 160f, 71f, 6f, "✕"),
		Part.Round(PadTarget.SQUARE, 148f, 59f, 6f, "□"),
		Part.Round(PadTarget.CIRCLE, 172f, 59f, 6f, "○"),
		Part.Round(PadTarget.PS, 100f, 70f, 5f, "PS"),
		Part.Stick(PadTarget.L3, 74f, 88f, 13f, true),
		Part.Stick(PadTarget.R3, 126f, 88f, 13f, false)
	)

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
	private val body = RectF(6f, 22f, 194f, 114f)

	private var scale = 1f
	private var offX = 0f
	private var offY = 0f

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		scale = min(w / W, h / H)
		offX = (w - W * scale) / 2f
		offY = (h - H * scale) / 2f
	}

	private fun pressed(target: PadTarget) = when(target)
	{
		PadTarget.L2 -> state.l2State > 0U
		PadTarget.R2 -> state.r2State > 0U
		else -> state.buttons and target.button != 0U
	}

	override fun onDraw(canvas: Canvas)
	{
		canvas.save()
		canvas.translate(offX, offY)
		canvas.scale(scale, scale)

		paint.style = Paint.Style.FILL
		paint.color = BODY
		canvas.drawRoundRect(body, 30f, 30f, paint)
		paint.style = Paint.Style.STROKE
		paint.strokeWidth = 0.8f
		paint.color = LINE
		canvas.drawRoundRect(body, 30f, 30f, paint)

		for(part in parts)
		{
			val on = pressed(part.target)
			when(part)
			{
				is Part.Box -> {
					fill(if(on) GOLD else IDLE); canvas.drawRoundRect(part.rect, part.radius, part.radius, paint)
					stroke(); canvas.drawRoundRect(part.rect, part.radius, part.radius, paint)
					label(canvas, part.label, part.rect.centerX(), part.rect.centerY(), part.rect.height() * 0.6f, on)
				}
				is Part.Round -> {
					fill(if(on) GOLD else IDLE); canvas.drawCircle(part.x, part.y, part.r, paint)
					stroke(); canvas.drawCircle(part.x, part.y, part.r, paint)
					label(canvas, part.label, part.x, part.y, part.r * (if(part.label.length > 1) 0.9f else 1.3f), on)
				}
				is Part.Trigger -> {
					val value = (if(part.target == PadTarget.L2) state.l2State else state.r2State).toFloat() / 255f
					fill(IDLE); canvas.drawRoundRect(part.rect, 3f, 3f, paint)
					if(value > 0f)
					{
						fill(GOLD)
						val r = RectF(part.rect)
						r.right = r.left + r.width() * value
						canvas.drawRoundRect(r, 3f, 3f, paint)
					}
					stroke(); canvas.drawRoundRect(part.rect, 3f, 3f, paint)
					val text = if(value > 0f) "${part.label} ${(value * 100).toInt()}%" else part.label
					label(canvas, text, part.rect.centerX(), part.rect.centerY(), 6f, value > 0.5f)
				}
				is Part.Stick -> {
					fill(if(on) GOLD else IDLE); canvas.drawCircle(part.x, part.y, part.r, paint)
					stroke(); canvas.drawCircle(part.x, part.y, part.r, paint)
					val sx = (if(part.left) state.leftX else state.rightX) / 32767f
					val sy = (if(part.left) state.leftY else state.rightY) / 32767f
					fill(if(on) TEXT_ON else GOLD)
					canvas.drawCircle(part.x + sx * (part.r - 4f), part.y + sy * (part.r - 4f), 3.5f, paint)
				}
			}
		}
		canvas.restore()
	}

	private fun fill(color: Int) { paint.style = Paint.Style.FILL; paint.color = color }
	private fun stroke() { paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.8f; paint.color = LINE }

	private fun label(canvas: Canvas, text: String, x: Float, y: Float, size: Float, on: Boolean)
	{
		if(text.isEmpty())
			return
		textPaint.textSize = size
		textPaint.color = if(on) TEXT_ON else TEXT
		val fm = textPaint.fontMetrics
		canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		if(event.actionMasked == MotionEvent.ACTION_DOWN)
			return true
		if(event.actionMasked != MotionEvent.ACTION_UP)
			return super.onTouchEvent(event)
		val x = (event.x - offX) / scale
		val y = (event.y - offY) / scale
		// how far outside each part the tap is (negative = inside); the nearest one within 6 units wins
		fun distance(part: Part): Float
		{
			val (cx, cy, reach) = when(part)
			{
				is Part.Box -> Triple(part.rect.centerX(), part.rect.centerY(), maxOf(part.rect.width(), part.rect.height()) / 2f)
				is Part.Trigger -> Triple(part.rect.centerX(), part.rect.centerY(), part.rect.width() / 2f)
				is Part.Round -> Triple(part.x, part.y, part.r)
				is Part.Stick -> Triple(part.x, part.y, part.r)
			}
			return kotlin.math.hypot(x - cx, y - cy) - reach
		}
		val hit = parts.minByOrNull { distance(it) }
		if(hit != null && distance(hit) <= 6f)
		{
			performClick()
			onPick?.invoke(hit.target)
		}
		return true
	}

	override fun performClick() = super.performClick()
}
