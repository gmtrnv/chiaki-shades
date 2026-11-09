// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/**
 * Tips on the main screen, one new each day, telling the app's features little by little instead
 * of a tutorial: the first one (Optical) first, then the others in a random order. The texts live
 * in res/values/tips.xml and values-es/tips.xml, nothing else to edit to review them. The light
 * bulb opens the welcome sheet, which lists them all; the welcome also opens by itself once, the
 * first time a fresh install opens. Settings (and the welcome) can turn the daily tip off.
 */
object Tips
{
	const val KEY_ENABLED = "tips_enabled"
	/** the tips given so far, as their indexes in order ("0,5,2") */
	private const val KEY_ORDER = "tips_order"
	/** the day (days since 1970, local time) the last one was given */
	private const val KEY_DAY = "tips_day"
	/** the last one given has been closed */
	private const val KEY_READ = "tips_read"
	/** the welcome sheet opened by itself already (or the install is older than the welcome) */
	private const val KEY_WELCOMED = "welcome_shown"

	private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

	fun all(context: Context): Array<String> = context.resources.getStringArray(R.array.tips)

	fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

	fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

	private fun today(): Long
	{
		val now = System.currentTimeMillis()
		return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
	}

	/** indexes of the tips given so far, oldest first */
	private fun order(context: Context): List<Int>
	{
		val p = prefs(context)
		val count = all(context).size
		val list = p.getString(KEY_ORDER, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
		return list.filter { it in 0 until count }.distinct()
	}

	/**
	 * Today's tip if it is still to be read: gives a new one the first time the app opens on a new
	 * day (while there are tips left). Null when tips are off, all are read, or today's was closed.
	 */
	fun current(context: Context): String?
	{
		if(!enabled(context))
			return null
		val p = prefs(context)
		val tips = all(context)
		var order = order(context)
		if(p.getLong(KEY_DAY, -1L) != today() && order.size < tips.size)
		{
			val next = if(order.isEmpty()) 0 else (tips.indices - order.toSet()).random()
			order = order + next
			p.edit().putString(KEY_ORDER, order.joinToString(","))
				.putLong(KEY_DAY, today()).putBoolean(KEY_READ, false).apply()
		}
		if(order.isEmpty() || p.getBoolean(KEY_READ, false))
			return null
		return tips.getOrNull(order.last())
	}

	/** today's tip was closed */
	fun markRead(context: Context) = prefs(context).edit().putBoolean(KEY_READ, true).apply()

	fun hasUnread(context: Context) =
		enabled(context) && order(context).isNotEmpty() && !prefs(context).getBoolean(KEY_READ, false)

	/**
	 * True once: the first time a fresh install opens, to show the welcome by itself. An install
	 * that already gave tips counts as welcomed.
	 */
	fun takeWelcome(context: Context): Boolean
	{
		val p = prefs(context)
		if(p.getBoolean(KEY_WELCOMED, false))
			return false
		val fresh = !p.contains(KEY_ORDER)
		p.edit().putBoolean(KEY_WELCOMED, true).apply()
		return fresh
	}
}
