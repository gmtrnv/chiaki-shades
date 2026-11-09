// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.Window
import android.view.WindowManager
import com.metallic.chiaki.lib.ControllerState
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The console-mode touch pad (phase 12): zones measured in millimetres so the pad lands in
 * the same place under the thumbs on any phone. The defaults come from the layout the user
 * tuned on their phone (157x73 mm) with the HTML prototype in _mods/prototipo-consola/.
 */

enum class ZoneKind
{
	/** left stick: the finger that lands here moves it until it is lifted */
	STICK,
	/** right stick (camera): like STICK, or "drag like a mouse" */
	CAM,
	/** L2/R2: held while the finger is down, and the finger also moves that side's stick */
	TRIGGER,
	/** cross of four buttons, can be slid across */
	DPAD,
	/** △○✕□, can be slid across */
	FACE,
	/** a single button (L1/R1 slide-able, PS/Options only by a direct touch) */
	BUTTON,
}

enum class Side { L, R, C }

class Zone(
	val id: String,
	val kind: ZoneKind,
	val side: Side,
	/** y measured from the top edge instead of the bottom one */
	val top: Boolean,
	var x: Float,
	var y: Float,
	var w: Float,
	var h: Float,
	/** BUTTON/TRIGGER: what it presses */
	val button: UInt = 0U,
	/** BUTTON: only a finger that starts on it presses it (no sliding onto PS/Options) */
	val fixed: Boolean = false,
	/** BUTTON: drawn as a circle */
	val round: Boolean = false)
{
	fun copy() = Zone(id, kind, side, top, x, y, w, h, button, fixed, round)
}

/**
 * The video: as wide as the space between [marginX] on each side allows (16:9), [top] from the
 * top edge, never closer than [bottom] to the bottom edge (on wider screens it would grow over
 * the controls under it).
 */
class VideoBox(var marginX: Float, var top: Float, var dx: Float, var bottom: Float)
{
	fun copy() = VideoBox(marginX, top, dx, bottom)
}

/**
 * Where the touchpad is: tied to the video (fractions of the video, whole video by default; it moves
 * and grows with it) or [free] (fractions of the screen, anywhere). Smaller than the video, controls
 * can sit over the rest of it.
 */
class TouchArea(var left: Float = 0f, var top: Float = 0f, var right: Float = 1f, var bottom: Float = 1f,
	var free: Boolean = false)
{
	fun copy() = TouchArea(left, top, right, bottom, free)
	private fun on(v: RectF4) = RectF4(v.x + left * v.w, v.y + top * v.h, (right - left) * v.w, (bottom - top) * v.h)
	/** on screen, given where the video is and the screen's size */
	fun rect(video: RectF4, width: Float, height: Float) = if(free) on(RectF4(0f, 0f, width, height)) else on(video)
	/** set from a place on screen, in what it is measured against now (video or screen) */
	fun set(r: RectF4, video: RectF4, width: Float, height: Float)
	{
		val ref = if(free) RectF4(0f, 0f, width, height) else video
		left = ((r.x - ref.x) / ref.w).coerceIn(0f, 1f)
		top = ((r.y - ref.y) / ref.h).coerceIn(0f, 1f)
		right = ((r.right - ref.x) / ref.w).coerceIn(left, 1f)
		bottom = ((r.bottom - ref.y) / ref.h).coerceIn(top, 1f)
	}
}

