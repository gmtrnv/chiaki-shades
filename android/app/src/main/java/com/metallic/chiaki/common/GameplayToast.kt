// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.metallic.chiaki.stream.StreamActivity

/**
 * The little confirmations of actions ("Gyroscope stick paused", "Layout saved"…). Over the game they
 * only show when Settings → "Show action messages while playing" is on (off by default: they get in the way);
 * anywhere else (the test screen, Settings) they always show. Errors and the console's own messages are
 * not routed through here.
 */
object GameplayToast
{
	fun show(context: Context, text: CharSequence)
	{
		if(context is StreamActivity && !Preferences(context).gameplayToasts)
			return
		Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
	}

	fun show(context: Context, @StringRes text: Int) = show(context, context.getString(text))
}
