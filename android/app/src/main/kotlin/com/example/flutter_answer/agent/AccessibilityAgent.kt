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
    private const val minMatchScore = 25
    private const val actionCacheMaxSize = 80
    private val locatorCache = LinkedHashMap<String, NodeLocator>()
    private val actionLocatorCache = LinkedHashMap<String, NodeLocator>()
    private val flowStateMachine = AutomationFlowStateMachine()

    @Volatile
    private var lastPageClassification: PageClassification? = null

    @Volatile
    private var lastRegions: List<UiRegion> = emptyList()

    @Volatile
    private var lastQuestionContext: QuestionContext? = null

    private val highRiskKeywords = listOf("提交", "交卷", "确认", "发送", "完成")

    fun peekServiceStatus(): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
        val currentPackageName = service?.currentPackageName()
        return mapOf(
            "ok" to true,
            "enabled" to (service != null),
            "lastEventAtMs" to ExamAccessibilityService.lastEventAtMs,
            "hasActiveWindow" to (service?.currentRoot() != null),
            "currentPackageName" to currentPackageName,
            "flow" to flowStateMachine.snapshot(),
            "latestPageType" to (lastPageClassification?.pageType ?: "unknown_context"),
        )
    }

    fun getServiceStatus(): Map<String, Any?> {
        val payload = peekServiceStatus()
        AgentEventBus.log(
            "info",
            "服务状态已刷新，当前包名：${payload["currentPackageName"] ?: "未识别"}",
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
        val payload = success("已打开无障碍设置页面。")
        AgentEventBus.log("info", "已打开无障碍设置页面。")
        AgentEventBus.toolResult("openAccessibilitySettings", true, payload)
        return payload
    }

    fun getPageContext(): Map<String, Any?> {
        transitionTo(AutomationFlowState.PAGE_DETECTING, "开始页面结构分析。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getPageContext", "无障碍服务尚未开启。", "service_unavailable")
        val root = service.currentRoot()
            ?: return reportFailure("getPageContext", "未获取到可用节点，无法分析页面结构。", "window_unavailable")

        val snapshot = dumpUiTree(root)
        val regions = UiRegionLocator.locate(snapshot)
        val context = buildScreenContextSummary(snapshot, regions)
        rememberPageContext(context, regions)
        transitionTo(AutomationFlowState.PAGE_CLASSIFIED, "页面结构分析完成：${context.pageType}。")

        val payload = success(
            message = "页面结构分析完成：${context.pageType}，置信度 ${(context.confidence * 100).toInt()}%。",
            extra = mapOf(
                "context" to context.toMap(),
                "page" to context.toMap(),
                "regions" to regions.map(UiRegion::toMap),
                "nodeCount" to snapshot.elements.size,
            ),
        )
        RecognitionTraceStore.record(
            stage = "page_context",
            pageType = context.pageType,
            confidence = context.confidence,
            ok = true,
            message = payload["message"].toString(),
            payload = mapOf("regionCount" to regions.size),
        )
        AgentEventBus.log("info", payload["message"].toString())
        AgentEventBus.toolResult("getPageContext", true, payload)
        return payload
    }

    fun getUiSnapshot(): Map<String, Any?> {
        transitionTo(AutomationFlowState.PAGE_DETECTING, "开始采集 UI 快照。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getUiSnapshot", "无障碍服务尚未开启。", "service_unavailable")
        val root = service.currentRoot()
            ?: return reportFailure("getUiSnapshot", "未获取到可用节点，当前没有可分析的目标窗口。", "window_unavailable")

        val snapshot = dumpUiTree(root)
        val regions = UiRegionLocator.locate(snapshot)
        val context = buildScreenContextSummary(snapshot, regions)
        rememberPageContext(context, regions)
        transitionTo(AutomationFlowState.PAGE_CLASSIFIED, "UI 快照采集与结构分析完成。")

        val payload = success(
            message = "已采集 ${snapshot.elements.size} 个界面节点，上下文：${context.pageType}。",
            extra = mapOf(
                "snapshot" to snapshot.toMap(),
                "context" to context.toMap(),
                "page" to context.toMap(),
                "regions" to regions.map(UiRegion::toMap),
            ),
        )
        RecognitionTraceStore.record(
            stage = "ui_snapshot",
            pageType = context.pageType,
            confidence = context.confidence,
            ok = true,
            message = payload["message"].toString(),
            payload = mapOf("nodeCount" to snapshot.elements.size),
        )
        AgentEventBus.log("info", payload["message"].toString())
        AgentEventBus.toolResult("getUiSnapshot", true, payload)
        return payload
    }

    fun getQuestionContext(): Map<String, Any?> {
        transitionTo(AutomationFlowState.PAGE_DETECTING, "开始提取题目上下文。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getQuestionContext", "无障碍服务尚未开启。", "service_unavailable")
        val root = service.currentRoot()
            ?: return reportFailure("getQuestionContext", "未获取到可用节点，无法提取题目上下文。", "window_unavailable")

        val snapshot = dumpUiTree(root)
        transitionTo(AutomationFlowState.PAGE_CLASSIFIED, "页面采集完成，进入题目解析。")
        transitionTo(AutomationFlowState.QUESTION_PARSING, "题目结构化解析中。")

        val context = enrichContextWithCachedActions(QuestionExtractor.extract(snapshot))
        lastQuestionContext = context
        rememberPageContext(
            page = PageClassification(
                pageType = context.pageType,
                confidence = context.pageTypeConfidence,
                reason = context.pageTypeReason,
                scores = context.pageScores,
                signals = emptyMap(),
            ),
            regions = context.regions,
        )
        cacheActionLocators(context)

        if (context.needConfirm) {
            transitionTo(AutomationFlowState.WAITING_CONFIRM, "题目置信度较低，等待确认。")
        } else {
            transitionTo(AutomationFlowState.ANSWER_MATCHING, "题目解析完成，进入答案匹配阶段。")
        }

        val questionPreview = context.question.take(80).ifBlank { "（题干为空）" }
        val payload = success(
            message = "上下文：${context.pageType}，识别到 ${context.options.size} 个选项、${context.inputFields.size} 个输入框。",
            extra = mapOf("context" to context.toMap()),
        )
        RecognitionTraceStore.record(
            stage = "question_context",
            pageType = context.pageType,
            confidence = context.confidence,
            ok = true,
            message = payload["message"].toString(),
            payload = mapOf(
                "needConfirm" to context.needConfirm,
                "fingerprint" to context.fingerprint,
            ),
        )
        AgentEventBus.log("info", payload["message"].toString())
        AgentEventBus.log("info", "题干摘要：$questionPreview")
        AgentEventBus.toolResult("getQuestionContext", true, payload)
        return payload
    }

    fun getRecognitionHistory(limit: Int?): Map<String, Any?> {
        val safeLimit = (limit ?: 20).coerceIn(1, 100)
        val payload = success(
            message = "已返回最近 $safeLimit 条识别轨迹。",
            extra = mapOf(
                "traces" to RecognitionTraceStore.latest(safeLimit),
                "questionCache" to QuestionFingerprintCache.snapshot(safeLimit),
                "flow" to flowStateMachine.snapshot(),
            ),
        )
        AgentEventBus.toolResult("getRecognitionHistory", true, payload)
        return payload
    }

    fun clickElement(elementId: String?): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备执行点击动作。")
        if (elementId.isNullOrBlank()) {
            return reportFailure("clickElement", "缺少 elementId，无法执行点击。", "invalid_arguments")
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("clickElement", "未找到可点击节点：$elementId。", "element_not_found")
        if (!node.isEnabled) {
            return reportFailure("clickElement", "节点 $elementId 当前不可用。", "element_disabled")
        }
        if (!canClick(node)) {
            return reportFailure("clickElement", "节点 $elementId 不可点击。", "click_failed")
        }

        val service = ExamAccessibilityService.instance
        val beforeFingerprint = captureUiFingerprint(service?.currentRoot())
        val riskLevel = resolveActionRisk(node)
        if (!click(node)) {
            return reportFailure("clickElement", "节点 $elementId 执行点击失败。", "click_failed")
        }

        Thread.sleep(160)
        transitionTo(AutomationFlowState.RESULT_VERIFYING, "点击动作已发送，开始回读校验。")
        val afterFingerprint = captureUiFingerprint(service?.currentRoot())
        val changed = beforeFingerprint != afterFingerprint

        val result = if (changed || riskLevel != "high") {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "点击动作回读通过。")
            success(
                message = "已点击节点：$elementId。",
                extra = mapOf(
                    "elementId" to elementId,
                    "riskLevel" to riskLevel,
                    "postCheckChanged" to changed,
                ),
            )
        } else {
            failure("节点 $elementId 点击后界面未变化，已拦截高风险动作。", "click_not_effective")
        }

        val ok = result["ok"] as Boolean
        if (ok) {
            rememberLocatorForAction(elementId)
        }
        RecognitionTraceStore.record(
            stage = "click_element",
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = ok,
            message = result["message"].toString(),
            payload = mapOf("riskLevel" to riskLevel, "postCheckChanged" to changed),
        )
        if (!ok) {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "点击动作回读失败。")
        }
        AgentEventBus.log(if (ok) "info" else "error", result["message"].toString())
        AgentEventBus.toolResult("clickElement", ok, result)
        return result
    }

    fun setText(elementId: String?, text: String?): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备执行输入动作。")
        if (elementId.isNullOrBlank() || text == null) {
            return reportFailure("setText", "缺少 elementId 或 text，无法输入内容。", "invalid_arguments")
        }
        if (text.length > 2000) {
            return reportFailure("setText", "输入文本过长，已拒绝执行。", "invalid_arguments")
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("setText", "未找到输入节点：$elementId。", "element_not_found")
        if (!node.isEnabled) {
            return reportFailure("setText", "节点 $elementId 当前不可编辑。", "element_disabled")
        }

        val service = ExamAccessibilityService.instance
        val beforeFingerprint = captureUiFingerprint(service?.currentRoot())
        // 输入动作要求“可读回”才能判定成功，避免仅靠界面变化导致误判。
        val writeResult = writeAndVerifyInput(elementId = elementId, text = text, maxAttempts = 2)
        if (!writeResult.writeSent) {
            return reportFailure("setText", "节点 $elementId 不支持输入文本。", "set_text_failed")
        }

        transitionTo(AutomationFlowState.RESULT_VERIFYING, "输入动作已发送，开始回读校验。")
        val refreshedText = findNodeById(elementId)?.let(::extractNodeText).orEmpty()
        val postCheckMatched = writeResult.matched || isInputTextMatched(refreshedText, text)
        val afterFingerprint = captureUiFingerprint(service?.currentRoot())
        val changed = beforeFingerprint != afterFingerprint

        val result = if (postCheckMatched) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "输入动作回读通过。")
            success(
                message = "已向节点 $elementId 输入内容。",
                extra = mapOf(
                    "elementId" to elementId,
                    "text" to text,
                    "attempts" to writeResult.attempts,
                    "lastReadText" to writeResult.lastReadText,
                    "postCheckMatched" to postCheckMatched,
                    "postCheckChanged" to changed,
                ),
            )
        } else {
            failure("节点 $elementId 输入后未读取到目标文本。", "set_text_failed")
        }

        val ok = result["ok"] as Boolean
        if (ok) {
            rememberLocatorForAction(elementId)
        }
        RecognitionTraceStore.record(
            stage = "set_text",
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = ok,
            message = result["message"].toString(),
            payload = mapOf("postCheckMatched" to postCheckMatched, "postCheckChanged" to changed),
        )
        if (!ok) {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "输入动作回读失败。")
        }
        AgentEventBus.log(if (ok) "info" else "error", result["message"].toString())
        AgentEventBus.toolResult("setText", ok, result)
        return result
    }

    fun toggleElement(elementId: String?, targetChecked: Boolean?): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备执行勾选切换。")
        if (elementId.isNullOrBlank() || targetChecked == null) {
            return reportFailure(
                "toggleElement",
                "缺少 elementId 或 targetChecked，无法切换选中状态。",
                "invalid_arguments",
            )
        }

        val node = findNodeById(elementId)
            ?: return reportFailure("toggleElement", "未找到可切换节点：$elementId。", "element_not_found")
        val toggleNode = findToggleNode(node)
            ?: return reportFailure(
                "toggleElement",
                "节点 $elementId 不支持勾选状态切换。",
                "toggle_failed",
            )

        val currentChecked = toggleNode.isChecked
        val result = if (currentChecked == targetChecked) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "节点状态已满足，无需切换。")
            success(
                message = "节点 $elementId 当前状态已符合预期，无需重复切换。",
                extra = mapOf(
                    "elementId" to elementId,
                    "checked" to currentChecked,
                ),
            )
        } else if (!performToggle(toggleNode)) {
            failure("切换节点 $elementId 失败。", "toggle_failed")
        } else {
            Thread.sleep(120)
            transitionTo(AutomationFlowState.RESULT_VERIFYING, "勾选切换已发送，开始回读校验。")
            val refreshedChecked = findNodeById(elementId)?.let(::findToggleNode)?.isChecked
            if (refreshedChecked == null || refreshedChecked == targetChecked) {
                transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "勾选切换回读通过。")
                success(
                    message = "已切换节点：$elementId。",
                    extra = mapOf(
                        "elementId" to elementId,
                        "checked" to (refreshedChecked ?: targetChecked),
                    ),
                )
            } else {
                failure(
                    "节点 $elementId 未达到目标选中状态。",
                    "toggle_failed",
                )
            }
        }

        val ok = result["ok"] as Boolean
        if (ok) {
            rememberLocatorForAction(elementId)
        } else {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "勾选切换回读失败。")
        }
        RecognitionTraceStore.record(
            stage = "toggle_element",
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = ok,
            message = result["message"].toString(),
        )
        AgentEventBus.log(if (ok) "info" else "error", result["message"].toString())
        AgentEventBus.toolResult("toggleElement", ok, result)
        return result
    }

    fun scrollScreen(direction: String?, amount: String?): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备执行滚动动作。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("scrollScreen", "无障碍服务尚未开启。", "service_unavailable")
        val normalizedDirection = direction?.trim()?.lowercase()
        val normalizedAmount = amount?.trim()?.lowercase().orEmpty().ifBlank { "medium" }

        if (normalizedDirection !in setOf("up", "down", "left", "right")) {
            return reportFailure(
                "scrollScreen",
                "滚动方向必须是 up、down、left 或 right。",
                "invalid_arguments",
            )
        }
        if (normalizedAmount !in setOf("small", "medium", "large")) {
            return reportFailure(
                "scrollScreen",
                "滚动幅度必须是 small、medium 或 large。",
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

        val beforeFingerprint = captureUiFingerprint(service.currentRoot())
        var stepsPerformed = 0
        repeat(attempts) {
            val root = service.currentRoot() ?: return@repeat
            val scrollNode = findScrollableNode(root) ?: return@repeat
            if (scrollNode.performAction(action)) {
                stepsPerformed += 1
                Thread.sleep(120)
            }
        }

        transitionTo(AutomationFlowState.RESULT_VERIFYING, "滚动动作已发送，开始回读校验。")
        val afterFingerprint = captureUiFingerprint(service.currentRoot())
        val changed = beforeFingerprint != afterFingerprint

        val result = if (stepsPerformed > 0 && changed) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "滚动动作回读通过。")
            success(
                message = "已向 $normalizedDirection 方向滚动 $stepsPerformed 次。",
                extra = mapOf(
                    "direction" to normalizedDirection,
                    "amount" to normalizedAmount,
                    "stepsPerformed" to stepsPerformed,
                    "postCheckChanged" to true,
                ),
            )
        } else if (stepsPerformed > 0) {
            failure("滚动动作已发送，但界面未检测到变化。", "scroll_no_effect")
        } else {
            failure("当前界面没有可响应 $normalizedDirection 滚动的容器。", "scroll_failed")
        }

        val ok = result["ok"] as Boolean
        if (!ok) {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "滚动动作回读失败。")
        }
        RecognitionTraceStore.record(
            stage = "scroll_screen",
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = ok,
            message = result["message"].toString(),
            payload = mapOf("stepsPerformed" to stepsPerformed, "postCheckChanged" to changed),
        )
        AgentEventBus.log(if (ok) "info" else "error", result["message"].toString())
        AgentEventBus.toolResult("scrollScreen", ok, result)
        return result
    }

    fun pressGlobal(action: String?): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备执行系统级动作。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("pressGlobal", "无障碍服务尚未开启。", "service_unavailable")
        val normalizedAction = action?.trim()?.lowercase()
            ?: return reportFailure("pressGlobal", "缺少 action，无法执行系统级操作。", "invalid_arguments")

        val globalAction = when (normalizedAction) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> {
                return reportFailure(
                    "pressGlobal",
                    "action 必须是 back、home、recents、notifications 或 quick_settings。",
                    "invalid_arguments",
                )
            }
        }

        val result = if (service.performGlobalAction(globalAction)) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "系统级动作执行完成。")
            success("已执行系统操作：$normalizedAction。", mapOf("action" to normalizedAction))
        } else {
            failure("执行系统操作失败：$normalizedAction。", "global_action_failed")
        }

        val ok = result["ok"] as Boolean
        if (!ok) {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "系统级动作执行失败。")
        }
        RecognitionTraceStore.record(
            stage = "press_global",
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = ok,
            message = result["message"].toString(),
            payload = mapOf("action" to normalizedAction),
        )
        AgentEventBus.log(if (ok) "info" else "error", result["message"].toString())
        AgentEventBus.toolResult("pressGlobal", ok, result)
        return result
    }

    fun waitForIdle(timeoutMs: Int): Map<String, Any?> {
        transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "等待页面稳定。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("waitForIdle", "无障碍服务尚未开启。", "service_unavailable")

        val timeout = timeoutMs.coerceIn(200, 5000)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeout) {
            val quietFor = SystemClock.uptimeMillis() - ExamAccessibilityService.lastEventAtMs
            if (quietFor >= stableWindowMs && service.currentRoot() != null) {
                val waitedMs = SystemClock.uptimeMillis() - start
                val payload = success(
                    message = "界面已稳定，等待耗时 ${waitedMs}ms。",
                    extra = mapOf("waitedMs" to waitedMs),
                )
                RecognitionTraceStore.record(
                    stage = "wait_for_idle",
                    pageType = currentPageType(),
                    confidence = currentPageConfidence(),
                    ok = true,
                    message = payload["message"].toString(),
                )
                AgentEventBus.log("info", payload["message"].toString())
                AgentEventBus.toolResult("waitForIdle", true, payload)
                return payload
            }
            Thread.sleep(80)
        }

        return reportFailure("waitForIdle", "等待界面稳定超时，已达到 ${timeout}ms。", "timeout")
    }

    private fun buildScreenContextSummary(snapshot: UiSnapshot, regions: List<UiRegion>): PageClassification {
        // 这里输出的是“结构化上下文摘要”而不是页面分类结果，避免错误分类导致后续策略偏航。
        val regionNames = regions.map(UiRegion::region).toSet()
        val hasQuestionSignals = "question" in regionNames || "options" in regionNames || "input" in regionNames
        val hasPopup = "popup" in regionNames
        val hasFooterActions = "footer_actions" in regionNames
        val pageType = when {
            hasPopup && hasFooterActions -> "submit_action_context"
            hasPopup -> "dialog_context"
            hasQuestionSignals -> "question_context"
            else -> "generic_context"
        }
        val reason = when (pageType) {
            "submit_action_context" -> "检测到弹窗区域与底部动作区，按提交/确认上下文处理。"
            "dialog_context" -> "检测到中心弹窗区域，按弹窗上下文处理。"
            "question_context" -> "检测到题干/选项/输入框区域，按题目上下文处理。"
            else -> "未检测到稳定题目结构，按通用上下文处理。"
        }
        var confidence = 0.26
        if (hasQuestionSignals) {
            confidence += 0.32
        }
        if ("options" in regionNames) {
            confidence += 0.18
        }
        if ("input" in regionNames) {
            confidence += 0.12
        }
        if (hasPopup) {
            confidence += 0.1
        }
        if (hasFooterActions) {
            confidence += 0.08
        }
        val signals = linkedMapOf<String, Any?>(
            "regionNames" to regionNames.toList(),
            "nodeCount" to snapshot.elements.size,
            "packageName" to snapshot.screen["packageName"]?.toString(),
        )
        val scores = linkedMapOf(
            "regionCount" to regions.size.toDouble(),
            "questionSignal" to if (hasQuestionSignals) 1.0 else 0.0,
            "popupSignal" to if (hasPopup) 1.0 else 0.0,
            "footerSignal" to if (hasFooterActions) 1.0 else 0.0,
        )
        return PageClassification(
            pageType = pageType,
            confidence = confidence.coerceIn(0.12, 0.96),
            reason = reason,
            scores = scores,
            signals = signals,
        )
    }

    private fun rememberPageContext(page: PageClassification, regions: List<UiRegion>) {
        lastPageClassification = page
        lastRegions = regions
    }

    private fun enrichContextWithCachedActions(context: QuestionContext): QuestionContext {
        val actions = context.actions.toMutableList()
        val existingKinds = actions.map { action -> action.kind }.toSet()
        val actionCacheScope = resolveActionCacheScope(context.pageType, context.screenPackage)

        if ("next" !in existingKinds && hasCachedAction(kind = "next", contextScope = actionCacheScope)) {
            actions += QuestionAction(
                id = "action:next",
                text = "下一题（缓存定位）",
                kind = "next",
                riskLevel = "medium",
            )
        }

        if ("submit" !in existingKinds && hasCachedAction(kind = "submit", contextScope = actionCacheScope)) {
            actions += QuestionAction(
                id = "action:submit",
                text = "提交（缓存定位）",
                kind = "submit",
                riskLevel = "high",
            )
        }

        if (actions.size == context.actions.size) {
            return context
        }
        return context.copy(actions = actions)
    }

    private fun hasCachedAction(kind: String, contextScope: String): Boolean {
        val exactKey = buildActionLocatorKey(contextScope, kind)
        if (actionLocatorCache.containsKey(exactKey)) {
            return true
        }
        return actionLocatorCache.keys.any { key -> key.endsWith(":$kind") }
    }

    private fun cacheActionLocators(context: QuestionContext) {
        val actionCacheScope = resolveActionCacheScope(context.pageType, context.screenPackage)
        context.actions.forEach { action ->
            val locator = locatorCache[action.id] ?: return@forEach
            val key = buildActionLocatorKey(actionCacheScope, action.kind)
            actionLocatorCache[key] = locator.copy(pageType = context.pageType)
        }
        while (actionLocatorCache.size > actionCacheMaxSize) {
            val oldestKey = actionLocatorCache.keys.firstOrNull() ?: break
            actionLocatorCache.remove(oldestKey)
        }
    }

    private fun rememberLocatorForAction(elementId: String) {
        val locator = locatorCache[elementId] ?: return
        val pageType = currentPageType()
        val actionCacheScope = currentActionCacheScope()
        val inferredKind = inferActionKind(locator)
        if (inferredKind.isBlank()) {
            return
        }
        actionLocatorCache[buildActionLocatorKey(actionCacheScope, inferredKind)] = locator.copy(pageType = pageType)
    }

    private fun resolveActionCacheScope(pageType: String, packageName: String?): String {
        // 缓存键加入包名，避免不同应用/页面之间出现动作定位串扰。
        val safePackage = packageName?.trim().orEmpty().ifBlank { "unknown_package" }
        return "$safePackage#$pageType"
    }

    private fun currentActionCacheScope(): String {
        val packageName = lastQuestionContext?.screenPackage
            ?: lastPageClassification?.signals?.get("packageName")?.toString()
        return resolveActionCacheScope(currentPageType(), packageName)
    }

    private fun buildActionLocatorKey(contextScope: String, kind: String): String {
        return "$contextScope:${kind.lowercase()}"
    }

    private fun inferActionKind(locator: NodeLocator): String {
        val text = (locator.text ?: locator.desc).orEmpty()
        return when {
            text.contains("提交") || text.contains("交卷") -> "submit"
            text.contains("下一") || text.contains("继续") -> "next"
            text.contains("确认") || text.contains("确定") -> "confirm"
            text.contains("取消") || text.contains("返回") -> "cancel"
            else -> ""
        }
    }

    private fun dumpUiTree(root: AccessibilityNodeInfo?, refreshLocatorCache: Boolean = true): UiSnapshot {
        if (refreshLocatorCache) {
            locatorCache.clear()
        }
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
        traverse(root, 0, elements, IdGenerator(), refreshLocatorCache)
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
        shouldCacheLocator: Boolean,
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

        if (shouldCacheLocator) {
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
                parentClassName = node.parent?.className?.toString(),
                siblingTextHints = collectSiblingTextHints(node),
            )
        }

        out += element

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                traverse(child, depth + 1, out, idGenerator, shouldCacheLocator)
            }
        }
    }

    private fun collectSiblingTextHints(node: AccessibilityNodeInfo): List<String> {
        val parent = node.parent ?: return emptyList()
        val hints = mutableListOf<String>()
        for (index in 0 until parent.childCount) {
            val child = parent.getChild(index) ?: continue
            if (child == node) {
                continue
            }
            val text = child.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                hints += text
            }
            if (hints.size >= 3) {
                break
            }
        }
        return hints
    }

    private fun findNodeById(elementId: String): AccessibilityNodeInfo? {
        val root = ExamAccessibilityService.instance?.currentRoot() ?: return null

        locatorCache[elementId]?.let { locator ->
            return findBestMatch(root, locator)
        }

        val semanticLocator = findSemanticLocator(elementId)
        if (semanticLocator != null) {
            return findBestMatch(root, semanticLocator)
        }

        return null
    }

    private fun findSemanticLocator(elementId: String): NodeLocator? {
        if (!elementId.startsWith("action:")) {
            return null
        }
        val kind = elementId.removePrefix("action:").trim().lowercase()
        if (kind.isBlank()) {
            return null
        }
        val actionCacheScope = currentActionCacheScope()
        val exact = actionLocatorCache[buildActionLocatorKey(actionCacheScope, kind)]
        if (exact != null) {
            return exact
        }
        return actionLocatorCache.entries
            .lastOrNull { (key, _) -> key.endsWith(":$kind") }
            ?.value
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
        return bestNode?.takeIf { bestScore >= minMatchScore }
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

        if (!locator.parentClassName.isNullOrBlank() && locator.parentClassName == node.parent?.className?.toString()) {
            score += 10
        }

        if (locator.siblingTextHints.isNotEmpty()) {
            val parent = node.parent
            val siblingTexts = mutableSetOf<String>()
            if (parent != null) {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChild(index) ?: continue
                    val text = child.text?.toString()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        siblingTexts += text
                    }
                }
            }
            val siblingHitCount = locator.siblingTextHints.count { hint -> siblingTexts.contains(hint) }
            score += siblingHitCount * 3
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

    private fun canClick(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun setTextValue(node: AccessibilityNodeInfo?, value: String): Boolean {
        var current = node
        while (current != null) {
            if (current.isEditable && current.isEnabled) {
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

    private fun writeAndVerifyInput(
        elementId: String,
        text: String,
        maxAttempts: Int,
    ): InputWriteResult {
        var attempts = 0
        var writeSent = false
        var matched = false
        var lastReadText = ""

        while (attempts < maxAttempts) {
            attempts += 1
            val targetNode = findNodeById(elementId) ?: break
            if (!setTextValue(targetNode, text)) {
                continue
            }
            writeSent = true
            Thread.sleep(140)
            val refreshedNode = findNodeById(elementId)
            lastReadText = extractNodeText(refreshedNode)
            if (!isInputTextMatched(lastReadText, text)) {
                continue
            }
            // 输入后主动清焦点，触发部分题库页面的“失焦保存”逻辑，减少下一题后答案丢失。
            clearInputFocus(refreshedNode)
            Thread.sleep(80)
            lastReadText = extractNodeText(findNodeById(elementId))
            matched = isInputTextMatched(lastReadText, text)
            if (matched) {
                break
            }
        }

        return InputWriteResult(
            writeSent = writeSent,
            matched = matched,
            attempts = attempts,
            lastReadText = lastReadText,
        )
    }

    private fun clearInputFocus(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var cleared = false
        while (current != null) {
            if (current.isFocused) {
                cleared = current.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) || cleared
            }
            current = current.parent
        }
        return cleared
    }

    private fun isInputTextMatched(actual: String, expected: String): Boolean {
        val normalizedActual = normalizeInputText(actual)
        val normalizedExpected = normalizeInputText(expected)
        if (normalizedExpected.isBlank()) {
            return normalizedActual.isBlank()
        }
        return normalizedActual == normalizedExpected || normalizedActual.contains(normalizedExpected)
    }

    private fun normalizeInputText(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }

    private fun extractNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return ""
        }
        return node.text?.toString()?.trim().orEmpty()
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

    private fun captureUiFingerprint(root: AccessibilityNodeInfo?): String {
        if (root == null) {
            return "empty"
        }

        val rows = mutableListOf<String>()
        var nodeCount = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (nodeCount >= 120) {
                return
            }
            nodeCount += 1
            val rect = Rect().also(node::getBoundsInScreen)
            val text = node.text?.toString()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty().take(20)
            val desc = node.contentDescription?.toString()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty().take(20)
            rows += listOf(
                depth.toString(),
                node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                text,
                desc,
                node.isChecked.toString(),
                node.isClickable.toString(),
                rect.left.toString(),
                rect.top.toString(),
                rect.right.toString(),
                rect.bottom.toString(),
            ).joinToString("#")

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    visit(child, depth + 1)
                }
            }
        }

        visit(root, 0)
        val joined = rows.joinToString("|")
        val hash = joined.hashCode().toLong() and 0xffffffffL
        val packageName = root.packageName?.toString().orEmpty()
        return "$packageName:$hash:$nodeCount"
    }

    private fun resolveActionRisk(node: AccessibilityNodeInfo): String {
        val text = buildString {
            append(node.text?.toString().orEmpty())
            append(" ")
            append(node.contentDescription?.toString().orEmpty())
        }
        return if (highRiskKeywords.any { keyword -> text.contains(keyword) }) {
            "high"
        } else if (text.contains("下一") || text.contains("继续")) {
            "medium"
        } else {
            "low"
        }
    }

    private fun transitionTo(target: AutomationFlowState, reason: String) {
        val transition = flowStateMachine.transitionTo(target, reason)
        if (!transition.valid) {
            AgentEventBus.log(
                "warn",
                "状态机非常规跳转：${transition.from} -> ${transition.to}，原因：${transition.reason}",
            )
        }
    }

    private fun currentPageType(): String {
        return lastQuestionContext?.pageType ?: lastPageClassification?.pageType ?: "unknown_context"
    }

    private fun currentPageConfidence(): Double {
        return lastQuestionContext?.pageTypeConfidence ?: lastPageClassification?.confidence ?: 0.0
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
        transitionTo(AutomationFlowState.ERROR_RECOVERING, "$tool 失败：$message")
        val payload = failure(message, code)
        RecognitionTraceStore.record(
            stage = tool,
            pageType = currentPageType(),
            confidence = currentPageConfidence(),
            ok = false,
            message = message,
            payload = mapOf("code" to code),
        )
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

    private data class InputWriteResult(
        val writeSent: Boolean,
        val matched: Boolean,
        val attempts: Int,
        val lastReadText: String,
    )
}
