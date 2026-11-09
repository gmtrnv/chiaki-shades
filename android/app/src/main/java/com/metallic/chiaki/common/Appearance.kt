// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

/** How the console cards animate while waking up, disconnecting or suspending (Settings → Appearance) */
enum class CardAnimation(val value: String)
{
	/** the sun of the app icon rises and sets (default: the Chiaki Shades look) */
	SUNRISE("sunrise"),
	/** the light strip breathes like the console's own power light */
	BREATHING("breathing"),
	/** the console icon fills with light and drains */
	CHARGE("charge");

	companion object
	{
		val default = SUNRISE
		fun fromValue(value: String?) = values().find { it.value == value } ?: default
	}
}
