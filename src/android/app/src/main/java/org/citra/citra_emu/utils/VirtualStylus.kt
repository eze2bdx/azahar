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
 * AIR mode: the finger moves around the screen and stays inside it (reticle follows the stylus).
 * LAND mode: the finger drags continuously; when it nears an edge it is lifted (after holding
 * still, so the game doesn't read it as a flick) and put back in the center.
 */
class VirtualStylus : Choreographer.FrameCallback {
    enum class Mode { AIR, LAND }

    var mode = Mode.AIR
        private set

    private var stickX = 0f
    private var stickY = 0f

    private var x = 0.5f
    private var y = 0.5f
    private var pressed = false
    private var idleTime = 0f
    private var holdFrames = 0

    private var lastFrameNanos = 0L
    private var running = false

    fun onStick(sx: Float, sy: Float) {
        stickX = sx
        stickY = sy
        start()
    }

    fun toggleMode(): Mode {
        release()
        mode = if (mode == Mode.AIR) Mode.LAND else Mode.AIR
        x = 0.5f
        y = 0.5f
        return mode
    }

    fun stop() {
        release()
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

        if (pressed || stickX != 0f || stickY != 0f) {
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

        // Land mode: lifting the finger near an edge, holding still first to avoid a flick
        if (holdFrames > 0) {
            holdFrames--
            send()
            if (holdFrames == 0) {
                release()
                x = 0.5f
                y = 0.5f
            }
            return
        }

        if (!active) {
            idleTime += dt
            if (pressed) {
                send() // stay still before lifting
                if (idleTime >= RELEASE_DELAY) {
                    release()
                    if (mode == Mode.LAND) {
                        x = 0.5f
                        y = 0.5f
                    }
                }
            }
            return
        }

        idleTime = 0f
        pressed = true

        val speed = if (mode == Mode.AIR) SPEED_AIR else SPEED_LAND
        x += vx * speed * dt
        y += vy * speed * dt * ASPECT

        if (mode == Mode.AIR) {
            x = x.coerceIn(AIR_MARGIN, 1f - AIR_MARGIN)
            y = y.coerceIn(AIR_MARGIN, 1f - AIR_MARGIN)
        } else if (x < LAND_EDGE || x > 1f - LAND_EDGE || y < LAND_EDGE || y > 1f - LAND_EDGE) {
            x = x.coerceIn(0f, 1f)
            y = y.coerceIn(0f, 1f)
            holdFrames = REGRIP_HOLD_FRAMES
        }
        send()
    }

    private fun send() {
        NativeLibrary.setVirtualStylus(x, y, true)
    }

    private fun release() {
        holdFrames = 0
        idleTime = 0f
        if (pressed) {
            pressed = false
            if (NativeLibrary.isRunning()) {
                NativeLibrary.setVirtualStylus(0f, 0f, false)
            }
        }
    }

    companion object {
        private const val DEADZONE = 0.15f

        // Screen widths per second at full tilt
        private const val SPEED_AIR = 1.6f
        private const val SPEED_LAND = 1.5f

        // The 3DS bottom screen is 320x240, keep vertical speed equal in pixels
        private const val ASPECT = 320f / 240f

        private const val AIR_MARGIN = 0.02f
        private const val LAND_EDGE = 0.1f
        private const val REGRIP_HOLD_FRAMES = 3
        private const val RELEASE_DELAY = 0.12f
    }
}
