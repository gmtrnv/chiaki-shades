// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R

/**
 * "Which inputs?" for the gyroscope stick's activation: a tick for each stick (moved) and each button of
 * the controller; the ones ticked must all be held at once. At least one.
 */
object GyroInputsDialog
{
	private const val GOLD = 0xFFD4AB55.toInt()

	fun label(activity: AppCompatActivity, id: String) = when(id)
	{
		GyroInputs.STICK_L -> activity.getString(R.string.gyro_input_stick_l_short)
		GyroInputs.STICK_R -> activity.getString(R.string.gyro_input_stick_r_short)
		else -> PadButton.from(id)?.label ?: id
	}

	/** "L3 + R3" */
	fun summary(activity: AppCompatActivity, ids: List<String>) = ids.joinToString(" + ") { label(activity, it) }

	fun show(activity: AppCompatActivity, current: List<String>, onPick: (List<String>) -> Unit)
	{
		val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt() }
		val column = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
		}
		val boxes = LinkedHashMap<String, CheckBox>()
		fun box(id: String, text: String) = CheckBox(activity).apply {
			this.text = text
			textSize = 14f
			setTextColor(0xFFFFFFFF.toInt())
			buttonTintList = ColorStateList.valueOf(GOLD)
			isChecked = id in current
			boxes[id] = this
		}
		fun header(text: String) = column.addView(TextView(activity).apply {
			this.text = text
			textSize = 13f
			setTextColor(GOLD)
			setPadding(0, dp(12f), 0, dp(2f))
		})
		fun row(items: List<CheckBox>) = column.addView(LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			items.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
		})

		header(activity.getString(R.string.gyro_input_group_sticks))
		row(listOf(box(GyroInputs.STICK_L, activity.getString(R.string.gyro_input_stick_l)),
			box(GyroInputs.STICK_R, activity.getString(R.string.gyro_input_stick_r))))
		for(group in PadButtonDialog.groupNames.indices)
		{
			val buttons = PadButton.values().filter { it.group == group }
			header(activity.getString(PadButtonDialog.groupNames[group]))
			row(buttons.map { box(it.value, it.label) })
		}
		val hint = TextView(activity).apply {
			text = activity.getString(R.string.gyro_inputs_hint)
			textSize = 12f
			setTextColor(0xFF999999.toInt())
			setPadding(0, dp(12f), 0, 0)
		}
		column.addView(hint)

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(R.string.gyro_pick_inputs)
			.setView(ScrollView(activity).apply { addView(column) })
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, null)
			.create()
		dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
			setColor(0xFF1E2024.toInt())
			cornerRadius = dp(20f).toFloat()
		})
		dialog.show()
		dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setTextColor(GOLD)
		dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.apply {
			setTextColor(GOLD)
			// set here, so an empty choice keeps the dialog open
			setOnClickListener {
				val ids = boxes.filter { it.value.isChecked }.keys
				if(ids.isEmpty())
					Toast.makeText(activity, R.string.gyro_inputs_min, Toast.LENGTH_SHORT).show()
				else
				{
					dialog.dismiss()
					onPick(GyroInputs.order(ids))
				}
			}
		}
	}
}
