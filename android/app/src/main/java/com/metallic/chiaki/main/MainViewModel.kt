// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.VpnMonitor
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.DiscoveryHost
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject

/** Registered consoles are matched by MAC; unregistered ones only by address */
private fun DiscoveryHost.isSameConsole(manualHost: ManualHost, registeredHost: RegisteredHost?) =
	if(registeredHost != null)
		serverMac == registeredHost.serverMac
	else
		hostAddr == manualHost.host

class MainViewModel(val database: AppDatabase, val preferences: Preferences, appContext: Context): ViewModel()
{
	private val disposable = CompositeDisposable()

	private val vpnMonitor = VpnMonitor(appContext)

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	init
	{
		// Consoles added by address are also asked directly, so they can be seen through a VPN
		database.manualHostDao().getAll()
			.subscribe { manualHosts -> discoveryManager.setProbeTargets(manualHosts.map { it.host }.toSet()) }
			.addTo(disposable)
	}

	// The PlayStation account in use ("" = none saved): each console shows its registration for that account
	private val psnAccounts = PsnAccounts(appContext)
	private val accountsSubject = BehaviorSubject.createDefault(Unit)
	private val accountsListener: () -> Unit = { accountsSubject.onNext(Unit) }.also { PsnAccounts.addListener(it) }

	/**
	 * Which registration of a console is used: the one for the account in use; else one from before
	 * accounts were kept (it may be anyone's, it keeps working as before); with no account saved, any.
	 * Null if the console is only registered with other accounts: the card offers to register it.
	 */
	private fun pickRegistration(registrations: List<RegisteredHost>?, active: String?): RegisteredHost? =
		if(registrations.isNullOrEmpty())
			null
		else if(active == null)
			registrations.firstOrNull { it.psnAccountId == null } ?: registrations.first()
		else
			registrations.firstOrNull { it.psnAccountId == active } ?: registrations.firstOrNull { it.psnAccountId == null }

	/**
	 * Registered consoles busy waking up, going to rest… (see [consoleActivity]). Waking up, a PS5 stops
	 * answering discovery for a few seconds: its card would vanish ("no consoles") right while it says
	 * "waking up". While busy, a console found on the local network keeps its card with its last answer.
	 */
	private val busyConsoles = BehaviorSubject.createDefault(emptySet<Long>())
	/** the last answer of each console found on the local network, by MAC */
	private val lastDiscovered = java.util.concurrent.ConcurrentHashMap<MacAddress, DiscoveryHost>()

	val displayHosts = Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts,
			discoveryManager.probedHosts,
			discoveryManager.running,
			discoveryManager.searching,
			vpnMonitor.vpnActive,
			accountsSubject,
			busyConsoles.distinctUntilChanged())
			{ manualHosts, registeredHosts, discoveredHosts, probedHosts, discoveryActive, searching, vpnActive, _, busy ->
				val active = psnAccounts.active?.accountId
				val names = psnAccounts.all.associate { it.accountId to it.name }
				val macRegistrations = registeredHosts.groupBy { it.serverMac }
				val idRegisteredHosts = registeredHosts.associateBy { it.id }
				fun pick(mac: MacAddress?) = mac?.let { pickRegistration(macRegistrations[it], active) }
				fun others(mac: MacAddress?, picked: RegisteredHost?) =
					if(mac == null || picked != null) listOf()
					else macRegistrations[mac].orEmpty().mapNotNull { r -> r.psnAccountId?.let { names[it] ?: it } }.distinct()
				// A console added by address that is also found on the local network is shown once:
				// the manual card takes the discovered answer and the discovered card is dropped.
				val merged = mutableSetOf<DiscoveryHost>()
				val manualDisplayHosts = manualHosts.map { manualHost ->
					// the entry is linked to one registration; the console (its MAC) is what counts
					val linked = manualHost.registeredHost?.let { id -> idRegisteredHosts[id] }
					val linkedMac = linked?.serverMac
					val registeredHost = pick(linkedMac)
					// Whatever answers at that address must be this console: away from home the same
					// local address may belong to another device of that network.
					val probed = probedHosts[manualHost.host]?.takeIf {
						linkedMac == null || it.serverMac == linkedMac
					}
					val discovered = discoveredHosts.firstOrNull {
						it.isSameConsole(manualHost, linked) || (probed?.serverMac != null && it.serverMac == probed.serverMac)
					}
					discovered?.let { merged.add(it) }
					val reach = when
					{
						!discoveryActive -> Reach.DISCOVERY_OFF
						discovered != null -> Reach.HOME
						probed != null -> if(vpnActive) Reach.VPN else Reach.DIRECT
						searching -> Reach.SEARCHING
						vpnActive -> Reach.UNREACHABLE_VPN
						else -> Reach.UNREACHABLE
					}
					ManualDisplayHost(registeredHost, manualHost, discovered, probed, reach, others(linkedMac, registeredHost))
				}
				discoveredHosts.forEach { host -> host.serverMac?.let { lastDiscovered[it] = host } }
				val discoveredDisplayHosts = discoveredHosts.filter { it !in merged }.map {
					val registeredHost = pick(it.serverMac)
					DiscoveredDisplayHost(registeredHost, it, others(it.serverMac, registeredHost))
				}
				// busy consoles that stopped answering: their card stays, "searching" until they answer again
				val shown = discoveredDisplayHosts + manualDisplayHosts
				val kept = if(!discoveryActive) listOf() else busy.mapNotNull { id ->
					val mac = idRegisteredHosts[id]?.serverMac ?: return@mapNotNull null
					if(shown.any { it.registeredHost?.serverMac == mac })
						return@mapNotNull null
					val last = lastDiscovered[mac] ?: return@mapNotNull null
					val registeredHost = pick(mac)?.takeIf { it.id == id } ?: return@mapNotNull null
					DiscoveredDisplayHost(registeredHost, last, others(mac, registeredHost), stale = true)
				}
				discoveredDisplayHosts + kept + manualDisplayHosts
			}
			.toLiveData()

	val discoveryActive = discoveryManager.discoveryActive.toLiveData()
	/** consoles are being looked for now: the setting, or a search by hand (pulling the list down) */
	val searchRunning = discoveryManager.running.toLiveData()

	/**
	 * Pulling the list down: look for consoles now (even with discovery off, until the list leaves
	 * the screen) and measure the network again from scratch, so Auto decides with fresh answers.
	 */
	fun refresh()
	{
		discoveryManager.searchNow()
		com.metallic.chiaki.discovery.RttMeter.remeasure()
	}

	/** What each console is going through (waking up, disconnecting…); lives here so it survives rotation */
	val consoleActivity = ConsoleActivityTracker { host, registeredHost ->
		discoveryManager.sendWakeup(host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	// a paused session's console is connected and answering: it needs no kept card
	private val busyObserver = androidx.lifecycle.Observer<Map<Long, ConsoleActivityTracker.Activity>> { state ->
		busyConsoles.onNext(state.filterValues { it.phase != ConsoleActivityTracker.Phase.PAUSED }.keys)
	}.also { consoleActivity.state.observeForever(it) }

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		PsnAccounts.removeListener(accountsListener)
		consoleActivity.state.removeObserver(busyObserver)
		disposable.dispose()
		consoleActivity.dispose()
		discoveryManager.dispose()
		vpnMonitor.dispose()
	}
}