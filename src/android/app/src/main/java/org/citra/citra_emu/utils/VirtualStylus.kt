// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.view.Choreographer
import org.citra.citra_emu.NativeLibrary
import kotlin.math.hypot

/**
 * Turns the right stick into a virtual finger on the 3DS bottom screen, so games that aim with the
 * stylus (e.g. Kid Icarus: Uprising) can be played with two sticks.
 *
 * The game reads stylus drags relatively, so the finger is dragged by the stick and, when it runs
 * out of room at a screen edge, it is briefly lifted and put back down on the opposite side
 * ("regrip") so the drag can keep going.
 *
 * AIR mode: the finger never lifts on its own, so the reticle stays where it was aimed.
 * LAND mode: the finger lifts shortly after the stick is released, so the camera stops.
 */
class VirtualStylus : Choreographer.FrameCallback {
    enum class Mode { AIR, LAND }

    private enum class State { UP, DOWN, EDGE_HOLD, REGRIP_UP }

    var mode = Mode.AIR
        private set

    private var stickX = 0f
    private var stickY = 0f

    private var x = 0.5f
    private var y = 0.5f
    private var state = State.UP
    private var timer = 0f

    // Where to put the finger back down after a regrip
    private var regripX = 0.5f
    private var regripY = 0.5f

    private var lastFrameNanos = 0L
    private var running = false

    fun onStick(sx: Float, sy: Float) {
        stickX = sx
        stickY = sy
        start()
    }

    fun toggleMode(): Mode {
        lift()
        mode = if (mode == Mode.AIR) Mode.LAND else Mode.AIR
        return mode
    }

    fun stop() {
        lift()
        stickX = 0f
        stickY = 0f
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this)
            running = false
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        }
        lastFrameNanos = frameTimeNanos

        if (NativeLibrary.isRunning()) {
            tick(dt)
        }

        if (state != State.UP || stickX != 0f || stickY != 0f) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }

    private fun tick(dt: Float) {
        // Deadzone, then a squared response curve for finer small movements
        val mag = hypot(stickX, stickY)
        val active = mag > DEADZONE
        var vx = 0f
        var vy = 0f
        if (active) {
            val scaled = ((mag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
            val curve = scaled * scaled
            vx = stickX / mag * curve
            vy = stickY / mag * curve
        }

        when (state) {
            State.EDGE_HOLD -> {
                // Stay still before lifting, so the game doesn't read it as a flick
                timer -= dt
                press()
                if (timer <= 0f) {
                    release()
                    state = State.REGRIP_UP
                    timer = REGRIP_UP_TIME
                }
                return
            }

            State.REGRIP_UP -> {
                // Stay lifted long enough for the game to notice
                timer -= dt
                if (timer <= 0f) {
                    x = regripX
                    y = regripY
                    if (active || mode == Mode.AIR) {
                        state = State.DOWN
                        timer = 0f
                        press()
                    } else {
                        state = State.UP
                    }
                }
                return
            }

            State.UP -> {
                if (!active) return
                x = 0.5f
                y = 0.5f
                state = State.DOWN
                timer = 0f
            }

            State.DOWN -> Unit
        }

        // State.DOWN
        if (!active) {
            press() // keep the finger still
            if (mode == Mode.LAND) {
                timer += dt
                if (timer >= LAND_RELEASE_DELAY) {
                    lift()
                }
            }
            return
        }
        timer = 0f

        val speed = if (mode == Mode.AIR) SPEED_AIR else SPEED_LAND
        x += vx * speed * dt
        y += vy * speed * dt * ASPECT

        val hitLeft = x < EDGE
        val hitRight = x > 1f - EDGE
        val hitTop = y < EDGE
        val hitBottom = y > 1f - EDGE
        x = x.coerceIn(EDGE, 1f - EDGE)
        y = y.coerceIn(EDGE, 1f - EDGE)
        press()

        if (hitLeft || hitRight || hitTop || hitBottom) {
            // Out of room: come back down on the opposite side to keep the drag going
            regripX = when {
                hitRight -> REGRIP_ANCHOR
                hitLeft -> 1f - REGRIP_ANCHOR
                else -> x
            }
            regripY = when {
                hitBottom -> REGRIP_ANCHOR
                hitTop -> 1f - REGRIP_ANCHOR
                else -> y
            }
            state = State.EDGE_HOLD
            timer = EDGE_HOLD_TIME
        }
    }

    private fun press() {
        NativeLibrary.setVirtualStylus(x, y, true)
    }

    private fun release() {
        if (NativeLibrary.isRunning()) {
            NativeLibrary.setVirtualStylus(0f, 0f, false)
        }
    }

    private fun lift() {
        if (state != State.UP) {
            release()
        }
        state = State.UP
        timer = 0f
        x = 0.5f
        y = 0.5f
    }

    companion object {
        private const val DEADZONE = 0.15f

        // Screen widths per second at full tilt
        private const val SPEED_AIR = 1.6f
        private const val SPEED_LAND = 1.9f

        // The 3DS bottom screen is 320x240, keep vertical speed equal in pixels
        private const val ASPECT = 320f / 240f

        private const val EDGE = 0.04f
        private const val REGRIP_ANCHOR = 0.08f

        // Timings in seconds, long enough to span a couple of game frames at 30fps
        private const val EDGE_HOLD_TIME = 0.04f
        private const val REGRIP_UP_TIME = 0.07f
        private const val LAND_RELEASE_DELAY = 0.12f
    }
}
