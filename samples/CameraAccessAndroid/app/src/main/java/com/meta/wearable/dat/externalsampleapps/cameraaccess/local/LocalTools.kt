package com.meta.wearable.dat.externalsampleapps.cameraaccess.local

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONObject

object LocalTools {
    private const val TAG = "LocalTools"
    @SuppressLint("StaticFieldLeak")
    @Volatile var appContext: Context? = null

    fun timerDeclaration(): JSONObject = JSONObject().apply {
        put("name", "set_timer")
        put("description", "Set a countdown timer on the user's phone Clock app. Use when the user asks for a timer, e.g. 'set a timer for 10 minutes'.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("minutes", JSONObject().put("type", "integer").put("description", "Minutes. Default 0."))
                put("seconds", JSONObject().put("type", "integer").put("description", "Extra seconds. Default 0."))
                put("label", JSONObject().put("type", "string").put("description", "Timer label."))
            })
        })
    }

    fun alarmDeclaration(): JSONObject = JSONObject().apply {
        put("name", "set_alarm")
        put("description", "Set an alarm at a clock time on the user's phone. Use when the user asks for an alarm, e.g. 'wake me at 7am'.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("hour", JSONObject().put("type", "integer").put("description", "Hour 0-23."))
                put("minute", JSONObject().put("type", "integer").put("description", "Minute 0-59. Default 0."))
                put("label", JSONObject().put("type", "string").put("description", "Alarm label."))
            })
        })
    }

    fun handle(name: String, minutes: Int, seconds: Int, hour: Int, minute: Int, label: String): JSONObject {
        val ctx = appContext ?: return JSONObject().put("output", "App context unavailable.")
        return try {
            when (name) {
                "set_timer" -> {
                    val total = minutes * 60 + seconds
                    if (total <= 0) return JSONObject().put("output", "Timer length must be positive.")
                    val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, total)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "VisionClaw timer" })
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                    JSONObject().put("output", "Timer set for $minutes min $seconds sec.")
                }
                "set_alarm" -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "VisionClaw alarm" })
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                    JSONObject().put("output", "Alarm set for %02d:%02d.".format(hour, minute))
                }
                else -> JSONObject().put("output", "Unknown tool")
            }
        } catch (e: Exception) {
            Log.e(TAG, "clock intent failed", e)
            JSONObject().put("output", "Could not reach the Clock app: ${e.message}")
        }
    }
}
