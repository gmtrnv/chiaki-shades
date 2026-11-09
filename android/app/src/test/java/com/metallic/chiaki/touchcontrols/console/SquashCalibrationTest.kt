// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SquashCalibrationTest
{
	/** a thumb doing what the wizard asks: [sizeAt] gives the contact size at a time (ms) */
	private fun run(c: SquashCalibrator, until: Long, sizeAt: (Long) -> Float)
	{
		c.touchDown(0)
		var t = 0L
		while(t <= until && c.step != SquashCalibrator.Step.DONE && c.step != SquashCalibrator.Step.FAILED)
		{
			c.sample(sizeAt(t), t)
			t += 25
		}
	}

	/** rests 5 s (70..78), then three times: squashes to 125 for 0.6 s, eases to 90 for 1.5 s */
	private fun gesture(t: Long): Float
	{
		if(t < 5000) return 70f + (t / 25 % 5) * 2f
		val u = t - 5000
		val cycle = u % 2100
		return if(cycle < 600) 125f else 90f
	}

	@Test fun measuresTheThumbAndStores()
	{
		val c = SquashCalibrator()
		run(c, 30000, ::gesture)
		assertEquals(SquashCalibrator.Step.DONE, c.step)
		val r = c.result!!
		assertEquals(78f, r.normal, 1f)
		assertEquals(125f, r.press, 1f)
		assertEquals(90f, r.eased, 1f)
		assertTrue(r.on > r.off && r.off > r.eased)
		// the levels survive being saved and read back
		val back = SquashCalibration.decode(r.encode())!!
		assertEquals(r.on, back.on, 0.2f)
	}

	@Test fun liftingTheFingerStartsAgain()
	{
		val c = SquashCalibrator()
		c.touchDown(0)
		c.sample(70f, 100)
		assertEquals(SquashCalibrator.Step.NORMAL, c.step)
		c.touchUp()
		assertEquals(SquashCalibrator.Step.WAIT_TOUCH, c.step)
		assertTrue(c.lifted)
	}

	@Test fun squashingTooLittleDoesNotFinish()
	{
		val c = SquashCalibrator()
		// 70..78 at rest, then it never gets 15% over: the wizard keeps asking (it does not invent numbers)
		run(c, 20000) { t -> if(t < 5000) 70f + (t / 25 % 5) * 2f else 80f }
		assertEquals(SquashCalibrator.Step.PRESS, c.step)
		assertNull(c.result)
	}

	@Test fun brokenValuesAreNotAccepted()
	{
		assertNull(SquashCalibration.decode(null))
		assertNull(SquashCalibration.decode("1;2"))
		assertNull(SquashCalibration.decode("0;5;3"))
		assertNotNull(SquashCalibration.decode("70.0;125.0;90.0"))
		assertFalse(SquashCalibration(70f, 125f, 90f).on <= SquashCalibration(70f, 125f, 90f).off)
	}
}
