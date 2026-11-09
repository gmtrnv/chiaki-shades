// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contact sizes are simulated: these check the logic, not how a given phone reports them. */
class SquashDetectorTest
{
	private val ratio = 1.5f

	/** a thumb that has been resting for a while at [size] */
	private fun resting(size: Float = 10f): Pair<SquashDetector, Long>
	{
		val d = SquashDetector()
		d.reset(size, 0)
		var t = 0L
		while(t < 1600) { t += 8; d.update(size, t, ratio) }
		return d to t
	}

	@Test fun restingThumbIsNotSquashing()
	{
		val (d, _) = resting()
		assertFalse(d.squashed)
	}

	@Test fun squashingIsDetectedAndEasingOffReleases()
	{
		val (d, t0) = resting()
		var t = t0
		repeat(10) { t += 8; d.update(10f + it * 1.2f, t, ratio) }   // up to 20.8: x2
		assertTrue(d.squashed)
		repeat(10) { t += 8; d.update(20.8f - it * 1.2f, t, ratio) } // back to ~10
		assertFalse("easing off must let go without lifting the finger", d.squashed)
	}

	/**
	 * A quick squash, then the thumb eases off in one reading and keeps perfectly still: no more touch events. The
	 * last reading came too soon to let go (minimum hold, smoothing), so the pad judges the same size again on a
	 * timer (2.5.7); that must be enough to let go.
	 */
	@Test fun easingOffThenKeepingStillLetsGoWhenJudgedAgain()
	{
		val (d, t0) = resting()
		var t = t0
		repeat(4) { t += 8; d.update(10f + (it + 1) * 3f, t, ratio) } // 13 .. 22: on
		assertTrue(d.squashed)
		t += 8; d.update(10f, t, ratio)                                 // back to rest at once
		assertTrue("too soon to let go on this reading alone", d.squashed)
		repeat(10) { t += 30; d.update(10f, t, ratio) }                // the timer: same size, later
		assertFalse("judging the same size again must let go", d.squashed)
	}

	@Test fun holdDoesNotFadeAwayByItself()
	{
		val (d, t0) = resting()
		var t = t0
		repeat(10) { t += 8; d.update(10f + it * 1.2f, t, ratio) }
		repeat(600) { t += 8; d.update(20f, t, ratio) }               // ~5 s held steady
		assertTrue(d.squashed)
	}

	@Test fun slowGrowthFromTiltingTheThumbIsNotSquashing()
	{
		val (d, t0) = resting()
		var t = t0
		// the contact grows 10 -> 17 (x1.7) over 3 s while the thumb rolls towards the edge of the stick
		for(i in 1..375) { t += 8; d.update(10f + 7f * i / 375f, t, ratio) }
		assertFalse(d.squashed)
		// and a real squash from there still works (x1.6 of the followed rest size)
		repeat(10) { t += 8; d.update(17f + it * 1.2f, t, ratio) }
		assertTrue(d.squashed)
	}

	@Test fun thumbThatGrewKeepsReleasingWhenEasingOff()
	{
		// the old logic compared with the first 150 ms for ever: a thumb that grew stayed "squashed"
		val (d, t0) = resting()
		var t = t0
		for(i in 1..375) { t += 8; d.update(10f + 7f * i / 375f, t, ratio) }
		repeat(10) { t += 8; d.update(17f + it * 1.2f, t, ratio) }
		assertTrue(d.squashed)
		repeat(15) { t += 8; d.update(27f - it * 0.7f, t, ratio) }   // eases to ~17
		assertFalse(d.squashed)
	}

	@Test fun landingHardDoesNotStartSquashed()
	{
		val d = SquashDetector()
		d.reset(8f, 0)
		var t = 0L
		// a thumb that lands and spreads in the first moments (150 ms of settling)
		repeat(15) { t += 8; d.update(8f + it, t, ratio) }
		assertFalse(d.squashed)
	}

	@Test fun oneGlitchyReadingDoesNotSquash()
	{
		val (d, t0) = resting()
		var t = t0 + 8
		d.update(3f, t, ratio)      // a reading far too small...
		t += 8
		d.update(10f, t, ratio)     // ...then normal again
		assertFalse(d.squashed)
	}

	@Test fun flattenedThumbThatOnlyEasesPartlyStillReleasesAndDoesNotRetrigger()
	{
		// the contact does not go back to its first size: 10 -> 22 squashed, eased only to 17 (still x1.7 of the first rest)
		val (d, t0) = resting()
		var t = t0
		repeat(10) { t += 8; d.update(10f + it * 1.4f, t, ratio) }
		assertTrue(d.squashed)
		repeat(20) { t += 8; d.update(22.6f, t, ratio) }
		assertTrue(d.squashed)
		repeat(15) { t += 8; d.update(22.6f - it * 0.4f, t, ratio) }   // eases to ~17
		repeat(30) { t += 8; d.update(17f, t, ratio) }
		assertFalse("easing off must let go even if the contact stays wide", d.squashed)
		// and it takes a new squash from there, not a stuck one
		repeat(10) { t += 8; d.update(17f + it * 1.2f, t, ratio) }     // up to ~28
		assertTrue(d.squashed)
	}

	// ---- real contact sizes, recorded on a handheld's touch panel (ABS_MT_TOUCH_MAJOR) with getevent