class ConsolePadLayout(
	/** first = lowest: drawn under the rest, and the one on top takes a touch where they overlap */
	val zones: MutableList<Zone>,
	val video: VideoBox,
	val touch: TouchArea = TouchArea(),
	/** controls switched off (zone ids, and [TOUCHPAD] for the touchpad): not drawn, not touchable */
	val hidden: MutableSet<String> = DEFAULT_HIDDEN.toMutableSet())
{
	companion object
	{
		const val KEY = "console_pad_layout"
		private const val VERSION = 1

		/** controls are designed for this screen height; lower screens shrink them */
		const val REFERENCE_HEIGHT_MM = 72f

		/** the touchpad in [hidden] */
		const val TOUCHPAD = "touchpad"
		/** Share, L3 and R3 buttons are there for who wants them (L3/R3 also come from the stick gestures) */
		val DEFAULT_HIDDEN = setOf("share", "l3", "r3", "gyro")

		/** the zones that mirror each other when editing with "symmetric" on */
		val partners = mapOf("lstick" to "rstick", "dpad" to "face", "l1" to "r1", "l2" to "r2", "l3" to "r3")
			.let { m -> m + m.entries.associate { (a, b) -> b to a } }

		fun defaults(): ConsolePadLayout
		{
			// third phone test with the PS5 (157x73 mm, whole screen): everything a little towards
			// the middle, so the hand resting on the edges presses nothing; sticks low but off the
			// corners, d-pad/face above them, L2/R2 beside the sticks with L1/R1 on top, PS and
			// Options stacked in the middle, the video at the top between the columns
			val left = listOf(
				Zone("lstick", ZoneKind.STICK, Side.L, false, 10.3f, 13f, 24.8f, 26.8f),
				Zone("dpad", ZoneKind.DPAD, Side.L, false, 6.6f, 40.5f, 30f, 30f),
				Zone("l1", ZoneKind.BUTTON, Side.L, false, 36.1f, 18f, 25.7f, 6.1f, ControllerState.BUTTON_L1),
				Zone("l2", ZoneKind.TRIGGER, Side.L, false, 36f, 3.6f, 28.6f, 12.3f, BUTTON_L2),
				// off by default: small, in the corner beside the stick
				Zone("l3", ZoneKind.BUTTON, Side.L, false, 1.5f, 2f, 8f, 8f, ControllerState.BUTTON_L3, fixed = true, round = true))
			val right = listOf(
				Zone("rstick", ZoneKind.CAM, Side.R, false, 10.3f, 13f, 24.8f, 26.8f),
				Zone("face", ZoneKind.FACE, Side.R, false, 6.6f, 40.5f, 30f, 30f),
				Zone("r1", ZoneKind.BUTTON, Side.R, false, 36.1f, 18f, 25.7f, 6.1f, ControllerState.BUTTON_R1),
				Zone("r2", ZoneKind.TRIGGER, Side.R, false, 36f, 3.6f, 28.6f, 12.3f, BUTTON_R2),
				Zone("r3", ZoneKind.BUTTON, Side.R, false, 1.5f, 2f, 8f, 8f, ControllerState.BUTTON_R3, fixed = true, round = true))
			// first in the list = lowest priority when zones overlap (narrow screens)
			val center = listOf(
				Zone("ps", ZoneKind.BUTTON, Side.C, false, -7.1f, 0.9f, 14.2f, 14.2f, ControllerState.BUTTON_PS, fixed = true, round = true),
				Zone("options", ZoneKind.BUTTON, Side.C, false, -10.4f, 17.5f, 20.8f, 6f, ControllerState.BUTTON_OPTIONS, fixed = true),
				// off by default: left of PS
				Zone("share", ZoneKind.BUTTON, Side.C, false, -21.5f, 4.6f, 12f, 6f, ControllerState.BUTTON_SHARE, fixed = true),
				// off by default: right of PS; switches the gyroscope stick on and off
				Zone("gyro", ZoneKind.BUTTON, Side.C, false, 9.5f, 3.2f, 10f, 10f, BUTTON_GYRO, fixed = true, round = true))
			return ConsolePadLayout((center + left + right).toMutableList(), VideoBox(39.3f, 3.1f, 0f, 25.5f))
		}

		/** sets the geometry of the zones of [l] by id: x, y, w, h (partners get the same numbers) */
		private fun place(l: ConsolePadLayout, vararg g: Pair<String, FloatArray>): ConsolePadLayout
		{
			for((id, v) in g)
				for(zid in listOfNotNull(id, partners[id]))
					l.zones.firstOrNull { it.id == zid }?.let { z ->
						if(zid == id || z.side != Side.C) { z.x = v[0]; z.y = v[1]; z.w = v[2]; z.h = v[3] }
					}
			return l
		}

		/**
		 * "Big screen": the user's own layout on their phone (phase 13, measured from a screenshot
		 * and tidied up: symmetric, video centred): a larger video with the button groups over its
		 * top corners, sticks below them, L1/L2 along the bottom edge, PS and Options in the middle.
		 */
		fun big(): ConsolePadLayout
		{
			val l = defaults()
			place(l,
				"lstick" to floatArrayOf(6.8f, 11f, 22f, 28.5f),
				"dpad" to floatArrayOf(6.8f, 41f, 30f, 30f),
				"l1" to floatArrayOf(8.3f, 3.4f, 25.8f, 6.1f),
				"l2" to floatArrayOf(33.8f, 6f, 29.8f, 8.5f),
				"l3" to floatArrayOf(0.8f, 1f, 7f, 7f),
				"ps" to floatArrayOf(-12.6f, 4.2f, 11.4f, 11.4f),
				"options" to floatArrayOf(1.2f, 6.9f, 12.6f, 6f),
				"share" to floatArrayOf(-6f, 17.5f, 12f, 6f),
				"gyro" to floatArrayOf(-25f, 4.2f, 10f, 10f))
			l.video.marginX = 23.1f; l.video.top = 4.4f; l.video.dx = 0f; l.video.bottom = 6.5f
			return l
		}

		/**
		 * "Compact": for squarer screens (3:2, 4:3, like many handhelds), after a layout the user
		 * tuned on one: the video at the top in the middle with the button groups beside it, sticks
		 * under those, L1/R1 and Options in the middle, L2/R2 in the bottom corners, PS at the bottom.
		 */
		fun compact(): ConsolePadLayout
		{
			val l = defaults()
			place(l,
				"dpad" to floatArrayOf(2f, 45f, 26f, 26f),
				"lstick" to floatArrayOf(6f, 10f, 24f, 26f),
				"l2" to floatArrayOf(1f, 1f, 20f, 8f),
				"l1" to floatArrayOf(30f, 22f, 16f, 6f),
				"l3" to floatArrayOf(24f, 1f, 7f, 7f),
				"options" to floatArrayOf(-8f, 33f, 16f, 6f),
				"ps" to floatArrayOf(-6f, 2f, 12f, 12f),
				"share" to floatArrayOf(-21f, 4f, 12f, 6f),
				"gyro" to floatArrayOf(8f, 2f, 10f, 10f))
			l.video.marginX = 29f; l.video.top = 1f; l.video.dx = 0f; l.video.bottom = 0f
			return l
		}

		/** a layout from its JSON (the saved one, a slot's); anything missing or unreadable comes from the defaults */
		fun fromJson(o: JSONObject?): ConsolePadLayout
		{
			val def = defaults()
			if(o == null)
				return def
			return try
			{
				if(o.optInt("v") != VERSION)
					return def
				val saved = o.getJSONObject("zones")
				// geometry from the file, everything else from the defaults (zones never appear or vanish)
				def.zones.forEach { z ->
					saved.optJSONArray(z.id)?.let {
						z.x = it.getDouble(0).toFloat(); z.y = it.getDouble(1).toFloat()
						z.w = it.getDouble(2).toFloat(); z.h = it.getDouble(3).toFloat()
					}
				}
				o.optJSONArray("video")?.let {
					def.video.marginX = it.getDouble(0).toFloat(); def.video.top = it.getDouble(1).toFloat()
					def.video.dx = it.getDouble(2).toFloat()
					def.video.bottom = it.optDouble(3, 0.0).toFloat()
				}
				o.optJSONArray("touch")?.let {
					def.touch.left = it.getDouble(0).toFloat(); def.touch.top = it.getDouble(1).toFloat()
					def.touch.right = it.getDouble(2).toFloat(); def.touch.bottom = it.getDouble(3).toFloat()
					def.touch.free = it.optInt(4, 0) == 1
				}
				// which control is over which (phase 13); ids it does not know keep their place under the rest
				o.optJSONArray("order")?.let { a ->
					val ids = (0 until a.length()).map { a.getString(it) }
					val known = ids.mapNotNull { id -> def.zones.firstOrNull { it.id == id } }
					val rest = def.zones.filter { it !in known }
					def.zones.clear()
					def.zones.addAll(rest + known)
				}
				// layouts saved before phase 13 have no list: the default one
				o.optJSONArray("hidden")?.let { a ->
					def.hidden.clear()
					for(i in 0 until a.length()) def.hidden.add(a.getString(i))
				}
				def
			}
			catch(e: Exception) { def }
		}

		fun load(prefs: SharedPreferences): ConsolePadLayout =
			fromJson(prefs.getString(KEY, null)?.let { try { JSONObject(it) } catch(e: Exception) { null } })

		fun reset(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()
	}

	/**
	 * Controls that cannot work with the options as they are now (the gyroscope button with the gyroscope stick
	 * off): not drawn and not touchable, but neither switched off nor moved: when they can work again they are
	 * where they were. Set by the pad view, never saved.
	 */
	var unavailable: Set<String> = emptySet()

	/** the controls switched on (and able to work) */
	val active get() = zones.filter { it.id !in hidden && it.id !in unavailable }
	val touchpadOn get() = TOUCHPAD !in hidden

	fun toJson(): JSONObject
	{
		val z = JSONObject()
		zones.forEach { z.put(it.id, JSONArray(listOf(r(it.x), r(it.y), r(it.w), r(it.h)))) }
		return JSONObject()
			.put("v", VERSION)
			.put("zones", z)
			.put("video", JSONArray(listOf(r(video.marginX), r(video.top), r(video.dx), r(video.bottom))))
			.put("touch", JSONArray(listOf(r3(touch.left), r3(touch.top), r3(touch.right), r3(touch.bottom), if(touch.free) 1 else 0)))
			.put("hidden", JSONArray(hidden.sorted()))
			.put("order", JSONArray(zones.map { it.id }))
	}

	fun save(prefs: SharedPreferences) = prefs.edit().putString(KEY, toJson().toString()).apply()

	private fun r(f: Float) = Math.round(f * 10.0) / 10.0
	private fun r3(f: Float) = Math.round(f * 1000.0) / 1000.0

	fun copy() = ConsolePadLayout(zones.map { it.copy() }.toMutableList(), video.copy(), touch.copy(), hidden.toMutableSet())
}

/**
 * Named layouts the user keeps (phase 13): a slot is a whole layout (places, sizes, video, touch
 * area, controls on/off); the pad's options stay global. What is on screen is always "the current
 * layout": loading a slot replaces it, editing does not touch the slot until it is overwritten.
 */
/** the layouts that come with the app: they can be loaded, not overwritten, renamed nor deleted */
enum class BuiltinLayout(val id: String, val nameRes: Int, val make: () -> ConsolePadLayout)
{
	CLASSIC("#classic", com.metallic.chiaki.R.string.console_layout_builtin_classic, { ConsolePadLayout.defaults() }),
	BIG("#big", com.metallic.chiaki.R.string.console_layout_builtin_big, { ConsolePadLayout.big() }),
	COMPACT("#compact", com.metallic.chiaki.R.string.console_layout_builtin_compact, { ConsolePadLayout.compact() });
	companion object { fun from(name: String?) = values().firstOrNull { it.id == name } }
}

object LayoutSlots
{
	const val KEY = "console_pad_slots"
	/** name of the slot last loaded or saved */
	const val KEY_CURRENT = "console_pad_slot_current"

	class Slot(val name: String, val layout: JSONObject)

	fun list(prefs: SharedPreferences): List<Slot>
	{
		val json = prefs.getString(KEY, null) ?: return emptyList()
		return try
		{
			val a = JSONObject(json).getJSONArray("slots")
			(0 until a.length()).map { a.getJSONObject(it) }.map { Slot(it.getString("name"), it.getJSONObject("layout")) }
		}
		catch(e: Exception) { emptyList() }
	}

	private fun store(prefs: SharedPreferences, slots: List<Slot>)
	{
		val a = JSONArray()
		slots.forEach { a.put(JSONObject().put("name", it.name).put("layout", it.layout)) }
		prefs.edit().putString(KEY, JSONObject().put("slots", a).toString()).apply()
	}

	fun current(prefs: SharedPreferences) = prefs.getString(KEY_CURRENT, null)
	private fun setCurrent(prefs: SharedPreferences, name: String?) =
		prefs.edit().apply { if(name == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, name) }.apply()

	/** a new slot, or the one with that name overwritten */
	fun save(prefs: SharedPreferences, name: String, layout: ConsolePadLayout)
	{
		val slots = list(prefs).filter { it.name != name } + Slot(name, layout.toJson())
		store(prefs, slots)
		setCurrent(prefs, name)
	}

	fun load(prefs: SharedPreferences, name: String): ConsolePadLayout?
	{
		BuiltinLayout.from(name)?.let { setCurrent(prefs, name); return it.make() }
		val slot = list(prefs).firstOrNull { it.name == name } ?: return null
		setCurrent(prefs, name)
		return ConsolePadLayout.fromJson(slot.layout)
	}

	fun rename(prefs: SharedPreferences, from: String, to: String)
	{
		store(prefs, list(prefs).map { if(it.name == from) Slot(to, it.layout) else it })
		if(current(prefs) == from) setCurrent(prefs, to)
	}

	fun delete(prefs: SharedPreferences, name: String)
	{
		store(prefs, list(prefs).filter { it.name != name })
		if(current(prefs) == name) setCurrent(prefs, null)
	}

	/** the current layout differs from the slot it came from (shown with a *) */
	fun modified(prefs: SharedPreferences, layout: ConsolePadLayout): Boolean
	{
		val name = current(prefs) ?: return false
		BuiltinLayout.from(name)?.let { return it.make().toJson().toString() != layout.toJson().toString() }
		val slot = list(prefs).firstOrNull { it.name == name } ?: return false
		return ConsolePadLayout.fromJson(slot.layout).toJson().toString() != layout.toJson().toString()
	}

	/** a free name like "Layout 3" */
	fun freeName(prefs: SharedPreferences, base: String): String
	{
		val names = list(prefs).map { it.name }.toSet()
		var i = names.size + 1
		while("$base $i" in names) i++
		return "$base $i"
	}

	/** after a reset the screen holds the classic layout */
	fun clearCurrent(prefs: SharedPreferences) = setCurrent(prefs, BuiltinLayout.CLASSIC.id)
}

/** Where things are on a given view, in pixels. */
class PadGeometry(val width: Float, val height: Float, metrics: DisplayMetrics)
{
	/** real pixels per millimetre (xdpi), unless the device reports nonsense */
	private val pxPerMmReal: Float = run {
		val nominal = metrics.densityDpi / 25.4f
		val real = (metrics.xdpi + metrics.ydpi) / 2f / 25.4f
		if(real > 0 && abs(real / nominal - 1f) < 0.35f) real else nominal
	}

	val heightMm get() = height / pxPerMmReal
	val widthMm get() = width / pxPerMmReal

	/** pixels per (layout) millimetre: lower screens shrink the controls */
	val mm: Float = pxPerMmReal * min(1f, height / pxPerMmReal / ConsolePadLayout.REFERENCE_HEIGHT_MM)

	fun rect(z: Zone): RectF4
	{
		val w = z.w * mm
		val h = z.h * mm
		val x = when(z.side)
		{
			Side.L -> z.x * mm
			Side.R -> width - z.x * mm - w
			Side.C -> width / 2f + z.x * mm
		}
		val y = if(z.top) z.y * mm else height - z.y * mm - h
		return RectF4(x, y, w, h)
	}

	/** the other way round: puts [z] at [r] on screen */
	fun setRect(z: Zone, r: RectF4)
	{
		z.w = r.w / mm
		z.h = r.h / mm
		z.x = when(z.side)
		{
			Side.L -> r.x / mm
			Side.R -> (width - r.right) / mm
			Side.C -> (r.x - width / 2f) / mm
		}
		z.y = if(z.top) r.y / mm else (height - r.bottom) / mm
	}

	/** [keepBottom] false while the video is being edited: the gap under it is what the user leaves */
	fun video(v: VideoBox, keepBottom: Boolean = true): RectF4
	{
		var w = max(width * 0.2f, width - 2f * v.marginX * mm)
		var h = w * 9f / 16f
		val maxH = max(height * 0.2f, height - max(0f, v.top) * mm - (if(keepBottom) max(0f, v.bottom) * mm else 0f))
		if(h > maxH) { h = maxH; w = h * 16f / 9f }
		if(w > width) { w = width; h = w * 9f / 16f }
		val x = ((width - w) / 2f + v.dx * mm).coerceIn(0f, width - w)
		val y = (v.top * mm).coerceIn(0f, max(0f, height - h))
		return RectF4(x, y, w, h)
	}
}

data class RectF4(val x: Float, val y: Float, val w: Float, val h: Float)
{
	val right get() = x + w
	val bottom get() = y + h
	val cx get() = x + w / 2f
	val cy get() = y + h / 2f
	fun contains(px: Float, py: Float) = px >= x && px <= right && py >= y && py <= bottom
	/** distance from the edge: negative inside */
	fun distance(px: Float, py: Float): Float
	{
		val dx = max(max(x - px, 0f), px - right)
		val dy = max(max(y - py, 0f), py - bottom)
		if(dx > 0f || dy > 0f)
			return kotlin.math.hypot(dx, dy)
		return -min(min(px - x, right - px), min(py - y, bottom - py))
	}
}

/**
 * Draw over the camera cut-out too (Android leaves that strip empty by default): the console
 * mode and its test screen use the whole screen, and only the shell falls under the camera.
 */
fun useWholeScreen(window: Window, whole: Boolean = true)
{
	if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
		return
	window.attributes = window.attributes.also {
		it.layoutInDisplayCutoutMode =
			if(whole) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
	}
}

/** L2/R2 are analog in ControllerState; inside the pad they are handled as two extra "buttons". */
val BUTTON_L2 = (1 shl 20).toUInt()
val BUTTON_R2 = (1 shl 21).toUInt()
/** the pad's own gyroscope button: it switches the gyroscope stick on and off and never reaches the console */
val BUTTON_GYRO = (1 shl 22).toUInt()

/** how a stick's own click (L3 / R3) is made; "squash" is its own choice now (see [PadButton]) */
enum class ClickMode(val value: String)
{
	DOUBLE_TAP("double"), EDGE("edge"), OFF("off");
	companion object { fun from(s: String?, default: ClickMode) = values().firstOrNull { it.value == s } ?: default }
}

/**
 * A button that squashing the thumb (or pressing a trigger harder) can hold: picked in a dialog
 * and saved by [value]. [group] orders the dialog's list.
 */
enum class PadButton(val value: String, val button: UInt, val label: String, val group: Int)
{
	L3("l3", ControllerState.BUTTON_L3, "L3", 0), R3("r3", ControllerState.BUTTON_R3, "R3", 0),
	L2("l2", BUTTON_L2, "L2", 1), R2("r2", BUTTON_R2, "R2", 1),
	L1("l1", ControllerState.BUTTON_L1, "L1", 2), R1("r1", ControllerState.BUTTON_R1, "R1", 2),
	CROSS("cross", ControllerState.BUTTON_CROSS, "✕", 3), CIRCLE("circle", ControllerState.BUTTON_MOON, "○", 3),
	SQUARE("square", ControllerState.BUTTON_BOX, "□", 3), TRIANGLE("triangle", ControllerState.BUTTON_PYRAMID, "△", 3),
	UP("up", ControllerState.BUTTON_DPAD_UP, "▲", 4), DOWN("down", ControllerState.BUTTON_DPAD_DOWN, "▼", 4),
	LEFT("left", ControllerState.BUTTON_DPAD_LEFT, "◀", 4), RIGHT("right", ControllerState.BUTTON_DPAD_RIGHT, "▶", 4),
	OPTIONS("options", ControllerState.BUTTON_OPTIONS, "OPTIONS", 5), SHARE("share", ControllerState.BUTTON_SHARE, "SHARE", 5),
	PS("ps", ControllerState.BUTTON_PS, "PS", 5);
	companion object
	{
		fun from(s: String?) = values().firstOrNull { it.value == s }
		/** what is saved for "nothing" */
		const val NONE = "none"
		fun save(b: PadButton?) = b?.value ?: NONE
	}
}

/** colour of the controls' outlines, names and symbols ([color] null = the shell's own) */
enum class LineColor(val value: String, val color: Int?)
{
	THEME("theme", null), BLUE("blue", 0xFF4FA3FF.toInt()), BLACK("black", 0xFF000000.toInt()), WHITE("white", 0xFFFFFFFF.toInt());
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: THEME }
}

