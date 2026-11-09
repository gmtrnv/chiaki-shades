// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.session.KeptSession
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput

class StreamViewModel(val application: Application, val connectInfo: ConnectInfo): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	private var _session: StreamSession? = null
	/** a session kept alive in the background for this console (quick reconnect) comes back as it was */
	val session = KeptSession.take(connectInfo)
		?: StreamSession(connectInfo, logManager, preferences.logVerbose, StreamInput(application, preferences), application,
			{ "hq=${if(preferences.hqEnabled) 1 else 0} deband=${if(preferences.debandLightEnabled) 1 else 0} color=${if(preferences.colorEnabled) 1 else 0} " +
				"mode=${preferences.displayMode} touch=${if(preferences.onScreenControlsEnabled) 1 else 0}" }).also {
			it.setVolume(preferences.effectiveStreamVolume)
		}
	val input get() = session.input

	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(preferences.onScreenControlsEnabled)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled


	override fun onCleared()
	{
		super.onCleared()
		_session?.shutdown()
		// safety net: a session still running when its screen is gone ends here, unless it is
		// kept for quick reconnect (the fork's _session above is never set, so it did nothing)
		if(!KeptSession.holds(session))
			session.shutdown()
	}

	fun setOnScreenControlsEnabled(enabled: Boolean)
	{
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

}