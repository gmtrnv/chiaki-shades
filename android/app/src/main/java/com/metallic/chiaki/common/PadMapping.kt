// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.SharedPreferences
import android.view.KeyEvent
import com.metallic.chiaki.lib.ControllerState
import org.json.JSONArray
import org.json.JSONObject

/**
 * A button of the console's controller that a physical key can press. L2/R2 have no bit: they set the trigger.
 * [RECENTER] is the one that is not a button but an action of the app.
 */
enum class PadTarget(val id: String, val button: UInt)
{
	CROSS("cross", ControllerState.BUTTON_CROSS),
	CIRCLE("circle", ControllerState.BUTTON_MOON),
	SQUARE("square", ControllerState.BUTTON_BOX),
	TRIANGLE("triangle", ControllerState.BUTTON_PYRAMID),
	DPAD_UP("dpad_up", ControllerState.BUTTON_DPAD_UP),
	DPAD_DOWN("dpad_down", ControllerState.BUTTON_DPAD_DOWN),
	DPAD_LEFT("dpad_left", ControllerState.BUTTON_DPAD_LEFT),
	DPAD_RIGHT("dpad_right", ControllerState.BUTTON_DPAD_RIGHT),
	L1("l1", ControllerState.BUTTON_L1),
	R1("r1", ControllerState.BUTTON_R1),
	L2("l2", 0U),
	R2("r2", 0U),
	L3("l3", ControllerState.BUTTON_L3),
	R3("r3", ControllerState.BUTTON_R3),
	OPTIONS("options", ControllerState.BUTTON_OPTIONS),
	SHARE("share", ControllerState.BUTTON_SHARE),
	TOUCHPAD("touchpad", ControllerState.BUTTON_TOUCHPAD),
	PS("ps", ControllerState.BUTTON_PS),
	/** not a button: recentres the motion (the controller's orientation and the gyroscope stick), see StreamInput */
	RECENTER("recenter", 0U);

	companion object
	{
		fun fromId(id: String) = values().firstOrNull { it.id == id }
	}
}

/** A physical key (an Android key code) that presses its target, right away or only when held ([hold]). */
data class KeyBinding(val keyCode: Int, val hold: Boolean = false)

/**
 * The physical controller's mapping: each button of the console's controller can have up to
 * [MAX_PER_TARGET] keys. A key presses at most one target when pressed and one when held: only keys
 * with a "held" target wait (their short press goes out when let go), every other key is sent at once.
 *
 * Stored as one JSON string ([KEY]), so it travels in the export as a whole. It replaces the fork's
 * `mapping_<button>` keys (one key per button, no touchpad, no D-pad keys): see [load].
 */
class PadMapping(bindings: Map<PadTarget, List<KeyBinding>>)
{
	private val bindings: Map<PadTarget, List<KeyBinding>> = PadTarget.values().associateWith { bindings[it].orEmpty() }

	private val pressByKey = HashMap<Int, PadTarget>()
	private val holdByKey = HashMap<Int, PadTarget>()

	init
	{
		for((target, list) in this.bindings)
			for(b in list)
				(if(b.hold) holdByKey else pressByKey)[b.keyCode] = target
	}

	fun bindings(target: PadTarget) = bindings[target].orEmpty()

	/** the target the key presses as soon as it goes down (or on release, if it also has a held one) */
	fun pressTarget(keyCode: Int) = pressByKey[keyCode]

	/** the target the key presses after [HOLD_MS] held */
	fun holdTarget(keyCode: Int) = holdByKey[keyCode]

	val isEmpty get() = bindings.values.all { it.isEmpty() }

	/**
	 * [binding] added to [target] (or in place of its binding number [replace]). The same key in the
	 * same mode leaves whatever target it had: a key does one thing when pressed and one when held.
	 */
	fun with(target: PadTarget, binding: KeyBinding, replace: Int? = null): PadMapping
	{
		val m = bindings.mapValues { (_, list) -> list.filter { it != binding } }.toMutableMap()
		val list = m.getValue(target).toMutableList()
		if(replace != null && replace in list.indices)
			list[replace] = binding
		else
		{
			list.add(binding)
			while(list.size > MAX_PER_TARGET)
				list.removeAt(0)
		}
		m[target] = list.distinct()
		return PadMapping(m)
	}

