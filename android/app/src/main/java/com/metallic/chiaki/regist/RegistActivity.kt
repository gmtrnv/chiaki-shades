// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PsnAccounts
import com.metallic.chiaki.common.ext.RevealActivity
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.lang.IllegalArgumentException

/**
 * Registering a console: 1 the PlayStation account (signed in once in the browser and kept, see
 * [PsnLoginActivity]), 2 the console (found on the network or typed), 3 the PIN, last because it
 * expires. Under "Advanced": the console version and the Account ID typed by hand (the old way).
 */
class RegistActivity: AppCompatActivity(), RevealActivity
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"

		private const val PIN_LENGTH = 8
		private const val BROADCAST_ADDRESS = "255.255.255.255"

		private const val REQUEST_REGIST = 1
	}

	private lateinit var viewModel: RegistViewModel
	private lateinit var binding: ActivityRegistBinding

	override val revealWindow: Window get() = window
	override val revealIntent: Intent get() = intent
	override val revealRootLayout: View get() = binding.rootLayout

	private val accountsListener: () -> Unit = { updateAccount() }
	/** What the console list shows now, to rebuild it only when it changes (discovery answers often) */
	private var shownConsoles: List<Triple<String, String?, DiscoveryHost.State?>> = listOf()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistBinding.inflate(layoutInflater)
		setContentView(binding.root)
		handleReveal()

		viewModel = ViewModelProvider(this).get(RegistViewModel::class.java)
		// only a sign-in started from this screen may close it
		PsnLoginActivity.consolesAdopted = false

		// 1 · account
		binding.psnLoginButton.setOnClickListener { startActivity(Intent(this, PsnLoginActivity::class.java)) }
		binding.accountChangeButton.setOnClickListener { AccountSheet.show(this) }
		PsnAccounts.addListener(accountsListener)

		// 2 · console: a tapped card brings its address; otherwise the one found, if only one
		if(savedInstanceState == null)
		{
			val host = intent.getStringExtra(EXTRA_HOST)
			binding.hostEditText.setText(host ?: BROADCAST_ADDRESS)
			binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)
			if(host != null)
				viewModel.selectedHost.value = host
		}
		binding.manualHostRadioButton.setOnClickListener { viewModel.selectedHost.value = null; manualChosen = true; updateConsoleSelection() }
		viewModel.discoveredHosts.observe(this, Observer { updateConsoles(it) })
		viewModel.selectedHost.observe(this, Observer { updateConsoleSelection() })

		// 3 · PIN
		binding.registButton.setOnClickListener { doRegist() }

		// Advanced
		binding.advancedButton.setOnClickListener { viewModel.advancedOpen.value = viewModel.advancedOpen.value != true }
		viewModel.advancedOpen.observe(this, Observer { open ->
			binding.advancedGroup.visibility = if(open) View.VISIBLE else View.GONE
			binding.advancedButton.setIconResource(if(open) R.drawable.ic_arrow_drop_up else R.drawable.ic_arrow_drop_down)
		})

		binding.ps4VersionRadioGroup.check(when(viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5) {
			RegistViewModel.ConsoleVersion.PS5 -> R.id.ps5RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> R.id.ps4VersionGE8RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> R.id.ps4VersionGE7RadioButton
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.id.ps4VersionLT7RadioButton
		})
		binding.ps4VersionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			viewModel.ps4Version.value = when(checkedId)
			{
				R.id.ps5RadioButton -> RegistViewModel.ConsoleVersion.PS5
				R.id.ps4VersionGE8RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_8
				R.id.ps4VersionGE7RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_7
				R.id.ps4VersionLT7RadioButton -> RegistViewModel.ConsoleVersion.PS4_LT_7
				else -> RegistViewModel.ConsoleVersion.PS5
			}
		}
		viewModel.ps4Version.observe(this, Observer {
			val lt7 = it == RegistViewModel.ConsoleVersion.PS4_LT_7
			binding.psnIdTextInputLayout.hint = getString(if(lt7) R.string.hint_regist_psn_online_id else R.string.hint_regist_psn_account_id)
			binding.psnIdHelpTextView.setText(if(lt7) R.string.regist_online_id_help else R.string.regist_manual_id_help)
			binding.pinHelpBeforeTextView.setText(if(it.isPS5) R.string.regist_pin_now_ps5 else R.string.regist_pin_now_ps4)
			binding.pinHelpNavigationTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_navigation else R.string.regist_pin_instructions_ps4_navigation)
			// a PS4 older than 7 is registered with the online ID: open Advanced, where it is typed
			if(lt7)
				viewModel.advancedOpen.value = true
			updateAccount()
		})
		updateAccount()
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		PsnAccounts.removeListener(accountsListener)
	}

	override fun onResume()
	{
		super.onResume()
		// coming back from signing in: if the old consoles were this account's, they are registered
		// already and there is nothing to do here
		if(PsnLoginActivity.consolesAdopted)
		{
			PsnLoginActivity.consolesAdopted = false
			finish()
			return
		}
		updateAccount()
	}

	// --- 1 · account ---

	private fun updateAccount()
	{
		val accounts = PsnAccounts(this)
		val account = accounts.active
		binding.accountStatusTextView.text =
			if(account != null) getString(R.string.regist_psn_account_ready, account.name)
			else getString(R.string.regist_account_none)
		binding.psnLoginButton.visibility = if(account == null) View.VISIBLE else View.GONE
		binding.accountChangeButton.visibility = if(account != null) View.VISIBLE else View.GONE
	}

	// --- 2 · console ---

	/** The user chose "type the address": a console found later does not take its place */
	private var manualChosen = false

	private fun updateConsoles(hosts: List<DiscoveryHost>)
	{
		val consoles = hosts.filter { it.hostAddr != null }
		val shown = consoles.map { Triple(it.hostAddr!!, it.hostName, it.state) }
		if(shown != shownConsoles)
		{
			shownConsoles = shown
			binding.consoleRadioGroup.removeAllViews()
			for(host in consoles)
			{
				val address = host.hostAddr!!
				val radio = MaterialRadioButton(this)
				val state = when(host.state)
				{
					DiscoveryHost.State.STANDBY -> getString(R.string.regist_console_standby)
					DiscoveryHost.State.READY -> getString(R.string.regist_console_ready)
					else -> ""
				}
				radio.text = listOfNotNull(host.hostName ?: address, address.takeIf { host.hostName != null }, state.ifEmpty { null })
					.joinToString(" · ")
				radio.tag = address
				radio.setOnClickListener {
					manualChosen = false
					viewModel.selectedHost.value = address
					// a PS4 found on the network: the usual PS4 version (changeable under Advanced)
					val version = viewModel.ps4Version.value
					if(!host.isPS5 && version == RegistViewModel.ConsoleVersion.PS5)
						binding.ps4VersionRadioGroup.check(R.id.ps4VersionGE8RadioButton)
					else if(host.isPS5 && version != RegistViewModel.ConsoleVersion.PS5)
						binding.ps4VersionRadioGroup.check(R.id.ps5RadioButton)
				}
				binding.consoleRadioGroup.addView(radio)
			}
		}
		binding.consoleSearchTextView.setText(if(consoles.isEmpty()) R.string.regist_console_searching else R.string.regist_console_pick)
		// only one console around and nothing chosen yet: that one
		if(viewModel.selectedHost.value == null && !manualChosen && consoles.size == 1)
			consoles[0].hostAddr?.let { address -> binding.consoleRadioGroup.findViewWithTag<View>(address)?.performClick() }
		updateConsoleSelection()
	}

	private fun updateConsoleSelection()
	{
		val selected = viewModel.selectedHost.value
		val inList = selected != null && shownConsoles.any { it.first == selected }
		for(i in 0 until binding.consoleRadioGroup.childCount)
		{
			val radio = binding.consoleRadioGroup.getChildAt(i) as MaterialRadioButton
			radio.isChecked = inList && radio.tag == selected
		}
		// a console that is not (yet) in the list, e.g. a card tapped away from home: typed by hand
		val manual = !inList
		binding.manualHostRadioButton.isChecked = manual
		binding.manualHostGroup.visibility = if(manual) View.VISIBLE else View.GONE
		if(manual && selected != null && binding.hostEditText.text.toString() == BROADCAST_ADDRESS)
		{
			binding.hostEditText.setText(selected)
			binding.broadcastCheckBox.isChecked = false
		}
	}

	// --- register ---

	private fun doRegist()
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5
		val lt7 = ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7

		val selected = viewModel.selectedHost.value?.takeIf { address -> shownConsoles.any { it.first == address } }
		val host = selected ?: binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = if(selected != null) false else binding.broadcastCheckBox.isChecked

		// the Account ID typed under Advanced wins; otherwise the account in use
		val typed = binding.psnIdEditText.text.toString().trim()
		val account = PsnAccounts(this).active
		val psnOnlineId: String? = if(lt7) typed else null
		val psnAccountId: ByteArray? =
			if(lt7)
				null
			else if(typed.isNotEmpty())
				try { Base64.decode(typed, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
			else
				account?.let { try { Base64.decode(it.accountId, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null } }
		val psnIdValid =
			if(lt7) !psnOnlineId.isNullOrEmpty()
			else psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE

		val pin = binding.pinEditText.text.toString()
		val pinValid = pin.length == PIN_LENGTH

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid && (lt7 || typed.isNotEmpty()))
				getString(if(lt7) R.string.regist_psn_online_id_invalid else R.string.regist_psn_account_id_invalid)
			else
				null
		binding.pinEditText.error = if(!pinValid) getString(R.string.regist_pin_invalid, PIN_LENGTH) else null

		if(!psnIdValid && !lt7 && typed.isEmpty())
		{
			// no account yet: step 1 first
			MaterialAlertDialogBuilder(this)
				.setMessage(R.string.regist_account_missing)
				.setPositiveButton(R.string.regist_psn_login) { _, _ -> startActivity(Intent(this, PsnLoginActivity::class.java)) }
				.setNegativeButton(R.string.action_regist_discard, null)
				.show()
			return
		}
		if(!hostValid || !psnIdValid || !pinValid)
			return

		// an Account ID typed by hand becomes a known account too (it can be renamed in the sheet)
		if(!lt7 && typed.isNotEmpty() && psnAccountId != null)
			PsnAccounts(this).ensure(Base64.encodeToString(psnAccountId, Base64.NO_WRAP), getString(R.string.psn_account_default_name))

		val target = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS5 -> Target.PS5_1
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> Target.PS4_10
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> Target.PS4_9
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> Target.PS4_8
		}

		val registInfo = RegistInfo(target, host, broadcast, psnOnlineId, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			startActivityForResult(it, REQUEST_REGIST)
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_REGIST && resultCode == RESULT_OK)
			finish()
	}
}
