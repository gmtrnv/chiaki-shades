// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols.console

import com.metallic.chiaki.lib.ControllerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gyroscope-as-a-stick maths: signs, dead zone, activation, tilt. Plain JVM, no Android. */
class GyroStickTest
{
	private fun gyro(vararg options: Pair<String, Any>): GyroStick
	{
		val prefs = FakePrefs()
		prefs.values[ConsolePadSettings.KEY_GYRO] = true
		// no smoothing and no anti dead zone unless a test asks: the numbers are then plain
		prefs.values[ConsolePadSettings.KEY_GYRO_SMOOTH] = 0
		prefs.values[ConsolePadSettings.KEY_GYRO_ANTI_DEADZONE] = 0
		prefs.values[ConsolePadSettings.KEY_GYRO_DEADZONE] = 0
		prefs.values[ConsolePadSettings.KEY_GYRO_ACTIVATION] = GyroActivation.ALWAYS.value
		options.forEach { prefs.values[it.first] = it.second }
		return GyroStick(ConsolePadSettings(prefs)).also { it.engage(false) }
	}

	private val deg = Math.PI.toFloat() / 180f
	private val dt = 0.008f

	@Test fun turningLeftPushesTheStickLeft()
	{
		val g = gyro()
		// a positive turn about y is turning left
		g.feed(0f, 50f * deg, 0f, 0f, 1f, 0f, dt)
		assertEquals(-0.5f, g.outX, 0.01f)
		assertEquals(0f, g.outY, 0.001f)
	}

	@Test fun noseUpPushesTheStickUp()
	{
		val g = gyro()
		// a positive turn about x is the nose going up; up on a stick is negative
		g.feed(50f * deg, 0f, 0f, 0f, 1f, 0f, dt)
		assertEquals(-0.5f, g.outY, 0.01f)
	}

	@Test fun fullSpeedIsAllTheWayAndNoMore()
	{
		val g = gyro()
		g.feed(0f, -400f * deg, 0f, 0f, 1f, 0f, dt)
		assertEquals(1f, g.outX, 0.001f)
	}

