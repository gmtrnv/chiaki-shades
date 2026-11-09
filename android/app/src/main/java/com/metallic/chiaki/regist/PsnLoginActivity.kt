// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PsnAccounts
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.databinding.ActivityPsnLoginBinding
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers

/**
 * Signing in with PlayStation, in the user's own browser (see [PsnAuth]): this screen opens the
 * Sony page and takes back the address it ends on, shared from the browser ("Share → this app"),
 * copied, or pasted by hand. It never shows Sony's page itself.
 */
class PsnLoginActivity: AppCompatActivity()
{
	private lateinit var binding: ActivityPsnLoginBinding
	private var work: Disposable? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityPsnLoginBinding.inflate(layoutInflater)
		setContentView(binding.root)

		binding.openSonyButton.setOnClickListener { openSony() }
		binding.shareLinkButton.setOnClickListener { shareLink() }
		binding.useCopiedButton.setOnClickListener { useClipboard() }
		binding.pasteContinueButton.setOnClickListener { useText(binding.pasteEditText.text?.toString() ?: "") }
		binding.closeButton.setOnClickListener { finish() }
		showBusy(false)

		handleShare(intent)
	}

	override fun onNewIntent(intent: Intent)
	{
		super.onNewIntent(intent)
		handleShare(intent)
	}

	override fun onDestroy()
	{
		super.onDestroy()
		work?.dispose()
	}

	/** The browser shared the address with us */
	private fun handleShare(intent: Intent?)
	{
		if(intent?.action != Intent.ACTION_SEND)
			return
		val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
		useText(text)
	}

	private fun openSony()
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PsnAuth.LOGIN_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
		}
		catch(e: ActivityNotFoundException)
		{
			showError(getString(R.string.psn_login_no_browser))
		}
	}

	/**
	 * Android's share menu with the address: copy it into a private tab (no app can open one by
	 * itself), another browser, another device…
	 */
	private fun shareLink()
	{
		val send = Intent(Intent.ACTION_SEND).also {
			it.type = "text/plain"
			it.putExtra(Intent.EXTRA_TEXT, PsnAuth.LOGIN_URL)
		}
		startActivity(Intent.createChooser(send, getString(R.string.psn_login_share_link)).also {
			// this app takes shared links too (the "redirect" one): not this one
			it.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(this, PsnLoginActivity::class.java)))
		})
	}

	/** Reads the clipboard only now, when the user asked for it */
	private fun useClipboard()
	{
		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
		if(text.isNullOrBlank() || !PsnAuth.looksLikeRedirect(text))
		{
			showError(getString(R.string.psn_login_clipboard_empty))
			return
		}
		useText(text)
	}

	private fun useText(text: String)
	{
		if(work?.isDisposed == false)
			return
		val code = PsnAuth.extractCode(text)
		if(code == null)
		{
			showError(getString(R.string.psn_login_no_code))
			return
		}
		showBusy(true)
		work = Single.fromCallable { PsnAuth.fetchAccount(code) }
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe({ account ->
				showBusy(false)
				// the link holds a one-time code: no use keeping it on screen
				binding.pasteEditText.setText("")
				if(account.onlineId != null)
					saved(account.accountId, account.onlineId)
				else
					askName(account.accountId)
			}, { e ->
				showBusy(false)
				val reason = (e as? PsnAuth.AuthException)?.reason
				showError(getString(when(reason)
				{
					PsnAuth.Reason.CODE_REJECTED -> R.string.psn_login_code_rejected
					PsnAuth.Reason.BAD_ANSWER -> R.string.psn_login_bad_answer
					else -> R.string.psn_login_network
				}))
			})
	}

	/** Sony did not say the account's name: the user gives one ("Me", "Mum"…) */
	private fun askName(accountId: String)
	{
		val edit = EditText(this).also {
			it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			it.setSingleLine()
		}
		val frame = FrameLayout(this).also {
			val pad = (20 * resources.displayMetrics.density).toInt()
			it.setPadding(pad, pad / 2, pad, 0)
			it.addView(edit)
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.psn_account_name_title)
			.setMessage(R.string.psn_account_name_message)
			.setView(frame)
			.setCancelable(false)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				saved(accountId, edit.text.toString().trim().ifEmpty { getString(R.string.psn_account_default_name) })
			}
			.show()
	}

	private fun saved(accountId: String, name: String)
	{
		val accounts = PsnAccounts(this)
		val first = accounts.all.isEmpty()
		accounts.add(accountId, name)
		if(first)
			askAdoptOld(accountId, name)
		else
		{
			Toast.makeText(this, getString(R.string.psn_login_done, name), Toast.LENGTH_LONG).show()
			done()
		}
	}

	companion object
	{
		/**
		 * Set when the old consoles were given to the account just signed in: they are registered
		 * already, so the registration screen that opened this one closes too (it reads and clears it).
		 */
		var consolesAdopted = false
	}

	private fun done()
	{
		setResult(RESULT_OK)
		finish()
	}

	/**
	 * Consoles registered before accounts were kept do not say with which account. With the first
	 * account signed in, ask once whether they are its (the usual case); if not, they stay as they are.
	 */
	private fun askAdoptOld(accountId: String, name: String)
	{
		val dao = getDatabase(this).registeredHostDao()
		val ps4Lt7 = Target.PS4_8.value
		work = dao.countUnowned(ps4Lt7)
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe({ count ->
				if(count == 0)
				{
					Toast.makeText(this, getString(R.string.psn_login_done, name), Toast.LENGTH_LONG).show()
					done()
					return@subscribe
				}
				MaterialAlertDialogBuilder(this)
					.setMessage(resources.getQuantityString(R.plurals.psn_adopt_old_message, count, count, name))
					.setCancelable(false)
					.setPositiveButton(R.string.psn_adopt_old_yes) { _, _ ->
						dao.adoptUnowned(accountId, ps4Lt7)
							.subscribeOn(Schedulers.io())
							.observeOn(AndroidSchedulers.mainThread())
							.subscribe({
								// nothing left to register: back to the main screen
								Toast.makeText(this, resources.getQuantityString(R.plurals.psn_adopt_done, count, name, count), Toast.LENGTH_LONG).show()
								consolesAdopted = true
								done()
							}, { done() })
					}
					.setNegativeButton(R.string.psn_adopt_old_no) { _, _ ->
						Toast.makeText(this, getString(R.string.psn_login_done, name), Toast.LENGTH_LONG).show()
						done()
					}
					.show()
			}, { done() })
	}

	private fun showBusy(busy: Boolean)
	{
		binding.progress.visibility = if(busy) View.VISIBLE else View.GONE
		binding.statusTextView.visibility = if(busy) View.VISIBLE else View.GONE
		if(busy)
			binding.statusTextView.setText(R.string.psn_login_working)
		binding.openSonyButton.isEnabled = !busy
		binding.useCopiedButton.isEnabled = !busy
		binding.pasteContinueButton.isEnabled = !busy
	}

	private fun showError(message: String)
	{
		MaterialAlertDialogBuilder(this)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok) { _, _ -> }
			.show()
	}
}
