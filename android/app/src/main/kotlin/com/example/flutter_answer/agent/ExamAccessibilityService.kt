package com.example.flutter_answer.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ExamAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ExamAccessibilityService? = null

        @Volatile
        var lastEventAtMs: Long = 0L
    }

    @Volatile
    private var currentTargetPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastEventAtMs = SystemClock.uptimeMillis()
        updateCurrentPackageName(resolveCurrentTargetPackageName())
        AgentEventBus.log("info", "无障碍服务已连接。")
        AgentEventBus.serviceStatus(AccessibilityAgent.peekServiceStatus())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAtMs = SystemClock.uptimeMillis()
        val eventType = event?.eventType ?: 0
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            updateCurrentPackageName(resolveCurrentTargetPackageName(event))
        }
    }

    override fun onInterrupt() {
        AgentEventBus.log("warn", "无障碍服务被系统中断。")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        currentTargetPackageName = null
        AgentEventBus.log("warn", "无障碍服务已销毁。")
        AgentEventBus.serviceStatus(AccessibilityAgent.peekServiceStatus())
        super.onDestroy()
    }

    fun currentRoot(): AccessibilityNodeInfo? {
        return findTargetRoot() ?: rootInActiveWindow?.takeUnless {
            it.packageName?.toString() == packageName
        }
    }

    fun currentPackageName(): String? {
        return currentRoot()?.packageName?.toString() ?: currentTargetPackageName
    }

    private fun updateCurrentPackageName(candidate: String?) {
        if (candidate.isNullOrBlank() || candidate == currentTargetPackageName) {
            return
        }
        currentTargetPackageName = candidate
        AgentEventBus.log("info", "当前包名已更新为：$candidate")
        println("当前包名已更新为：$candidate")
        AgentEventBus.serviceStatus(AccessibilityAgent.peekServiceStatus())
    }

    private fun resolveCurrentTargetPackageName(event: AccessibilityEvent? = null): String? {
        val eventPackage = event?.packageName?.toString()?.takeUnless {
            it.isBlank() || it == packageName
        }
        if (!eventPackage.isNullOrBlank()) {
            return eventPackage
        }
        return findTargetRoot()?.packageName?.toString()?.takeUnless {
            it.isBlank() || it == packageName
        } ?: currentTargetPackageName
    }

    private fun findTargetRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val ownPackage = packageName
            val windows = windows.orEmpty()
            val bestWindow = windows
                .asSequence()
                .filter { window -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { window ->
                    val root = window.root ?: return@mapNotNull null
                    val packageName = root.packageName?.toString()
                    if (packageName.isNullOrBlank() || packageName == ownPackage) {
                        return@mapNotNull null
                    }
                    WindowCandidate(window, root)
                }
                .sortedWith(
                    compareByDescending<WindowCandidate> { it.window.isFocused }
                        .thenByDescending { it.window.isActive }
                        .thenByDescending { it.window.layer }
                )
                .firstOrNull()

            if (bestWindow != null) {
                return bestWindow.root
            }
        }

        return rootInActiveWindow?.takeUnless {
            it.packageName?.toString() == packageName
        }
    }

    private data class WindowCandidate(
        val window: AccessibilityWindowInfo,
        val root: AccessibilityNodeInfo,
    )
}
