// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.touchcontrols.ButtonHaptics
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The console-mode touch pad: one view for every control, so a finger can slide from one
 * button to the next (and press two at once in between), and a finger that lands on a stick
 * or trigger zone keeps that job until it is lifted (it can leave the zone without pressing
 * whatever is next to it).
 */
class ConsolePadView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	companion object
	{
		private const val CLICK_MS = 90L
		/** how far outside a button a sliding finger still presses it */
		private const val BUTTON_TOLERANCE_MM = 1.5f
		/** "drag like a mouse": finger speed that pushes the stick all the way */
		private const val DRAG_FULL_SPEED_MM_S = 80f
		private const val HANDLE_MM = 7f
		private const val SQUASH_SETTLE_MS = 150L
		/** while a squash holds a button, how often its release is judged again without new touch events */
		private const val SQUASH_RECHECK_MS = 30L
		private const val TOUCH_EDIT_COLOR = 0xFFFF6FD8.toInt()
		private const val LAMP_FADE_MS = 450L
		private const val KEY_CORNERS = "console_pad_edit_corners"
		/** the axes of a group's four buttons: up, right, down, left */
		private val AXIS_X = floatArrayOf(0f, 1f, 0f, -1f)
		private val AXIS_Y = floatArrayOf(-1f, 0f, 1f, 0f)
		/** what a tap landed on, besides a zone */
		private val VIDEO = Any()
		private val TOUCH = Any()
	}

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	var padLayout = ConsolePadLayout.load(prefs)
		private set(value)
		{
			field = value
			value.unavailable = unavailableZones()
		}
	val settings = ConsolePadSettings(prefs, hasGyroscope(context))

	/** the gyroscope button is of no use while the gyroscope stick is off: it goes away (and comes back where it was) */
	private fun unavailableZones() = if(settings.gyroEnabled) emptySet() else setOf("gyro")

	init { padLayout.unavailable = unavailableZones() }
	private val haptics = ButtonHaptics(context)

	var geometry = PadGeometry(1f, 1f, resources.displayMetrics)
		private set

	/** every change of what the pad holds */
	var stateListener: ((ControllerState) -> Unit)? = null
	/** the layout changed (edited or reset): the video may have moved */
	var onLayoutChanged: (() -> Unit)? = null

	var editMode = false
		set(value)
		{
			if(field == value) return
			cancelAll()
			field = value
			// zones are moved in place while editing: the kept pictures are stale after
			lookChanged()
			dropPictures()
			if(!value)
			{
				editTarget = EditTarget.LAYOUT
				videoRaised = false
			}
			wake()
			invalidate()
		}

	// ---------------------------------------------------------------- fading out when untouched

	/** the controls faded out (true) or came back: the shell follows */
	var onIdleChanged: ((Boolean) -> Unit)? = null
	private var idle = false
	private val idleRunnable = Runnable { goIdle() }

	/** a touch: everything back at once (the touch itself works as usual, even while invisible) */
	private fun wake()
	{
		removeCallbacks(idleRunnable)
		if(idle)
		{
			idle = false
			animate().cancel()
			animate().alpha(1f).setDuration(120L).start()
			onIdleChanged?.invoke(false)
		}
		scheduleIdle()
	}

	/** the options panel is open over the pad: it never fades out meanwhile */
	var idleBlocked = false
		set(value) { field = value; wake() }
	/** a dialog (or the system) has the screen: same */
	private val blocked get() = idleBlocked || !hasWindowFocus()

	override fun onWindowFocusChanged(hasWindowFocus: Boolean)
	{
		super.onWindowFocusChanged(hasWindowFocus)
		wake()
	}

	private fun scheduleIdle()
	{
		removeCallbacks(idleRunnable)
		if(settings.autoHideS > 0 && !editMode && fingers.isEmpty() && !blocked)
			postDelayed(idleRunnable, settings.autoHideS * 1000L)
	}

	private fun goIdle()
	{
		// not while held, edited, or hidden (controls off: the shell must stay)
		if(idle || fingers.isNotEmpty() || editMode || blocked || visibility != VISIBLE || settings.autoHideS <= 0)
			return
		idle = true
		animate().cancel()
		animate().alpha(0f).setDuration(700L).start()
		onIdleChanged?.invoke(true)
	}

	override fun onAttachedToWindow()
	{
		super.onAttachedToWindow()
		scheduleIdle()
	}
	/** while editing, a zone and its partner on the other side move together */
	var symmetric = true

	/**
	 * Where the video really is when it is not the console layout's (the pad over the full-screen
	 * video in any display mode): the touchpad maps to it.
	 */
	var videoOverride: RectF4? = null

	/** the video can only be moved when it is the layout's (Shell mode), not the display mode's */
	val videoEditable get() = videoOverride == null

	/** while the video is being moved, it is not held back by the gap it had under it */
	val videoRect get() = videoOverride ?: geometry.video(padLayout.video, keepBottom = !videoBeingEdited)
	private val videoBeingEdited get() = fingers.values.any { it.mode == Mode.EDIT_VIDEO }

	/**
	 * Where the layout puts the video on a screen of this size (from the size now, not from the
	 * last time this view was measured: coming back from the small window that was tiny).
	 */
	fun videoRectFor(width: Int, height: Int) =
		PadGeometry(width.toFloat(), height.toFloat(), resources.displayMetrics).video(padLayout.video)

	fun resetLayout()
	{
		cancelAll()
		ConsolePadLayout.reset(prefs)
		padLayout = ConsolePadLayout.load(prefs)
		lookChanged()
		invalidate()
		onLayoutChanged?.invoke()
	}

	/** a slot loaded, or the controls switched on/off: becomes the current layout */
	fun applyLayout(layout: ConsolePadLayout)
	{
		cancelAll()
		padLayout = layout
		layout.save(prefs)
		lookChanged()
		invalidate()
		onLayoutChanged?.invoke()
	}

	/** the current layout changed in place (controls on/off): save and redraw */
	fun layoutChanged()
	{
		cancelAll()
		padLayout.save(prefs)
		lookChanged()
		invalidate()
		onLayoutChanged?.invoke()
	}

	/** the options changed (the editor's panel): whoever uses the gyroscope as a stick reloads its own copy */
	var onSettingsReloaded: (() -> Unit)? = null

	fun reloadSettings()
	{
		cancelAll()
		settings.reload()
		val before = padLayout.unavailable
		padLayout.unavailable = unavailableZones()
		if(before != padLayout.unavailable)
			onLayoutChanged?.invoke()
		wake()
		lookChanged()
		invalidate()
		onSettingsReloaded?.invoke()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		geometry = PadGeometry(w.toFloat(), h.toFloat(), resources.displayMetrics)
		lookChanged()
		onLayoutChanged?.invoke()
	}

	override fun onVisibilityChanged(changedView: View, visibility: Int)
	{
		super.onVisibilityChanged(changedView, visibility)
		// hidden (controls off, small window…): nothing may stay held
		if(visibility != VISIBLE)
		{
			cancelAll()
			dropPictures()
		}
		// shown again: from fully visible; hidden: the shell must not stay faded
		wake()
	}

	override fun onDetachedFromWindow()
	{
		removeCallbacks(idleRunnable)
		cancelAll()
		dropPictures()
		super.onDetachedFromWindow()
	}

	// ---------------------------------------------------------------- what the pad holds

	private val counts = HashMap<UInt, Int>()
	private fun isDown(b: UInt) = (counts[b] ?: 0) > 0

	private fun press(b: UInt, harder: Boolean = false)
	{
		val c = (counts[b] ?: 0) + 1
		counts[b] = c
		if(c == 1)
		{
			haptics.trigger(harder)
			if(b == BUTTON_GYRO)
				onGyroToggle?.invoke()
		}
	}

	/** the gyroscope button was pressed: the host switches the gyroscope stick (the console never sees this button) */
	var onGyroToggle: (() -> Unit)? = null

	/** the gyroscope stick is on: the button draws crossed out when it is not */
	var gyroOn = true
		set(value)
		{
			if(field == value)
				return
			field = value
			lookChanged()
			invalidate()
		}

	private fun release(b: UInt)
	{
		val c = counts[b] ?: return
		if(c <= 1) counts.remove(b) else counts[b] = c - 1
	}

	/** a short press (L3/R3 from a double tap, squash or leaving the zone) */
	/** short clicks still running (they let go by themselves) */
	private val clicking = HashMap<UInt, Int>()

	private fun click(b: UInt)
	{
		press(b, harder = true)
		clicking[b] = (clicking[b] ?: 0) + 1
		postDelayed({ endClick(b); release(b); emit() }, CLICK_MS)
	}

	private fun endClick(b: UInt)
	{
		val c = clicking[b] ?: return
		if(c <= 1) clicking.remove(b) else clicking[b] = c - 1
	}

	private class Stick
	{
		var x = 0f; var y = 0f
		/** "drag like a mouse": where x/y are heading */
		var tx = 0f; var ty = 0f
		var finger = -1
		var releasedAt = -1_000_000L
		var lastX = 0f; var lastY = 0f
		var lastKind: ZoneKind? = null
		/** coyote time: the stick stays where it was for a moment after the finger is lifted */
		var hold = false
	}
	private val left = Stick()
	private val right = Stick()
	private fun stick(side: Side) = if(side == Side.R) right else left

	private var lastState = ControllerState()

	private fun quantize(f: Float) = (Short.MAX_VALUE * f.coerceIn(-1f, 1f)).toInt().toShort()

	private val sides = arrayOf(Side.L, Side.R)

	/** what a stick sends: as it is, or (drag style) pushed a little past the game's dead zone */
	private fun stickOutput(side: Side): Pair<Float, Float>
	{
		val st = stick(side)
		if(settings.style(side) == StickStyle.STICK)
			return st.x to st.y
		val m = hypot(st.x, st.y)
		if(m < 0.02f)
			return 0f to 0f
		val dz = settings.antiDeadzone(side)
		val out = dz + (1f - dz) * min(1f, m)
		return st.x / m * out to st.y / m * out
	}

	private fun triggerLevel(b: UInt): Float
	{
		var level = 0f
		for(f in fingers.values)
		{
			if(f.trigger == b)
				level = max(level, f.triggerLevel)
			// held by squashing: fully pulled
			if(f.squashHeld == b)
				level = 1f
		}
		return level
	}

	private fun triggerValue(b: UInt): UByte =
		if(!isDown(b)) 0U else (triggerLevel(b) * 255f).toInt().coerceIn(1, 255).toUByte()

	// ---------------------------------------------------------------- the video is the touchpad

	/** a touch on the video (outside every zone) is a touchpad touch; a short tap also clicks it */
	var touchpadEnabled = true
	private val touchState = ControllerState()
	private val touchTapMs = 250L
	private val touchTapMoveMm = 4f

	/** the part of the video that is the touchpad (all of it unless the layout says otherwise) */
	val touchRect get() = padLayout.touch.rect(videoRect, geometry.width, geometry.height)

	private fun touchX(x: Float): UShort
	{
		val v = touchRect
		return (((x - v.x) / v.w) * ControllerState.TOUCHPAD_WIDTH.toFloat()).toInt()
			.coerceIn(0, ControllerState.TOUCHPAD_WIDTH.toInt() - 1).toUShort()
	}

	private fun touchY(y: Float): UShort
	{
		val v = touchRect
		return (((y - v.y) / v.h) * ControllerState.TOUCHPAD_HEIGHT.toFloat()).toInt()
			.coerceIn(0, ControllerState.TOUCHPAD_HEIGHT.toInt() - 1).toUShort()
	}

	private fun touchDown(f: Finger)
	{
		val id = touchState.startTouch(touchX(f.x), touchY(f.y)) ?: return
		f.mode = Mode.TOUCHPAD
		f.touchId = id
		f.startX = f.x; f.ly = f.y; f.t0 = SystemClock.uptimeMillis()
		f.trail.clear()
		f.trail.add(TrailPoint(f.x, f.y, f.t0))
	}

	private fun touchUp(f: Finger)
	{
		val id = f.touchId
		val moved = hypot(f.x - f.startX, f.y - f.ly) / geometry.mm
		if(SystemClock.uptimeMillis() - f.t0 < touchTapMs && moved < touchTapMoveMm)
		{
			// a tap: the touch stays down while the click lasts, like on the controller
			press(ControllerState.BUTTON_TOUCHPAD)
			clicking[ControllerState.BUTTON_TOUCHPAD] = (clicking[ControllerState.BUTTON_TOUCHPAD] ?: 0) + 1
			postDelayed({
				endClick(ControllerState.BUTTON_TOUCHPAD)
				release(ControllerState.BUTTON_TOUCHPAD)
				touchState.stopTouch(id)
				emit()
			}, CLICK_MS + 30)
		}
		else
			touchState.stopTouch(id)
	}

	private fun emit()
	{
		var buttons = 0U
		for((b, c) in counts)
			if(c > 0 && b != BUTTON_L2 && b != BUTTON_R2 && b != BUTTON_GYRO)
				buttons = buttons or b
		val (lx, ly) = stickOutput(Side.L)
		val (rx, ry) = stickOutput(Side.R)
		val state = ControllerState(
			buttons = buttons,
			l2State = triggerValue(BUTTON_L2),
			r2State = triggerValue(BUTTON_R2),
			leftX = quantize(lx),
			leftY = quantize(ly),
			rightX = quantize(rx),
			rightY = quantize(ry),
			touches = touchState.touches.map { it.copy() }.toTypedArray())
		if(state != lastState)
		{
			lastState = state
			stateListener?.invoke(state)
		}
	}

	// ---------------------------------------------------------------- fingers

	/**
	 * CLUSTER: worked a button group like a stick; FREE: landed on empty space, the first group it
	 * slides into takes it
	 */
	private enum class Mode { NONE, STICK, SLIDE, FIXED, CLUSTER, FREE, TOUCHPAD, EDIT_ZONE, EDIT_VIDEO, EDIT_TOUCH }

	private class Finger(val id: Int, var x: Float, var y: Float, var size: Float)
	{
		var mode = Mode.NONE
		var zone: Zone? = null
		var side = Side.L
		var asStick = true
		var trigger = 0U
		/** how far the trigger is pulled, 0..1 (always 1 unless "pressure" is on) */
		var triggerLevel = 1f
		var ox = 0f; var oy = 0f
		var lx = 0f; var ly = 0f; var lt = 0L
		var size0 = 0f
		/** the other contact readings Android gives (for the test screen's numbers) */
		var minor = 0f; var pressure = 0f; var sizeN = 0f
		var t0 = 0L
		/** STICK: reads when the thumb squashes (only when something is set to happen) */
		var squash: SquashDetector? = null
		/** STICK: the button the squash holds now, 0 = none */
		var squashHeld = 0U
		var edgeArmed = true
		val pressed = HashSet<UInt>()
		/** CLUSTER: what the finger pressed last time it was inside the drawing */
		var last: List<Int> = emptyList()
		var resize = false
		/** EDIT: which corner is being dragged, and the opposite one that stays (ax, ay) */
		var corner = 0
		var ax = 0f; var ay = 0f
		var moved = false
		var startX = 0f
		var startY = 0f
		var touchId: UByte = 0U
		/** TOUCHPAD: where the finger went lately, drawn as a fading trail */
		val trail = ArrayList<TrailPoint>()
	}
	private class TrailPoint(val x: Float, val y: Float, val t: Long)
	private val fingers = HashMap<Int, Finger>()

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				wake()
				val i = event.actionIndex
				val id = event.getPointerId(i)
				// fingers still listed when the first one lands, or one with the same id: their release
				// never arrived (the system took the touch…), so they are let go now instead of staying held
				if(event.actionMasked == MotionEvent.ACTION_DOWN && fingers.isNotEmpty())
					dropLostFingers(emptySet())
				fingers.remove(id)?.let { up(it) }
				down(Finger(id, event.getX(i), event.getY(i), event.getTouchMajor(i)).also {
					it.minor = event.getTouchMinor(i); it.pressure = event.getPressure(i); it.sizeN = event.getSize(i) })
			}
			MotionEvent.ACTION_MOVE -> {
				if(fingers.size > event.pointerCount)
					dropLostFingers((0 until event.pointerCount).map { event.getPointerId(it) }.toSet())
				for(i in 0 until event.pointerCount)
				{
					val f = fingers[event.getPointerId(i)] ?: continue
					f.x = event.getX(i); f.y = event.getY(i); f.size = event.getTouchMajor(i)
						f.minor = event.getTouchMinor(i); f.pressure = event.getPressure(i); f.sizeN = event.getSize(i)
					move(f)
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				fingers.remove(event.getPointerId(event.actionIndex))?.let { leaveLight(it); up(it) }
				if(event.actionMasked == MotionEvent.ACTION_UP)
				{
					// no finger left on the screen: nothing may stay held, whatever got lost on the way
					if(fingers.isNotEmpty())
						dropLostFingers(emptySet())
					releaseLeftovers()
				}
				scheduleIdle()
			}
			MotionEvent.ACTION_CANCEL -> { cancelAll(); scheduleIdle() }
		}
		emit()
		invalidate()
		return true
	}

	/** fingers the system no longer reports (their release got lost): let go as if lifted */
	private fun dropLostFingers(alive: Set<Int>)
	{
		val lost = fingers.keys.filter { it !in alive }
		if(lost.isEmpty())
			return
		android.util.Log.w("ConsolePadView", "Letting go of ${lost.size} finger(s) whose release never arrived")
		lost.forEach { id -> fingers.remove(id)?.let { up(it) } }
	}

	/**
	 * With no finger on the screen only the short clicks still running (L3/R3, touchpad tap) may be
	 * held, and no stick may be pushed except during its coyote time.
	 */
	private fun releaseLeftovers()
	{
		val stuck = counts.keys.filter { (counts[it] ?: 0) > (clicking[it] ?: 0) }
		if(stuck.isNotEmpty())
		{
			android.util.Log.w("ConsolePadView", "Buttons still held with no finger down: $stuck")
			stuck.forEach { b -> val keep = clicking[b] ?: 0; if(keep > 0) counts[b] = keep else counts.remove(b) }
		}
		for(st in listOf(left, right))
		{
			if(st.finger >= 0)
			{
				android.util.Log.w("ConsolePadView", "A stick was still held with no finger down")
				st.finger = -1
				if(!st.hold) { st.x = 0f; st.y = 0f }
				st.tx = 0f; st.ty = 0f
			}
		}
	}

	/** lets go of everything (cancelled touch, edit mode, leaving the screen) */
	fun cancelAll()
	{
		removeCallbacks(squashRecheck)
		fingers.values.forEach { f ->
			f.pressed.forEach { release(it) }
			if(f.trigger != 0U) release(f.trigger)
		}
		fingers.clear()
		fadingLights.clear()
		fadingTouches.clear()
		touchState.touches.forEach { it.id = -1 }
		for(s in listOf(left, right))
		{
			s.x = 0f; s.y = 0f; s.tx = 0f; s.ty = 0f; s.finger = -1; s.hold = false
			s.releasedAt = -1_000_000L
		}
		emit()
		invalidate()
	}

	private fun hit(x: Float, y: Float): Zone? =
		padLayout.active.lastOrNull { geometry.rect(it).contains(x, y) }

	private fun down(f: Finger)
	{
		fingers[f.id] = f
		if(editMode)
		{
			editDown(f)
			return
		}
		val z = hit(f.x, f.y)
		if(z == null)
		{
			if(touchpadEnabled && padLayout.touchpadOn && touchRect.contains(f.x, f.y))
				touchDown(f)
			else
				f.mode = Mode.FREE
			return
		}
		startZone(f, z)
	}

	/** the finger takes the job of [z]: landing on it, or sliding into it from outside */
	private fun startZone(f: Finger, z: Zone)
	{
		when(z.kind)
		{
			ZoneKind.STICK -> startStick(f, Side.L, z)
			ZoneKind.CAM -> startStick(f, Side.R, z)
			ZoneKind.TRIGGER -> {
				f.trigger = z.button
				f.triggerLevel = if(settings.triggerPressure(z.side)) settings.triggerBase else 1f
				press(z.button)
				startStick(f, z.side, z)
			}
			ZoneKind.BUTTON -> if(z.fixed)
			{
				f.mode = Mode.FIXED
				f.pressed.add(z.button)
				press(z.button)
			}
			else
			{
				f.mode = Mode.SLIDE
				slide(f)
			}
			ZoneKind.DPAD, ZoneKind.FACE -> {
				f.mode = Mode.CLUSTER
				f.zone = z
				clusterMove(f)
			}
		}
	}

	private fun move(f: Finger)
	{
		when(f.mode)
		{
			Mode.STICK -> moveStick(f)
			Mode.SLIDE -> slide(f)
			Mode.CLUSTER -> clusterMove(f)
			Mode.FREE -> capture(f)
			Mode.TOUCHPAD -> if(touchRect.contains(f.x, f.y))
			{
				touchState.setTouchPos(f.touchId, touchX(f.x), touchY(f.y))
				f.trail.add(TrailPoint(f.x, f.y, SystemClock.uptimeMillis()))
			}
			else
			{
				// the touchpad never holds a finger: out of its area the touch ends and the finger is
				// free again, for whatever control it reaches (a game that ignores the touchpad must
				// not leave the thumb doing nothing)
				touchState.stopTouch(f.touchId)
				leaveTouchLight(f)
				f.mode = Mode.FREE
				capture(f)
			}
			Mode.EDIT_ZONE, Mode.EDIT_VIDEO, Mode.EDIT_TOUCH -> editMove(f)
			else -> {}
		}
	}

	/** where a sliding finger is taken by a control: button groups on their drawing, the rest on their zone */
	private fun capturable(z: Zone, x: Float, y: Float) = when(z.kind)
	{
		ZoneKind.DPAD, ZoneKind.FACE -> clusterHit(z, x, y) != null
		else -> geometry.rect(z).contains(x, y)
	}

	/**
	 * A free finger (landed on empty space, or left the touchpad) is taken by the first control it
	 * slides into, the top one if they overlap; a stick takes the point where it came in as its centre.
	 */
	private fun capture(f: Finger)
	{
		if(!settings.clusterCapture)
			return
		padLayout.active.lastOrNull { capturable(it, f.x, f.y) }?.let { startZone(f, it) }
	}

	private fun up(f: Finger)
	{
		when(f.mode)
		{
			Mode.STICK -> {
				endStick(f)
				if(f.trigger != 0U) release(f.trigger)
			}
			Mode.TOUCHPAD -> { leaveTouchLight(f); touchUp(f) }
			Mode.EDIT_ZONE, Mode.EDIT_VIDEO, Mode.EDIT_TOUCH -> editUp(f)
			else -> {}
		}
		f.pressed.forEach { release(it) }
	}

	// ---------------------------------------------------------------- sticks

	private fun clickMode(side: Side) = settings.clickMode(side)
	private fun clickButton(side: Side) = if(side == Side.R) ControllerState.BUTTON_R3 else ControllerState.BUTTON_L3
	private fun travel() = settings.stickTravelMm * geometry.mm

	private fun startStick(f: Finger, side: Side, z: Zone)
	{
		val st = stick(side)
		val now = SystemClock.uptimeMillis()
		f.mode = Mode.STICK
		f.zone = z
		f.side = side
		f.asStick = settings.style(side) == StickStyle.STICK
		var rx = 0f
		var ry = 0f
		val isStickZone = z.kind == ZoneKind.STICK || z.kind == ZoneKind.CAM
		if(clickMode(side) == ClickMode.DOUBLE_TAP && isStickZone && st.lastKind == z.kind
			&& now - st.releasedAt < settings.coyoteMs)
		{
			click(clickButton(side))
			// the stick goes on from where it was: running does not cut the movement
			if(f.asStick) { rx = st.lastX; ry = st.lastY }
		}
		val r = travel()
		f.ox = f.x - rx * r
		f.oy = f.y - ry * r
		f.lx = f.x; f.ly = f.y; f.lt = now
		f.size0 = f.size
		f.t0 = now
		f.squash = if(squashTarget(f, isStickZone) != 0U) SquashDetector(settings.calibration(f.side)).also { it.reset(f.size, now) } else null
		st.finger = f.id
		st.releasedAt = -1_000_000L
		st.hold = false
		if(f.asStick) { st.x = rx; st.y = ry } else { st.tx = 0f; st.ty = 0f; scheduleFrame() }
	}

	/** the button squashing holds for this finger: a stick's own choice, or its trigger's "hold" (0 = nothing) */
	private fun squashTarget(f: Finger, isStickZone: Boolean): UInt
	{
		val b = when
		{
			isStickZone -> settings.stickSquash(f.side)?.button ?: 0U
			f.trigger != 0U && settings.triggerSquash(f.side) == TriggerSquash.HOLD -> settings.triggerHold(f.side).button
			else -> 0U
		}
		// a trigger cannot hold itself
		return if(b == f.trigger) 0U else b
	}

	/** squashing the thumb holds the chosen button while it lasts, easing off lets it go (the thumb keeps steering) */
	private fun updateSquash(f: Finger, isStickZone: Boolean)
	{
		val d = f.squash ?: return
		val target = squashTarget(f, isStickZone)
		if(target == 0U)
			return
		val now = SystemClock.uptimeMillis()
		val on = d.update(f.size, now, settings.squashRatio)
		if(on && f.squashHeld == 0U)
		{
			f.squashHeld = target
			f.pressed.add(target)
			press(target, harder = true)
			// the release is judged again on a timer: a thumb that eases off and then keeps perfectly still sends
			// no more touch events, and the button would stay held until it moved
			removeCallbacks(squashRecheck)
			postDelayed(squashRecheck, SQUASH_RECHECK_MS)
			android.util.Log.i("ConsolePadView", "Squash on: contact %.1f, rest %.1f (x%.2f, needs x%.2f)".format(f.size, d.rest, d.ratio(f.size), settings.squashRatio))
		}
		else if(!on && f.squashHeld != 0U)
		{
			f.pressed.remove(f.squashHeld)
			release(f.squashHeld)
			f.squashHeld = 0U
			android.util.Log.i("ConsolePadView", "Squash off: contact %.1f, rest %.1f (x%.2f)".format(f.size, d.rest, d.ratio(f.size)))
		}
	}

	/** fingers holding a button by squashing: their last contact size is judged again with the time that has passed */
	private val squashRecheck = object : Runnable
	{
		override fun run()
		{
			var any = false
			for(f in fingers.values)
			{
				if(f.mode != Mode.STICK || f.squashHeld == 0U)
					continue
				val z = f.zone ?: continue
				updateSquash(f, z.kind == ZoneKind.STICK || z.kind == ZoneKind.CAM)
				if(f.squashHeld != 0U)
					any = true
			}
			emit()
			invalidate()
			if(any)
				postDelayed(this, SQUASH_RECHECK_MS)
		}
	}

	private fun moveStick(f: Finger)
	{
		val st = stick(f.side)
		val z = f.zone ?: return
		val isStickZone = z.kind == ZoneKind.STICK || z.kind == ZoneKind.CAM
		if(f.trigger != 0U && settings.triggerPressure(f.side) && f.size0 > 0f)
		{
			// squashing pulls the trigger further, easing off lets it back (from the settled size)
			if(SystemClock.uptimeMillis() - f.t0 < SQUASH_SETTLE_MS)
				f.size0 = max(f.size0, f.size)
			else
			{
				val t = ((f.size / f.size0 - 1f) / max(0.05f, settings.triggerFullRatio - 1f)).coerceIn(0f, 1f)
				val target = settings.triggerBase + (1f - settings.triggerBase) * t
				// smoothed: one odd reading of the contact must not drop the trigger (a game may take
				// a sudden drop for letting go: Horizon shot the arrow)
				f.triggerLevel += (target - f.triggerLevel) * 0.3f
			}
		}
		updateSquash(f, isStickZone)
		when(clickMode(f.side))
		{
			ClickMode.EDGE -> if(isStickZone)
			{
				val inside = geometry.rect(z).contains(f.x, f.y)
				if(!inside && f.edgeArmed) { click(clickButton(f.side)); f.edgeArmed = false }
				if(inside) f.edgeArmed = true
			}
			else -> {}
		}
		if(st.finger != f.id)
			return
		if(f.asStick)
		{
			val r = travel()
			var dx = (f.x - f.ox) / r
			var dy = (f.y - f.oy) / r
			val m = hypot(dx, dy)
			if(m > 1f)
			{
				// past the edge: the centre follows the finger, so turning back answers at once
				if(settings.follow) { f.ox = f.x - dx / m * r; f.oy = f.y - dy / m * r }
				dx /= m; dy /= m
			}
			st.x = dx; st.y = dy
		}
		else
		{
			val now = SystemClock.uptimeMillis()
			val dt = max(4L, now - f.lt).toFloat()
			val k = geometry.mm
			val full = DRAG_FULL_SPEED_MM_S / settings.sensitivity(f.side)
			st.tx = (f.x - f.lx) / k / dt * 1000f / full
			st.ty = (f.y - f.ly) / k / dt * 1000f / full
			f.lx = f.x; f.ly = f.y; f.lt = now
			scheduleFrame()
		}
	}

	private fun endStick(f: Finger)
	{
		val st = stick(f.side)
		if(st.finger != f.id)
			return
		val kind = f.zone?.kind
		st.finger = -1
		st.releasedAt = SystemClock.uptimeMillis()
		st.lastX = st.x; st.lastY = st.y
		st.lastKind = kind
		st.hold = f.asStick && clickMode(f.side) == ClickMode.DOUBLE_TAP && (kind == ZoneKind.STICK || kind == ZoneKind.CAM)
		if(!st.hold) { st.x = 0f; st.y = 0f }
		st.tx = 0f; st.ty = 0f
		scheduleFrame()
	}

	private var framePosted = false
	private val frameCallback = Choreographer.FrameCallback { frame() }

	private fun scheduleFrame()
	{
		if(framePosted) return
		framePosted = true
		Choreographer.getInstance().postFrameCallback(frameCallback)
	}

	private fun frame()
	{
		framePosted = false
		val now = SystemClock.uptimeMillis()
		var again = false
		for(st in listOf(left, right))
		{
			if(st.finger < 0 && st.hold)
			{
				if(now - st.releasedAt >= settings.coyoteMs) { st.x = 0f; st.y = 0f; st.hold = false }
				else again = true
			}
		}
		for(side in sides)
		{
			if(settings.style(side) != StickStyle.DRAG)
				continue
			val st = stick(side)
			val f = fingers[st.finger]
			if(f != null && now - f.lt > 60) { st.tx = 0f; st.ty = 0f }
			st.x += (st.tx - st.x) * 0.35f
			st.y += (st.ty - st.y) * 0.35f
			if(f != null || abs(st.x) > 0.002f || abs(st.y) > 0.002f) again = true
			else { st.x = 0f; st.y = 0f }
		}
		emit()
		invalidate()
		if(again) scheduleFrame()
	}

	// ---------------------------------------------------------------- buttons you can slide across

	/** L1/R1: a finger that started on one presses whichever it is on (or near) */
	private fun slide(f: Finger)
	{
		val tol = BUTTON_TOLERANCE_MM * geometry.mm
		val near = padLayout.active
			.filter { it.kind == ZoneKind.BUTTON && !it.fixed }
			.map { it.button to geometry.rect(it).distance(f.x, f.y) }
			.minByOrNull { it.second }
			?.takeIf { it.second <= tol }
		setPressed(f, if(near != null) setOf(near.first) else emptySet())
	}

	private fun setPressed(f: Finger, want: Set<UInt>)
	{
		val gone = f.pressed.filter { it !in want }
		gone.forEach { release(it); f.pressed.remove(it) }
		want.filter { it !in f.pressed }.forEach { press(it); f.pressed.add(it) }
	}

	// ---------------------------------------------------------------- button groups worked like a stick

	private val faceNames = listOf(ControllerState.BUTTON_PYRAMID, ControllerState.BUTTON_MOON, ControllerState.BUTTON_CROSS, ControllerState.BUTTON_BOX)
	private val dpadNames = listOf(ControllerState.BUTTON_DPAD_UP, ControllerState.BUTTON_DPAD_RIGHT, ControllerState.BUTTON_DPAD_DOWN, ControllerState.BUTTON_DPAD_LEFT)

	/** up, right, down, left (the same list every time, not a new one) */
	private fun clusterNames(z: Zone) = if(z.kind == ZoneKind.FACE) faceNames else dpadNames

	/** radius of a group's drawing: the tips of the eggs */
	private fun clusterRadiusPx(z: Zone) = geometry.rect(z).let { min(it.w, it.h) / 2f }

	private fun clusterHit(z: Zone, x: Float, y: Float): List<Int>?
	{
		val r = geometry.rect(z)
		val s = clusterRadiusPx(z)
		return settings.shape(z.kind).classify((x - r.cx) / s, (y - r.cy) / s)
	}

	/**
	 * The finger presses what is under it: one button on an egg, both neighbours on the ring,
	 * nothing in the middle. Outside the drawing it lets go (pressing again on coming back, so
	 * quick repeats need no lifting), or keeps what it had if the user prefers.
	 */
	private fun clusterMove(f: Finger)
	{
		val z = f.zone ?: return
		val hit = clusterHit(z, f.x, f.y) ?: if(settings.clusterKeep) f.last else emptyList()
		f.last = hit
		val names = clusterNames(z)
		setPressed(f, hit.map { names[it] }.toSet())
	}

	// ---------------------------------------------------------------- editing the layout
	//
	// Drag a control (or the video) to move it; its handle changes the size. A double tap moves a
	// control's handle to the next corner; a long press brings up the lowest thing under the finger
	// (cycling through what is piled up there, the video included) and drags that one. A double tap
	// on the video edits the touchpad: four corners, free or tied to the video.

	enum class EditTarget { LAYOUT, TOUCHPAD }

	/** what the editor works on: the controls and the video, or the touchpad */
	var editTarget = EditTarget.LAYOUT
		set(value)
		{
			if(field == value) return
			cancelAll()
			field = value
			invalidate()
			onEditStateChanged?.invoke()
		}
	/** the edit target or the touchpad's tie changed: the editor's bar follows */
	var onEditStateChanged: (() -> Unit)? = null

	/** the video was brought up by a long press: it takes touches before the controls over it */
	private var videoRaised = false
	/** what a long press brought up, lit for a moment */
	private var flashZone: Zone? = null
	private var flashVideo = false
	private var flashAt = 0L
	private val FLASH_MS = 500L
	private val LONG_PRESS_MS = 450L
	private val DOUBLE_TAP_MS = 350L
	private val TAP_MOVE_MM = 2.5f

	private var lastTapKey: Any? = null
	private var lastTapAt = 0L

	// corners: 0 = bottom right, 1 = bottom left, 2 = top left, 3 = top right
	private fun isRightCorner(c: Int) = c == 0 || c == 3
	private fun isBottomCorner(c: Int) = c == 0 || c == 1
	private fun cornerX(r: RectF4, c: Int) = if(isRightCorner(c)) r.right else r.x
	private fun cornerY(r: RectF4, c: Int) = if(isBottomCorner(c)) r.bottom else r.y
	/** the same corner seen in a mirror (the partner on the other side) */
	private fun mirrorCorner(c: Int) = intArrayOf(1, 0, 3, 2)[c]

	/** which corner of each control has the size handle (only on this device, not in the layout) */
	private val corners: MutableMap<String, Int> = run {
		val m = HashMap<String, Int>()
		try
		{
			val o = org.json.JSONObject(prefs.getString(KEY_CORNERS, null) ?: "{}")
			o.keys().forEach { m[it] = o.getInt(it) and 3 }
		}
		catch(e: Exception) {}
		m
	}
	private fun cornerOf(z: Zone) = corners[z.id] ?: 0
	private fun saveCorners() = prefs.edit().putString(KEY_CORNERS, org.json.JSONObject(corners as Map<*, *>).toString()).apply()

	/** a size handle under the finger: inside the rect, near that corner */
	private fun nearCorner(r: RectF4, c: Int, x: Float, y: Float, handle: Float): Boolean
	{
		val tol = handle * 0.25f
		if(x < r.x - tol || x > r.right + tol || y < r.y - tol || y > r.bottom + tol)
			return false
		val dx = if(isRightCorner(c)) r.right - x else x - r.x
		val dy = if(isBottomCorner(c)) r.bottom - y else y - r.y
		return dx < handle && dy < handle
	}

	/** the video's four corners sit a little inside it, easier to grab */
	private fun videoCornerInset() = 1.5f * geometry.mm

	private fun editDown(f: Finger)
	{
		f.lx = f.x; f.ly = f.y
		f.startX = f.x; f.startY = f.y
		f.t0 = SystemClock.uptimeMillis()
		f.moved = false
		val handle = HANDLE_MM * geometry.mm
		if(editTarget == EditTarget.TOUCHPAD)
		{
			val t = touchRect
			val c = (0..3).firstOrNull { hypot(f.x - cornerX(t, it), f.y - cornerY(t, it)) < handle * 0.9f }
			if(c != null)
			{
				f.mode = Mode.EDIT_TOUCH; f.resize = true; f.corner = c
				f.ax = cornerX(t, (c + 2) % 4); f.ay = cornerY(t, (c + 2) % 4)
			}
			else if(t.contains(f.x, f.y))
			{
				f.mode = Mode.EDIT_TOUCH; f.resize = false
				armLongPress(f)
			}
			return
		}
		// a control's size handle (the top one first)
		padLayout.active.lastOrNull { nearCorner(geometry.rect(it), cornerOf(it), f.x, f.y, handle) }?.let { z ->
			f.mode = Mode.EDIT_ZONE; f.zone = z; f.resize = true; f.corner = cornerOf(z)
			return
		}
		val v = videoRect
		if(videoEditable)
		{
			val inset = videoCornerInset()
			val inner = RectF4(v.x + inset, v.y + inset, v.w - 2f * inset, v.h - 2f * inset)
			val c = (0..3).firstOrNull { hypot(f.x - cornerX(inner, it), f.y - cornerY(inner, it)) < handle * 0.9f }
			if(c != null)
			{
				f.mode = Mode.EDIT_VIDEO; f.resize = true; f.corner = c
				f.ax = cornerX(v, (c + 2) % 4); f.ay = cornerY(v, (c + 2) % 4)
				return
			}
		}
		val onVideo = v.contains(f.x, f.y)
		val z = if(videoRaised && onVideo) null else hit(f.x, f.y)
		when
		{
			z != null -> { f.mode = Mode.EDIT_ZONE; f.zone = z; f.resize = false }
			onVideo -> { f.mode = Mode.EDIT_VIDEO; f.resize = false }
			else -> return
		}
		armLongPress(f)
	}

	private fun armLongPress(f: Finger)
	{
		val id = f.id
		postDelayed({
			val g = fingers[id]
			if(g === f && !f.moved && editMode)
				longPress(f)
		}, LONG_PRESS_MS)
	}

	/**
	 * Controls: the lowest of the pile under the finger comes up (the video counts as the lowest
	 * unless it was just brought up), and the finger drags it. Touchpad: it fills the video.
	 */
	private fun longPress(f: Finger)
	{
		performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
		lastTapKey = null
		if(editTarget == EditTarget.TOUCHPAD)
		{
			fillTouchVideo()
			f.mode = Mode.NONE
			return
		}
		val under = padLayout.active.filter { geometry.rect(it).contains(f.x, f.y) }
		val onVideo = videoEditable && videoRect.contains(f.x, f.y)
		// bottom to top
		val pile: List<Any> = if(videoRaised) under + listOfNotNull(if(onVideo) VIDEO else null)
			else listOfNotNull(if(onVideo) VIDEO else null) + under
		if(pile.size < 2)
			return
		val lowest = pile.first()
		flashAt = SystemClock.uptimeMillis()
		if(lowest === VIDEO)
		{
			videoRaised = true
			flashVideo = true; flashZone = null
			f.mode = Mode.EDIT_VIDEO; f.resize = false; f.zone = null
		}
		else
		{
			val z = lowest as Zone
			videoRaised = false
			padLayout.zones.remove(z)
			padLayout.zones.add(z)
			padLayout.save(prefs)
			flashZone = z; flashVideo = false
			f.mode = Mode.EDIT_ZONE; f.resize = false; f.zone = z
			onLayoutChanged?.invoke()
		}
		f.lx = f.x; f.ly = f.y
		invalidate()
		postInvalidateDelayed(FLASH_MS)
	}

	private fun editMove(f: Finger)
	{
		if(!f.moved && hypot(f.x - f.startX, f.y - f.startY) > TAP_MOVE_MM * geometry.mm)
			f.moved = true
		when(f.mode)
		{
			Mode.EDIT_ZONE -> editZone(f)
			Mode.EDIT_VIDEO -> editVideo(f)
			Mode.EDIT_TOUCH -> editTouch(f)
			else -> {}
		}
	}

	private fun editUp(f: Finger)
	{
		padLayout.save(prefs)
		onLayoutChanged?.invoke()
		val now = SystemClock.uptimeMillis()
		if(f.moved || f.resize || now - f.t0 > LONG_PRESS_MS)
			return
		// a tap: two on the same thing make a double tap
		val key: Any? = when(f.mode)
		{
			Mode.EDIT_ZONE -> f.zone
			Mode.EDIT_VIDEO -> VIDEO
			Mode.EDIT_TOUCH -> TOUCH
			else -> null
		}
		if(key != null && key === lastTapKey && now - lastTapAt < DOUBLE_TAP_MS)
		{
			lastTapKey = null
			doubleTap(key)
		}
		else
		{
			lastTapKey = key
			lastTapAt = now
		}
	}

	private fun doubleTap(key: Any)
	{
		when
		{
			key === VIDEO -> editTarget = EditTarget.TOUCHPAD
			key === TOUCH -> touchTied = !touchTied
			key is Zone -> {
				// the size handle goes round the corners
				val c = (cornerOf(key) + 1) % 4
				corners[key.id] = c
				if(symmetric)
					ConsolePadLayout.partners[key.id]?.let { corners[it] = mirrorCorner(c) }
				saveCorners()
				invalidate()
			}
		}
	}

	private fun editZone(f: Finger)
	{
		val z = f.zone ?: return
		val m = geometry.mm
		val dx = (f.x - f.lx) / m
		val dy = (f.y - f.ly) / m
		f.lx = f.x; f.ly = f.y
		if(!f.resize)
		{
			z.x += if(z.side == Side.R) -dx else dx
			z.y += if(z.top) dy else -dy
		}
		else
		{
			// the handle's corner follows the finger, the opposite one stays
			val r = geometry.rect(z)
			val min = 6f * m
			var l = r.x; var t = r.y; var rr = r.right; var b = r.bottom
			if(isRightCorner(f.corner)) rr = max(l + min, rr + dx * m) else l = min(rr - min, l + dx * m)
			if(isBottomCorner(f.corner)) b = max(t + min, b + dy * m) else t = min(b - min, t + dy * m)
			geometry.setRect(z, RectF4(l, t, rr - l, b - t))
		}
		if(symmetric)
			ConsolePadLayout.partners[z.id]?.let { id -> padLayout.zones.firstOrNull { it.id == id } }?.let {
				it.x = z.x; it.y = z.y; it.w = z.w; it.h = z.h
			}
		// the shell's wells follow the zones while they move
		onLayoutChanged?.invoke()
	}

	// ---- the touchpad

	/** tied: it is part of the video (moves and grows with it); free: anywhere on the screen */
	var touchTied: Boolean
		get() = !padLayout.touch.free
		set(value)
		{
			if(value == !padLayout.touch.free) return
			// it stays where it is, now measured against the other thing
			val r = touchRect
			padLayout.touch.free = !value
			padLayout.touch.set(r, videoRect, geometry.width, geometry.height)
			touchEdited()
		}

	fun fillTouchVideo()
	{
		padLayout.touch.apply { free = false; left = 0f; top = 0f; right = 1f; bottom = 1f }
		touchEdited()
	}

	fun fillTouchScreen()
	{
		padLayout.touch.apply { free = true; left = 0f; top = 0f; right = 1f; bottom = 1f }
		touchEdited()
	}

	private fun touchEdited()
	{
		padLayout.save(prefs)
		invalidate()
		onLayoutChanged?.invoke()
		onEditStateChanged?.invoke()
	}

	private fun editTouch(f: Finger)
	{
		val t = touchRect
		val dx = f.x - f.lx
		val dy = f.y - f.ly
		f.lx = f.x; f.ly = f.y
		val min = 10f * geometry.mm
		val r = if(f.resize)
		{
			// the opposite corner stays
			val l = min(f.ax, f.x); val rr = max(f.ax, f.x)
			val tp = min(f.ay, f.y); val b = max(f.ay, f.y)
			RectF4(l, tp, max(min, rr - l), max(min, b - tp))
		}
		else
		{
			// moved whole, kept inside what it is measured against
			val ref = if(padLayout.touch.free) RectF4(0f, 0f, geometry.width, geometry.height) else videoRect
			val nx = (t.x + dx).coerceIn(ref.x, max(ref.x, ref.right - t.w))
			val ny = (t.y + dy).coerceIn(ref.y, max(ref.y, ref.bottom - t.h))
			RectF4(nx, ny, t.w, t.h)
		}
		padLayout.touch.set(r, videoRect, geometry.width, geometry.height)
		invalidate()
	}

	private fun editVideo(f: Finger)
	{
		val v = padLayout.video
		val m = geometry.mm
		val dx = (f.x - f.lx) / m
		val dy = (f.y - f.ly) / m
		f.lx = f.x; f.ly = f.y
		if(f.resize)
		{
			// the grabbed corner follows the finger, the opposite one stays (always 16:9)
			val w = max(geometry.width * 0.2f, max(abs(f.x - f.ax), abs(f.y - f.ay) * 16f / 9f))
			val h = w * 9f / 16f
			val x = if(isRightCorner(f.corner)) f.ax else f.ax - w
			val y = if(isBottomCorner(f.corner)) f.ay else f.ay - h
			v.marginX = (geometry.width - w) / 2f / m
			v.dx = (x - (geometry.width - w) / 2f) / m
			v.top = y / m
		}
		else
		{
			v.dx += dx
			v.top += dy
		}
		// keep what is stored equal to where it really is (against the screen edges); the gap left
		// under it is kept on wider screens
		val r = geometry.video(v, keepBottom = false)
		val centred = (geometry.width - r.w) / 2f
		v.dx = (r.x - centred) / m
		v.top = r.y / m
		v.bottom = max(0f, (geometry.height - r.bottom) / m - 0.2f)
		onLayoutChanged?.invoke()
	}

	// ---------------------------------------------------------------- drawing

	/** colours follow the shell */
	var theme = ShellTheme.load(prefs)
		set(value) { field = value; lookChanged(); invalidate() }
	private val accent get() = theme.accent
	/** outlines, names and symbols: the shell's colour or the one chosen in the options */
	private val line get() = settings.lineColor.color ?: accent
	/** the softer outline and name of the stick and trigger zones */
	private val zoneLine get() = settings.lineColor.color?.let { withAlpha(it, 0xB3) } ?: theme.zoneStroke
	private val zoneLabel get() = settings.lineColor.color?.let { withAlpha(it, 0xCC) } ?: theme.label
	private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
	private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = 0xFF00E5FF.toInt()
		pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
	}
	private val tmpRect = RectF()

	/** the buttons keep the names they have on the controller; sticks have only an icon */
	private fun label(z: Zone): String = when(z.kind)
	{
		ZoneKind.STICK, ZoneKind.CAM -> ""
		else -> when(z.button)
		{
			BUTTON_L2 -> "L2"
			BUTTON_R2 -> "R2"
			ControllerState.BUTTON_L1 -> "L1"
			ControllerState.BUTTON_R1 -> "R1"
			ControllerState.BUTTON_PS -> "PS"
			ControllerState.BUTTON_OPTIONS -> "OPTIONS"
			ControllerState.BUTTON_SHARE -> "SHARE"
			ControllerState.BUTTON_L3 -> "L3"
			ControllerState.BUTTON_R3 -> "R3"
			else -> ""
		}
	}

	// ---- icons (sticks, OPTIONS, SHARE, PS); L1, R2… keep their names, as on the controller.
	// PS is a little house: the PlayStation logo is not ours to draw.

	private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val iconPath = Path()

	/** draws [z]'s icon in a box of [s] around (cx, cy); false = it has none */
	private fun drawIcon(canvas: Canvas, z: Zone, cx: Float, cy: Float, s: Float, color: Int): Boolean
	{
		val h = s / 2f
		iconPaint.color = color
		iconPaint.strokeWidth = max(2f, s * 0.09f)
		val p = iconPath
		p.reset()
		when
		{
			z.kind == ZoneKind.STICK || z.kind == ZoneKind.CAM -> {
				// four arrows: a stick (both look the same: what a stick does depends on the game)
				val a = h * 0.32f
				p.moveTo(cx - h, cy); p.lineTo(cx + h, cy)
				p.moveTo(cx, cy - h); p.lineTo(cx, cy + h)
				p.moveTo(cx - h + a, cy - a); p.lineTo(cx - h, cy); p.lineTo(cx - h + a, cy + a)
				p.moveTo(cx + h - a, cy - a); p.lineTo(cx + h, cy); p.lineTo(cx + h - a, cy + a)
				p.moveTo(cx - a, cy - h + a); p.lineTo(cx, cy - h); p.lineTo(cx + a, cy - h + a)
				p.moveTo(cx - a, cy + h - a); p.lineTo(cx, cy + h); p.lineTo(cx + a, cy + h - a)
			}
			z.button == ControllerState.BUTTON_OPTIONS -> {
				// three lines
				for(dy in listOf(-0.55f, 0f, 0.55f)) { p.moveTo(cx - h * 0.9f, cy + dy * h); p.lineTo(cx + h * 0.9f, cy + dy * h) }
			}
			z.button == ControllerState.BUTTON_SHARE -> {
				// three linked dots
				val r = h * 0.2f
				val ax = cx - h * 0.6f; val bx = cx + h * 0.6f
				p.moveTo(ax, cy); p.lineTo(bx, cy - h * 0.6f)
				p.moveTo(ax, cy); p.lineTo(bx, cy + h * 0.6f)
				canvas.drawPath(p, iconPaint)
				p.reset()
				p.addCircle(ax, cy, r, Path.Direction.CW)
				p.addCircle(bx, cy - h * 0.6f, r, Path.Direction.CW)
				p.addCircle(bx, cy + h * 0.6f, r, Path.Direction.CW)
				fillPaint.color = color
				canvas.drawPath(p, fillPaint)
				return true
			}
			z.button == BUTTON_GYRO -> {
				// a phone with a turn arrow over it; crossed out while the gyroscope stick is off
				val w = h * 0.34f
				val t = h * 0.55f
				p.addRoundRect(cx - w, cy - t * 0.75f, cx + w, cy + t * 0.75f, w * 0.4f, w * 0.4f, Path.Direction.CW)
				tmpRect2.set(cx - h * 0.9f, cy - h * 0.9f, cx + h * 0.9f, cy + h * 0.9f)
				p.addArc(tmpRect2, 200f, 100f)
				p.moveTo(cx + h * 0.36f, cy - h * 0.98f); p.lineTo(cx + h * 0.55f, cy - h * 0.6f)
				p.moveTo(cx + h * 0.36f, cy - h * 0.98f); p.lineTo(cx + h * 0.05f, cy - h * 0.9f)
				p.addArc(tmpRect2, 20f, 100f)
				p.moveTo(cx - h * 0.36f, cy + h * 0.98f); p.lineTo(cx - h * 0.55f, cy + h * 0.6f)
				p.moveTo(cx - h * 0.36f, cy + h * 0.98f); p.lineTo(cx - h * 0.05f, cy + h * 0.9f)
				if(!gyroOn) { p.moveTo(cx - h, cy + h); p.lineTo(cx + h, cy - h) }
			}
			z.button == ControllerState.BUTTON_PS -> {
				// a little house: home
				p.moveTo(cx - h * 0.85f, cy - h * 0.05f); p.lineTo(cx, cy - h * 0.8f); p.lineTo(cx + h * 0.85f, cy - h * 0.05f)
				p.moveTo(cx - h * 0.6f, cy - h * 0.25f); p.lineTo(cx - h * 0.6f, cy + h * 0.7f)
				p.lineTo(cx + h * 0.6f, cy + h * 0.7f); p.lineTo(cx + h * 0.6f, cy - h * 0.25f)
			}
			else -> return false
		}
		canvas.drawPath(p, iconPaint)
		return true
	}
	private val tmpRect2 = RectF()

	private fun symbol(b: UInt) = when(b)
	{
		ControllerState.BUTTON_PYRAMID -> "△"
		ControllerState.BUTTON_MOON -> "○"
		ControllerState.BUTTON_CROSS -> "✕"
		ControllerState.BUTTON_BOX -> "□"
		ControllerState.BUTTON_DPAD_UP -> "▲"
		ControllerState.BUTTON_DPAD_RIGHT -> "▶"
		ControllerState.BUTTON_DPAD_DOWN -> "▼"
		else -> "◀"
	}

	private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, sizePx: Float, color: Int)
	{
		textPaint.textSize = sizePx
		textPaint.color = color
		val fm = textPaint.fontMetrics
		canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
	}

	// ---- opacity and the lamp: the pad is drawn once at its opacity, and once more at full
	// strength (glowing) only where the fingers' lamps light it, fading out after they lift

	/** alpha multiplier of the pass being drawn */
	private var fade = 1f
	/** drawing the lit pass: untouched controls glow */
	private var lit = false

	private fun col(c: Int): Int
	{
		if(fade >= 1f) return c
		val a = ((c ushr 24) * fade).toInt().coerceIn(0, 255)
		return (c and 0x00FFFFFF) or (a shl 24)
	}
	/** fill of a pressed control: without the lamp it shows at full strength whatever the opacity */
	private fun onCol(c: Int) = if(!settings.lamp) c else col(c)
	/** fill of an untouched control: its theme colour, or a glow under a lamp */
	private fun idle(c: Int) = col(if(lit) withAlpha(accent, 0x59) else c)

	private class Light(val x: Float, val y: Float, val releasedAt: Long)
	/** lamps of fingers already lifted, fading out */
	private val fadingLights = ArrayList<Light>()
	// filtered: the half-resolution lit picture is enlarged smoothly, not in visible steps
	private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
	private val lampRect = RectF()
	private val lampMatrix = android.graphics.Matrix()
	private var lampShader: android.graphics.RadialGradient? = null
	private var lampShaderRadius = 0f
	private val maskPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) }

	private fun lightsUp(mode: Mode) = when(mode)
	{
		Mode.STICK, Mode.SLIDE, Mode.FIXED, Mode.CLUSTER, Mode.FREE -> true
		else -> false
	}

	/** a lifted finger leaves its lamp to fade out where it was */
	private fun leaveLight(f: Finger)
	{
		if(settings.lamp && lightsUp(f.mode))
			fadingLights.add(Light(f.x, f.y, SystemClock.uptimeMillis()))
	}

	// ---- touchpad touches: a dot under the finger with a short trail, inside the touchpad's area,
	// whatever the opacity (they are the only sign the touch arrived)

	private val TOUCH_TRAIL_MS = 260L
	private val TOUCH_FADE_MS = 300L
	/** touches already lifted (or gone out of the area), fading out */
	private val fadingTouches = ArrayList<Light>()
	private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}

	private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

	private fun leaveTouchLight(f: Finger)
	{
		fadingTouches.add(Light(f.x, f.y, SystemClock.uptimeMillis()))
		f.trail.clear()
	}

	private fun drawTouches(canvas: Canvas)
	{
		val now = SystemClock.uptimeMillis()
		// (loops, not filter/removeAll: no new lists or lambdas in every frame)
		val fading = fadingTouches.iterator()
		while(fading.hasNext())
			if(now - fading.next().releasedAt >= TOUCH_FADE_MS)
				fading.remove()
		var touchingAny = false
		for(f in fingers.values)
			if(f.mode == Mode.TOUCHPAD) { touchingAny = true; break }
		if(!touchingAny && fadingTouches.isEmpty())
			return
		val m = geometry.mm
		val t = touchRect
		// the touchpad's edge glows while it is touched and fades out after (invisible otherwise)
		var glow = if(touchingAny) 1f else 0f
		if(!touchingAny)
			for(l in fadingTouches)
				glow = max(glow, 1f - (now - l.releasedAt).toFloat() / TOUCH_FADE_MS)
		if(glow > 0f)
		{
			val inset = m * 0.4f
			tmpRect.set(t.x + inset, t.y + inset, t.right - inset, t.bottom - inset)
			edgePaint.strokeWidth = m * 0.8f
			edgePaint.color = withAlpha(accent, (0x40 * glow).toInt())
			canvas.drawRoundRect(tmpRect, 2f * m, 2f * m, edgePaint)
			edgePaint.strokeWidth = max(2f, m * 0.3f)
			edgePaint.color = withAlpha(accent, (0xC0 * glow).toInt())
			canvas.drawRoundRect(tmpRect, 2f * m, 2f * m, edgePaint)
		}
		val save = canvas.save()
		canvas.clipRect(t.x, t.y, t.right, t.bottom)
		val dot = 3.2f * m
		for(f in fingers.values)
		{
			if(f.mode != Mode.TOUCHPAD)
				continue
			val old = f.trail.iterator()
			while(old.hasNext())
				if(now - old.next().t > TOUCH_TRAIL_MS)
					old.remove()
			trailPaint.strokeWidth = dot * 0.9f
			for(i in 1 until f.trail.size)
			{
				val a = f.trail[i - 1]
				val b = f.trail[i]
				val s = 1f - (now - b.t).toFloat() / TOUCH_TRAIL_MS
				trailPaint.color = withAlpha(accent, (0x70 * s).toInt().coerceIn(0, 255))
				canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
			}
			fillPaint.color = withAlpha(accent, 0x30)
			canvas.drawCircle(f.x, f.y, dot * 1.8f, fillPaint)
			fillPaint.color = withAlpha(accent, 0xB0)
			canvas.drawCircle(f.x, f.y, dot, fillPaint)
		}
		for(l in fadingTouches)
		{
			val s = 1f - (now - l.releasedAt).toFloat() / TOUCH_FADE_MS
			// a ring that opens up as it fades
			strokePaint.color = withAlpha(accent, (0xB0 * s).toInt().coerceIn(0, 255))
			canvas.drawCircle(l.x, l.y, dot * (1f + (1f - s) * 1.2f), strokePaint)
		}
		canvas.restoreToCount(save)
		// the trail fades even when the finger stands still
		postInvalidateOnAnimation()
	}

	// ---- game mode: no back gesture from the side edges where the controls are

	/** the side edges under the controls do not start the system's back gesture (game mode) */
	var excludeEdgeGestures = false
		set(value) { field = value; invalidate() }
	private var excludedRects: List<android.graphics.Rect> = emptyList()
	private var exclusionKey = Int.MIN_VALUE
	private var exclusionWidth = 0
	private var exclusionHeight = 0

	/**
	 * Each control near a side edge is kept from the back gesture (Android honours up to 200 dp per
	 * edge; Home from the bottom edge always works). Updated from drawing: it follows the layout.
	 */
	private fun updateGestureExclusion()
	{
		if(android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q)
			return
		// only when the layout, the size or the mode changed (it built new lists in every frame)
		val key = if(!excludeEdgeGestures || editMode) -1 else lookGeneration
		if(key == exclusionKey && width == exclusionWidth && height == exclusionHeight && !editMode)
			return
		exclusionKey = key
		exclusionWidth = width
		exclusionHeight = height
		val strip = 48f * resources.displayMetrics.density
		val rects = if(!excludeEdgeGestures || editMode) emptyList() else padLayout.active.map { geometry.rect(it) }
			.filter { it.x < strip || it.right > geometry.width - strip }
			.map { android.graphics.Rect(it.x.toInt(), it.y.toInt(), Math.ceil(it.right.toDouble()).toInt(), Math.ceil(it.bottom.toDouble()).toInt()) }
		if(rects == excludedRects)
			return
		excludedRects = rects
		systemGestureExclusionRects = rects
	}

	// ---- the resting picture: every control as it looks now (pressed or not), kept by the GPU as a
	// ready-made image and made again only when something in it changes (a press, a release, a
	// setting, the layout). Before, the whole pad (some 30 shapes, names, icons) was drawn again in
	// every frame while a finger moved, only for a dot or a stick to follow it.

	private val restNode: android.graphics.RenderNode? =
		if(android.os.Build.VERSION.SDK_INT >= 29)
			android.graphics.RenderNode("ConsolePadRest").apply { setUseCompositingLayer(true, null) }
		else null
	/** bumped by everything that changes how the controls look, besides pressing them */
	private var lookGeneration = 0
	private var restGeneration = -1
	private var restPressed = -1L
	private var restWidth = 0
	private var restHeight = 0
	private var restOpacity = -1f

	/** the controls' look changed (layout, settings, theme, size, editing…): their pictures go */
	private fun lookChanged()
	{
		lookGeneration++
	}

	/** which buttons are pressed, as one number */
	private fun pressedKey(): Long
	{
		var k = 0L
		for((b, c) in counts)
			if(c > 0)
				k = k or b.toLong()
		return k
	}

	private fun drawRest(canvas: Canvas, node: android.graphics.RenderNode)
	{
		val pressed = pressedKey()
		val opacity = settings.opacity
		if(restGeneration != lookGeneration || restPressed != pressed || restWidth != width || restHeight != height ||
			restOpacity != opacity || !node.hasDisplayList())
		{
			node.setPosition(0, 0, width, height)
			val c = node.beginRecording(width, height)
			try
			{
				fade = opacity
				lit = false
				drawControls(c, null, Part.RESTING)
			}
			finally { node.endRecording() }
			restGeneration = lookGeneration
			restPressed = pressed
			restWidth = width
			restHeight = height
			restOpacity = opacity
			com.metallic.chiaki.stream.RenderStats.padRecords.incrementAndGet()
		}
		canvas.drawRenderNode(node)
	}

	/** frees the kept pictures (hidden, editing, gone): they are made again when needed */
	private fun dropPictures()
	{
		restNode?.discardDisplayList()
		restGeneration = -1
		litBitmap?.recycle()
		litBitmap = null
		litShader = null
		lampCompose = null
	}

	// ---- the lamps without extra layers: the whole pad drawn once lit (as if untouched) into a
	// half-resolution picture; each light shows it through a soft round mask in a single draw
	// (picture and mask combined in the paint). Before, every frame opened two offscreen layers
	// and drew the controls under the light again. Half resolution: the glow is soft anyway, and
	// it takes a quarter of the memory (about 4.5 MB on a 3088x1440 screen).

	private var litBitmap: android.graphics.Bitmap? = null
	private var litShader: android.graphics.BitmapShader? = null
	private var lampCompose: android.graphics.ComposeShader? = null
	private var litGeneration = -1
	private var litWidth = 0
	private var litHeight = 0
	private val litMatrix = android.graphics.Matrix()

	private fun ensureLitPicture(): Boolean
	{
		if(litBitmap != null && litGeneration == lookGeneration && litWidth == width && litHeight == height)
			return true
		litBitmap?.recycle()
		litBitmap = null
		litShader = null
		lampCompose = null
		if(width <= 1 || height <= 1)
			return false
		val w = width / 2
		val h = height / 2
		val soft = try { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
			catch(e: OutOfMemoryError) { return false }
		val c = Canvas(soft)
		c.scale(w / width.toFloat(), h / height.toFloat())
		fade = 1f
		lit = true
		ignorePressed = true
		try { drawControls(c, null, Part.RESTING) }
		finally { ignorePressed = false; lit = false }
		// kept only on the GPU side from now on (no copy left in the app's memory)
		val bitmap = if(android.os.Build.VERSION.SDK_INT >= 26)
			soft.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft
		else soft
		litBitmap = bitmap
		litShader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).also {
			litMatrix.setScale(width / w.toFloat(), height / h.toFloat())
			it.setLocalMatrix(litMatrix)
		}
		litGeneration = lookGeneration
		litWidth = width
		litHeight = height
		return true
	}

	/** one light: the lit picture through the round mask, dimmed by [strength] */
	private fun drawLight(canvas: Canvas, x: Float, y: Float, radius: Float, strength: Float)
	{
		lampMatrix.setTranslate(x, y)
		lampShader?.setLocalMatrix(lampMatrix)
		lampPaint.alpha = (255 * strength).toInt().coerceIn(0, 255)
		canvas.drawCircle(x, y, radius, lampPaint)
	}

	private fun drawLampsFast(canvas: Canvas)
	{
		val now = SystemClock.uptimeMillis()
		val it = fadingLights.iterator()
		while(it.hasNext())
			if(now - it.next().releasedAt >= LAMP_FADE_MS)
				it.remove()
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		var any = false
		for(f in fingers.values)
			if(lightsUp(f.mode))
			{
				any = true
				minX = min(minX, f.x); minY = min(minY, f.y); maxX = max(maxX, f.x); maxY = max(maxY, f.y)
			}
		for(l in fadingLights)
		{
			any = true
			minX = min(minX, l.x); minY = min(minY, l.y); maxX = max(maxX, l.x); maxY = max(maxY, l.y)
		}
		if(!any || !ensureLitPicture())
			return
		val radius = settings.lampSizeMm * geometry.mm
		if(lampShader == null || lampShaderRadius != radius)
		{
			lampShader = android.graphics.RadialGradient(0f, 0f, radius,
				intArrayOf(0xFFFFFFFF.toInt(), withAlpha(0xFFFFFF, (255 * 0.7f).toInt()), 0),
				floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
			lampShaderRadius = radius
			lampCompose = null
		}
		if(lampCompose == null)
		{
			// the lit picture ("dst") kept only where the mask is ("src", DST_IN)
			lampCompose = android.graphics.ComposeShader(litShader!!, lampShader!!, android.graphics.PorterDuff.Mode.DST_IN)
			lampPaint.shader = lampCompose
		}
		for(f in fingers.values)
			if(lightsUp(f.mode))
				drawLight(canvas, f.x, f.y, radius, 1f)
		for(l in fadingLights)
		{
			val s = 1f - (now - l.releasedAt).toFloat() / LAMP_FADE_MS
			drawLight(canvas, l.x, l.y, radius, s * s * (3f - 2f * s))
		}
		// the lit picture shows every control untouched: the pressed ones under the lamps go on top,
		// at full strength, as the lit pass drew them
		lampRect.set(minX - radius, minY - radius, maxX + radius, maxY + radius)
		fade = 1f
		lit = true
		drawControls(canvas, lampRect, Part.PRESSED)
		lit = false
		if(fadingLights.isNotEmpty())
			postInvalidateOnAnimation()
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		updateGestureExclusion()
		val m = geometry.mm
		strokePaint.strokeWidth = max(2f, m * 0.3f)
		editPaint.strokeWidth = max(2f, m * 0.3f)
		val node = restNode
		if(node != null && !editMode && canvas.isHardwareAccelerated)
		{
			drawRest(canvas, node)
			if(settings.lamp)
				drawLampsFast(canvas)
			drawMoving(canvas)
			fade = settings.opacity
			lit = false
			drawTouches(canvas)
			fade = 1f
			lit = false
			return
		}
		// edit mode and Android 9 or older: everything drawn every time, as before
		// while editing, everything in full view
		fade = if(editMode) 1f else settings.opacity
		lit = false
		drawControls(canvas)
		if(!editMode && settings.lamp)
			drawLamps(canvas)
		if(!editMode)
			drawTouches(canvas)
		fade = 1f
		lit = false
		if(editMode)
			drawEditing(canvas)
	}

	private fun drawLamps(canvas: Canvas)
	{
		val now = SystemClock.uptimeMillis()
		val radius = settings.lampSizeMm * geometry.mm
		fadingLights.removeAll { now - it.releasedAt >= LAMP_FADE_MS }
		// (x, y, strength 0..1)
		val lights = fingers.values.filter { lightsUp(it.mode) }.map { Triple(it.x, it.y, 1f) } +
			fadingLights.map {
				val s = 1f - (now - it.releasedAt).toFloat() / LAMP_FADE_MS
				Triple(it.x, it.y, s * s * (3f - 2f * s))
			}
		if(lights.isEmpty())
			return
		// only the part of the screen the lamps reach goes through the extra layers (its own rect:
		// drawControls() uses tmpRect, and the mask layer used to get the last control's rect)
		lampRect.set(lights.minOf { it.first } - radius, lights.minOf { it.second } - radius,
			lights.maxOf { it.first } + radius, lights.maxOf { it.second } + radius)
		val layer = canvas.saveLayer(lampRect, null)
		fade = 1f
		lit = true
		drawControls(canvas, lampRect)
		val mask = canvas.saveLayer(lampRect, maskPaint)
		// one gradient, made again only when the size changes: moved to each light and dimmed with
		// the paint's alpha (a new gradient for every light in every frame before)
		if(lampShader == null || lampShaderRadius != radius)
		{
			lampShader = android.graphics.RadialGradient(0f, 0f, radius,
				intArrayOf(0xFFFFFFFF.toInt(), withAlpha(0xFFFFFF, (255 * 0.7f).toInt()), 0),
				floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
			lampShaderRadius = radius
			lampPaint.shader = lampShader
		}
		for((x, y, s) in lights)
		{
			lampMatrix.setTranslate(x, y)
			lampShader?.setLocalMatrix(lampMatrix)
			lampPaint.alpha = (255 * s).toInt().coerceIn(0, 255)
			canvas.drawCircle(x, y, radius, lampPaint)
		}
		canvas.restoreToCount(mask)
		canvas.restoreToCount(layer)
		if(fadingLights.isNotEmpty())
			postInvalidateOnAnimation()
	}

	/**
	 * What drawControls draws: everything (edit mode, old Android); the resting picture (shapes,
	 * names and which are pressed, without what moves with the fingers); or only the pressed
	 * controls (over the lamps, whose lit picture shows them unpressed).
	 */
	private enum class Part { ALL, RESTING, PRESSED }

	/** drawing the lamps' lit picture: every control as if untouched */
	private var ignorePressed = false
	/** pressed, as far as the picture being drawn is concerned */
	private fun shownDown(b: UInt) = !ignorePressed && isDown(b)

	private fun zonePressed(z: Zone): Boolean = when(z.kind)
	{
		ZoneKind.BUTTON, ZoneKind.TRIGGER -> shownDown(z.button)
		ZoneKind.FACE, ZoneKind.DPAD -> clusterNames(z).any { shownDown(it) }
		else -> false
	}

	/** [only]: skip the controls outside it (the lamps' pass only needs what the light reaches) */
	private fun drawControls(canvas: Canvas, only: RectF? = null, part: Part = Part.ALL)
	{
		val m = geometry.mm
		for(z in padLayout.active)
		{
			val r = geometry.rect(z)
			if(only != null && !only.intersects(r.x, r.y, r.right, r.bottom))
				continue
			if(part == Part.PRESSED && !zonePressed(z))
				continue
			tmpRect.set(r.x, r.y, r.right, r.bottom)
			when(z.kind)
			{
				ZoneKind.STICK, ZoneKind.CAM, ZoneKind.TRIGGER -> {
					val on = z.kind == ZoneKind.TRIGGER && shownDown(z.button)
					fillPaint.color = if(on) onCol(withAlpha(accent, 0x55)) else idle(theme.zoneFill)
					canvas.drawRoundRect(tmpRect, 3f * m, 3f * m, fillPaint)
					strokePaint.color = col(if(lit) accent else zoneLine)
					canvas.drawRoundRect(tmpRect, 3f * m, 3f * m, strokePaint)
					if(on && settings.triggerPressure(z.side) && part == Part.ALL)
						drawTriggerLevel(canvas, z)
					val labelColor = col(if(lit) accent else zoneLabel)
					if(!drawIcon(canvas, z, r.cx, r.cy, min(min(r.w, r.h) * 0.5f, 9f * m), labelColor))
						drawText(canvas, label(z), r.cx, r.cy, 3.2f * m, labelColor)
					if(part == Part.ALL)
						for(f in fingers.values)
							if(f.mode == Mode.STICK && f.zone === z) { drawActiveStick(canvas, f); break }
				}
				ZoneKind.FACE, ZoneKind.DPAD -> drawCluster(canvas, z, part)
				ZoneKind.BUTTON -> {
					val on = shownDown(z.button)
					fillPaint.color = if(on) onCol(accent) else idle(theme.buttonFill)
					strokePaint.color = col(if(lit) accent else line)
					if(z.round)
					{
						val rad = min(r.w, r.h) / 2f
						canvas.drawCircle(r.cx, r.cy, rad, fillPaint)
						canvas.drawCircle(r.cx, r.cy, rad, strokePaint)
					}
					else
					{
						canvas.drawRoundRect(tmpRect, 2.5f * m, 2.5f * m, fillPaint)
						canvas.drawRoundRect(tmpRect, 2.5f * m, 2.5f * m, strokePaint)
					}
					val labelColor = col(if(on) theme.pressedText else if(lit) accent else line)
					if(!drawIcon(canvas, z, r.cx, r.cy, min(r.w, r.h) * 0.62f, labelColor))
						drawText(canvas, label(z), r.cx, r.cy, (if(z.fixed) 2.4f else 3.2f) * m, labelColor)
				}
			}
		}
	}

	/** how far the trigger is pulled: a bar along the bottom of the zone */
	private fun drawTriggerLevel(canvas: Canvas, z: Zone)
	{
		val m = geometry.mm
		val r = geometry.rect(z)
		fillPaint.color = col(accent)
		val level = triggerLevel(z.button)
		canvas.drawRect(r.x + m, r.bottom - 1.6f * m, r.x + m + (r.w - 2f * m) * level, r.bottom - 0.8f * m, fillPaint)
	}

	/**
	 * What moves with the fingers, drawn every frame over the resting picture: the sticks, the
	 * thumbs on the button groups and how far the triggers are pulled. With the lamp on they are
	 * always under a light, so at full strength (the lit pass used to draw them so).
	 */
	private fun drawMoving(canvas: Canvas)
	{
		fade = if(settings.lamp) 1f else settings.opacity
		lit = false
		for(z in padLayout.active)
			if(z.kind == ZoneKind.TRIGGER && isDown(z.button) && settings.triggerPressure(z.side))
				drawTriggerLevel(canvas, z)
		for(f in fingers.values)
		{
			val z = f.zone ?: continue
			when(f.mode)
			{
				Mode.STICK -> if(z.kind == ZoneKind.STICK || z.kind == ZoneKind.CAM || z.kind == ZoneKind.TRIGGER) drawActiveStick(canvas, f)
				Mode.CLUSTER -> drawThumb(canvas, f, clusterRadiusPx(z))
				else -> {}
			}
		}
	}

	private fun drawEditing(canvas: Canvas)
	{
		val m = geometry.mm
		val now = SystemClock.uptimeMillis()
		val flashing = now - flashAt < FLASH_MS
		val t = touchRect
		touchEditPaint.strokeWidth = max(2f, m * 0.3f)
		if(editTarget == EditTarget.TOUCHPAD)
		{
			// everything else dimmed; the video outlined when the touchpad is tied to it
			fillPaint.color = 0x99000000.toInt()
			canvas.drawRect(0f, 0f, geometry.width, geometry.height, fillPaint)
			if(touchTied)
			{
				val v = videoRect
				canvas.drawRect(v.x, v.y, v.right, v.bottom, editPaint)
			}
			fillPaint.color = withAlpha(TOUCH_EDIT_COLOR, 0x30)
			canvas.drawRect(t.x, t.y, t.right, t.bottom, fillPaint)
			val inset = touchEditPaint.strokeWidth
			canvas.drawRect(t.x + inset, t.y + inset, t.right - inset, t.bottom - inset, touchEditPaint)
			fillPaint.color = TOUCH_EDIT_COLOR
			for(c in 0..3)
				canvas.drawCircle(cornerX(t, c), cornerY(t, c), HANDLE_MM * m * 0.45f, fillPaint)
			drawText(canvas, context.getString(if(touchTied) R.string.touch_edit_label_tied else R.string.touch_edit_label_free),
				t.cx, t.cy, 3f * m, TOUCH_EDIT_COLOR)
			return
		}
		// the touchpad, faintly: a double tap on the video edits it
		if(padLayout.touchpadOn)
		{
			val inset = touchEditPaint.strokeWidth
			touchEditPaint.alpha = 0x80
			canvas.drawRect(t.x + inset, t.y + inset, t.right - inset, t.bottom - inset, touchEditPaint)
			touchEditPaint.alpha = 0xFF
			drawText(canvas, "TOUCHPAD", t.cx, t.y + 4f * m, 2.6f * m, withAlpha(TOUCH_EDIT_COLOR, 0x99))
		}
		if(videoEditable)
		{
			val v = videoRect
			if(flashing && flashVideo)
			{
				fillPaint.color = 0x4000E5FF
				canvas.drawRect(v.x, v.y, v.right, v.bottom, fillPaint)
			}
			editPaint.strokeWidth = max(2f, m * (if(videoRaised) 0.6f else 0.3f))
			canvas.drawRect(v.x, v.y, v.right, v.bottom, editPaint)
			editPaint.strokeWidth = max(2f, m * 0.3f)
			// four corners, a little inside
			fillPaint.color = 0xFF00E5FF.toInt()
			val inset = videoCornerInset()
			val inner = RectF4(v.x + inset, v.y + inset, v.w - 2f * inset, v.h - 2f * inset)
			for(c in 0..3)
				canvas.drawCircle(cornerX(inner, c), cornerY(inner, c), HANDLE_MM * m * 0.4f, fillPaint)
		}
		for(z in padLayout.active)
		{
			val r = geometry.rect(z)
			tmpRect.set(r.x, r.y, r.right, r.bottom)
			if(flashing && z === flashZone)
			{
				fillPaint.color = 0x5500E5FF
				canvas.drawRect(tmpRect, fillPaint)
			}
			canvas.drawRect(tmpRect, editPaint)
			// the size handle, on the corner chosen with a double tap
			fillPaint.color = 0xFF00E5FF.toInt()
			val hs = HANDLE_MM * m * 0.6f
			val c = cornerOf(z)
			val hx = if(isRightCorner(c)) r.right - hs else r.x
			val hy = if(isBottomCorner(c)) r.bottom - hs else r.y
			canvas.drawRect(hx, hy, hx + hs, hy + hs, fillPaint)
		}
	}

	private val touchEditPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = TOUCH_EDIT_COLOR
		pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
	}

	/** the test screen shows the dead zone (red) and the ring of two buttons (green) */
	var showZones = false
		set(value) { field = value; lookChanged(); invalidate() }
	/**
	 * The four eggs of a group, made again only when its place, size or shape changes. They used
	 * to be rebuilt (4 × 49 points with sines and cosines) in every frame, and a path that is new
	 * each time cannot reuse what the GPU side prepared for it the time before.
	 */
	private class EggCache
	{
		val paths = Array(4) { Path() }
		/** centre x, y, radius, rotation, width, taper the paths were made for */
		val key = FloatArray(6) { Float.NaN }
	}
	private val eggCaches = HashMap<Zone, EggCache>()

	private fun eggs(z: Zone, cx: Float, cy: Float, s: Float, shape: ClusterShape): Array<Path>
	{
		val cache = eggCaches.getOrPut(z) { EggCache() }
		val k = cache.key
		val rotation = shape.rotation.toFloat()
		val width = shape.width.toFloat()
		val taper = shape.taper
		if(k[0] == cx && k[1] == cy && k[2] == s && k[3] == rotation && k[4] == width && k[5] == taper)
			return cache.paths
		k[0] = cx; k[1] = cy; k[2] = s; k[3] = rotation; k[4] = width; k[5] = taper
		val a = Math.toRadians(rotation.toDouble())
		val cos = kotlin.math.cos(a).toFloat()
		val sin = kotlin.math.sin(a).toFloat()
		val c = (ClusterShape.EGG_IN + ClusterShape.EGG_OUT) / 2f
		val l = (ClusterShape.EGG_OUT - ClusterShape.EGG_IN) / 2f
		val w = width / 100f
		for(i in 0 until 4)
		{
			val ax = AXIS_X[i]
			val ay = AXIS_Y[i]
			val p = cache.paths[i]
			p.reset()
			for(n in 0..48)
			{
				val t = n / 48.0 * Math.PI * 2.0
				val ct = kotlin.math.cos(t).toFloat()
				val u = c + l * ct
				val v = w * kotlin.math.sin(t).toFloat() * (1f - taper * ct)
				val lx = u * ax - v * ay
				val ly = u * ay + v * ax
				// group coordinates (radii, upright) -> screen
				val x = cx + s * (lx * cos - ly * sin)
				val y = cy + s * (lx * sin + ly * cos)
				if(n == 0) p.moveTo(x, y) else p.lineTo(x, y)
			}
			p.close()
		}
		return cache.paths
	}

	private fun drawCluster(canvas: Canvas, z: Zone, part: Part = Part.ALL)
	{
		val r = geometry.rect(z)
		val s = clusterRadiusPx(z)
		val shape = settings.shape(z.kind)
		val names = clusterNames(z)
		val a = Math.toRadians(shape.rotation.toDouble())
		val cos = kotlin.math.cos(a).toFloat()
		val sin = kotlin.math.sin(a).toFloat()
		// group coordinates (radii, upright) -> screen
		fun px(lx: Float, ly: Float) = r.cx + s * (lx * cos - ly * sin)
		fun py(lx: Float, ly: Float) = r.cy + s * (lx * sin + ly * cos)

		var downCount = 0
		for(n in names) if(shownDown(n)) downCount++
		val both = downCount == 2
		if(part != Part.PRESSED)
		{
			// the coloured zones are a help on the test screen: drawn once, at full strength
			if(showZones && !lit)
			{
				fillPaint.color = if(both) 0xCC9BE26B.toInt() else 0x996FBF44.toInt()
				canvas.drawCircle(r.cx, r.cy, s * shape.green / 100f, fillPaint)
				fillPaint.color = 0x99EF5656.toInt()
				canvas.drawCircle(r.cx, r.cy, s * shape.dead / 100f, fillPaint)
			}
			else if(!showZones)
			{
				// the ring, faintly: where two buttons press together
				fillPaint.color = col(withAlpha(accent, if(both) 0x55 else if(lit) 0x30 else 0x1C))
				canvas.drawCircle(r.cx, r.cy, s * shape.green / 100f, fillPaint)
			}
		}

		val paths = eggs(z, r.cx, r.cy, s, shape)
		for(i in 0 until 4)
		{
			val ax = AXIS_X[i]
			val ay = AXIS_Y[i]
			val on = shownDown(names[i])
			if(part == Part.PRESSED && !on)
				continue
			fillPaint.color = if(on) onCol(accent) else idle(theme.buttonFill)
			canvas.drawPath(paths[i], fillPaint)
			strokePaint.color = col(if(lit) accent else line)
			canvas.drawPath(paths[i], strokePaint)
			// the symbol stays upright, on the fat part of the egg
			val su = if(shape.invert) 0.7f else 0.6f
			drawText(canvas, symbol(names[i]), px(su * ax, su * ay), py(su * ax, su * ay), s * 0.3f,
				col(if(on) theme.pressedText else if(lit) accent else line))
		}

		// where the thumb is (moves with it: not part of the resting picture)
		if(part == Part.ALL)
			for(f in fingers.values)
				if(f.mode == Mode.CLUSTER && f.zone === z)
					drawThumb(canvas, f, s)
	}

	private fun drawThumb(canvas: Canvas, f: Finger, groupRadius: Float)
	{
		fillPaint.color = col(withAlpha(accent, 0x99))
		canvas.drawCircle(f.x, f.y, groupRadius * 0.13f, fillPaint)
	}

	private fun drawActiveStick(canvas: Canvas, f: Finger)
	{
		val st = stick(f.side)
		// squashing holds a button: the stick lights up so it is clear it is held
		val held = f.squashHeld != 0U
		if(f.asStick)
		{
			val r = travel()
			if(held)
			{
				fillPaint.color = col(withAlpha(accent, 0x44))
				canvas.drawCircle(f.ox, f.oy, r * 1.25f, fillPaint)
			}
			strokePaint.color = col(accent)
			canvas.drawCircle(f.ox, f.oy, r, strokePaint)
			fillPaint.color = col(withAlpha(accent, 0xCC))
			if(st.finger == f.id)
				canvas.drawCircle(f.ox + st.x * r, f.oy + st.y * r, r * 0.45f, fillPaint)
		}
		else
		{
			fillPaint.color = col(withAlpha(accent, if(held) 0xDD else 0x99))
			canvas.drawCircle(f.x, f.y, (if(held) 5.5f else 4f) * geometry.mm, fillPaint)
		}
		// test screen with "show zones": how much bigger than at rest the contact is (to tune the squash strength)
		val d = f.squash
		if(showZones && d != null)
		{
			drawText(canvas, if(d.calibrated) "%.0f  (on %.0f / off %.0f)".format(f.size, d.onLevel, d.offLevel)
				else "x%.2f / %.2f".format(d.ratio(f.size), settings.squashRatio), f.x, f.y - 8f * geometry.mm, 3f * geometry.mm, col(accent))
		}
	}
}
