// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.GameplayToast

/**
 * The layout dialogs shared by "Try the touch pad" and the live editor in the stream (phase 13):
 * which controls are on, the named layouts (slots) and the reset that can keep a copy.
 */
object LayoutDialogs
{
	/** id in the layout's "hidden" set → text in the list */
	private fun controls(context: Context) = listOf(
		"lstick" to context.getString(R.string.console_pad_stick_left),
		"rstick" to context.getString(R.string.console_pad_stick_right),
		"dpad" to context.getString(R.string.console_test_group_dpad),
		"face" to context.getString(R.string.console_test_group_face),
		"l1" to "L1", "r1" to "R1", "l2" to "L2", "r2" to "R2",
		"l3" to "L3", "r3" to "R3",
		"ps" to "PS", "options" to "OPTIONS", "share" to "SHARE",
		"gyro" to context.getString(R.string.gyro_sec),
		"recenter" to context.getString(R.string.motion_recenter_control),
		ConsolePadLayout.TOUCHPAD to context.getString(R.string.console_layout_touchpad))

	/** which controls are on; each tick applies and saves at once */
	fun showControls(context: Context, pad: ConsolePadView)
	{
		pad.cancelAll()
		val items = controls(context)
		val hidden = pad.padLayout.hidden
		// the gyroscope button is greyed while the gyroscope stick is off: a tap says why instead of doing nothing
		fun greyed(id: String) = id in pad.padLayout.unavailable
		val adapter = object : android.widget.ArrayAdapter<String>(context, android.R.layout.simple_list_item_multiple_choice,
			items.map { it.second })
		{
			override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
				super.getView(position, convertView, parent).also {
					it.alpha = if(greyed(items[position].first)) 0.4f else 1f
					// explicit colours, on the dark panel below: this plain list took the black of the phone's theme
					(it as? android.widget.CheckedTextView)?.apply {
						setTextColor(0xFFFFFFFF.toInt())
						checkMarkTintList = android.content.res.ColorStateList.valueOf(0xFFD4AB55.toInt())
					}
				}
		}
		val dialog = MaterialAlertDialogBuilder(context)
			.setTitle(R.string.console_layout_controls)
			.setAdapter(adapter, null)
			.setPositiveButton(R.string.console_layout_close, null)
			.create()
		// a dark panel of our own, as in the button picker: the same look on any phone theme
		dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
			setColor(0xFF1E2024.toInt())
			cornerRadius = 20f * context.resources.displayMetrics.density
		})
		dialog.show()
		dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(0xFFD4AB55.toInt())
		dialog.listView.apply {
			choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
			items.forEachIndexed { i, item -> setItemChecked(i, item.first !in hidden) }
			setOnItemClickListener { _, _, which, _ ->
				val id = items[which].first
				if(greyed(id))
				{
					// nothing changes: it stays as it was, and says why. Always, also over the game: it answers a tap
					// in an open dialog (not an action's confirmation), and without it the tap would look broken
					setItemChecked(which, id !in hidden)
					Toast.makeText(context, if(pad.settings.gyroHardware) R.string.gyro_unavailable else R.string.gyro_no_hardware,
						Toast.LENGTH_SHORT).show()
					return@setOnItemClickListener
				}
				if(isItemChecked(which)) hidden.remove(id) else hidden.add(id)
				pad.layoutChanged()
			}
		}
	}

	private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

	/** a slot's name as shown: the built-in ones by their (translated) name */
	private fun displayName(context: Context, name: String) =
		BuiltinLayout.from(name)?.let { context.getString(it.nameRes) } ?: name

	/**
	 * The named layouts: first the three that come with the app (a tap loads one), then the user's
	 * (a tap shows load / overwrite / rename / delete); save the current one as a new slot.
	 */
	fun showSlots(context: Context, pad: ConsolePadView)
	{
		pad.cancelAll()
		val prefs = prefs(context)
		val slots = LayoutSlots.list(prefs)
		val current = LayoutSlots.current(prefs)
		val star = if(LayoutSlots.modified(prefs, pad.padLayout)) "*" else ""
		val title = if(current != null) context.getString(R.string.console_layout_slots_current, displayName(context, current) + star)
			else context.getString(R.string.console_layout_slots)
		val builder = MaterialAlertDialogBuilder(context)
			.setTitle(title)
			.setPositiveButton(R.string.console_layout_save_new) { _, _ ->
				askName(context, LayoutSlots.freeName(prefs, context.getString(R.string.console_layout_default_name))) { name ->
					saveSlot(context, pad, name)
				}
			}
			.setNegativeButton(R.string.console_layout_close, null)
		val builtins = BuiltinLayout.values()
		val names = builtins.map { "★ " + context.getString(it.nameRes) to it.id } + slots.map { it.name to it.name }
		builder.setItems(names.map { (label, id) -> if(id == current) "● $label$star" else label }.toTypedArray()) { _, which ->
			val id = names[which].second
			if(BuiltinLayout.from(id) != null)
				guardUnsaved(context, pad) {
					LayoutSlots.load(prefs, id)?.let {
						pad.applyLayout(it)
						GameplayToast.show(context, context.getString(R.string.console_layout_loaded, displayName(context, id)))
					}
				}
			else
				showSlotActions(context, pad, id)
		}
		builder.show()
	}

	private fun showSlotActions(context: Context, pad: ConsolePadView, name: String)
	{
		val prefs = prefs(context)
		val actions = arrayOf(
			context.getString(R.string.console_layout_load),
			context.getString(R.string.console_layout_overwrite),
			context.getString(R.string.console_layout_rename),
			context.getString(R.string.console_layout_delete))
		MaterialAlertDialogBuilder(context)
			.setTitle(name)
			.setItems(actions) { _, which ->
				when(which)
				{
					0 -> guardUnsaved(context, pad) {
						LayoutSlots.load(prefs, name)?.let {
							pad.applyLayout(it)
							GameplayToast.show(context, context.getString(R.string.console_layout_loaded, name))
						}
					}
					1 -> confirm(context, context.getString(R.string.console_layout_overwrite_confirm, name)) { saveSlot(context, pad, name) }
					2 -> askName(context, name) { to ->
						if(to != name && LayoutSlots.list(prefs).any { it.name == to })
							Toast.makeText(context, R.string.console_layout_name_taken, Toast.LENGTH_SHORT).show()
						else
							LayoutSlots.rename(prefs, name, to)
						showSlots(context, pad)
					}
					3 -> confirm(context, context.getString(R.string.console_layout_delete_confirm, name)) {
						LayoutSlots.delete(prefs, name)
						showSlots(context, pad)
					}
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> showSlots(context, pad) }
			.show()
	}

	/**
	 * Before loading another layout over an edited one (the "*"): save it (in the user's own slot,
	 * or as a new one) or discard the changes; Cancel does nothing. The built-in layouts cannot be
	 * overwritten, so they only offer "as new". Then [then].
	 */
	private fun guardUnsaved(context: Context, pad: ConsolePadView, then: () -> Unit)
	{
		val prefs = prefs(context)
		val current = LayoutSlots.current(prefs)
		if(current == null || !LayoutSlots.modified(prefs, pad.padLayout))
		{
			then()
			return
		}
		val own = current.takeIf { BuiltinLayout.from(it) == null }
		val actions = listOfNotNull(
			own?.let { context.getString(R.string.unsaved_save_in, it) to { saveSlot(context, pad, it); then() } },
			context.getString(R.string.unsaved_save_new) to {
				askName(context, LayoutSlots.freeName(prefs, context.getString(R.string.console_layout_default_name))) { name ->
					saveSlot(context, pad, name)
					then()
				}
			},
			context.getString(R.string.unsaved_discard) to then)
		MaterialAlertDialogBuilder(context)
			.setTitle(context.getString(R.string.unsaved_title, displayName(context, current)))
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun saveSlot(context: Context, pad: ConsolePadView, name: String)
	{
		LayoutSlots.save(prefs(context), name, pad.padLayout)
		GameplayToast.show(context, context.getString(R.string.console_layout_saved, name))
	}

	/**
	 * Reset: keep a copy in a new slot first, or just reset (what it always did). [after] runs once
	 * the layout is back to the defaults.
	 */
	fun confirmReset(context: Context, pad: ConsolePadView, after: () -> Unit = {})
	{
		pad.cancelAll()
		val prefs = prefs(context)
		val reset = {
			pad.resetLayout()
			LayoutSlots.clearCurrent(prefs)
			GameplayToast.show(context, R.string.console_test_reset_done)
			after()
		}
		MaterialAlertDialogBuilder(context)
			.setMessage(R.string.console_layout_reset_question)
			.setPositiveButton(R.string.console_layout_reset_keep) { _, _ ->
				askName(context, LayoutSlots.freeName(prefs, context.getString(R.string.console_layout_default_name))) { name ->
					LayoutSlots.save(prefs, name, pad.padLayout)
					reset()
				}
			}
			.setNeutralButton(R.string.console_layout_reset_discard) { _, _ -> reset() }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun confirm(context: Context, message: String, yes: () -> Unit)
	{
		MaterialAlertDialogBuilder(context)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ -> yes() }
			.show()
	}

	private fun askName(context: Context, suggested: String, done: (String) -> Unit)
	{
		val input = EditText(context).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			setText(suggested)
			selectAll()
		}
		val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, context.resources.displayMetrics).toInt()
		val box = FrameLayout(context).apply {
			setPadding(pad, pad / 2, pad, 0)
			addView(input)
		}
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.console_layout_name)
			.setView(box)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				// "#" starts the names of the built-in layouts
				val name = input.text.toString().trim().trimStart('#').trim()
				if(name.isNotEmpty()) done(name)
			}
			.show()
	}
}
