// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import kotlin.math.sqrt

/**
 * The controller's orientation quaternion, worked out from its gyroscope and accelerometer: a Kotlin copy of the
 * library's tracker (lib/src/orientation.c, Madgwick's IMU filter), the one Chiaki on a computer uses for a real
 * DualSense.
 *
 * It used to come from Android's rotation vector, which is measured against the Earth (z to the sky, y to magnetic
 * north): the console got a controller standing on its end with the upright grip, turned by the compass heading.
 * Games that only read the turning speed (aiming) did not notice; those that draw the controller (Astro's controller
 * demo) did. Like a real controller, this one starts facing forward and knows nothing of north.
 *
 * Gyroscope in rad/s and accelerometer in g, both already in the controller's axes (x right, y up out of its face,
 * z toward the player).
 *
 * Two changes from the library's one: it starts already tilted as gravity says (the library's starts flat, as a
 * controller on a table, and corrects only a few degrees a second after a short warm-up), and the fuzz filter
 * smooths only what is sent. The library's filtered its own state, so turns slower than ~5°/s were partly lost
 * at each reading, and a lost turn left/right never comes back (gravity only corrects the tilt): it drifted.
 */
class OrientationTracker
{
	private companion object
	{
		const val SIN_1_4_PI = 0.70710677f
		const val COS_1_4_PI = 0.70710677f
		/** how hard gravity pulls the tilt back (the library's value after its warm-up, which this one does not need) */
		const val BETA_DEFAULT = 0.05f
		const val ORIENT_FUZZ = 0.0007f
		const val FUZZ_FILTER_PREV_WEIGHT = 0.75f
		const val FUZZ_FILTER_PREV_WEIGHT_2X = 0.6f
	}

	// Madgwick's frame (z up): starts 90° around x, a controller lying flat
	private var w = COS_1_4_PI
	private var x = SIN_1_4_PI
	private var y = 0f
	private var z = 0f
	private var samples = 0

	/** the result in the console's frame (y up), as the library's tracker hands it over */
	var orientW = 1f; private set
	var orientX = 0f; private set
	var orientY = 0f; private set
	var orientZ = 0f; private set

	fun reset()
	{
		w = COS_1_4_PI; x = SIN_1_4_PI; y = 0f; z = 0f
		samples = 0
		orientW = 1f; orientX = 0f; orientY = 0f; orientZ = 0f
	}

	/** one reading; [dt] the seconds since the previous one (the first one only lines it up with gravity) */
	fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, dt: Float)
	{
		samples++
		if(samples <= 1)
			alignWithGravity(ax, ay, az)
		else if(dt > 0f)
			step(gx, gy, gz, ax, ay, az, BETA_DEFAULT, dt)
		else
			return
		// -90° around x, from Madgwick's frame to the console's; the fuzz filter only on what is sent
		val c = COS_1_4_PI
		val s = -SIN_1_4_PI
		val first = samples <= 1
		orientW = fuzz(c * w - s * x, orientW, first)
		orientX = fuzz(c * x + s * w, orientX, first)
		orientY = fuzz(c * y - s * z, orientY, first)
		orientZ = fuzz(c * z + s * y, orientZ, first)
	}

	/**
	 * Tilts the state as gravity says, still facing forward: the filter's own gravity correction with the gyroscope
	 * at rest, in steps that get smaller (a fixed step would end up rocking around the answer).
	 */
	private fun alignWithGravity(ax: Float, ay: Float, az: Float)
	{
		if(ax == 0f && ay == 0f && az == 0f)
			return
		var beta = 20f
		while(beta > 0.001f)
		{
			repeat(20) { step(0f, 0f, 0f, ax, ay, az, beta, 0.01f) }
			beta *= 0.5f
		}
	}

	private fun step(gx: Float, gy: Float, gz: Float, axIn: Float, ayIn: Float, azIn: Float, beta: Float, dt: Float)
	{
		var q0 = w; var q1 = x; var q2 = y; var q3 = z

		// rate of change of the quaternion from the gyroscope
		var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
		var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
		var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
		var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

		// feedback from gravity, when there is an accelerometer reading
		if(!(axIn == 0f && ayIn == 0f && azIn == 0f))
		{
			val an = 1f / sqrt(axIn * axIn + ayIn * ayIn + azIn * azIn)
			val ax = axIn * an
			val ay = ayIn * an
			val az = azIn * an

			val _2q0 = 2f * q0; val _2q1 = 2f * q1; val _2q2 = 2f * q2; val _2q3 = 2f * q3
			val _4q0 = 4f * q0; val _4q1 = 4f * q1; val _4q2 = 4f * q2
			val _8q1 = 8f * q1; val _8q2 = 8f * q2
			val q0q0 = q0 * q0; val q1q1 = q1 * q1; val q2q2 = q2 * q2; val q3q3 = q3 * q3

			// gradient descent corrective step
			var s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
			var s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
			var s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
			var s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay
			val sn = s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3
			// no step when the orientation is already perfect (or its exact opposite): it would be NaN
			if(sn > 0.000001f)
			{
				val r = 1f / sqrt(sn)
				s0 *= r; s1 *= r; s2 *= r; s3 *= r
				qDot1 -= beta * s0
				qDot2 -= beta * s1
				qDot3 -= beta * s2
				qDot4 -= beta * s3
			}
		}

		q0 += qDot1 * dt
		q1 += qDot2 * dt
		q2 += qDot3 * dt
		q3 += qDot4 * dt
		val qn = 1f / sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
		q0 *= qn; q1 *= qn; q2 *= qn; q3 *= qn

		w = q0; x = q1; y = q2; z = q3
	}

	/** the input system's fuzz filter: tiny changes are ignored or softened, so a still controller stays still */
	private fun fuzz(cur: Float, prev: Float, first: Boolean): Float
	{
		val d = cur - prev
		return when
		{
			first -> cur
			d < ORIENT_FUZZ / 2 && d > -ORIENT_FUZZ / 2 -> prev
			d < ORIENT_FUZZ && d > -ORIENT_FUZZ -> FUZZ_FILTER_PREV_WEIGHT * prev + (1 - FUZZ_FILTER_PREV_WEIGHT) * cur
			d < ORIENT_FUZZ * 2 && d > -ORIENT_FUZZ * 2 -> FUZZ_FILTER_PREV_WEIGHT_2X * prev + (1 - FUZZ_FILTER_PREV_WEIGHT_2X) * cur
			else -> cur
		}
	}
}