/**
 * Cinematic immersion (Shell mode): when the controls fade out, the video glides to fill the
 * screen like this display mode, and back to its place at the next touch.
 */
enum class CinemaMode(val value: String)
{
	OFF("off"), FIT("fit"), STRETCH("stretch"), ZOOM("zoom"), OPTICAL("optical");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: OFF }

	/** where the picture ends up on a screen of w x h ([aspect] = the stream's width / height) */
	fun target(w: Float, h: Float, aspect: Float): RectF4 = when(this)
	{
		STRETCH, OPTICAL, OFF -> RectF4(0f, 0f, w, h)
		FIT -> if(w / h > aspect) RectF4((w - h * aspect) / 2f, 0f, h * aspect, h) else RectF4(0f, (h - w / aspect) / 2f, w, w / aspect)
		ZOOM -> if(w / h > aspect) RectF4(0f, (h - w / aspect) / 2f, w, w / aspect) else RectF4((w - h * aspect) / 2f, 0f, h * aspect, h)
	}
}

/** what pressing L2 / R2 harder (squashing the thumb) does; one or the other: both would get mixed up */
enum class TriggerSquash(val value: String)
{
	NONE("none"),
	/** the trigger follows how hard the thumb squashes */
	PRESSURE("pressure"),
	/** a button held while squashing (the trigger stays fully pulled) */
	HOLD("hold");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } }
}

