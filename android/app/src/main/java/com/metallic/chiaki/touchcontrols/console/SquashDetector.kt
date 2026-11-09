// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tells when a thumb is being squashed harder against the screen, from the size of its contact
 * (the phone reports it with every touch event).
 *
 * What the real thumb does (recorded on a handheld's touch panel, see the tests):
 *  - On landing the contact grows for up to about a second (often after a flat start), in one
 *    ramp: 20 -> 68, 30 -> 102. That ramp looks exactly like a squash, so nothing is judged until
 *    it is over (a rise followed by a plateau or a fall; or 1.5 s without any rise), and the size
 *    there is the rest size.
 *  - Rolling the thumb towards the edge of a stick makes the contact longer without extra
 *    pressure: the rest size is followed (falls fast, rises slowly), so slow drift does not count
 *    and a quick jump of tens of percent does.
 *  - A squashed thumb does not go back to its first size when easing off (about 1.3x of it), so
 *    the release is judged against the peak of the squash (it lets go when the contact has given
 *    back part of what it gained), not against the size at rest; and the new rest is half way.
 */
class SquashDetector(private val calibration: SquashCalibration? = null)
{
	companion object
	{
		/** the rest size follows a smaller contact in about this time... */
		const val FALL_MS = 120f
		/** ...and a bigger one in about this time */
		const val RISE_MS = 900f
		/** readings are smoothed over this time before judging a release (one odd reading must not let go) */
		const val SMOOTH_MS = 40f
		/** it lets go when the contact has given back this part of what the squash gained over rest */
		const val RELEASE_GIVEN_BACK = 0.35f
		/** a squash is held at least this long (the smoothed reading is still catching up with it) */
		const val MIN_HOLD_MS = 80L
		/** landing: the contact has risen this much over its smallest size... */
		const val LAND_RISE = 1.15f
		/** ...and is over when it stays within 4% for this long, or falls below 90% of its largest... */
		const val LAND_STABLE_MS = 150L
		const val LAND_FALL = 0.9f
		/** ...or when this much time has passed */
		const val LAND_MAX_MS = 1500L
	}

	/** the size of the contact at rest, as followed so far */
	var rest = 0f
		private set
	var squashed = false
		private set
	private var smooth = 0f
	private var peak = 0f
	private var start = 0L
	private var squashedAt = 0L
	private var last = 0L
	// landing
	private var landed = false
	private var landMin = 0f
	private var landMax = 0f
	private var landRose = false
	private var ref = 0f
	private var refAt = 0L

	/** calibrated (the wizard measured this thumb): squashing is just passing the measured levels */
	val calibrated get() = calibration != null
	val onLevel get() = calibration?.on ?: 0f
	val offLevel get() = calibration?.off ?: 0f

	fun reset(size: Float, now: Long)
	{
		rest = calibration?.normal ?: size
		smooth = size
		peak = size
		squashed = false
		start = now
		last = now
		landed = false
		landMin = size
		landMax = size
		landRose = false
		ref = size
		refAt = now
	}

	private fun updateCalibrated(c: SquashCalibration, now: Long): Boolean
	{
		if(!squashed)
		{
			if(smooth >= c.on)
			{
				squashed = true
				squashedAt = now
				peak = smooth
			}
		}
		else
		{
			peak = max(peak, smooth)
			// lets go below the lower level, or when it gives back half of what it gained over the eased size
			// (a harder squash than the measured one must not need going all the way back)
			val easing = smooth <= c.off || smooth <= peak - 0.5f * (peak - c.eased)
			if(now - squashedAt >= MIN_HOLD_MS && easing)
				squashed = false
		}
		return squashed
	}

	/** the contact ratio against rest (1 = at rest) */
	fun ratio(size: Float) = if(rest > 0f) size / rest else 1f

	/**
	 * A new reading of the contact's [size] at [now] (ms). [ratio]: how much bigger than at rest
	 * counts as squashing (e.g. 1.25). Returns whether the thumb is squashing now.
	 */
	fun update(size: Float, now: Long, ratio: Float): Boolean
	{
		val dt = max(0L, now - last).toFloat()
		last = now
		if(size <= 0f)
			return squashed
		smooth += (size - smooth) * min(1f, dt / SMOOTH_MS)
		if(calibration != null)
			return updateCalibrated(calibration, now)
		if(!landed)
		{
			landMin = min(landMin, size)
			landMax = max(landMax, size)
			if(size > landMin * LAND_RISE) landRose = true
			if(abs(size - ref) > 0.04f * ref) { ref = size; refAt = now }
			val over = (landRose && (now - refAt >= LAND_STABLE_MS || size < landMax * LAND_FALL)) || now - start >= LAND_MAX_MS
			if(!over)
			{
				rest = size
				smooth = size
				return false
			}
			landed = true
			rest = size
			smooth = size
		}
		if(!squashed)
		{
			val tau = if(size < rest) FALL_MS else RISE_MS
			rest += (size - rest) * min(1f, dt / tau)
			if(size > rest * ratio)
			{
				squashed = true
				squashedAt = now
				peak = smooth
			}
		}
		else
		{
			peak = max(peak, smooth)
			val easedHalfway = smooth < rest * (1f + (ratio - 1f) / 2f)
			val givenBack = smooth < peak - RELEASE_GIVEN_BACK * (peak - rest)
			if(now - squashedAt >= MIN_HOLD_MS && (easedHalfway || givenBack))
			{
				squashed = false
				rest = (rest + smooth) / 2f
			}
		}
		return squashed
	}
}
