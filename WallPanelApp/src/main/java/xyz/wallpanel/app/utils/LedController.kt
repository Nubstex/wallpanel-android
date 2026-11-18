package xyz.wallpanel.app.utils

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import xyz.wallpanel.app.persistence.Configuration
import kotlin.math.sin

/**
 * Drives the SICP-controlled LED strip. Exactly one of two things is in control at a time:
 * - manual modes (static / active-only / two-state), configured in Settings and following the
 *   panel's active/inactive (screen saver) state via setPanelActive(), or
 * - the WLED simulation (configuration.wledSimulationEnabled), whose state is pushed in by
 *   WledEmulationServer from external HTTP calls (e.g. Home Assistant's WLED integration).
 * Which one applies is decided purely by configuration.wledSimulationEnabled; the Settings UI
 * is responsible for keeping that flag and the manual mode mutually exclusive.
 */
object LedController {

    private data class LedColor(val on: Boolean, val r: Int, val g: Int, val b: Int)

    private const val TICK_INTERVAL_MS = 150L

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private var hue = 0f
    private var breatheT = 0.0

    private lateinit var configuration: Configuration

    @Volatile
    var panelActive: Boolean = true
        private set

    // WLED simulation state, written by WledEmulationServer and/or the MQTT light command topic
    @Volatile var wledOn: Boolean = false
    @Volatile var wledBrightness: Int = 255
    @Volatile var wledColor: IntArray = intArrayOf(255, 255, 255)
    @Volatile var wledFx: Int = 0 // 0 = solid, 1 = rainbow, 2 = breathe
    @Volatile var wledSpeed: Int = 128

    /** Notified after setWledState() changes anything, regardless of which transport (HTTP or MQTT) called it. */
    var onWledStateChanged: (() -> Unit)? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            applyOnce()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    fun init(configuration: Configuration) {
        this.configuration = configuration
        refresh()
    }

    /** Returns true if the active/inactive state actually changed. */
    fun setPanelActive(active: Boolean): Boolean {
        if (panelActive == active) return false
        panelActive = active
        refresh()
        return true
    }

    fun setWledState(on: Boolean? = null, bri: Int? = null, color: IntArray? = null, fx: Int? = null, sx: Int? = null) {
        on?.let { wledOn = it }
        bri?.let { wledBrightness = it.coerceIn(0, 255) }
        color?.let { wledColor = it }
        fx?.let { wledFx = it }
        sx?.let { wledSpeed = it }
        refresh()
        onWledStateChanged?.invoke()
    }

    /** Called whenever anything that affects the current LED output may have changed. */
    fun refresh() {
        if (!::configuration.isInitialized) return
        if (isLoopingNow()) {
            if (!ticking) {
                ticking = true
                handler.post(tickRunnable)
            }
        } else {
            ticking = false
            handler.removeCallbacks(tickRunnable)
            applyOnce()
        }
    }

    private fun isLoopingNow(): Boolean {
        if (configuration.wledSimulationEnabled) {
            return wledOn && wledBrightness > 0 && wledFx != 0
        }
        return when (configuration.ledMode) {
            Configuration.LED_MODE_ACTIVE_ONLY -> panelActive && configuration.ledActiveLoop
            Configuration.LED_MODE_TWO_STATE ->
                if (panelActive) configuration.ledActiveLoop else configuration.ledInactiveLoop
            else -> false
        }
    }

    private fun applyOnce() {
        val color = computeColor()
        if (!color.on) {
            SicpClient.ledOff()
        } else {
            SicpClient.setColor(color.r, color.g, color.b)
        }
    }

    private fun computeColor(): LedColor {
        if (configuration.wledSimulationEnabled) {
            return computeWledColor()
        }
        return when (configuration.ledMode) {
            Configuration.LED_MODE_STATIC -> {
                if (configuration.ledStaticOn) solidColor(configuration.ledStaticColor) else LedColor(false, 0, 0, 0)
            }
            Configuration.LED_MODE_ACTIVE_ONLY -> {
                if (!panelActive) {
                    LedColor(false, 0, 0, 0)
                } else if (configuration.ledActiveLoop) {
                    rainbowColor()
                } else {
                    solidColor(configuration.ledActiveColor)
                }
            }
            Configuration.LED_MODE_TWO_STATE -> {
                val loop = if (panelActive) configuration.ledActiveLoop else configuration.ledInactiveLoop
                val color = if (panelActive) configuration.ledActiveColor else configuration.ledInactiveColor
                if (loop) rainbowColor() else solidColor(color)
            }
            else -> LedColor(false, 0, 0, 0)
        }
    }

    private fun computeWledColor(): LedColor {
        if (!wledOn || wledBrightness == 0) return LedColor(false, 0, 0, 0)
        val scale = wledBrightness / 255f
        return when (wledFx) {
            1 -> {
                val c = rainbowColor()
                LedColor(true, (c.r * scale).toInt(), (c.g * scale).toInt(), (c.b * scale).toInt())
            }
            2 -> {
                breatheT += 0.15
                val level = (0.5 + 0.5 * sin(breatheT)) * scale
                LedColor(
                    true,
                    (wledColor[0] * level).toInt(),
                    (wledColor[1] * level).toInt(),
                    (wledColor[2] * level).toInt()
                )
            }
            else -> LedColor(
                true,
                (wledColor[0] * scale).toInt(),
                (wledColor[1] * scale).toInt(),
                (wledColor[2] * scale).toInt()
            )
        }
    }

    private fun rainbowColor(): LedColor {
        hue = (hue + 0.01f) % 1f
        val rgb = Color.HSVToColor(floatArrayOf(hue * 360f, 1f, 1f))
        return LedColor(true, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    private fun solidColor(color: Int): LedColor {
        return LedColor(true, Color.red(color), Color.green(color), Color.blue(color))
    }
}
