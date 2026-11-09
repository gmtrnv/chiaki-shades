// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject

/**
 * Tells whether a VPN (Tailscale or any other) is carrying this app's traffic, so the console list
 * can say how a console was reached and why it may not be visible away from home.
 */
class VpnMonitor(context: Context)
{
	private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
	private val vpnNetworks = mutableSetOf<Network>()
	private val vpnActiveSubject = BehaviorSubject.createDefault(false)

	val vpnActive: Observable<Boolean> get() = vpnActiveSubject.distinctUntilChanged()

	private val callback = object: ConnectivityManager.NetworkCallback()
	{
		override fun onAvailable(network: Network) = update { vpnNetworks.add(network) }
		override fun onLost(network: Network) = update { vpnNetworks.remove(network) }
	}

	init
	{
		// Requests exclude VPNs by default (NOT_VPN), so that capability has to be removed
		val request = NetworkRequest.Builder()
			.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
			.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
			.build()
		try
		{
			connectivityManager.registerNetworkCallback(request, callback)
		}
		catch(e: Exception)
		{
			Log.e("VpnMonitor", "Failed to watch VPN state: $e")
		}
	}

	private fun update(change: () -> Unit)
	{
		synchronized(vpnNetworks) {
			change()
			vpnActiveSubject.onNext(vpnNetworks.isNotEmpty())
		}
	}

	fun dispose()
	{
		try
		{
			connectivityManager.unregisterNetworkCallback(callback)
		}
		catch(e: Exception) {}
	}
}