/** how a stick follows the thumb: like a joystick, or "drag like a mouse" */
enum class StickStyle(val value: String)
{
	STICK("stick"), DRAG("drag");
	companion object { fun from(s: String?) = values().firstOrNull { it.value == s } ?: STICK }
}

/**
 * The shape of a group of four buttons (d-pad or △○✕□), worked like a stick (phase 13, tuned by
 * the user with _mods/prototipo-consola/estrella.html). Sizes in % of the group's radius:
 * [dead] = middle where nothing is pressed, [green] = ring where the two neighbours press
 * together, [width] = half width of each egg-shaped button; [invert] puts the narrow end of the
 * eggs inside; [rotation] in degrees, positive = clockwise.
 */
class ClusterShape(val dead: Int, val green: Int, val width: Int, val invert: Boolean, val rotation: Int)
{
	companion object
	{
		/** egg from this far from the centre to the edge of the group (fraction of the radius) */
		const val EGG_IN = 0.24f
		const val EGG_OUT = 1f
		val DPAD_DEFAULT = ClusterShape(30, 80, 45, true, 15)
		val FACE_DEFAULT = ClusterShape(45, 80, 35, false, -15)
	}

	/** > 0: round end inside, narrower outside; inverted: pointed end inside, fat end outside */
	val taper get() = if(invert) -0.32f else 0.25f

