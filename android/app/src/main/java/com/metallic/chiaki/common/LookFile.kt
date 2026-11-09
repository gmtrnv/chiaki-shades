// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.stream.ColorGrade
import com.metallic.chiaki.stream.ColorGrade.BwFilter
import com.metallic.chiaki.stream.ColorGrade.Palette
import com.metallic.chiaki.stream.OpticalLens.VignetteAxis
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Look files: an mpv shader of one pass (color grading + vignette, at the video's resolution) that
 * carries its settings in one comment line, "// image-look: {json}". The JSON uses the "color" and
 * "vignette" blocks of Anamorfica's settings (v1: same names, units and ranges), so that editor can
 * open the file too. Importing only reads that line and rebuilds the look from it: the shader code
 * of a file is never used.
 *
 * A field that is missing takes Anamorfica's reference value if it is one of its original fields
 * (exposure 0.06, temp -0.06, contrast and saturation 1.04, vibrance 0.24, the reference curve, the
 * teal / orange colors with amount 0.15, vignette 0.098) and the neutral value otherwise.
 */
object LookFile
{
	const val PREFIX = "// image-look: "
	// shaders made with Anamorfica carry the same JSON under their own name
	private const val ANAMORFICA_PREFIX = "// anamorfica-ajustes: "
	private const val MAX_LINE = 64 * 1024
	private val NAME_REGEX = Regex("[A-Za-z0-9_.-]{1,64}")

	private val REFERENCE_CURVE = listOf(0f, 0.228f, 0.494f, 0.766f, 1f)
	private val REFERENCE_COLORS = Palette.TEAL_ORANGE.colors!!

	/** Name of a preset in the file (ASCII, also the file name); English like the file's comments. */
	fun name(preset: ColorPreset) = when(preset)
	{
		ColorPreset.CINEMATIC -> "Cinematic"
		ColorPreset.VIVID -> "Vivid"
		ColorPreset.NATURAL -> "Natural"
		ColorPreset.WARM -> "Warm"
		ColorPreset.NIGHT -> "Night"
		ColorPreset.VINTAGE -> "Vintage_film"
		ColorPreset.MONO -> "Black_and_white"
		ColorPreset.SEPIA -> "Sepia"
		ColorPreset.HANDHELD -> "Handheld"
		ColorPreset.CUSTOM -> "Custom"
	}

	private fun shape(axis: VignetteAxis) = when(axis)
	{
		VignetteAxis.SIDES -> "side"
		VignetteAxis.TOP_BOTTOM -> "topBottom"
		VignetteAxis.ALL -> "box"
		VignetteAxis.ELLIPSE -> "ellipse"
	}

	/** The curve the shader really uses, in thousandths (never goes down). */
	private fun curve(values: ColorValues) = ColorGrade.sanitizeCurve(values.params().curve).map { (it * 1000f).roundToInt() }

	// ---- writing ----

	/** The settings line's JSON: every color and vignette field, also the neutral ones. */
	fun json(values: ColorValues, preset: ColorPreset): String
	{
		val tones = values.splitTones
		val color = obj(
			"exposure" to milli(values.exposure),
			"brightness" to milli(values.brightness),
			"temp" to milli(values.temp),
			"tint" to milli(values.tint),
			"contrast" to milli(values.contrast),
			"saturation" to milli(values.saturation),
			"vibrance" to milli(values.vibrance),
			"curve" to arr(curve(values).map { milli(it) }),
			"split" to obj(
				"shadow" to arr(tones.shadow.map { dec(it) }),
				"high" to arr(tones.high.map { dec(it) }),
				"amount" to milli(values.split),
				"balance" to milli(values.balance)),
			"bw" to obj(
				"on" to (values.bw != BwFilter.OFF).toString(),
				"filter" to JSONObject.quote((if(values.bw == BwFilter.OFF) BwFilter.NEUTRAL else values.bw).name.lowercase()),
				"strength" to milli(values.bwStrength)))
		val vignette = obj(
			"amount" to milli(values.vignette),
			"start" to milli(values.vignetteStart),
			"shape" to JSONObject.quote(shape(values.vignetteAxis)))
		return obj(*listOfNotNull(
			"v" to "1",
			if(preset != ColorPreset.CUSTOM) "preset" to JSONObject.quote(preset.name) else null,
			"name" to JSONObject.quote(name(preset)),
			"color" to color,
			"vignette" to vignette).toTypedArray())
	}

	/** The whole look file: an mpv shader with the settings line. Only what is on is written. */
	fun glsl(values: ColorValues, preset: ColorPreset): String
	{
		val u = ColorGrade.uniforms(values.params())
		val out = StringBuilder()
		out.append("//!HOOK MAIN\n//!BIND HOOKED\n//!DESC ").append(name(preset)).append('\n')
		out.append(PREFIX).append(json(values, preset)).append('\n')
		out.append("// License: CC0-1.0 (public domain)\n")
		out.append("// Image look: color grading and vignette, for mpv and mpv-based players.\n")
		out.append("// Runs at the video's resolution. Values are precomputed: do not edit by hand.\n\n")
		out.append("float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n\n")
		out.append("// master curve through 5 points (Catmull-Rom), one coefficient per segment\n")
		out.append("vec3 pick(vec4 k, vec3 s1, vec3 s2, vec3 s3) { return k.x + s1 * (k.y - k.x) + s2 * (k.z - k.y) + s3 * (k.w - k.z); }\n\n")
		out.append("vec3 curve(vec3 x) {\n")
		out.append("    x = clamp(x, 0.0, 1.0) * 4.0;\n")
		out.append("    vec3 s1 = step(1.0, x);\n    vec3 s2 = step(2.0, x);\n    vec3 s3 = step(3.0, x);\n")
		out.append("    vec3 t = x - (s1 + s2 + s3);\n")
		out.append("    vec3 a = pick(").append(vec(u.curveA)).append(", s1, s2, s3);\n")
		out.append("    vec3 b = pick(").append(vec(u.curveB)).append(", s1, s2, s3);\n")
		out.append("    vec3 c = pick(").append(vec(u.curveC)).append(", s1, s2, s3);\n")
		out.append("    vec3 d = pick(").append(vec(u.curveD)).append(", s1, s2, s3);\n")
		out.append("    return clamp(a + t * (b + t * (c + t * d)), 0.0, 1.0);\n}\n\n")
		out.append("vec4 hook() {\n")
		out.append("    vec2 p = HOOKED_pos;\n")
		out.append("    vec3 col = HOOKED_tex(p).rgb;\n")
		out.append("    // exposure, temperature, tint, contrast and brightness\n")
		out.append("    col = col * ").append(vec(u.mul)).append(if(u.add < 0f) " - " else " + ").append(f(abs(u.add))).append(";\n")
		if(values.bw != BwFilter.OFF)
		{
			out.append("    // black and white through a color filter\n")
			out.append("    col = vec3(dot(col, ").append(vec(u.bwWeights)).append("));\n")
		}
		else
		{
			out.append("    // saturation + vibrance\n")
			out.append("    float L = luma(col);\n")
			out.append("    float sp = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));\n")
			out.append("    col = mix(vec3(L), col, ").append(f(u.satBase)).append(" - ").append(f(u.satVib))
				.append(" * clamp(sp * 1.5, 0.0, 1.0));\n")
		}
		out.append("    col = curve(col);\n")
		if(values.split > 0)
		{
			out.append("    // split toning, soft light blend\n")
			out.append("    vec3 tn = mix(").append(vec(u.splitShadow)).append(", ").append(vec(u.splitHigh))
				.append(", smoothstep(").append(f(u.splitRange[0])).append(", ").append(f(u.splitRange[1])).append(", luma(col)));\n")
			out.append("    col = mix(col, (1.0 - 2.0 * tn) * col * col + 2.0 * tn * col, ").append(f(u.splitAmount)).append(");\n")
		}
		if(values.vignette > 0)
		{
			val amount = f(values.vignetteAmount)
			val start = f(values.vignetteStartFraction)
			out.append("    // vignette\n")
			when(values.vignetteAxis)
			{
				VignetteAxis.SIDES ->
					out.append("    col *= 1.0 - $amount * smoothstep($start, 1.0, abs(p.x * 2.0 - 1.0));\n")
				VignetteAxis.TOP_BOTTOM ->
					out.append("    col *= 1.0 - $amount * smoothstep($start, 1.0, abs(p.y * 2.0 - 1.0));\n")
				VignetteAxis.ALL ->
				{
					out.append("    vec2 v = smoothstep(vec2($start), vec2(1.0), abs(p * 2.0 - 1.0));\n")
					out.append("    col *= (1.0 - $amount * v.x) * (1.0 - $amount * v.y);\n")
				}
				VignetteAxis.ELLIPSE ->
					out.append("    col *= 1.0 - $amount * smoothstep($start, 1.0, length(p * 2.0 - 1.0) * 0.70710678);\n")
			}
		}
		out.append("    return vec4(clamp(col, 0.0, 1.0), 1.0);\n}\n")
		return out.toString()
	}

	private fun obj(vararg fields: Pair<String, String>) =
		fields.joinToString(",", "{", "}") { (key, value) -> JSONObject.quote(key) + ":" + value }

	private fun arr(items: List<String>) = items.joinToString(",", "[", "]")

	/** Thousandths as a JSON number (0.06, not 0.060000). */
	private fun milli(value: Int) = BigDecimal(value).movePointLeft(3).stripTrailingZeros().toPlainString()

	/** At most 6 decimals, like Anamorfica's toFixed(6). */
	private fun dec(value: Float) = BigDecimal(value.toString()).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

	private fun f(value: Float) = String.format(Locale.ROOT, "%.6f", value)

	private fun vec(values: FloatArray) = "vec${values.size}(" + values.joinToString(", ") { f(it) } + ")"

	// ---- reading ----

	/** Something that did not come over exactly (shown after importing). */
	enum class Issue { CLAMPED, UNKNOWN_VIGNETTE_SHAPE, UNKNOWN_BW_FILTER }

	class Imported(val values: ColorValues, val preset: ColorPreset?, val name: String?, val issues: Set<Issue>)

	/**
	 * Reads the settings line of a look file (ours or Anamorfica's). Null when there is none or it
	 * cannot be read. Values beyond the common ranges are limited (Issue.CLAMPED).
	 */
	fun parse(text: String): Imported?
	{
		val line = text.lineSequence().map { it.trimEnd('\r') }
			.firstOrNull { it.startsWith(PREFIX) || it.startsWith(ANAMORFICA_PREFIX) } ?: return null
		if(line.length > MAX_LINE)
			return null
		val json = try
		{
			JSONObject(line.removePrefix(PREFIX).removePrefix(ANAMORFICA_PREFIX))
		}
		catch(e: JSONException)
		{
			return null
		}
		if((json.opt("v") as? Number)?.toDouble() != 1.0)
			return null

		val issues = mutableSetOf<Issue>()

		// a number in range; missing or not a number = default, beyond the range = limited
		fun number(obj: JSONObject?, key: String, default: Double, min: Double, max: Double): Double
		{
			val value = (obj?.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return default
			val limited = value.coerceIn(min, max)
			if(limited != value)
				issues += Issue.CLAMPED
			return limited
		}
		fun toMilli(value: Double) = (value * 1000.0).roundToInt()

		fun rgb(array: JSONArray?, default: List<Float>): List<Float>
		{
			if(array == null || array.length() != 3)
				return default
			val values = (0 until 3).map { (array.opt(it) as? Number)?.toFloat()?.takeIf { v -> v.isFinite() } ?: return default }
			val limited = values.map { it.coerceIn(0f, 1f) }
			if(limited != values)
				issues += Issue.CLAMPED
			return limited
		}

		val color = json.optJSONObject("color")
		val curveJson = color?.optJSONArray("curve")
		val curveIn = if(curveJson == null) REFERENCE_CURVE
			else List(5) { (curveJson.opt(it) as? Number)?.toFloat() ?: Float.NaN }
		val curve = ColorGrade.sanitizeCurve(curveIn)
		if(curveIn.indices.any { curveIn[it].isFinite() && abs(curveIn[it] - curve[it]) > 1e-6f })
			issues += Issue.CLAMPED
		val curveMilli = curve.map { (it * 1000f).roundToInt() }

		val split = color?.optJSONObject("split")
		val colors = ColorGrade.SplitColors(
			rgb(split?.optJSONArray("shadow"), REFERENCE_COLORS.shadow),
			rgb(split?.optJSONArray("high"), REFERENCE_COLORS.high))
		val palette = Palette.matching(colors) ?: Palette.CUSTOM

		val bwJson = color?.optJSONObject("bw")
		val bwOn = bwJson?.opt("on") == true
		val filterName = bwJson?.opt("filter") as? String
		val filter = if(filterName == null) BwFilter.NEUTRAL
			else BwFilter.values().find { it != BwFilter.OFF && it.name.lowercase() == filterName }
				?: BwFilter.NEUTRAL.also { if(bwOn) issues += Issue.UNKNOWN_BW_FILTER }

		val vignette = json.optJSONObject("vignette")
		val shapeName = vignette?.opt("shape") as? String
		val axis = if(shapeName == null) VignetteAxis.SIDES
			else VignetteAxis.values().find { shape(it) == shapeName }
				?: VignetteAxis.SIDES.also { issues += Issue.UNKNOWN_VIGNETTE_SHAPE }

		val values = ColorValues(
			exposure = toMilli(number(color, "exposure", 0.06, -1.0, 1.0)),
			temp = toMilli(number(color, "temp", -0.06, -1.0, 1.0)),
			contrast = toMilli(number(color, "contrast", 1.04, 0.5, 1.5)),
			saturation = toMilli(number(color, "saturation", 1.04, 0.0, 2.0)),
			vibrance = toMilli(number(color, "vibrance", 0.24, -1.0, 1.0)),
			blacks = curveMilli[0],
			shadows = curveMilli[1] - 250,
			mids = curveMilli[2] - 500,
			highs = curveMilli[3] - 750,
			whites = curveMilli[4] - 1000,
			split = toMilli(number(split, "amount", 0.15, 0.0, 1.0)),
			palette = palette,
			vignette = toMilli(number(vignette, "amount", 0.098, 0.0, 0.5)),
			vignetteAxis = axis,
			brightness = toMilli(number(color, "brightness", 0.0, -0.1, 0.1)),
			tint = toMilli(number(color, "tint", 0.0, -1.0, 1.0)),
			balance = toMilli(number(split, "balance", 0.0, -1.0, 1.0)),
			vignetteStart = toMilli(number(vignette, "start", 0.55, 0.0, 0.95)),
			bw = if(bwOn) filter else BwFilter.OFF,
			bwStrength = toMilli(number(bwJson, "strength", 1.0, 0.0, 1.0)),
			splitColors = if(palette == Palette.CUSTOM) colors else null)

		val preset = (json.opt("preset") as? String)?.let { id -> ColorPreset.values().find { it.name == id && it != ColorPreset.CUSTOM } }
		val name = (json.opt("name") as? String)?.takeIf { NAME_REGEX.matches(it) }
		return Imported(values, preset, name, issues)
	}
}
