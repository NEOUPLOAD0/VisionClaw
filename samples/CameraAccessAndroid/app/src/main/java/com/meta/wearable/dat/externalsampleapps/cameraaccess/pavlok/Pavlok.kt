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
        // Local fallback: vibrate the phone immediately (no cloud needed)
        try {
            val ctx = com.meta.wearable.dat.externalsampleapps.cameraaccess.local.LocalTools.appContext
            if (ctx != null) {
                val vm = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(600L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (t: Throwable) { Log.e(TAG, "phone vibrate failed", t) }
        iftttTrigger()
        if (Secrets.pavlokToken.isBlank()) return
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
    fun iftttTrigger() {
        if (Secrets.iftttKey.isBlank()) return
        thread {
            try {
                val code = (URL("https://maker.ifttt.com/trigger/metaShock/with/key/" + Secrets.iftttKey).openConnection() as HttpURLConnection).responseCode
                Log.d(TAG, "IFTTT trigger -> HTTP $code")
            } catch (e: Exception) { Log.e(TAG, "IFTTT error", e) }
        }
    }
}

object Callout {
    private var tts: android.speech.tts.TextToSpeech? = null
    fun speak(msg: String) {
        val ctx = com.meta.wearable.dat.externalsampleapps.cameraaccess.local.LocalTools.appContext ?: return
        try {
            if (tts == null) tts = android.speech.tts.TextToSpeech(ctx) { }
            tts?.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "radar")
        } catch (t: Throwable) { Log.e("Callout", "tts failed", t) }
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
    @Volatile private var scanning = false
    @Volatile private var streak = 0

    fun onFrame(bitmap: Bitmap) {
        if (!enabled || scanning) return
        scanning = true
        val safe = try {
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        } catch (t: Throwable) { scanning = false; return }
        thread {
            try { scan(safe) } catch (t: Throwable) { Log.e(TAG, "scan error", t) }
            finally { if (safe !== bitmap) runCatching { safe.recycle() }; scanning = false }
        }
    }

    private fun scan(bmp: Bitmap) {
        val w = bmp.width; val h = bmp.height
        if (w < 40 || h < 40) return
        val rw = w / 3; val rh = h / 3
        val x0 = if (corner.contains("right")) w - rw else 0
        val y0 = if (corner.contains("bottom")) h - rh else 0
        val px = IntArray(rw * rh)
        bmp.getPixels(px, 0, rw, x0, y0, rw, rh)
        val cx = rw / 2; val cy = rh / 2
        val radius = (minOf(rw, rh) * 0.5).toInt()
        val r2 = radius * radius
        var hits = 0
        var y = 0
        while (y < rh) {
            var x = 0
            while (x < rw) {
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy <= r2) {
                    val p = px[y * rw + x]
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    if (r > 150 && r > g + 70 && r > b + 70) hits++
                }
                x += 3
            }
            y += 3
        }
        Log.d(TAG, "scan hits=$hits streak=$streak corner=$corner")
        val plausible = hits in 3..80
        streak = if (plausible) streak + 1 else 0
        if (streak >= 4 && System.currentTimeMillis() - lastBuzz > 1500) {
            streak = 0
            lastBuzz = System.currentTimeMillis()
            Log.d(TAG, "Blip detected ($hits px) -> buzz")
            Callout.speak("Watch out")
            PavlokClient.buzz("vibe", 100)
        }
    }
}