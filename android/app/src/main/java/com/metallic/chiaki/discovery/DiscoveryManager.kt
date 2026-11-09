// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.discovery

import android.util.Log
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ext.hexToByteArray
import com.metallic.chiaki.lib.CreateError
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import java.lang.NumberFormatException
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val DiscoveryHost.serverMac get() = this.hostId?.hexToByteArray()?.let {
	if(it.size == MacAddress.LENGTH)
		MacAddress(it)
	else
		null
}

class DiscoveryManager
{
	companion object
	{
		const val HOSTS_MAX: ULong = 16U
		const val DROP_PINGS: ULong = 3U
		const val PING_MS: ULong = 500U
		const val PROBE_PING_MS: ULong = 1000U // a VPN on mobile data can be slow
		const val PORT = 987

		const val DEBOUNCE_EMPTY_MS = 1000L
		const val SEARCHING_MS = 3500L // a few direct questions through a slow VPN
	}

	private var discoveryService: DiscoveryService? = null

	private val discoveryActiveSubject: Subject<Boolean> = BehaviorSubject.create<Boolean>().also { it.onNext(false) }
	val discoveryActive: Observable<Boolean> get() = discoveryActiveSubject
	/** the user's setting: search all the time while the list is on screen */
	var active = false
		set(value)
		{
			field = value
			if(!value)
				searchByHand = false
			discoveryActiveSubject.onNext(value)
			updateService()
		}
	private var paused = false

	/**
	 * A search asked by hand (pulling the list down) with the setting off: it runs like the
	 * setting was on until the list leaves the screen; coming back, it is off again.
	 */
	@Volatile private var searchByHand = false

	private val runningSubject: Subject<Boolean> = BehaviorSubject.createDefault(false).toSerialized()
	/** whether consoles are being looked for while the list is on screen (the setting, or a search by hand) */
	val running: Observable<Boolean> get() = runningSubject.distinctUntilChanged()

	private val wanted get() = (active || searchByHand) && !paused

	/** Pulling the list down: search now (with the setting off too) */
	fun searchNow()
	{
		if(active)
			startSearching()
		else
		{
			searchByHand = true
			updateService()
		}
	}

	private val disposable = CompositeDisposable()

	private var discoveredHostsSubjectDebounced: Subject<List<DiscoveryHost>> = BehaviorSubject.create<List<DiscoveryHost>>().also {
		it.onNext(listOf())
	}.toSerialized()

	private var discoveredHostsSubjectRaw: Subject<List<DiscoveryHost>> = BehaviorSubject.create<List<DiscoveryHost>>().also { subject ->
		subject.debounce { hosts ->
				if(hosts.isEmpty())
					Observable.timer(DEBOUNCE_EMPTY_MS, TimeUnit.MILLISECONDS)
				else
					Observable.empty()
			}
			.subscribe { hosts ->
				discoveredHostsSubjectDebounced.onNext(hosts)
			}
			.addTo(disposable)
	}

	val discoveredHosts: Observable<List<DiscoveryHost>> get() = discoveredHostsSubjectDebounced

	fun resume()
	{
		paused = false
		updateService()
	}

	fun pause()
	{
		paused = true
		searchByHand = false
		updateService()
	}

	fun dispose()
	{
		active = false
		disposable.dispose()
		searchingTimer?.dispose()
		probeExecutor.shutdown()
	}

	fun sendWakeup(host: String, registKey: ByteArray, ps5: Boolean)
	{
		val registKeyString = registKey.indexOfFirst { it == 0.toByte() }.let { end -> registKey.copyOfRange(0, if(end >= 0) end else registKey.size) }.toString(StandardCharsets.UTF_8)
		val credential = try { registKeyString.toULong(16) } catch(e: NumberFormatException) {
			Log.e("DiscoveryManager", "Failed to convert registKey to int", e)
			return
		}
		DiscoveryService.wakeup(discoveryService, host, credential, ps5)
	}

	// --- Direct questions to consoles added by address ---
	// The broadcast above never leaves the local network (a VPN does not carry it), so each
	// console added by address also gets its own discovery service that asks that address directly.
	// Services are created on a separate thread because a host name may need a DNS lookup.

	private val probeExecutor = Executors.newSingleThreadExecutor()
	private val probeServices = mutableMapOf<String, DiscoveryService>() // only touched on probeExecutor
	@Volatile private var probeTargets: Set<String> = setOf()
	private val probeResults = ConcurrentHashMap<String, DiscoveryHost>()
	private val probedHostsSubject: Subject<Map<String, DiscoveryHost>> =
		BehaviorSubject.createDefault<Map<String, DiscoveryHost>>(mapOf()).toSerialized()

	/** Answer of each console added by address, by its address as typed */
	val probedHosts: Observable<Map<String, DiscoveryHost>> get() = probedHostsSubject

	fun setProbeTargets(hosts: Set<String>)
	{
		probeTargets = hosts
		updateProbes()
	}

	private fun updateProbes()
	{
		if(probeExecutor.isShutdown)
			return
		probeExecutor.execute {
			val targets = if(wanted) probeTargets else setOf()
			(probeServices.keys - targets).forEach { host ->
				probeServices.remove(host)?.dispose()
				probeResults.remove(host)
			}
			(targets - probeServices.keys).forEach { host ->
				try
				{
					probeServices[host] = DiscoveryService(DiscoveryServiceOptions(
						1U, DROP_PINGS, PROBE_PING_MS, InetSocketAddress(host, PORT)
					)) { hosts ->
						val answer = hosts.firstOrNull()
						if(answer != null)
							probeResults[host] = answer
						else
							probeResults.remove(host)
						probedHostsSubject.onNext(HashMap(probeResults))
					}
				}
				catch(e: Exception)
				{
					Log.e("DiscoveryManager", "Failed to start probe for $host: $e")
				}
			}
			probedHostsSubject.onNext(HashMap(probeResults))
		}
	}

	// Right after discovery starts nothing has answered yet: "searching" instead of "not visible"
	private val searchingSubject: Subject<Boolean> = BehaviorSubject.createDefault(false).toSerialized()
	val searching: Observable<Boolean> get() = searchingSubject
	private var searchingTimer: Disposable? = null

	private fun startSearching()
	{
		searchingSubject.onNext(true)
		searchingTimer?.dispose()
		searchingTimer = Observable.timer(SEARCHING_MS, TimeUnit.MILLISECONDS)
			.subscribe { searchingSubject.onNext(false) }
	}

	private fun updateService()
	{
		updateProbes()
		runningSubject.onNext(active || searchByHand)
		if(wanted && discoveryService == null)
		{
			discoveredHostsSubjectRaw.onNext(listOf())
			startSearching()
			try
			{
				discoveryService = DiscoveryService(DiscoveryServiceOptions(
					HOSTS_MAX, DROP_PINGS, PING_MS, InetSocketAddress("255.255.255.255", PORT)
				), discoveredHostsSubjectRaw::onNext)
			}
			catch(e: CreateError)
			{
				Log.e("DiscoveryManager", "Failed to start Discovery Service: $e")
			}
		}
		else if(!wanted && discoveryService != null)
		{
			val service = discoveryService ?: return
			service.dispose()
			discoveryService = null
			if(!active)
				discoveredHostsSubjectRaw.onNext(listOf())
		}
	}
}