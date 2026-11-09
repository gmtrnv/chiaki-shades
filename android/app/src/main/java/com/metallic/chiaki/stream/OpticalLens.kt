// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import kotlin.math.atan
import kotlin.math.max

/**
 * CPU side of the "Stretch óptico" lens (shaders/optical_stretch.frag).
 *
 * Everything that only depends on the view size and the settings is solved here once,
 * instead of per pixel: whether the lens is rotated 90°, the lens width W and the
 * atan profile constant k (k / atan(k) = s0, the stretch at the center).
 * Field names follow the "geometry" block of Anamorfica's settings JSON.
 */
object OpticalLens
{
	data class Params(
		val cover: Float = 1.0f,   // 1 = fill the screen, 0 = keep the source width
		val fix: Float = 0.85f,    // how much of the stretch goes into the lens profile
		val zc: Float = 1.04f,     // zoom on the other axis
		val vcomp: Float = 0.2f,   // that zoom grows towards the stretched edges
		val keep: Float = 0.55f,   // keeps the outer rows/columns in place
		// "quality" block
		val antiring: Float = 0.75f, // 0..1, limits Catmull-Rom halos
		val sharp: Float = 0.6f,     // CAS strength where the stream is magnified
		val sharpMax: Float = 0.08f, // max brightness CAS may add or remove
		// "vignette" block; only drawn while the "Color" toggle is on (off by default). The amount
		// and direction come from the color preset (ColorValues), these are just the defaults
		val vignetteAmount: Float = 0.098f,
		val vignetteStart: Float = 0.55f,
		val vignetteAxis: VignetteAxis = VignetteAxis.SIDES
	)

	/** Order = uVigAxis in the shader. ALL darkens per axis (a "box"), ELLIPSE by distance from the center. */
	enum class VignetteAxis { SIDES, TOP_BOTTOM, ALL, ELLIPSE }

	/** Per-view values passed to the shader as uniforms. */
	class Solved(val swap: Boolean, val w: Float, val s0: Float, val k: Float, val atanK: Float)

	fun solve(viewWidth: Int, viewHeight: Int, sourceAspect: Float, params: Params): Solved
	{
		var r = (viewWidth.toFloat() / viewHeight) / sourceAspect // >1 extra width, <1 extra height
		val swap = r < 1f // screen taller than the source: the lens works vertically
		if(swap)
			r = 1f / r
		val w = lerp(1f / r, 1f, params.cover)
		val s0 = max(lerp(1f, r * w / params.zc, params.fix), 1f)
		val k = solveK(s0.toDouble())
		return Solved(swap, w, s0, k.toFloat(), atan(k).toFloat())
	}

	/** Newton's method for k / atan(k) = s0. */
	fun solveK(s0: Double): Double
	{
		if(s0 <= 1.0005)
			return 0.001
		var k = 1.0
		repeat(12) {
			val at = atan(k)
			val f = k / at - s0
			val df = (at - k / (1.0 + k * k)) / (at * at)
			k = max(k - f / df, 0.001)
		}
		return k
	}

	private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
