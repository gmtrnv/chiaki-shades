// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * The shells drawn behind the console-mode pad. Our own design (a handheld feel: a body,
 * a bezel round the screen, wells under the thumbs), not any real console's shape or colours.
 */
enum class ShellTheme(
	val value: String,
	val bodyStart: Int, val bodyEnd: Int,
	val bezel: Int,
	/** edges, symbols and highlights */
	val accent: Int,
	/** recess under the sticks and buttons */
	val well: Int,
	val zoneFill: Int, val zoneStroke: Int, val label: Int,
	val buttonFill: Int, val pressedText: Int)
{
	/** the identity of Chiaki Shades: dusk purple to orange, golden edges */
	SUNSET("sunset", 0xFF4A2A6E.toInt(), 0xFFE07A3C.toInt(), 0xFF180C22.toInt(), 0xFFFFD47E.toInt(), 0x33140820,
		0x1FFFFFFF, 0x8CFFD47E.toInt(), 0xB3FFE6B4.toInt(), 0x33000000, 0xFF2A1030.toInt()),
	/** matte black with golden details */
	GRAPHITE("graphite", 0xFF2A2A2E.toInt(), 0xFF111113.toInt(), 0xFF050505.toInt(), 0xFFD4AB55.toInt(), 0x59000000,
		0x14FFFFFF, 0x73D4AB55, 0x8CD4AB55.toInt(), 0x40000000, 0xFF111111.toInt()),
	/** light bluish grey */
	ICE("ice", 0xFFDDE4EB.toInt(), 0xFF9FB2C3.toInt(), 0xFF1C2630.toInt(), 0xFF2F4F6C.toInt(), 0x1F14283C,
		0x14000000, 0x802F4F6C.toInt(), 0x992F4F6C.toInt(), 0x59FFFFFF, 0xFFFFFFFF.toInt());

	companion object
	{
		const val KEY = "console_shell_theme"
		fun from(s: String?) = values().firstOrNull { it.value == s } ?: SUNSET
		fun load(prefs: SharedPreferences) = from(prefs.getString(KEY, null))
	}
}

class ShellView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	/** where the zones and the video are */
	var pad: ConsolePadView? = null
		set(value) { field = value; invalidate() }

	var theme = ShellTheme.SUNSET
		set(value) { field = value; invalidate() }

	/** 0..1: under 1 the shell fades into black (0 = only the layout of the screen is left) */
	var opacity = 1f
		set(value) { field = value; invalidate() }

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val rect = RectF()

	init
	{
		// Kept as a ready-made picture: the shell changes rarely, but without this its full-screen
		// gradients were painted again every time anything in the window redrew (every frame while
		// a finger is on the controls)
		setLayerType(LAYER_TYPE_HARDWARE, null)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(opacity >= 1f)
		{
			drawShell(canvas)
			return
		}
		canvas.drawColor(0xFF000000.toInt())
		if(opacity <= 0f)
			return
		val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (opacity * 255).toInt())
		drawShell(canvas)
		canvas.restoreToCount(layer)
	}

	private fun drawShell(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		val t = theme

		// body: a diagonal gradient and a soft light from the top left
		paint.shader = LinearGradient(0f, 0f, w, h, t.bodyStart, t.bodyEnd, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.shader = RadialGradient(w * 0.22f, 0f, max(w, h) * 0.7f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.shader = null

		val pad = pad ?: return
		val g = pad.geometry
		val m = g.mm

		// a thin edge all round, like the rim of a handheld
		stroke.color = (t.accent and 0x00FFFFFF) or 0x40000000
		stroke.strokeWidth = max(1.5f, 0.4f * m)
		val inset = 1.2f * m
		rect.set(inset, inset, w - inset, h - inset)
		canvas.drawRoundRect(rect, 6f * m, 6f * m, stroke)

		// wells under the thumbs: round recesses with a light lower rim
		for(z in pad.padLayout.active)
		{
			val r = g.rect(z)
			val radius = when(z.kind)
			{
				ZoneKind.STICK, ZoneKind.CAM -> min(r.w, r.h) * 0.46f
				ZoneKind.FACE, ZoneKind.DPAD -> min(r.w, r.h) * 0.52f
				else -> continue
			}
			paint.color = t.well
			canvas.drawCircle(r.cx, r.cy, radius, paint)
			stroke.color = 0x1FFFFFFF
			stroke.strokeWidth = max(1f, 0.3f * m)
			rect.set(r.cx - radius, r.cy - radius, r.cx + radius, r.cy + radius)
			canvas.drawArc(rect, 20f, 140f, false, stroke)
		}

		// bezel round the screen
		val v = pad.videoRect
		val b = 1.8f * m
		rect.set(v.x - b, v.y - b, v.right + b, v.bottom + b)
		paint.color = t.bezel
		canvas.drawRoundRect(rect, 3f * m, 3f * m, paint)
		stroke.color = (t.accent and 0x00FFFFFF) or 0x99000000.toInt()
		stroke.strokeWidth = max(1.5f, 0.35f * m)
		canvas.drawRoundRect(rect, 3f * m, 3f * m, stroke)
	}
}
