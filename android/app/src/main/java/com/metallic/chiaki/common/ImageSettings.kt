// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.SharedPreferences
import com.metallic.chiaki.stream.ColorGrade
import com.metallic.chiaki.stream.Deband
import com.metallic.chiaki.stream.OpticalLens
import java.util.Locale
import kotlin.math.roundToInt

/**
 * User settings of our image pipeline (Settings → Image), stored as whole numbers (% or, for the
 * color look, thousandths).
 * Defaults come from OpticalLens.Params and ColorValues so there is a single source of truth;
 * the slider ranges live in res/xml/preferences.xml.
 */
object ImageSettings
{
	open class Slider(val key: String, val default: Int, val unit: String = "%")
	{
		fun get(prefs: SharedPreferences) = prefs.getInt(key, default)
		open fun fraction(prefs: SharedPreferences) = get(prefs) / 100f
		/** Position of the slider (its units). */
		open fun sliderValue(prefs: SharedPreferences) = get(prefs)
		/** Text shown next to the slider. */
		open fun label(prefs: SharedPreferences) = get(prefs).toString()
		/** Stores a position chosen on the slider. */
		open fun putSliderValue(prefs: SharedPreferences, value: Int) = prefs.edit().putInt(key, value).apply()
	}

	/**
	 * One field of ColorValues, stored in thousandths under "<name>_milli"; the slider moves in
	 * whole %. The default is the "Cinematic" look.
	 */
	class ColorSlider(name: String, val field: (ColorValues) -> Int): Slider(name + "_milli", field(ColorValues.CINEMATIC))
	{
		override fun fraction(prefs: SharedPreferences) = get(prefs) / 1000f
		override fun sliderValue(prefs: SharedPreferences) = (get(prefs) / 10f).roundToInt()
		// one decimal only when the value is not a whole % (an imported look)
		override fun label(prefs: SharedPreferences) = get(prefs).let {
			if(it % 10 == 0) (it / 10).toString() else String.format(Locale.getDefault(), "%.1f", it / 10f)
		}
		// moving the slider and leaving it on the same whole % keeps the exact value
		override fun putSliderValue(prefs: SharedPreferences, value: Int)
		{
			if(value != sliderValue(prefs))
				prefs.edit().putInt(key, value * 10).apply()
		}
	}

	private val defaults = OpticalLens.Params()

	val opticalFix = Slider("image_optical_fix", percent(defaults.fix))
	val opticalZc = Slider("image_optical_zc", percent(defaults.zc))
	val sharp = Slider("image_quality_sharp", percent(defaults.sharp))
	val antiring = Slider("image_quality_antiring", percent(defaults.antiring))

	val colorExposure = ColorSlider("image_color_exposure") { it.exposure }
	val colorBrightness = ColorSlider("image_color_brightness") { it.brightness }
	val colorTemp = ColorSlider("image_color_temp") { it.temp }
	val colorTint = ColorSlider("image_color_tint") { it.tint }
	val colorContrast = ColorSlider("image_color_contrast") { it.contrast }
	val colorSaturation = ColorSlider("image_color_saturation") { it.saturation }
	val colorVibrance = ColorSlider("image_color_vibrance") { it.vibrance }
	val colorBwStrength = ColorSlider("image_color_bw_strength") { it.bwStrength }
	val colorBlacks = ColorSlider("image_color_curve_blacks") { it.blacks }
	val colorShadows = ColorSlider("image_color_curve_shadows") { it.shadows }
	val colorMids = ColorSlider("image_color_curve_mids") { it.mids }
	val colorHighs = ColorSlider("image_color_curve_highs") { it.highs }
	val colorWhites = ColorSlider("image_color_curve_whites") { it.whites }
	val colorSplit = ColorSlider("image_color_split") { it.split }
	val colorBalance = ColorSlider("image_color_split_balance") { it.balance }
	// the vignette is part of the look (drawn under the Color toggle), so presets carry it too
	val vignetteAmount = ColorSlider("image_vignette_amount") { it.vignette }
	val vignetteStart = ColorSlider("image_vignette_start") { it.vignetteStart }

	val colorSliders = listOf(colorExposure, colorBrightness, colorTemp, colorTint, colorContrast, colorSaturation,
		colorVibrance, colorBwStrength, colorBlacks, colorShadows, colorMids, colorHighs, colorWhites, colorSplit, colorBalance,
		vignetteAmount, vignetteStart)

	private val debandDefaults = Deband.Params()

	val debandSteps = Slider("image_deband_steps", debandDefaults.steps, unit = "")
	val debandThreshold = Slider("image_deband_threshold", percent(debandDefaults.threshold))
	val debandRange = Slider("image_deband_range", debandDefaults.range.roundToInt(), unit = "px")
	val debandGrain = Slider("image_deband_grain", percent(debandDefaults.grain))

