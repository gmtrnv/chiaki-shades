// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A pretend game for trying the touch pad without a console: a landscape that turns with the
 * camera stick, a ground that moves with the left stick, and a bow (L2 aims, holding R2 draws,
 * letting go shoots). It also lists what was pressed.
 */
class FakeGameView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	var videoRectProvider: (() -> RectF4)? = null
	var state = ControllerState()
	/** what the gyroscope stick is doing (second line of the top strip), empty when it is off */
	var gyroHud = ""

	private var prev = ControllerState()
	private var yaw = 0f
	private var pitch = 0f
	private var posX = 0f
	private var posZ = 0f
	private var sprint = false
	private var tension = 0f
	private var focus = false
	private var flash = 0f
	private var lastFrame = 0L
	private val log = ArrayDeque<Pair<String, Long>>()

	private val tick = object : Runnable
	{
		override fun run()
		{
			step()
			invalidate()
			if(isAttachedToWindow)
				postOnAnimation(this)
		}
	}

	override fun onAttachedToWindow()
	{
		super.onAttachedToWindow()
		lastFrame = SystemClock.uptimeMillis()
		postOnAnimation(tick)
	}

	override fun onDetachedFromWindow()
	{
		removeCallbacks(tick)
		super.onDetachedFromWindow()
	}

	private fun logEvent(text: String)
	{
		log.addLast(text to SystemClock.uptimeMillis())
		while(log.size > 5) log.removeFirst()
	}

	private val buttonNames = listOf(
		ControllerState.BUTTON_CROSS to "✕", ControllerState.BUTTON_MOON to "○",
		ControllerState.BUTTON_BOX to "□", ControllerState.BUTTON_PYRAMID to "△",
		ControllerState.BUTTON_DPAD_UP to "▲", ControllerState.BUTTON_DPAD_RIGHT to "▶",
		ControllerState.BUTTON_DPAD_DOWN to "▼", ControllerState.BUTTON_DPAD_LEFT to "◀",
		ControllerState.BUTTON_L1 to "L1", ControllerState.BUTTON_R1 to "R1",
		ControllerState.BUTTON_L3 to "L3", ControllerState.BUTTON_R3 to "R3",
		ControllerState.BUTTON_OPTIONS to "OPTIONS", ControllerState.BUTTON_PS to "PS",
		ControllerState.BUTTON_TOUCHPAD to "Touchpad")

	private fun step()
	{
		val now = SystemClock.uptimeMillis()
		val dt = min(0.05f, (now - lastFrame) / 1000f)
		lastFrame = now
		val s = state

		val l2 = s.l2State > 0U
		val r2 = s.r2State > 0U
		val l2Was = prev.l2State > 0U
		val r2Was = prev.r2State > 0U
		if(l2 && !l2Was) logEvent(context.getString(R.string.console_test_aim))
		if(!l2 && l2Was && tension > 0.05f) { logEvent(context.getString(R.string.console_test_cancel)); tension = 0f }
		if(r2 && !r2Was)
			logEvent(context.getString(if(l2) R.string.console_test_draw else R.string.console_test_heavy))
		if(!r2 && r2Was)
		{
			if(l2 && tension > 0.1f)
			{
				logEvent(context.getString(R.string.console_test_arrow, (tension * 100).toInt()))
				flash = 1f
			}
			tension = 0f
		}
		val pressed = s.buttons and prev.buttons.inv()
		for((b, name) in buttonNames)
		{
			if(pressed and b == 0U) continue
			when(b)
			{
				ControllerState.BUTTON_L3 -> { sprint = true; logEvent(context.getString(R.string.console_test_run)) }
				ControllerState.BUTTON_R3 -> { focus = !focus; logEvent(context.getString(R.string.console_test_focus)) }
				else -> logEvent(name)
			}
		}
		prev = s

		val (lx, ly) = deadzone(s.leftX / 32767f, s.leftY / 32767f)
		val (rx, ry) = deadzone(s.rightX / 32767f, s.rightY / 32767f)
		val lm = hypot(lx, ly)
		if(lm < 0.2f) sprint = false
		yaw += rx * (if(l2) 70f else 160f) * dt
		pitch = (pitch - ry * (if(l2) 50f else 110f) * dt).coerceIn(-35f, 35f)
		val speed = (if(sprint) 2.2f else 1f) * 3f * dt
		val yawRad = Math.toRadians(yaw.toDouble())
		val fx = sin(yawRad).toFloat()
		val fz = cos(yawRad).toFloat()
		// forward (-ly) along where the camera looks, sideways (lx) to its right
		posX += (fx * -ly + fz * lx) * speed
		posZ += (fz * -ly - fx * lx) * speed
		// a trigger pulled only part of the way draws the bow more slowly
		if(l2 && r2) tension = min(1f, tension + dt / 0.7f * (s.r2State.toFloat() / 255f))
		flash = max(0f, flash - dt * 3f)
	}

	/** like a game: small pushes do nothing */
	private fun deadzone(x: Float, y: Float): Pair<Float, Float>
	{
		val m = hypot(x, y)
		if(m < 0.12f) return 0f to 0f
		val k = min(1f, (m - 0.12f) / 0.88f) / m
		return x * k to y * k
	}

	/**
	 * A grid on the ground seen from the camera (eye 1.7 above it): it slides when walking,
	 * stepping sideways or turning, like a real floor.
	 */
	private fun drawGround(canvas: Canvas, cx: Float, hy: Float, pxPerRad: Float)
	{
		val yawRad = Math.toRadians(yaw.toDouble())
		val s = sin(yawRad).toFloat()
		val c = cos(yawRad).toFloat()
		val eye = 1.7f
		val near = 0.4f
		val step = 2f
		val reach = 40f
		// world → camera: x to the right, z forward
		fun cam(wx: Float, wz: Float): Pair<Float, Float>
		{
			val dx = wx - posX
			val dz = wz - posZ
			return (dx * c - dz * s) to (dx * s + dz * c)
		}
		fun line(ax: Float, az: Float, bx: Float, bz: Float)
		{
			var (x1, z1) = cam(ax, az)
			var (x2, z2) = cam(bx, bz)
			if(z1 < near && z2 < near) return
			if(z1 < near) { val t = (near - z1) / (z2 - z1); x1 += (x2 - x1) * t; z1 = near }
			if(z2 < near) { val t = (near - z2) / (z1 - z2); x2 += (x1 - x2) * t; z2 = near }
			canvas.drawLine(cx + x1 / z1 * pxPerRad, hy + eye / z1 * pxPerRad,
				cx + x2 / z2 * pxPerRad, hy + eye / z2 * pxPerRad, paint)
		}
		val baseX = Math.floor((posX / step).toDouble()).toFloat() * step
		val baseZ = Math.floor((posZ / step).toDouble()).toFloat() * step
		var k = -reach
		while(k <= reach)
		{
			line(baseX + k, baseZ - reach, baseX + k, baseZ + reach)
			line(baseX - reach, baseZ + k, baseX + reach, baseZ + k)
			k += step
		}
	}

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
	private val path = Path()
	private val rect = RectF()

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		// only the "screen": the shell behind it draws the body and the bezel
		val v = videoRectProvider?.invoke() ?: return
		val d = resources.displayMetrics.density

		canvas.save()
		canvas.clipRect(v.x, v.y, v.right, v.bottom)
		val l2 = state.l2State > 0U
		val pxDeg = v.w / 90f * (if(l2) 1.6f else 1f)
		val cx = v.cx
		val hy = v.y + v.h * 0.55f + pitch * pxDeg
		paint.shader = LinearGradient(0f, v.y, 0f, hy, 0xFF2B4D7A.toInt(), 0xFFF2A65E.toInt(), Shader.TileMode.CLAMP)
		canvas.drawRect(v.x, v.y, v.right, v.bottom, paint)
		paint.shader = null

		val sunDeg = ((30f - yaw) % 360f + 540f) % 360f - 180f
		paint.color = 0xFFFFE2A0.toInt()
		canvas.drawCircle(cx + sunDeg * pxDeg, hy - 14f * pxDeg, 4f * pxDeg, paint)

		val layers = listOf(Triple(0.35f, 0xFF5D4A6E.toInt(), 9f to 1f), Triple(0.7f, 0xFF3D3550.toInt(), 6f to 2.1f),
			Triple(1f, 0xFF262433.toInt(), 4f to 3.7f))
		for((par, color, af) in layers)
		{
			val (amp, f) = af
			paint.color = color
			path.reset()
			path.moveTo(v.x, v.bottom)
			var x = v.x
			while(x <= v.right + 4f)
			{
				val deg = (x - cx) / pxDeg + yaw * par
				val hh = (sin(deg * 0.045f * f + f) + 0.5f * sin(deg * 0.11f * f) + 1.5f) * amp
				path.lineTo(x, hy - hh * pxDeg)
				x += 4f * d
			}
			path.lineTo(v.right, v.bottom)
			path.close()
			canvas.drawPath(path, paint)
		}
		paint.color = 0xFF2F3B26.toInt()
		canvas.drawRect(v.x, hy, v.right, v.bottom, paint)
		paint.color = 0x59A0BE78
		paint.strokeWidth = d
		drawGround(canvas, cx, hy, pxDeg * 57.2958f)

		if(focus)
		{
			paint.color = 0x2400C8FF
			canvas.drawRect(v.x, v.y, v.right, v.bottom, paint)
		}
		val ccy = v.cy
		if(l2)
		{
			val r = (6f + (1f - tension) * 22f) * d
			paint.style = Paint.Style.STROKE
			paint.strokeWidth = 2f * d
			paint.color = if(tension > 0.98f) 0xFFFFFF66.toInt() else 0xFFFFFFFF.toInt()
			canvas.drawCircle(cx, ccy, r, paint)
			canvas.drawLine(cx - 4 * d, ccy, cx + 4 * d, ccy, paint)
			canvas.drawLine(cx, ccy - 4 * d, cx, ccy + 4 * d, paint)
			paint.style = Paint.Style.FILL
		}
		if(flash > 0f)
		{
			paint.color = ((flash * 255).toInt() shl 24) or 0xFFF0B4
			paint.strokeWidth = 3f * d
			canvas.drawLine(cx + 30 * d, v.bottom, cx, ccy, paint)
		}

		// what the pad sends
		textPaint.textSize = 12f * d
		paint.color = 0x73000000
		canvas.drawRect(v.x, v.y, v.right, v.y + (if(gyroHud.isEmpty()) 18 else 32) * d, paint)
		val lm = hypot(state.leftX / 32767f, state.leftY / 32767f)
		val walk = context.getString(when
		{
			sprint -> R.string.console_test_running
			lm > 0.6f -> R.string.console_test_trotting
			lm > 0.12f -> R.string.console_test_walking
			else -> R.string.console_test_still
		})
		val held = buttonNames.filter { state.buttons and it.first != 0U }.joinToString(" ") { it.second } +
			(if(l2) " L2 ${state.l2State.toInt() * 100 / 255}%" else "") +
			(if(state.r2State > 0U) " R2 ${state.r2State.toInt() * 100 / 255}%" else "")
		canvas.drawText("$walk · L(%.2f, %.2f) R(%.2f, %.2f) ·%s".format(state.leftX / 32767f, state.leftY / 32767f,
			state.rightX / 32767f, state.rightY / 32767f, held), v.x + 6 * d, v.y + 13 * d, textPaint)
		if(gyroHud.isNotEmpty())
			canvas.drawText(gyroHud, v.x + 6 * d, v.y + 27 * d, textPaint)
		if(focus)
			canvas.drawText(context.getString(R.string.console_test_focus_on), v.right - 60 * d, v.y + 48 * d, textPaint)

		val now = SystemClock.uptimeMillis()
		log.forEachIndexed { i, (text, t) ->
			val a = max(0f, 1f - (now - t) / 4000f)
			if(a <= 0f) return@forEachIndexed
			val y = v.bottom - 18 * d * (log.size - i)
			paint.color = ((a * 128).toInt() shl 24)
			canvas.drawRect(v.x + 4 * d, y - 4 * d, v.x + 220 * d, y + 12 * d, paint)
			textPaint.alpha = (a * 255).toInt()
			canvas.drawText(text, v.x + 8 * d, y + 8 * d, textPaint)
			textPaint.alpha = 255
		}
		canvas.restore()
	}
}