	/** in group coordinates: u along the button's axis (outwards), v across, in radii */
	fun inEgg(u: Float, v: Float): Boolean
	{
		val c = (EGG_IN + EGG_OUT) / 2f
		val l = (EGG_OUT - EGG_IN) / 2f
		val ct = (u - c) / l
		if(ct < -1f || ct > 1f) return false
		return abs(v) <= width / 100f * kotlin.math.sqrt(1f - ct * ct) * (1f - taper * ct)
	}

	/**
	 * Which of the four buttons (0 = up, then clockwise) a point presses, the point given in
	 * radii from the centre on screen axes; empty = dead zone, null = outside the drawing.
	 */
	fun classify(sx: Float, sy: Float): List<Int>?
	{
		// undo the rotation, then work as if the group were upright
		val a = -Math.toRadians(rotation.toDouble())
		val c = kotlin.math.cos(a).toFloat()
		val s = kotlin.math.sin(a).toFloat()
		val dx = sx * c - sy * s
		val dy = sx * s + sy * c
		// axis i: up, right, down, left
		if(inEgg(-dy, dx)) return listOf(0)
		if(inEgg(dx, dy)) return listOf(1)
		if(inEgg(dy, -dx)) return listOf(2)
		if(inEgg(-dx, -dy)) return listOf(3)
		val r = kotlin.math.hypot(dx, dy)
		if(r < dead / 100f) return emptyList()
		if(r <= green / 100f) return listOf(if(dy > 0f) 2 else 0, if(dx > 0f) 1 else 3)
		return null
	}
}

