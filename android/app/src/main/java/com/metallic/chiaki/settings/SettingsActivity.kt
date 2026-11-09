// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivitySettingsBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

/**
 * Settings: the main menu, or one of its pages. Each page (a PreferenceScreen in preferences.xml, or the
 * registered consoles and the logs) opens as its own SettingsActivity, so every page comes in with the
 * system's own screen animation, like the physical controller's panel (2.6.0-rc3; they used to fade
 * inside one screen).
 */
class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
	PreferenceFragmentCompat.OnPreferenceStartScreenCallback
{
	companion object
	{
		const val EXTRA_PAGE = "settings_page"
		const val EXTRA_PAGE_TITLE = "settings_page_title"
		private const val EXTRA_FRAGMENT = "settings_fragment"

		/** Settings opened on one page (e.g. the quality panel's "Settings" → Stream) */
		fun pageIntent(context: Context, key: String, title: String?) = Intent(context, SettingsActivity::class.java)
			.putExtra(EXTRA_PAGE, key)
			.putExtra(EXTRA_PAGE_TITLE, title)
	}

	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		title = ""
		setSupportActionBar(binding.toolbar)

		val page = intent.getStringExtra(EXTRA_PAGE)
		val fragment: Fragment = when(intent.getStringExtra(EXTRA_FRAGMENT))
		{
			SettingsRegisteredHostsFragment::class.java.name -> SettingsRegisteredHostsFragment()
			SettingsLogsFragment::class.java.name -> SettingsLogsFragment()
			else -> if(page != null) SettingsFragment.page(page, intent.getStringExtra(EXTRA_PAGE_TITLE)) else SettingsFragment()
		}
		supportFragmentManager.beginTransaction()
			.replace(R.id.settingsFragment, fragment)
			.commit()
		binding.titleTextView.text = (fragment as? TitleFragment)?.getTitle(resources) ?: ""
	}

	override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean
	{
		startActivity(pageIntent(this, pref.key, pref.title?.toString()))
		return true
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean
	{
		val name = pref.fragment ?: return false
		if(name != SettingsRegisteredHostsFragment::class.java.name && name != SettingsLogsFragment::class.java.name)
			return false
		startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_FRAGMENT, name))
		return true
	}
}
