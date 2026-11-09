// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import androidx.appcompat.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Rational
import android.util.TypedValue
import android.os.*
import android.util.Log
import android.view.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.ColorPreset
import com.metallic.chiaki.common.ColorValues
import com.metallic.chiaki.common.ImageSettings
import com.metallic.chiaki.common.GameplayToast
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.console.ConsolePadSettings
import com.metallic.chiaki.touchcontrols.console.CinemaMode
import com.metallic.chiaki.touchcontrols.console.ShellTheme
import com.metallic.chiaki.touchcontrols.console.PadEditor
import com.metallic.chiaki.touchcontrols.console.useWholeScreen
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()
private object ConsoleBusyDialog: DialogContents()
private object ConnectionLostDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 5000L
		/** console busy with the previous session: retry this often, for up to this long */
		private const val BUSY_RETRY_MS = 3000L
		private const val BUSY_MAX_MS = 120_000L
		private const val VOLUME_PICKER_TIMEOUT_MS = 3000L
		private const val CONNECTION_WATCHDOG_MS = 2000L
		private const val SUSPEND_TIMEOUT_MS = 2000L
		private const val BACK_AFTER_SHOW_MS = 500L
		/** cinematic immersion: the video's glide to full screen (with the controls' fade) and back */
		private const val CINEMA_IN_MS = 700L
		private const val CINEMA_OUT_MS = 350L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()
	private val prefs by lazy { Preferences(this) }

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
		// a kept session (quick reconnect) brings its old input: the options may have changed since (test screen, Settings)
		viewModel.input.reloadGyroSettings()

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsButton.isChecked != it)
				binding.onScreenControlsButton.isChecked = it
		})
		binding.onScreenControlsButton.addOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}
		// long press on the controls button: edit the layout over the live game
		binding.onScreenControlsButton.setOnLongClickListener {
			startLayoutEditing()
			true
		}
		setupLayoutEditing()
		binding.aspectRatioLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePadVideo() }
		// the screen changed size (small window and back, camera strip…): the video goes where the
		// layout says on the new size
		binding.mainStreamLayout.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
			if(r - l != or - ol || b - t != ob - ot)
				binding.mainStreamLayout.post { placeVideo(); binding.consoleShell.invalidate() }
		}
		binding.consolePad.stateListener = {
			lastConsolePadControllerState = it
			updateCombinedTouchState()
		}
		// posted: it comes from the pad's own layout pass
		binding.consolePad.onLayoutChanged = { binding.consolePad.post { placeVideo(); binding.consoleShell.invalidate() } }
		binding.consoleShell.pad = binding.consolePad
		binding.consolePad.excludeEdgeGestures = prefs.gameMode
		// the shell fades out with the controls when they go untouched (cutscenes): just the video on black
		binding.consolePad.onIdleChanged = { hidden ->
			binding.consoleShell.animate().cancel()
			binding.consoleShell.animate().alpha(if(hidden) 0f else 1f).setDuration(if(hidden) 700L else 120L).start()
			// and with cinematic immersion on, the video glides to fill the screen (and back)
			setCinema(hidden)
		}
		binding.consoleShell.theme = ShellTheme.load(prefs.sharedPreferences)
		// 0 % = black around the video: just the layout of the screen, without the shell's look
		binding.consoleShell.opacity = ConsolePadSettings(prefs.sharedPreferences).shellOpacity
		// after the controls fragment's own observer: the fork's controls stay hidden
		viewModel.onScreenControlsEnabled.observe(this, Observer { applyConsoleMode() })


		if (prefs.colorEnabled)
			binding.effectsToggle.check(R.id.effect_color_button)
		if (prefs.hqEnabled)
			binding.effectsToggle.check(R.id.effect_hq_button)
		if (prefs.debandLightEnabled)
			binding.effectsToggle.check(R.id.effect_deband_button)
		binding.effectsToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
			when (buttonId) {
				R.id.effect_color_button -> {
					prefs.colorEnabled = isChecked
					shaderRenderer?.colorEnabled = isChecked
				}
				R.id.effect_hq_button -> {
					prefs.hqEnabled = isChecked
					shaderRenderer?.hqEnabled = isChecked
				}
				R.id.effect_deband_button -> {
					prefs.debandLightEnabled = isChecked
					shaderRenderer?.debandEnabled = isChecked
				}
			}
			showOverlay()
		}
		// long press on Color: switch preset without leaving the stream, previewing it live
		binding.effectColorButton.setOnLongClickListener {
			openPresetPicker()
			true
		}
		setupPresetPicker()
		setupVolume()
		setupGyroButton()
		binding.overlayBar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
			if(r - l != or - ol)
				binding.overlayBar.post { fitBarButtons() }
		}
		val savedMode = prefs.displayMode
		if (savedMode != TransformMode.FIT)
			binding.displayModeToggle.check(savedMode.buttonId)
		// ✕ is the way out of the stream; Back only shows and hides this bar
		binding.overlayCloseButton.setOnClickListener { showQuitDialog() }
		// long press: statistics over the game on/off (the same switch as in Settings)
		binding.overlayCloseButton.setOnLongClickListener {
			prefs.statsOverlay = !prefs.statsOverlay
			applyStatsOverlay()
			GameplayToast.show(this, if(prefs.statsOverlay) R.string.stats_overlay_on else R.string.stats_overlay_off)
			showOverlay()
			true
		}
		binding.statsOverlay.targetFps = viewModel.session.connectInfo.videoProfile.maxFPS
		applyStatsOverlay()

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, isChecked ->
			// switching between two modes also unchecks the old one first: only act on the new one,
			// or on the last button being unchecked (back to Normal)
			if (!isChecked && binding.displayModeToggle.checkedButtonId != View.NO_ID)
				return@addOnButtonCheckedListener
			val mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
			prefs.displayMode = mode
			// optical lens, the video's place and size, the shell
			applyConsoleMode()
			showOverlay()
		}

		setupVideoOutput()
		
		if (prefs.touchscreenTouchpadEnabled) {
			binding.streamTouchpadView.visibility = View.VISIBLE
			binding.streamTouchpadView.controllerState
				.subscribe { 
					lastStreamTouchpadControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
		}

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		applyConsoleMode()

		// always there: it can be switched on over the game (sound and vibration panel)
		val input = viewModel.input
		val rumble = Rumble(this, prefs) { input.lastControllerId }
		this.rumble = rumble
		viewModel.session.rumbleState.observe(this, Observer { rumble.set(it.left.toInt(), it.right.toInt()) })
	}

	/** the game's vibration */
	private var rumble: Rumble? = null

	private var shaderRenderer: ShaderRenderer? = null

	/**
	 * Video always goes through our GL renderer (all four display modes and effects, switched
	 * live without reattaching the decoder).
	 */
	private fun setupVideoOutput() {
		val prefs = Preferences(this)
		viewModel.session.detachSurface()

		val videoProfile = viewModel.session.connectInfo.videoProfile
		shaderRenderer = ShaderRenderer(assets, "shaders/optical_stretch.frag", videoProfile.width, videoProfile.height,
			ImageSettings.params(prefs.sharedPreferences), ImageSettings.colorParams(prefs.sharedPreferences),
			ImageSettings.debandParams(prefs.sharedPreferences)) { surface ->
			viewModel.session.attachToSurface(surface)
		}
		shaderRenderer!!.opticalEnabled = effectiveMode == TransformMode.OPTICAL
			&& !consoleActive
		val session = viewModel.session
		shaderRenderer!!.onPicture = { session.notePicture() }
		shaderRenderer!!.colorEnabled = prefs.colorEnabled
		shaderRenderer!!.hqEnabled = prefs.hqEnabled
		shaderRenderer!!.debandEnabled = prefs.debandLightEnabled

		binding.videoSurfaceView.setEGLContextClientVersion(3)
		// No alpha: the video is opaque, and an opaque surface is cheaper for Android to put on screen
		// (it does not have to be blended with what lies behind it)
		binding.videoSurfaceView.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
		binding.videoSurfaceView.holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
		binding.videoSurfaceView.setRenderer(shaderRenderer)
		// Draw only when a new picture arrives (or a setting changes), not at every screen refresh: at
		// 120 Hz the whole effect shader ran twice per picture and the phone got hot for nothing.
		binding.videoSurfaceView.renderMode = android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY
		shaderRenderer!!.requestRedraw = { binding.videoSurfaceView.requestRender() }

		// Tell Android the picture changes at the stream's rate, so a 90/120 Hz screen does not run its
		// whole interface (the controls, the case) at double speed for nothing
		// the interface's own frames go to the STATS lines (ui=[...])
		RenderStats.watchWindow(window)

		val streamFps = viewModel.session.connectInfo.videoProfile.maxFPS
		if(streamFps > 0)
		{
			window.attributes = window.attributes.also { it.preferredRefreshRate = streamFps.toFloat() }
			if(android.os.Build.VERSION.SDK_INT >= 30)
			{
				binding.videoSurfaceView.holder.addCallback(object: android.view.SurfaceHolder.Callback {
					override fun surfaceCreated(holder: android.view.SurfaceHolder)
					{
						try { holder.surface.setFrameRate(streamFps.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
						catch(e: Throwable) { }
					}
					override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) { }
					override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { }
				})
			}
		}
	}

	private var lastFragmentControllerState = com.metallic.chiaki.lib.ControllerState()
	private var lastStreamTouchpadControllerState = com.metallic.chiaki.lib.ControllerState()

	private var lastConsolePadControllerState = com.metallic.chiaki.lib.ControllerState()

	private fun updateCombinedTouchState() {
		viewModel.input.touchControllerState =
			lastFragmentControllerState or lastStreamTouchpadControllerState or lastConsolePadControllerState
	}

	// ---------------------------------------------------------------- Console mode

	/**
	 * Phase 13: the shell is a display mode (the video where the layout puts it, over the shell or
	 * black), and the touch controls go on and off on their own in any mode.
	 */
	private val controlsShown get() = viewModel.onScreenControlsEnabled.value == true && !isInPictureInPictureMode
	/** the small window shows the whole picture as usual */
	private val consoleActive get() =
		TransformMode.fromButton(binding.displayModeToggle.checkedButtonId) == TransformMode.SHELL && !isInPictureInPictureMode

	private fun applyConsoleMode()
	{
		val shell = consoleActive
		val controls = controlsShown
		if(!controls && editingLayout)
			stopLayoutEditing()
		// no immersion out of Shell mode or without the controls: straight back to normal
		if(cinema && (!shell || !controls))
		{
			cinema = false
			cinemaFull = false
			resetVideoTransform()
		}
		useWholeScreen(window, shell)
		binding.consoleShell.isVisible = shell
		// hidden but still laid out: the shell takes the layout's sizes from the pad
		binding.consolePad.visibility = if(controls) View.VISIBLE else View.INVISIBLE
		// the new pad replaces the fork's controls
		supportFragmentManager.findFragmentById(R.id.controlsFragment)?.view?.isGone = true
		binding.streamTouchpadView.isVisible = prefs.touchscreenTouchpadEnabled && !controls
		shaderRenderer?.opticalEnabled = effectiveMode == TransformMode.OPTICAL
		adjustStreamViewAspect()
		placeVideo()
		updatePadVideo()
	}

	// ---------------------------------------------------------------- cinematic immersion

	/** the controls faded out and the video fills the screen (Shell mode, cinematic immersion on) */
	private var cinema = false
	/** the glide in has finished: the video is laid out full screen in the chosen mode */
	private var cinemaFull = false
	private val cinemaInterpolator = android.view.animation.AccelerateDecelerateInterpolator()

	private fun cinemaTransform(mode: CinemaMode) = when(mode)
	{
		CinemaMode.FIT, CinemaMode.OFF -> TransformMode.FIT
		CinemaMode.STRETCH -> TransformMode.STRETCH
		CinemaMode.ZOOM -> TransformMode.ZOOM
		CinemaMode.OPTICAL -> TransformMode.OPTICAL
	}

	/** what the video really shows now: the display mode, or the immersion's while it lasts */
	private val effectiveMode get() = when
	{
		consoleActive && cinemaFull -> cinemaTransform(binding.consolePad.settings.cinema)
		consoleActive -> TransformMode.FIT
		else -> TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private val streamAspect get() = viewModel.session.connectInfo.videoProfile.let { it.width.toFloat() / it.height.toFloat() }

	private fun resetVideoTransform()
	{
		val v = binding.aspectRatioLayout
		v.animate().cancel()
		v.scaleX = 1f; v.scaleY = 1f
		v.translationX = 0f; v.translationY = 0f
	}

	/**
	 * The glide is a scale and a move of the video view (the picture keeps rendering); once there,
	 * the view is really laid out full screen in that mode (the optical lens only switches on then)
	 * and the other way round when coming back.
	 */
	private fun setCinema(on: Boolean)
	{
		val mode = binding.consolePad.settings.cinema
		val v = binding.aspectRatioLayout
		val root = binding.mainStreamLayout
		val w = root.width.toFloat()
		val h = root.height.toFloat()
		if(on)
		{
			if(cinema || mode == CinemaMode.OFF || !consoleActive || !controlsShown || editingLayout || w <= 0f || v.width == 0)
				return
			cinema = true
			val t = mode.target(w, h, streamAspect)
			v.animate().cancel()
			v.pivotX = 0f; v.pivotY = 0f
			v.animate()
				.scaleX(t.w / v.width).scaleY(t.h / v.height)
				.translationX(t.x - v.left).translationY(t.y - v.top)
				.setDuration(CINEMA_IN_MS)
				.setInterpolator(cinemaInterpolator)
				.setListener(null)
				.withEndAction {
					if(!cinema) return@withEndAction
					// laid out full screen, and the transform dropped only once that layout is done,
					// right before it is drawn (dropping it earlier showed a frame of the small video)
					cinemaFull = true
					applyConsoleMode()
					beforeNextDraw(v) { if(cinema) resetVideoTransform() }
				}
				.start()
			return
		}
		if(!cinema)
			return
		cinema = false
		v.animate().cancel()
		if(!cinemaFull || !consoleActive || w <= 0f)
		{
			// still gliding in (or Shell mode gone): straight back from wherever it is
			cinemaFull = false
			if(consoleActive)
				v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
					.setDuration(CINEMA_OUT_MS).setInterpolator(cinemaInterpolator).setListener(null).start()
			else
				resetVideoTransform()
			return
		}
		// back in its place in the layout, but drawn where it is now, then gliding home. The
		// transform goes on only once the small layout is done, right before it is drawn: put on
		// earlier, a frame could show the full-screen view with it (a giant picture for a moment)
		cinemaFull = false
		val t = (if(mode == CinemaMode.OFF) CinemaMode.FIT else mode).target(w, h, streamAspect)
		applyConsoleMode()
		beforeNextDraw(v) {
			if(cinema || v.width == 0 || v.height == 0) return@beforeNextDraw
			v.pivotX = 0f; v.pivotY = 0f
			v.scaleX = t.w / v.width; v.scaleY = t.h / v.height
			v.translationX = t.x - v.left; v.translationY = t.y - v.top
			v.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
				.setDuration(CINEMA_OUT_MS).setInterpolator(cinemaInterpolator).setListener(null).start()
		}
	}

	/** runs [action] after the next layout, right before that frame is drawn */
	private fun beforeNextDraw(v: View, action: () -> Unit)
	{
		v.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener
		{
			override fun onPreDraw(): Boolean
			{
				if(v.viewTreeObserver.isAlive)
					v.viewTreeObserver.removeOnPreDrawListener(this)
				action()
				return true
			}
		})
	}

	/**
	 * Nine 60 dp buttons and ✕ fill a 640 dp wide bar to the last few dp: on narrower
	 * screens the buttons shrink evenly, icons kept in the middle.
	 */
	private fun fitBarButtons()
	{
		val bar = binding.overlayBar
		val d = resources.displayMetrics.density
		// the gyroscope button is there only when its option is on
		val gyro = binding.gyroButton.isVisible
		val buttons = listOf(binding.onScreenControlsButton, binding.volumeButton) +
			(if(gyro) listOf(binding.gyroButton) else emptyList()) + listOf(
			binding.effectColorButton, binding.effectHqButton, binding.effectDebandButton,
			binding.displayModeZoomButton, binding.displayModeStretchButton, binding.displayModeOpticalButton,
			binding.displayModeShellButton)
		// margins between the groups (8 + 8 + 16 + 8), ✕ (48) and a little air
		val fixed = (88 + 6 + (if(gyro) 8 else 0)) * d
		val avail = bar.width - bar.paddingLeft - bar.paddingRight - fixed
		if(avail <= 0f)
			return
		val w = min((60 * d).toInt(), (avail / buttons.size).toInt()).coerceAtLeast((40 * d).toInt())
		for(b in buttons)
		{
			if(b.layoutParams.width == w)
				continue
			b.layoutParams = b.layoutParams.also { it.width = w }
			if(w < (60 * d).toInt() && b.icon != null)
			{
				b.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
				b.iconPadding = 0
				b.setPaddingRelative(0, b.paddingTop, 0, b.paddingBottom)
			}
		}
	}

	// ---------------------------------------------------------------- editing the layout live

	/** the same editor as in Settings ("Try the touch pad"), over the game */
	private lateinit var padEditor: PadEditor
	private val editingLayout get() = ::padEditor.isInitialized && padEditor.editing

	private fun setupLayoutEditing()
	{
		padEditor = PadEditor(this, binding.padEditor, binding.consolePad, binding.consoleShell, testScreen = false)
		// the gyroscope stick's options can be tuned live: the panel's changes reach the input, and its button
		binding.consolePad.onSettingsReloaded = { viewModel.input.reloadGyroSettings(); updateGyroButton() }
		padEditor.gyroRecenter = { viewModel.input.gyroStick.recenter() }
		// leaving the editor: the controls go back to how they were (off for whoever only came in for an option)
		padEditor.onEditingChanged = { editing ->
			if(!editing && controlsOffBeforeEditing)
			{
				controlsOffBeforeEditing = false
				viewModel.setOnScreenControlsEnabled(false)
			}
		}
	}

	/** the touch controls were off when the editor opened (it turns them on) */
	private var controlsOffBeforeEditing = false

	/**
	 * The controls send nothing while the editor is open (they would get in the way), and turn on
	 * first if they were off; leaving the editor turns them off again then (2.5.7: its options panel
	 * also holds the gyroscope and the grip, which matter with a real controller too).
	 */
	private fun startLayoutEditing()
	{
		if(editingLayout || isInPictureInPictureMode)
			return
		controlsOffBeforeEditing = viewModel.onScreenControlsEnabled.value != true
		if(controlsOffBeforeEditing)
			viewModel.setOnScreenControlsEnabled(true)
		hideOverlayNow()
		padEditor.startEditing()
	}

	private fun stopLayoutEditing() = padEditor.stopEditing()

	/** without the shell the pad's touchpad is the video wherever the display mode put it */
	private fun updatePadVideo()
	{
		val v = binding.aspectRatioLayout
		binding.consolePad.videoOverride = if(consoleActive || v.width == 0) null
			else com.metallic.chiaki.touchcontrols.console.RectF4(v.left.toFloat(), v.top.toFloat(), v.width.toFloat(), v.height.toFloat())
	}

	/** Console mode: the video where the layout puts it; otherwise the whole screen as usual */
	private fun placeVideo()
	{
		val lp = binding.aspectRatioLayout.layoutParams as android.widget.FrameLayout.LayoutParams
		val root = binding.mainStreamLayout
		val width: Int
		val height: Int
		val gravity: Int
		val left: Int
		val top: Int
		if(consoleActive && !cinemaFull && root.width > 0)
		{
			// from the screen's size now (the pad may still hold the small window's size)
			val r = binding.consolePad.videoRectFor(root.width, root.height)
			width = Math.round(r.w); height = Math.round(r.h)
			gravity = Gravity.TOP or Gravity.START
			left = Math.round(r.x); top = Math.round(r.y)
		}
		else
		{
			width = ViewGroup.LayoutParams.MATCH_PARENT; height = ViewGroup.LayoutParams.MATCH_PARENT
			gravity = Gravity.CENTER
			left = 0; top = 0
		}
		// only when it changes: this also runs from layout passes
		if(lp.width == width && lp.height == height && lp.gravity == gravity && lp.leftMargin == left && lp.topMargin == top)
			return
		lp.width = width; lp.height = height
		lp.gravity = gravity
		lp.leftMargin = left; lp.topMargin = top
		binding.aspectRatioLayout.layoutParams = lp
	}

	private val controlsDisposable = CompositeDisposable()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerState
				.subscribe { 
					lastFragmentControllerState = it
					updateCombinedTouchState()
				}
				.addTo(controlsDisposable)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		keptAliveInPip = false
		keepOnLeave = false
		hideSystemUI()
		StreamDnd.start(this)
		// back from Home with quick reconnect: the session paused in the background is ours again
		// (the picture comes back as soon as the GL surface is attached)
		if(KeptSession.holds(viewModel.session))
			KeptSession.take(viewModel.session.connectInfo)
		binding.videoSurfaceView.onResume()
		viewModel.session.resume()
		uiVisibilityHandler.removeCallbacks(connectionWatchdog)
		uiVisibilityHandler.postDelayed(connectionWatchdog, CONNECTION_WATCHDOG_MS)
	}

	/**
	 * The session does not notice a dead connection (Wi-Fi lost…): the picture just freezes and
	 * nothing reaches the console. No pictures for a few seconds (the console keeps sending them
	 * even when nothing moves) means it is gone: end it and say so, with Reconnect.
	 */
	private val connectionWatchdog = object: Runnable
	{
		override fun run()
		{
			val session = viewModel.session
			if(session.state.value == StreamStateConnected && !KeptSession.holds(session)
				&& !session.receivingPictures && dialogContents == null && !suspending)
				connectionLost()
			uiVisibilityHandler.postDelayed(this, CONNECTION_WATCHDOG_MS)
		}
	}

	private fun connectionLost()
	{
		Log.w("StreamActivity", "No pictures from the console for a while: the connection is gone")
		viewModel.session.shutdown()
		dialog?.dismiss()
		val dialog = MaterialAlertDialogBuilder(this)
			.setMessage(R.string.alert_message_connection_lost)
			.setPositiveButton(R.string.action_reconnect) { _, _ ->
				dialog = null
				reconnect()
			}
			.setNegativeButton(R.string.action_quit_session) { _, _ ->
				dialog = null
				finish()
			}
			.setOnCancelListener {
				dialog = null
				finish()
			}
			.create()
		this.dialog = dialog
		dialogContents = ConnectionLostDialog
		dialog.show()
	}

	override fun onPause()
	{
		super.onPause()
		// notifications come back as soon as the stream leaves the screen (small window too)
		StreamDnd.stop(this)
		// in the small picture-in-picture window the stream goes on
		if(isInPictureInPictureMode)
		{
			keptAliveInPip = true
			return
		}
		// a vibration under way must not outlive the screen (it is renewed while the console asks)
		rumble?.stop()
		// Quitting keeps it only if "Pause and quit" was chosen; going away without quitting (Home
		// without the small window, screen off) keeps it whenever quick reconnect is on
		uiVisibilityHandler.removeCallbacks(connectionWatchdog)
		endOrKeepSession(keep = if(isFinishing) keepOnLeave else prefs.quickReconnectEnabled, screenAlive = !isFinishing)
	}

	/**
	 * The stream leaves the screen: the session ends, or with quick reconnect stays connected in
	 * the background for a while. The video moves to its sink before the GL surface goes away.
	 */
	private fun endOrKeepSession(keep: Boolean, screenAlive: Boolean)
	{
		val kept = keep && !suspending && KeptSession.keep(this, viewModel.session,
			prefs.quickReconnectSeconds * 1000L, prefs.quickReconnectPressPs, screenAlive)
		if(!kept)
			viewModel.session.pause()
		binding.videoSurfaceView.onPause()
	}

	/** "Pause and quit" was chosen: the session is kept for quick reconnect instead of ended */
	private var keepOnLeave = false

	private var keptAliveInPip = false

	override fun onStop()
	{
		super.onStop()
		// The small window was closed (tapping it back to full screen resumes instead): that is
		// leaving, like "Quit". onPause() already went by without ending the session, so it is
		// done here (the fork only finished the activity: the session went on, sound included,
		// and the console stayed "in use" until the app was killed).
		if(keptAliveInPip)
		{
			keptAliveInPip = false
			// the game was visible in the small window until now: closing it is when it pauses
			endOrKeepSession(keep = prefs.quickReconnectEnabled, screenAlive = false)
			finish()
		}
	}

	// Home / app switch during the stream: keep playing in a small 16:9 window instead of ending the
	// session (the console takes a while to free a dropped session before it accepts a new one).
	override fun onUserLeaveHint()
	{
		super.onUserLeaveHint()
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !prefs.pictureInPictureEnabled
			|| !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
			|| suspending || viewModel.session.state.value != StreamStateConnected)
			return
		val videoProfile = viewModel.session.connectInfo.videoProfile
		try
		{
			enterPictureInPictureMode(PictureInPictureParams.Builder()
				.setAspectRatio(Rational(videoProfile.width, videoProfile.height))
				.build())
		}
		catch(e: IllegalStateException)
		{
			// picture-in-picture turned off for this app in the system settings
		}
	}

	override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration)
	{
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
		if(isInPictureInPictureMode)
		{
			if(pickerOriginal != null)
				closePresetPicker()
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			hideOverlay()
			// the button pressed to leave (e.g. Home on the controller) never gets its release
			// here: let go of everything, or the console keeps it held
			viewModel.input.releaseAll()
		}
		// back to full screen: the controls only if the user had them on, and Console mode as it was
		// (the small window shows the whole picture)
		applyConsoleMode()
		applyStatsOverlay()
	}

	/** the statistics over the game, if switched on (never in the small window: too small to read) */
	private fun applyStatsOverlay()
	{
		val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
		binding.statsOverlay.isVisible = prefs.statsOverlay && !inPip
	}

	override fun onDestroy()
	{
		super.onDestroy()
		uiVisibilityHandler.removeCallbacks(finishSuspendRunnable)
		uiVisibilityHandler.removeCallbacks(connectionWatchdog)
		stopBusyWait()
		rumble?.stop()
		shaderRenderer?.release()
		controlsDisposable.dispose()
		StreamDnd.stop(this)
		// a session kept for quick reconnect outlives this screen: its gyroscope must not keep calling (and holding) it
		val gyro = viewModel.input.gyroStick
		if(gyro.onPausedChanged === gyroPausedListener)
			gyro.onPausedChanged = null
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	// Hides the overlay itself too: when the system bars are already hidden (e.g. after
	// tapping a button in the overlay) hideSystemUI() changes nothing, so the visibility
	// callback that would hide the overlay never comes.
	private val hideSystemUIRunnable = Runnable {
		hideSystemUI()
		hideOverlay()
	}

	// Color preset picker: a small panel at the top drawn inside our window (a dialog would bring
	// back the system bars and resize everything). Choosing a preset selects it right away, like
	// Settings → Color does (each preset keeps its own changes, so nothing is lost); Back or a
	// touch outside the panel closes it.
	// --- Game sound: the speaker button mutes; a long press opens a slider (0 % = muted)

	private val hideVolumePickerRunnable = Runnable { binding.volumePicker.isGone = true }

	// 2.6.5: one button for sound and vibration. The panel (long press) chooses what is on; a tap is a
	// total silence and the next tap brings back exactly that. With both off in the panel, a tap turns
	// both on. The icon shows what plays: wavy lines = vibration, ")))" = sound, crossed = nothing.

	private fun setupVolume()
	{
		updateVolumeViews()
		binding.volumeButton.setOnClickListener {
			when
			{
				prefs.streamSilenced -> prefs.streamSilenced = false
				prefs.streamMuted && !prefs.rumbleEnabled ->
				{
					prefs.streamMuted = false
					if(prefs.streamVolume == 0)
						prefs.streamVolume = 100
					prefs.rumbleEnabled = true
				}
				else -> prefs.streamSilenced = true
			}
			applySound()
			GameplayToast.show(this, when
			{
				soundSilent -> R.string.stream_sound_state_silent
				prefs.streamMuted -> R.string.stream_sound_state_vibration
				!prefs.rumbleEnabled -> R.string.stream_sound_state_sound
				else -> R.string.stream_sound_state_both
			})
			showOverlay()
		}
		// the panel's sound icon: the game's sound on or off (touching the panel ends a total silence)
		binding.volumePickerIcon.setOnClickListener {
			prefs.streamSilenced = false
			prefs.streamMuted = !prefs.streamMuted
			// unmuting with the volume at 0 would stay silent: back to full
			if(!prefs.streamMuted && prefs.streamVolume == 0)
				prefs.streamVolume = 100
			applySound()
			keepVolumePicker()
		}
		binding.volumeButton.setOnLongClickListener {
			binding.volumePicker.isVisible = !binding.volumePicker.isVisible
			updateVolumeViews()
			updateRumbleViews()
			keepVolumePicker()
			showOverlay()
			true
		}
		binding.volumeSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener
		{
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
			{
				if(!fromUser)
					return
				prefs.streamSilenced = false
				if(progress == 0)
					prefs.streamMuted = true // the volume before stays, for unmuting
				else
				{
					prefs.streamVolume = progress
					prefs.streamMuted = false
				}
				applySound()
				keepVolumePicker()
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) = keepVolumePicker()
			override fun onStopTrackingTouch(seekBar: SeekBar) = keepVolumePicker()
		})
		setupRumbleRow()
	}

	// --- the game's vibration, below the volume (2.6.0-rc4): the icon switches it on/off, the slider is
	// its strength (0 = off, the strength before stays). Saved like in Settings → General.

	private fun setupRumbleRow()
	{
		binding.rumbleIcon.setOnClickListener {
			setRumble(!prefs.rumbleEnabled)
			keepVolumePicker()
		}
		binding.rumbleSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener
		{
			override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean)
			{
				if(!fromUser)
					return
				if(progress == 0)
					setRumble(false)
				else
				{
					prefs.rumbleStrength = progress.coerceAtLeast(Rumble.STRENGTH_MIN)
					rumble?.strength = prefs.rumbleStrength
					setRumble(true)
				}
				keepVolumePicker()
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) = keepVolumePicker()
			override fun onStopTrackingTouch(seekBar: SeekBar) = keepVolumePicker()
		})
		updateRumbleViews()
	}

	private fun setRumble(on: Boolean)
	{
		prefs.streamSilenced = false
		prefs.rumbleEnabled = on
		applySound()
	}

	private fun updateRumbleViews()
	{
		val on = prefs.rumbleEnabled
		tintRowIcon(binding.rumbleIcon, on && !prefs.streamSilenced)
		val shown = if(on) prefs.rumbleStrength else 0
		if(binding.rumbleSeekBar.progress != shown)
			binding.rumbleSeekBar.progress = shown
		binding.rumbleValue.text = if(on) getString(R.string.percent_value, shown) else getString(R.string.stream_rumble_off)
	}

	/** a row of the sound panel: its icon in the accent colour while that plays, white while it does not */
	private fun tintRowIcon(icon: android.widget.ImageView, on: Boolean)
	{
		icon.alpha = 1f
		icon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(if(on) R.color.accent else R.color.stream_text))
	}

	/** nothing plays: the total silence, or both off in the panel */
	private val soundSilent get() = prefs.streamSilenced || (prefs.streamMuted && !prefs.rumbleEnabled)

	/** the panel's choices and the total silence reach the sound and the vibration, and every view */
	private fun applySound()
	{
		viewModel.session.setVolume(prefs.effectiveStreamVolume)
		rumble?.enabled = prefs.rumbleEnabled && !prefs.streamSilenced
		updateVolumeViews()
		updateRumbleViews()
	}

	// --- Gyroscope as a stick (2.5.3): the bar's button exists only while the option is on. A tap pauses
	// or resumes it for this session, a long press puts the tilt's centre where the phone is.

	/** what this screen hung on the gyroscope (let go in onDestroy: the input may outlive the screen) */
	private var gyroPausedListener: (() -> Unit)? = null

	private fun setupGyroButton()
	{
		val button = binding.gyroButton
		button.isChecked = !viewModel.input.gyroStick.paused
		button.addOnCheckedChangeListener { _, checked ->
			if(checked == !viewModel.input.gyroStick.paused)
				return@addOnCheckedChangeListener
			viewModel.input.gyroStick.paused = !checked
			showOverlay()
		}
		// whoever switches it (this button, the pad's, the controller's own button in Toggle): the views follow
		val gyro = viewModel.input.gyroStick
		val listener = {
			runOnUiThread {
				val on = !gyro.paused
				if(button.isChecked != on)
					button.isChecked = on
				binding.consolePad.gyroOn = on
				if(viewModel.input.gyroStickEnabled)
					GameplayToast.show(this, if(on) R.string.gyro_resumed_toast else R.string.gyro_paused_toast)
			}
		}
		gyroPausedListener = listener
		gyro.onPausedChanged = listener
		binding.consolePad.gyroOn = !gyro.paused
		binding.consolePad.onGyroToggle = { gyro.toggle() }
		button.setOnLongClickListener {
			viewModel.input.gyroStick.recenter()
			GameplayToast.show(this, R.string.gyro_recentered)
			showOverlay()
			true
		}
		updateGyroButton()
	}

	/** shown while the option is on (it can be switched on from the pad's options, over the game) */
	private fun updateGyroButton()
	{
		val on = viewModel.input.gyroStickEnabled
		if(binding.gyroButton.isVisible == on)
			return
		binding.gyroButton.isVisible = on
		binding.overlayBar.post { fitBarButtons() }
	}

	private fun updateVolumeViews()
	{
		val sound = !prefs.streamMuted
		val vibration = prefs.rumbleEnabled
		binding.volumeButton.setIconResource(when
		{
			soundSilent -> R.drawable.ic_sound_silent
			sound && vibration -> R.drawable.ic_sound_vibration
			sound -> R.drawable.ic_sound_only
			else -> R.drawable.ic_vibration_only
		})
		// accent = something plays, white = silence (like the other buttons of the bar: lit = on)
		binding.volumeButton.isChecked = !soundSilent
		binding.volumePickerIcon.setImageResource(if(sound) R.drawable.ic_volume else R.drawable.ic_volume_off)
		tintRowIcon(binding.volumePickerIcon, sound && !prefs.streamSilenced)
		val shown = if(prefs.streamMuted) 0 else prefs.streamVolume
		if(binding.volumeSeekBar.progress != shown)
			binding.volumeSeekBar.progress = shown
		binding.volumeValue.text = getString(R.string.percent_value, shown)
	}

	/** the slider closes by itself a moment after the last touch (and with the bar) */
	private fun keepVolumePicker()
	{
		uiVisibilityHandler.removeCallbacks(hideVolumePickerRunnable)
		uiVisibilityHandler.postDelayed(hideVolumePickerRunnable, VOLUME_PICKER_TIMEOUT_MS)
		// the bar stays up while the volume is being changed
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private var pickerOriginal: ColorPreset? = null // preset when the picker opened; null = closed
	private var pickerEdited = BooleanArray(0) // presets that keep user changes ("Name*")
	private var pickerIndex = 0
	private val pickerNames by lazy { resources.getStringArray(R.array.preferences_color_preset_entries) }

	private fun setupPresetPicker()
	{
		val count = ColorPreset.values().size
		binding.presetPrevButton.setOnClickListener { previewPreset((pickerIndex + count - 1) % count) }
		binding.presetNextButton.setOnClickListener { previewPreset((pickerIndex + 1) % count) }
		binding.presetName.setOnClickListener {
			binding.presetList.isVisible = !binding.presetList.isVisible
			if(binding.presetList.isVisible)
				binding.presetList.post {
					binding.presetList.scrollTo(0, binding.presetListItems.getChildAt(pickerIndex).top)
				}
		}

		val rowHeight = (36 * resources.displayMetrics.density).toInt()
		val rowBackground = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
		for(i in 0 until count)
		{
			binding.presetListItems.addView(TextView(this).apply {
				gravity = Gravity.CENTER
				textSize = 15f
				setBackgroundResource(rowBackground)
				setOnClickListener {
					previewPreset(i)
					binding.presetList.isGone = true
				}
			}, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight))
		}
	}

	private fun openPresetPicker()
	{
		val sharedPrefs = prefs.sharedPreferences
		val original = ImageSettings.colorPreset(sharedPrefs)
		pickerOriginal = original
		pickerEdited = ColorPreset.values().map { ImageSettings.colorPresetEdited(sharedPrefs, it) }.toBooleanArray()
		hideOverlay()
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		binding.presetList.isGone = true
		binding.presetPicker.isVisible = true
		shaderRenderer?.colorEnabled = true
		previewPreset(original.ordinal)
	}

	private fun previewPreset(index: Int)
	{
		val sharedPrefs = prefs.sharedPreferences
		pickerIndex = index
		val preset = ColorPreset.values()[index]
		// as the preset was left, with the user's changes if it has any
		if(preset != ImageSettings.colorPreset(sharedPrefs))
			ImageSettings.selectColorPreset(sharedPrefs, preset)
		applyLook(ImageSettings.storedColorValues(sharedPrefs, preset))

		fun label(i: Int) = pickerNames[i] + if(pickerEdited[i]) "*" else ""
		binding.presetName.text = label(index)
		for(i in 0 until binding.presetListItems.childCount)
		{
			(binding.presetListItems.getChildAt(i) as TextView).apply {
				text = label(i)
				setTextColor(getColor(if(i == index) R.color.accent else R.color.stream_text))
				setTypeface(null, if(i == index) Typeface.BOLD else Typeface.NORMAL)
			}
		}
	}

	/** Color grading and vignette of a preset, applied on the next frame. */
	private fun applyLook(values: ColorValues) =
		shaderRenderer?.setColorParams(values.params(), values.vignetteAmount, values.vignetteStartFraction, values.vignetteAxis)

	/** The chosen preset is already selected; a new choice also turns Color on (it is what the user saw). */
	private fun closePresetPicker()
	{
		val changed = ColorPreset.values()[pickerIndex] != pickerOriginal
		pickerOriginal = null
		binding.presetPicker.isGone = true
		applyLook(ImageSettings.colorValues(prefs.sharedPreferences))
		if(changed)
			binding.effectsToggle.check(R.id.effect_color_button)
		else
			shaderRenderer?.colorEnabled = prefs.colorEnabled
		showOverlay()
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed()
	{
		if(pickerOriginal != null)
		{
			closePresetPicker()
			return
		}
		if(::padEditor.isInitialized && padEditor.handleBack())
			return
		if(suspending)
			return
		// Back (the handheld's button or the phone's edge swipe) shows the bar and, with the bar
		// open, asks to quit (one more Back cancels, as with ✕). The swipe may also bring back the
		// system bars, which already show the bar a moment earlier: that Back only counts as showing it.
		if(overlayShown && SystemClock.uptimeMillis() - overlayShownAt > BACK_AFTER_SHOW_MS)
			showQuitDialog()
		else
			showOverlay()
	}

	private fun toggleOverlay()
	{
		if(pickerOriginal != null)
			return
		if(overlayShown)
			hideOverlayNow()
		else
			showOverlay()
	}

	private fun hideOverlayNow()
	{
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		hideSystemUIRunnable.run()
	}

	private fun showQuitDialog()
	{
		if(suspending)
			return
		// Plain Quit is the default button; suspending sits apart so it is always deliberate.
		// With quick reconnect, quitting pauses: closing for real takes the place of Cancel
		// (Back or a tap outside still cancels).
		if(prefs.quickReconnectEnabled)
		{
			val entries = resources.getStringArray(R.array.preferences_quick_reconnect_seconds_entries)
			val duration = entries.getOrNull(prefs.quickReconnectSecondsAll.indexOf(prefs.quickReconnectSeconds)) ?: ""
			MaterialAlertDialogBuilder(this)
				.setMessage(getString(R.string.alert_message_quit_stream_kept, duration))
				.setPositiveButton(R.string.action_pause_and_quit) { _, _ ->
					keepOnLeave = true
					finish()
				}
				.setNegativeButton(R.string.action_close_session) { _, _ -> finish() }
				.setNeutralButton(R.string.action_suspend_and_quit) { _, _ -> suspendAndQuit() }
				.show()
			return
		}
		MaterialAlertDialogBuilder(this)
			.setMessage(R.string.alert_message_quit_stream)
			.setPositiveButton(R.string.action_quit_session) { _, _ -> finish() }
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(R.string.action_suspend_and_quit) { _, _ -> suspendAndQuit() }
			.show()
	}

	private var suspending = false

	private val finishSuspendRunnable = Runnable {
		Toast.makeText(this, R.string.toast_console_suspended, Toast.LENGTH_SHORT).show()
		finish()
	}

	/** Sends the console to rest mode, waits for it to drop the session (at most 2 s) and quits anyway. */
	private fun suspendAndQuit()
	{
		if(suspending)
			return
		if(!viewModel.session.gotoBed())
		{
			finish()
			return
		}
		suspending = true
		binding.progressBar.visibility = View.VISIBLE
		uiVisibilityHandler.postDelayed(finishSuspendRunnable, SUSPEND_TIMEOUT_MS)
	}

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private var overlayShown = false
	private var overlayShownAt = 0L

	private fun showOverlay()
	{
		// the bar stays away while the preset picker is open, and in the small window (shrinking
		// into it shows the system bars, which used to bring the bar back, squeezed in a corner)
		if(pickerOriginal != null || isInPictureInPictureMode || editingLayout)
			return
		if(!overlayShown)
			overlayShownAt = SystemClock.uptimeMillis()
		overlayShown = true
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		overlayShown = false
		uiVisibilityHandler.removeCallbacks(hideVolumePickerRunnable)
		binding.volumePicker.isGone = true
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
		// A dialog (quit, retry…) or the system takes the input: what is held now (a stick
		// pushed to run) would never get its release here and the console would keep it held.
		// Nothing goes to the console until the stream has the focus again (starting from idle).
		viewModel.input.blocked = !hasFocus
	}

	private fun hideSystemUI()
	{
		// game mode: an edge swipe only peeks the system bars (a second one acts), so stray
		// touches do not bring them up; otherwise they come up and the stream's bar with them
		window.decorView.systemUiVisibility = ((if(prefs.gameMode) View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_IMMERSIVE)
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	/** When the console started answering "already in use"; 0 = not waiting */
	private var busySinceMs = 0L
	private val busyRetryRunnable = Runnable { reconnect() }
	private val busyTickRunnable = object: Runnable
	{
		override fun run()
		{
			updateBusyMessage()
			uiVisibilityHandler.postDelayed(this, 1000)
		}
	}

	private fun busyMessage() = getString(R.string.alert_message_console_busy,
		((SystemClock.elapsedRealtime() - busySinceMs) / 1000).toInt())

	private fun updateBusyMessage()
	{
		if(dialogContents == ConsoleBusyDialog)
			dialog?.setMessage(busyMessage())
	}

	/** @return false once the wait is over: the normal error dialog takes over */
	private fun handleConsoleBusy(): Boolean
	{
		val now = SystemClock.elapsedRealtime()
		if(busySinceMs == 0L)
			busySinceMs = now
		// with quick reconnect another device may hold its paused session that long: never give up before
		val maxMs = if(prefs.quickReconnectEnabled) maxOf(BUSY_MAX_MS, prefs.quickReconnectSeconds * 1000L + 60_000L) else BUSY_MAX_MS
		if(now - busySinceMs > maxMs)
		{
			stopBusyWait()
			return false
		}
		if(dialogContents != ConsoleBusyDialog)
		{
			dialog?.dismiss()
			val dialog = MaterialAlertDialogBuilder(this)
				.setMessage(busyMessage())
				.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ ->
					dialog = null
					stopBusyWait()
					finish()
				}
				.setOnCancelListener {
					dialog = null
					stopBusyWait()
					finish()
				}
				.create()
			// keep it, so the countdown can update it and a successful retry can close it
			this.dialog = dialog
			dialogContents = ConsoleBusyDialog
			dialog.show()
			uiVisibilityHandler.removeCallbacks(busyTickRunnable)
			uiVisibilityHandler.postDelayed(busyTickRunnable, 1000)
		}
		uiVisibilityHandler.removeCallbacks(busyRetryRunnable)
		uiVisibilityHandler.postDelayed(busyRetryRunnable, BUSY_RETRY_MS)
		return true
	}

	private fun stopBusyWait()
	{
		uiVisibilityHandler.removeCallbacks(busyRetryRunnable)
		uiVisibilityHandler.removeCallbacks(busyTickRunnable)
		busySinceMs = 0L
		if(dialogContents == ConsoleBusyDialog)
		{
			dialog?.dismiss()
			dialog = null
		}
	}

	private fun stateChanged(state: StreamState)
	{
		if(suspending)
		{
			// the console drops the session when it goes to rest: that is the expected end, not an error
			if(state is StreamStateQuit)
			{
				uiVisibilityHandler.removeCallbacks(finishSuspendRunnable)
				finishSuspendRunnable.run()
			}
			return
		}

		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		// "already in use" right after leaving: the console needs some seconds to let the old
		// session go, so keep retrying with a clear message instead of a dead-end error
		if(state is StreamStateQuit && state.reason.isRpInUse && handleConsoleBusy())
			return
		if(state is StreamStateConnected || (state is StreamStateQuit && !state.reason.isRpInUse))
			stopBusyWait()

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			// These states don't need special handling
			StreamStateIdle, StreamStateConnecting, StreamStateConnected -> { }
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode =
			effectiveMode
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	private val overlayBarRect = Rect()
	private val presetPickerRect = Rect()
	private var threeFingerGesture = false
	private var pickerClosingGesture = false

	override fun dispatchTouchEvent(event: MotionEvent): Boolean
	{
		// with the preset picker open, a touch outside it closes it and goes no further (not to the
		// console either): phones have no Back button that is easy to use mid-game
		if(event.actionMasked == MotionEvent.ACTION_DOWN)
		{
			pickerClosingGesture = pickerOriginal != null
				&& !(binding.presetPicker.getGlobalVisibleRect(presetPickerRect)
					&& presetPickerRect.contains(event.x.toInt(), event.y.toInt()))
			if(pickerClosingGesture)
				closePresetPicker()
		}
		if(pickerClosingGesture)
			return true

		// A third finger shows/hides the bar (phones have no Back button that is easy to use
		// mid-game). The fingers already down are cancelled so the touch controls and the touchpad
		// let go, and the rest of the gesture never reaches them nor the console.
		// Not with the shell on: there the thumbs are on the screen all the time and three fingers
		// at once are normal play (Back still shows the bar).
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN -> threeFingerGesture = false
			MotionEvent.ACTION_POINTER_DOWN -> if(!threeFingerGesture && event.pointerCount == 3 && !consoleActive && !editingLayout)
			{
				threeFingerGesture = true
				val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
				super.dispatchTouchEvent(cancel)
				cancel.recycle()
				toggleOverlay()
			}
		}
		if(threeFingerGesture)
			return true

		// any touch on the bar keeps it up for another full timeout
		if(overlayShown && (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
			&& ((binding.overlayBar.getGlobalVisibleRect(overlayBarRect)
					&& overlayBarRect.contains(event.getX(event.actionIndex).toInt(), event.getY(event.actionIndex).toInt()))
				|| (binding.volumePicker.isVisible && binding.volumePicker.getGlobalVisibleRect(overlayBarRect)
					&& overlayBarRect.contains(event.getX(event.actionIndex).toInt(), event.getY(event.actionIndex).toInt()))))
		{
			uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
			uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
		}
		return super.dispatchTouchEvent(event)
	}

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode(val value: String, val buttonId: Int)
{
	// Normal has no button: it is what is left when no mode button is checked
	FIT("fit", View.NO_ID),
	STRETCH("stretch", R.id.display_mode_stretch_button),
	ZOOM("zoom", R.id.display_mode_zoom_button),
	// fills the view like STRETCH; the shader does the reframing
	OPTICAL("optical", R.id.display_mode_optical_button),
	// the video where the touch pad's layout puts it, over the shell (phase 13: a display mode,
	// no longer tied to the touch controls)
	SHELL("shell", R.id.display_mode_shell_button);

	companion object
	{
		fun fromButton(displayModeButtonId: Int) = values().find { it.buttonId == displayModeButtonId } ?: FIT
		fun fromValue(value: String?) = values().find { it.value == value } ?: FIT
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH, TransformMode.OPTICAL -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT, TransformMode.SHELL -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