	fun without(target: PadTarget, binding: KeyBinding) =
		PadMapping(bindings.mapValues { (t, list) -> if(t == target) list - binding else list })

	/** ✕ with ○ and □ with △ swapped (controllers whose face buttons are the other way round, e.g. 8BitDo) */
	fun swappedFace(): PadMapping
	{
		val m = bindings.toMutableMap()
		fun swap(a: PadTarget, b: PadTarget) { val t = m.getValue(a); m[a] = m.getValue(b); m[b] = t }
		swap(PadTarget.CROSS, PadTarget.CIRCLE)
		swap(PadTarget.SQUARE, PadTarget.TRIANGLE)
		return PadMapping(m)
	}

	fun toJson(): String
	{
		val o = JSONObject()
		o.put("v", 1)
		for((target, list) in bindings)
		{
			if(list.isEmpty())
				continue
			o.put(target.id, JSONArray().apply {
				list.forEach { put(JSONObject().apply { put("k", it.keyCode); if(it.hold) put("h", 1) }) }
			})
		}
		return o.toString()
	}

	override fun equals(other: Any?) = other is PadMapping && other.bindings == bindings
	override fun hashCode() = bindings.hashCode()

	companion object
	{
		const val KEY = "pad_mapping"
		const val MAX_PER_TARGET = 2
		/** how long a key with a "held" target must be held */
		const val HOLD_MS = 400L

		private fun press(k: Int) = listOf(KeyBinding(k))

		/**
		 * Android's standard gamepad keys. Select is the touchpad click (much more used than Share,
		 * which most controllers have no spare key for) and, held, the PS button.
		 */
		val default = PadMapping(mapOf(
			PadTarget.CROSS to press(KeyEvent.KEYCODE_BUTTON_A),
			PadTarget.CIRCLE to press(KeyEvent.KEYCODE_BUTTON_B),
			PadTarget.SQUARE to press(KeyEvent.KEYCODE_BUTTON_X),
			PadTarget.TRIANGLE to press(KeyEvent.KEYCODE_BUTTON_Y),
			// controllers whose D-pad is keys rather than an axis (the axis is always read too)
			PadTarget.DPAD_UP to press(KeyEvent.KEYCODE_DPAD_UP),
			PadTarget.DPAD_DOWN to press(KeyEvent.KEYCODE_DPAD_DOWN),
			PadTarget.DPAD_LEFT to press(KeyEvent.KEYCODE_DPAD_LEFT),
			PadTarget.DPAD_RIGHT to press(KeyEvent.KEYCODE_DPAD_RIGHT),
			PadTarget.L1 to press(KeyEvent.KEYCODE_BUTTON_L1),
			PadTarget.R1 to press(KeyEvent.KEYCODE_BUTTON_R1),
			PadTarget.L2 to press(KeyEvent.KEYCODE_BUTTON_L2),
			PadTarget.R2 to press(KeyEvent.KEYCODE_BUTTON_R2),
			PadTarget.L3 to press(KeyEvent.KEYCODE_BUTTON_THUMBL),
			PadTarget.R3 to press(KeyEvent.KEYCODE_BUTTON_THUMBR),
			PadTarget.OPTIONS to press(KeyEvent.KEYCODE_BUTTON_START),
			PadTarget.TOUCHPAD to press(KeyEvent.KEYCODE_BUTTON_SELECT),
			PadTarget.PS to listOf(KeyBinding(KeyEvent.KEYCODE_BUTTON_MODE), KeyBinding(KeyEvent.KEYCODE_BUTTON_SELECT, hold = true))
		))

		fun fromJson(json: String?): PadMapping?
		{
			if(json.isNullOrBlank())
				return null
			return try
			{
				val o = JSONObject(json)
				val m = HashMap<PadTarget, List<KeyBinding>>()
				for(target in PadTarget.values())
				{
					val a = o.optJSONArray(target.id) ?: continue
					m[target] = (0 until a.length()).mapNotNull { i ->
						a.optJSONObject(i)?.let { b ->
							val k = b.optInt("k", 0)
							if(k <= 0) null else KeyBinding(k, b.optInt("h", 0) != 0)
						}
					}.take(MAX_PER_TARGET)
				}
				PadMapping(m)
			}
			catch(e: Exception) { null }
		}

		/** the stored mapping, or the default one */
		fun load(prefs: SharedPreferences): PadMapping =
			fromJson(prefs.getString(KEY, null)) ?: default

		fun save(prefs: SharedPreferences, mapping: PadMapping) =
			prefs.edit().putString(KEY, mapping.toJson()).apply()
	}
}

