package com.example.flutter_answer.agent

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ExamAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ExamAccessibilityService? = null

        @Volatile
        var lastEventAtMs: Long = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        lastEventAtMs = SystemClock.uptimeMillis()
        AgentEventBus.log("info", "Accessibility service connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAtMs = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {
        AgentEventBus.log("warn", "Accessibility service interrupted.")
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        AgentEventBus.log("warn", "Accessibility service destroyed.")
        super.onDestroy()
    }

    fun currentRoot(): AccessibilityNodeInfo? = rootInActiveWindow
}
