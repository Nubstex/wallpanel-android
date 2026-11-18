package xyz.wallpanel.app.network

import android.util.Log
import com.koushikdutta.async.AsyncServer
import com.koushikdutta.async.http.body.JSONObjectBody
import com.koushikdutta.async.http.server.AsyncHttpServer
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.app.utils.LedController

/**
 * Emulates enough of WLED's JSON HTTP API (https://kno.wled.ge/interfaces/json-api/) for Home
 * Assistant's WLED integration (or any other WLED client) to control the panel's SICP LED strip
 * directly, without a separate bridge server. Ported from the standalone Flask
 * wled-philips-bridge prototype. Only active while configuration.wledSimulationEnabled is true;
 * LedController ignores this state entirely when it's off.
 */
object WledEmulationServer {

    // Android apps can't bind ports below 1024 without root (EACCES), unlike a real WLED
    // device or a root-owned Docker container, so this can't actually be 80.
    private const val PORT = 8080
    private const val NAME = "WallPanel LED Strip"
    private val EFFECTS = listOf("Solid", "Rainbow", "Breathe")
    private val PALETTES = listOf("Default")

    private var httpServer: AsyncHttpServer? = null

    fun start() {
        if (httpServer != null) return
        Timber.d("Starting WLED emulation server on port $PORT")
        val server = AsyncHttpServer()

        server.addAction("GET", "/json/info") { _, response -> response.send(buildInfo()) }

        server.addAction("GET", "/json/state") { _, response -> response.send(buildState()) }
        server.addAction("POST", "/json/state") { request, response ->
            val body = (request.body as? JSONObjectBody)?.get()
            if (body != null) applyState(body)
            response.send(buildState())
        }

        server.addAction("GET", "/json/si") { _, response ->
            response.send(JSONObject().put("state", buildState()).put("info", buildInfo()))
        }

        server.addAction("GET", "/json") { _, response ->
            response.send(
                JSONObject()
                    .put("state", buildState())
                    .put("info", buildInfo())
                    .put("effects", JSONArray(EFFECTS))
                    .put("palettes", JSONArray(PALETTES))
            )
        }

        try {
            server.listen(AsyncServer.getDefault(), PORT)
            httpServer = server
            Log.i("WledEmulationServer", "Listening on port $PORT")
        } catch (e: Exception) {
            Log.e("WledEmulationServer", "Failed to bind port $PORT", e)
        }
    }

    fun stop() {
        httpServer?.stop()
        httpServer = null
    }

    private fun applyState(body: JSONObject) {
        val on: Boolean? = when {
            !body.has("on") -> null
            body.optString("on") == "t" -> !LedController.wledOn
            else -> body.optBoolean("on")
        }
        val bri: Int? = if (body.has("bri")) body.optInt("bri") else null

        var color: IntArray? = null
        var fx: Int? = null
        var sx: Int? = null
        val segs = body.optJSONArray("seg")
        if (segs != null && segs.length() > 0) {
            val seg = segs.optJSONObject(0)
            if (seg != null) {
                val col = seg.optJSONArray("col")
                if (col != null && col.length() > 0) {
                    val first = col.get(0)
                    color = when (first) {
                        is JSONArray -> intArrayOf(first.optInt(0), first.optInt(1), first.optInt(2))
                        is String -> {
                            val hex = first.removePrefix("#")
                            if (hex.length >= 6) {
                                intArrayOf(
                                    hex.substring(0, 2).toInt(16),
                                    hex.substring(2, 4).toInt(16),
                                    hex.substring(4, 6).toInt(16)
                                )
                            } else null
                        }
                        else -> null
                    }
                }
                if (seg.has("fx")) fx = seg.optInt("fx")
                if (seg.has("sx")) sx = seg.optInt("sx")
            }
        }

        LedController.setWledState(on = on, bri = bri, color = color, fx = fx, sx = sx)
    }

    private fun buildInfo(): JSONObject {
        return JSONObject().apply {
            put("ver", "0.15.0")
            put("vid", 1903252)
            put("leds", JSONObject().apply {
                put("count", 1)
                put("rgbw", false)
                put("maxseg", 1)
                put("pwr", 0)
                put("maxpwr", 0)
            })
            put("name", NAME)
            put("udpport", 21324)
            put("live", false)
            put("fxcount", EFFECTS.size)
            put("palcount", PALETTES.size)
            put("arch", "bridge")
            put("core", "wallpanel")
            put("freeheap", 50000)
            put("uptime", System.currentTimeMillis() / 1000)
            put("brand", "WLED")
            put("product", "FOSS")
            put("mac", "aabbccddeeff")
        }
    }

    private fun buildState(): JSONObject {
        val color = LedController.wledColor
        return JSONObject().apply {
            put("on", LedController.wledOn)
            put("bri", LedController.wledBrightness)
            put("transition", 7)
            put("ps", -1)
            put("pl", -1)
            put("nl", JSONObject().apply {
                put("on", false)
                put("dur", 60)
                put("mode", 1)
                put("tbri", 0)
            })
            put("udpn", JSONObject().apply {
                put("send", false)
                put("recv", false)
            })
            put("seg", JSONArray().put(JSONObject().apply {
                put("id", 0)
                put("start", 0)
                put("stop", 1)
                put("len", 1)
                put("col", JSONArray().apply {
                    put(JSONArray(listOf(color[0], color[1], color[2])))
                    put(JSONArray(listOf(0, 0, 0)))
                    put(JSONArray(listOf(0, 0, 0)))
                })
                put("fx", LedController.wledFx)
                put("sx", LedController.wledSpeed)
                put("ix", 128)
                put("pal", 0)
                put("sel", true)
                put("on", LedController.wledOn)
                put("bri", LedController.wledBrightness)
                put("rev", false)
            }))
        }
    }
}
