// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.common.CardAnimation
import com.metallic.chiaki.common.ColorPreset
import com.metallic.chiaki.common.ColorValues
import com.metallic.chiaki.common.ImageSettings
import com.metallic.chiaki.common.LookFile
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.QualityProfile
import com.metallic.chiaki.common.fileProviderAuthority
import com.metallic.chiaki.common.exportSettingsWithDialog
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.stream.ColorGrade
import io.reactivex.disposables.CompositeDisposable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxkotlin.addTo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** the app's language in Settings → Appearance ("system", "es" or "en") */
private const val KEY_APP_LANGUAGE = "app_language"

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.rumblePs5HapticsKey -> preferences.rumblePs5Haptics
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		preferences.hqEnabledKey -> preferences.hqEnabled
		preferences.debandLightEnabledKey -> preferences.debandLightEnabled
		preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled
		preferences.pictureInPictureEnabledKey -> preferences.pictureInPictureEnabled
		preferences.statsOverlayKey -> preferences.statsOverlay
		preferences.offerLowerResolutionsKey -> preferences.offerLowerResolutions
		preferences.gameModeKey -> preferences.gameMode
		preferences.streamDndKey -> preferences.streamDnd
		preferences.gameplayToastsKey -> preferences.gameplayToasts
		preferences.quickReconnectEnabledKey -> preferences.quickReconnectEnabled
		preferences.quickReconnectPressPsKey -> preferences.quickReconnectPressPs
		com.metallic.chiaki.main.Tips.KEY_ENABLED -> preferences.sharedPreferences.getBoolean(key, true)
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.rumblePs5HapticsKey -> preferences.rumblePs5Haptics = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			preferences.hqEnabledKey -> preferences.hqEnabled = value
			preferences.debandLightEnabledKey -> preferences.debandLightEnabled = value
			preferences.touchscreenTouchpadEnabledKey -> preferences.touchscreenTouchpadEnabled = value
			preferences.pictureInPictureEnabledKey -> preferences.pictureInPictureEnabled = value
			preferences.statsOverlayKey -> preferences.statsOverlay = value
			preferences.offerLowerResolutionsKey -> preferences.offerLowerResolutions = value
			preferences.gameModeKey -> preferences.gameMode = value
			preferences.streamDndKey -> preferences.streamDnd = value
			preferences.gameplayToastsKey -> preferences.gameplayToasts = value
			preferences.quickReconnectEnabledKey -> preferences.quickReconnectEnabled = value
			preferences.quickReconnectPressPsKey -> preferences.quickReconnectPressPs = value
			com.metallic.chiaki.main.Tips.KEY_ENABLED -> preferences.sharedPreferences.edit().putBoolean(key, value).apply()
		}
	}

	override fun getInt(key: String?, defValue: Int) = when(key)
	{
		preferences.streamVolumeKey -> preferences.streamVolume
		preferences.rumbleStrengthKey -> preferences.rumbleStrength
		SettingsFragment.KEY_QUALITY_BITRATE -> preferences.currentQuality.bitrateMbps
		preferences.lossReportStepKey -> preferences.lossReportStep
		else -> ImageSettings.slider(key)?.sliderValue(preferences.sharedPreferences) ?: defValue
	}

	override fun putInt(key: String?, value: Int)
	{
		when(key)
		{
			preferences.streamVolumeKey -> preferences.streamVolume = value
			preferences.rumbleStrengthKey -> preferences.rumbleStrength = value
			SettingsFragment.KEY_QUALITY_BITRATE -> preferences.bitrate = value * 1000
			preferences.lossReportStepKey -> preferences.lossReportStep = value
			else -> ImageSettings.slider(key)?.putSliderValue(preferences.sharedPreferences, value)
		}
	}

	/** Text next to a slider (may have a decimal, unlike its position). */
	fun label(key: String?) = ImageSettings.slider(key)?.label(preferences.sharedPreferences)

	override fun getString(key: String, defValue: String?) = when
	{
		key == QualityProfile.KEY -> preferences.qualityProfile.id
		key == preferences.resolutionKey ->
			if(preferences.qualityProfile == QualityProfile.AUTO)
				com.metallic.chiaki.common.AutoQuality.userResolution(preferences)?.value ?: com.metallic.chiaki.common.AutoQuality.RESOLUTION_AUTO
			else preferences.resolution.value
		key == preferences.fpsKey -> preferences.fps.value
		key == preferences.codecKey -> preferences.codec.value
		key == preferences.cardAnimationKey -> preferences.cardAnimation.value
		key == preferences.quickReconnectSecondsKey -> preferences.quickReconnectSeconds.toString()
		key == KEY_APP_LANGUAGE ->
			androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language ?: "system"
		key == preferences.rumbleTargetKey -> preferences.rumbleTarget.value
		key == preferences.motionGripKey -> if(preferences.motionGripFlat) Preferences.MOTION_GRIP_FLAT else Preferences.MOTION_GRIP_UPRIGHT
		key == ImageSettings.vignetteAxisKey ->ImageSettings.vignetteAxis(preferences.sharedPreferences).name
		key == ImageSettings.colorPaletteKey -> ImageSettings.colorPalette(preferences.sharedPreferences).name
		key == ImageSettings.colorBwKey -> ImageSettings.colorBw(preferences.sharedPreferences).name
		key == ImageSettings.colorPresetKey -> ImageSettings.colorPreset(preferences.sharedPreferences).name
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when
		{
			key == preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value }
				// Auto keeps the user's resolution (or Auto) apart: its own values are not used
				if(preferences.qualityProfile == QualityProfile.AUTO)
					com.metallic.chiaki.common.AutoQuality.setUserResolution(preferences, resolution)
				else if(resolution != null)
					preferences.resolution = resolution
			}
			key == preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			key == QualityProfile.KEY -> QualityProfile.fromId(value)?.let { preferences.selectQuality(it) }
			key == preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			key == preferences.cardAnimationKey -> preferences.cardAnimation = CardAnimation.fromValue(value)
			key == preferences.quickReconnectSecondsKey -> value?.toIntOrNull()?.let { preferences.quickReconnectSeconds = it }
			key == preferences.motionGripKey -> preferences.motionGripFlat = value == Preferences.MOTION_GRIP_FLAT
			key == preferences.rumbleTargetKey -> preferences.rumbleTarget = com.metallic.chiaki.stream.Rumble.Target.fromValue(value)
			// kept by Android (13+) or AppCompat, not in our settings: the screens are redrawn in it at once
			key == KEY_APP_LANGUAGE -> androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
				if(value == null || value == "system") androidx.core.os.LocaleListCompat.getEmptyLocaleList()
				else androidx.core.os.LocaleListCompat.forLanguageTags(value))
			key == ImageSettings.vignetteAxisKey || key == ImageSettings.colorPaletteKey || key == ImageSettings.colorPresetKey
				|| key == ImageSettings.colorBwKey ->
				preferences.sharedPreferences.edit().putString(key, value).apply()
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
		private const val KEY_COLOR_PRESET_RESET = "image_color_preset_reset"
		private const val KEY_COLOR_PRESET_SAVE = "image_color_preset_save"
		private const val KEY_COLOR_LOOK_EXPORT = "image_color_look_export"
		private const val KEY_COLOR_LOOK_IMPORT = "image_color_look_import"
		private const val PICK_LOOK_REQUEST = 2
		private const val MAX_LOOK_FILE_SIZE = 1024 * 1024
		const val KEY_QUALITY_BITRATE = "quality_bitrate_mbps"
		private const val KEY_QUALITY_RESET = "quality_profile_reset"
		private const val KEY_QUALITY_SAVE = "quality_profile_save"
		private const val ARG_TITLE = "page_title"

		/** one page of Settings (2.6.0-rc3): the same fragment, showing only the PreferenceScreen [key] */
		fun page(key: String, title: String?) = SettingsFragment().apply {
			arguments = Bundle().apply {
				putString(ARG_PREFERENCE_ROOT, key)
				putString(ARG_TITLE, title)
			}
		}
	}

	private var disposable = CompositeDisposable()
	private var exportDisposable = CompositeDisposable().also { it.addTo(disposable) }

	private val notificationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		// ↺ of the stream volume goes back to 100 % (the slider's own default is its minimum)
		preferenceScreen.findPreference<SliderPreference>(getString(R.string.preferences_stream_volume_key))?.let { volume ->
			volume.onReset = {
				if(volume.callChangeListener(100))
					volume.value = 100
			}
		}

		// ↺ of the rumble strength goes back to 100 %
		preferenceScreen.findPreference<SliderPreference>(preferences.rumbleStrengthKey)?.let { strength ->
			strength.onReset = {
				if(strength.callChangeListener(com.metallic.chiaki.stream.Rumble.STRENGTH_DEFAULT))
					strength.value = com.metallic.chiaki.stream.Rumble.STRENGTH_DEFAULT
			}
		}

		setupAbout(context)

		// quick reconnect shows the paused session in a notification: ask for that when turned on
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_quick_reconnect_enabled_key))?.setOnPreferenceChangeListener { _, newValue ->
			if(newValue == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
				notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
			true
		}

		// Do not disturb needs the user's OK once, in the system settings
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_stream_dnd_key))?.setOnPreferenceChangeListener { _, newValue ->
			if(newValue == true && !com.metallic.chiaki.stream.StreamDnd.granted(context))
			{
				android.widget.Toast.makeText(context, R.string.preferences_stream_dnd_grant, android.widget.Toast.LENGTH_LONG).show()
				try { startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
				catch(e: Exception) {}
			}
			true
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
		preferenceScreen.findPreference<Preference>("console_pad_test")?.setOnPreferenceClickListener {
			startActivity(Intent(requireContext(), com.metallic.chiaki.touchcontrols.console.TouchTestActivity::class.java))
			true
		}
		preferenceScreen.findPreference<Preference>("pad_mapping_open")?.setOnPreferenceClickListener {
			startActivity(Intent(requireContext(), PadMappingActivity::class.java))
			true
		}

		colorPrefs = preferences.sharedPreferences
		setupColorPresets()
		qualityPrefs = preferences
		setupQuality()
	}

	private var qualityPrefs: Preferences? = null

	// kept in a field: SharedPreferences only holds listeners weakly
	private val qualityChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		val p = qualityPrefs ?: return@OnSharedPreferenceChangeListener
		if(key == QualityProfile.KEY || key == p.resolutionKey || key == p.bitrateKey || key == p.offerLowerResolutionsKey
			|| key?.startsWith("quality_slot_") == true || key == com.metallic.chiaki.common.AutoQuality.KEY_RESOLUTION)
			refreshQuality()
	}

	/**
	 * Settings → About (2.7.0): the version, and links opened here rather than with an <intent> in the
	 * XML, so a device with no browser gets a message instead of a crash.
	 */
	private fun setupAbout(context: Context)
	{
		preferenceScreen.findPreference<Preference>("about_version")?.summary =
			getString(R.string.about_version_summary, com.metallic.chiaki.BuildConfig.VERSION_NAME)
		val links = mapOf(
			"about_source" to "https://github.com/gmtrnv/chiaki-shades",
			"about_license" to "https://github.com/gmtrnv/chiaki-shades/blob/main/COPYING",
			"about_credits" to "https://github.com/streetpea/chiaki-ng")
		for((key, url) in links)
		{
			preferenceScreen.findPreference<Preference>(key)?.setOnPreferenceClickListener {
				try
				{
					startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
				}
				catch(e: android.content.ActivityNotFoundException)
				{
					android.widget.Toast.makeText(context, R.string.about_no_browser, android.widget.Toast.LENGTH_SHORT).show()
				}
				true
			}
		}
	}

	/**
	 * Quality profiles, like the color presets: each profile keeps its changes (resolution, bitrate,
	 * "lower resolutions"), "Name*" = changed, Reset brings back its own values, "Save as Custom"
	 * copies them to Custom. The loss report is one setting for all of them.
	 */
	private fun setupQuality()
	{
		val p = qualityPrefs ?: return
		findPreference<ListPreference>(QualityProfile.KEY)?.let { list ->
			list.entryValues = QualityProfile.values().map { it.id }.toTypedArray()
			list.setOnPreferenceChangeListener { _, newValue ->
				val profile = QualityProfile.fromId(newValue as? String) ?: return@setOnPreferenceChangeListener false
				p.selectQuality(profile)
				showQualityValues()
				true
			}
		}
		findPreference<BitratePreference>(KEY_QUALITY_BITRATE)?.onReset = {
			p.putQuality(p.currentQuality.copy(bitrateMbps = p.qualityProfile.factory.bitrateMbps))
			showQualityValues()
		}
		findPreference<Preference>(KEY_QUALITY_RESET)?.setOnPreferenceClickListener {
			p.resetQuality()
			showQualityValues()
			true
		}
		findPreference<Preference>(KEY_QUALITY_SAVE)?.setOnPreferenceClickListener {
			p.saveQualityAsCustom()
			showQualityValues()
			true
		}
		refreshQuality()
	}

	/** the profile's values on screen again, after they were written directly */
	private fun showQualityValues()
	{
		val p = qualityPrefs ?: return
		findPreference<ListPreference>(QualityProfile.KEY)?.value = p.qualityProfile.id
		findPreference<ListPreference>(p.resolutionKey)?.value =
			if(p.qualityProfile == QualityProfile.AUTO)
				com.metallic.chiaki.common.AutoQuality.userResolution(p)?.value ?: com.metallic.chiaki.common.AutoQuality.RESOLUTION_AUTO
			else p.resolution.value
		findPreference<BitratePreference>(KEY_QUALITY_BITRATE)?.reload()
		findPreference<SwitchPreference>(p.offerLowerResolutionsKey)?.isChecked = p.offerLowerResolutions
		refreshQuality()
	}

	private fun refreshQuality()
	{
		val p = qualityPrefs ?: return
		val profile = p.qualityProfile
		val auto = profile == QualityProfile.AUTO
		fun name(it: QualityProfile) = getString(it.title) + if(it != QualityProfile.AUTO && p.qualityEdited(it)) "*" else ""
		findPreference<ListPreference>(QualityProfile.KEY)?.let { list ->
			list.entries = QualityProfile.values().map { name(it) }.toTypedArray()
			val v = p.currentQuality
			list.summary =
				if(auto) getString(R.string.quality_profile_auto_summary,
					com.metallic.chiaki.common.AutoQuality.userResolution(p)?.value ?: getString(R.string.quality_auto_resolution_auto))
				else getString(R.string.quality_profile_summary, name(profile), v.resolution.value, v.bitrateMbps)
		}
		// Auto: "Auto" is one more resolution; the bitrate and stepping down are the app's
		findPreference<ListPreference>(p.resolutionKey)?.let {
			val all = Preferences.resolutionAll
			it.entryValues = ((if(auto) listOf(com.metallic.chiaki.common.AutoQuality.RESOLUTION_AUTO) else listOf()) + all.map { res -> res.value }).toTypedArray()
			it.entries = ((if(auto) listOf(getString(R.string.quality_auto_resolution_auto)) else listOf()) + all.map { res -> getString(res.title) }).toTypedArray()
			// set by hand: the list's own "%s" is not redrawn when only its entries change
			val current = if(auto) com.metallic.chiaki.common.AutoQuality.userResolution(p) else p.resolution
			it.summary = current?.let { res -> getString(res.title) } ?: getString(R.string.quality_auto_resolution_auto)
		}
		findPreference<BitratePreference>(KEY_QUALITY_BITRATE)?.isVisible = !auto
		findPreference<SwitchPreference>(p.offerLowerResolutionsKey)?.isVisible = !auto
		findPreference<Preference>(KEY_QUALITY_RESET)?.let {
			it.summary = getString(R.string.quality_profile_reset_summary, getString(profile.title))
			it.isEnabled = p.qualityEdited()
		}
		findPreference<Preference>(KEY_QUALITY_SAVE)?.isVisible = !auto
		findPreference<Preference>(KEY_QUALITY_SAVE)?.isEnabled = profile != QualityProfile.CUSTOM
		// nothing below 360p to step down to
		findPreference<SwitchPreference>(p.offerLowerResolutionsKey)?.isEnabled = p.resolution != Preferences.Resolution.RES_360P
	}

	private var colorPrefs: SharedPreferences? = null

	// kept in a field: SharedPreferences only holds listeners weakly
	private val colorChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if(key == ImageSettings.colorPresetKey || key == ImageSettings.colorPaletteKey || key == ImageSettings.colorCustomKey
			|| key == ImageSettings.vignetteAxisKey || key == ImageSettings.colorBwKey || key == ImageSettings.colorSplitColorsKey
			|| ImageSettings.colorSliders.any { it.key == key })
			refreshColorPreset()
	}

	/**
	 * Color presets: each one keeps the user's changes in its own slot (ImageSettings), so
	 * choosing a preset brings back its values as they were left. "Name*" means they differ from
	 * the preset's own values (moving a slider back clears the star); ↺ and "Reset" restore those
	 * (Custom: everything flat), "Save as Custom" copies the current values to the Custom slot.
	 */
	private fun setupColorPresets()
	{
		val prefs = colorPrefs ?: return
		val presetPref = findPreference<ListPreference>(ImageSettings.colorPresetKey) ?: return

		// ↺ writes the preset's exact value (thousandths), not just a slider position
		for(slider in ImageSettings.colorSliders)
		{
			val pref = findPreference<SliderPreference>(slider.key) ?: continue
			pref.onReset = {
				prefs.edit().putInt(slider.key, slider.field(ImageSettings.factoryColorValues(ImageSettings.colorPreset(prefs)))).apply()
				pref.reload()
			}
		}

		presetPref.setOnPreferenceChangeListener { _, newValue ->
			val preset = ColorPreset.values().find { it.name == newValue } ?: return@setOnPreferenceChangeListener false
			if(preset != ImageSettings.colorPreset(prefs))
			{
				val values = ImageSettings.storedColorValues(prefs, preset)
				ImageSettings.saveColorSlot(prefs)
				applyColorValues(values)
			}
			true
		}
		findPreference<Preference>(KEY_COLOR_PRESET_RESET)?.setOnPreferenceClickListener {
			applyColorValues(ImageSettings.factoryColorValues(ImageSettings.colorPreset(prefs)))
			true
		}
		findPreference<Preference>(KEY_COLOR_PRESET_SAVE)?.setOnPreferenceClickListener {
			ImageSettings.saveAsCustom(prefs)
			presetPref.value = ColorPreset.CUSTOM.name
			refreshColorPreset()
			true
		}
		findPreference<Preference>(KEY_COLOR_LOOK_EXPORT)?.setOnPreferenceClickListener { exportLook(); true }
		findPreference<Preference>(KEY_COLOR_LOOK_IMPORT)?.setOnPreferenceClickListener {
			// .glsl files have no MIME type of their own
			val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
				addCategory(Intent.CATEGORY_OPENABLE)
				type = "*/*"
			}
			startActivityForResult(intent, PICK_LOOK_REQUEST)
			true
		}
		refreshColorPreset()
	}

	/** Writes the exact values, then shows them on the sliders and lists on screen. */
	private fun applyColorValues(values: ColorValues)
	{
		val prefs = colorPrefs ?: return
		prefs.edit().also { ImageSettings.putColorValues(it, values) }.apply()
		for(slider in ImageSettings.colorSliders)
			findPreference<SliderPreference>(slider.key)?.reload()
		findPreference<ListPreference>(ImageSettings.colorPaletteKey)?.value = values.palette.name
		findPreference<ListPreference>(ImageSettings.vignetteAxisKey)?.value = values.vignetteAxis.name
		findPreference<ListPreference>(ImageSettings.colorBwKey)?.value = values.bw.name
	}

	private fun refreshColorPreset()
	{
		val prefs = colorPrefs ?: return
		val presetPref = findPreference<ListPreference>(ImageSettings.colorPresetKey) ?: return
		val preset = ImageSettings.colorPreset(prefs)
		val edited = ImageSettings.colorPresetEdited(prefs)
		// the list shows which presets keep changes too
		val names = resources.getStringArray(R.array.preferences_color_preset_entries)
		presetPref.entries = ColorPreset.values().map { names[it.ordinal] + if(ImageSettings.colorPresetEdited(prefs, it)) "*" else "" }.toTypedArray()
		val name = names[preset.ordinal]
		presetPref.summary = if(edited) "$name*" else name

		findPreference<Preference>(KEY_COLOR_PRESET_RESET)?.let {
			it.summary = if(preset == ColorPreset.CUSTOM)
				getString(R.string.preferences_color_preset_reset_custom_summary)
			else
				getString(R.string.preferences_color_preset_reset_summary, name)
			it.isEnabled = edited
		}
		findPreference<Preference>(KEY_COLOR_PRESET_SAVE)?.isEnabled = preset != ColorPreset.CUSTOM

		// "Custom" palette only while there are free colors (from an imported look)
		findPreference<ListPreference>(ImageSettings.colorPaletteKey)?.let { pref ->
			val custom = ImageSettings.colorSplitColors(prefs) != null
			val paletteNames = resources.getStringArray(R.array.preferences_color_palette_entries).toList()
			val ids = resources.getStringArray(R.array.preferences_color_palette_values).toList()
			pref.entries = (if(custom) paletteNames + getString(R.string.preferences_color_palette_custom) else paletteNames).toTypedArray()
			pref.entryValues = (if(custom) ids + ColorGrade.Palette.CUSTOM.name else ids).toTypedArray()
			pref.summaryProvider = pref.summaryProvider // shows the entry again
		}

		// the strength only matters with a color filter; the b&w filter replaces saturation and vibrance
		val bw = ImageSettings.colorBw(prefs)
		findPreference<Preference>(ImageSettings.colorBwStrength.key)?.isEnabled =
			bw != ColorGrade.BwFilter.OFF && bw != ColorGrade.BwFilter.NEUTRAL
		for(slider in listOf(ImageSettings.colorSaturation, ImageSettings.colorVibrance))
			findPreference<Preference>(slider.key)?.isEnabled = bw == ColorGrade.BwFilter.OFF

		// with the vignette at 0 % its start and direction change nothing
		val vignetteOn = ImageSettings.vignetteAmount.get(prefs) > 0
		findPreference<Preference>(ImageSettings.vignetteStart.key)?.isEnabled = vignetteOn
		findPreference<Preference>(ImageSettings.vignetteAxisKey)?.isEnabled = vignetteOn
	}

	override fun onResume()
	{
		super.onResume()
		colorPrefs?.registerOnSharedPreferenceChangeListener(colorChangeListener)
		colorPrefs?.registerOnSharedPreferenceChangeListener(qualityChangeListener)
		refreshColorPreset()
		// the profile may have changed on the main screen
		showQualityValues()
	}

	override fun onPause()
	{
		super.onPause()
		colorPrefs?.unregisterOnSharedPreferenceChangeListener(colorChangeListener)
		colorPrefs?.unregisterOnSharedPreferenceChangeListener(qualityChangeListener)
	}

	override fun onDestroy()
	{
		super.onDestroy()
		disposable.dispose()
	}

	override fun getTitle(resources: Resources): String =
		arguments?.getString(ARG_TITLE) ?: resources.getString(R.string.title_settings)

	/** The current look as an mpv shader (LookFile), shared through the Android share menu. */
	private fun exportLook()
	{
		val activity = activity ?: return
		val prefs = colorPrefs ?: return
		val preset = ImageSettings.colorPreset(prefs)
		val dir = File(activity.cacheDir, "export_looks") // res/xml/filepaths.xml
		dir.deleteRecursively()
		dir.mkdirs()
		val file = File(dir, LookFile.name(preset) + ".glsl")
		file.writeText(LookFile.glsl(ImageSettings.colorValues(prefs), preset), Charsets.UTF_8)
		val uri = FileProvider.getUriForFile(activity, fileProviderAuthority, file)
		Intent(Intent.ACTION_SEND).also {
			it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			it.type = "text/plain"
			it.putExtra(Intent.EXTRA_STREAM, uri)
			it.clipData = ClipData.newRawUri("", uri)
			startActivity(Intent.createChooser(it, getString(R.string.action_export_look)))
		}
	}

	/**
	 * Reads a look file (only its settings line, LookFile.parse), asks before replacing the user's
	 * changes in the target preset, applies it and tells where it went.
	 */
	private fun importLook(uri: Uri)
	{
		val activity = activity ?: return
		val prefs = colorPrefs ?: return
		val text = try
		{
			activity.contentResolver.openInputStream(uri)?.use { readLimited(it) }?.toString(Charsets.UTF_8)
		}
		catch(e: IOException)
		{
			null
		}
		val imported = text?.let { LookFile.parse(it) }
		if(imported == null)
		{
			MaterialAlertDialogBuilder(activity)
				.setMessage(R.string.alert_message_look_invalid)
				.setPositiveButton(R.string.action_import_failed_ack, null)
				.show()
			return
		}

		val target = ImageSettings.importTarget(imported)
		val presetNames = resources.getStringArray(R.array.preferences_color_preset_entries)
		fun apply()
		{
			ImageSettings.importLook(prefs, target, imported.values)
			applyColorValues(imported.values)
			findPreference<ListPreference>(ImageSettings.colorPresetKey)?.value = target.name
			refreshColorPreset()
			showLookImported(imported, presetNames[target.ordinal] + if(ImageSettings.colorPresetEdited(prefs, target)) "*" else "",
				imported.name ?: displayName(uri) ?: "")
		}
		if(ImageSettings.colorPresetEdited(prefs, target) && ImageSettings.storedColorValues(prefs, target) != imported.values)
		{
			MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.alert_title_look_overwrite)
				.setMessage(getString(R.string.alert_message_look_overwrite, presetNames[target.ordinal]))
				.setNegativeButton(R.string.action_import_cancel, null)
				.setPositiveButton(R.string.action_import_import) { _, _ -> apply() }
				.show()
		}
		else
			apply()
	}

	/** Summary after importing: where the look went and only what did not come over as it was. */
	private fun showLookImported(imported: LookFile.Imported, presetName: String, lookName: String)
	{
		val activity = activity ?: return
		val lines = mutableListOf(getString(R.string.look_imported_into, lookName, presetName))
		val values = imported.values
		if(values.split > 0)
		{
			lines += if(values.palette == ColorGrade.Palette.CUSTOM)
				getString(R.string.look_imported_custom_colors)
			else
				getString(R.string.look_imported_palette, resources.getStringArray(R.array.preferences_color_palette_entries)[values.palette.ordinal])
		}
		if(LookFile.Issue.CLAMPED in imported.issues)
			lines += getString(R.string.look_issue_clamped)
		if(LookFile.Issue.UNKNOWN_VIGNETTE_SHAPE in imported.issues)
			lines += getString(R.string.look_issue_vignette)
		if(LookFile.Issue.UNKNOWN_BW_FILTER in imported.issues)
			lines += getString(R.string.look_issue_bw)
		MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.alert_title_look_imported)
			.setMessage(lines.joinToString("\n\n"))
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	/** The whole stream, or null when it is bigger than any look file (someone picked the wrong file). */
	private fun readLimited(input: InputStream): ByteArray?
	{
		val out = ByteArrayOutputStream()
		val chunk = ByteArray(8192)
		while(true)
		{
			val n = input.read(chunk)
			if(n < 0)
				return out.toByteArray()
			if(out.size() + n > MAX_LOOK_FILE_SIZE)
				return null
			out.write(chunk, 0, n)
		}
	}

	/** File name without extension, for looks that carry no name. */
	private fun displayName(uri: Uri): String? = try
	{
		activity?.contentResolver?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if(cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
		}
	}
	catch(e: Exception)
	{
		null
	}

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportSettingsWithDialog(activity, exportDisposable)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_LOOK_REQUEST && resultCode == Activity.RESULT_OK)
		{
			data?.data?.let { importLook(it) }
			return
		}
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, disposable)
			}
		}
	}
}