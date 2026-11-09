// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.GameplayToast
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.databinding.ViewPadEditorBinding

/**
 * The touch pad's editor (phase 13): the layout bar, its hint and the options panel. The same one
 * on "Try the touch pad" and over the live game (long press on the controls button).
 */
class PadEditor(
	private val activity: AppCompatActivity,
	private val ui: ViewPadEditorBinding,
	private val pad: ConsolePadView,
	private val shell: ShellView?,
	/** the test screen: its own bar moves with the editor's, and it can show the red/green zones */
	private val testScreen: Boolean,
	/** bars of the host that go top/bottom together with the editor's, and their arrow buttons */
	private val extraBars: List<View> = emptyList(),
	private val extraMoveButtons: List<Button> = emptyList())
{
	companion object
	{
		const val KEY_BARS_BOTTOM = "console_test_bars_bottom"
		private const val KEY_PANEL_LEFT = "console_test_panel_left"
		private const val HINT_MS = 5000L
	}

	/** editing started or stopped */
	var onEditingChanged: ((Boolean) -> Unit)? = null

	/** the host's way to put the gyroscope stick's centre where the phone is now (null = no button for it) */
	var gyroRecenter: (() -> Unit)? = null

	private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
	private val handler = Handler(Looper.getMainLooper())
	private val hideHint = Runnable { ui.editHint.isVisible = false }
	private val gold = 0xFFD4AB55.toInt()

	val editing get() = pad.editMode
	val panelOpen get() = ui.settingsPanel.isVisible

	init
	{
		ui.editBarDone.setOnClickListener { stopEditing() }
		ui.editBarReset.setOnClickListener { LayoutDialogs.confirmReset(activity, pad) }
		ui.editBarControls.setOnClickListener { LayoutDialogs.showControls(activity, pad) }
		ui.editBarSlots.setOnClickListener { LayoutDialogs.showSlots(activity, pad) }
		ui.editBarOptions.setOnClickListener { showSettings(!panelOpen) }
		ui.editBarMove.setOnClickListener { placeBars(!barsBottom) }
		ui.symmetricCheck.setOnCheckedChangeListener { _, checked -> pad.symmetric = checked }
		ui.settingsScrim.setOnClickListener { showSettings(false) }
		extraMoveButtons.forEach { it.setOnClickListener { placeBars(!barsBottom) } }
		// the touchpad (double tap on the video)
		ui.touchBarDone.setOnClickListener { pad.editTarget = ConsolePadView.EditTarget.LAYOUT }
		ui.touchBarTie.setOnClickListener { pad.touchTied = !pad.touchTied }
		ui.touchBarFillVideo.setOnClickListener { pad.fillTouchVideo() }
		ui.touchBarFillScreen.setOnClickListener { pad.fillTouchScreen() }
		ui.touchBarMove.setOnClickListener { placeBars(!barsBottom) }
		pad.onEditStateChanged = { refreshBars(announce = true) }
		placeBars(barsBottom)
		placePanel(panelLeft)
	}

	fun startEditing()
	{
		if(editing)
			return
		showSettings(false)
		pad.symmetric = ui.symmetricCheck.isChecked
		pad.editMode = true
		refreshBars(announce = true)
		onEditingChanged?.invoke(true)
	}

	private var shownTarget: ConsolePadView.EditTarget? = null

	/** the layout bar or the touchpad's, with the hint of what is being edited when it changes */
	private fun refreshBars(announce: Boolean)
	{
		val target = if(editing) pad.editTarget else null
		ui.editBar.isVisible = target == ConsolePadView.EditTarget.LAYOUT
		ui.touchBar.isVisible = target == ConsolePadView.EditTarget.TOUCHPAD
		ui.touchBarTie.setText(if(pad.touchTied) R.string.touch_edit_tied else R.string.touch_edit_free)
		if(announce && target != null && target != shownTarget)
		{
			ui.editHint.setText(when
			{
				target == ConsolePadView.EditTarget.TOUCHPAD -> R.string.touch_edit_hint
				pad.videoEditable -> R.string.console_test_edit_hint
				else -> R.string.layout_edit_hint_fixed_video
			})
			flashHint()
		}
		shownTarget = target
	}

	fun stopEditing()
	{
		if(!editing)
			return
		showSettings(false)
		pad.editMode = false
		refreshBars(announce = false)
		handler.removeCallbacks(hideHint)
		ui.editHint.isVisible = false
		onEditingChanged?.invoke(false)
	}

	/** the hint shows for a few seconds, then gets out of the way */
	private fun flashHint()
	{
		ui.editHint.isVisible = true
		handler.removeCallbacks(hideHint)
		handler.postDelayed(hideHint, HINT_MS)
	}

	/** Back closes the panel, then the editor; false = nothing of ours was open */
	fun handleBack(): Boolean
	{
		when
		{
			panelOpen -> showSettings(false)
			editing && pad.editTarget == ConsolePadView.EditTarget.TOUCHPAD -> pad.editTarget = ConsolePadView.EditTarget.LAYOUT
			editing -> stopEditing()
			else -> return false
		}
		return true
	}

	fun showSettings(show: Boolean)
	{
		if(show)
		{
			pad.cancelAll()
			buildSettings()
		}
		ui.settingsPanel.isVisible = show
		ui.settingsScrim.isVisible = show
		pad.idleBlocked = show
	}

	// where the bars and the panel sit is remembered (only on this device: not in the export)
	private var barsBottom
		get() = prefs.getBoolean(KEY_BARS_BOTTOM, false)
		set(value) { prefs.edit().putBoolean(KEY_BARS_BOTTOM, value).apply() }
	private var panelLeft
		get() = prefs.getBoolean(KEY_PANEL_LEFT, false)
		set(value) { prefs.edit().putBoolean(KEY_PANEL_LEFT, value).apply() }

	private fun placeBars(bottom: Boolean)
	{
		barsBottom = bottom
		for(bar in listOf(ui.editBar, ui.touchBar) + extraBars)
		{
			val lp = bar.layoutParams as FrameLayout.LayoutParams
			lp.gravity = Gravity.CENTER_HORIZONTAL or (if(bottom) Gravity.BOTTOM else Gravity.TOP)
			lp.topMargin = if(bottom) 0 else dp(28f)
			lp.bottomMargin = if(bottom) dp(8f) else 0
			bar.layoutParams = lp
		}
		val arrow = if(bottom) "▲" else "▼"
		(listOf(ui.editBarMove, ui.touchBarMove) + extraMoveButtons).forEach { it.text = arrow }
	}

	private fun placePanel(left: Boolean)
	{
		panelLeft = left
		val lp = ui.settingsPanel.layoutParams as FrameLayout.LayoutParams
		lp.gravity = if(left) Gravity.START else Gravity.END
		ui.settingsPanel.layoutParams = lp
	}

	/** resets lose fine tuning: ask first, and say it was done */
	private fun confirmReset(message: Int, reset: () -> Unit)
	{
		pad.cancelAll()
		MaterialAlertDialogBuilder(activity)
			.setMessage(message)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.console_test_reset_action) { _, _ ->
				reset()
				GameplayToast.show(activity, R.string.console_test_reset_done)
			}
			.show()
	}

	// ---------------------------------------------------------------- the options panel

	private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()
	private fun str(id: Int) = activity.getString(id)

	private fun textButton(text: String, onClick: () -> Unit) =
		Button(activity, null, 0, com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton).apply {
			this.text = text
			setTextColor(gold)
			setOnClickListener { onClick() }
		}

	/** the settings apply at once: on the pad, and on the shell behind it */
	private fun applyAll()
	{
		pad.reloadSettings()
		shell?.opacity = pad.settings.shellOpacity
	}

	/** the section that is open (only one at a time, so the panel is a short list of titles) */
	private var openSection: String? = null

	private fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); applyAll() }
	private fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); applyAll() }
	private fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); applyAll() }

	/** rebuilds the panel after the current touch is over (a radio button must not be rebuilt while it reports its change) */
	private fun rebuildLater() { ui.settingsContent.post { buildSettings() } }

	private fun buildSettings()
	{
		val c = ui.settingsContent
		c.removeAllViews()
		val s = pad.settings
		val g = pad.geometry

		c.addView(LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			addView(TextView(activity).apply {
				text = str(R.string.console_test_section_basic)
				setTextColor(gold)
				textSize = 17f
				setTypeface(typeface, Typeface.BOLD)
			}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
			// arrow towards the side it would go to
			addView(textButton(if(panelLeft) "▶" else "◀") { placePanel(!panelLeft); buildSettings() }.apply {
				contentDescription = str(R.string.console_test_move_panel)
				minWidth = 0
				minimumWidth = 0
			})
			addView(textButton(str(R.string.console_test_close_panel)) { showSettings(false) })
		})
		// which controls are on, and the saved layouts (also in the layout editor's bar)
		c.addView(LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			addView(textButton(str(R.string.console_layout_controls)) { showSettings(false); LayoutDialogs.showControls(activity, pad) })
			addView(textButton(str(R.string.console_layout_slots_button)) { showSettings(false); LayoutDialogs.showSlots(activity, pad) })
		})

		// one section per control; each shows what it is set to while closed
		section(c, "look", str(R.string.console_sec_look), shellName()) { lookSection(it, s) }
		section(c, "left", str(R.string.console_sec_left), stickSummary(s, Side.L)) { stickSection(it, s, Side.L) }
		section(c, "right", str(R.string.console_sec_right), stickSummary(s, Side.R)) { stickSection(it, s, Side.R) }
		section(c, "triggers", str(R.string.console_sec_triggers), triggerSummary(s)) { triggersSection(it, s) }
		section(c, "groups", str(R.string.console_sec_groups), "") { groupsSection(it, s) }
		section(c, "gyro", str(R.string.console_sec_gyro), gyroSummary(s)) { gyroSection(it, s) }
		section(c, "fine", str(R.string.console_sec_fine), str(R.string.console_sec_fine_summary)) { fineSection(it, s) }

		c.addView(textButton(str(R.string.console_test_reset_options)) {
			confirmReset(R.string.console_test_reset_options_confirm) { ConsolePadSettings.reset(prefs); applyAll(); buildSettings() }
		})
		c.addView(TextView(activity).apply {
			text = activity.getString(R.string.console_test_screen_info, g.widthMm, g.heightMm)
			setTextColor(0xFF999999.toInt())
			textSize = 12f
		})
	}

	// ---------------------------------------------------------------- sections

	private fun shellName() = str(when(ShellTheme.load(prefs))
	{
		ShellTheme.SUNSET -> R.string.console_shell_sunset
		ShellTheme.GRAPHITE -> R.string.console_shell_graphite
		ShellTheme.ICE -> R.string.console_shell_ice
	})

	private fun clickName(m: ClickMode) = str(when(m)
	{
		ClickMode.DOUBLE_TAP -> R.string.console_stick_click_double
		ClickMode.EDGE -> R.string.console_stick_click_edge
		ClickMode.OFF -> R.string.console_stick_click_off
	})

	private fun stickSummary(s: ConsolePadSettings, side: Side): String
	{
		val style = str(if(s.style(side) == StickStyle.DRAG) R.string.console_stick_style_drag else R.string.console_stick_style_joystick)
		val click = (if(side == Side.R) "R3" else "L3") + ": " + clickName(s.clickMode(side)).lowercase()
		val squash = activity.getString(R.string.console_summary_squash,
			s.stickSquash(side)?.label ?: str(R.string.console_btn_none).lowercase())
		return "$style · $click · $squash"
	}

	private fun triggerSummary(s: ConsolePadSettings): String = listOf(Side.L, Side.R).joinToString(" · ") { side ->
		(if(side == Side.R) "R2: " else "L2: ") + when(s.triggerSquash(side))
		{
			TriggerSquash.NONE -> str(R.string.console_trigger_none).lowercase()
			TriggerSquash.PRESSURE -> str(R.string.console_trigger_pressure).lowercase()
			TriggerSquash.HOLD -> s.triggerHold(side).label
		}
	}

	private fun lookSection(c: LinearLayout, s: ConsolePadSettings)
	{
		radios(c, str(R.string.preferences_console_shell_title), ShellTheme.values().map {
				it.value to str(when(it)
				{
					ShellTheme.SUNSET -> R.string.console_shell_sunset
					ShellTheme.GRAPHITE -> R.string.console_shell_graphite
					ShellTheme.ICE -> R.string.console_shell_ice
				})
			}, ShellTheme.load(prefs).value) {
			prefs.edit().putString(ShellTheme.KEY, it).apply()
			ShellTheme.from(it).let { t -> shell?.theme = t; pad.theme = t }
		}
		slider(c, str(R.string.console_test_shell_opacity), 0, 100, (s.shellOpacity * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_SHELL_OPACITY, it)
		}
		radios(c, str(R.string.console_test_line_color), listOf(
				LineColor.THEME.value to str(R.string.console_test_line_theme),
				LineColor.BLUE.value to str(R.string.console_test_line_blue),
				LineColor.BLACK.value to str(R.string.console_test_line_black),
				LineColor.WHITE.value to str(R.string.console_test_line_white)),
			s.lineColor.value) { putString(ConsolePadSettings.KEY_LINE_COLOR, it) }
		slider(c, str(R.string.console_test_opacity), 0, 100, (s.opacity * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_OPACITY, it)
		}
		switch(c, str(R.string.console_test_lamp), s.lamp) { putBoolean(ConsolePadSettings.KEY_LAMP, it); rebuildLater() }
		if(s.lamp)
			slider(c, str(R.string.console_test_lamp_size), 50, 250, (s.lampSizeMm * 10).toInt(), { "%.1f mm".format(it / 10f) }) {
				putInt(ConsolePadSettings.KEY_LAMP_SIZE, it)
			}
		radios(c, str(R.string.console_test_auto_hide), ConsolePadSettings.AUTO_HIDE_CHOICES.map {
				it.toString() to if(it == 0) str(R.string.console_test_auto_hide_off) else "$it s"
			}, s.autoHideS.toString()) { putInt(ConsolePadSettings.KEY_AUTO_HIDE, it.toInt()) }
		radios(c, str(R.string.console_test_cinema), listOf(
				CinemaMode.OFF.value to str(R.string.console_test_cinema_off),
				CinemaMode.FIT.value to str(R.string.console_test_cinema_fit),
				CinemaMode.STRETCH.value to str(R.string.console_test_cinema_stretch),
				CinemaMode.ZOOM.value to str(R.string.console_test_cinema_zoom),
				CinemaMode.OPTICAL.value to str(R.string.console_test_cinema_optical)),
			s.cinema.value) { putString(ConsolePadSettings.KEY_CINEMA, it) }
		val preferences = Preferences(activity)
		switch(c, str(R.string.preferences_button_haptic_enabled_title), preferences.buttonHapticEnabled) {
			preferences.buttonHapticEnabled = it
		}
	}

	/** a stick: how it follows the thumb, its click, and what squashing holds (the two sticks have exactly the same options) */
	private fun stickSection(c: LinearLayout, s: ConsolePadSettings, side: Side)
	{
		val left = side == Side.L
		radios(c, str(R.string.console_stick_style), listOf(
				StickStyle.STICK.value to str(R.string.console_stick_style_joystick),
				StickStyle.DRAG.value to str(R.string.console_stick_style_drag)),
			s.style(side).value) {
			putString(if(left) ConsolePadSettings.KEY_CAM_MODE_L else ConsolePadSettings.KEY_CAM_MODE, it); rebuildLater()
		}
		note(c, str(R.string.console_stick_style_hint))
		if(s.style(side) == StickStyle.DRAG)
		{
			slider(c, str(R.string.console_stick_sens), 30, 300, (s.sensitivity(side) * 100).toInt(), { "$it %" }) {
				putInt(if(left) ConsolePadSettings.KEY_CAM_SENS_L else ConsolePadSettings.KEY_CAM_SENS, it)
			}
			slider(c, str(R.string.console_stick_deadzone), 0, 30, (s.antiDeadzone(side) * 100).toInt(), { "$it %" }) {
				putInt(if(left) ConsolePadSettings.KEY_ANTI_DEADZONE_L else ConsolePadSettings.KEY_ANTI_DEADZONE, it)
			}
			note(c, str(R.string.console_stick_deadzone_hint))
		}
		radios(c, str(if(left) R.string.console_stick_click_l else R.string.console_stick_click_r), listOf(
				ClickMode.DOUBLE_TAP.value to str(R.string.console_stick_click_double),
				ClickMode.EDGE.value to str(R.string.console_stick_click_edge),
				ClickMode.OFF.value to str(R.string.console_stick_click_off)),
			s.clickMode(side).value) { putString(if(left) ConsolePadSettings.KEY_L3 else ConsolePadSettings.KEY_R3, it) }
		note(c, str(R.string.console_stick_click_hint))
		pickerRow(c, str(R.string.console_stick_squash), PadButtonDialog.name(activity, s.stickSquash(side))) {
			PadButtonDialog.show(activity, R.string.console_btn_pick_squash, s.stickSquash(side), null, true) { b ->
				putString(if(left) ConsolePadSettings.KEY_STICK_SQUASH_L else ConsolePadSettings.KEY_STICK_SQUASH_R, PadButton.save(b))
				buildSettings()
			}
		}
		note(c, str(R.string.console_stick_squash_hint))
		calibrationRow(c, s, side)
	}

	/** the calibration of this side's thumb: its state and the buttons to measure it again or delete it */
	private fun calibrationRow(c: LinearLayout, s: ConsolePadSettings, side: Side)
	{
		val calibrated = s.calibration(side) != null
		title(c, str(if(side == Side.R) R.string.calib_title_r else R.string.calib_title_l) + ": " +
			str(if(calibrated) R.string.calib_status_on else R.string.calib_status_off))
		c.addView(LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			addView(textButton(str(R.string.calib_button)) {
				pad.cancelAll()
				CalibrationDialog.show(activity, side) { applyAll(); buildSettings() }
			})
			if(calibrated)
				addView(textButton(str(R.string.calib_delete)) {
					SquashCalibration.save(prefs, side, null); applyAll(); buildSettings()
				})
		})
		note(c, str(R.string.calib_hint))
	}

	private fun triggersSection(c: LinearLayout, s: ConsolePadSettings)
	{
		for(side in listOf(Side.L, Side.R))
		{
			val left = side == Side.L
			radios(c, str(if(left) R.string.console_trigger_mode_l else R.string.console_trigger_mode_r), listOf(
					TriggerSquash.NONE.value to str(R.string.console_trigger_none),
					TriggerSquash.PRESSURE.value to str(R.string.console_trigger_pressure),
					TriggerSquash.HOLD.value to str(R.string.console_trigger_hold)),
				s.triggerSquash(side).value) {
				putString(if(left) ConsolePadSettings.KEY_TRIGGER_MODE_L else ConsolePadSettings.KEY_TRIGGER_MODE_R, it); rebuildLater()
			}
			if(s.triggerSquash(side) == TriggerSquash.HOLD)
				pickerRow(c, str(R.string.console_trigger_hold_button), s.triggerHold(side).label) {
					PadButtonDialog.show(activity, R.string.console_btn_pick_hold, s.triggerHold(side),
						if(left) PadButton.L2 else PadButton.R2, false) { b ->
						putString(if(left) ConsolePadSettings.KEY_TRIGGER_HOLD_L else ConsolePadSettings.KEY_TRIGGER_HOLD_R, (b ?: return@show).value)
						buildSettings()
					}
				}
		}
		note(c, str(R.string.console_trigger_mode_hint))
		note(c, str(R.string.calib_triggers_hint))
	}

	/** closed section: the controller's gyroscope on or off, then the stick */
	private fun gyroSummary(s: ConsolePadSettings): String
	{
		val native = str(if(Preferences(activity).motionEnabled && hasMotionSensors(activity)) R.string.gyro_summary_native_on
			else R.string.gyro_summary_native_off)
		return native + " · " + activity.getString(R.string.gyro_summary_stick, gyroStickSummary(s))
	}

	private fun gyroStickSummary(s: ConsolePadSettings): String
	{
		if(!s.gyroHardware)
			return str(R.string.gyro_no_hardware_short)
		if(!s.gyroEnabled)
			return str(R.string.gyro_summary_off).lowercase()
		val side = str(if(s.gyroSide == Side.R) R.string.gyro_target_right else R.string.gyro_target_left)
		val style = str(if(s.gyroStyle == GyroStyle.MOUSE) R.string.gyro_style_mouse else R.string.gyro_style_tilt)
		val act = when(s.gyroActivation)
		{
			GyroActivation.ALWAYS -> str(R.string.gyro_act_always)
			GyroActivation.HOLD -> activity.getString(R.string.gyro_summary_hold, GyroInputsDialog.summary(activity, s.gyroInputs))
			GyroActivation.CLUTCH -> activity.getString(R.string.gyro_summary_clutch, GyroInputsDialog.summary(activity, s.gyroInputs))
			GyroActivation.TOGGLE -> activity.getString(R.string.gyro_summary_toggle, GyroInputsDialog.summary(activity, s.gyroInputs))
		}
		return "$side · $style · $act"
	}

	/**
	 * Everything about the phone's movement, in one place (2.5.7): how it is held, the controller's own gyroscope
	 * (the console gets the movement, for games that read it) and the gyroscope as a stick (for games that do not).
	 * The same panel on the test screen and over the live game: every change applies at once. What the device's
	 * sensors cannot do is said here, where the switches are.
	 */
	private fun gyroSection(c: LinearLayout, s: ConsolePadSettings)
	{
		val preferences = Preferences(activity)
		val sensors = hasMotionSensors(activity)
		// how it is held: both uses read it
		if(sensors)
		{
			val entries = activity.resources.getStringArray(R.array.preferences_motion_grip_entries)
			radios(c, str(R.string.preferences_motion_grip_title), listOf(
					Preferences.MOTION_GRIP_UPRIGHT to entries[0], Preferences.MOTION_GRIP_FLAT to entries[1]),
				if(preferences.motionGripFlat) Preferences.MOTION_GRIP_FLAT else Preferences.MOTION_GRIP_UPRIGHT) {
				preferences.motionGripFlat = it == Preferences.MOTION_GRIP_FLAT
				applyAll()
			}
		}
		// the controller's own gyroscope (works with the accelerometer alone, tilt only)
		if(!sensors)
		{
			title(c, str(R.string.preferences_motion_enabled_title))
			note(c, str(R.string.preferences_motion_no_sensors))
		}
		else
		{
			switch(c, str(R.string.preferences_motion_enabled_title), preferences.motionEnabled) {
				preferences.motionEnabled = it
				applyAll()
			}
			note(c, str(R.string.preferences_motion_enabled_summary))
			if(!s.gyroHardware)
				note(c, str(R.string.preferences_motion_accel_only))
			// the controller faces forward again where the device points (only in a game: the test screen has no console)
			if(preferences.motionEnabled && !testScreen)
				gyroRecenter?.let { recenter ->
					c.addView(textButton(str(R.string.gyro_recenter)) {
						recenter()
						GameplayToast.show(activity, R.string.gyro_recentered)
					})
					note(c, str(R.string.motion_recenter_hint))
				}
		}

		header(c, str(R.string.gyro_sec))
		// no gyroscope in this device: nothing to switch on, and it says so plainly
		if(!s.gyroHardware)
		{
			title(c, str(R.string.gyro_enable))
			note(c, str(R.string.gyro_no_hardware))
			return
		}
		switch(c, str(R.string.gyro_enable), s.gyroEnabled) {
			// switching it on again starts afresh: the paused state it was left in is forgotten
			if(it)
				prefs.edit().remove(ConsolePadSettings.KEY_GYRO_PAUSED).apply()
			putBoolean(ConsolePadSettings.KEY_GYRO, it)
			rebuildLater()
		}
		note(c, str(R.string.gyro_enable_hint))
		if(!s.gyroEnabled)
			return
		radios(c, str(R.string.gyro_target), listOf(
				"r" to str(R.string.gyro_target_right), "l" to str(R.string.gyro_target_left)),
			if(s.gyroSide == Side.R) "r" else "l") { putString(ConsolePadSettings.KEY_GYRO_SIDE, it) }
		radios(c, str(R.string.gyro_style), listOf(
				GyroStyle.MOUSE.value to str(R.string.gyro_style_mouse),
				GyroStyle.TILT.value to str(R.string.gyro_style_tilt)),
			s.gyroStyle.value) { putString(ConsolePadSettings.KEY_GYRO_STYLE, it); rebuildLater() }
		note(c, str(if(s.gyroStyle == GyroStyle.MOUSE) R.string.gyro_style_mouse_hint else R.string.gyro_style_tilt_hint))
		radios(c, str(R.string.gyro_activation), listOf(
				GyroActivation.ALWAYS.value to str(R.string.gyro_act_always),
				GyroActivation.HOLD.value to str(R.string.gyro_act_hold),
				GyroActivation.CLUTCH.value to str(R.string.gyro_act_clutch),
				GyroActivation.TOGGLE.value to str(R.string.gyro_act_toggle)),
			s.gyroActivation.value) { putString(ConsolePadSettings.KEY_GYRO_ACTIVATION, it); rebuildLater() }
		if(s.gyroActivation != GyroActivation.ALWAYS)
		{
			pickerRow(c, str(R.string.gyro_inputs), GyroInputsDialog.summary(activity, s.gyroInputs)) {
				GyroInputsDialog.show(activity, s.gyroInputs) { ids ->
					putString(ConsolePadSettings.KEY_GYRO_INPUTS, GyroInputs.encode(ids))
					buildSettings()
				}
			}
			if(s.gyroInputs.any { GyroInputs.isStick(it) })
			{
				slider(c, str(R.string.gyro_stick_threshold), 5, 80, (s.gyroStickThreshold * 100).toInt(), { "$it %" }) {
					putInt(ConsolePadSettings.KEY_GYRO_STICK_THRESHOLD, it)
				}
				note(c, str(R.string.gyro_stick_threshold_hint))
			}
		}
		note(c, str(R.string.gyro_activation_hint))
		if(s.gyroStyle == GyroStyle.MOUSE)
			radios(c, str(R.string.gyro_horizontal), listOf(
					GyroHorizontal.YAW.value to str(R.string.gyro_horizontal_yaw),
					GyroHorizontal.ROLL.value to str(R.string.gyro_horizontal_roll)),
				s.gyroHorizontal.value) { putString(ConsolePadSettings.KEY_GYRO_HORIZONTAL, it) }

		header(c, str(R.string.gyro_calibration))
		slider(c, str(R.string.gyro_sens), 20, 400, (s.gyroSensitivity * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_GYRO_SENS, it)
		}
		slider(c, str(R.string.gyro_sens_y), 25, 200, (s.gyroSensitivityY * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_GYRO_SENS_Y, it)
		}
		if(s.gyroStyle == GyroStyle.TILT)
			slider(c, str(R.string.gyro_tilt_range), 10, 60, s.gyroTiltRange.toInt(), { "$it°" }) {
				putInt(ConsolePadSettings.KEY_GYRO_TILT_RANGE, it)
			}
		slider(c, str(R.string.gyro_deadzone), 0, 30, (s.gyroDeadzone * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_GYRO_DEADZONE, it)
		}
		note(c, str(R.string.gyro_deadzone_hint))
		slider(c, str(R.string.gyro_smooth), 0, 100, (s.gyroSmoothing * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_GYRO_SMOOTH, it)
		}
		note(c, str(R.string.gyro_smooth_hint))
		slider(c, str(R.string.gyro_curve), 100, 250, (s.gyroCurve * 100).toInt(), { "%.2f".format(it / 100f) }) {
			putInt(ConsolePadSettings.KEY_GYRO_CURVE, it)
		}
		note(c, str(R.string.gyro_curve_hint))
		slider(c, str(R.string.gyro_anti_deadzone), 0, 40, (s.gyroAntiDeadzone * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_GYRO_ANTI_DEADZONE, it)
		}
		note(c, str(R.string.console_stick_deadzone_hint))
		switch(c, str(R.string.gyro_invert_x), s.gyroInvertX) { putBoolean(ConsolePadSettings.KEY_GYRO_INVERT_X, it) }
		switch(c, str(R.string.gyro_invert_y), s.gyroInvertY) { putBoolean(ConsolePadSettings.KEY_GYRO_INVERT_Y, it) }
		switch(c, str(R.string.gyro_mute_native), s.gyroMuteNative) { putBoolean(ConsolePadSettings.KEY_GYRO_MUTE_NATIVE, it) }
		note(c, str(R.string.gyro_mute_native_hint))
		gyroRecenter?.let { recenter ->
			c.addView(textButton(str(R.string.gyro_recenter)) {
				recenter()
				GameplayToast.show(activity, R.string.gyro_recentered)
			})
			note(c, str(R.string.gyro_recenter_hint))
		}
		// the options the gyroscope stick has of its own: back to the factory values
		c.addView(textButton(str(R.string.gyro_reset)) {
			confirmReset(R.string.gyro_reset_confirm) {
				prefs.edit().apply {
					listOf(ConsolePadSettings.KEY_GYRO_SENS, ConsolePadSettings.KEY_GYRO_SENS_Y, ConsolePadSettings.KEY_GYRO_DEADZONE,
						ConsolePadSettings.KEY_GYRO_SMOOTH, ConsolePadSettings.KEY_GYRO_CURVE, ConsolePadSettings.KEY_GYRO_ANTI_DEADZONE,
						ConsolePadSettings.KEY_GYRO_TILT_RANGE, ConsolePadSettings.KEY_GYRO_INVERT_X, ConsolePadSettings.KEY_GYRO_INVERT_Y,
						ConsolePadSettings.KEY_GYRO_MUTE_NATIVE, ConsolePadSettings.KEY_GYRO_STYLE, ConsolePadSettings.KEY_GYRO_ACTIVATION,
						ConsolePadSettings.KEY_GYRO_INPUTS, ConsolePadSettings.KEY_GYRO_STICK_THRESHOLD,
						ConsolePadSettings.KEY_GYRO_HORIZONTAL, ConsolePadSettings.KEY_GYRO_SIDE)
						.forEach { remove(it) }
				}.apply()
				applyAll(); buildSettings()
			}
		})
	}

	private fun groupsSection(c: LinearLayout, s: ConsolePadSettings)
	{
		switch(c, str(R.string.console_test_group_keep), s.clusterKeep) { putBoolean(ConsolePadSettings.KEY_CLUSTER_KEEP, it) }
		switch(c, str(R.string.console_test_group_capture), s.clusterCapture) { putBoolean(ConsolePadSettings.KEY_CLUSTER_CAPTURE, it) }
		// the two button groups, worked like a stick
		for(group in ConsolePadSettings.groups)
		{
			val shape = if(group == "face") s.faceShape else s.dpadShape
			header(c, str(if(group == "face") R.string.console_test_group_face else R.string.console_test_group_dpad))
			val key = { field: String -> ConsolePadSettings.shapeKey(group, field) }
			slider(c, str(R.string.console_test_group_dead), 0, 60, shape.dead, { "$it %" }) { putInt(key("dead"), it) }
			slider(c, str(R.string.console_test_group_green), 30, 100, shape.green, { "$it %" }) { putInt(key("green"), it) }
			slider(c, str(R.string.console_test_group_width), 15, 45, shape.width, { "$it %" }) { putInt(key("width"), it) }
			slider(c, str(R.string.console_test_group_rotation), -90, 90, shape.rotation, { "$it°" }) { putInt(key("rot"), it) }
			switch(c, str(R.string.console_test_group_invert), shape.invert) { putBoolean(key("invert"), it) }
		}
		note(c, str(R.string.console_fine_group_hint))
	}

	/** what depends on the phone and the hand, not on one control: the same for every stick and trigger */
	private fun fineSection(c: LinearLayout, s: ConsolePadSettings)
	{
		slider(c, str(R.string.console_test_stick_travel), 50, 250, (s.stickTravelMm * 10).toInt(), { "%.1f mm".format(it / 10f) }) {
			putInt(ConsolePadSettings.KEY_STICK_TRAVEL, it)
		}
		note(c, str(R.string.console_fine_travel_hint))
		switch(c, str(R.string.console_test_follow), s.follow) { putBoolean(ConsolePadSettings.KEY_FOLLOW, it) }
		slider(c, str(R.string.console_fine_coyote), 80, 400, s.coyoteMs.toInt(), { "$it ms" }) {
			putInt(ConsolePadSettings.KEY_COYOTE, it)
		}
		note(c, str(R.string.console_fine_coyote_hint))
		slider(c, str(R.string.console_fine_squash), 110, 400, (s.squashRatio * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_SQUASH, it)
		}
		note(c, str(R.string.console_fine_squash_hint))
		slider(c, str(R.string.console_fine_trigger_base), 0, 90, (s.triggerBase * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_TRIGGER_BASE, it)
		}
		note(c, str(R.string.console_fine_trigger_base_hint))
		slider(c, str(R.string.console_fine_trigger_full), 110, 300, (s.triggerFullRatio * 100).toInt(), { "$it %" }) {
			putInt(ConsolePadSettings.KEY_TRIGGER_FULL, it)
		}
		note(c, str(R.string.console_fine_trigger_full_hint))
		// only for the test screen, not saved: the stream never shows them
		if(testScreen)
			switch(c, str(R.string.console_test_show_zones), pad.showZones) { pad.showZones = it }
	}

	/** a closed or open section: its title with what it is set to, and its options under it when open */
	private fun section(parent: ViewGroup, key: String, title: String, summary: String, build: (LinearLayout) -> Unit)
	{
		val open = openSection == key
		parent.addView(View(activity).apply { setBackgroundColor(0x33FFFFFF) },
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(6f) })
		parent.addView(LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(0, dp(8f), 0, dp(6f))
			isClickable = true
			setOnClickListener { openSection = if(open) null else key; buildSettings() }
			addView(TextView(activity).apply {
				text = (if(open) "▾ " else "▸ ") + title
				setTextColor(gold)
				textSize = 16f
				setTypeface(typeface, Typeface.BOLD)
			})
			if(!open && summary.isNotEmpty())
				addView(TextView(activity).apply {
					text = summary
					setTextColor(0xFF999999.toInt())
					textSize = 12f
				})
		})
		if(open)
		{
			val box = LinearLayout(activity).apply {
				orientation = LinearLayout.VERTICAL
				setPadding(dp(8f), 0, 0, dp(8f))
			}
			parent.addView(box)
			build(box)
		}
	}

	/** one line of grey text under an option: what it does */
	private fun note(parent: ViewGroup, text: String)
	{
		parent.addView(TextView(activity).apply {
			this.text = text
			setTextColor(0xFF999999.toInt())
			textSize = 12f
			setPadding(0, dp(2f), 0, dp(4f))
		})
	}

	/** an option that opens a dialog to choose: its title and, as a button, what is chosen now */
	private fun pickerRow(parent: ViewGroup, label: String, current: String, onClick: () -> Unit)
	{
		title(parent, label)
		parent.addView(textButton("$current  ▸", onClick).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL },
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
	}

	private fun header(parent: ViewGroup, text: String)
	{
		parent.addView(TextView(activity).apply {
			this.text = text
			setTextColor(gold)
			textSize = 15f
			setTypeface(typeface, Typeface.BOLD)
			setPadding(0, dp(12f), 0, dp(4f))
		})
	}

	private fun title(parent: ViewGroup, text: String) = TextView(activity).apply {
		this.text = text
		setTextColor(0xFFFFFFFF.toInt())
		textSize = 14f
		setPadding(0, dp(8f), 0, 0)
	}.also { parent.addView(it) }

	private fun radios(parent: ViewGroup, label: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit)
	{
		title(parent, label)
		// in a row when they fit the panel, otherwise one under the other
		val long = options.sumOf { it.second.length + 4 } > 34
		val group = RadioGroup(activity).apply { orientation = if(long) RadioGroup.VERTICAL else RadioGroup.HORIZONTAL }
		options.forEach { (value, text) ->
			group.addView(RadioButton(activity).apply {
				id = View.generateViewId()
				this.text = text
				textSize = 13f
				setTextColor(0xFFDDDDDD.toInt())
				buttonTintList = ColorStateList.valueOf(gold)
				isChecked = value == current
				setPadding(0, 0, dp(6f), 0)
				setOnCheckedChangeListener { _, checked -> if(checked) onPick(value) }
			})
		}
		parent.addView(group)
	}

	private fun slider(parent: ViewGroup, label: String, min: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit)
	{
		val t = title(parent, "$label: ${format(value)}")
		parent.addView(SeekBar(activity).apply {
			this.max = max - min
			progress = (value - min).coerceIn(0, max - min)
			progressTintList = ColorStateList.valueOf(gold)
			thumbTintList = ColorStateList.valueOf(gold)
			setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener
			{
				override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean)
				{
					t.text = "$label: ${format(progress + min)}"
					if(fromUser) onChange(progress + min)
				}
				override fun onStartTrackingTouch(seekBar: SeekBar?) {}
				override fun onStopTrackingTouch(seekBar: SeekBar?) {}
			})
		}, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
	}

	private fun switch(parent: ViewGroup, label: String, checked: Boolean, onChange: (Boolean) -> Unit)
	{
		parent.addView(SwitchCompat(activity).apply {
			text = label
			textSize = 14f
			setTextColor(0xFFFFFFFF.toInt())
			isChecked = checked
			thumbTintList = ColorStateList.valueOf(gold)
			setPadding(0, dp(8f), 0, 0)
			setOnCheckedChangeListener { _, v -> onChange(v) }
		})
	}
}
