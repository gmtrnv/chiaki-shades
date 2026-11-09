// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.metallic.chiaki.touchcontrols.console.ConsolePadLayout
import com.metallic.chiaki.touchcontrols.console.ConsolePadSettings
import com.metallic.chiaki.touchcontrols.console.ShellTheme
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		const val MOTION_GRIP_UPRIGHT = "upright"
		const val MOTION_GRIP_FLAT = "flat"
		/** bit of each resolution in ConnectInfo.resolutionsMask, by height */
		val RESOLUTION_BITS = mapOf(360 to 1, 540 to 2, 720 to 4, 1080 to 8)
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	internal val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> bitrateAutoSubject.onNext(bitrateAuto)
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }


	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	/** where the rumble goes (see [com.metallic.chiaki.stream.Rumble.Target]) */
	val rumbleTargetKey get() = "rumble_target"
	var rumbleTarget
		get() = com.metallic.chiaki.stream.Rumble.Target.fromValue(sharedPreferences.getString(rumbleTargetKey, null))
		set(value) { sharedPreferences.edit().putString(rumbleTargetKey, value.value).apply() }

	/** strength in %: more power where the motor can change it, longer pulses where it cannot */
	val rumbleStrengthKey get() = "rumble_strength"
	var rumbleStrength
		get() = sharedPreferences.getInt(rumbleStrengthKey, com.metallic.chiaki.stream.Rumble.STRENGTH_DEFAULT)
			.coerceIn(com.metallic.chiaki.stream.Rumble.STRENGTH_MIN, com.metallic.chiaki.stream.Rumble.STRENGTH_MAX)
		set(value) { sharedPreferences.edit().putInt(rumbleStrengthKey, value.coerceIn(com.metallic.chiaki.stream.Rumble.STRENGTH_MIN,
			com.metallic.chiaki.stream.Rumble.STRENGTH_MAX)).apply() }

	/**
	 * PS5 games send their vibration only to a DualSense (as "haptics", a sound for its motors): with
	 * this on, the app says it is one and turns those haptics into rumble. Off: a DualShock 4, as the
	 * fork always said, and PS5 games do not vibrate at all.
	 */
	val rumblePs5HapticsKey get() = "rumble_ps5_haptics"
	var rumblePs5Haptics
		get() = sharedPreferences.getBoolean(rumblePs5HapticsKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumblePs5HapticsKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	/**
	 * How the device is held for the motion sensors: "upright" (screen facing the player, like
	 * aiming a bow: turning it left/right turns the camera) or "flat" (lying like a controller,
	 * screen to the ceiling; what the fork always did).
	 */
	val motionGripKey get() = resources.getString(R.string.preferences_motion_grip_key)
	var motionGripFlat
		get() = sharedPreferences.getString(motionGripKey, null) == MOTION_GRIP_FLAT
		set(value) { sharedPreferences.edit().putString(motionGripKey, if(value) MOTION_GRIP_FLAT else MOTION_GRIP_UPRIGHT).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val touchscreenTouchpadEnabledKey get() = "preferences_touchscreen_touchpad_enabled"
	var touchscreenTouchpadEnabled
		get() = sharedPreferences.getBoolean(touchscreenTouchpadEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchscreenTouchpadEnabledKey, value).apply() }

	// Home during the stream keeps it playing in a small window instead of ending the session
	val pictureInPictureEnabledKey get() = resources.getString(R.string.preferences_pip_enabled_key)
	var pictureInPictureEnabled
		get() = sharedPreferences.getBoolean(pictureInPictureEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(pictureInPictureEnabledKey, value).apply() }

	// Small graphs over the game (bitrate, losses, picture timing); long press on ✕ in the bar too
	val statsOverlayKey get() = resources.getString(R.string.preferences_stats_overlay_key)
	var statsOverlay
		get() = sharedPreferences.getBoolean(statsOverlayKey, false)
		set(value) { sharedPreferences.edit().putBoolean(statsOverlayKey, value).apply() }

	// How much loss the console is told at most (the fork: always none, so it never added repair).
	// One setting for every quality profile; a stop of LossReport.STEPS, 2 % by default.
	val lossReportStepKey get() = LossReport.KEY
	var lossReportStep
		get() = sharedPreferences.getInt(LossReport.KEY, LossReport.DEFAULT_STEP).coerceIn(0, LossReport.LAST)
		set(value) { sharedPreferences.edit().putInt(LossReport.KEY, value.coerceIn(0, LossReport.LAST)).apply() }
	val packetLossMax get() = LossReport.maxShare(lossReportStep)

	// ---- quality profiles (QualityProfiles.kt). The profile in use keeps its values in the usual
	// resolution, bitrate and "lower resolutions" keys; the others in their slots.
	var qualityProfile
		get() = QualityProfile.fromId(sharedPreferences.getString(QualityProfile.KEY, null)) ?: QualityProfile.default
		private set(value) { sharedPreferences.edit().putString(QualityProfile.KEY, value.id).apply() }

	/** what the profile in use asks for now */
	val currentQuality get() = QualityValues(resolution, ((bitrate ?: bitrateAuto) + 500) / 1000, offerLowerResolutions)

	fun putQuality(values: QualityValues)
	{
		sharedPreferences.edit()
			.putString(resolutionKey, values.resolution.value)
			.putInt(bitrateKey, validateBitrate(values.bitrateMbps * 1000))
			.putBoolean(offerLowerResolutionsKey, values.allowLower)
			.apply()
	}

	/** a profile's values: the ones in use for the current profile, its slot (or its own values) for the others */
	fun storedQuality(profile: QualityProfile) =
		if(profile == qualityProfile) currentQuality
		else QualityValues.decode(sharedPreferences.getString(profile.slotKey, null)) ?: profile.factory

	/** "Name*": the profile differs from its own values (Auto: the user fixed a resolution) */
	fun qualityEdited(profile: QualityProfile = qualityProfile) =
		if(profile == QualityProfile.AUTO) AutoQuality.userResolution(this) != null
		else storedQuality(profile) != profile.factory

	/** the current profile keeps its values in its slot and [profile] brings back its own */
	fun selectQuality(profile: QualityProfile)
	{
		val old = qualityProfile
		if(profile == old)
			return
		val values = storedQuality(profile)
		sharedPreferences.edit().putString(old.slotKey, currentQuality.encode()).apply()
		qualityProfile = profile
		putQuality(values)
	}

	/** back to the profile's own values */
	fun resetQuality()
	{
		val profile = qualityProfile
		if(profile == QualityProfile.AUTO)
			AutoQuality.setUserResolution(this, null)
		sharedPreferences.edit().remove(profile.slotKey).apply()
		putQuality(profile.factory)
	}

	/** copies the values in use to Custom and switches to it (the profile they came from keeps them too) */
	fun saveQualityAsCustom()
	{
		val values = currentQuality
		sharedPreferences.edit()
			.putString(qualityProfile.slotKey, values.encode())
			.putString(QualityProfile.CUSTOM.slotKey, values.encode())
			.apply()
		qualityProfile = QualityProfile.CUSTOM
		putQuality(values)
	}

	/** The first start: the default quality profile with its values in the keys in use */
	private fun firstQuality()
	{
		if(sharedPreferences.contains(QualityProfile.KEY))
			return
		qualityProfile = QualityProfile.default
		putQuality(QualityProfile.default.factory)
	}

	// The console may step down to lower resolutions by itself (and back up) when the network is bad
	val offerLowerResolutionsKey get() = resources.getString(R.string.preferences_offer_lower_res_key)
	var offerLowerResolutions
		get() = sharedPreferences.getBoolean(offerLowerResolutionsKey, false)
		set(value) { sharedPreferences.edit().putBoolean(offerLowerResolutionsKey, value).apply() }

	/**
	 * What to ask the console for: the video profile (its size is the highest resolution offered,
	 * the decoder is made for it) and the other resolutions offered, as ConnectInfo.resolutionsMask.
	 */
	fun connectVideoRequest(): Pair<com.metallic.chiaki.lib.ConnectVideoProfile, Int>
	{
		if(qualityProfile == QualityProfile.AUTO)
			// no console to judge the network by (the caller should have used the other one): its start
			return connectVideoRequest(AutoQuality.Decision(QualityProfile.AUTO.factory.resolution,
				QualityProfile.AUTO.factory.bitrateMbps, true, true, listOf()))
		val profile = videoProfile
		val mask = if(offerLowerResolutions) RESOLUTION_BITS.filterKeys { it < profile.height }.values.sum() else 0
		return Pair(profile, mask)
	}

	/** What to ask for in the Auto profile: its decision, with the fps and codec of Settings; one resolution lower offered */
	fun connectVideoRequest(decision: AutoQuality.Decision): Pair<com.metallic.chiaki.lib.ConnectVideoProfile, Int>
	{
		val profile = ConnectVideoProfile.preset(decision.resolution.preset, fps.preset, codec.codec)
			.copy(bitrate = validateBitrate(decision.bitrateMbps * 1000))
		val lower = RESOLUTION_BITS.filterKeys { it < profile.height }.maxByOrNull { it.key }
		val mask = if(decision.allowLower && lower != null) lower.value else 0
		return Pair(profile, mask)
	}

	// Game mode: edge swipes only peek the system bars, no back gesture over the touch controls
	val gameModeKey get() = resources.getString(R.string.preferences_game_mode_key)
	var gameMode
		get() = sharedPreferences.getBoolean(gameModeKey, true)
		set(value) { sharedPreferences.edit().putBoolean(gameModeKey, value).apply() }

	// the phone's Do not disturb while streaming (needs the user's OK in the system settings)
	val gameplayToastsKey get() = resources.getString(R.string.preferences_gameplay_toasts_key)
	/** the little action confirmations (Toasts) over the game: off by default, they get in the way (see [GameplayToast]) */
	var gameplayToasts
		get() = sharedPreferences.getBoolean(gameplayToastsKey, false)
		set(value) { sharedPreferences.edit().putBoolean(gameplayToastsKey, value).apply() }

	val streamDndKey get() = resources.getString(R.string.preferences_stream_dnd_key)
	var streamDnd
		get() = sharedPreferences.getBoolean(streamDndKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamDndKey, value).apply() }

	// Quick reconnect: a stream that leaves the screen stays connected for a while (off by default)
	val quickReconnectEnabledKey get() = resources.getString(R.string.preferences_quick_reconnect_enabled_key)
	var quickReconnectEnabled
		get() = sharedPreferences.getBoolean(quickReconnectEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(quickReconnectEnabledKey, value).apply() }

	val quickReconnectSecondsKey get() = resources.getString(R.string.preferences_quick_reconnect_seconds_key)
	/** stored as a string for the list in Settings: "30", "60" or "120" */
	var quickReconnectSeconds
		get() = sharedPreferences.getString(quickReconnectSecondsKey, null)?.toIntOrNull()?.takeIf { it in quickReconnectSecondsAll } ?: 60
		set(value) { sharedPreferences.edit().putString(quickReconnectSecondsKey, value.toString()).apply() }
	val quickReconnectSecondsAll = listOf(30, 60, 120)

	val quickReconnectPressPsKey get() = resources.getString(R.string.preferences_quick_reconnect_press_ps_key)
	var quickReconnectPressPs
		get() = sharedPreferences.getBoolean(quickReconnectPressPsKey, true)
		set(value) { sharedPreferences.edit().putBoolean(quickReconnectPressPsKey, value).apply() }

	// Volume of the game's sound only (so something else can play at the same time)
	val streamVolumeKey get() = resources.getString(R.string.preferences_stream_volume_key)
	var streamVolume
		get() = sharedPreferences.getInt(streamVolumeKey, 100).coerceIn(0, 100)
		set(value) { sharedPreferences.edit().putInt(streamVolumeKey, value.coerceIn(0, 100)).apply() }

	/** the stream bar's speaker button: muted keeps [streamVolume] for when it is unmuted */
	val streamMutedKey get() = "stream_muted"
	var streamMuted
		get() = sharedPreferences.getBoolean(streamMutedKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamMutedKey, value).apply() }

	/**
	 * What the game's sound plays at, 0..1. The ear is not linear: as a straight line, half the
	 * slider sounded nearly as loud as all of it. Squared (2.6.5), half the slider is −12 dB.
	 */
	val effectiveStreamVolume get() = if(streamMuted || streamSilenced) 0f else (streamVolume / 100f).let { it * it }

	/**
	 * The bar's speaker button (2.6.5): total silence, sound and vibration, over what the panel has on.
	 * Tapping it again brings back exactly that; the panel's choices are not touched.
	 */
	val streamSilencedKey get() = "stream_silenced"
	var streamSilenced
		get() = sharedPreferences.getBoolean(streamSilencedKey, false)
		set(value) { sharedPreferences.edit().putBoolean(streamSilencedKey, value).apply() }

	val cardAnimationKey get() = resources.getString(R.string.preferences_card_animation_key)
	var cardAnimation
		get() = CardAnimation.fromValue(sharedPreferences.getString(cardAnimationKey, null))
		set(value) { sharedPreferences.edit().putString(cardAnimationKey, value.value).apply() }

	// Mapping Keys
	val displayModeKey get() = resources.getString(R.string.preferences_display_mode_key)
	var displayMode
		// Optical by default (2.6): the app was born from it; on a 16:9 screen it changes nothing
		get() = sharedPreferences.getString(displayModeKey, null)?.let { com.metallic.chiaki.stream.TransformMode.fromValue(it) }
			?: com.metallic.chiaki.stream.TransformMode.OPTICAL
		set(value) { sharedPreferences.edit().putString(displayModeKey, value.value).apply() }

	// Color on by default (2.6), with the Cinematic preset
	val colorEnabledKey get() = resources.getString(R.string.preferences_color_enabled_key)
	var colorEnabled
		get() = sharedPreferences.getBoolean(colorEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(colorEnabledKey, value).apply() }

	val hqEnabledKey get() = resources.getString(R.string.preferences_hq_enabled_key)
	var hqEnabled
		get() = sharedPreferences.getBoolean(hqEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(hqEnabledKey, value).apply() }

	/** Our light debanding (Deband button). */
	val debandLightEnabledKey get() = resources.getString(R.string.preferences_deband_light_enabled_key)
	var debandLightEnabled
		get() = sharedPreferences.getBoolean(debandLightEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(debandLightEnabledKey, value).apply() }

	/** the physical controller's mapping */
	var padMapping: PadMapping
		get() = PadMapping.load(sharedPreferences)
		set(value) = PadMapping.save(sharedPreferences, value)

	/**
	 * General app settings for the export file ("app" block): stream, controls and the rest of
	 * Settings that is not part of the image. Mappings are Android key codes of the controller.
	 */
	val appSettingsBlock get() = SettingsBlock(
		// the touch pad travels too: its options, its layout (one JSON string) and the shell
		intKeys = listOf(bitrateKey, streamVolumeKey, lossReportStepKey, rumbleStrengthKey) + ConsolePadSettings.intKeys,
		stringKeys = listOf(resolutionKey, fpsKey, codecKey, cardAnimationKey, quickReconnectSecondsKey, motionGripKey, QualityProfile.KEY,
			rumbleTargetKey,
			AutoQuality.KEY_RESOLUTION) +
			QualityProfile.values().map { it.slotKey } + PadMapping.KEY +
				MappingSlots.KEY + MappingSlots.KEY_CURRENT +
			ConsolePadSettings.stringKeys + listOf(ConsolePadLayout.KEY, ShellTheme.KEY,
				com.metallic.chiaki.touchcontrols.console.LayoutSlots.KEY,
				com.metallic.chiaki.touchcontrols.console.LayoutSlots.KEY_CURRENT),
		booleanKeys = listOf(discoveryEnabledKey, onScreenControlsEnabledKey, rumbleEnabledKey, rumblePs5HapticsKey, motionEnabledKey,
			buttonHapticEnabledKey, logVerboseKey, touchscreenTouchpadEnabledKey, pictureInPictureEnabledKey,
			statsOverlayKey, offerLowerResolutionsKey, gameModeKey, streamDndKey, gameplayToastsKey, quickReconnectEnabledKey, quickReconnectPressPsKey, streamMutedKey, streamSilencedKey) + ConsolePadSettings.booleanKeys)

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val bitrateAutoSubject by lazy { BehaviorSubject.createDefault(bitrateAuto) }
	val bitrateAutoObservable: Observable<Int> get() = bitrateAutoSubject

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}

	// Last, once every field above exists: the first profile writes the resolution, and the change
	// listener at the top then uses bitrateAutoSubject. Run earlier (2.5.8), a fresh install
	// closed on its first start (the field was still null). Nothing built here may tell listeners
	// or throw: it runs in the middle of a screen's onCreate (2.6.5 closed on start that way).
	init
	{
		firstQuality()
	}
}