// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ImageSettings

/** Slider with a unit after the value and a ↺ button that restores the default from ImageSettings. */
class SliderPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): SeekBarPreference(context, attrs)
{
	init
	{
		layoutResource = R.layout.preference_slider
	}

	/** Replaces the plain ↺ (the fixed default); the color presets restore their own values. */
	var onReset: (() -> Unit)? = null

	/** Shows the stored value again after someone wrote it directly (not through the slider). */
	fun reload()
	{
		showStored()
		notifyChanged() // the label may show decimals even when the position did not move
	}

	override fun onSetInitialValue(defaultValue: Any?) = showStored()

	/**
	 * Moves the slider to the stored value without writing it back: a value beyond the slider's
	 * range (an imported look) would otherwise be overwritten by the end of the slider.
	 */
	private fun showStored()
	{
		val stored = getPersistedInt(value)
		isPersistent = false
		value = stored
		isPersistent = true
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder)
	{
		super.onBindViewHolder(holder)
		(holder.findViewById(R.id.slider_unit) as TextView).text = ImageSettings.slider(key)?.unit ?: "%"
		(preferenceDataStore as? DataStore)?.label(key)?.let { (holder.findViewById(R.id.seekbar_value) as TextView).text = it }
		holder.findViewById(R.id.slider_reset).setOnClickListener {
			onReset?.let { reset ->
				reset()
				return@setOnClickListener
			}
			val value = ImageSettings.slider(key)?.default ?: min
			if(callChangeListener(value))
				this.value = value
		}
	}
}
