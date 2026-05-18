package com.example.flutter_answer.agent

import android.app.Activity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

internal class FlutterAgentPlatform(
    private val activity: Activity,
    private val toolDispatcher: NativeToolDispatcher = NativeToolDispatcher.shared,
) {

    fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "openAccessibilitySettings" -> {
                result.success(AccessibilityAgent.openAccessibilitySettings(activity))
            }

            "listTools" -> {
                result.success(
                    mapOf(
                        "ok" to true,
                        "tools" to NativeToolRegistry.listDefinitions(),
                    ),
                )
            }

            "executeTool" -> {
                val requestId = call.argument<String>("requestId")
                    ?: "req_${System.nanoTime()}"
                val toolName = call.argument<String>("toolName").orEmpty()
                val arguments = normalizeMap(call.argument<Map<*, *>>("arguments"))
                toolDispatcher.dispatch(
                    requestId = requestId,
                    toolName = toolName,
                    arguments = arguments,
                ) { payload ->
                    result.success(payload)
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun normalizeMap(value: Map<*, *>?): Map<String, Any?> {
        if (value == null) {
            return emptyMap()
        }
        return value.mapKeys { (key, _) -> key.toString() }
    }
}
