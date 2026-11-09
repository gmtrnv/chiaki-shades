// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.common.PsnAccounts
import com.metallic.chiaki.common.getDatabase
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers

/**
 * The PlayStation accounts on this phone, from the account pill (main screen) or the registration
 * screen: tap = use it (connecting and registering), ⋮ = rename / forget, "Add account" = sign in.
 */
object AccountSheet
{
	fun show(activity: Activity)
	{
		val accounts = PsnAccounts(activity)
		val dialog = BottomSheetDialog(activity)
		val view = activity.layoutInflater.inflate(R.layout.dialog_accounts, null)
		val list = view.findViewById<LinearLayout>(R.id.accountList)
		var counts: Map<String?, Int> = mapOf()
		var hosts: List<com.metallic.chiaki.common.RegisteredHost> = listOf()

		fun fill()
		{
			list.removeAllViews()
			val active = accounts.active
			for(account in accounts.all)
			{
				val row = activity.layoutInflater.inflate(R.layout.item_account, list, false)
				row.findViewById<RadioButton>(R.id.accountRadio).isChecked = account.accountId == active?.accountId
				row.findViewById<TextView>(R.id.accountName).text = account.name
				row.findViewById<View>(R.id.accountColor).backgroundTintList =
					android.content.res.ColorStateList.valueOf(account.colorValue)
				val count = counts[account.accountId] ?: 0
				row.findViewById<TextView>(R.id.accountConsoles).text =
					activity.resources.getQuantityString(R.plurals.accounts_consoles, count, count)
				row.setOnClickListener {
					if(account.accountId == accounts.active?.accountId)
						return@setOnClickListener
					confirmPausedGame(activity, hosts, keepFor = account.accountId) {
						accounts.setActive(account.accountId)
						fill()
					}
				}
				val menuButton = row.findViewById<View>(R.id.accountMenu)
				menuButton.setOnClickListener {
					val menu = PopupMenu(activity, menuButton)
					menu.menu.add(0, 1, 0, R.string.accounts_rename)
					menu.menu.add(0, 3, 1, R.string.accounts_color)
					menu.menu.add(0, 2, 2, R.string.accounts_forget)
					menu.setOnMenuItemClickListener { item ->
						when(item.itemId)
						{
							1 -> rename(activity, account) { fill() }
							3 -> pickColor(activity, account) { fill() }
							2 -> forget(activity, account, count, hosts) { fill() }
						}
						true
					}
					menu.show()
				}
				list.addView(row)
			}
		}

		// consoles registered with each account, for the rows
		val countsWork = getDatabase(activity).registeredHostDao().getAll().firstOrError()
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe({ all ->
				hosts = all
				counts = all.groupingBy { it.psnAccountId }.eachCount()
				fill()
			}, { })
		dialog.setOnDismissListener { countsWork.dispose() }

		fill()
		view.findViewById<View>(R.id.accountAdd).setOnClickListener {
			dialog.dismiss()
			activity.startActivity(Intent(activity, PsnLoginActivity::class.java))
		}
		view.findViewById<View>(R.id.accountDone).setOnClickListener { dialog.dismiss() }
		dialog.setContentView(view)
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.behavior.skipCollapsed = true
		dialog.show()
	}