/**
 * Named mappings (like the touch pad's saved layouts): two come with the app ("#default", "#swapped"),
 * then the user's. Stored as one JSON string; the name last loaded or saved is remembered, to show a
 * "*" when the mapping changed since.
 */
object MappingSlots
{
	const val KEY = "pad_mapping_slots"
	const val KEY_CURRENT = "pad_mapping_slot_current"
	const val BUILTIN_DEFAULT = "#default"
	const val BUILTIN_SWAPPED = "#swapped"
	val builtins = listOf(BUILTIN_DEFAULT, BUILTIN_SWAPPED)

	class Slot(val name: String, val mapping: PadMapping)

	fun builtin(name: String) = when(name)
	{
		BUILTIN_DEFAULT -> PadMapping.default
		BUILTIN_SWAPPED -> PadMapping.default.swappedFace()
		else -> null
	}

	fun list(prefs: SharedPreferences): List<Slot>
	{
		val json = prefs.getString(KEY, null) ?: return emptyList()
		return try
		{
			val a = JSONObject(json).getJSONArray("slots")
			(0 until a.length()).mapNotNull { i ->
				val o = a.getJSONObject(i)
				PadMapping.fromJson(o.getString("mapping"))?.let { Slot(o.getString("name"), it) }
			}
		}
		catch(e: Exception) { emptyList() }
	}

	private fun store(prefs: SharedPreferences, slots: List<Slot>)
	{
		val a = JSONArray()
		slots.forEach { a.put(JSONObject().put("name", it.name).put("mapping", it.mapping.toJson())) }
		prefs.edit().putString(KEY, JSONObject().put("slots", a).toString()).apply()
	}

	fun current(prefs: SharedPreferences) = prefs.getString(KEY_CURRENT, null)
	fun setCurrent(prefs: SharedPreferences, name: String?) =
		prefs.edit().apply { if(name == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, name) }.apply()

	fun save(prefs: SharedPreferences, name: String, mapping: PadMapping)
	{
		store(prefs, list(prefs).filter { it.name != name } + Slot(name, mapping))
		setCurrent(prefs, name)
	}

	fun load(prefs: SharedPreferences, name: String): PadMapping?
	{
		val m = builtin(name) ?: list(prefs).firstOrNull { it.name == name }?.mapping ?: return null
		setCurrent(prefs, name)
		return m
	}

	fun rename(prefs: SharedPreferences, from: String, to: String)
	{
		store(prefs, list(prefs).map { if(it.name == from) Slot(to, it.mapping) else it })
		if(current(prefs) == from) setCurrent(prefs, to)
	}

	fun delete(prefs: SharedPreferences, name: String)
	{
		store(prefs, list(prefs).filter { it.name != name })
		if(current(prefs) == name) setCurrent(prefs, null)
	}

	/** the mapping differs from the profile it came from */
	fun modified(prefs: SharedPreferences, mapping: PadMapping): Boolean
	{
		val name = current(prefs) ?: return false
		val saved = builtin(name) ?: list(prefs).firstOrNull { it.name == name }?.mapping ?: return false
		return saved != mapping
	}

	/** "base", or "base 2", "base 3"… if taken */
	fun freeName(prefs: SharedPreferences, base: String): String
	{
		val taken = list(prefs).map { it.name }.toSet()
		if(base !in taken) return base
		var i = 2
		while("$base $i" in taken) i++
		return "$base $i"
	}
}
