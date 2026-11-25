// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.util.Log

/**
 * Keeps an entry by address (a [ManualHost]) for each registered console.
 *
 * Only consoles added by address are asked directly, and only a direct question reaches a console
 * through a VPN: the broadcast never leaves the local network. A console registered from the card
 * found at home had no such entry, so away from home it had no card at all. Now registering a
 * console gives it an entry, and consoles registered before get one the first time they are found
 * on the local network. At home nothing changes: the entry and the console found are one card.
 *
 * Each console gets its entry made for it only once: if the user deletes it, it stays deleted
 * (registering the console again makes it again). Entries are also kept in step: an entry left
 * unlinked at the console's address gets linked again, and if the console's local address changes,
 * an entry with a local address follows it (host names and other addresses are never touched).
 */
object ConsoleEntries
{
	private const val TAG = "ConsoleEntries"
	private const val PREFS = "console_entries"
	private const val KEY_MADE = "made_for" // MACs of the consoles that already got their entry made

	private val lock = Any()

	/**
	 * Called on a background thread. [address]: where the console answers on the local network (null if
	 * unknown, e.g. a registration by broadcast). [registered]: it was just registered, so it gets its
	 * entry even if one was made (and deleted) before.
	 */
	fun ensure(context: Context, database: AppDatabase, mac: MacAddress, address: String?, registered: Boolean)
	{
		synchronized(lock)
		{
			try
			{
				database.runInTransaction { update(context, database, mac, address, registered) }
			}
			catch(e: Exception)
			{
				Log.e(TAG, "Failed to keep the entry of $mac", e)
			}
		}
	}

	/**
	 * Cheap check (no database access) whether [ensure] has something to do for a console found at
	 * [address] on the local network, from lists already in memory.
	 */
	fun needsWork(context: Context, mac: MacAddress, address: String, manualHosts: List<ManualHost>, registeredHosts: List<RegisteredHost>): Boolean
	{
		val ids = registeredHosts.filter { it.serverMac == mac }.map { it.id }.toSet()
		if(ids.isEmpty())
			return false
		val own = manualHosts.filter { it.registeredHost in ids }
		if(manualHosts.any { it.host == address && it.registeredHost == null })
			return true // an entry at its address lost its link
		if(own.isEmpty())
			return mac.toString() !in made(context)
		return own.none { it.host == address } && own.any { isLocalIPv4(it.host) } && isLocalIPv4(address)
	}

	private fun update(context: Context, database: AppDatabase, mac: MacAddress, address: String?, registered: Boolean)
	{
		val registrations = database.registeredHostDao().getAllNow().filter { it.serverMac == mac }
		if(registrations.isEmpty())
			return
		val ids = registrations.map { it.id }.toSet()
		val linkTo = registrations.last().id // any registration of the console: cards pick by account
		val manualDao = database.manualHostDao()
		var manualHosts = manualDao.getAllNow()

		// an entry at its address that lost its link (e.g. the registration it pointed to was replaced)
		if(address != null)
			manualHosts.filter { it.host == address && it.registeredHost == null }.forEach {
				manualDao.updateNow(it.copy(registeredHost = linkTo))
			}
		manualHosts = manualDao.getAllNow()

		val own = manualHosts.filter { it.registeredHost in ids }
		if(own.isEmpty())
		{
			if(address == null || !isUsableAddress(address))
				return
			if(!registered && mac.toString() in made(context))
				return // the user deleted it
			manualDao.insertNow(ManualHost(host = address, registeredHost = linkTo))
			Log.i(TAG, "Made the entry of $mac")
		}
		else if(address != null && isLocalIPv4(address) && own.none { it.host == address })
		{
			// the console moved to another local address: an entry with a local address follows it
			own.firstOrNull { isLocalIPv4(it.host) }?.let {
				manualDao.updateNow(it.copy(host = address))
				Log.i(TAG, "Moved the entry of $mac to its new address")
			}
		}
		markMade(context, mac)
	}

	private fun made(context: Context): Set<String> =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_MADE, null) ?: setOf()

	private fun markMade(context: Context, mac: MacAddress)
	{
		val made = made(context)
		if(mac.toString() in made)
			return
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putStringSet(KEY_MADE, made + mac.toString())
			.apply()
	}

	/** a broadcast address is no console's address */
	private fun isUsableAddress(address: String) =
		address.isNotBlank() && address != "255.255.255.255"

	/** a local network IPv4 address (10/8, 172.16/12, 192.168/16): one the router may hand out again */
	fun isLocalIPv4(address: String): Boolean
	{
		val parts = address.split('.')
		if(parts.size != 4)
			return false
		val b = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
		return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
	}
}
