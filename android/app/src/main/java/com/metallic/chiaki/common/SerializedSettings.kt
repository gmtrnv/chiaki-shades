// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Activity
import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.room.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.DialogSettingsCategoriesBinding
import com.metallic.chiaki.lib.Target
import com.squareup.moshi.*
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Singles
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

@JsonClass(generateAdapter = true)
class SerializedRegisteredHost(
	@Json(name = "target") val target: Target,
	@Json(name = "ap_ssid") val apSsid: String?,
	@Json(name = "ap_bssid") val apBssid: String?,
	@Json(name = "ap_key") val apKey: String?,
	@Json(name = "ap_name") val apName: String?,
	@Json(name = "server_mac") val serverMac: MacAddress,
	@Json(name = "server_nickname") val serverNickname: String?,
	@Json(name = "rp_regist_key") val rpRegistKey: ByteArray,
	@Json(name = "rp_key_type") val rpKeyType: Int,
	@Json(name = "rp_key") val rpKey: ByteArray,
	// the PlayStation account it was registered with and its name; optional (older files, other chiakis)
	@Json(name = "psn_account_id") val psnAccountId: String? = null,
	@Json(name = "psn_account_name") val psnAccountName: String? = null
){
	constructor(registeredHost: RegisteredHost, accountName: String?) : this(
		registeredHost.target,
		registeredHost.apSsid,
		registeredHost.apBssid,
		registeredHost.apKey,
		registeredHost.apName,
		registeredHost.serverMac,
		registeredHost.serverNickname,
		registeredHost.rpRegistKey,
		registeredHost.rpKeyType,
		registeredHost.rpKey,
		registeredHost.psnAccountId,
		accountName
	)
}

@JsonClass(generateAdapter = true)
class SerializedManualHost(
	@Json(name = "host") val host: String,
	@Json(name = "server_mac") val serverMac: MacAddress?
)

@JsonClass(generateAdapter = true)
data class SerializedSettings(
	@Json(name = "registered_hosts") val registeredHosts: List<SerializedRegisteredHost>,
	@Json(name = "manual_hosts") val manualHosts: List<SerializedManualHost>,
	// our image settings (ImageSettings.export); optional, so older files still import
	@Json(name = "image") val image: Map<String, Any>? = null,
	// general app settings (Preferences.appSettingsBlock); optional too
	@Json(name = "app") val app: Map<String, Any>? = null
)
{
	companion object
	{
		/** Without [consoles] the host lists stay empty (still written, so other chiaki versions can read the file). */
		fun fromDatabase(db: AppDatabase, consoles: Boolean, accountNames: Map<String, String>, image: Map<String, Any>?, app: Map<String, Any>?): Single<SerializedSettings> =
			if(!consoles)
				Single.just(SerializedSettings(listOf(), listOf(), image, app))
			else Singles.zip(
			db.registeredHostDao().getAll().firstOrError(),
			db.manualHostDao().getAll().firstOrError()
		) { registeredHosts, manualHosts ->
			SerializedSettings(
				registeredHosts.map { SerializedRegisteredHost(it, it.psnAccountId?.let { id -> accountNames[id] }) },
				manualHosts.map {  manualHost ->
					SerializedManualHost(
						manualHost.host,
						manualHost.registeredHost?.let { registeredHostId ->
							registeredHosts.firstOrNull { it.id == registeredHostId }
						}?.serverMac
					)
				},
				image,
				app)
		}
	}
}

private class ByteArrayJsonAdapter
{
	@ToJson fun toJson(byteArray: ByteArray) = Base64.encodeToString(byteArray, Base64.NO_WRAP)
	@FromJson fun fromJson(string: String) = Base64.decode(string, Base64.DEFAULT)
}

private fun moshi() =
	Moshi.Builder()
	.add(MacAddressJsonAdapter())
	.add(ByteArrayJsonAdapter())
	.build()

private fun Moshi.serializedSettingsAdapter() =
	adapter(SerializedSettings::class.java)
	.serializeNulls()

private const val KEY_FORMAT = "format"
private const val FORMAT = "chiaki-settings"
private const val KEY_VERSION = "version"
private const val VERSION = 2
private const val KEY_SETTINGS = "settings"

/** What goes into the export file, one flag per block. Consoles carry their secret Remote Play keys. */
data class ExportSelection(val app: Boolean, val image: Boolean, val consoles: Boolean)
{
	val any get() = app || image || consoles
}

fun exportSettings(db: AppDatabase, preferences: Preferences, selection: ExportSelection, accountNames: Map<String, String> = mapOf()) = SerializedSettings.fromDatabase(db,
		selection.consoles, accountNames,
		if(selection.image) ImageSettings.export(preferences) else null,
		if(selection.app) preferences.appSettingsBlock.export(preferences.sharedPreferences) else null)
	.subscribeOn(Schedulers.io())
	.map {
		val buffer = Buffer()
		val writer = JsonWriter.of(buffer)
		val adapter = moshi().serializedSettingsAdapter()
		writer.indent = "  "
		writer.
			beginObject()
			.name(KEY_FORMAT).value(FORMAT)
			.name(KEY_VERSION).value(VERSION)
		writer.name(KEY_SETTINGS)
		adapter.toJson(writer, it)
		writer.endObject()
		buffer.readUtf8()
	}

