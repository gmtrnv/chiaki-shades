// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.SharedPreferences

/**
 * A group of SharedPreferences keys that is exported and imported together (one block of the
 * settings file, e.g. "image" or "app"). Only known keys with the right type are written back.
 */
class SettingsBlock(
	private val intKeys: Collection<String>,
	private val stringKeys: Collection<String>,
	private val booleanKeys: Collection<String>
)
{
	/** Only the values the user has changed (the rest are defaults and are not stored). */
	fun export(prefs: SharedPreferences): Map<String, Any>
	{
		val all = prefs.all
		return (intKeys + stringKeys + booleanKeys).mapNotNull { key -> all[key]?.let { key to it } }.toMap()
	}

	/** Writes back what export() produced; unknown keys and wrong types are skipped. Returns how many were applied. */
	fun import(prefs: SharedPreferences, values: Map<String, Any?>): Int
	{
		val editor = prefs.edit()
		var count = 0
		for((key, value) in values)
		{
			when
			{
				key in intKeys && value is Number -> editor.putInt(key, value.toInt()) // JSON numbers come back as Double
				key in stringKeys && value is String -> editor.putString(key, value)
				key in booleanKeys && value is Boolean -> editor.putBoolean(key, value)
				else -> continue
			}
			count++
		}
		editor.apply()
		return count
	}
}
