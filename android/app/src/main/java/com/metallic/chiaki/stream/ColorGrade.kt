// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import kotlin.math.abs
import kotlin.math.pow

/**
 * CPU side of the color block of shaders/optical_stretch.frag (the "Color" toggle).
 *
 * Same steps as Anamorfica's color block: exposure, temperature and contrast folded into one
 * multiply + add, saturation + vibrance, a 5 point master curve (Catmull-Rom) and split toning
 * (soft light blend). Everything that does not depend on the pixel is precomputed here once.
 * Field names follow the "color" block of Anamorfica's settings JSON.
 */
object ColorGrade
{
	data class Params(
		val exposure: Float = 0f,     // EV
		val brightness: Float = 0f,   // added to every channel: lifts or lowers the blacks
		val temp: Float = 0f,         // -1 cold .. +1 warm
		val tint: Float = 0f,         // -1 green .. +1 magenta
		val contrast: Float = 1f,     // pivot 0.5
		val saturation: Float = 1f,
		val vibrance: Float = 0f,     // extra saturation for the less saturated colors
		val curve: List<Float> = IDENTITY_CURVE, // output at x = 0, .25, .5, .75, 1
		val splitColors: SplitColors = Palette.TEAL_ORANGE.colors!!,
		val splitAmount: Float = 0f,
		val splitBalance: Float = 0f, // -1 .. +1, moves the shadows / highlights crossover
		val bw: BwFilter = BwFilter.OFF, // black and white instead of saturation + vibrance
		val bwStrength: Float = 1f    // 0 = neutral grey .. 1 = the filter's full channel mix
	)

	/**
	 * Black and white channel mix, like a color filter in front of b&w film (Anamorfica's weights).
	 * The weights used are neutral + (filter - neutral) * strength.
	 */
	enum class BwFilter(val weights: FloatArray?)
	{
		OFF(null),
		NEUTRAL(floatArrayOf(0.2126f, 0.7152f, 0.0722f)),
		RED(floatArrayOf(0.75f, 0.25f, 0f)),
		ORANGE(floatArrayOf(0.55f, 0.40f, 0.05f)),
		YELLOW(floatArrayOf(0.40f, 0.55f, 0.05f)),
		GREEN(floatArrayOf(0.15f, 0.75f, 0.10f)),
		BLUE(floatArrayOf(0.10f, 0.30f, 0.60f))
	}

	val IDENTITY_CURVE = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

	const val CURVE_BLACK_MAX = 0.3f
	const val CURVE_WHITE_MIN = 0.7f

	/**
	 * Makes the curve never go down, exactly like Anamorfica does (only min / max, so both give the same
	 * curve): ends limited, then each middle point at least the previous one (forward pass) and at most
	 * the next one (backward pass). A missing or non-finite point is the straight line's.
	 */
	fun sanitizeCurve(curve: List<Float>): List<Float>
	{
		val out = FloatArray(5) { i -> curve.getOrNull(i)?.takeIf { it.isFinite() } ?: IDENTITY_CURVE[i] }
		out[0] = out[0].coerceIn(0f, CURVE_BLACK_MAX)
		out[4] = out[4].coerceIn(CURVE_WHITE_MIN, 1f)
		for(i in 1..3)
			out[i] = minOf(1f, maxOf(out[i - 1], out[i]))
		for(i in 3 downTo 1)
			out[i] = minOf(out[i], out[i + 1])
		return out.toList()
	}

	/** Split toning colors: tint for the shadows and for the highlights (RGB 0..1). */
	data class SplitColors(val shadow: List<Float>, val high: List<Float>)
	{
		/** Compact text "r/g/b r/g/b" for SharedPreferences. */
		fun serialize() = shadow.joinToString("/") + " " + high.joinToString("/")

		companion object
		{
			fun parse(text: String?): SplitColors?
			{
				val parts = text?.split(" ") ?: return null
				if(parts.size != 2)
					return null
				val rgb = parts.map { part ->
					part.split("/").map { it.toFloatOrNull()?.takeIf { v -> v.isFinite() }?.coerceIn(0f, 1f) ?: return null }
						.takeIf { it.size == 3 } ?: return null
				}
				return SplitColors(rgb[0], rgb[1])
			}
		}
	}