/** the device has a gyroscope: without one the gyroscope stick cannot work (the tilt alone is not offered) */
fun hasGyroscope(context: android.content.Context): Boolean =
	(context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
		?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null

/** the device has some motion sensor: the controller's gyroscope works with the accelerometer alone (tilt only) */
fun hasMotionSensors(context: android.content.Context): Boolean =
	hasGyroscope(context) || (context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
		?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null

/**
 * The pad's options (Settings → "Controls and gyroscope"). [gyroHardware]: the device has a gyroscope (see
 * [hasGyroscope]); without one the gyroscope stick counts as off whatever was saved, so its buttons go away too.
 */
class ConsolePadSettings(private val prefs: SharedPreferences, val gyroHardware: Boolean = true)
{
	companion object
	{
		const val KEY_STICK_TRAVEL = "console_pad_stick_travel"     // tenths of a mm
		const val KEY_COYOTE = "console_pad_coyote_ms"
		const val KEY_L3 = "console_pad_l3"
		const val KEY_R3 = "console_pad_r3"
		const val KEY_SQUASH = "console_pad_squash"                 // % of the first contact size
		// per stick: the 2.5.0 keys (named after the camera) are the right stick's, the "_l" ones the left's
		const val KEY_CAM_MODE = "console_pad_cam_mode"
		const val KEY_CAM_MODE_L = "console_pad_cam_mode_l"
		const val KEY_CAM_SENS = "console_pad_cam_sens"             // %
		const val KEY_CAM_SENS_L = "console_pad_cam_sens_l"
		const val KEY_ANTI_DEADZONE = "console_pad_anti_deadzone"   // %
		const val KEY_ANTI_DEADZONE_L = "console_pad_anti_deadzone_l"
		const val KEY_FOLLOW = "console_pad_follow"
		/** a finger leaving a button group keeps what it held (off: lets go, and presses again on coming back) */
		const val KEY_CLUSTER_KEEP = "console_pad_cluster_keep"
		/** a finger that lands on empty space is taken by the first button group it slides into */
		const val KEY_CLUSTER_CAPTURE = "console_pad_cluster_capture"
		/** how visible the pad is when untouched, % (0 = invisible) */
		const val KEY_OPACITY = "console_pad_opacity"
		/** each finger lights up the controls around it, fading out after it lifts */
		const val KEY_LAMP = "console_pad_lamp"
		const val KEY_LAMP_SIZE = "console_pad_lamp_size"           // radius, tenths of a mm
		/** how visible the shell is, % (0 = black, only the video where the layout puts it) */
		const val KEY_SHELL_OPACITY = "console_shell_opacity"
		/** shape of each button group: console_pad_<dpad|face>_<dead|green|width|rot|invert> */
		fun shapeKey(group: String, field: String) = "console_pad_${group}_$field"
		val groups = listOf("dpad", "face")
		/** what pressing L2 / R2 harder does ([TriggerSquash]) and, for "hold", which button ([PadButton]) */
		const val KEY_TRIGGER_MODE_L = "console_pad_trigger_mode_l"
		const val KEY_TRIGGER_MODE_R = "console_pad_trigger_mode_r"
		const val KEY_TRIGGER_HOLD_L = "console_pad_trigger_hold_l"
		const val KEY_TRIGGER_HOLD_R = "console_pad_trigger_hold_r"
		/** the button a squashed left / right stick holds ([PadButton.value] or "none") */
		const val KEY_STICK_SQUASH_L = "console_pad_stick_squash_l"
		const val KEY_STICK_SQUASH_R = "console_pad_stick_squash_r"
		/** seconds without a touch before the controls (and the shell) fade out; 0 = never */
		const val KEY_AUTO_HIDE = "console_pad_auto_hide"
		const val KEY_LINE_COLOR = "console_pad_line_color"
		const val KEY_CINEMA = "console_pad_cinema"
		val AUTO_HIDE_CHOICES = listOf(0, 2, 3, 5)
		// the gyroscope as a stick (2.5.3)
		const val KEY_GYRO = "console_pad_gyro"
		const val KEY_GYRO_SIDE = "console_pad_gyro_side"             // "l" / "r"
		const val KEY_GYRO_STYLE = "console_pad_gyro_style"
		const val KEY_GYRO_ACTIVATION = "console_pad_gyro_activation"
		const val KEY_GYRO_HORIZONTAL = "console_pad_gyro_horizontal"
		const val KEY_GYRO_SENS = "console_pad_gyro_sens"             // %
		const val KEY_GYRO_SENS_Y = "console_pad_gyro_sens_y"         // % of the horizontal one
		const val KEY_GYRO_DEADZONE = "console_pad_gyro_deadzone"     // % of the full range
		const val KEY_GYRO_SMOOTH = "console_pad_gyro_smooth"         // %
		const val KEY_GYRO_CURVE = "console_pad_gyro_curve"           // hundredths of the exponent
		const val KEY_GYRO_ANTI_DEADZONE = "console_pad_gyro_anti_deadzone" // %
		const val KEY_GYRO_TILT_RANGE = "console_pad_gyro_tilt_range" // degrees
		const val KEY_GYRO_INVERT_X = "console_pad_gyro_invert_x"
		const val KEY_GYRO_INVERT_Y = "console_pad_gyro_invert_y"
		const val KEY_GYRO_MUTE_NATIVE = "console_pad_gyro_mute_native"
		/** the on/off of the gyroscope stick (2.5.6): remembered on this device, NOT exported (a state, not an option) */
		const val KEY_GYRO_PAUSED = "console_pad_gyro_paused"
		/** what activates it (2.5.6): inputs held together, "l3,r3" (buttons by [PadButton.value], "stick_l" / "stick_r" = moved) */
		const val KEY_GYRO_INPUTS = "console_pad_gyro_inputs"
		/** how far a stick must be pushed to count as moved, % */
		const val KEY_GYRO_STICK_THRESHOLD = "console_pad_gyro_stick_threshold"
		const val GYRO_STICK_THRESHOLD_DEFAULT = 25
		const val GYRO_SENS_DEFAULT = 100
		const val GYRO_SENS_Y_DEFAULT = 100
		const val GYRO_DEADZONE_DEFAULT = 3
		const val GYRO_SMOOTH_DEFAULT = 10
		const val GYRO_CURVE_DEFAULT = 100
		const val GYRO_ANTI_DEADZONE_DEFAULT = 15
		const val GYRO_TILT_RANGE_DEFAULT = 25
		const val KEY_TRIGGER_BASE = "console_pad_trigger_base"     // % of the trigger on touch
		const val KEY_TRIGGER_FULL = "console_pad_trigger_full"     // % of the first contact size for 100 %

		const val STICK_TRAVEL_DEFAULT = 100
		const val COYOTE_DEFAULT = 100
		const val SQUASH_DEFAULT = 125
		const val CAM_SENS_DEFAULT = 100
		const val ANTI_DEADZONE_DEFAULT = 15
		const val TRIGGER_BASE_DEFAULT = 50
		const val TRIGGER_FULL_DEFAULT = 160

		const val OPACITY_DEFAULT = 50
		const val LAMP_SIZE_DEFAULT = 120
		const val SHELL_OPACITY_DEFAULT = 100

		val intKeys = listOf(KEY_STICK_TRAVEL, KEY_COYOTE, KEY_SQUASH, KEY_CAM_SENS, KEY_CAM_SENS_L, KEY_ANTI_DEADZONE, KEY_ANTI_DEADZONE_L,
			KEY_TRIGGER_BASE, KEY_TRIGGER_FULL, KEY_OPACITY, KEY_LAMP_SIZE, KEY_SHELL_OPACITY, KEY_AUTO_HIDE,
			KEY_GYRO_SENS, KEY_GYRO_SENS_Y, KEY_GYRO_DEADZONE, KEY_GYRO_SMOOTH, KEY_GYRO_CURVE, KEY_GYRO_ANTI_DEADZONE,
			KEY_GYRO_TILT_RANGE, KEY_GYRO_STICK_THRESHOLD) +
			groups.flatMap { g -> listOf("dead", "green", "width", "rot").map { shapeKey(g, it) } }
		val stringKeys = listOf(KEY_L3, KEY_R3, KEY_CAM_MODE, KEY_CAM_MODE_L,
			KEY_TRIGGER_MODE_L, KEY_TRIGGER_MODE_R, KEY_TRIGGER_HOLD_L, KEY_TRIGGER_HOLD_R,
			KEY_STICK_SQUASH_L, KEY_STICK_SQUASH_R, KEY_LINE_COLOR, KEY_CINEMA,
			KEY_GYRO_SIDE, KEY_GYRO_STYLE, KEY_GYRO_ACTIVATION, KEY_GYRO_HORIZONTAL, KEY_GYRO_INPUTS)
		val booleanKeys = listOf(KEY_FOLLOW, KEY_CLUSTER_KEEP, KEY_CLUSTER_CAPTURE, KEY_LAMP,
			KEY_GYRO, KEY_GYRO_INVERT_X, KEY_GYRO_INVERT_Y, KEY_GYRO_MUTE_NATIVE) +
			groups.map { shapeKey(it, "invert") }

		fun defaultShape(group: String) = if(group == "face") ClusterShape.FACE_DEFAULT else ClusterShape.DPAD_DEFAULT

		fun reset(prefs: SharedPreferences) = prefs.edit().apply {
			(intKeys + stringKeys + booleanKeys).forEach { remove(it) }
			remove(KEY_GYRO_PAUSED)
		}.apply()
	}

	var stickTravelMm = 10f
	var coyoteMs = 100L
	var l3 = ClickMode.EDGE
	var r3 = ClickMode.DOUBLE_TAP
	fun clickMode(side: Side) = if(side == Side.R) r3 else l3
	/** how much bigger the contact must get, against the finger at rest, to count as squashing */
	var squashRatio = 1.25f
	/** each stick: joystick or drag, how fast a drag turns, and where the push starts (drag only) */
	var styleL = StickStyle.STICK
	var styleR = StickStyle.STICK
	fun style(side: Side) = if(side == Side.R) styleR else styleL
	var sensitivityL = 1f
	var sensitivityR = 1f
	fun sensitivity(side: Side) = if(side == Side.R) sensitivityR else sensitivityL
	var antiDeadzoneL = 0.15f
	var antiDeadzoneR = 0.15f
	fun antiDeadzone(side: Side) = if(side == Side.R) antiDeadzoneR else antiDeadzoneL
	var follow = false
	var clusterKeep = false
	var clusterCapture = true
	var opacity = 0.5f
	var lamp = true
	var lampSizeMm = 12f
	var shellOpacity = 1f
	var dpadShape = ClusterShape.DPAD_DEFAULT
	var faceShape = ClusterShape.FACE_DEFAULT
	/** what pressing L2 / R2 harder does, each its own, and the button "hold" holds */
	var triggerModeL = TriggerSquash.NONE
	var triggerModeR = TriggerSquash.NONE
	fun triggerSquash(side: Side) = if(side == Side.R) triggerModeR else triggerModeL
	fun triggerPressure(side: Side) = triggerSquash(side) == TriggerSquash.PRESSURE
	var triggerHoldL = PadButton.L3
	var triggerHoldR = PadButton.R3
	fun triggerHold(side: Side) = if(side == Side.R) triggerHoldR else triggerHoldL
	/** the button a squashed stick holds (null = squashing does nothing) */
	var stickSquashL: PadButton? = null
	var stickSquashR: PadButton? = null
	fun stickSquash(side: Side) = if(side == Side.R) stickSquashR else stickSquashL
	/** what each thumb measured in the calibration wizard (null = not calibrated: the strength setting is used) */
	var calibrationL: SquashCalibration? = null
	var calibrationR: SquashCalibration? = null
	fun calibration(side: Side) = if(side == Side.R) calibrationR else calibrationL
	var autoHideS = 0
	var lineColor = LineColor.THEME
	var cinema = CinemaMode.OFF
	var triggerBase = 0.5f
	var triggerFullRatio = 1.6f
	/** the gyroscope as a stick: off unless asked for, and always off without a gyroscope (see [GyroStick]) */
	var gyroEnabled = false
	var gyroSide = Side.R
	var gyroStyle = GyroStyle.MOUSE
	var gyroActivation = GyroActivation.HOLD
	/** the inputs that must all be held for the activation */
	var gyroInputs: List<String> = listOf(PadButton.L2.value)
	var gyroStickThreshold = 0.25f
	var gyroHorizontal = GyroHorizontal.YAW
	var gyroSensitivity = 1f
	/** vertical against horizontal */
	var gyroSensitivityY = 1f
	var gyroDeadzone = 0.03f
	var gyroSmoothing = 0.1f
	var gyroCurve = 1f
	var gyroAntiDeadzone = 0.15f
	var gyroTiltRange = 25f
	var gyroInvertX = false
	var gyroInvertY = false
	/** while the gyroscope drives the stick, the controller's own gyroscope stays quiet (no double aim) */
	var gyroMuteNative = true

	init { reload() }

	/** whether the gyroscope stick was left paused (null = never decided: the activation's own start) */
	fun gyroPausedStored(): Boolean? = if(prefs.contains(KEY_GYRO_PAUSED)) prefs.getBoolean(KEY_GYRO_PAUSED, false) else null

	fun storeGyroPaused(paused: Boolean) = prefs.edit().putBoolean(KEY_GYRO_PAUSED, paused).apply()

	fun reload()
	{
		stickTravelMm = prefs.getInt(KEY_STICK_TRAVEL, STICK_TRAVEL_DEFAULT) / 10f
		coyoteMs = prefs.getInt(KEY_COYOTE, COYOTE_DEFAULT).toLong()
		// leaving the zone suits running (push on past the edge); the camera stick needs a double tap
		l3 = ClickMode.from(prefs.getString(KEY_L3, null), ClickMode.EDGE)
		r3 = ClickMode.from(prefs.getString(KEY_R3, null), ClickMode.DOUBLE_TAP)
		squashRatio = prefs.getInt(KEY_SQUASH, SQUASH_DEFAULT) / 100f
		calibrationL = SquashCalibration.load(prefs, Side.L)
		calibrationR = SquashCalibration.load(prefs, Side.R)
		styleR = StickStyle.from(prefs.getString(KEY_CAM_MODE, null))
		styleL = StickStyle.from(prefs.getString(KEY_CAM_MODE_L, null))
		sensitivityR = prefs.getInt(KEY_CAM_SENS, CAM_SENS_DEFAULT) / 100f
		sensitivityL = prefs.getInt(KEY_CAM_SENS_L, CAM_SENS_DEFAULT) / 100f
		antiDeadzoneR = prefs.getInt(KEY_ANTI_DEADZONE, ANTI_DEADZONE_DEFAULT) / 100f
		antiDeadzoneL = prefs.getInt(KEY_ANTI_DEADZONE_L, ANTI_DEADZONE_DEFAULT) / 100f
		stickSquashL = PadButton.from(prefs.getString(KEY_STICK_SQUASH_L, null))
		stickSquashR = PadButton.from(prefs.getString(KEY_STICK_SQUASH_R, null))
		follow = prefs.getBoolean(KEY_FOLLOW, false)
		clusterKeep = prefs.getBoolean(KEY_CLUSTER_KEEP, false)
		clusterCapture = prefs.getBoolean(KEY_CLUSTER_CAPTURE, true)
		opacity = prefs.getInt(KEY_OPACITY, OPACITY_DEFAULT) / 100f
		lamp = prefs.getBoolean(KEY_LAMP, true)
		lampSizeMm = prefs.getInt(KEY_LAMP_SIZE, LAMP_SIZE_DEFAULT) / 10f
		shellOpacity = prefs.getInt(KEY_SHELL_OPACITY, SHELL_OPACITY_DEFAULT) / 100f
		dpadShape = loadShape("dpad")
		faceShape = loadShape("face")
		triggerModeL = TriggerSquash.from(prefs.getString(KEY_TRIGGER_MODE_L, null)) ?: TriggerSquash.NONE
		triggerModeR = TriggerSquash.from(prefs.getString(KEY_TRIGGER_MODE_R, null)) ?: TriggerSquash.NONE
		triggerHoldL = PadButton.from(prefs.getString(KEY_TRIGGER_HOLD_L, null)) ?: PadButton.L3
		triggerHoldR = PadButton.from(prefs.getString(KEY_TRIGGER_HOLD_R, null)) ?: PadButton.R3
		autoHideS = prefs.getInt(KEY_AUTO_HIDE, 0)
		lineColor = LineColor.from(prefs.getString(KEY_LINE_COLOR, null))
		cinema = CinemaMode.from(prefs.getString(KEY_CINEMA, null))
		triggerBase = prefs.getInt(KEY_TRIGGER_BASE, TRIGGER_BASE_DEFAULT) / 100f
		triggerFullRatio = prefs.getInt(KEY_TRIGGER_FULL, TRIGGER_FULL_DEFAULT) / 100f
		gyroEnabled = gyroHardware && prefs.getBoolean(KEY_GYRO, false)
		gyroSide = if(prefs.getString(KEY_GYRO_SIDE, null) == "l") Side.L else Side.R
		gyroStyle = GyroStyle.from(prefs.getString(KEY_GYRO_STYLE, null))
		gyroActivation = GyroActivation.from(prefs.getString(KEY_GYRO_ACTIVATION, null))
		gyroInputs = GyroInputs.parse(prefs.getString(KEY_GYRO_INPUTS, null))
		gyroStickThreshold = prefs.getInt(KEY_GYRO_STICK_THRESHOLD, GYRO_STICK_THRESHOLD_DEFAULT) / 100f
		gyroHorizontal = GyroHorizontal.from(prefs.getString(KEY_GYRO_HORIZONTAL, null))
		gyroSensitivity = prefs.getInt(KEY_GYRO_SENS, GYRO_SENS_DEFAULT) / 100f
		gyroSensitivityY = prefs.getInt(KEY_GYRO_SENS_Y, GYRO_SENS_Y_DEFAULT) / 100f
		gyroDeadzone = prefs.getInt(KEY_GYRO_DEADZONE, GYRO_DEADZONE_DEFAULT) / 100f
		gyroSmoothing = prefs.getInt(KEY_GYRO_SMOOTH, GYRO_SMOOTH_DEFAULT) / 100f
		gyroCurve = prefs.getInt(KEY_GYRO_CURVE, GYRO_CURVE_DEFAULT) / 100f
		gyroAntiDeadzone = prefs.getInt(KEY_GYRO_ANTI_DEADZONE, GYRO_ANTI_DEADZONE_DEFAULT) / 100f
		gyroTiltRange = prefs.getInt(KEY_GYRO_TILT_RANGE, GYRO_TILT_RANGE_DEFAULT).toFloat()
		gyroInvertX = prefs.getBoolean(KEY_GYRO_INVERT_X, false)
		gyroInvertY = prefs.getBoolean(KEY_GYRO_INVERT_Y, false)
		gyroMuteNative = prefs.getBoolean(KEY_GYRO_MUTE_NATIVE, true)
	}


	private fun loadShape(group: String): ClusterShape
	{
		val d = defaultShape(group)
		return ClusterShape(
			prefs.getInt(shapeKey(group, "dead"), d.dead),
			prefs.getInt(shapeKey(group, "green"), d.green),
			prefs.getInt(shapeKey(group, "width"), d.width),
			prefs.getBoolean(shapeKey(group, "invert"), d.invert),
			prefs.getInt(shapeKey(group, "rot"), d.rotation))
	}

	fun shape(kind: ZoneKind) = if(kind == ZoneKind.FACE) faceShape else dpadShape
}
