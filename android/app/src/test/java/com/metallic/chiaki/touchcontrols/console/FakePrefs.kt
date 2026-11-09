// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.SharedPreferences

/** just enough of SharedPreferences for the tests: a map, edits apply at once */
internal class FakePrefs(val values: MutableMap<String, Any> = mutableMapOf()) : SharedPreferences
{
	override fun getAll(): MutableMap<String, *> = values
	override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
	override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
	override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
	override fun getLong(key: String?, defValue: Long) = defValue
	override fun getFloat(key: String?, defValue: Float) = defValue
	override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
	override fun contains(key: String?) = values.containsKey(key)
	override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor
	{
		override fun putString(key: String?, value: String?) = also { values[key!!] = value!! }
		override fun putStringSet(key: String?, values: MutableSet<String>?) = this
		override fun putInt(key: String?, value: Int) = also { this@FakePrefs.values[key!!] = value }
		override fun putLong(key: String?, value: Long) = this
		override fun putFloat(key: String?, value: Float) = this
		override fun putBoolean(key: String?, value: Boolean) = also { this@FakePrefs.values[key!!] = value }
		override fun remove(key: String?) = also { this@FakePrefs.values.remove(key!!) }
		override fun clear() = also { values.clear() }
		override fun commit() = true
		override fun apply() {}
	}
	override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
	override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