	enum class Palette(val colors: SplitColors?)
	{
		TEAL_ORANGE(SplitColors(listOf(0.114f, 0.357f, 0.475f), listOf(1.0f, 0.698f, 0.357f))),
		BLUE_AMBER(SplitColors(listOf(0.20f, 0.30f, 0.65f), listOf(1.0f, 0.80f, 0.45f))),
		GREEN_MAGENTA(SplitColors(listOf(0.25f, 0.55f, 0.35f), listOf(0.95f, 0.55f, 0.80f))),
		PURPLE_YELLOW(SplitColors(listOf(0.40f, 0.25f, 0.60f), listOf(1.0f, 0.90f, 0.50f))),
		SEPIA(SplitColors(listOf(0.45f, 0.30f, 0.18f), listOf(1.0f, 0.85f, 0.60f))),
		COLD(SplitColors(listOf(0.15f, 0.25f, 0.55f), listOf(0.75f, 0.88f, 1.0f))),
		/** Free colors (ColorValues.splitColors): an imported look whose colors are none of the above. */
		CUSTOM(null);

		companion object
		{
			/** The palette with these colors (within 0.001 per channel), if any. */
			fun matching(colors: SplitColors) = values().find { palette ->
				val own = palette.colors ?: return@find false
				close(own.shadow, colors.shadow) && close(own.high, colors.high)
			}

			private fun close(a: List<Float>, b: List<Float>) =
				a.size == b.size && a.indices.all { abs(a[it] - b[it]) <= 0.001f }
		}
	}

	/** Values passed to the shader as uniforms. */
	class Uniforms(
		val mul: FloatArray,       // vec3: exposure * temperature * contrast
		val add: Float,            // contrast offset
		val satBase: Float,        // saturation factor = satBase - satVib * spread
		val satVib: Float,
		val curveA: FloatArray,    // vec4 per coefficient, one component per curve segment
		val curveB: FloatArray,
		val curveC: FloatArray,
		val curveD: FloatArray,
		val splitShadow: FloatArray,
		val splitHigh: FloatArray,
		val splitAmount: Float,
		val splitRange: FloatArray, // vec2: smoothstep edges of the shadows → highlights crossover
		val bw: Float,             // 1.0 = black and white
		val bwWeights: FloatArray
	)

	fun uniforms(params: Params): Uniforms
	{
		// temperature: red up and blue down (or the other way); tint: green down (magenta) or up
		val gain = 2f.pow(params.exposure) * params.contrast
		val mul = floatArrayOf(gain * (1f + 0.16f * params.temp), gain * (1f - 0.12f * params.tint), gain * (1f - 0.16f * params.temp))
		val add = 0.5f * (1f - params.contrast) + params.brightness

		// vibrance acts on top of saturation: full extra on grey, none on fully saturated colors
		val satBase = params.saturation * (1f + params.vibrance)
		val satVib = params.saturation * params.vibrance

		// Catmull-Rom through the 5 points, one-sided tangents at the ends; per segment
		// y(t) = a + t (b + t (c + t d)) with t in 0..1
		val y = sanitizeCurve(params.curve)
		val m = FloatArray(5) { i ->
			when(i)
			{
				0 -> y[1] - y[0]
				4 -> y[4] - y[3]
				else -> (y[i + 1] - y[i - 1]) * 0.5f
			}
		}
		val a = FloatArray(4) { y[it] }
		val b = FloatArray(4) { m[it] }
		val c = FloatArray(4) { 3f * (y[it + 1] - y[it]) - 2f * m[it] - m[it + 1] }
		val d = FloatArray(4) { 2f * (y[it] - y[it + 1]) + m[it] + m[it + 1] }

		val balance = params.splitBalance
		val neutral = BwFilter.NEUTRAL.weights!!
		val filter = params.bw.weights ?: neutral
		val bwWeights = FloatArray(3) { neutral[it] + (filter[it] - neutral[it]) * params.bwStrength }
		return Uniforms(mul, add, satBase, satVib, a, b, c, d,
			params.splitColors.shadow.toFloatArray(), params.splitColors.high.toFloatArray(), params.splitAmount,
			floatArrayOf(-0.5f * balance, 1f - 0.5f * balance),
			if(params.bw == BwFilter.OFF) 0f else 1f, bwWeights)
	}
}
