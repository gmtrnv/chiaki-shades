// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.animation.ObjectAnimator
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.CardAnimation
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.Reach
import com.metallic.chiaki.common.ext.inflate
import com.metallic.chiaki.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.main.ConsoleActivityTracker.Activity
import com.metallic.chiaki.main.ConsoleActivityTracker.Phase

/**
 * Which console a card is for, whatever it says right now. The fork compared whole cards here, so
 * any change (the console going from rest to ready while waking, back to rest when suspending, a
 * game starting, the reach label) counted as one card removed and another added: a cross-fade
 * that made the card blink, often in the middle of its animation. Now those are in-place changes.
 */
private val DisplayHost.cardKey: Any get() =
	registeredHost?.id?.let { "registered:$it" } ?: when(this)
	{
		is ManualDisplayHost -> "manual:${manualHost.id}"
		is DiscoveredDisplayHost -> "discovered:${id ?: host}"
	}

class DisplayHostDiffCallback(val old: List<DisplayHost>, val new: List<DisplayHost>): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = old[oldItemPosition].cardKey == new[newItemPosition].cardKey
	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	val clickCallback: (DisplayHost) -> Unit,
	val wakeupCallback: (DisplayHost) -> Unit,
	val cancelWakeupCallback: (DisplayHost) -> Unit,
	val closeKeptCallback: (DisplayHost) -> Unit,
	val editCallback: (DisplayHost) -> Unit,
	val deleteCallback: (DisplayHost) -> Unit
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var hosts: List<DisplayHost> = listOf()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(DisplayHostDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	/**
	 * What each console is going through (by registered host id): waking up, disconnecting,
	 * suspending. Its card shows that instead of where it was seen, and animates.
	 */
	var activities: Map<Long, Activity> = mapOf()
		set(value)
		{
			// the busy ones change their seconds every time
			val changed = (field.keys + value.keys).filter { field[it] != value[it] || value[it]?.busy == true || value[it]?.phase == Phase.PAUSED }.toSet()
			field = value
			notifyHosts(changed)
		}

	var cardAnimation = CardAnimation.default
		set(value)
		{
			if(value == field)
				return
			field = value
			notifyDataSetChanged()
		}

	/** Auto quality: what the next connection to a console would ask for ("720p"), null = say nothing */
	var autoLabel: ((DisplayHost) -> String?)? = null

	/** the Auto label each card shows now, by card */
	private val shownAutoLabels = mutableMapOf<Any, String?>()

	fun shownAutoLabel(host: DisplayHost) = shownAutoLabels[host.cardKey]

	/** The Auto labels again (the network, battery or profile changed): only the cards whose label changed are redrawn */
	fun refreshAutoLabels()
	{
		val label = autoLabel ?: return
		hosts.forEachIndexed { i, host ->
			if(label(host) != shownAutoLabels[host.cardKey])
				notifyItemChanged(i)
		}
	}

	/** Cards saying "wait, I'm busy" after a tap, until then (elapsedRealtime) */
	private val nopeUntil = mutableMapOf<Long, Long>()
	private val handler = Handler(Looper.getMainLooper())

	/** A tap on a busy card: it shakes and says to wait, without interrupting anything */
	fun nope(registeredHostId: Long)
	{
		nopeUntil[registeredHostId] = SystemClock.elapsedRealtime() + NOPE_SHOWN_MS
		hosts.forEachIndexed { i, host -> if(host.registeredHost?.id == registeredHostId) notifyItemChanged(i, PAYLOAD_NOPE) }
		handler.postDelayed({ notifyHosts(setOf(registeredHostId)) }, NOPE_SHOWN_MS + 50)
	}

	private fun notifyHosts(ids: Set<Long>)
	{
		hosts.forEachIndexed { i, host -> if(host.registeredHost?.id in ids) notifyItemChanged(i) }
	}

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.count()

	override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>)
	{
		if(PAYLOAD_NOPE in payloads)
		{
			bindActivityLabel(holder, hosts[position])
			val d = holder.itemView.resources.displayMetrics.density
			ObjectAnimator.ofFloat(holder.itemView, View.TRANSLATION_X, 0f, -9f * d, 8f * d, -6f * d, 4f * d, 0f).apply {
				duration = 450
				start()
			}
		}
		else
			onBindViewHolder(holder, position)
	}

	/** The label shows what the console is going through, if anything; false if it is not busy with anything */
	private fun bindActivityLabel(holder: ViewHolder, host: DisplayHost): Boolean
	{
		val context = holder.itemView.context
		val id = host.registeredHost?.id ?: return false
		val activity = activities[id] ?: return false
		val nope = (nopeUntil[id] ?: 0L) > SystemClock.elapsedRealtime() && activity.busy
		holder.binding.reachTextView.text = when(activity.phase)
		{
			Phase.WAKING -> if(nope) context.getString(R.string.card_nope_waking) else context.getString(R.string.wakeup_waking, activity.elapsedSeconds())
			Phase.READY -> context.getString(if(activity.connect) R.string.wakeup_ready_connecting else R.string.wakeup_ready)
			Phase.FAILED -> context.getString(R.string.wakeup_failed)
			Phase.LEAVING -> if(nope) context.getString(R.string.card_nope_leaving) else context.getString(R.string.card_leaving, activity.elapsedSeconds())
			Phase.SLEEPING -> if(nope) context.getString(R.string.card_nope_sleeping) else context.getString(R.string.card_sleeping, activity.elapsedSeconds())
			Phase.PAUSED -> activity.remainingSeconds().let { context.getString(R.string.card_paused, "%d:%02d".format(it / 60, it % 60)) }
		}
		val color = when(activity.phase)
		{
			Phase.WAKING -> R.color.wakeup_waking
			Phase.READY -> R.color.reach_home
			Phase.FAILED -> R.color.reach_warning
			Phase.LEAVING -> R.color.card_leaving
			Phase.SLEEPING -> R.color.card_sleeping
			Phase.PAUSED -> R.color.card_paused
		}
		ViewCompat.setBackgroundTintList(holder.binding.reachTextView, ColorStateList.valueOf(ContextCompat.getColor(context, color)))
		return true
	}

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val context = holder.itemView.context
		val host = hosts[position]
		holder.binding.also {
			it.nameTextView.text = host.name
			it.hostTextView.text = context.getString(R.string.display_host_host, host.host)
			val id = host.id
			it.idTextView.text =
				if(!host.isRegistered && host.otherAccountNames.isNotEmpty())
					// registered here, but with another account: tapping registers it with this one
					context.getString(R.string.display_host_other_account, host.otherAccountNames.joinToString(", "))
				else if(id != null)
					context.getString(
						if(host.isRegistered)
							R.string.display_host_id_registered
						else
							R.string.display_host_id_unregistered,
						id)
				else
					""
			val live = host.live
			it.bottomInfoTextView.text = live?.let { discoveredHost ->
				if(discoveredHost.runningAppName != null || discoveredHost.runningAppTitleid != null)
					context.getString(R.string.display_host_app_title_id, discoveredHost.runningAppName ?: "", discoveredHost.runningAppTitleid ?: "")
				else
					""
			} ?: ""
			val (reachText, reachColor) = when(host.reach)
			{
				Reach.HOME -> R.string.reach_home to R.color.reach_home
				Reach.VPN -> R.string.reach_vpn to R.color.reach_remote
				Reach.DIRECT -> R.string.reach_direct to R.color.reach_remote
				Reach.SEARCHING -> R.string.reach_searching to R.color.reach_neutral
				Reach.UNREACHABLE -> R.string.reach_unreachable to R.color.reach_warning
				Reach.UNREACHABLE_VPN -> R.string.reach_unreachable_vpn to R.color.reach_warning
				Reach.DISCOVERY_OFF -> R.string.reach_discovery_off to R.color.reach_neutral
			}
			val auto = autoLabel?.invoke(host)
			shownAutoLabels[host.cardKey] = auto
			if(!bindActivityLabel(holder, host))
			{
				it.reachTextView.text = context.getString(reachText) + if(auto != null) " · $auto" else ""
				ViewCompat.setBackgroundTintList(it.reachTextView, ColorStateList.valueOf(ContextCompat.getColor(context, reachColor)))
			}
			it.stateIndicatorImageView.setImageResource(
				when
				{
					live != null -> when(live.state)
					{
						DiscoveryHost.State.STANDBY -> if(host.isPS5) R.drawable.ic_console_ps5_standby else R.drawable.ic_console_standby
						DiscoveryHost.State.READY -> if(host.isPS5) R.drawable.ic_console_ps5_ready else R.drawable.ic_console_ready
						else -> if(host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console
					}
					host.isPS5 -> R.drawable.ic_console_ps5
					else -> R.drawable.ic_console
				}
			)
			it.root.setOnClickListener { clickCallback(host) }
			val activity = host.registeredHost?.id?.let { id -> activities[id] }
			it.animationBack.show(cardAnimation, activity)
			it.animationFront.iconView = it.stateIndicatorImageView
			it.animationFront.show(cardAnimation, activity)

			val canWakeup = host.registeredHost != null
			val canEditDelete = host is ManualDisplayHost
			if(canWakeup || canEditDelete)
			{
				it.menuButton.isVisible = true
				it.menuButton.setOnClickListener { _ ->
					val menu = PopupMenu(context, it.menuButton)
					menu.menuInflater.inflate(R.menu.display_host, menu.menu)
					val cancellable = activity?.cancellableWakeup == true
					menu.menu.findItem(R.id.action_wakeup).isVisible = canWakeup && !cancellable && activity?.phase != Phase.PAUSED
					menu.menu.findItem(R.id.action_cancel_wakeup).isVisible = cancellable
					menu.menu.findItem(R.id.action_close_kept_session).isVisible = activity?.phase == Phase.PAUSED
					menu.menu.findItem(R.id.action_edit).isVisible = canEditDelete
					menu.menu.findItem(R.id.action_delete).isVisible = canEditDelete
					menu.setOnMenuItemClickListener { menuItem ->
						when(menuItem.itemId)
						{
							R.id.action_wakeup -> wakeupCallback(host)
							R.id.action_cancel_wakeup -> cancelWakeupCallback(host)
							R.id.action_close_kept_session -> closeKeptCallback(host)
							R.id.action_edit -> editCallback(host)
							R.id.action_delete -> deleteCallback(host)
							else -> return@setOnMenuItemClickListener false
						}
						true
					}
					menu.show()
				}
			}
			else
			{
				it.menuButton.isGone = true
				it.menuButton.setOnClickListener(null)
			}
		}
	}

	companion object
	{
		private const val PAYLOAD_NOPE = "nope"
		private const val NOPE_SHOWN_MS = 1400L
	}
}
