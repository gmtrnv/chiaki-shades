// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.KeyBinding
import com.metallic.chiaki.common.MappingSlots
import com.metallic.chiaki.common.PadMapping
import com.metallic.chiaki.common.PadTarget
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.session.StreamInput

/**
 * Settings → "Physical controller": a drawing that shows live what the console would get (the same
 * StreamInput as the stream, so held keys and analog triggers behave the same), the console's buttons
 * in groups with their keys, and the dialog that captures a key. Replaces the fork's list of 13 entries.
 */
class PadMappingActivity: AppCompatActivity()
{
	companion object
	{
		private const val GOLD = 0xFFD4AB55.toInt()
		private const val BG = 0xFF14161A.toInt()
		private const val CARD = 0xFF1E2024.toInt()
		private const val GREY = 0xFF9A9A9A.toInt()

		val groups = listOf(
			R.string.pad_group_face to listOf(PadTarget.CROSS, PadTarget.CIRCLE, PadTarget.SQUARE, PadTarget.TRIANGLE),
			R.string.pad_group_dpad to listOf(PadTarget.DPAD_UP, PadTarget.DPAD_DOWN, PadTarget.DPAD_LEFT, PadTarget.DPAD_RIGHT),
			R.string.pad_group_shoulders to listOf(PadTarget.L1, PadTarget.R1, PadTarget.L2, PadTarget.R2),
			R.string.pad_group_sticks to listOf(PadTarget.L3, PadTarget.R3),
			R.string.pad_group_center to listOf(PadTarget.OPTIONS, PadTarget.SHARE, PadTarget.TOUCHPAD, PadTarget.PS))

		fun targetName(context: Context, target: PadTarget) = when(target)
		{
			PadTarget.CROSS -> context.getString(R.string.pad_target_cross)
			PadTarget.CIRCLE -> context.getString(R.string.pad_target_circle)
			PadTarget.SQUARE -> context.getString(R.string.pad_target_square)
			PadTarget.TRIANGLE -> context.getString(R.string.pad_target_triangle)
			PadTarget.DPAD_UP -> context.getString(R.string.pad_target_dpad_up)
			PadTarget.DPAD_DOWN -> context.getString(R.string.pad_target_dpad_down)
			PadTarget.DPAD_LEFT -> context.getString(R.string.pad_target_dpad_left)
			PadTarget.DPAD_RIGHT -> context.getString(R.string.pad_target_dpad_right)
			PadTarget.L1 -> "L1"
			PadTarget.R1 -> "R1"
			PadTarget.L2 -> "L2"
			PadTarget.R2 -> "R2"
			PadTarget.L3 -> context.getString(R.string.pad_target_l3)
			PadTarget.R3 -> context.getString(R.string.pad_target_r3)
			PadTarget.OPTIONS -> context.getString(R.string.pad_target_options)
			PadTarget.SHARE -> context.getString(R.string.pad_target_share)
			PadTarget.TOUCHPAD -> context.getString(R.string.pad_target_touchpad)
			PadTarget.PS -> context.getString(R.string.pad_target_ps)
		}

		/** a key's name as its controller would print it: "A", "Select", "Volume +"… */
		fun keyName(context: Context, keyCode: Int): String = when(keyCode)
		{
			KeyEvent.KEYCODE_VOLUME_UP -> context.getString(R.string.key_name_volume_up)
			KeyEvent.KEYCODE_VOLUME_DOWN -> context.getString(R.string.key_name_volume_down)
			KeyEvent.KEYCODE_BACK -> context.getString(R.string.key_name_back)
			KeyEvent.KEYCODE_BUTTON_MODE -> context.getString(R.string.key_name_mode)
			KeyEvent.KEYCODE_BUTTON_THUMBL -> context.getString(R.string.key_name_thumbl)
			KeyEvent.KEYCODE_BUTTON_THUMBR -> context.getString(R.string.key_name_thumbr)
			KeyEvent.KEYCODE_DPAD_UP -> context.getString(R.string.key_name_dpad, "↑")
			KeyEvent.KEYCODE_DPAD_DOWN -> context.getString(R.string.key_name_dpad, "↓")
			KeyEvent.KEYCODE_DPAD_LEFT -> context.getString(R.string.key_name_dpad, "←")
			KeyEvent.KEYCODE_DPAD_RIGHT -> context.getString(R.string.key_name_dpad, "→")
			in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ->
				context.getString(R.string.key_name_button, keyCode - KeyEvent.KEYCODE_BUTTON_1 + 1)
			else -> KeyEvent.keyCodeToString(keyCode)
				.removePrefix("KEYCODE_").removePrefix("BUTTON_")
				.split('_').joinToString(" ") { w -> if(w.length <= 2) w else w.lowercase().replaceFirstChar { it.uppercase() } }
		}

		fun bindingName(context: Context, b: KeyBinding) =
			if(b.hold) context.getString(R.string.pad_key_held, keyName(context, b.keyCode)) else keyName(context, b.keyCode)
	}