	/** feeds the recorded readings (time in ms, size) as a stream every 8 ms, holding the last one; returns the state at each time */
	private fun replay(times: LongArray, sizes: IntArray, ratio: Float, calibration: SquashCalibration? = null): (Long) -> Boolean
	{
		val d = SquashDetector(calibration)
		d.reset(sizes[0].toFloat(), 0)
		val states = HashMap<Long, Boolean>()
		var i = 0
		var t = 0L
		while(t <= times.last() + 500)
		{
			while(i + 1 < times.size && times[i + 1] <= t) i++
			states[t] = d.update(sizes[i].toFloat(), t, ratio)
			t += 8
		}
		return { at -> states[(at / 8) * 8] ?: false }
	}

	// a squash and an ease-off (size 70 at rest, 124 squashed, 90 eased): lands already wide
	private val squashTimes = longArrayOf(0, 2990, 3000, 3020, 3040, 3050, 3060, 3070, 3090, 3100, 3110, 3120, 3140, 3210, 3220, 3230, 3250, 3260, 3270, 3280, 3290, 3330, 3340, 3360, 3380, 3400, 5640, 5660, 5700, 5710, 5720, 5730, 5740, 5750, 5760, 5770, 5780, 5790, 5800, 8360, 8370)
	private val squashSizes = intArrayOf(70, 72, 74, 76, 78, 80, 82, 84, 86, 90, 92, 94, 96, 98, 100, 102, 106, 108, 110, 112, 114, 116, 118, 120, 122, 124, 122, 120, 118, 116, 114, 112, 108, 104, 100, 96, 94, 92, 90, 84, 66)

	@Test fun recordedSquashIsHeldAndLetGoWhenEasingOff()
	{
		val at = replay(squashTimes, squashSizes, 1.25f)
		assertFalse(at(2000))
		assertTrue("squashed at the peak", at(3600))
		assertTrue("still held while the thumb keeps squashing", at(5500))
		assertFalse("let go after easing off (to 90, still wider than the 70 it began with)", at(6200))
		assertFalse(at(8000))
	}

	// lands light (20), spreads to 68 in ~0.4 s (starting 0.7 s later), a soft press (64 -> 92), eased, then a hard one (62 -> 104)
	private val landTimes = longArrayOf(0, 720, 750, 770, 800, 850, 900, 940, 990, 1000, 1010, 1020, 1030, 1050, 1070, 1090, 1100, 1140, 3170, 3270, 3360, 5160, 5170, 5180, 5190, 5200, 5210, 5220, 5230, 5240, 5250, 5280, 5330, 5390, 5520, 5550, 6160, 6170, 6180, 6190, 6200, 6210, 6220, 6230, 6240, 6250, 6260, 6270, 6280, 6290, 6320, 7610, 7620, 7630, 7640, 7650, 7660, 7670, 7690, 7700, 7710, 7720, 8510)
	private val landSizes = intArrayOf(20, 22, 24, 26, 28, 30, 32, 34, 44, 50, 54, 56, 58, 60, 62, 64, 66, 68, 66, 64, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 90, 88, 86, 84, 82, 78, 76, 74, 72, 70, 68, 66, 64, 62, 60, 62, 66, 70, 74, 78, 82, 84, 88, 96, 100, 104, 88)

	@Test fun recordedLandingIsNotASquashAndStaysNotStuck()
	{
		val at = replay(landTimes, landSizes, 1.25f)
		for(t in 0L..5000L step 8)
			assertFalse("landing growth (20 -> 68) must not count as squashing, t=$t", at(t))
		assertTrue("the soft press", at(5700))
		assertFalse("let go after easing off", at(6600))
		assertFalse(at(7000))
		assertTrue("the hard press", at(7900))
	}

	// a second recording: lands (30 -> 102 over 0.6-1.1 s), steers on a plateau, squashes 104 -> 132 (x1.27 only), eases to 104
	private val thumbTimes = longArrayOf(0, 606, 617, 627, 637, 648, 658, 669, 679, 689, 731, 752, 763, 783, 867, 877, 888, 898, 909, 971, 982, 992, 1003, 1013, 1024, 1044, 1055, 1065, 1076, 1086, 1097, 1107, 1118, 1995, 2006, 2026, 2037, 2047, 2058, 2068, 2079, 2089, 2266, 2277, 2287, 2297, 2308, 2423, 2433, 2444, 2465, 2475, 2486, 2496)
	private val thumbSizes = intArrayOf(30, 34, 36, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 90, 94, 96, 98, 100, 102, 104, 106, 108, 112, 114, 120, 126, 130, 132, 126, 122, 120, 118, 116, 114, 112, 110, 108, 106, 104, 94)

	@Test fun aSoftRecordedSquashIsFoundWithoutWorkingHard()
	{
		val at = replay(thumbTimes, thumbSizes, 1.25f)
		for(t in 0L..1900L step 8)
			assertFalse("landing, t=$t", at(t))
		assertTrue("squashed", at(2150))
		assertFalse("let go while easing off", at(2400))
	}

	// ---- calibrated: squashing is passing the levels the wizard measured

	@Test fun calibratedRecordedSquashIsHeldAndLetGo()
	{
		// this thumb measured: resting ~75, squashing 124, easing off to 90 -> on at 107, off at ~99
		val at = replay(squashTimes, squashSizes, 1.25f, SquashCalibration(75f, 124f, 90f))
		assertFalse(at(2000))
		assertTrue(at(3600))
		assertTrue(at(5500))
		assertFalse("eased to 90 lets go", at(6200))
	}

	@Test fun calibratedIgnoresTheLandingSpreadAndFindsBothPresses()
	{
		val at = replay(landTimes, landSizes, 1.25f, SquashCalibration(68f, 104f, 76f))
		for(t in 0L..5000L step 8)
			assertFalse("landing, t=$t", at(t))
		assertTrue("soft press", at(5700))
		assertFalse("eased", at(6600))
		assertTrue("hard press", at(7900))
	}
}
