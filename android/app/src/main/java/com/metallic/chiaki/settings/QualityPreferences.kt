// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.LossReport
import com.metallic.chiaki.common.QualityValues

/** A slider whose value is only written when the finger lets go (or a button sets it). */
private fun SeekBar.onRelease(moving: (Int) -> Unit, released: (Int) -> Unit)
{
	setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener
	{
		override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) { if(fromUser) moving(progress) }
		override fun onStartTrackingTouch(seekBar: SeekBar) {}
		override fun onStopTrackingTouch(seekBar: SeekBar) = released(seekBar.progress)
	})
}

/**
 * Bitrate of the quality profile in Mbit/s (2-100): slider to get close, − and + for steps of 1,
 * tap the number to type it. ↺ goes back to the profile's own value ([onReset]).
 */
class BitratePreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): Preference(context, attrs)
{
	init
	{
		layoutResource = R.layout.preference_bitrate
		isSelectable = false
	}

	var onReset: (() -> Unit)? = null

	private var current = QualityValues.MIN_MBPS

	override fun onSetInitialValue(defaultValue: Any?) { current = getPersistedInt(current) }

	/** shows the stored value again after someone wrote it directly */
	fun reload()
	{
		current = getPersistedInt(current)
		notifyChanged()
	}

	private fun set(mbps: Int)
	{
		val v = mbps.coerceIn(QualityValues.MIN_MBPS, QualityValues.MAX_MBPS)
		if(v == current || !callChangeListener(v))
		{
			notifyChanged()
			return
		}
		current = v
		persistInt(v)
		notifyChanged()
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder)
	{
		super.onBindViewHolder(holder)
		val seek = holder.findViewById(R.id.seekbar) as SeekBar
		val label = holder.findViewById(R.id.seekbar_value) as TextView
		seek.max = QualityValues.MAX_MBPS - QualityValues.MIN_MBPS
		seek.progress = current - QualityValues.MIN_MBPS
		label.text = current.toString()
		seek.onRelease({ label.text = (it + QualityValues.MIN_MBPS).toString() }, { set(it + QualityValues.MIN_MBPS) })
		holder.findViewById(R.id.bitrate_minus).setOnClickListener { set(current - 1) }
		holder.findViewById(R.id.bitrate_plus).setOnClickListener { set(current + 1) }
		label.setOnClickListener { askNumber() }
		holder.findViewById(R.id.slider_reset).setOnClickListener { onReset?.invoke() }
	}

	private fun askNumber()
	{
		val input = EditText(context).apply {
			inputType = InputType.TYPE_CLASS_NUMBER
			setText(current.toString())
			selectAll()
			hint = context.getString(R.string.quality_bitrate_range, QualityValues.MIN_MBPS, QualityValues.MAX_MBPS)
		}
		val pad = (20 * context.resources.displayMetrics.density).toInt()
		val frame = android.widget.FrameLayout(context).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
		MaterialAlertDialogBuilder(context)
			.setTitle(R.string.quality_bitrate_dialog_title)
			.setView(frame)
			.setPositiveButton(android.R.string.ok) { _, _ -> input.text.toString().trim().toIntOrNull()?.let { set(it) } }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}
}

/**
 * How much loss the console may be told at most: a slider with the stops of LossReport.STEPS
 * (No · 1 % … 5 % · All) and, below, what the stop under the finger does.
 */
class LossReportPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): Preference(context, attrs)
{
	init
	{
		layoutResource = R.layout.preference_loss_report
		isSelectable = false
	}

	private var current = LossReport.DEFAULT_STEP

	override fun onSetInitialValue(defaultValue: Any?) { current = getPersistedInt(current) }

	private fun stepName(step: Int) = LossReport.stepName(context, step)

	private fun explanation(step: Int) = context.resources.getStringArray(R.array.loss_report_explanations)[step.coerceIn(0, LossReport.LAST)]

	override fun onBindViewHolder(holder: PreferenceViewHolder)
	{
		super.onBindViewHolder(holder)
		val seek = holder.findViewById(R.id.seekbar) as SeekBar
		val label = holder.findViewById(R.id.seekbar_value) as TextView
		val explain = holder.findViewById(R.id.loss_explanation) as TextView
		fun show(step: Int)
		{
			label.text = stepName(step)
			explain.text = explanation(step)
		}
		seek.max = LossReport.LAST
		seek.progress = current
		show(current)
		seek.onRelease({ show(it) }) { step ->
			if(step != current && callChangeListener(step))
			{
				current = step
				persistInt(step)
			}
			show(current)
			seek.progress = current
		}
	}
}
