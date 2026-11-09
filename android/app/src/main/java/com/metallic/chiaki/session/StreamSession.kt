// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.lib.*
import java.util.concurrent.Executors

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

private const val TAG = "StreamSession"
private const val PS_PRESS_MS = 150L
/** no picture from the console for this long: the connection is gone */
const val NO_PICTURES_MS = 6000L

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput,
					/** where the once-a-second STATS lines of the session log get their numbers (see [SessionStats]; cheap, always on) */
					private val diagContext: android.content.Context? = null,
					private val diagFlags: () -> String = { "" })
{
	var session: Session? = null
		private set

	private var stats: SessionStats? = null
	private var wifiLock: StreamWifiLock? = null

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state
	private val _rumbleState = MutableLiveData<RumbleEvent>(RumbleEvent(0U, 0U))
	val rumbleState: LiveData<RumbleEvent> get() = _rumbleState

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
	}

	companion object
	{
		/**
		 * Ended sessions are closed here, one after the other, away from the main thread: saying
		 * goodbye to the console and shutting the decoders down usually takes well under a
		 * second, but the fork did it on the main thread, so anything stuck in there froze the
		 * app and, its black window on top, the whole phone until Android offered to kill it.
		 */
		private val closer = Executors.newSingleThreadExecutor { Thread(it, "SessionCloser") }
		private val mainHandler = Handler(Looper.getMainLooper())

		/** sessions still closing (main thread only) */
		private var closing = 0
		/** sessions that want to start once those are done (main thread only) */
		private val waitingForClose = mutableListOf<StreamSession>()

		private fun closed()
		{
			closing--
			if(closing > 0)
				return
			val waiting = waitingForClose.toList()
			waitingForClose.clear()
			waiting.forEach { it.resume() }
		}
	}

	/**
	 * Ends the session but keeps the video surface: it belongs to the view, not to the session.
	 * (The fork dropped it here, so "Reconnect", which does not pause the view, came back with
	 * sound but no picture.) The actual closing happens in the background, see [closer].
	 */
	fun shutdown()
	{
		if(wasConnected)
		{
			SessionExits.report(SessionExits.Exit(connectInfo.registKey, suspendRequested))
			judgeGame()
		}
		wasConnected = false
		quitWithError = false
		suspendRequested = false
		waitingForClose.remove(this)
		val session = session
		this.session = null
		// nothing may write into the native session once it starts closing
		stats?.stop()
		stats = null
		wifiLock?.release()
		wifiLock = null
		// the decoder may still draw into the sink until the session is closed
		val sink = sink
		val sinkThread = sinkThread
		this.sink = null
		this.sinkThread = null
		sinkHandler = null
		_state.value = StreamStateIdle
		if(session == null)
		{
			sink?.close()
			sinkThread?.quitSafely()
			return
		}
		// ended on purpose: its "quit" event, arriving later, must not be taken for the console
		// ending the stream (it would close whatever the screen shows by then)
		session.eventCallback = null
		closing++
		closer.execute {
			try
			{
				session.stop()
				session.dispose()
			}
			catch(e: Throwable)
			{
				Log.e(TAG, "Failed to close the session", e)
			}
			sink?.close()
			sinkThread?.quitSafely()
			mainHandler.post { closed() }
		}
	}

	/** Asks the console to go to rest mode; false when there is no session to send it through. */
	fun gotoBed(): Boolean
	{
		val session = session ?: return false
		val sent = session.gotoBed().isSuccess
		if(sent)
			suspendRequested = true
		return sent
	}

	/** volume of the game's sound, 0..1 (Settings → Stream volume, the bar's speaker); applied to each new session */
	var audioVolume = 1f
		private set

	fun setVolume(volume: Float)
	{
		audioVolume = volume
		session?.setAudioVolume(volume)
	}

	/** reached the stream (not just tried): leaving it is worth showing on the console's card */
	@Volatile private var wasConnected = false
	/** the console ended the stream with an error */
	@Volatile private var quitWithError = false

	/**
	 * How the game that just ended went, for the Auto quality of the next connection in the same
	 * sitting (AutoQuality.Memory): bad = the connection was lost, an error, or over its last two
	 * minutes many troubled seconds, many losses or the console cutting the bitrate well below what
	 * was asked; good = clean and long enough to tell; anything else says nothing.
	 */
	private fun judgeGame()
	{
		val samples = LiveStats.snapshot()
		val lost = SystemClock.elapsedRealtime() - lastPictureMs > NO_PICTURES_MS
		val asked = connectInfo.videoProfile.bitrate / 1000.0
		val targets = samples.takeLast(30).map { it.targetMbps }.filter { it > 0 }.sorted()
		val cut = targets.size >= 10 && targets[targets.size / 2] < asked * 0.6
		val enough = samples.size >= 20
		val troubled = enough && samples.count { it.trouble } >= samples.size * 0.10
		val lossy = enough && samples.sumOf { it.packetsLost } * 100.0 / samples.sumOf { it.packetsTotal }.coerceAtLeast(1) >= 3.0
		val bad = lost || quitWithError || troubled || lossy || cut
		val good = !bad && samples.size >= 30
		Log.i(TAG, "Game judged for Auto: bad=$bad (lost=$lost error=$quitWithError troubled=$troubled lossy=$lossy cut=$cut) good=$good")
		com.metallic.chiaki.common.AutoQuality.Memory.gameEnded(bad, good)
	}
	@Volatile private var suspendRequested = false

	fun pause()
	{
		shutdown()
		// the view is paused too and its surface goes away; it attaches a new one when it resumes
		surface = null
	}

	fun resume()
	{
		if(session != null)
			return
		// a session still saying goodbye to the console: start once it is done, or the console
		// would see us twice (and the old decoder may still hold the video surface)
		if(closing > 0)
		{
			if(this !in waitingForClose)
				waitingForClose.add(this)
			_state.value = StreamStateConnecting
			return
		}
		try
		{
			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
			_state.value = StreamStateConnecting
			session.eventCallback = this::eventCallback
			session.start()
			session.setAudioVolume(audioVolume)
			val surface = surface
			if(surface != null)
				session.setSurface(surface)
			this.session = session
			startStats(session)
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
	}

	private fun startStats(session: Session)
	{
		val context = diagContext ?: return
		wifiLock = StreamWifiLock(context).also { session.logLine(it.acquire()) }
		val p = connectInfo.videoProfile
		val header = "${p.width}x${p.height}@${p.maxFPS} bitrate=${p.bitrate}kbps codec=${connectInfo.ps5.let { if(it) p.codec else "H264" }} " +
			"device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} android=${android.os.Build.VERSION.SDK_INT} " +
			"cores=${Runtime.getRuntime().availableProcessors()}"
		LiveStats.reset()
		stats = SessionStats(context, { session.logLine(it) }, diagFlags, header,
			{ session.getStats(it) }).also { it.start() }
	}

	private fun eventCallback(event: Event)
	{
		when(event)
		{
			is ConnectedEvent ->
			{
				wasConnected = true
				notePicture() // a grace period until the first pictures come
				_state.postValue(StreamStateConnected)
			}
			is QuitEvent ->
			{
				quitWithError = event.reason.isError
				onQuitWhileKept?.invoke()
				_state.postValue(
				StreamStateQuit(
					event.reason,
					event.reasonString
				)
			)
			}
			is LoginPinRequestEvent -> _state.postValue(
				StreamStateLoginPinRequest(
					event.pinIncorrect
				)
			)
			is RumbleEvent -> _rumbleState.postValue(event)
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder)
			{
				val surface = holder.surface
				this@StreamSession.surface = surface
				session?.setSurface(surface)
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				this@StreamSession.surface = null
				session?.setSurface(null)
			}
		})
		
		val surface = surfaceView.holder.surface
		if (surface?.isValid == true) {
			this.surface = surface
			session?.setSurface(surface)
		}
	}

	/**
	 * Attach to a custom Surface (the one our ShaderRenderer draws the video from)
	 */
	fun attachToSurface(surface: Surface)
	{
		this.surface = surface
		session?.setSurface(surface)
		// back from the background: the decoder just switched from the sink to this surface
		if(sink != null)
		{
			releaseSink()
			session?.setAudioMuted(false)
			Log.i(TAG, "Kept session back on screen")
		}
	}

	fun detachSurface()
	{
		this.surface = null
		// a kept session keeps decoding into its sink until the new screen attaches
		if(sink == null)
			session?.setSurface(null)
	}

	// --- Kept alive in the background (quick reconnect) ---

	private var sink: ImageReader? = null
	private var sinkThread: HandlerThread? = null
	private var sinkHandler: Handler? = null

	/**
	 * When the last picture arrived from the console (on screen or in the sink while kept,
	 * elapsedRealtime). The console keeps sending them even with the game paused; none for a few
	 * seconds means the connection is gone (e.g. Wi-Fi lost), which the session itself does not
	 * notice: it would go on as if nothing happened, frozen.
	 */
	@Volatile var lastPictureMs = 0L
		private set

	fun notePicture()
	{
		lastPictureMs = SystemClock.elapsedRealtime()
	}

	/** connected and pictures still arriving */
	val receivingPictures get() =
		_state.value == StreamStateConnected && SystemClock.elapsedRealtime() - lastPictureMs < NO_PICTURES_MS

	/** Called (from a native thread) if the console ends the session while it is kept */
	@Volatile var onQuitWhileKept: (() -> Unit)? = null

	/**
	 * The screen goes away but the session stays connected: the video goes to a sink that drops
	 * every picture (the decoder stays in step, so coming back shows the picture at once instead
	 * of waiting for a keyframe), the audio is silenced and the controller is let go.
	 * @return false if there is no connected session to keep
	 */
	fun keepInBackground(pressPs: Boolean): Boolean
	{
		val session = session ?: return false
		// a dead connection is not worth keeping: it would come back as a black screen
		if(!receivingPictures)
			return false
		val thread = HandlerThread("StreamSink").also { it.start() }
		val profile = connectInfo.videoProfile
		val reader = ImageReader.newInstance(profile.width, profile.height, ImageFormat.PRIVATE, 3)
		val handler = Handler(thread.looper)
		reader.setOnImageAvailableListener({
			it.acquireLatestImage()?.close()
			notePicture()
		}, handler)
		sinkHandler = handler
		sink = reader
		sinkThread = thread
		surface = null
		session.setSurface(reader.surface)
		session.setAudioMuted(true)
		input.releaseAll() // nothing held when it comes back either
		session.setControllerState(ControllerState())
		if(pressPs)
		{
			// a short press of PS opens the console's control center: most games pause, and
			// coming back makes it obvious we were away
			session.setControllerState(ControllerState(buttons = ControllerState.BUTTON_PS))
			sinkHandler?.postDelayed({ this.session?.setControllerState(ControllerState()) }, PS_PRESS_MS)
		}
		Log.i(TAG, "Session kept in the background (pressPs=$pressPs)")
		return true
	}

	private fun releaseSink()
	{
		sink?.close()
		sink = null
		sinkThread?.quitSafely()
		sinkThread = null
		sinkHandler = null
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				this@StreamSession.surface = Surface(surfaceTexture)
				session?.setSurface(Surface(surface))
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}
}