/**
 * Asks what to export (app settings and image on, consoles off: they carry the keys that give
 * access to the console) and then shares the file.
 */
fun exportSettingsWithDialog(activity: Activity, disposable: CompositeDisposable)
{
	val binding = DialogSettingsCategoriesBinding.inflate(activity.layoutInflater)
	binding.appCheckBox.isChecked = true
	binding.imageCheckBox.isChecked = true
	binding.consolesCheckBox.isChecked = false
	binding.consolesWarningTextView.setText(R.string.export_consoles_warning)
	fun selection() = ExportSelection(binding.appCheckBox.isChecked, binding.imageCheckBox.isChecked, binding.consolesCheckBox.isChecked)

	val dialog = MaterialAlertDialogBuilder(activity)
		.setTitle(R.string.alert_title_export)
		.setView(binding.root)
		.setNegativeButton(android.R.string.cancel, null)
		.setPositiveButton(R.string.action_export) { _, _ ->
			disposable.clear()
			exportAndShareSettings(activity, selection()).addTo(disposable)
		}
		.show()

	fun update()
	{
		binding.consolesWarningTextView.isVisible = binding.consolesCheckBox.isChecked
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = selection().any
	}
	listOf(binding.appCheckBox, binding.imageCheckBox, binding.consolesCheckBox).forEach {
		it.setOnCheckedChangeListener { _, _ -> update() }
	}
	update()
}

private fun exportAndShareSettings(activity: Activity, selection: ExportSelection): Disposable
{
	val db = getDatabase(activity)
	val dir = File(activity.cacheDir, "export_settings")
	dir.mkdirs()
	val file = File(dir, "chiaki-settings.json")
	return exportSettings(db, Preferences(activity), selection, PsnAccounts(activity).all.associate { it.accountId to it.name })
		.map {
			file.writeText(it, Charsets.UTF_8)
			file
		}
		.observeOn(AndroidSchedulers.mainThread())
		.subscribe { _ ->
			val uri = FileProvider.getUriForFile(activity, fileProviderAuthority, file)
			Intent(Intent.ACTION_SEND).also {
				it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				it.type = "application/json"
				it.putExtra(Intent.EXTRA_STREAM, uri)
				it.clipData = ClipData.newRawUri("", uri)
				activity.startActivity(Intent.createChooser(it, activity.getString(R.string.action_export_settings)))
			}
		}
}

fun importSettingsFromUri(activity: Activity, uri: Uri, disposable: CompositeDisposable)
{
	fun loadFail(msg: String)
	{
		MaterialAlertDialogBuilder(activity)
			.setMessage(activity.getString(R.string.alert_message_import_failed, msg))
			.setPositiveButton(R.string.action_import_failed_ack) { _, _ -> }
			.create()
			.show()
	}

	try
	{
		val inputStream = activity.contentResolver.openInputStream(uri) ?: throw IOException()
		val buffer = inputStream.source().buffer()
		val reader = JsonReader.of(buffer)
		val adapter = moshi().serializedSettingsAdapter()

		var format: String? = null
		var version: Int? = null
		var settingsValue: Any? = null

		reader.beginObject()
		while(reader.hasNext())
		{
			when(reader.nextName())
			{
				KEY_FORMAT -> format = reader.nextString()
				KEY_VERSION -> version = reader.nextInt()
				KEY_SETTINGS -> settingsValue = reader.readJsonValue()
			}
		}
		reader.endObject()

		if(format == null || version == null || settingsValue == null)
			throw IOException("Missing format, version or settings from JSON")
		if(format != FORMAT)
			throw IOException("Value of format is invalid")
		if(version != VERSION) // Add migrations here when necessary
			throw IOException("Value of version is invalid")

		val settings = adapter.fromJsonValue(settingsValue) ?: throw JsonDataException("Failed to parse Settings JSON")
		showImportDialog(activity, settings, disposable) ?: loadFail(activity.getString(R.string.alert_message_import_empty))
	}
	catch(e: IOException)
	{
		android.util.Log.w("SerializedSettings", "Import failed", e)
		loadFail(e.message ?: "")
	}
	catch(e: JsonDataException)
	{
		android.util.Log.w("SerializedSettings", "Import failed", e)
		loadFail(e.message ?: "")
	}
}

/**
 * Asks which blocks of the file to apply: only the ones it contains are shown, all checked.
 * Returns null (no dialog) when the file has nothing to import.
 */
