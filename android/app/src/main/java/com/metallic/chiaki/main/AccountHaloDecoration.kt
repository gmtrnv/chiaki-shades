// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * The account's color as the main screen's theme ("Halo", the mock-up chosen with the user): the
 * background stays dark and the color glows from behind the console cards, like a light. Drawn by
 * the list itself under its cards, so it follows them wherever they are (one card at the top of a
 * tall phone, or a card filling a small landscape screen) and while they scroll:
 * - a soft rim of the color around each card;
 * - a wider glow around all the cards, a little lower than their middle, that shows mostly under them.
 * No account = nothing (the screen as it always was). Changing the account fades the color.
 */
class AccountHaloDecoration(private val list: RecyclerView) : RecyclerView.ItemDecoration()
{
	companion object
	{
		/** strength of the wide glow at its middle and of the rim next to the card (mock-up: 34 % and 25 %) */
		private const val GLOW_ALPHA = 0.34f
		private const val RIM_ALPHA = 0.25f
		/** how soft the rim is */
		private const val RIM_BLUR_DP = 20f
		/** the rim is drawn small and stretched: it is only a blur */
		private const val RIM_SCALE = 0.25f
		/** the wide glow, around one card: a bit wider and much taller than it, and lower (mock-up proportions) */
		private const val GLOW_WIDTH = 1.09f
		private const val GLOW_EXTRA_HEIGHT = 0.215f
		private const val GLOW_DROP = 0.14f
		private const val FADE_MS = 600L
	}

	private val density = list.resources.displayMetrics.density

	/** the account color drawn now, its alpha = how much of the halo shows (fades); transparent = none */
	private var current = Color.TRANSPARENT
	private var animator: ValueAnimator? = null

	private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val glowMatrix = Matrix()
	private var glowShader: RadialGradient? = null
	private var glowShaderColor = Color.TRANSPARENT

	private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private var rimBitmap: Bitmap? = null
	private var rimFor = 0L // card size the rim was made for
	private val rect = RectF()
	private val union = RectF()

	/** The account's color, or null for no account. [animate] = fade (false the first time) */
	fun setColor(color: Int?, animate: Boolean)
	{
		val target = color?.let { it or 0xFF000000.toInt() } ?: Color.TRANSPARENT
		animator?.cancel()
		if(!animate || target == current)
		{
			current = target
			list.invalidate()
			return
		}
		// fade through the same hue with no alpha, not through black, when one side is "none"
		val from = if(current == Color.TRANSPARENT) target and 0x00FFFFFF else current
		val to = if(target == Color.TRANSPARENT) current and 0x00FFFFFF else target
		animator = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
			duration = FADE_MS
			addUpdateListener { current = it.animatedValue as Int; list.invalidate() }
			start()
		}
	}

	private fun withAlpha(color: Int, alpha: Float) =
		Color.argb((alpha * Color.alpha(color)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

	override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State)
	{
		if(Color.alpha(current) == 0 || parent.childCount == 0)
			return
		var cardWidth = 0
		var cardHeight = 0
		union.setEmpty()
		for(i in 0 until parent.childCount)
		{
			val child = parent.getChildAt(i)
			if(child.width == 0 || child.height == 0)
				continue
			rect.set(child.left + child.translationX, child.top + child.translationY,
				child.right + child.translationX, child.bottom + child.translationY)
			if(rect.bottom < 0 || rect.top > parent.height)
				continue
			if(union.isEmpty) union.set(rect) else union.union(rect)
			cardWidth = max(cardWidth, child.width)
			cardHeight = max(cardHeight, child.height)
		}
		if(union.isEmpty)
			return

		drawGlow(c, cardHeight)

		val rim = rim(cardWidth, cardHeight) ?: return
		rimPaint.color = withAlpha(current, RIM_ALPHA)
		val pad = 2 * RIM_BLUR_DP * density
		for(i in 0 until parent.childCount)
		{
			val child = parent.getChildAt(i)
			if(child.width == 0 || child.height == 0)
				continue
			val left = child.left + child.translationX
			val top = child.top + child.translationY
			if(top + child.height < 0 || top > parent.height)
				continue
			rect.set(left - pad, top - pad, left + child.width + pad, top + child.height + pad)
			c.drawBitmap(rim, null, rect, rimPaint)
		}
	}

	/** the wide glow: a circle of radius 1 stretched over the cards, fading to nothing at its edge */
	private fun drawGlow(c: Canvas, cardHeight: Int)
	{
		val color = withAlpha(current, GLOW_ALPHA)
		if(glowShader == null || glowShaderColor != color)
		{
			glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(color, color and 0x00FFFFFF),
				floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
			glowShaderColor = color
		}
		val rx = union.width() / 2 * GLOW_WIDTH
		val ry = union.height() / 2 + GLOW_EXTRA_HEIGHT * cardHeight
		glowMatrix.reset()
		glowMatrix.setScale(rx, ry)
		glowMatrix.postTranslate(union.centerX(), union.centerY() + GLOW_DROP * cardHeight)
		glowShader?.setLocalMatrix(glowMatrix)
		glowPaint.shader = glowShader
		c.drawRect(union.centerX() - rx, union.centerY() + GLOW_DROP * cardHeight - ry,
			union.centerX() + rx, union.centerY() + GLOW_DROP * cardHeight + ry, glowPaint)
	}

	/**
	 * The rim for a card of this size: its shape blurred, as a mask (drawn in the account color).
	 * Made with a software canvas, where the blur works on every Android version.
	 */
	private fun rim(width: Int, height: Int): Bitmap?
	{
		val key = (width.toLong() shl 32) or height.toLong()
		if(rimBitmap != null && rimFor == key)
			return rimBitmap
		if(width <= 0 || height <= 0)
			return null
		val blur = RIM_BLUR_DP * density * RIM_SCALE
		val pad = 2 * blur
		val w = (width * RIM_SCALE + 2 * pad).toInt().coerceAtLeast(1)
		val h = (height * RIM_SCALE + 2 * pad).toInt().coerceAtLeast(1)
		val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) } catch(e: Throwable) { return null }
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			maskFilter = BlurMaskFilter(max(blur, 0.5f), BlurMaskFilter.Blur.NORMAL)
		}
		val corner = min(4 * density * RIM_SCALE, h / 2f)
		Canvas(bitmap).drawRoundRect(pad, pad, w - pad, h - pad, corner, corner, paint)
		rimBitmap?.recycle()
		rimBitmap = bitmap
		rimFor = key
		return bitmap
	}
}
