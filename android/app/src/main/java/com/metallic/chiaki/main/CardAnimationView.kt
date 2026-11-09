// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import com.metallic.chiaki.common.CardAnimation
import com.metallic.chiaki.main.ConsoleActivityTracker.Activity
import com.metallic.chiaki.main.ConsoleActivityTracker.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
private fun easeIn(t: Float) = t * t
private fun lerpColor(a: Int, b: Int, t: Float): Int
{
	val u = clamp01(t)
	return Color.argb(
		lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), u).toInt(),
		lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), u).toInt(),
		lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), u).toInt(),
		lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), u).toInt())
}
private fun withAlpha(color: Int, alpha: Float) = (color and 0x00ffffff) or ((clamp01(alpha) * 255).toInt() shl 24)

/**
 * Draws a console card's animation while it wakes up, disconnects or suspends (see
 * [ConsoleActivityTracker]). Each card has two of these: one behind the texts (android:tag="back":
 * sun, sky, night) and one in front (android:tag="front": the light strip, the filling icon).
 * Everything is computed from the time since the phase began, so rebinding the card every second
 * does not restart anything. Never takes touches.
 */
class CardAnimationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val front get() = tag == "front"
	private val dp = resources.displayMetrics.density

	private var style = CardAnimation.default
	private var activity: Activity? = null
	/** the console icon of the card, for the "charge" style */
	var iconView: ImageView? = null

	fun show(style: CardAnimation, activity: Activity?)
	{
		val shown = activity?.takeIf { it.phase.animated }
		if(style == this.style && shown == this.activity)
			return
		this.style = style
		this.activity = shown
		invalidate()
	}

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val atopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
	private var sunShader: RadialGradient? = null
	private var sunRadius = 0f

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		sunRadius = h * 0.75f
		sunShader = RadialGradient(0f, 0f, sunRadius,
			intArrayOf(0xFFFFD36B.toInt(), 0xFFFF7A3D.toInt(), 0xFFFF3D7F.toInt(), 0x00FF3D7F),
			floatArrayOf(0f, 0.48f, 0.83f, 1f), Shader.TileMode.CLAMP)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val activity = activity ?: return
		val t = (SystemClock.elapsedRealtime() - activity.phaseStartMs).toFloat()
		when(style)
		{
			CardAnimation.SUNRISE -> if(!front) drawSunrise(canvas, activity.phase, t)
			CardAnimation.BREATHING -> if(front) drawBreathing(canvas, activity.phase, t)
			CardAnimation.CHARGE -> if(front) drawCharge(canvas, activity.phase, t)
		}
		if(front && activity.phase == Phase.PAUSED)
			drawPauseBadge(canvas, t)
		postInvalidateOnAnimation()
	}

	// --- Sunrise: the sun rises; disconnecting it goes halfway down; suspending it sets, night and stars come out

	private fun drawSunrise(canvas: Canvas, phase: Phase, t: Float)
	{
		var sun = 0f // 0 = below the card, 1 = high up
		var sunAlpha = 0f
		var sky = 0f
		var night = 0f
		var stars = 0f
		when(phase)
		{
			Phase.WAKING ->
			{
				val e = easeOut(clamp01(t / ConsoleActivityTracker.WAKE_ESTIMATE_MS))
				sun = 0.05f + 0.75f * e
				sunAlpha = 0.2f + 0.4f * e
				sky = 0.85f * e
			}
			Phase.READY ->
			{
				sun = lerp(0.8f, 1f, easeOut(clamp01(t / 600f)))
				sunAlpha = 0.6f
				sky = 0.9f
			}
			Phase.LEAVING ->
			{
				val p = clamp01(t / ConsoleActivityTracker.LEAVE_MS)
				sun = 1f - 0.65f * easeIn(p)
				sunAlpha = 0.6f - 0.35f * p
				sky = 0.9f * (1f - p)
			}
			Phase.SLEEPING ->
			{
				val p = clamp01(t / ConsoleActivityTracker.SLEEP_MS)
				sun = 1f - 1.2f * easeIn(clamp01(p / 0.75f))
				sunAlpha = 0.6f * (1f - clamp01((p - 0.5f) / 0.5f))
				sky = 0.9f * (1f - clamp01(p / 0.5f))
				night = 0.8f * easeOut(p)
				stars = 0.9f * clamp01((p - 0.55f) / 0.45f)
			}
			Phase.PAUSED ->
			{
				drawMoon(canvas, t)
				return
			}
			Phase.FAILED -> return
		}
		val w = width.toFloat()
		val h = height.toFloat()
		if(night > 0f)
		{
			paint.shader = LinearGradient(0f, 0f, 0f, h, withAlpha(0xFF0B1030.toInt(), night), withAlpha(0xFF1B1446.toInt(), night), Shader.TileMode.CLAMP)
			canvas.drawRect(0f, 0f, w, h, paint)
		}
		if(sky > 0f)
		{
			paint.shader = LinearGradient(0f, h, 0f, h * 0.15f, withAlpha(0xFF3A1C5C.toInt(), sky), 0x003A1C5C, Shader.TileMode.CLAMP)
			canvas.drawRect(0f, 0f, w, h, paint)
		}
		paint.shader = null
		if(stars > 0f)
		{
			paint.color = withAlpha(Color.WHITE, stars)
			for(i in STARS.indices step 3)
				canvas.drawCircle(STARS[i] * w, STARS[i + 1] * h, STARS[i + 2] * dp, paint)
		}
		val sunShader = sunShader ?: return
		if(sunAlpha > 0f)
		{
			val cy = lerp(h + sunRadius * 0.45f, h * 0.2f, sun)
			canvas.save()
			canvas.translate(w / 2f, cy)
			paint.shader = sunShader
			paint.alpha = (sunAlpha * 255).toInt()
			canvas.drawCircle(0f, 0f, sunRadius, paint)
			paint.shader = null
			paint.alpha = 255
			canvas.restore()
		}
	}

	// --- Breathing light: orange pulse -> white; disconnecting the white light slows down and fades;
	// suspending it turns into the slow orange pulse of the console's rest mode

	private fun breathe(t: Float, periodMs: Float) = 0.5f + 0.5f * cos(2f * PI.toFloat() * t / periodMs)

	private fun drawBreathing(canvas: Canvas, phase: Phase, t: Float)
	{
		val color: Int
		val alpha: Float
		when(phase)
		{
			Phase.WAKING ->
			{
				color = ORANGE
				alpha = 0.15f + 0.85f * (1f - breathe(t, 2200f))
			}
			Phase.READY ->
			{
				color = Color.WHITE
				alpha = 1f
			}
			Phase.LEAVING ->
			{
				val total = ConsoleActivityTracker.LEAVE_MS.toFloat()
				val p = clamp01(t / total)
				// the breathing period grows from 1.2 s to 3.2 s: integrate the phase
				val p0 = 1200f
				val p1 = 3200f
				val period = lerp(p0, p1, p)
				val turns = total / (p1 - p0) * ln(period / p0)
				color = Color.WHITE
				alpha = (1f - p) * (0.25f + 0.75f * (0.5f + 0.5f * cos(2f * PI.toFloat() * turns)))
			}
			Phase.SLEEPING ->
			{
				val p = clamp01(t / ConsoleActivityTracker.SLEEP_MS)
				color = lerpColor(Color.WHITE, ORANGE, p / 0.3f)
				alpha = if(p < 0.3f) lerp(1f, 0.85f, p / 0.3f) else 0.2f + 0.8f * breathe(t - 0.3f * ConsoleActivityTracker.SLEEP_MS, 3000f)
			}
			Phase.PAUSED ->
			{
				color = PAUSE_BLUE
				alpha = 0.3f + 0.7f * breathe(t, 3600f)
			}
			Phase.FAILED -> return
		}
		val w = width.toFloat()
		val h = height.toFloat()
		val bar = 4f * dp
		val glow = 22f * dp
		paint.shader = LinearGradient(0f, h - bar, 0f, h - bar - glow, withAlpha(color, alpha * 0.35f), withAlpha(color, 0f), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, h - bar - glow, w, h - bar, paint)
		paint.shader = null
		paint.color = withAlpha(color, alpha)
		canvas.drawRect(0f, h - bar, w, h, paint)
	}

	// --- Charge: the console icon fills with golden light; disconnecting it drains; suspending it
	// drains slowly, cools down to blue and "z"s float up

	private val iconRect = RectF()
	private val iconBounds = Rect()
	private val wavePath = Path()
	private val myLocation = IntArray(2)
	private val iconLocation = IntArray(2)

	private fun drawCharge(canvas: Canvas, phase: Phase, t: Float)
	{
		val icon = iconView ?: return
		val drawable = icon.drawable ?: return
		var level: Float
		var cool = 0f
		var zs = 0f
		when(phase)
		{
			Phase.WAKING -> level = 0.05f + 0.9f * easeOut(clamp01(t / ConsoleActivityTracker.WAKE_ESTIMATE_MS))
			Phase.READY -> level = lerp(0.95f, 1.1f, clamp01(t / 400f))
			Phase.LEAVING -> level = 1.05f - 1.1f * clamp01(t / ConsoleActivityTracker.LEAVE_MS)
			Phase.SLEEPING ->
			{
				val p = clamp01(t / ConsoleActivityTracker.SLEEP_MS)
				level = 1.05f - 1.1f * easeOut(p)
				cool = clamp01(p / 0.6f)
				zs = if(p > 0.5f) 1f else 0f
			}
			Phase.PAUSED ->
			{
				level = 0.82f + 0.04f * sin(t / 1300f)
				cool = 1f
			}
			Phase.FAILED -> return
		}

		if(!findIconRect())
			return

		// the icon itself as a mask, the liquid drawn only on its pixels
		val layer = canvas.saveLayer(iconRect, null)
		drawable.copyBounds(iconBounds)
		drawable.setBounds(iconRect.left.toInt(), iconRect.top.toInt(), iconRect.right.toInt(), iconRect.bottom.toInt())
		drawable.draw(canvas)
		drawable.bounds = iconBounds

		val surface = iconRect.bottom - level * iconRect.height()
		val amplitude = 3f * dp
		val shift = t / 1500f * 2f * PI.toFloat()
		wavePath.reset()
		wavePath.moveTo(iconRect.left, iconRect.bottom)
		var x = iconRect.left
		val step = 4f * dp
		while(x <= iconRect.right + step)
		{
			val u = (x - iconRect.left) / iconRect.width()
			wavePath.lineTo(x, surface + amplitude * sin(u * 2f * PI.toFloat() * 1.5f + shift))
			x += step
		}
		wavePath.lineTo(iconRect.right + step, iconRect.bottom)
		wavePath.close()
		atopPaint.shader = LinearGradient(0f, iconRect.top, 0f, iconRect.bottom,
			lerpColor(GOLD, COOL_TOP, cool), lerpColor(ORANGE_DEEP, COOL_BOTTOM, cool), Shader.TileMode.CLAMP)
		canvas.drawPath(wavePath, atopPaint)
		canvas.restoreToCount(layer)

		if(zs > 0f)
		{
			// three "z"s rising and fading, one after another
			textPaint.textSize = 15f * dp
			for(i in 0 until 3)
			{
				val cycle = ((t / 3000f) + i / 3f) % 1f
				textPaint.color = withAlpha(COOL_TOP, if(cycle < 0.3f) cycle / 0.3f else 1f - (cycle - 0.3f) / 0.7f)
				textPaint.textSize = (11f + 7f * cycle) * dp
				canvas.drawText("z", iconRect.right - 4f * dp + 14f * dp * cycle, iconRect.top + 8f * dp - 18f * dp * cycle, textPaint)
			}
		}
	}

	/** Where the console icon's picture is drawn, in this view's coordinates, into [iconRect] */
	private fun findIconRect(): Boolean
	{
		val icon = iconView ?: return false
		val drawable = icon.drawable ?: return false
		iconRect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
		icon.imageMatrix.mapRect(iconRect)
		getLocationInWindow(myLocation)
		icon.getLocationInWindow(iconLocation)
		iconRect.offset((iconLocation[0] - myLocation[0] + icon.paddingLeft).toFloat(), (iconLocation[1] - myLocation[1] + icon.paddingTop).toFloat())
		return !iconRect.isEmpty
	}

	// --- Paused (quick reconnect): a pause sign over the console, whatever the style

	private val badgeRect = RectF()

	private fun drawPauseBadge(canvas: Canvas, t: Float)
	{
		if(!findIconRect())
			return
		val size = iconRect.height().coerceAtMost(iconRect.width()) * 0.32f
		val cx = iconRect.centerX()
		val cy = iconRect.centerY()
		val bar = size * 0.32f
		val gap = size * 0.22f
		val radius = bar * 0.35f
		val alpha = 0.65f + 0.3f * breathe(t, 3600f)
		// soft dark disc behind, so the sign reads on any icon
		paint.color = withAlpha(Color.BLACK, 0.45f * alpha)
		canvas.drawCircle(cx, cy, size * 0.95f, paint)
		paint.color = withAlpha(Color.WHITE, alpha)
		badgeRect.set(cx - gap / 2f - bar, cy - size / 2f, cx - gap / 2f, cy + size / 2f)
		canvas.drawRoundRect(badgeRect, radius, radius, paint)
		badgeRect.set(cx + gap / 2f, cy - size / 2f, cx + gap / 2f + bar, cy + size / 2f)
		canvas.drawRoundRect(badgeRect, radius, radius, paint)
	}

	// --- Sunrise paused: a twilight with the moon floating (the sun is for waking and leaving)

	private val moonPath = Path()
	private val moonCut = Path()

	private fun drawMoon(canvas: Canvas, t: Float)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		paint.shader = LinearGradient(0f, 0f, 0f, h, withAlpha(0xFF101A44.toInt(), 0.75f), withAlpha(0xFF24306A.toInt(), 0.55f), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.shader = null
		val twinkle = 0.35f + 0.25f * breathe(t, 2800f)
		paint.color = withAlpha(Color.WHITE, twinkle)
		for(i in STARS.indices step 3)
			canvas.drawCircle(STARS[i] * w, STARS[i + 1] * h, STARS[i + 2] * 0.8f * dp, paint)

		val r = h * 0.13f
		val cx = w * 0.78f
		val cy = h * 0.24f + 3f * dp * sin(t / 1800f)
		// glow
		paint.shader = RadialGradient(cx, cy, r * 2.6f, withAlpha(0xFFDDE6FF.toInt(), 0.35f), 0x00DDE6FF, Shader.TileMode.CLAMP)
		canvas.drawCircle(cx, cy, r * 2.6f, paint)
		paint.shader = null
		// crescent: the moon disc minus a shifted disc
		moonPath.reset()
		moonPath.addCircle(cx, cy, r, Path.Direction.CW)
		moonCut.reset()
		moonCut.addCircle(cx - r * 0.5f, cy - r * 0.2f, r * 0.9f, Path.Direction.CW)
		moonPath.op(moonCut, Path.Op.DIFFERENCE)
		paint.color = 0xFFF2F5FF.toInt()
		canvas.drawPath(moonPath, paint)
	}

	companion object
	{
		private val PAUSE_BLUE = 0xFF3D8BFF.toInt()
		private val ORANGE = 0xFFFF8A1F.toInt()
		private val ORANGE_DEEP = 0xFFFF7A3D.toInt()
		private val GOLD = 0xFFE8B949.toInt()
		private val COOL_TOP = 0xFF9FB3FF.toInt()
		private val COOL_BOTTOM = 0xFF5C7CFA.toInt()
		/** x, y (fractions of the card) and radius (dp) of the stars */
		private val STARS = floatArrayOf(
			0.20f, 0.25f, 1.4f, 0.70f, 0.18f, 1.0f, 0.85f, 0.40f, 1.4f, 0.35f, 0.60f, 1.0f,
			0.55f, 0.35f, 1.0f, 0.12f, 0.70f, 1.4f, 0.92f, 0.12f, 1.0f, 0.45f, 0.10f, 1.2f)
	}
}
