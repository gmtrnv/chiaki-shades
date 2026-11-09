// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.lib.DiscoveryHost
import java.util.Objects

/** How the console was reached, shown on its card */
enum class Reach
{
	HOME, // answered the broadcast: same local network
	VPN, // answered only the direct question, with a VPN on
	DIRECT, // answered only the direct question, without a VPN (routed or public address)
	SEARCHING, // no answer yet, discovery just started
	UNREACHABLE, // no answer, no VPN
	UNREACHABLE_VPN, // no answer, with a VPN on
	DISCOVERY_OFF // discovery is turned off, the state is unknown
}

sealed class DisplayHost
{
	abstract val reach: Reach
	abstract val registeredHost: RegisteredHost?
	abstract val host: String
	abstract val name: String?
	abstract val id: String?
	abstract val isPS5: Boolean
	/** What the console answered to discovery (state, running app), if it answered */
	abstract val live: DiscoveryHost?
	/**
	 * Names of the other PlayStation accounts this console is registered with on this phone, when it
	 * is not registered with the account in use (the card then offers to register it with this one)
	 */
	abstract val otherAccountNames: List<String>

	val isRegistered get() = registeredHost != null
}

/**
 * A console found on the local network. [stale]: it stopped answering while waking up or going to
 * rest (the PS5 drops off the network for a while then): its last answer keeps the card on screen,
 * but it counts as not answering ([live] is null) until it answers again.
 */
class DiscoveredDisplayHost(
	override val registeredHost: RegisteredHost?,
	val discoveredHost: DiscoveryHost,
	override val otherAccountNames: List<String> = listOf(),
	val stale: Boolean = false
): DisplayHost()
{
	override val host get() = discoveredHost.hostAddr ?: ""
	override val name get() = discoveredHost.hostName ?: registeredHost?.serverNickname
	override val id get() = discoveredHost.hostId ?: registeredHost?.serverMac?.toString()
	override val isPS5 get() = discoveredHost.isPS5
	override val live get() = if(stale) null else discoveredHost
	override val reach get() = if(stale) Reach.SEARCHING else Reach.HOME

	override fun equals(other: Any?): Boolean =
		if(other !is DiscoveredDisplayHost)
			false
		else
			other.discoveredHost == discoveredHost && other.registeredHost == registeredHost && other.otherAccountNames == otherAccountNames
				&& other.stale == stale

	override fun hashCode() = Objects.hash(registeredHost, discoveredHost, otherAccountNames, stale)

	override fun toString() = "DiscoveredDisplayHost{${registeredHost}, ${discoveredHost}, stale=${stale}}"
}

/**
 * A console added by address. If the same console was also found on the local network,
 * [discovered] holds that answer and both are shown as one card that connects through the local address.
 * [probed] is the answer to the direct question sent to its address (also works through a VPN).
 */
class ManualDisplayHost(
	override val registeredHost: RegisteredHost?,
	val manualHost: ManualHost,
	val discovered: DiscoveryHost?,
	val probed: DiscoveryHost?,
	override val reach: Reach,
	override val otherAccountNames: List<String> = listOf()
): DisplayHost()
{
	override val host get() = discovered?.hostAddr ?: manualHost.host
	override val name get() = live?.hostName ?: registeredHost?.serverNickname
	override val id get() = registeredHost?.serverMac?.toString() ?: live?.hostId
	override val isPS5: Boolean get() = registeredHost?.target?.isPS5 ?: live?.isPS5 ?: false
	override val live get() = discovered ?: probed

	override fun equals(other: Any?): Boolean =
		if(other !is ManualDisplayHost)
			false
		else
			other.manualHost == manualHost && other.registeredHost == registeredHost
				&& other.discovered == discovered && other.probed == probed && other.reach == reach
				&& other.otherAccountNames == otherAccountNames

	override fun hashCode() = Objects.hash(registeredHost, manualHost, discovered, probed, reach, otherAccountNames)

	override fun toString() = "ManualDisplayHost{${registeredHost}, ${manualHost}, ${discovered}, ${probed}, ${reach}}"
}