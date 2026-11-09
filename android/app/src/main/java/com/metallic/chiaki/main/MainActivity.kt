// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.session.KeptSession
import com.metallic.chiaki.session.SessionExits
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : AppCompatActivity()
{
	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null
	/** the account's color glowing behind the cards */
	private lateinit var halo: AccountHaloDecoration

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		title = ""
		setSupportActionBar(binding.toolbar)
		// the version and build, always visible, so it is clear which one is being tested
		supportActionBar?.subtitle = "v${com.metallic.chiaki.BuildConfig.VERSION_NAME} (${com.metallic.chiaki.BuildConfig.VERSION_CODE})" +
			(if(com.metallic.chiaki.BuildConfig.DEBUG) " · debug" else "")

		binding.floatingActionButton.setOnClickListener {
			expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
		}
		binding.floatingActionButtonDialBackground.setOnClickListener {
			expandFloatingActionButton(false)
		}

		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.addManualLabelButton.setOnClickListener { addManualConsole() }

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.registerLabelButton.setOnClickListener { showRegistration() }

		binding.qualityPill.setOnClickListener { showQualitySheet() }
		binding.tipCard.setOnClickListener {
			Tips.markRead(this)
			binding.tipCard.animate().alpha(0f).setDuration(200).withEndAction { binding.tipCard.visibility = View.GONE }.start()
			updateTipsIcon()
		}
		binding.accountPill.setOnClickListener { com.metallic.chiaki.regist.AccountSheet.show(this) }

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this), applicationContext) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::cancelWakeup, this::closeKeptSession, this::editHost, this::deleteHost)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
		// busy cards are rebound every second (their seconds): the default cross-fade on each
		// change made them blink
		(binding.hostsRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
		halo = AccountHaloDecoration(binding.hostsRecyclerView)
		binding.hostsRecyclerView.addItemDecoration(halo)
		// only now: the listener paints the halo (told before it existed, 2.6.5 closed on start)
		PsnAccounts.addListener(accountsListener)
		viewModel.consoleActivity.state.observe(this, Observer { recyclerViewAdapter.activities = it })
		// a stream paused in the background (quick reconnect) shows on its console's card
		KeptSession.state.observe(this, Observer {
			viewModel.consoleActivity.setKept(it)
			viewModel.consoleActivity.update(recyclerViewAdapter.hosts)
		})
		viewModel.consoleActivity.onReadyToConnect = { id ->
			recyclerViewAdapter.hosts
				.filter { it.registeredHost?.id == id }
				.let { hosts -> hosts.firstOrNull { it.live?.state == DiscoveryHost.State.READY } ?: hosts.firstOrNull() }
				?.let { connectHost(it) }
		}
		// Auto quality: the card says what the next connection would ask for ("At home · 720p")
		recyclerViewAdapter.autoLabel = this::autoLabel
		com.metallic.chiaki.discovery.RttMeter.listener = { recyclerViewAdapter.refreshAutoLabels() }
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
			viewModel.consoleActivity.update(it)
			recyclerViewAdapter.hosts = it
			com.metallic.chiaki.discovery.RttMeter.setTargets(it
				.filter { host -> host.registeredHost != null && host.host.isNotEmpty() }
				.map { host -> com.metallic.chiaki.discovery.RttMeter.Target(host.host, host.isPS5) }.toSet())
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
		})

		viewModel.discoveryActive.observe(this, Observer { active: Boolean ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})
		viewModel.searchRunning.observe(this, Observer { updateEmptyInfo() })

		// a fresh install says hello once (afterwards the light bulb opens the same sheet)
		if(savedInstanceState == null && Tips.takeWelcome(this))
			binding.root.post { showWelcome(fromBulb = false) }

		// pull down = look for consoles now and measure the network again (Auto); the circle stays
		// while the quick measuring runs, so it feels like what it is
		binding.hostsRefresh.setColorSchemeColors(androidx.core.content.ContextCompat.getColor(this, R.color.accent))
		binding.hostsRefresh.setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#303030"))
		binding.hostsRefresh.setOnRefreshListener {
			viewModel.refresh()
			binding.hostsRefresh.removeCallbacks(refreshDone)
			binding.hostsRefresh.postDelayed(refreshDone, REFRESH_MS)
		}
	}

	companion object
	{
		/** as long as the quick measuring after pulling the list down (RttMeter's burst) */
		private const val REFRESH_MS = 1600L
	}

	private val refreshDone = Runnable {
		binding.hostsRefresh.isRefreshing = false
		(binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter)?.refreshAutoLabels()
	}

	override fun onResume()
	{
		super.onResume()
		// a stream killed mid-game may have left the phone in Do not disturb: undo it
		com.metallic.chiaki.stream.StreamDnd.stop(this)
		// the profile may have changed in Settings
		updateQualityPill()
		updateAccountPill()
		showTip()
		val adapter = binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter ?: return
		adapter.cardAnimation = Preferences(this).cardAnimation
		// a stream that ended (just now, or while on screen when a paused one runs out of time):
		// its console's card shows it disconnecting or suspending
		SessionExits.listener = {
			viewModel.consoleActivity.reportExit(it)
			viewModel.consoleActivity.update(adapter.hosts)
		}
	}

	override fun onPause()
	{
		super.onPause()
		SessionExits.listener = null
	}

	override fun onDestroy()
	{
		super.onDestroy()
		// the view model outlives this activity on rotation; the new one sets its own callback
		viewModel.consoleActivity.onReadyToConnect = null
		com.metallic.chiaki.discovery.RttMeter.listener = null
		PsnAccounts.removeListener(accountsListener)
	}

	private val accountsListener: () -> Unit = { updateAccountPill() }

	private var tipsMenuItem: MenuItem? = null

	/** Today's tip, if there is one to read (a new one each day; Settings can turn them off) */
	private fun showTip()
	{
		val tip = Tips.current(this)
		if(tip == null)
			binding.tipCard.visibility = View.GONE
		else if(binding.tipCard.visibility != View.VISIBLE)
		{
			binding.tipText.text = tip
			binding.tipCard.alpha = 0f
			binding.tipCard.visibility = View.VISIBLE
			binding.tipCard.animate().alpha(1f).setDuration(300).start()
		}
		updateTipsIcon()
	}

	private fun updateTipsIcon()
	{
		val item = tipsMenuItem ?: return
		item.setIcon(if(Tips.hasUnread(this)) R.drawable.ic_tip_new else R.drawable.ic_tip)
	}

	/**
	 * The light bulb (and, once, a fresh install's first opening): a short welcome to what the app
	 * is about, the daily tip on or off, and every tip, in their usual order.
	 */
	private fun showWelcome(fromBulb: Boolean = true)
	{
		// opened by itself, today's tip (the first, Optical) still shows after the welcome
		if(fromBulb)
		{
			Tips.markRead(this)
			binding.tipCard.visibility = View.GONE
			updateTipsIcon()
		}
		val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
		val view = layoutInflater.inflate(R.layout.dialog_welcome, null)
		val toggle = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.welcomeTipsToggle)
		toggle.isChecked = Tips.enabled(this)
		toggle.setOnCheckedChangeListener { _, checked ->
			Tips.setEnabled(this, checked)
			if(!checked)
				binding.tipCard.visibility = View.GONE
			updateTipsIcon()
		}
		view.findViewById<android.widget.TextView>(R.id.welcomeTips).text =
			Tips.all(this).joinToString("\n\n") { "•  $it" }
		view.findViewById<View>(R.id.welcomeDone).setOnClickListener { dialog.dismiss() }
		dialog.setContentView(view)
		dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
		dialog.behavior.skipCollapsed = true
		dialog.setOnDismissListener { showTip() }
		dialog.show()
	}

	/** the account color shown last (to fade only when it really changes) */
	private var shownAccountColor: Int? = null
	private var accountColorShown = false

	/**
	 * "● Account · Name": which PlayStation account connects (shown once one is saved). Its color
	 * tints the pill and glows behind the cards (the main screen's theme; the rest of the app keeps
	 * its colors, settings are global).
	 */
	private fun updateAccountPill()
	{
		val account = PsnAccounts(this).active
		binding.accountPill.visibility = if(account != null) View.VISIBLE else View.GONE
		val color = account?.colorValue
		if(account != null && color != null)
		{
			val dot = "● "
			val text = android.text.SpannableString(dot + getString(R.string.account_pill_label, account.name))
			text.setSpan(android.text.style.ForegroundColorSpan(color), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
			binding.accountPill.text = text
			binding.accountPill.strokeColor = android.content.res.ColorStateList.valueOf(color)
		}
		if(!accountColorShown || color != shownAccountColor)
		{
			halo.setColor(color, animate = accountColorShown)
			shownAccountColor = color
			accountColorShown = true
		}
	}

	/** "Quality · Balanced* ●●●○" */
	private fun updateQualityPill()
	{
		val prefs = Preferences(this)
		val profile = prefs.qualityProfile
		val dots = QualityProfile.dots(profile.weight)
		// Auto with a fixed resolution says which: "Quality · Auto 1080p"
		val fixed = if(profile == QualityProfile.AUTO) AutoQuality.userResolution(prefs)?.let { " ${it.value}" } ?: "" else ""
		binding.qualityPill.text = getString(R.string.quality_pill_label) + " · " + getString(profile.title) + fixed +
			(if(profile != QualityProfile.AUTO && prefs.qualityEdited()) "*" else "") + (if(dots.isNotEmpty()) "  $dots" else "")
		(binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter)?.refreshAutoLabels()
	}

	/**
	 * The quality profiles for the next connection, chosen with one tap (applied at once, like the
	 * color presets in the game). Adjust opens Settings, where the values and the loss report live.
	 */
	private fun showQualitySheet()
	{
		val prefs = Preferences(this)
		val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
		val view = layoutInflater.inflate(R.layout.dialog_quality, null)
		val list = view.findViewById<android.widget.LinearLayout>(R.id.qualityList)
		fun fill()
		{
			list.removeAllViews()
			val current = prefs.qualityProfile
			for(profile in QualityProfile.values())
			{
				val row = layoutInflater.inflate(R.layout.item_quality_profile, list, false)
				val values = prefs.storedQuality(profile)
				row.findViewById<android.widget.RadioButton>(R.id.qualityRadio).isChecked = profile == current
				row.findViewById<android.widget.TextView>(R.id.qualityName).text =
					getString(profile.title) + if(profile != QualityProfile.AUTO && prefs.qualityEdited(profile)) "*" else ""
				row.findViewById<android.widget.TextView>(R.id.qualityDots).text = QualityProfile.dots(profile.weight)
				row.findViewById<android.widget.TextView>(R.id.qualityValues).text =
					if(profile == QualityProfile.AUTO)
						getString(R.string.quality_profile_auto_values,
							AutoQuality.userResolution(prefs)?.value ?: getString(R.string.quality_auto_resolution_auto))
					else
						getString(R.string.quality_profile_values, values.resolution.value, values.bitrateMbps)
				row.findViewById<android.widget.TextView>(R.id.qualityDescription).setText(profile.description)
				row.setOnClickListener {
					prefs.selectQuality(profile)
					fill()
					updateQualityPill()
				}
				list.addView(row)
			}
			fillAuto(view, prefs) { fill(); updateQualityPill() }
		}
		fill()
		view.findViewById<android.widget.TextView>(R.id.qualityLoss).text =
			getString(R.string.quality_sheet_loss, LossReport.stepName(this, prefs.lossReportStep))
		view.findViewById<View>(R.id.qualityAdjust).setOnClickListener {
			dialog.dismiss()
			startActivity(SettingsActivity.pageIntent(this, "screen_stream", getString(R.string.settings_page_stream)))
		}
		view.findViewById<View>(R.id.qualityDone).setOnClickListener { dialog.dismiss() }
		dialog.setContentView(view)
		// open whole (a short landscape screen would otherwise show only the top)
		dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
		dialog.behavior.skipCollapsed = true
		dialog.show()
	}

	/**
	 * Auto in the quality sheet: the resolution the user wants (Auto or one) and, for each console
	 * that can be reached, what the next connection would ask for and why.
	 */
	private fun fillAuto(view: View, prefs: Preferences, changed: () -> Unit)
	{
		val box = view.findViewById<View>(R.id.qualityAutoBox)
		if(prefs.qualityProfile != QualityProfile.AUTO)
		{
			box.visibility = View.GONE
			return
		}
		box.visibility = View.VISIBLE
		val buttons = view.findViewById<android.widget.LinearLayout>(R.id.qualityAutoResolutions)
		buttons.removeAllViews()
		val chosen = AutoQuality.userResolution(prefs)
		val choices = listOf<Preferences.Resolution?>(null) + Preferences.Resolution.values().reversed()
		for(choice in choices)
		{
			val button = com.google.android.material.button.MaterialButton(this, null,
				com.google.android.material.R.attr.materialButtonOutlinedStyle)
			button.text = choice?.value ?: getString(R.string.quality_auto_resolution_auto)
			button.isAllCaps = false
			button.minWidth = 0
			button.minimumWidth = 0
			val selected = choice == chosen
			if(selected)
			{
				button.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent))
				button.setTextColor(android.graphics.Color.BLACK)
			}
			else
				button.setTextColor(android.graphics.Color.WHITE)
			button.setOnClickListener {
				AutoQuality.setUserResolution(prefs, choice)
				changed()
			}
			buttons.addView(button, android.widget.LinearLayout.LayoutParams(
				android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
				it.marginEnd = (6 * resources.displayMetrics.density).toInt()
			})
		}

		val hosts = (binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter)?.hosts.orEmpty()
			.filter { it.registeredHost != null && (it.reach == Reach.HOME || it.reach == Reach.VPN || it.reach == Reach.DIRECT) }
		view.findViewById<android.widget.TextView>(R.id.qualityAutoNow).text =
			if(hosts.isEmpty())
				getString(R.string.quality_auto_no_console)
			else
				hosts.joinToString("\n\n") { host ->
					val d = AutoQuality.decide(this, prefs, host)
					val reasons = d.reasons.joinToString(" · ") { r -> if(r.arg != null) getString(r.text, r.arg) else getString(r.text) }
					getString(R.string.quality_auto_now, host.name ?: host.host, (if(d.guess) "~" else "") + d.resolution.value, d.bitrateMbps) +
						"\n" + reasons
				}
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.searchRunning.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
		com.metallic.chiaki.discovery.RttMeter.start()
	}

	override fun onStop()
	{
		super.onStop()
		binding.hostsRefresh.removeCallbacks(refreshDone)
		binding.hostsRefresh.isRefreshing = false
		viewModel.discoveryManager.pause()
		com.metallic.chiaki.discovery.RttMeter.stop()
	}

	/** "720p" (or "~720p" while the console's answers are not known) for a reachable console in the Auto profile */
	private fun autoLabel(host: DisplayHost): String?
	{
		val prefs = Preferences(this)
		if(prefs.qualityProfile != QualityProfile.AUTO || host.registeredHost == null)
			return null
		if(host.reach != Reach.HOME && host.reach != Reach.VPN && host.reach != Reach.DIRECT)
			return null
		val decision = AutoQuality.decide(this, prefs, host)
		return (if(decision.guess) "~" else "") + decision.resolution.value
	}

	override fun onBackPressed()
	{
		if(binding.floatingActionButton.isExpanded)
		{
			expandFloatingActionButton(false)
			return
		}
		super.onBackPressed()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		tipsMenuItem = menu.findItem(R.id.action_tips)
		updateTipsIcon()
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_tips ->
		{
			showWelcome()
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		// A console busy waking up, disconnecting or suspending only says "wait" (its wakeup can
		// be cancelled from the card's menu): an anxious tap never interrupts anything
		val registeredHostId = host.registeredHost?.id
		if(registeredHostId != null && viewModel.consoleActivity[registeredHostId]?.busy == true)
		{
			(binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter)?.nope(registeredHostId)
			return
		}

		// Explain why it will probably fail instead of waiting for the connection to time out
		val unreachableMessage = when(host.reach)
		{
			Reach.UNREACHABLE -> R.string.alert_message_unreachable
			Reach.UNREACHABLE_VPN -> R.string.alert_message_unreachable_vpn
			else -> null
		}
		if(unreachableMessage != null)
		{
			MaterialAlertDialogBuilder(this)
				.setMessage(unreachableMessage)
				.setPositiveButton(R.string.action_try_anyway) { _, _ -> openHost(host) }
				.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
				.create()
				.show()
		}
		else
			openHost(host)
	}

	private fun openHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			// Connecting to a console in standby can only fail: offer to wake it up first
			// (the fork offered "Connect" here, which never worked)
			if(host.live?.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup_connect) { _, _ ->
						viewModel.consoleActivity.startWakeup(host, connect = true)
					}
					.setNeutralButton(R.string.action_wakeup_only) { _, _ ->
						viewModel.consoleActivity.startWakeup(host, connect = false)
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.show()
			}
			else
				connectHost(host)
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun connectHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		val prefs = Preferences(this)
		val (videoProfile, resolutionsMask) =
			if(prefs.qualityProfile == QualityProfile.AUTO)
			{
				// what the card showed, decided again now (the same unless something changed this very second)
				val decision = AutoQuality.decide(this, prefs, host)
				val adapter = binding.hostsRecyclerView.adapter as? DisplayHostRecyclerViewAdapter
				val shown = adapter?.shownAutoLabel(host)
				val now = (if(decision.guess) "~" else "") + decision.resolution.value
				if(shown != null && shown.removePrefix("~") != now.removePrefix("~"))
					android.widget.Toast.makeText(this, getString(R.string.auto_changed_toast, decision.resolution.value, decision.bitrateMbps),
						android.widget.Toast.LENGTH_SHORT).show()
				prefs.connectVideoRequest(decision)
			}
			else
				prefs.connectVideoRequest()
		val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, videoProfile,
			prefs.packetLossMax, resolutionsMask, enableDualsense = prefs.rumbleEnabled && prefs.rumblePs5Haptics)
		Intent(this, StreamActivity::class.java).let {
			it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
			startActivity(it)
		}
	}

	private fun wakeupHost(host: DisplayHost) = viewModel.consoleActivity.startWakeup(host, connect = false)

	private fun closeKeptSession(host: DisplayHost)
	{
		val registKey = host.registeredHost?.rpRegistKey ?: return
		if(KeptSession.info?.connectInfo?.registKey?.contentEquals(registKey) == true)
			KeptSession.end()
	}

	private fun cancelWakeup(host: DisplayHost)
	{
		host.registeredHost?.id?.let { viewModel.consoleActivity.cancel(it) }
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
