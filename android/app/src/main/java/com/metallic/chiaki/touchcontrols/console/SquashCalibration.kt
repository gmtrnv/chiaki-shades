// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * What one thumb measures on one phone (2.5.2): the contact size when resting on the screen,
 * when squashing hard, and when easing off without lifting. Measured by the calibration wizard
 * (see [SquashCalibrator]): every phone and every thumb is different, and so is the left thumb
 * from the right one. Kept on this device only (not exported).
 */
class SquashCalibration(val normal: Float, val press: Float, val eased: Float)
{
	/** from this size on, the thumb counts as squashing: half way between eased and squashed... */
	val on get() = max(eased + 0.5f * (press - eased), normal * 1.1f)
	/** ...and it lets go below this one (a quarter of the way): the gap keeps it from flickering */
	val off get() = min(eased + 0.25f * (press - eased), on - 1f)

	fun encode() = String.format(Locale.ROOT, "%.1f;%.1f;%.1f", normal, press, eased)

	companion object
	{
		private fun key(side: Side) = "console_pad_squash_calib_" + if(side == Side.R) "r" else "l"

		fun decode(s: String?): SquashCalibration?
		{
			val p = s?.split(';')?.mapNotNull { it.toFloatOrNull() } ?: return null
			if(p.size != 3 || p.any { it <= 0f })
				return null
			return SquashCalibration(p[0], p[1], p[2])
		}

		fun load(prefs: SharedPreferences, side: Side) = decode(prefs.getString(key(side), null))

		fun save(prefs: SharedPreferences, side: Side, c: SquashCalibration?) =
			prefs.edit().apply { if(c == null) remove(key(side)) else putString(key(side), c.encode()) }.apply()
	}
}

/**
 * The calibration wizard's logic (pure: the screen only feeds it the contact size and shows what it
 * asks): rest the thumb and move it as when playing (5 s), then [rounds] times squash hard and
 * ease off without lifting. Lifting the finger in the middle starts again.
 */
class SquashCalibrator(val rounds: Int = 3)
{
	enum class Step { WAIT_TOUCH, NORMAL, PRESS, EASE, DONE, FAILED }

	companion object
	{
		const val NORMAL_MS = 5000L
		/** the first moment of a landing is not "normal" yet (the contact is still spreading) */
		const val NORMAL_SKIP_MS = 1000L
		const val MIN_SAMPLES = 10
		/** squashing = this much over the resting size... */
		const val PRESS_FACTOR = 1.15f
		/** ...for this long */
		const val PRESS_HOLD_MS = 300L
		/** easing off = given back this part of what the squash gained */
		const val EASE_FRACTION = 0.3f
		const val EASE_WINDOW_MS = 1000L
		/** the first part of the easing still falls: only the rest of the window counts */
		const val EASE_SKIP_MS = 500L

		private fun median(v: List<Float>): Float
		{
			val s = v.sorted()
			return if(s.isEmpty()) 0f else s[s.size / 2]
		}
	}

	var step = Step.WAIT_TOUCH
		private set
	/** 0-based: which squash we are on */
	var round = 0
		private set
	/** the finger was lifted in the middle: shown as a hint */
	var lifted = false
		private set
	var result: SquashCalibration? = null
		private set
	var normal = 0f
		private set

	private var stepStart = 0L
	private val normalSamples = ArrayList<Float>()
	private val peaks = ArrayList<Float>()
	private val eases = ArrayList<Float>()
	private var overSince = -1L
	private var peak = 0f
	private var easeAt = -1L
	private val easeSamples = ArrayList<Pair<Long, Float>>()

	fun reset()
	{
		step = Step.WAIT_TOUCH
		round = 0
		result = null
		normalSamples.clear(); peaks.clear(); eases.clear(); easeSamples.clear()
		overSince = -1L
		easeAt = -1L
	}

	/** how far along the whole thing is, 0..1 */
	fun progress(now: Long): Float = when(step)
	{
		Step.WAIT_TOUCH -> 0f
		Step.NORMAL -> min(1f, (now - stepStart).toFloat() / NORMAL_MS) * 0.2f
		Step.PRESS -> 0.2f + 0.8f * round / rounds
		Step.EASE -> 0.2f + 0.8f * (round + 0.5f) / rounds
		Step.DONE, Step.FAILED -> 1f
	}

	fun touchDown(now: Long)
	{
		if(step != Step.WAIT_TOUCH)
			return
		step = Step.NORMAL
		stepStart = now
		lifted = false
	}

	fun touchUp()
	{
		if(step == Step.NORMAL || step == Step.PRESS || step == Step.EASE)
		{
			reset()
			lifted = true
		}
	}

	/** the contact size now, called regularly while the finger is down */
	fun sample(size: Float, now: Long)
	{
		when(step)
		{
			Step.NORMAL ->
			{
				val t = now - stepStart
				if(t >= NORMAL_SKIP_MS)
					normalSamples.add(size)
				if(t >= NORMAL_MS)
				{
					if(normalSamples.size < MIN_SAMPLES) { step = Step.FAILED; return }
					// the 90th percentile: the biggest it gets while moving, not counting the odd reading
					normal = normalSamples.sorted()[(normalSamples.size * 9) / 10]
					step = Step.PRESS
					round = 0
					overSince = -1L
				}
			}
			Step.PRESS ->
			{
				// after easing off the thumb stays wider than when resting: the next squash is measured from there
				val from = max(normal, eases.lastOrNull() ?: 0f)
				if(size >= from * PRESS_FACTOR)
				{
					if(overSince < 0) { overSince = now; peak = 0f }
					peak = max(peak, size)
					if(now - overSince >= PRESS_HOLD_MS)
					{
						peaks.add(peak)
						step = Step.EASE
						easeAt = -1L
						easeSamples.clear()
					}
				}
				else
					overSince = -1L
			}
			Step.EASE ->
			{
				val lastPeak = peaks.last()
				if(easeAt < 0)
				{
					if(size <= lastPeak - EASE_FRACTION * (lastPeak - normal)) { easeAt = now; easeSamples.clear() }
				}
				else
				{
					easeSamples.add((now - easeAt) to size)
					if(now - easeAt >= EASE_WINDOW_MS)
					{
						eases.add(median(easeSamples.filter { it.first >= EASE_SKIP_MS }.map { it.second }))
						round++
						if(round >= rounds) finish() else { step = Step.PRESS; overSince = -1L }
					}
				}
			}
			else -> {}
		}
	}

	private fun finish()
	{
		val p = median(peaks)
		val e = median(eases)
		// squashing must be clearly more than resting and than easing off, or the numbers mean nothing
		if(p < normal * 1.15f || p < e * 1.1f || e <= 0f)
		{
			step = Step.FAILED
			return
		}
		result = SquashCalibration(normal, p, e)
		step = Step.DONE
	}
}
