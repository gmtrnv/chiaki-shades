// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.stream.ColorGrade.BwFilter
import com.metallic.chiaki.stream.ColorGrade.Palette
import com.metallic.chiaki.stream.OpticalLens.VignetteAxis

/**
 * Built-in looks for Settings → Color (grading and vignette), each one showing off some of the settings.
 * Order and names match res/values/strings.xml (preferences_color_preset_*).
 * CUSTOM has no fixed values: it holds what the user saved (FLAT until then).
 * Values in thousandths (see ColorValues).
 */
enum class ColorPreset(val values: ColorValues?)
{
	CINEMATIC(ColorValues.CINEMATIC),
	// strong colors and contrast, slightly deeper blacks
	VIVID(ColorValues(0, 0, 1100, 1200, 400, 0, -30, 0, 30, 0, 0, Palette.TEAL_ORANGE, 0, VignetteAxis.SIDES,
		brightness = -20)),
	// faithful colors, unlike Cinematic: neutral white balance, no tint, open shadows, gentle highlights
	NATURAL(ColorValues(40, 0, 1000, 1000, 350, 0, 40, 20, -10, 0, 0, Palette.TEAL_ORANGE, 0, VignetteAxis.SIDES)),
	// clearly golden: strong temperature plus warm split toning, pushed up into the midtones (balance)
	WARM(ColorValues(30, 600, 1020, 1080, 100, 0, 0, 10, 10, 0, 300, Palette.SEPIA, 80, VignetteAxis.ELLIPSE,
		balance = 250, vignetteStart = 450)),
	// moonlight: cold and a little green, deep blacks, a round vignette that starts early
	NIGHT(ColorValues(-150, -300, 1080, 750, 0, 0, -30, -20, 0, 0, 200, Palette.COLD, 150, VignetteAxis.ELLIPSE,
		brightness = -20, tint = -100, vignetteStart = 400)),
	// old film: washed out blacks, tinted shadows, strong round vignette
	VINTAGE(ColorValues(0, 100, 900, 800, -100, 80, 20, 0, -20, -60, 150, Palette.SEPIA, 250, VignetteAxis.ELLIPSE,
		brightness = 30, balance = -300, vignetteStart = 350)),
	// red filter, like on b&w film: darker skies, more drama
	MONO(ColorValues(0, 0, 1150, 1000, 0, 0, -30, 0, 30, 0, 0, Palette.TEAL_ORANGE, 120, VignetteAxis.ALL,
		bw = BwFilter.RED)),
	SEPIA(ColorValues(0, 0, 1080, 0, 0, 20, 0, 0, 0, -20, 1000, Palette.SEPIA, 150, VignetteAxis.ELLIPSE,
		balance = -200, vignetteStart = 400)),
	// outdoors in daylight: brighter, open shadows, lifted blacks and stronger colors (sunlight washes
	// out color and hides dark detail); slightly warm and a touch of magenta against a green cast
	HANDHELD(ColorValues(180, 60, 1000, 1180, 300, 0, 80, 50, 20, 0, 0, Palette.TEAL_ORANGE, 0, VignetteAxis.SIDES,
		brightness = 30, tint = 50)),
	CUSTOM(null)
}
