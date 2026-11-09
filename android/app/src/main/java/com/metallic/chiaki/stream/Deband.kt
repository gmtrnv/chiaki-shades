// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

/**
 * Light debanding of shaders/optical_stretch.frag (the "Deband" toggle): 4 texture reads per
 * step, in our single pass.
 */
object Deband
{
	data class Params(
		val steps: Int = 1,          // 1..4, each one searches further away (4 reads each)
		val threshold: Float = 0.4f, // 0..1 of THRESHOLD_FULL
		val range: Float = 16f,      // search radius of the first step, stream px
		val grain: Float = 0.3f      // 0..1 of GRAIN_FULL
	)

	/** Threshold at 100 %: steps of up to 4 levels (of 255) are treated as smooth gradients. */
	const val THRESHOLD_FULL = 4f / 255f
	/** Grain at 100 %: noise of ±3 levels. */
	const val GRAIN_FULL = 3f / 255f
}
