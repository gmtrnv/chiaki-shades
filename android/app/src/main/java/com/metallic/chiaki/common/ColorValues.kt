// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.stream.ColorGrade
import com.metallic.chiaki.stream.OpticalLens.VignetteAxis

/**
 * Color settings in thousandths (1000 = 1.0), so a look imported from a file keeps its exact values;
 * the sliders in Settings → Color move in whole % (10 thousandths) and only round what the user moves.
 * Curve points are offsets from the straight line at x = 0, .25, .5, .75, 1.
 * The vignette is part of the look too: it is drawn under the same "Color" toggle.
 */
data class ColorValues(
	val exposure: Int,    // 1000 = +1 EV
	val temp: Int,        // -1000 cold .. +1000 warm
	val contrast: Int,    // 1000 = unchanged
	val saturation: Int,  // 1000 = unchanged
	val vibrance: Int,
	val blacks: Int,
	val shadows: Int,
	val mids: Int,
	val highs: Int,
	val whites: Int,
	val split: Int,       // split toning amount
	val palette: ColorGrade.Palette,
	val vignette: Int,    // darkening at the edges, 0 = off
	val vignetteAxis: VignetteAxis,
	// added with the look files; neutral by default, so the built-in presets do not list them
	val brightness: Int = 0,      // 100 = +0.1 on every channel
	val tint: Int = 0,            // -1000 green .. +1000 magenta
	val balance: Int = 0,         // split toning crossover, -1000 .. +1000
	val vignetteStart: Int = 550, // 0 .. 950 from the center, where the vignette begins
	val bw: ColorGrade.BwFilter = ColorGrade.BwFilter.OFF,
	val bwStrength: Int = 1000,   // 0 = neutral grey .. 1000 = the filter's full channel mix
	// free split toning colors of an imported look, used with palette CUSTOM; kept when another
	// palette is chosen, so "Custom" stays in the list
	val splitColors: ColorGrade.SplitColors? = null
)
{
	fun params() = ColorGrade.Params(
		exposure = exposure / 1000f,
		brightness = brightness / 1000f,
		temp = temp / 1000f,
		tint = tint / 1000f,
		contrast = contrast / 1000f,
		saturation = saturation / 1000f,
		vibrance = vibrance / 1000f,
		curve = listOf(blacks / 1000f, 0.25f + shadows / 1000f, 0.5f + mids / 1000f, 0.75f + highs / 1000f, 1f + whites / 1000f),
		splitColors = splitTones,
		splitAmount = split / 1000f,
		splitBalance = balance / 1000f,
		bw = bw,
		bwStrength = bwStrength / 1000f
	)

	/** The split toning colors in use: the palette's, or the free ones with CUSTOM. */
	val splitTones get() = palette.colors ?: splitColors ?: ColorGrade.Palette.TEAL_ORANGE.colors!!

	val vignetteAmount get() = vignette / 1000f
	val vignetteStartFraction get() = vignetteStart / 1000f

	/**
	 * Compact form for SharedPreferences (preset slots): name=value pairs, comma separated, so new
	 * fields can be added later; a missing one is neutral.
	 */
	fun serialize() = listOf(
		"exposure" to exposure, "temp" to temp, "contrast" to contrast, "saturation" to saturation,
		"vibrance" to vibrance, "blacks" to blacks, "shadows" to shadows, "mids" to mids, "highs" to highs,
		"whites" to whites, "split" to split, "palette" to palette.name, "vignette" to vignette,
		"vignetteAxis" to vignetteAxis.name, "brightness" to brightness, "tint" to tint, "balance" to balance,
		"vignetteStart" to vignetteStart, "bw" to bw.name, "bwStrength" to bwStrength,
		"splitColors" to splitColors?.serialize()
	).filter { it.second != null }.joinToString(",") { (name, value) -> "$name=$value" }

	companion object
	{
		/** Reads serialize()'s text (name=value, in thousandths); null when it is broken. */
		fun parse(text: String?): ColorValues?
		{
			if(text == null || !text.contains('='))
				return null
			return parseNamed(text)
		}

		private fun parseNamed(text: String): ColorValues?
		{
			val map = text.split(",").associate { it.substringBefore('=') to it.substringAfter('=', "") }
			// null = present but not a number (the text is broken)
			fun int(name: String, neutral: Int): Int? = map[name]?.let { it.toIntOrNull() ?: return null } ?: neutral
			val palette = map["palette"]?.let { name -> ColorGrade.Palette.values().find { it.name == name } ?: return null } ?: FLAT.palette
			val axis = map["vignetteAxis"]?.let { name -> VignetteAxis.values().find { it.name == name } ?: return null } ?: FLAT.vignetteAxis
			val bw = map["bw"]?.let { name -> ColorGrade.BwFilter.values().find { it.name == name } ?: return null } ?: FLAT.bw
			val splitColors = map["splitColors"]?.let { ColorGrade.SplitColors.parse(it) ?: return null }
			if(palette == ColorGrade.Palette.CUSTOM && splitColors == null)
				return null
			return ColorValues(
				exposure = int("exposure", FLAT.exposure) ?: return null,
				temp = int("temp", FLAT.temp) ?: return null,
				contrast = int("contrast", FLAT.contrast) ?: return null,
				saturation = int("saturation", FLAT.saturation) ?: return null,
				vibrance = int("vibrance", FLAT.vibrance) ?: return null,
				blacks = int("blacks", FLAT.blacks) ?: return null,
				shadows = int("shadows", FLAT.shadows) ?: return null,
				mids = int("mids", FLAT.mids) ?: return null,
				highs = int("highs", FLAT.highs) ?: return null,
				whites = int("whites", FLAT.whites) ?: return null,
				split = int("split", FLAT.split) ?: return null,
				palette = palette,
				vignette = int("vignette", FLAT.vignette) ?: return null,
				vignetteAxis = axis,
				brightness = int("brightness", FLAT.brightness) ?: return null,
				tint = int("tint", FLAT.tint) ?: return null,
				balance = int("balance", FLAT.balance) ?: return null,
				vignetteStart = int("vignetteStart", FLAT.vignetteStart) ?: return null,
				bw = bw,
				bwStrength = int("bwStrength", FLAT.bwStrength) ?: return null,
				splitColors = splitColors
			)
		}

		/** No grading at all. */
		val FLAT = ColorValues(0, 0, 1000, 1000, 0, 0, 0, 0, 0, 0, 0, ColorGrade.Palette.TEAL_ORANGE, 0, VignetteAxis.SIDES)

		/** Teal & orange cinema look, rounded from the user's favorite Anamorfica shader. */
		val CINEMATIC = ColorValues(60, -60, 1040, 1040, 240, 0, -20, -10, 20, 0, 150, ColorGrade.Palette.TEAL_ORANGE, 100, VignetteAxis.SIDES)
	}
}