	private lateinit var preferences: Preferences
	private lateinit var input: StreamInput
	private lateinit var mapping: PadMapping
	private lateinit var tester: PadTesterView
	private lateinit var deviceText: TextView
	private val rowValues = HashMap<PadTarget, TextView>()
	/** the last controller seen and whether its triggers are analog */
	private var lastDevice: InputDevice? = null

	private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		preferences = Preferences(this)
		mapping = preferences.padMapping
		input = StreamInput(this, preferences)
		input.controllerStateChangedCallback = { tester.state = it }
		window.decorView.setBackgroundColor(BG)
		build()
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		build()
	}

	override fun onPause()
	{
		super.onPause()
		input.releaseAll()
	}

	private fun build()
	{
		val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
		val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

		// header: back arrow and title
		root.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			setPadding(dp(4f), dp(4f), dp(16f), dp(4f))
			addView(ImageButton(this@PadMappingActivity).apply {
				setImageResource(R.drawable.ic_arrow_back)
				background = null
				setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
				setOnClickListener { finish() }
			})
			addView(TextView(this@PadMappingActivity).apply {
				text = getString(R.string.pad_mapping_title)
				textSize = 20f
				setTextColor(0xFFFFFFFF.toInt())
			})
		})

		// rebuilt on rotation: the drawing keeps what it showed
		val oldState = if(this::tester.isInitialized) tester.state else null
		tester = PadTesterView(this).apply {
			onPick = { showAssign(it) }
			oldState?.let { state = it }
		}
		deviceText = TextView(this).apply {
			textSize = 13f
			setTextColor(GOLD)
			gravity = Gravity.CENTER
		}
		updateDevice()
		val hint = TextView(this).apply {
			text = getString(R.string.pad_mapping_hint)
			textSize = 12f
			setTextColor(GREY)
			gravity = Gravity.CENTER
			setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
		}
		val testerColumn = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			addView(tester, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
			addView(deviceText)
			addView(hint)
		}

		val list = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(16f), 0, dp(16f), dp(16f))
		}
		rowValues.clear()
		profileButton = textButton("") { showProfiles() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
		list.addView(profileButton)
		updateProfile()
		for((title, targets) in groups)
		{
			list.addView(TextView(this).apply {
				text = getString(title)
				textSize = 13f
				setTextColor(GOLD)
				setPadding(dp(4f), dp(14f), 0, dp(4f))
			})
			val card = LinearLayout(this).apply {
				orientation = LinearLayout.VERTICAL
				background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(12f).toFloat() }
			}
			targets.forEach { card.addView(row(it)) }
			list.addView(card)
		}
		list.addView(LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.END
			setPadding(0, dp(12f), 0, 0)
			addView(textButton(getString(R.string.pad_swap_face)) {
				save(mapping.swappedFace())
				Toast.makeText(this@PadMappingActivity, R.string.pad_swap_face_done, Toast.LENGTH_SHORT).show()
			})
			addView(textButton(getString(R.string.pad_reset)) { confirmReset() })
		})
		val scroll = ScrollView(this).apply { addView(list) }

		val content = LinearLayout(this).apply {
			orientation = if(landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
			if(landscape)
			{
				addView(testerColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
				addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
			}
			else
			{
				val h = (resources.displayMetrics.widthPixels * 0.62f).toInt() + dp(56f)
				addView(testerColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
				addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
			}
		}
		root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
		setContentView(root)
	}

	private fun row(target: PadTarget): View
	{
		val value = TextView(this).apply {
			textSize = 14f
			setTextColor(GOLD)
			gravity = Gravity.END
		}
		rowValues[target] = value
		updateRow(target)
		return LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = dp(48f)
			setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
			val attr = TypedValue()
			context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
			setBackgroundResource(attr.resourceId)
			isClickable = true
			setOnClickListener { showAssign(target) }
			addView(TextView(this@PadMappingActivity).apply {
				text = targetName(this@PadMappingActivity, target)
				textSize = 15f
				setTextColor(0xFFFFFFFF.toInt())
			}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			addView(value)
		}
	}

	private fun updateRow(target: PadTarget)
	{
		val list = mapping.bindings(target)
		rowValues[target]?.text = if(list.isEmpty()) getString(R.string.pad_no_key)
			else list.joinToString(" · ") { bindingName(this, it) }
	}

	private fun textButton(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
		this.text = text
		isAllCaps = false
		setTextColor(GOLD)
		setOnClickListener { onClick() }
	}

	private fun updateDevice()
	{
		val device = lastDevice
		deviceText.text = if(device == null) getString(R.string.pad_mapping_no_controller)
		else
		{
			val (l2, r2) = input.analogTriggers(device)
			getString(if(l2 && r2) R.string.pad_mapping_device_analog else R.string.pad_mapping_device_digital, device.name)
		}
	}

	private fun seen(device: InputDevice?)
	{
		if(device == null || device.isVirtual || device.id == lastDevice?.id)
			return
		val gamepad = device.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0
		if(!gamepad)
			return
		lastDevice = device
		updateDevice()
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		seen(event.device)
		return input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		seen(event.device)
		return input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
	}

	private fun save(m: PadMapping)
	{
		mapping = m
		preferences.padMapping = m
		input.reloadMapping()
		PadTarget.values().forEach { updateRow(it) }
		updateProfile()
	}

	// —— profiles (MappingSlots): the built-in ones, then the user's ——

	private lateinit var profileButton: Button
	private val prefs get() = preferences.sharedPreferences

	private fun profileName(name: String) = when(name)
	{
		MappingSlots.BUILTIN_DEFAULT -> getString(R.string.pad_profile_default)
		MappingSlots.BUILTIN_SWAPPED -> getString(R.string.pad_profile_swapped)
		else -> name
	}

	private fun updateProfile()
	{
		if(!this::profileButton.isInitialized)
			return
		// never picked one, still the default mapping: that is the default profile
		val current = MappingSlots.current(prefs) ?: MappingSlots.BUILTIN_DEFAULT.takeIf { mapping == PadMapping.default }
		val star = if(MappingSlots.modified(prefs, mapping)) "*" else ""
		profileButton.text = if(current == null) getString(R.string.pad_profiles) + "  ▾"
			else getString(R.string.pad_profile_current, profileName(current) + star) + "  ▾"
	}

	private fun showProfiles()
	{
		input.releaseAll()
		val current = MappingSlots.current(prefs)
		val star = if(MappingSlots.modified(prefs, mapping)) "*" else ""
		val names = MappingSlots.builtins.map { "★ " + profileName(it) to it } +
			MappingSlots.list(prefs).map { it.name to it.name }
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pad_profiles_title)
			.setItems(names.map { (label, id) -> if(id == current) "● $label$star" else label }.toTypedArray()) { _, which ->
				val id = names[which].second
				if(id in MappingSlots.builtins)
					loadProfile(id)
				else
					showProfileActions(id)
			}
			.setPositiveButton(R.string.pad_profile_save_new) { _, _ ->
				askName(MappingSlots.freeName(prefs, getString(R.string.pad_profile_default_name))) { name ->
					saveProfile(name)
				}
			}
			.setNegativeButton(R.string.pad_profile_close, null)
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	/**
	 * Changes that loading a profile (or resetting) would lose: the profile in use was edited, or
	 * there is none and the mapping is neither the default one nor kept in a profile.
	 */
	private fun unsavedChanges(): Boolean
	{
		val current = MappingSlots.current(prefs)
		return if(current != null) MappingSlots.modified(prefs, mapping)
			else mapping != PadMapping.default && MappingSlots.list(prefs).none { it.mapping == mapping }
	}

	/**
	 * Before something replaces the mapping: with unsaved changes, save them (in the user's own
	 * profile, or as a new one) or discard them; Cancel does nothing. The built-in profiles cannot
	 * be overwritten, so they only offer "as new". Then [then].
	 */
	private fun guardUnsaved(then: () -> Unit)
	{
		if(!unsavedChanges())
		{
			then()
			return
		}
		val current = MappingSlots.current(prefs)
		val own = current?.takeIf { it !in MappingSlots.builtins }
		val actions = listOfNotNull(
			own?.let { getString(R.string.unsaved_save_in, it) to { saveProfile(it); then() } },
			getString(R.string.unsaved_save_new) to {
				askName(MappingSlots.freeName(prefs, getString(R.string.pad_profile_default_name))) { name ->
					saveProfile(name)
					then()
				}
			},
			getString(R.string.unsaved_discard) to then)
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setTitle(if(current != null) getString(R.string.unsaved_title, profileName(current)) else getString(R.string.unsaved_title_none))
			.setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
			.setNegativeButton(android.R.string.cancel, null)
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	private fun loadProfile(name: String) = guardUnsaved {
		MappingSlots.load(prefs, name)?.let {
			save(it)
			Toast.makeText(this, getString(R.string.pad_profile_loaded, profileName(name)), Toast.LENGTH_SHORT).show()
		}
	}

	private fun saveProfile(name: String)
	{
		MappingSlots.save(prefs, name, mapping)
		updateProfile()
		Toast.makeText(this, getString(R.string.pad_profile_saved, name), Toast.LENGTH_SHORT).show()
	}

	private fun showProfileActions(name: String)
	{
		val actions = arrayOf(getString(R.string.pad_profile_load), getString(R.string.pad_profile_overwrite),
			getString(R.string.pad_profile_rename), getString(R.string.pad_profile_delete))
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setTitle(name)
			.setItems(actions) { _, which ->
				when(which)
				{
					0 -> loadProfile(name)
					1 -> confirm(getString(R.string.pad_profile_overwrite_confirm, name)) { saveProfile(name) }
					2 -> askName(name) { to ->
						if(to != name && MappingSlots.list(prefs).any { it.name == to })
							Toast.makeText(this, R.string.pad_profile_name_taken, Toast.LENGTH_SHORT).show()
						else
							MappingSlots.rename(prefs, name, to)
						updateProfile()
						showProfiles()
					}
					3 -> confirm(getString(R.string.pad_profile_delete_confirm, name)) {
						MappingSlots.delete(prefs, name)
						updateProfile()
						showProfiles()
					}
				}
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> showProfiles() }
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	private fun confirm(message: String, yes: () -> Unit)
	{
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ -> yes() }
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	private fun askName(suggested: String, done: (String) -> Unit)
	{
		val edit = android.widget.EditText(this).apply {
			inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			setText(suggested)
			selectAll()
		}
		val box = android.widget.FrameLayout(this).apply {
			setPadding(dp(20f), dp(10f), dp(20f), 0)
			addView(edit)
		}
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setTitle(R.string.pad_profile_name)
			.setView(box)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok) { _, _ ->
				// "#" starts the names of the built-in profiles
				val name = edit.text.toString().trim().trimStart('#').trim()
				if(name.isNotEmpty()) done(name)
			}
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	private fun styled(dialog: AlertDialog): AlertDialog
	{
		dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
			setColor(CARD)
			cornerRadius = dp(20f).toFloat()
		})
		return dialog
	}

	private fun goldButtons(dialog: AlertDialog)
	{
		listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)
			.forEach { dialog.getButton(it)?.setTextColor(GOLD) }
	}

	private fun confirmReset()
	{
		// with unsaved changes, that question already asks whether to lose them
		if(unsavedChanges())
		{
			guardUnsaved {
				MappingSlots.setCurrent(prefs, MappingSlots.BUILTIN_DEFAULT)
				save(PadMapping.default)
			}
			return
		}
		val dialog = styled(MaterialAlertDialogBuilder(this)
			.setMessage(R.string.pad_reset_confirm)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.pad_reset) { _, _ ->
				MappingSlots.setCurrent(prefs, MappingSlots.BUILTIN_DEFAULT)
				save(PadMapping.default)
			}
			.create())
		dialog.show()
		goldButtons(dialog)
	}

	/**
	 * The keys of [target]: the current ones (tap to remove) and the capture of a new one. The key
	 * pressed shows first and is saved with "Add", so pressing Back or a wrong key changes nothing yet.
	 */
	private fun showAssign(target: PadTarget)
	{
		input.releaseAll()
		val column = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(22f), dp(8f), dp(22f), 0)
		}
		fun text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

		var dialog: AlertDialog? = null
		var captured: Int? = null

		// the current keys, each one removable
		val current = text(13f, GREY).apply { setPadding(0, 0, 0, dp(4f)) }
		val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
		fun fillChips()
		{
			chips.removeAllViews()
			val list = mapping.bindings(target)
			current.text = getString(if(list.isEmpty()) R.string.pad_assign_none else R.string.pad_assign_current)
			for(b in list)
				chips.addView(textButton("✕  " + bindingName(this, b)) {
					save(mapping.without(target, b))
					Toast.makeText(this, getString(R.string.pad_assign_removed, bindingName(this, b)), Toast.LENGTH_SHORT).show()
					fillChips()
				}.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
		}
		fillChips()
		column.addView(current)
		column.addView(chips)

		// an analog trigger always works as itself: say so
		val device = lastDevice
		if(device != null && (target == PadTarget.L2 || target == PadTarget.R2))
		{
			val (l2, r2) = input.analogTriggers(device)
			if((target == PadTarget.L2 && l2) || (target == PadTarget.R2 && r2))
				column.addView(text(12f, GREY).apply {
					text = getString(R.string.pad_assign_analog, targetName(this@PadMappingActivity, target))
					setPadding(0, dp(6f), 0, 0)
				})
		}

		val prompt = text(16f, 0xFFFFFFFF.toInt()).apply {
			text = getString(R.string.pad_assign_press)
			setPadding(0, dp(16f), 0, dp(4f))
		}
		val conflict = text(12f, GOLD).apply { visibility = View.GONE }
		val hold = CheckBox(this).apply {
			text = getString(R.string.pad_assign_hold)
			setTextColor(0xFFFFFFFF.toInt())
			buttonTintList = ColorStateList.valueOf(GOLD)
		}
		val note = text(12f, GREY).apply { text = getString(R.string.pad_assign_hold_note) }
		column.addView(prompt)
		column.addView(conflict)
		column.addView(hold)
		column.addView(note)

		fun refresh()
		{
			val code = captured
			dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = code != null
			if(code == null)
				return
			prompt.text = getString(R.string.pad_assign_got, keyName(this, code))
			val binding = KeyBinding(code, hold.isChecked)
			val other = (if(binding.hold) mapping.holdTarget(code) else mapping.pressTarget(code))?.takeIf { it != target }
			val notes = mutableListOf<String>()
			if(other != null)
				notes += getString(R.string.pad_assign_conflict, targetName(this, other))
			if(mapping.bindings(target).size >= PadMapping.MAX_PER_TARGET && binding !in mapping.bindings(target))
				notes += getString(R.string.pad_assign_full)
			conflict.text = notes.joinToString("\n")
			conflict.visibility = if(notes.isEmpty()) View.GONE else View.VISIBLE
		}
		hold.setOnCheckedChangeListener { _, _ -> refresh() }

		dialog = styled(MaterialAlertDialogBuilder(this)
			.setTitle(targetName(this, target))
			.setView(ScrollView(this).apply { addView(column) })
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.pad_assign_add) { _, _ ->
				captured?.let { save(mapping.with(target, KeyBinding(it, hold.isChecked))) }
			}
			.create())
		dialog.setOnKeyListener { _, keyCode, event ->
			// the system's back gesture (a virtual device) closes the dialog; any real key is captured,
			// the controller's own Back included
			if(event.device == null || event.device.isVirtual)
				return@setOnKeyListener false
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
			{
				captured = keyCode
				refresh()
			}
			true
		}
		dialog.show()
		goldButtons(dialog)
		refresh()
	}
}
