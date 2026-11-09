// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class GyroCalibrationTest
{
	private val dt = 0.008f

	private fun GyroCalibration.run(seconds: Float, gyro: (Float) -> Triple<Float, Float, Float>, accel: (Float) -> Triple<Float, Float, Float>)
	{
		var time = 0f
		repeat((seconds / dt).toInt()) {
			val (ax, ay, az) = accel(time)
			feedAccel(ax, ay, az, dt)
			val (gx, gy, gz) = gyro(time)
			feedGyro(gx, gy, gz, dt)
			time += dt
		}
	}

	private val resting = { _: Float -> Triple(0f, 0f, 1f) }

	@Test
	fun learnsTheOffsetWhileStill()
	{
		val c = GyroCalibration()
		c.run(15f, { Triple(0.02f, -0.01f, 0.005f) }, resting)
		assertEquals(0.02f, c.offsetX, 0.001f)
		assertEquals(-0.01f, c.offsetY, 0.001f)
		assertEquals(0.005f, c.offsetZ, 0.001f)
	}

	@Test
	fun aSteadyTurnIsNotAnOffset()
	{
		val c = GyroCalibration()
		// a slow aim (~5.7°/s) with the accelerometer steady
		c.run(15f, { Triple(0f, 0.1f, 0f) }, resting)
		assertEquals(0f, c.offsetY, 0f)
	}

	@Test
	fun shakingInTheHandsIsNotStill()
	{
		val c = GyroCalibration()
		c.run(15f, { Triple(0.02f, 0f, 0f) }, { t -> Triple(0.1f * sin(t * 20f), 0f, 1f) })
		assertEquals(0f, c.offsetX, 0f)
	}

	@Test
	fun theOffsetIsCapped()
	{
		val c = GyroCalibration()
		// a reading that creeps up in small steps (each one still counts as still) keeps pulling it up: capped
		for(i in 1..4)
			c.run(30f, { Triple(i * 0.04f, 0f, 0f) }, resting)
		assertEquals(GyroCalibration.MAX_OFFSET, c.offsetX, 0.0001f)
	}
}
