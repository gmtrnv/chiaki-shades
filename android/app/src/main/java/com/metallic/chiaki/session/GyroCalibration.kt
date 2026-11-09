// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Learns the gyroscope's offset (what it reads lying still; almost no sensor reads exactly 0) while the device is
 * still, to subtract it. A real DualSense comes calibrated and the console corrects it; the sensors of handhelds and
 * phones much less: the offset adds up into a slow turn of its own (the orientation drifts left or right, aiming
 * creeps).
 *
 * Works in the sensor's own axes (the offset belongs to the chip, whatever the grip or the screen's rotation), with
 * the gyroscope in rad/s and the accelerometer in g. Still means: the gyroscope (minus what it learnt) and the
 * accelerometer barely change for [STILL_SECONDS]. The thresholds are tight so a slow, steady aim is not taken for
 * an offset, and what it learns is capped at [MAX_OFFSET].
 */
class GyroCalibration
{
	companion object
	{
		/** how much turning still counts as still, in rad/s (~3.4°/s, over the learnt offset) */
		const val STILL_GYRO = 0.06f
		/** how far the accelerometer may wander from its recent average, in g */
		const val STILL_ACCEL = 0.03f
		/** how long it has to stay still before it learns */
		const val STILL_SECONDS = 1f
		/** how fast it learns once still (time constant, seconds) */
		const val LEARN_SECONDS = 2f
		/** the largest offset it accepts per axis, in rad/s (bigger is a turn, not an offset) */
		const val MAX_OFFSET = 0.08f
		/** how fast the accelerometer's average follows (time constant, seconds) */
		private const val ACCEL_AVERAGE_SECONDS = 0.25f
	}

	var offsetX = 0f; private set
	var offsetY = 0f; private set
	var offsetZ = 0f; private set

	private var stillTime = 0f
	private var avgX = 0f
	private var avgY = 0f
	private var avgZ = 0f
	private var hasAccel = false

	/** a new accelerometer reading */
	fun feedAccel(ax: Float, ay: Float, az: Float, dt: Float)
	{
		if(!hasAccel)
		{
			avgX = ax; avgY = ay; avgZ = az
			hasAccel = true
			return
		}
		val k = if(dt <= 0f) 0f else min(1f, dt / ACCEL_AVERAGE_SECONDS)
		val dx = ax - avgX; val dy = ay - avgY; val dz = az - avgZ
		avgX += dx * k; avgY += dy * k; avgZ += dz * k
		if(sqrt(dx * dx + dy * dy + dz * dz) > STILL_ACCEL)
			stillTime = 0f
	}

	/** a new gyroscope reading: learns from it if the device has been still long enough */
	fun feedGyro(gx: Float, gy: Float, gz: Float, dt: Float)
	{
		if(!hasAccel || dt <= 0f)
			return
		val rx = gx - offsetX; val ry = gy - offsetY; val rz = gz - offsetZ
		if(sqrt(rx * rx + ry * ry + rz * rz) > STILL_GYRO)
		{
			stillTime = 0f
			return
		}
		stillTime += dt
		if(stillTime < STILL_SECONDS)
			return
		val k = min(1f, dt / LEARN_SECONDS)
		offsetX = clamp(offsetX + rx * k)
		offsetY = clamp(offsetY + ry * k)
		offsetZ = clamp(offsetZ + rz * k)
	}

	private fun clamp(v: Float) = if(abs(v) > MAX_OFFSET) MAX_OFFSET * kotlin.math.sign(v) else v
}