	private val sliders = listOf(opticalFix, opticalZc, sharp, antiring) + colorSliders +
		listOf(debandSteps, debandThreshold, debandRange, debandGrain)

	fun slider(key: String?) = sliders.find { it.key == key }

	/** Stored as the enum name, the entry values of the list in preferences.xml. */
	const val vignetteAxisKey = "image_vignette_axis"

	fun vignetteAxis(prefs: SharedPreferences) = prefs.getString(vignetteAxisKey, null)
		?.let { value -> OpticalLens.VignetteAxis.values().find { it.name == value } }
		?: ColorValues.CINEMATIC.vignetteAxis

	/** Split toning palette, stored as the enum name. */
	const val colorPaletteKey = "image_color_palette"

	fun colorPalette(prefs: SharedPreferences) = prefs.getString(colorPaletteKey, null)
		?.let { value -> ColorGrade.Palette.values().find { it.name == value } }
		?.takeIf { it != ColorGrade.Palette.CUSTOM || colorSplitColors(prefs) != null }
		?: ColorValues.CINEMATIC.palette

	/** Free split toning colors of an imported look (SplitColors.serialize()); only some looks have them. */
	const val colorSplitColorsKey = "image_color_split_colors"

	fun colorSplitColors(prefs: SharedPreferences) = ColorGrade.SplitColors.parse(prefs.getString(colorSplitColorsKey, null))

	/** Black and white filter (OFF = color), stored as the enum name. */
	const val colorBwKey = "image_color_bw"

	fun colorBw(prefs: SharedPreferences) = prefs.getString(colorBwKey, null)
		?.let { value -> ColorGrade.BwFilter.values().find { it.name == value } }
		?: ColorValues.CINEMATIC.bw

	/** Selected color preset, stored as the enum name. The sliders hold the values in use. */
	const val colorPresetKey = "image_color_preset"
	/** Slot of the Custom preset (key kept from before presets had slots, so saved values survive). */
	const val colorCustomKey = "image_color_custom"

	fun colorPreset(prefs: SharedPreferences) = prefs.getString(colorPresetKey, null)
		?.let { value -> ColorPreset.values().find { it.name == value } }
		?: ColorPreset.CINEMATIC

	/**
	 * Every preset keeps the user's changes in its own slot (ColorValues.serialize()), so leaving a
	 * preset and coming back finds them again. The selected preset's values live in the sliders;
	 * its slot is written when another preset is selected. No slot = the preset's own values.
	 */
	fun colorSlotKey(preset: ColorPreset) =
		if(preset == ColorPreset.CUSTOM) colorCustomKey else "image_color_slot_" + preset.name.lowercase()

	/** A preset's own values: what ↺ and "Reset preset" restore and what "Name*" compares with. Custom: flat. */
	fun factoryColorValues(preset: ColorPreset) = preset.values ?: ColorValues.FLAT

	/** Values a preset has: the current ones if selected, else its slot or, without one, its own values. */
	fun storedColorValues(prefs: SharedPreferences, preset: ColorPreset) =
		if(preset == colorPreset(prefs)) colorValues(prefs)
		else ColorValues.parse(prefs.getString(colorSlotKey(preset), null)) ?: factoryColorValues(preset)

	/** True when the preset's values differ from its own ones (shown as "Name*"). */
	fun colorPresetEdited(prefs: SharedPreferences, preset: ColorPreset = colorPreset(prefs)) =
		storedColorValues(prefs, preset) != factoryColorValues(preset)

	/** Keeps the selected preset's current values in its slot (no slot when they are its own values). */
	private fun saveColorSlot(prefs: SharedPreferences, editor: SharedPreferences.Editor)
	{
		val preset = colorPreset(prefs)
		val values = colorValues(prefs)
		if(values == factoryColorValues(preset))
			editor.remove(colorSlotKey(preset))
		else
			editor.putString(colorSlotKey(preset), values.serialize())
	}

	fun saveColorSlot(prefs: SharedPreferences) = prefs.edit().also { saveColorSlot(prefs, it) }.apply()

	/** Selects a preset like Settings → Color does: the current one keeps its changes, the new one brings its own. */
	fun selectColorPreset(prefs: SharedPreferences, preset: ColorPreset)
	{
		val values = storedColorValues(prefs, preset)
		prefs.edit().apply {
			saveColorSlot(prefs, this)
			putColorValues(this, values)
			putString(colorPresetKey, preset.name)
		}.apply()
	}

