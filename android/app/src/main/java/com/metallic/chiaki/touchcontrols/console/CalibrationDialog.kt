// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R

/**
 * The squash calibration wizard (2.5.2): a full-screen page where one thumb (left or right: each is
 * measured on its own) rests, moves, squashes and eases off, as when playing. What it measures is
 * kept on this device and used by "squash" on that side's controls.
 */
object CalibrationDialog
{
	fun show(activity: AppCompatActivity, side: Side, onClose: () -> Unit)
	{
		val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
		val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
		val view = CalibrationView(activity, side)
		val root = FrameLayout(activity)
		root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		val cancel = Button(activity).apply { setText(R.string.calib_wiz_cancel) }
		val retry = Button(activity).apply { setText(R.string.calib_wiz_retry); visibility = View.GONE }
		val done = Button(activity).apply { setText(R.string.calib_wiz_ok); visibility = View.GONE }
		val lp = { gravity: Int -> FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity).apply { setMargins(32, 32, 32, 32) } }
		root.addView(cancel, lp(Gravity.TOP or Gravity.END))
		root.addView(retry, lp(Gravity.BOTTOM or Gravity.START))
		root.addView(done, lp(Gravity.BOTTOM or Gravity.END))
		dialog.setContentView(root)
		dialog.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
		cancel.setOnClickListener { dialog.dismiss() }
		done.setOnClickListener { dialog.dismiss() }
		retry.setOnClickListener { view.calibrator.reset(); view.refreshButtons() }
		view.onStep = {
			val c = view.calibrator
			retry.visibility = if(c.step == SquashCalibrator.Step.FAILED) View.VISIBLE else View.GONE
			done.visibility = if(c.step == SquashCalibrator.Step.DONE) View.VISIBLE else View.GONE
			cancel.visibility = if(c.step == SquashCalibrator.Step.DONE) View.GONE else View.VISIBLE
			c.result?.let { SquashCalibration.save(prefs, side, it) }
		}
		dialog.setOnDismissListener { view.stop(); onClose() }
		dialog.show()
	}
}

private class CalibrationView(context: Context, private val side: Side) : View(context)
{
	val calibrator = SquashCalibrator()
	var onStep: (() -> Unit)? = null
	private val handler = Handler(Looper.getMainLooper())
	private var pointer = -1
	private var size = 0f
	private var running = true
	private var shownStep: SquashCalibrator.Step? = null
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val gold = 0xFFD4AB55.toInt()

	private val tick = object : Runnable
	{
		override fun run()
		{
			if(!running) return
			val now = SystemClock.uptimeMillis()
			if(pointer >= 0)
				calibrator.sample(size, now)
			refreshButtons()
			invalidate()
			handler.postDelayed(this, 25)
		}
	}

	init { handler.post(tick) }

	fun stop() { running = false; handler.removeCallbacks(tick) }

	fun refreshButtons()
	{
		if(shownStep != calibrator.step) { shownStep = calibrator.step; onStep?.invoke() }
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				pointer = event.getPointerId(0)
				size = event.getTouchMajor(0)
				calibrator.touchDown(SystemClock.uptimeMillis())
			}
			MotionEvent.ACTION_MOVE ->
			{
				val i = event.findPointerIndex(pointer)
				if(i >= 0) size = event.getTouchMajor(i)
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				pointer = -1
				calibrator.touchUp()
			}
		}
		refreshButtons()
		invalidate()
		return true
	}

	override fun onDraw(canvas: Canvas)
	{
		val d = resources.displayMetrics.density
		val w = width.toFloat()
		val h = height.toFloat()
		canvas.drawColor(0xFF101216.toInt())
		paint.textAlign = Paint.Align.CENTER
		paint.typeface = Typeface.DEFAULT_BOLD
		paint.color = gold
		paint.textSize = 22f * d
		canvas.drawText(context.getString(if(side == Side.R) R.string.calib_wiz_title_r else R.string.calib_wiz_title_l), w / 2f, h * 0.16f, paint)

		val step = calibrator.step
		val text = when(step)
		{
			SquashCalibrator.Step.WAIT_TOUCH -> context.getString(if(calibrator.lifted) R.string.calib_wiz_lifted else R.string.calib_wiz_touch)
			SquashCalibrator.Step.NORMAL -> context.getString(R.string.calib_wiz_normal)
			SquashCalibrator.Step.PRESS -> context.getString(R.string.calib_wiz_press, calibrator.round + 1, calibrator.rounds)
			SquashCalibrator.Step.EASE -> context.getString(R.string.calib_wiz_ease, calibrator.round + 1, calibrator.rounds)
			SquashCalibrator.Step.DONE -> context.getString(R.string.calib_wiz_done)
			SquashCalibrator.Step.FAILED -> context.getString(R.string.calib_wiz_failed)
		}
		paint.color = if(step == SquashCalibrator.Step.PRESS) 0xFFFF8A65.toInt() else 0xFFFFFFFF.toInt()
		paint.textSize = 30f * d
		canvas.drawText(text, w / 2f, h * 0.45f, paint)

		// the measurement now, so it is clear the phone notices the thumb
		if(pointer >= 0)
		{
			paint.typeface = Typeface.DEFAULT
			paint.color = 0xFF9AA0A6.toInt()
			paint.textSize = 16f * d
			canvas.drawText(context.getString(R.string.calib_wiz_size, size.toInt()), w / 2f, h * 0.55f, paint)
		}

		// progress
		val pw = w * 0.5f
		val px = (w - pw) / 2f
		val py = h * 0.7f
		paint.color = 0xFF2A2E35.toInt()
		canvas.drawRoundRect(px, py, px + pw, py + 10f * d, 5f * d, 5f * d, paint)
		paint.color = gold
		canvas.drawRoundRect(px, py, px + pw * calibrator.progress(SystemClock.uptimeMillis()), py + 10f * d, 5f * d, 5f * d, paint)
	}
}