private fun showImportDialog(activity: Activity, settings: SerializedSettings, disposable: CompositeDisposable): Unit?
{
	val hasApp = !settings.app.isNullOrEmpty()
	val hasImage = !settings.image.isNullOrEmpty()
	val hasConsoles = settings.registeredHosts.isNotEmpty() || settings.manualHosts.isNotEmpty()
	if(!hasApp && !hasImage && !hasConsoles)
		return null

	val binding = DialogSettingsCategoriesBinding.inflate(activity.layoutInflater)
	binding.appCheckBox.isVisible = hasApp
	binding.imageCheckBox.isVisible = hasImage
	binding.consolesCheckBox.isVisible = hasConsoles
	listOf(binding.appCheckBox, binding.imageCheckBox, binding.consolesCheckBox).forEach { it.isChecked = it.isVisible }
	if(hasConsoles)
	{
		binding.consolesDetailsTextView.text =
			(settings.registeredHosts.map { "${it.serverNickname ?: "?"} / ${it.serverMac}" } +
				settings.manualHosts.map { "${it.host} / ${it.serverMac ?: "-"}" })
				.joinToString("\n") { "• $it" }
		binding.consolesDetailsTextView.isVisible = true
		// the keys are only in the registered consoles; manual entries are just addresses
		if(settings.registeredHosts.isNotEmpty())
		{
			binding.consolesWarningTextView.setText(R.string.import_consoles_warning)
			binding.consolesWarningTextView.isVisible = true
		}
	}
	binding.noteTextView.setText(R.string.import_replace_note)
	binding.noteTextView.isVisible = true

	val dialog = MaterialAlertDialogBuilder(activity)
		.setTitle(R.string.alert_title_import)
		.setView(binding.root)
		.setNegativeButton(R.string.action_import_cancel, null)
		.setPositiveButton(R.string.action_import_import) { _, _ ->
			val preferences = Preferences(activity)
			if(binding.imageCheckBox.isChecked)
				settings.image?.let { ImageSettings.import(preferences, it) }
			if(binding.appCheckBox.isChecked)
				settings.app?.let { preferences.appSettingsBlock.import(preferences.sharedPreferences, it) }
			if(binding.consolesCheckBox.isChecked)
			{
				// the accounts the consoles were registered with come along (name included)
				val accounts = PsnAccounts(activity)
				settings.registeredHosts.forEach { host -> host.psnAccountId?.let { accounts.ensure(it, host.psnAccountName) } }
				getDatabase(activity).importDao()
					.importCompletable(settings)
					.subscribeOn(Schedulers.io())
					.observeOn(AndroidSchedulers.mainThread())
					.subscribe()
					.addTo(disposable)
			}
		}
		.show()

	val checkBoxes = listOf(binding.appCheckBox, binding.imageCheckBox, binding.consolesCheckBox)
	fun update()
	{
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = checkBoxes.any { it.isChecked }
	}
	checkBoxes.forEach { it.setOnCheckedChangeListener { _, _ -> update() } }
	update()
	return Unit
}

@Dao
abstract class ImportDao
{
	@Insert
	abstract fun insertRegisteredHosts(hosts: List<RegisteredHost>)

	@Update
	abstract fun updateRegisteredHosts(hosts: List<RegisteredHost>)

	@Insert
	abstract fun insertManualHosts(hosts: List<ManualHost>)

	@Query("SELECT host FROM manual_host")
	abstract fun manualHostAddresses(): List<String>

	class IdWithMac(val id: Long, val mac: MacAddress, val account: String?)

	@Query("SELECT id, server_mac AS mac, psn_account_id AS account FROM registered_host WHERE server_mac IN (:macs)")
	abstract fun registeredHostsByMac(macs: List<MacAddress>): List<IdWithMac>

	/**
	 * Consoles already here (same MAC and same account) are updated in place, keeping their id so
	 * manual entries stay linked; manual entries with an address already here are skipped. Nothing
	 * is duplicated.
	 */
	@Transaction
	fun import(settings: SerializedSettings)
	{
		val existingIds =
			if(settings.registeredHosts.isNotEmpty())
				registeredHostsByMac(settings.registeredHosts.map { it.serverMac })
			else
				listOf()
		val (updated, added) = settings.registeredHosts.map {
				RegisteredHost(
					id = existingIds.firstOrNull { existing -> existing.mac == it.serverMac && existing.account == it.psnAccountId }?.id ?: 0,
					target = it.target,
					apSsid = it.apSsid,
					apBssid = it.apBssid,
					apKey = it.apKey,
					apName = it.apName,
					serverMac = it.serverMac,
					serverNickname = it.serverNickname,
					rpRegistKey = it.rpRegistKey,
					rpKeyType = it.rpKeyType,
					rpKey = it.rpKey,
					psnAccountId = it.psnAccountId
				)
		}.partition { it.id != 0L }
		updateRegisteredHosts(updated)
		insertRegisteredHosts(added)

		val macs = settings.manualHosts.mapNotNull { it.serverMac }
		val idMacs =
			if(macs.isNotEmpty())
				registeredHostsByMac(macs)
			else
				listOf()

		val addresses = manualHostAddresses().toSet()
		insertManualHosts(
			settings.manualHosts
				.filter { it.host !in addresses }
				.distinctBy { it.host }
				.map {
					ManualHost(
						host = it.host,
						registeredHost = idMacs.firstOrNull { regHost -> regHost.mac == it.serverMac }?.id
					)
				})
	}
}

private fun ImportDao.importCompletable(settings: SerializedSettings) = Completable.fromCallable { import(settings) }
