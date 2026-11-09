// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import androidx.annotation.StringRes
import com.metallic.chiaki.R

/**
 * What a quality profile asks the console for: one resolution, a bitrate (the console's ceiling,
 * Mbit/s; the real video is usually 60-80 % of it) and whether the console may step down to lower
 * resolutions by itself when the network gets bad.
 */
data class QualityValues(val resolution: Preferences.Resolution, val bitrateMbps: Int, val allowLower: Boolean)
{
	/** "720p|20|0", the form kept in a profile's slot */
	fun encode() = "${resolution.value}|$bitrateMbps|${if(allowLower) 1 else 0}"

	companion object
	{
		const val MIN_MBPS = 2
		const val MAX_MBPS = 100

		fun decode(s: String?): QualityValues?
		{
			val parts = s?.split('|') ?: return null
			if(parts.size != 3)
				return null
			val resolution = Preferences.Resolution.values().firstOrNull { it.value == parts[0] } ?: return null
			val mbps = parts[1].toIntOrNull()?.coerceIn(MIN_MBPS, MAX_MBPS) ?: return null
			return QualityValues(resolution, mbps, parts[2] == "1")
		}
	}
}

/**
 * Quality profiles, like the color presets: each one keeps the user's changes in its own slot
 * (Preferences.selectQuality), "Name*" = it differs from its own values, Custom starts as Balanced.
 * The numbers are the best measured with the network lab of 2.5.7 (720p asked at 50 Mbps gave
 * 20-38 Mbps of real video; small losses told honestly keep the bitrate, see congestioncontrol.c).
 * [weight] is only a comparison between profiles (dots), never a promise of data use.
 */
enum class QualityProfile(val id: String, @StringRes val title: Int, @StringRes val description: Int, val weight: Int, val factory: QualityValues)
{
	/**
	 * The app picks the bitrate (and the resolution, unless the user fixed one) before each
	 * connection, see [AutoQuality]. Its stored values are not used.
	 */
	AUTO("auto", R.string.quality_profile_auto, R.string.quality_profile_auto_description, 0,
		QualityValues(Preferences.Resolution.RES_720P, 15, true)),
	MAX("max", R.string.quality_profile_max, R.string.quality_profile_max_description, 4,
		QualityValues(Preferences.Resolution.RES_1080P, 40, false)),
	BALANCED("balanced", R.string.quality_profile_balanced, R.string.quality_profile_balanced_description, 3,
		QualityValues(Preferences.Resolution.RES_720P, 20, false)),
	LIGHT("light", R.string.quality_profile_light, R.string.quality_profile_light_description, 2,
		QualityValues(Preferences.Resolution.RES_540P, 10, true)),
	ULTRALIGHT("ultralight", R.string.quality_profile_ultralight, R.string.quality_profile_ultralight_description, 1,
		QualityValues(Preferences.Resolution.RES_360P, 5, false)),
	CUSTOM("custom", R.string.quality_profile_custom, R.string.quality_profile_custom_description, 0,
		QualityValues(Preferences.Resolution.RES_720P, 20, false));

	val slotKey get() = "quality_slot_$id"

	companion object
	{
		const val KEY = "quality_profile"
		val default = AUTO

		fun fromId(id: String?) = values().firstOrNull { it.id == id }

		/** "●●●○" for the profiles that have a weight */
		fun dots(weight: Int) = if(weight <= 0) "" else "●".repeat(weight) + "○".repeat(4 - weight)
	}
}

/**
 * How much loss the console may be told at most (one setting for every profile): stops of a
 * slider. Up to 2 % it adds repair pieces (FEC) without cutting the bitrate; from 3 % on it starts
 * cutting, and gives the bitrate back very slowly (a few kbps a second).
 */
object LossReport
{
	const val KEY = "net_loss_report_step"
	/** % told at most at each stop; the last one is "everything" */
	val STEPS = intArrayOf(0, 1, 2, 3, 4, 5, 100)
	const val DEFAULT_STEP = 2
	val LAST = STEPS.size - 1

	fun maxShare(step: Int) = STEPS[step.coerceIn(0, LAST)] / 100.0

	/** "No", "2 %", "All" */
	fun stepName(context: Context, step: Int) = when(step)
	{
		0 -> context.getString(R.string.loss_report_step_off)
		LAST -> context.getString(R.string.loss_report_step_all)
		else -> context.getString(R.string.loss_report_step_percent, STEPS[step.coerceIn(0, LAST)])
	}
}
