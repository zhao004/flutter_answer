package com.example.flutter_answer.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter 与原生悬浮窗之间的桥接层。
 *
 * 这里仅承接低频控制指令；高频拖动和吸边逻辑完全留在原生侧，
 * 避免因为 MethodChannel 往返把交互重新拖回卡顿状态。
 */
class FlutterOverlayPlatform(
    private val activity: Activity,
) {

    fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        NativeOverlayController.initialize(activity.applicationContext)
        when (call.method) {
            "isPermissionGranted" -> {
                result.success(Settings.canDrawOverlays(activity))
            }

            "requestPermission" -> {
                result.success(requestPermission())
            }

            "isActive" -> {
                result.success(NativeOverlayController.isActive())
            }

            "getOverlayState" -> {
                result.success(NativeOverlayController.snapshot())
            }

            "showOverlay" -> {
                val payload = normalizeMap(call.arguments)
                val position = normalizeMap(payload["position"])
                val x = readDouble(position["x"]) ?: 12.0
                val y = readDouble(position["y"]) ?: 120.0
                val mode = payload["mode"]?.toString() ?: "expanded"
                NativeOverlayController.showOverlay(mode, x, y)
                NativeOverlayService.ensureRunning(activity.applicationContext)
                result.success(null)
            }

            "closeOverlay" -> {
                NativeOverlayController.hideOverlay(notify = true)
                NativeOverlayService.stop(activity.applicationContext)
                result.success(null)
            }

            "moveOverlay" -> {
                val payload = normalizeMap(call.arguments)
                val position = normalizeMap(payload["position"])
                val x = readDouble(position["x"]) ?: return result.error("invalid_args", "缺少 x 坐标。", null)
                val y = readDouble(position["y"]) ?: return result.error("invalid_args", "缺少 y 坐标。", null)
                NativeOverlayController.moveOverlay(x, y)
                result.success(null)
            }

            "sendOverlayMessage" -> {
                val payload = normalizeMap(call.arguments)
                NativeOverlayController.handleFlutterMessage(normalizeMap(payload["payload"]))
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun requestPermission(): Boolean {
        if (Settings.canDrawOverlays(activity)) {
            return true
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return Settings.canDrawOverlays(activity)
    }

    private fun normalizeMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) {
            return emptyMap()
        }
        return value.entries.associate { (key, item) ->
            key.toString() to when (item) {
                is Map<*, *> -> normalizeMap(item)
                is List<*> -> item.map { listItem -> if (listItem is Map<*, *>) normalizeMap(listItem) else listItem }
                else -> item
            }
        }
    }

    private fun readDouble(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        }
    }
}
