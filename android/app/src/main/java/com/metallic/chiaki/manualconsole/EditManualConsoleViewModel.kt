// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.manualconsole

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.AppDatabase
import com.metallic.chiaki.common.ManualHost
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.ext.toLiveData
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

class EditManualConsoleViewModel(val database: AppDatabase, manualHostId: Long?): ViewModel()
{
	/**
	 * One choice per console: a console registered with several accounts would show identical
	 * choices, and which one is linked does not matter, the card uses the registration for the
	 * account in use. The link only says which console it is.
	 */
	val registeredHosts = database.registeredHostDao().getAll()
			.map { hosts -> hosts.distinctBy { it.serverMac } }
			.observeOn(AndroidSchedulers.mainThread())
			.doOnNext { hosts ->
				val selectedHost = selectedRegisteredHost.value
				if(selectedHost != null)
					selectedRegisteredHost.value = hosts.firstOrNull { it.serverMac == selectedHost.serverMac }
			}
			.map { listOf(null) + it }
			.toLiveData()

	val existingHost: LiveData<ManualHost>? =
		if(manualHostId != null)
			database.manualHostDao()
				.getByIdWithRegisteredHost(manualHostId)
				.toFlowable()
				.doOnError {
					Log.e("EditManualConsole", "Failed to fetch existing manual host", it)
				}
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.doOnNext { hosts ->
					// the console's choice in the list (the link may point to another account's registration)
					val linked = hosts.registeredHost
					selectedRegisteredHost.value = linked?.let { l ->
						registeredHosts.value?.firstOrNull { it?.serverMac == l.serverMac } } ?: linked
				}
				.map { hosts -> hosts.manualHost }
				.toLiveData()
		else
			null

	var selectedRegisteredHost = MutableLiveData<RegisteredHost?>(null)

	fun saveHost(host: String) =
		database.manualHostDao()
			.let {
				val registeredHost = selectedRegisteredHost.value?.id
				val existingHost = existingHost?.value
				if(existingHost != null)
					it.update(ManualHost(id = existingHost.id, host = host, registeredHost = registeredHost))
				else
					it.insert(ManualHost(host = host, registeredHost = registeredHost))
			}
			.subscribeOn(Schedulers.io())
}