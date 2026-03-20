package com.example.flutter_answer.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

object AccessibilityAgent {

    private const val stableWindowMs = 350L
    private val locatorCache = LinkedHashMap<String, NodeLocator>()

    fun getServiceStatus(): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
        val payload = mapOf(
            "ok" to true,
            "enabled" to (service != null),
            "lastEventAtMs" to ExamAccessibilityService.lastEventAtMs,
            "hasActiveWindow" to (service?.currentRoot() != null),
        )
        AgentEventBus.toolResult("getServiceStatus", true, payload)
        return payload
    }

    fun openAccessibilitySettings(context: Context): Map<String, Any?> {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        val payload = success("Opened accessibility settings.")
        AgentEventBus.log("info", "Opened accessibility settings.")
        AgentEventBus.toolResult("openAccessibilitySettings", true, payload)
        return payload
    }

    fun getUiSnapshot(): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getUiSnapshot", "Accessibility service is not enabled.", "service_unavailable")
        val snapshot = dumpUiTree(service.currentRoot())
        val payload = success(
            message = "Captured ${snapshot.elements.size} UI elements.",
            extra = mapOf("snapshot" to snapshot.toMap()),
        )
        AgentEventBus.log("info", payload["message"] as String)
        AgentEventBus.toolResult("getUiSnapshot", true, payload)
        return payload
    }

    fun getQuestionContext(): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getQuestionContext", "Accessibility service is not enabled.", "service_unavailable")
        val snapshot = dumpUiTree(service.currentRoot())
        val context = QuestionExtractor.extract(snapshot)
        val payload = success(
            message = "Extracted ${context.options.size} options and ${context.actions.size} actions.",
            extra = mapOf("context" to context.toMap()),
        )
        AgentEventBus.log("info", payload["message"] as String)
        AgentEventBus.toolResult("getQuestionContext", true, payload)
        return payload
    }

    fun clickElement(elementId: String?): Map<String, Any?> {
        if (elementId.isNullOrBlank()) {
            return reportFailure("clickElement", "elementId is required.", "invalid_arguments")
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("clickElement", "Unable to resolve element $elementId.", "element_not_found")

        val result = if (click(node)) {
            success("Clicked $elementId.", mapOf("elementId" to elementId))
        } else {
            failure("Element $elementId is not clickable.", "click_failed")
        }
        val ok = result["ok"] as Boolean
        AgentEventBus.log(if (ok) "info" else "error", result["message"] as String)
        AgentEventBus.toolResult("clickElement", ok, result)
        return result
    }

    fun setText(elementId: String?, text: String?): Map<String, Any?> {
        if (elementId.isNullOrBlank() || text == null) {
            return reportFailure("setText", "elementId and text are required.", "invalid_arguments")
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("setText", "Unable to resolve element $elementId.", "element_not_found")

        val result = if (setTextValue(node, text)) {
            success("Filled $elementId.", mapOf("elementId" to elementId, "text" to text))
        } else {
            failure("Element $elementId does not accept text.", "set_text_failed")
        }
        val ok = result["ok"] as Boolean
        AgentEventBus.log(if (ok) "info" else "error", result["message"] as String)
        AgentEventBus.toolResult("setText", ok, result)
        return result
    }

    fun toggleElement(elementId: String?, targetChecked: Boolean?): Map<String, Any?> {
        if (elementId.isNullOrBlank() || targetChecked == null) {
            return reportFailure(
                "toggleElement",
                "elementId and targetChecked are required.",
                "invalid_arguments",
            )
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("toggleElement", "Unable to resolve element $elementId.", "element_not_found")
        val toggleNode = findToggleNode(node)
            ?: return reportFailure(
                "toggleElement",
                "Element $elementId is not checkable.",
                "toggle_failed",
            )

        val currentChecked = toggleNode.isChecked
        val result = if (currentChecked == targetChecked) {
            success(
                message = "Element $elementId already matches the requested checked state.",
                extra = mapOf(
                    "elementId" to elementId,
                    "checked" to currentChecked,
                ),
            )
        } else if (!performToggle(toggleNode)) {
            failure("Failed to toggle element $elementId.", "toggle_failed")
        } else {
            Thread.sleep(120)
            val refreshedChecked = findNodeById(elementId)?.let(::findToggleNode)?.isChecked
            if (refreshedChecked == null || refreshedChecked == targetChecked) {
                success(
                    message = "Toggled $elementId.",
                    extra = mapOf(
                        "elementId" to elementId,
                        "checked" to (refreshedChecked ?: targetChecked),
                    ),
                )
            } else {
                failure(
                    "Element $elementId did not reach the requested checked state.",
                    "toggle_failed",
                )
            }
        }

        val ok = result["ok"] as Boolean
        AgentEventBus.log(if (ok) "info" else "error", result["message"] as String)
        AgentEventBus.toolResult("toggleElement", ok, result)
        return result
    }

    fun scrollScreen(direction: String?, amount: String?): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("scrollScreen", "Accessibility service is not enabled.", "service_unavailable")
        val normalizedDirection = direction?.trim()?.lowercase()
        val normalizedAmount = amount?.trim()?.lowercase().orEmpty().ifBlank { "medium" }

        if (normalizedDirection !in setOf("up", "down", "left", "right")) {
            return reportFailure(
                "scrollScreen",
                "direction must be one of up, down, left, or right.",
                "invalid_arguments",
            )
        }
        if (normalizedAmount !in setOf("small", "medium", "large")) {
            return reportFailure(
                "scrollScreen",
                "amount must be one of small, medium, or large.",
                "invalid_arguments",
            )
        }

        val action = when (normalizedDirection) {
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val attempts = when (normalizedAmount) {
            "small" -> 1
            "large" -> 3
            else -> 2
        }

        var stepsPerformed = 0
        repeat(attempts) {
            val root = service.currentRoot() ?: return@repeat
            val scrollNode = findScrollableNode(root) ?: return@repeat
            if (scrollNode.performAction(action)) {
                stepsPerformed += 1
                Thread.sleep(120)
            }
        }

        val result = if (stepsPerformed > 0) {
            success(
                message = "Scrolled $normalizedDirection $stepsPerformed step(s).",
                extra = mapOf(
                    "direction" to normalizedDirection,
                    "amount" to normalizedAmount,
                    "stepsPerformed" to stepsPerformed,
                ),
            )
        } else {
            failure("No scrollable container responded to $normalizedDirection.", "scroll_failed")
        }

        val ok = result["ok"] as Boolean
        AgentEventBus.log(if (ok) "info" else "error", result["message"] as String)
        AgentEventBus.toolResult("scrollScreen", ok, result)
        return result
    }

    fun pressGlobal(action: String?): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("pressGlobal", "Accessibility service is not enabled.", "service_unavailable")
        val normalizedAction = action?.trim()?.lowercase()
            ?: return reportFailure("pressGlobal", "action is required.", "invalid_arguments")

        val globalAction = when (normalizedAction) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> {
                return reportFailure(
                    "pressGlobal",
                    "action must be one of back, home, recents, notifications, or quick_settings.",
                    "invalid_arguments",
                )
            }
        }

        val result = if (service.performGlobalAction(globalAction)) {
            success("Performed global action: $normalizedAction.", mapOf("action" to normalizedAction))
        } else {
            failure("Failed to perform global action: $normalizedAction.", "global_action_failed")
        }

        val ok = result["ok"] as Boolean
        AgentEventBus.log(if (ok) "info" else "error", result["message"] as String)
        AgentEventBus.toolResult("pressGlobal", ok, result)
        return result
    }

    fun waitForIdle(timeoutMs: Int): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("waitForIdle", "Accessibility service is not enabled.", "service_unavailable")

        val timeout = timeoutMs.coerceIn(200, 5000)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeout) {
            val quietFor = SystemClock.uptimeMillis() - ExamAccessibilityService.lastEventAtMs
            if (quietFor >= stableWindowMs && service.currentRoot() != null) {
                val waitedMs = SystemClock.uptimeMillis() - start
                val payload = success(
                    message = "UI settled after $waitedMs ms.",
                    extra = mapOf("waitedMs" to waitedMs),
                )
                AgentEventBus.log("info", payload["message"] as String)
                AgentEventBus.toolResult("waitForIdle", true, payload)
                return payload
            }
            Thread.sleep(80)
        }

        return reportFailure("waitForIdle", "Timed out after $timeout ms while waiting for idle.", "timeout")
    }

    private fun dumpUiTree(root: AccessibilityNodeInfo?): UiSnapshot {
        locatorCache.clear()
        if (root == null) {
            return UiSnapshot(
                screen = mapOf(
                    "packageName" to null,
                    "timestamp" to System.currentTimeMillis(),
                ),
                elements = emptyList(),
            )
        }

        val elements = mutableListOf<UiElement>()
        traverse(root, 0, elements, IdGenerator())
        return UiSnapshot(
            screen = mapOf(
                "packageName" to root.packageName?.toString(),
                "timestamp" to System.currentTimeMillis(),
            ),
            elements = elements,
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<UiElement>,
        idGenerator: IdGenerator,
    ) {
        val rect = Rect().also(node::getBoundsInScreen)
        val id = idGenerator.next()
        val element = UiElement(
            id = id,
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            editable = node.isEditable,
            enabled = node.isEnabled,
            scrollable = node.isScrollable,
            bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
            depth = depth,
        )
        locatorCache[id] = NodeLocator(
            text = element.text,
            desc = element.desc,
            className = element.className,
            viewId = element.viewId,
            bounds = element.bounds,
            clickable = element.clickable,
            editable = element.editable,
            checkable = element.checkable,
            scrollable = element.scrollable,
        )
        out += element

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                traverse(child, depth + 1, out, idGenerator)
            }
        }
    }

    private fun findNodeById(elementId: String): AccessibilityNodeInfo? {
        val locator = locatorCache[elementId] ?: return null
        return findBestMatch(ExamAccessibilityService.instance?.currentRoot(), locator)
    }

    private fun findBestMatch(root: AccessibilityNodeInfo?, locator: NodeLocator): AccessibilityNodeInfo? {
        if (root == null) {
            return null
        }

        var bestScore = Int.MIN_VALUE
        var bestNode: AccessibilityNodeInfo? = null

        fun visit(node: AccessibilityNodeInfo) {
            val score = matchScore(node, locator)
            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }

        visit(root)
        return bestNode?.takeIf { bestScore >= 25 }
    }

    private fun matchScore(node: AccessibilityNodeInfo, locator: NodeLocator): Int {
        var score = 0
        val nodeText = node.text?.toString().orEmpty().trim()
        val nodeDesc = node.contentDescription?.toString().orEmpty().trim()
        val nodeClass = node.className?.toString()
        val nodeViewId = node.viewIdResourceName

        if (!locator.text.isNullOrBlank()) {
            score += when {
                nodeText == locator.text -> 40
                nodeText.contains(locator.text, ignoreCase = true) -> 24
                else -> 0
            }
        }

        if (!locator.desc.isNullOrBlank()) {
            score += when {
                nodeDesc == locator.desc -> 30
                nodeDesc.contains(locator.desc, ignoreCase = true) -> 18
                else -> 0
            }
        }

        if (!locator.viewId.isNullOrBlank() && nodeViewId == locator.viewId) {
            score += 20
        }

        if (!locator.className.isNullOrBlank() && nodeClass == locator.className) {
            score += 15
        }

        if (node.isClickable == locator.clickable) {
            score += 8
        }
        if (node.isEditable == locator.editable) {
            score += 6
        }
        if (node.isCheckable == locator.checkable) {
            score += 6
        }
        if (node.isScrollable == locator.scrollable) {
            score += 4
        }

        val rect = Rect().also(node::getBoundsInScreen)
        val nodeBounds = listOf(rect.left, rect.top, rect.right, rect.bottom)
        val distance = locator.bounds.zip(nodeBounds).sumOf { (expected, actual) -> abs(expected - actual) }
        score += (20 - (distance / 60)).coerceAtLeast(0)
        return score
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun setTextValue(node: AccessibilityNodeInfo?, value: String): Boolean {
        var current = node
        while (current != null) {
            if (current.isEditable) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                }
                return current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            current = current.parent
        }
        return false
    }

    private fun findToggleNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isCheckable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun performToggle(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (node.isScrollable && node.isEnabled) {
                val rect = Rect().also(node::getBoundsInScreen)
                val area = rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
                val score = area - (depth * 5_000)
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    visit(child, depth + 1)
                }
            }
        }

        visit(root, 0)
        return bestNode
    }

    private fun success(
        message: String,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "ok" to true,
        "message" to message,
    ).apply {
        putAll(extra)
    }

    private fun failure(message: String, code: String): Map<String, Any?> = linkedMapOf(
        "ok" to false,
        "code" to code,
        "message" to message,
    )

    private fun reportFailure(tool: String, message: String, code: String): Map<String, Any?> {
        val payload = failure(message, code)
        AgentEventBus.log("warn", message)
        AgentEventBus.toolResult(tool, false, payload)
        return payload
    }

    private class IdGenerator {
        private var index = 0

        fun next(): String {
            index += 1
            return "n$index"
        }
    }
}
