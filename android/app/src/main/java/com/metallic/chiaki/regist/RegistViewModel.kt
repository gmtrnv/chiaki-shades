// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.lib.DiscoveryHost

class RegistViewModel: ViewModel()
{
	enum class ConsoleVersion {
		PS5,
		PS4_GE_8,
		PS4_GE_7,
		PS4_LT_7;

		val isPS5 get() = this == PS5
	}

	val ps4Version = MutableLiveData<ConsoleVersion>(ConsoleVersion.PS5)

	/** Its own search for consoles on the network, to pick one instead of typing its address */
	val discoveryManager = DiscoveryManager().also { it.active = true }
	val discoveredHosts: LiveData<List<DiscoveryHost>> = discoveryManager.discoveredHosts.toLiveData()

	/** The console picked from the list (by its address), or null = typed by hand */
	val selectedHost = MutableLiveData<String?>(null)

	/** "Advanced" (console version, Account ID by hand) is open */
	val advancedOpen = MutableLiveData(false)

	override fun onCleared()
	{
		super.onCleared()
		discoveryManager.dispose()
	}
}