	private fun rename(activity: Activity, account: PsnAccount, done: () -> Unit)
	{
		val edit = EditText(activity).also {
			it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			it.setSingleLine()
			it.setText(account.name)
			it.selectAll()
		}
		val frame = FrameLayout(activity).also {
			val pad = (20 * activity.resources.displayMetrics.density).toInt()
			it.setPadding(pad, pad / 2, pad, 0)
			it.addView(edit)
		}
		MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.psn_account_name_title)
			.setView(frame)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				PsnAccounts(activity).rename(account.accountId, edit.text.toString())
				done()
			}
			.setNegativeButton(R.string.action_regist_discard, null)
			.show()
	}

	/**
	 * A game paused in the background (quick reconnect) belongs to the account that connected: after
	 * switching to another one its card would be gone, so ask first and close it. [keepFor] = the
	 * account being switched to (no question if the paused game is already its own); null = always ask.
	 */
	private fun confirmPausedGame(activity: Activity, hosts: List<com.metallic.chiaki.common.RegisteredHost>,
		keepFor: String?, then: () -> Unit)
	{
		val kept = com.metallic.chiaki.session.KeptSession.info
		if(kept == null)
		{
			then()
			return
		}
		val host = hosts.firstOrNull { it.rpRegistKey.contentEquals(kept.connectInfo.registKey) }
		if(keepFor != null && host?.psnAccountId == keepFor)
		{
			then()
			return
		}
		val console = host?.serverNickname ?: kept.connectInfo.host
		MaterialAlertDialogBuilder(activity)
			.setMessage(activity.getString(R.string.accounts_paused_game_message, console))
			.setPositiveButton(R.string.accounts_paused_game_close) { _, _ ->
				com.metallic.chiaki.session.KeptSession.end()
				then()
			}
			.setNegativeButton(R.string.action_regist_discard, null)
			.show()
	}

	/** The account's color (the main screen's theme): one tap on a dot of the palette picks it */
	private fun pickColor(activity: Activity, account: PsnAccount, done: () -> Unit)
	{
		val density = activity.resources.displayMetrics.density
		val size = (40 * density).toInt()
		val gap = (8 * density).toInt()
		// two rows of four: fits a small landscape screen without scrolling
		val grid = LinearLayout(activity).also {
			it.orientation = LinearLayout.VERTICAL
			it.gravity = android.view.Gravity.CENTER_HORIZONTAL
			it.setPadding(gap * 2, gap * 2, gap * 2, 0)
		}
		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(activity.getString(R.string.accounts_color_title, account.name))
			.setView(grid)
			.setNegativeButton(R.string.action_regist_discard, null)
			.create()
		com.metallic.chiaki.common.AccountColors.values.forEachIndexed { index, color ->
			val dot = View(activity)
			dot.background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.account_dot)?.mutate()
			dot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
			// the current one wears a ring
			if(index == Math.floorMod(account.color, com.metallic.chiaki.common.AccountColors.values.size))
				dot.foreground = android.graphics.drawable.GradientDrawable().also {
					it.shape = android.graphics.drawable.GradientDrawable.OVAL
					it.setStroke((3 * density).toInt(), android.graphics.Color.WHITE)
				}
			dot.isClickable = true
			dot.isFocusable = true
			dot.contentDescription = activity.resources.getStringArray(R.array.account_color_names).getOrNull(index)
			dot.setOnClickListener {
				PsnAccounts(activity).setColor(account.accountId, index)
				dialog.dismiss()
				done()
			}
			if(index % 4 == 0)
				grid.addView(LinearLayout(activity).also {
					it.orientation = LinearLayout.HORIZONTAL
					it.gravity = android.view.Gravity.CENTER_HORIZONTAL
				})
			(grid.getChildAt(grid.childCount - 1) as LinearLayout).addView(dot,
				LinearLayout.LayoutParams(size, size).also { it.setMargins(gap / 2, gap / 2, gap / 2, gap / 2) })
		}
		dialog.show()
	}

	/**
	 * Forgetting an account also forgets, on this phone, the consoles registered with it (their keys
	 * would sign in as that user). The console keeps this device linked until it is removed there.
	 */
	private fun forget(activity: Activity, account: PsnAccount, consoles: Int,
		hosts: List<com.metallic.chiaki.common.RegisteredHost>, done: () -> Unit)
	{
		val message =
			if(consoles > 0)
				activity.resources.getQuantityString(R.plurals.accounts_forget_message_consoles, consoles, account.name, consoles)
			else
				activity.getString(R.string.accounts_forget_message, account.name)
		MaterialAlertDialogBuilder(activity)
			.setMessage(message)
			.setPositiveButton(R.string.accounts_forget) { _, _ ->
				// a game paused with this account's console would have no card left to go back to
				val kept = com.metallic.chiaki.session.KeptSession.info
				if(kept != null && hosts.any { it.psnAccountId == account.accountId && it.rpRegistKey.contentEquals(kept.connectInfo.registKey) })
					com.metallic.chiaki.session.KeptSession.end()
				PsnAccounts(activity).remove(account.accountId)
				getDatabase(activity).registeredHostDao().deleteByAccount(account.accountId)
					.subscribeOn(Schedulers.io())
					.observeOn(AndroidSchedulers.mainThread())
					.subscribe({ done() }, { done() })
			}
			.setNegativeButton(R.string.action_regist_discard, null)
			.show()
	}
}
