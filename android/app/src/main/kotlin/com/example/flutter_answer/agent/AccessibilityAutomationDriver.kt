package com.example.flutter_answer.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import cn.vove7.auto.core.AutoApi
import cn.vove7.auto.core.api.click as autoClick
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal object AccessibilityAutomationDriver {

    private const val autoApiTapTimeoutMs = 450L
    private const val gestureTapDurationMs = 40L
    private const val gestureTapResultWaitMs = 450L
    private const val minGestureTapSizePx = 48
    private const val maxGestureTapAreaRatio = 0.18

    fun clickNode(node: AccessibilityNodeInfo?, rootBoundsProvider: () -> Rect?): Boolean {
        if (node == null) {
            return false
        }
        if (supportsClickAction(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (tapNodeBestEffort(node, rootBoundsProvider)) {
            return true
        }
        var current = node.parent
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    fun canClickNode(node: AccessibilityNodeInfo?, rootBoundsProvider: () -> Rect?): Boolean {
        if (node == null || !node.isEnabled) {
            return false
        }
        val rootBounds = rootBoundsProvider()
        if (supportsClickAction(node) || isGestureTapCandidate(node, rootBounds)) {
            return true
        }
        var current = node.parent
        while (current != null) {
            if (current.isEnabled && (current.isClickable || isGestureTapCandidate(current, rootBounds))) {
                return true
            }
            current = current.parent
        }
        return false
    }

    fun tapNodeBestEffort(node: AccessibilityNodeInfo, rootBoundsProvider: () -> Rect?): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (tapNode(current, rootBoundsProvider)) {
                return true
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    fun tapPoint(x: Float, y: Float, rootBoundsProvider: () -> Rect?): Boolean {
        val rootRect = rootBoundsProvider() ?: return false
        val targetX = x.coerceIn(
            (rootRect.left + 1).toFloat(),
            (rootRect.right - 1).coerceAtLeast(rootRect.left + 1).toFloat(),
        )
        val targetY = y.coerceIn(
            (rootRect.top + 1).toFloat(),
            (rootRect.bottom - 1).coerceAtLeast(rootRect.top + 1).toFloat(),
        )
        // 优先走 Android-Auto-Api 的同步手势，失败时再回退到本地 dispatchGesture，兼顾库能力与兼容性。
        return tapPointWithAutoApi(targetX, targetY) || tapPointWithService(targetX, targetY)
    }

    fun performGlobalAction(action: Int, fallbackService: AccessibilityService): Boolean {
        val autoResult = runCatching {
            AutoApi.performAction(action)
        }.getOrDefault(false)
        return autoResult || fallbackService.performGlobalAction(action)
    }

    private fun tapNode(node: AccessibilityNodeInfo, rootBoundsProvider: () -> Rect?): Boolean {
        if (!isGestureTapCandidate(node, rootBoundsProvider())) {
            return false
        }
        val rect = Rect().also(node::getBoundsInScreen)
        return tapPoint(rect.exactCenterX(), rect.exactCenterY(), rootBoundsProvider)
    }

    private fun supportsClickAction(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun isGestureTapCandidate(node: AccessibilityNodeInfo, rootRect: Rect?): Boolean {
        if (!node.isEnabled) {
            return false
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val width = rect.width().coerceAtLeast(0)
        val height = rect.height().coerceAtLeast(0)
        if (width < minGestureTapSizePx || height < minGestureTapSizePx) {
            return false
        }
        val screenArea = rootRect?.let { bounds ->
            bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
        } ?: 0
        val nodeArea = width * height
        if (screenArea > 0 && nodeArea > (screenArea * maxGestureTapAreaRatio).toInt()) {
            return false
        }
        val hasTextSignal = node.text?.toString()?.trim().isNullOrBlank().not() ||
            node.contentDescription?.toString()?.trim().isNullOrBlank().not()
        if (hasTextSignal || node.isCheckable || node.isEditable) {
            return true
        }
        val className = node.className?.toString().orEmpty()
        return className.contains("Button", ignoreCase = true) ||
            className.contains("TextView", ignoreCase = true)
    }

    private fun tapPointWithAutoApi(x: Float, y: Float): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val targetX = x.roundToInt()
        val targetY = y.roundToInt()
        return runCatching {
            runBlocking {
                // 第三方同步手势依赖系统回调；缺失回调时必须超时退出，避免阻塞原生工具队列。
                withTimeoutOrNull(autoApiTapTimeoutMs) {
                    autoClick(targetX, targetY)
                } ?: false
            }
        }.getOrDefault(false)
    }

    private fun tapPointWithService(x: Float, y: Float): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        val service = ExamAccessibilityService.instance ?: return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, gestureTapDurationMs))
            .build()
        val finished = CountDownLatch(1)
        var completed = false
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    finished.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finished.countDown()
                }
            },
            null,
        )
        if (!dispatched) {
            return false
        }
        return runCatching {
            finished.await(gestureTapResultWaitMs, TimeUnit.MILLISECONDS)
            completed
        }.getOrDefault(false)
    }
}