	@Test fun shakingBelowTheDeadZoneDoesNothing()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_DEADZONE to 3)
		// 3 % of 100 deg/s
		g.feed(0f, 2f * deg, 0f, 0f, 1f, 0f, dt)
		assertEquals(0f, g.outX, 0f)
	}

	@Test fun antiDeadZoneJumpsPastTheGamesOwn()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ANTI_DEADZONE to 20)
		g.feed(0f, -10f * deg, 0f, 0f, 1f, 0f, dt)
		// 10 deg/s is a bit past the dead zone, but the output starts at 20 %
		assertTrue(g.outX >= 0.2f)
	}

	@Test fun invertAndSensitivity()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_INVERT_X to true, ConsolePadSettings.KEY_GYRO_SENS to 200)
		g.feed(0f, 20f * deg, 0f, 0f, 1f, 0f, dt)
		// twice the sensitivity: 20 deg/s counts as 40; inverted: turning left goes right
		assertEquals(0.4f, g.outX, 0.01f)
	}

	@Test fun rollWorksAsAWheel()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_HORIZONTAL to GyroHorizontal.ROLL.value)
		g.feed(0f, 80f * deg, 50f * deg, 0f, 1f, 0f, dt)
		// the yaw is ignored, the roll (wheel turned left) pushes left
		assertEquals(-0.5f, g.outX, 0.01f)
	}

	@Test fun holdWorksOnlyWhileTheButtonIsHeld()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.HOLD.value)
		assertFalse(g.engage(false))
		g.feed(0f, 50f * deg, 0f, 0f, 1f, 0f, dt)
		assertEquals(0f, g.outX, 0f)
		assertTrue(g.engage(true))
		g.feed(0f, 50f * deg, 0f, 0f, 1f, 0f, dt)
		assertEquals(-0.5f, g.outX, 0.01f)
		// letting go stops it at once
		assertFalse(g.engage(false))
		assertEquals(0f, g.outX, 0f)
	}

	@Test fun toggleStartsOffAndEachPressFlipsIt()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.TOGGLE.value)
		var changes = 0
		g.onPausedChanged = { changes++ }
		assertFalse(g.engage(false))
		assertTrue(g.engage(true))
		// held: no new press
		assertTrue(g.engage(true))
		// letting go keeps it on
		assertTrue(g.engage(false))
		assertFalse(g.engage(true))
		assertFalse(g.engage(false))
		assertEquals(2, changes)
	}

	@Test fun hearingTheSwitchAndCallingBackDoesNotLoop()
	{
		// the test screen recomputes its state when the switch changes: that went back into engage() and
		// saw the same press again, flipping forever (a StackOverflowError on the first press)
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.TOGGLE.value,
			ConsolePadSettings.KEY_GYRO_INPUTS to PadButton.L3.value)
		val held = ControllerState(buttons = ControllerState.BUTTON_L3)
		var calls = 0
		g.onPausedChanged = { calls++; g.process(held.copy()) }
		g.process(held.copy())
		assertEquals(1, calls)
		assertFalse(g.paused)
		// still held: nothing more; released and pressed again: off
		g.process(held.copy())
		assertEquals(1, calls)
		g.process(ControllerState())
		g.process(held.copy())
		assertEquals(2, calls)
		assertTrue(g.paused)
	}

	@Test fun parsingTheInputs()
	{
		assertEquals(listOf("l2"), GyroInputs.parse(null))
		assertEquals(listOf("l2"), GyroInputs.parse("nonsense,"))
		// sticks first, then the buttons in the picker's order; duplicates once
		assertEquals(listOf("stick_r", "l3", "r3"), GyroInputs.parse("r3, stick_r,l3,l3"))
		assertEquals("stick_l,l3", GyroInputs.encode(listOf("l3", "stick_l")))
	}

	@Test fun everyInputMustBeHeldAtOnce()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.HOLD.value,
			ConsolePadSettings.KEY_GYRO_INPUTS to "l3,r3")
		val l3 = ControllerState(buttons = ControllerState.BUTTON_L3)
		val both = ControllerState(buttons = ControllerState.BUTTON_L3 or ControllerState.BUTTON_R3)
		assertFalse(g.inputsHeld(l3))
		assertTrue(g.inputsHeld(both))
		g.process(l3.copy())
		assertFalse(g.engaged)
		g.process(both.copy())
		assertTrue(g.engaged)
	}

	@Test fun aPushedStickIsAnInputAndLettingGoTakesAMoment()
	{
		// gyroscope always on, paused while the right stick is moved: the fine adjustment with the thumb
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.CLUTCH.value,
			ConsolePadSettings.KEY_GYRO_INPUTS to "stick_r")
		var now = 1000L
		g.clock = { now }
		g.process(ControllerState())
		assertTrue(g.engaged)
		// a small push (12 %) is under the 25 % it takes: still engaged
		g.process(ControllerState(rightX = 4000))
		assertTrue(g.engaged)
		// pushed (49 %): paused
		g.process(ControllerState(rightX = 16000))
		assertFalse(g.engaged)
		// let go: still paused for a moment, then back
		now += 100
		g.process(ControllerState())
		assertFalse(g.engaged)
		now += 100
		g.process(ControllerState())
		assertTrue(g.engaged)
	}

	@Test fun buttonsHaveNoDelayOnLettingGo()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.HOLD.value,
			ConsolePadSettings.KEY_GYRO_INPUTS to "l3")
		g.clock = { 0L }
		g.process(ControllerState(buttons = ControllerState.BUTTON_L3))
		assertTrue(g.engaged)
		g.process(ControllerState())
		assertFalse(g.engaged)
	}

	@Test fun toggleWithACombinationFlipsWhenItCompletes()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.TOGGLE.value,
			ConsolePadSettings.KEY_GYRO_INPUTS to "l3,r3")
		val l3 = ControllerState(buttons = ControllerState.BUTTON_L3)
		val both = ControllerState(buttons = ControllerState.BUTTON_L3 or ControllerState.BUTTON_R3)
		assertTrue(g.paused)
		g.process(l3.copy())
		assertTrue(g.paused)
		g.process(both.copy())
		assertFalse(g.paused)
		g.process(both.copy())
		assertFalse(g.paused)
		g.process(l3.copy())
		g.process(both.copy())
		assertTrue(g.paused)
	}

	@Test fun theSwitchIsRememberedBetweenSessions()
	{
		val prefs = FakePrefs()
		prefs.values[ConsolePadSettings.KEY_GYRO] = true
		prefs.values[ConsolePadSettings.KEY_GYRO_ACTIVATION] = GyroActivation.ALWAYS.value
		val first = GyroStick(ConsolePadSettings(prefs))
		assertFalse(first.paused)
		first.toggle()
		// a new session (a new engine on the same preferences) finds it as it was left
		val second = GyroStick(ConsolePadSettings(prefs))
		assertTrue(second.paused)
		assertFalse(second.engage(false))
		second.toggle()
		assertFalse(GyroStick(ConsolePadSettings(prefs)).paused)
	}

	@Test fun toggleStartsOffOnlyTheFirstTime()
	{
		val prefs = FakePrefs()
		prefs.values[ConsolePadSettings.KEY_GYRO] = true
		prefs.values[ConsolePadSettings.KEY_GYRO_ACTIVATION] = GyroActivation.TOGGLE.value
		assertTrue(GyroStick(ConsolePadSettings(prefs)).paused)
		// once decided (switched on), it is remembered on
		val g = GyroStick(ConsolePadSettings(prefs))
		g.toggle()
		assertFalse(GyroStick(ConsolePadSettings(prefs)).paused)
	}

	@Test fun clearingTheSavedStateForcesTheStartAgain()
	{
		val prefs = FakePrefs()
		prefs.values[ConsolePadSettings.KEY_GYRO] = true
		prefs.values[ConsolePadSettings.KEY_GYRO_ACTIVATION] = GyroActivation.ALWAYS.value
		val g = GyroStick(ConsolePadSettings(prefs))
		g.toggle()
		assertTrue(g.paused)
		// the panel clears it when the gyroscope stick is switched on again; the engine takes it again
		prefs.values.remove(ConsolePadSettings.KEY_GYRO_PAUSED)
		g.reloadPaused()
		assertFalse(g.paused)
	}

	@Test fun theOtherButtonsFlipTheSameSwitch()
	{
		val g = gyro()
		assertTrue(g.engage(false))
		g.toggle()
		assertFalse(g.engage(false))
		g.toggle()
		assertTrue(g.engage(false))
	}

	@Test fun clutchPausesWhileTheButtonIsHeld()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_ACTIVATION to GyroActivation.CLUTCH.value)
		assertTrue(g.engage(false))
		assertFalse(g.engage(true))
	}

	@Test fun processAddsToTheStickAndQuietsTheGyroscope()
	{
		val g = gyro()
		g.feed(0f, 50f * deg, 0f, 0f, 1f, 0f, dt)
		val state = ControllerState(rightX = 1000, gyroY = 3f)
		val out = g.process(state)
		// 0.5 of full scale to the left on top of the thumb's own push
		assertEquals((1000 - 16383).toDouble(), out.rightX.toDouble(), 40.0)
		assertEquals(0f, out.gyroY, 0f)
	}

	@Test fun disabledChangesNothing()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO to false)
		val state = ControllerState(rightX = 5, gyroY = 3f)
		val out = g.process(state)
		assertEquals(5, out.rightX.toInt())
		assertEquals(3f, out.gyroY, 0f)
	}

	@Test fun tiltFollowsTheAngleFromWhereItStarted()
	{
		val g = gyro(ConsolePadSettings.KEY_GYRO_STYLE to GyroStyle.TILT.value, ConsolePadSettings.KEY_GYRO_TILT_RANGE to 30)
		val rest = 0f to 1f
		repeat(5) { g.feed(0f, 0f, 0f, rest.first, rest.second, 0f, dt) }
		assertEquals(0f, g.outX, 0.01f)
		// 15 degrees of wheel turned left: the accelerometer reads sin / cos of the angle on x / y
		val a = 15f * deg
		repeat(200) { g.feed(0f, 0f, 0f, Math.sin(a.toDouble()).toFloat(), Math.cos(a.toDouble()).toFloat(), 0f, dt) }
		// half of the 30 degrees, to the left (the smoothing has settled)
		assertEquals(-0.5f, g.outX, 0.03f)
		// nose up 15 degrees: az = -sin
		val b = 15f * deg
		repeat(200) { g.feed(0f, 0f, 0f, 0f, Math.cos(b.toDouble()).toFloat(), -Math.sin(b.toDouble()).toFloat(), dt) }
		assertEquals(-0.5f, g.outY, 0.03f)
	}
}
