// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R

/**
 * "Which button?": a list of every button of the controller in groups, one tap picks it. Used by
 * what squashing a stick holds and what pressing L2 / R2 harder holds.
 */
object PadButtonDialog
{
	internal val groupNames = intArrayOf(
		R.string.console_btn_group_sticks, R.string.console_btn_group_triggers, R.string.console_btn_group_shoulders,
		R.string.console_btn_group_face, R.string.console_btn_group_dpad, R.string.console_btn_group_system)

	private const val GOLD = 0xFFD4AB55.toInt()

	/** the label shown for a choice (null = nothing) */
	fun name(activity: AppCompatActivity, b: PadButton?) = b?.label ?: activity.getString(R.string.console_btn_none)

	/**
	 * [exclude]: a button that cannot be picked here (a trigger holding itself); [allowNone]: offer "nothing".
	 * [onPick] gets the choice, null = nothing.
	 */
	fun show(activity: AppCompatActivity, title: Int, current: PadButton?, exclude: PadButton?, allowNone: Boolean,
		onPick: (PadButton?) -> Unit)
	{
		val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
		val column = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
		}
		lateinit var dialog: androidx.appcompat.app.AlertDialog
		fun chip(text: String, selected: Boolean, pick: () -> Unit) =
			MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
				this.text = text
				minWidth = 0
				minimumWidth = 0
				textSize = 14f
				isAllCaps = false
				strokeWidth = dp(1f)
				// explicit colours: the dialog's own made the text hard to read
				strokeColor = ColorStateList.valueOf(if(selected) GOLD else 0x88FFFFFF.toInt())
				backgroundTintList = ColorStateList.valueOf(if(selected) GOLD else 0x00000000)
				setTextColor(if(selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
				setOnClickListener { dialog.dismiss(); pick() }
			}
		fun row(chips: List<MaterialButton>) = LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			chips.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
		}.also { column.addView(it) }

		if(allowNone)
			row(listOf(chip(activity.getString(R.string.console_btn_none), current == null) { onPick(null) }))
		for(group in groupNames.indices)
		{
			val buttons = PadButton.values().filter { it.group == group && it != exclude }
			if(buttons.isEmpty())
				continue
			column.addView(TextView(activity).apply {
				text = activity.getString(groupNames[group])
				textSize = 13f
				setTextColor(GOLD)
				setPadding(0, dp(12f), 0, dp(2f))
			})
			row(buttons.map { b -> chip(b.label, b == current) { onPick(b) } })
		}
		dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(title)
			.setView(ScrollView(activity).apply { addView(column) })
			.setNegativeButton(android.R.string.cancel, null)
			.create()
		// a dark panel of our own, so the white and gold texts read the same on any phone theme
		dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
			setColor(0xFF1E2024.toInt())
			cornerRadius = dp(20f).toFloat()
		})
		dialog.show()
		dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(GOLD)
	}
}