	/**
	 * Where an imported look goes: the preset it was exported from, else a built-in preset with exactly
	 * these values, else Custom.
	 */
	fun importTarget(imported: LookFile.Imported) =
		imported.preset ?: ColorPreset.values().firstOrNull { it.values == imported.values } ?: ColorPreset.CUSTOM

	/** Puts an imported look in [target] and selects that preset; the current one keeps its changes. */
	fun importLook(prefs: SharedPreferences, target: ColorPreset, values: ColorValues)
	{
		prefs.edit().apply {
			saveColorSlot(prefs, this)
			putColorValues(this, values)
			putString(colorPresetKey, target.name)
		}.apply()
	}

	/** Makes [values] the current ones (sliders, palette, vignette direction). */
	fun putColorValues(editor: SharedPreferences.Editor, values: ColorValues)
	{
		for(slider in colorSliders)
			editor.putInt(slider.key, slider.field(values))
		editor.putString(colorPaletteKey, values.palette.name)
		editor.putString(vignetteAxisKey, values.vignetteAxis.name)
		editor.putString(colorBwKey, values.bw.name)
		values.splitColors?.let { editor.putString(colorSplitColorsKey, it.serialize()) } ?: editor.remove(colorSplitColorsKey)
	}

	/** "Save as Custom": the current values replace the Custom slot; the current preset keeps its changes too. */
	fun saveAsCustom(prefs: SharedPreferences)
	{
		val values = colorValues(prefs)
		prefs.edit().apply {
			saveColorSlot(prefs, this)
			putString(colorCustomKey, values.serialize())
			putString(colorPresetKey, ColorPreset.CUSTOM.name)
		}.apply()
	}

	fun params(prefs: SharedPreferences) = defaults.copy(
		fix = opticalFix.fraction(prefs),
		zc = opticalZc.fraction(prefs),
		vignetteAmount = vignetteAmount.fraction(prefs),
		vignetteStart = vignetteStart.fraction(prefs),
		vignetteAxis = vignetteAxis(prefs),
		sharp = sharp.fraction(prefs),
		antiring = antiring.fraction(prefs)
	)

	fun colorValues(prefs: SharedPreferences) = ColorValues(
		exposure = colorExposure.get(prefs),
		temp = colorTemp.get(prefs),
		contrast = colorContrast.get(prefs),
		saturation = colorSaturation.get(prefs),
		vibrance = colorVibrance.get(prefs),
		blacks = colorBlacks.get(prefs),
		shadows = colorShadows.get(prefs),
		mids = colorMids.get(prefs),
		highs = colorHighs.get(prefs),
		whites = colorWhites.get(prefs),
		split = colorSplit.get(prefs),
		palette = colorPalette(prefs),
		vignette = vignetteAmount.get(prefs),
		vignetteAxis = vignetteAxis(prefs),
		brightness = colorBrightness.get(prefs),
		tint = colorTint.get(prefs),
		balance = colorBalance.get(prefs),
		vignetteStart = vignetteStart.get(prefs),
		bw = colorBw(prefs),
		bwStrength = colorBwStrength.get(prefs),
		splitColors = colorSplitColors(prefs)
	)

	fun colorParams(prefs: SharedPreferences) = colorValues(prefs).params()

	fun debandParams(prefs: SharedPreferences) = Deband.Params(
		steps = debandSteps.get(prefs).coerceIn(1, 4),
		threshold = debandThreshold.fraction(prefs),
		range = debandRange.get(prefs).toFloat(),
		grain = debandGrain.fraction(prefs)
	)

	// ---- export / import (SerializedSettings, "image" block) ----

	// the bar buttons (display mode, Color, HQ, Deband) travel with the image: whoever imports a look sees it as made
	private fun block(preferences: Preferences) = SettingsBlock(
		intKeys = sliders.map { it.key },
		stringKeys = listOf(vignetteAxisKey, colorPaletteKey, colorSplitColorsKey, colorBwKey, colorPresetKey, preferences.displayModeKey) +
			ColorPreset.values().map { colorSlotKey(it) },
		booleanKeys = listOf(preferences.colorEnabledKey, preferences.hqEnabledKey, preferences.debandLightEnabledKey))

	/** Our settings for the export file, only the ones the user has changed (the rest are defaults). */
	fun export(preferences: Preferences) = block(preferences).export(preferences.sharedPreferences)

	/** Writes back what export() produced; unknown keys and wrong types are skipped. Returns how many were applied. */
	fun import(preferences: Preferences, values: Map<String, Any?>): Int =
		block(preferences).import(preferences.sharedPreferences, values)

	private fun percent(value: Float) = (value * 100f).roundToInt()
}
