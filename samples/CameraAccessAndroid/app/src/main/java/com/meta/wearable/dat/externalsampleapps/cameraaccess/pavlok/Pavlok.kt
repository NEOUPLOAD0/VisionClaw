package com.meta.wearable.dat.externalsampleapps.cameraaccess.pavlok

import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.Secrets
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import org.json.JSONObject

object PavlokClient {
    private const val TAG = "PavlokClient"
    fun buzz(type: String = "vibe", strength: Int = 80) {
        thread {
            try {
                val conn = URL("https://api.pavlok.com/api/v5/stimulus/send").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer " + Secrets.pavlokToken)
                conn.doOutput = true
                val body = JSONObject().put("stimulus", JSONObject()
                    .put("stimulusType", type).put("stimulusValue", strength.coerceIn(1, 100)))
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                Log.d(TAG, "Pavlok $type $strength -> HTTP " + conn.responseCode)
                conn.disconnect()
            } catch (e: Exception) { Log.e(TAG, "Pavlok error", e) }
        }
    }
}

object PavlokTools {
    fun buzzDeclaration(): JSONObject = JSONObject().apply {
        put("name", "buzz_pavlok")
        put("description", "Send a haptic stimulus to the user's Pavlok wristband. Use when the user asks to be buzzed, zapped, beeped, or physically reminded.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("type", JSONObject().put("type", "string").put("description", "vibe, beep, or zap. Default vibe."))
                put("strength", JSONObject().put("type", "integer").put("description", "1-100. Default 80."))
            })
        })
    }
    fun radarDeclaration(): JSONObject = JSONObject().apply {
        put("name", "set_radar_watch")
        put("description", "Turn the on-device game radar watcher on/off. When on, the app scans the minimap corner of the video for close red enemy blips and buzzes the Pavlok automatically.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("enabled", JSONObject().put("type", "boolean").put("description", "true to watch, false to stop"))
                put("corner", JSONObject().put("type", "string").put("description", "top_left, top_right, bottom_left, bottom_right. Default top_left."))
            })
        })
    }
    fun handle(name: String, type: String, strength: Int, enabled: Boolean, corner: String): JSONObject =
        when (name) {
            "buzz_pavlok" -> { PavlokClient.buzz(type, strength); JSONObject().put("output", "Sent $type at $strength.") }
            "set_radar_watch" -> {
                RadarWatcher.enabled = enabled; RadarWatcher.corner = corner
                JSONObject().put("output", if (enabled) "Radar watch ON, $corner corner." else "Radar watch OFF.")
            }
            else -> JSONObject().put("output", "Unknown tool")
        }
}

object RadarWatcher {
    private const val TAG = "RadarWatcher"
    @Volatile var enabled = false
    @Volatile var corner = "top_left"
    @Volatile private var lastBuzz = 0L

    fun onFrame(bitmap: Bitmap) {
        if (!enabled) return
        try {
            val w = bitmap.width; val h = bitmap.height
            val rw = w / 4; val rh = h / 4
            val x0 = if (corner.contains("right")) w - rw else 0
            val y0 = if (corner.contains("bottom")) h - rh else 0
            val cx = x0 + rw / 2; val cy = y0 + rh / 2
            val radius = (minOf(rw, rh) * 0.35).toInt()
            var hits = 0
            var x = maxOf(cx - radius, 0)
            while (x < minOf(cx + radius, w)) {
                var y = maxOf(cy - radius, 0)
                while (y < minOf(cy + radius, h)) {
                    val dx = x - cx; val dy = y - cy
                    if (dx * dx + dy * dy <= radius * radius) {
                        val p = bitmap.getPixel(x, y)
                        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                        if (r > 150 && r > g + 60 && r > b + 60) hits++
                    }
                    y += 3
                }
                x += 3
            }
            if (hits >= 10 && System.currentTimeMillis() - lastBuzz > 3000) {
                lastBuzz = System.currentTimeMillis()
                Log.d(TAG, "Blip detected ($hits px) -> buzz")
                PavlokClient.buzz("vibe", 100)
            }
        } catch (e: Exception) { Log.e(TAG, "scan error", e) }
    }
}
