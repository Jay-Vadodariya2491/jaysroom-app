package com.jayu.jaysroom

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "JaysRoomController"

/**
 * Talks to one ESP32 board over the exact same WebSocket protocol the dashboard
 * page (webpage.html) already uses - ws://<host>/ws, plain text commands like
 * "MAINLIGHT_OFF" or "PROFILELIGHT_80". This lets the app's master switch track
 * and drive the room's real state without needing to open the web page, and
 * needs no firmware changes since it's speaking the protocol that's already there.
 */
class DeviceController(
    private val device: EspDevice,
    private val onAnyOnChanged: (Boolean) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // last known state of each controllable device, kept in sync from broadcasts
    private var mainLightOn = false
    private var fanOn = false
    private var entranceLightOn = false
    private var profileLightBrightness = 0
    private var dressingLightBrightness = 0

    fun connect() {
        val url = "ws://${device.host}:${device.port}/ws"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS connection to ${device.name} failed: ${t.message}")
                // The switch just stops live-updating until the next connect() call
                // (MainActivity re-creates controllers each time discovery restarts).
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, null)
        socket = null
        client.dispatcher.executorService.shutdown()
    }

    private fun handleMessage(text: String) {
        val sep = text.indexOf('_')
        if (sep <= 0) return
        val deviceId = text.substring(0, sep)
        val cmd = text.substring(sep + 1)

        when (deviceId) {
            "MAINLIGHT" -> mainLightOn = (cmd == "ON")
            "FAN" -> fanOn = (cmd == "ON")
            "ENTRANCELIGHT" -> entranceLightOn = (cmd == "ON")
            "PROFILELIGHT" -> cmd.toIntOrNull()?.let { profileLightBrightness = it }
            "DRESSINGLIGHT" -> {
                // Only the H.._S.._B.. form carries a real brightness; the plain
                // DRESSINGLIGHT_ON/OFF broadcast that follows it is a no-op here.
                val bIndex = cmd.indexOf('B')
                if (bIndex >= 0) {
                    cmd.substring(bIndex + 1).toFloatOrNull()?.let {
                        dressingLightBrightness = it.toInt()
                    }
                }
            }
            else -> return
        }
        notifyState()
    }

    private fun notifyState() {
        val anyOn = mainLightOn || fanOn || entranceLightOn ||
            profileLightBrightness > 0 || dressingLightBrightness > 0
        mainHandler.post { onAnyOnChanged(anyOn) }
    }

    /** Master switch turned off: everything in the room goes off. */
    fun turnAllOff() {
        send("MAINLIGHT_OFF")
        send("FAN_OFF")
        send("ENTRANCELIGHT_OFF")
        send("PROFILELIGHT_0")
        send("DRESSINGLIGHT_H0_S0_B0")
    }

    /** Master switch turned on: the "on" scene - fan on, profile light at 80%. */
    fun applyOnScene() {
        send("FAN_ON")
        send("PROFILELIGHT_80")
    }

    private fun send(message: String) {
        val sent = socket?.send(message) ?: false
        if (!sent) Log.w(TAG, "Could not send \"$message\" - socket not connected")
    }
}
