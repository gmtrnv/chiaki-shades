// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A PlayStation account known on this phone: its Account ID (8 bytes, base64), a name to show and
 * its color (an index in [AccountColors]; purely cosmetic, it tints the main screen)
 */
data class PsnAccount(val accountId: String, val name: String, val color: Int = 0)
{
	val colorValue get() = AccountColors.value(color)
}

/** The palette of account colors: picked at random when an account is added, changed in the accounts sheet */
object AccountColors
{
	val values = intArrayOf(
		0xFFFF6B6B.toInt(), // coral
		0xFFFFB347.toInt(), // amber
		0xFFA8E063.toInt(), // lime
		0xFF3DDC97.toInt(), // mint
		0xFF2EC4B6.toInt(), // teal
		0xFF4EA8DE.toInt(), // sky
		0xFF9D8DF1.toInt(), // lavender
		0xFFF072B6.toInt()  // pink
	)

	fun value(index: Int) = values[Math.floorMod(index, values.size)]

	/** A random color no other account has, while any is left */
	fun pickFree(taken: Collection<Int>): Int
	{
		val free = values.indices.filter { it !in taken }
		return (free.ifEmpty { values.indices.toList() }).random()
	}

	/** Accounts saved before colors existed get a fixed one from their ID (saved on the next change) */
	fun fallback(accountId: String) = Math.floorMod(accountId.hashCode(), values.size)
}

/**
 * The PlayStation accounts saved on this phone and the one in use.
 *
 * The Account ID is not a secret (it is the account's number; without the console's PIN it opens
 * nothing), so it is kept to avoid signing in again. It lives in its own file, outside the settings
 * export: it belongs to this phone. The token used to learn it is never stored.
 */
class PsnAccounts(context: Context)
{
	companion object
	{
		private const val FILE = "psn_accounts"
		private const val KEY_LIST = "accounts"
		private const val KEY_ACTIVE = "active"
		private val listeners = mutableSetOf<() -> Unit>()

		/** Called (main thread) whenever the accounts or the active one change */
		fun addListener(listener: () -> Unit) { listeners.add(listener) }
		fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
	}

	private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

	val all: List<PsnAccount> get()
	{
		val raw = prefs.getString(KEY_LIST, null) ?: return listOf()
		return try
		{
			val array = JSONArray(raw)
			(0 until array.length()).mapNotNull { i ->
				val o = array.optJSONObject(i) ?: return@mapNotNull null
				val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
				PsnAccount(id, o.optString("name").ifBlank { id }, o.optInt("color", AccountColors.fallback(id)))
			}
		}
		catch(e: Exception) { listOf() }
	}

	/** The account used to connect and register; the first one if none was picked */
	val active: PsnAccount? get()
	{
		val accounts = all
		val id = prefs.getString(KEY_ACTIVE, null)
		return accounts.firstOrNull { it.accountId == id } ?: accounts.firstOrNull()
	}

	fun get(accountId: String?) = accountId?.let { id -> all.firstOrNull { it.accountId == id } }

	/** Adds the account (or renames it if already known, keeping a name the user chose) and makes it active */
	fun add(accountId: String, name: String?)
	{
		val accounts = all.toMutableList()
		val index = accounts.indexOfFirst { it.accountId == accountId }
		if(index >= 0)
		{
			if(name != null && accounts[index].name == accounts[index].accountId)
				accounts[index] = accounts[index].copy(name = name)
		}
		else
			accounts.add(PsnAccount(accountId, name ?: accountId, AccountColors.pickFree(accounts.map { it.color })))
		save(accounts, accountId)
	}

	/** Makes the account known (from an import or a typed Account ID) without changing the one in use */
	fun ensure(accountId: String, name: String?)
	{
		val accounts = all
		if(accounts.any { it.accountId == accountId })
			return
		save(accounts + PsnAccount(accountId, name?.takeIf { it.isNotBlank() } ?: accountId, AccountColors.pickFree(accounts.map { it.color })),
			prefs.getString(KEY_ACTIVE, null) ?: accountId)
	}

	fun setColor(accountId: String, color: Int) =
		save(all.map { if(it.accountId == accountId) it.copy(color = color) else it }, prefs.getString(KEY_ACTIVE, null))

	fun rename(accountId: String, name: String)
	{
		if(name.isBlank())
			return
		save(all.map { if(it.accountId == accountId) it.copy(name = name.trim()) else it }, prefs.getString(KEY_ACTIVE, null))
	}

	fun remove(accountId: String)
	{
		val accounts = all.filter { it.accountId != accountId }
		val activeId = prefs.getString(KEY_ACTIVE, null)
		save(accounts, if(activeId == accountId) accounts.firstOrNull()?.accountId else activeId)
	}

	fun setActive(accountId: String) = save(all, accountId)

	private fun save(accounts: List<PsnAccount>, activeId: String?)
	{
		val array = JSONArray()
		accounts.forEach { array.put(JSONObject().put("id", it.accountId).put("name", it.name).put("color", it.color)) }
		prefs.edit().putString(KEY_LIST, array.toString()).putString(KEY_ACTIVE, activeId).apply()
		listeners.toList().forEach { it() }
	}
}
