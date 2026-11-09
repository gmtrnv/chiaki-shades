// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class OrientationTrackerTest
{
	private val dt = 0.008f

	private fun OrientationTracker.feed(seconds: Float, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float)
	{
		repeat((seconds / dt).toInt()) { update(gx, gy, gz, ax, ay, az, dt) }
	}

	@Test
	fun lyingStillIsFacingForward()
	{
		val t = OrientationTracker()
		t.feed(2f, 0f, 0f, 0f, 0f, 1f, 0f)
		assertEquals(1f, abs(t.orientW), 0.01f)
		assertEquals(0f, t.orientX, 0.01f)
		assertEquals(0f, t.orientY, 0.01f)
		assertEquals(0f, t.orientZ, 0.01f)
	}

	@Test
	fun faceTowardThePlayerIsAQuarterTurnAroundX()
	{
		val t = OrientationTracker()
		// after the quick warm-up gravity corrects it only a few degrees a second (as in the library)
		t.feed(10f, 0f, 0f, 0f, 0f, 0f, 1f)
		assertEquals(0.707f, abs(t.orientX), 0.02f)
		assertEquals(0.707f, abs(t.orientW), 0.02f)
		assertEquals(0f, t.orientY, 0.02f)
		assertEquals(0f, t.orientZ, 0.02f)
	}

	@Test
	fun turningAroundItsUpAxisIsAYawForTheConsole()
	{
		val t = OrientationTracker()
		t.feed(1f, 0f, 0f, 0f, 0f, 1f, 0f)
		// a quarter turn at 1 rad/s around the controller's y (up out of its face)
		t.feed((PI / 2).toFloat(), 0f, 1f, 0f, 0f, 1f, 0f)
		assertEquals(0.707f, abs(t.orientY), 0.03f)
		assertEquals(0.707f, abs(t.orientW), 0.03f)
		assertEquals(0f, t.orientX, 0.03f)
		assertEquals(0f, t.orientZ, 0.03f)
	}

	@Test
	fun resetFacesForwardAgain()
	{
		val t = OrientationTracker()
		t.feed(1f, 0f, 0f, 0f, 0f, 0f, 1f)
		t.reset()
		assertEquals(1f, t.orientW, 0f)
		assertEquals(0f, t.orientX, 0f)
	}
}
