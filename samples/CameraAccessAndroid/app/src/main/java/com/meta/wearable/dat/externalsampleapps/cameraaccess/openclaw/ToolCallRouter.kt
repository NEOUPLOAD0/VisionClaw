package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ToolCallRouter"
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val inFlightJobs = mutableMapOf<String, Job>()
    private var consecutiveFailures = 0

    fun handleToolCall(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val callId = call.id
        val callName = call.name

        Log.d(TAG, "Received: $callName (id: $callId) args: ${call.args}")
        if (callName == "set_timer" || callName == "set_alarm") {
            val tMin = runCatching { call.args["minutes"]?.toString()?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
            val tSec = runCatching { call.args["seconds"]?.toString()?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
            val tHour = runCatching { call.args["hour"]?.toString()?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
            val tMinute = runCatching { call.args["minute"]?.toString()?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 0
            val tLabel = runCatching { call.args["label"]?.toString() }.getOrNull() ?: ""
            val tResp = com.meta.wearable.dat.externalsampleapps.cameraaccess.local.LocalTools.handle(callName, tMin, tSec, tHour, tMinute, tLabel)
            sendResponse(JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(JSONObject().apply {
                        put("id", callId); put("name", callName); put("response", tResp)
                    }))
                })
            })
            return
        }
        if (callName == "buzz_pavlok" || callName == "set_radar_watch") {
            val bType = runCatching { call.args["type"]?.toString() }.getOrNull() ?: "vibe"
            val bStrength = runCatching { call.args["strength"]?.toString()?.toDoubleOrNull()?.toInt() }.getOrNull() ?: 80
            val bEnabled = runCatching { call.args["enabled"]?.toString()?.toBoolean() }.getOrNull() ?: true
            val bCorner = runCatching { call.args["corner"]?.toString() }.getOrNull() ?: "top_left"
            val pResp = com.meta.wearable.dat.externalsampleapps.cameraaccess.pavlok.PavlokTools.handle(callName, bType, bStrength, bEnabled, bCorner)
            sendResponse(JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(JSONObject().apply {
                        put("id", callId); put("name", callName); put("response", pResp)
                    }))
                })
            })
            return
        }

        // Circuit breaker: stop sending tool calls after repeated failures
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.d(TAG, "Circuit breaker open ($consecutiveFailures consecutive failures), rejecting $callId")
            val errorResult = ToolResult.Failure(
                "Tool execution is temporarily unavailable after $consecutiveFailures consecutive failures. " +
                "Please tell the user you cannot complete this action right now and suggest they check their OpenClaw gateway connection."
            )
            sendResponse(buildToolResponse(callId, callName, errorResult))
            return
        }

        val job = scope.launch {
            val taskDesc = call.args["task"]?.toString() ?: call.args.toString()
            val result = bridge.delegateTask(task = taskDesc, toolName = callName)

            if (!coroutineContext[Job]!!.isCancelled) {
                Log.d(TAG, "Result for $callName (id: $callId): $result")

                when (result) {
                    is ToolResult.Success -> consecutiveFailures = 0
                    is ToolResult.Failure -> consecutiveFailures++
                }

                val response = buildToolResponse(callId, callName, result)
                sendResponse(response)
            } else {
                Log.d(TAG, "Task $callId was cancelled, skipping response")
            }

            inFlightJobs.remove(callId)
        }

        inFlightJobs[callId] = job
    }

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            inFlightJobs[id]?.let { job ->
                Log.d(TAG, "Cancelling in-flight call: $id")
                job.cancel()
                inFlightJobs.remove(id)
            }
        }
        bridge.setToolCallStatus(ToolCallStatus.Cancelled(ids.firstOrNull() ?: "unknown"))
    }

    fun cancelAll() {
        for ((id, job) in inFlightJobs) {
            Log.d(TAG, "Cancelling in-flight call: $id")
            job.cancel()
        }
        inFlightJobs.clear()
        consecutiveFailures = 0
    }

    private fun buildToolResponse(
        callId: String,
        name: String,
        result: ToolResult
    ): JSONObject {
        return JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().put(JSONObject().apply {
                    put("id", callId)
                    put("name", name)
                    put("response", result.toJSON())
                }))
            })
        }
    }
}
