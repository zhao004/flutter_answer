package com.example.flutter_answer.agent

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object AccessibilityAgent {

    private const val stableWindowMs = 350L
    private const val actionPostCheckTimeoutMs = 1200L
    private const val actionPostCheckPollMs = 80L
    private const val uiFingerprintNodeLimit = 220
    private const val uiFingerprintTextLimit = 48
    private const val minMatchScore = 25
    private const val actionCacheMaxSize = 80
    private const val gestureTapDurationMs = 40L
    private const val gestureTapResultWaitMs = 450L
    private const val minGestureTapSizePx = 48
    private const val maxGestureTapAreaRatio = 0.18
    private const val inputActivationWaitMs = 140L
    private const val inputBlurWaitMs = 100L
    private val blankHintPattern = Regex("^第\\s*\\d{1,3}\\s*空[:：]?$")
    private val inputPlaceholderKeywords = listOf("请输入答案", "请填写答案", "轻点两下来编辑", "未作答", "点击输入", "双击输入")
    private val locatorCache = LinkedHashMap<String, NodeLocator>()
    private val actionLocatorCache = LinkedHashMap<String, NodeLocator>()
    private val flowStateMachine = AutomationFlowStateMachine()

    @Volatile
    private var lastScreenContextSummary: ScreenContextSummary? = null

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
            "latestContextSignature" to currentContextTag(),
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
        val summary = buildScreenContextSummary(snapshot, regions)
        rememberScreenContext(summary, regions)
        transitionTo(AutomationFlowState.CONTEXT_ANALYZED, "页面结构分析完成。")

        val payload = success(
            message = "页面结构分析完成，识别到 ${regions.size} 个区域信号。",
            extra = mapOf(
                "context" to summary.toMap(),
                "summary" to summary.toMap(),
                "regions" to regions.map(UiRegion::toMap),
                "nodeCount" to snapshot.elements.size,
            ),
        )
        RecognitionTraceStore.record(
            stage = "page_context",
            contextTag = summary.contextSignature,
            confidence = summary.confidence,
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
        val summary = buildScreenContextSummary(snapshot, regions)
        rememberScreenContext(summary, regions)
        transitionTo(AutomationFlowState.CONTEXT_ANALYZED, "UI 快照采集与结构分析完成。")

        val payload = success(
            message = "已采集 ${snapshot.elements.size} 个界面节点，并生成结构摘要。",
            extra = mapOf(
                "snapshot" to snapshot.toMap(),
                "context" to summary.toMap(),
                "summary" to summary.toMap(),
                "regions" to regions.map(UiRegion::toMap),
            ),
        )
        RecognitionTraceStore.record(
            stage = "ui_snapshot",
            contextTag = summary.contextSignature,
            confidence = summary.confidence,
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
        transitionTo(AutomationFlowState.CONTEXT_ANALYZED, "页面采集完成，进入结构提取。")
        transitionTo(AutomationFlowState.QUESTION_PARSING, "题目结构化解析中。")

        val context = enrichContextWithCachedActions(QuestionExtractor.extract(snapshot))
        lastQuestionContext = context
        rememberScreenContext(
            summary = ScreenContextSummary(
                packageName = context.screenPackage,
                contextSignature = context.contextSignature,
                confidence = context.structureConfidence,
                signals = context.structureSignals,
                regionNames = context.regions.map(UiRegion::region).distinct().sorted(),
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
            message = "结构化上下文已提取，识别到 ${context.options.size} 个选项、${context.inputFields.size} 个输入框。",
            extra = mapOf("context" to context.toMap()),
        )
        RecognitionTraceStore.record(
            stage = "question_context",
            contextTag = context.contextSignature,
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

        transitionTo(AutomationFlowState.RESULT_VERIFYING, "点击动作已发送，开始回读校验。")
        val progress = observeUiProgress(beforeFingerprint)
        val changed = progress.changed

        val result = if (changed || riskLevel != "high") {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "点击动作回读通过。")
            success(
                message = "已点击节点：$elementId。",
                extra = mapOf(
                    "elementId" to elementId,
                    "riskLevel" to riskLevel,
                    "postCheckChanged" to changed,
                    "postCheckWaitedMs" to progress.waitedMs,
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
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
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
        val progress = observeUiProgress(beforeFingerprint)
        val refreshedText = findNodeById(elementId)?.let(::extractNodeText).orEmpty()
        val postCheckMatched = writeResult.matched || isInputTextMatched(refreshedText, text)
        val changed = progress.changed

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
                    "postCheckWaitedMs" to progress.waitedMs,
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
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
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
        val beforeFingerprint = captureUiFingerprint(ExamAccessibilityService.instance?.currentRoot())
        val result = if (currentChecked == targetChecked) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "节点状态已满足，无需切换。")
            success(
                message = "节点 $elementId 当前状态已符合预期，无需重复切换。",
                extra = mapOf(
                    "elementId" to elementId,
                    "checked" to currentChecked,
                    "postCheckChanged" to false,
                    "postCheckSatisfied" to true,
                ),
            )
        } else if (!performToggle(toggleNode)) {
            failure("切换节点 $elementId 失败。", "toggle_failed")
        } else {
            transitionTo(AutomationFlowState.RESULT_VERIFYING, "勾选切换已发送，开始回读校验。")
            val progress = observeUiProgress(beforeFingerprint)
            val refreshedChecked = findNodeById(elementId)?.let(::findToggleNode)?.isChecked
            if (refreshedChecked == null || refreshedChecked == targetChecked) {
                transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "勾选切换回读通过。")
                success(
                    message = "已切换节点：$elementId。",
                    extra = mapOf(
                        "elementId" to elementId,
                        "checked" to (refreshedChecked ?: targetChecked),
                        "postCheckChanged" to (progress.changed || currentChecked != (refreshedChecked
                            ?: targetChecked)),
                        "postCheckSatisfied" to true,
                        "postCheckWaitedMs" to progress.waitedMs,
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
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
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
        val progress = observeUiProgress(
            beforeFingerprint = beforeFingerprint,
            timeoutMs = actionPostCheckTimeoutMs + (attempts * 120L),
        )
        val changed = progress.changed

        val result = if (stepsPerformed > 0 && changed) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "滚动动作回读通过。")
            success(
                message = "已向 $normalizedDirection 方向滚动 $stepsPerformed 次。",
                extra = mapOf(
                    "direction" to normalizedDirection,
                    "amount" to normalizedAmount,
                    "stepsPerformed" to stepsPerformed,
                    "postCheckChanged" to true,
                    "postCheckWaitedMs" to progress.waitedMs,
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
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
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

        val beforeFingerprint = captureUiFingerprint(service.currentRoot())
        val result = if (service.performGlobalAction(globalAction)) {
            transitionTo(AutomationFlowState.RESULT_VERIFYING, "系统级动作已发送，开始回读校验。")
            val progress = observeUiProgress(beforeFingerprint)
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "系统级动作执行完成。")
            success(
                if (progress.changed) {
                    "已执行系统操作：$normalizedAction。"
                } else {
                    "已执行系统操作：$normalizedAction，暂未观测到界面变化。"
                },
                mapOf(
                    "action" to normalizedAction,
                    "postCheckChanged" to progress.changed,
                    "postCheckWaitedMs" to progress.waitedMs,
                ),
            )
        } else {
            failure("执行系统操作失败：$normalizedAction。", "global_action_failed")
        }

        val ok = result["ok"] as Boolean
        if (!ok) {
            transitionTo(AutomationFlowState.ERROR_RECOVERING, "系统级动作执行失败。")
        }
        RecognitionTraceStore.record(
            stage = "press_global",
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
            ok = ok,
            message = result["message"].toString(),
            payload = mapOf(
                "action" to normalizedAction,
                "postCheckChanged" to result["postCheckChanged"],
            ),
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
                    contextTag = currentContextTag(),
                    confidence = currentContextConfidence(),
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

    private fun buildScreenContextSummary(snapshot: UiSnapshot, regions: List<UiRegion>): ScreenContextSummary {
        // 这里只保留结构摘要与信号强度，不再输出任何页面分类标签。
        val regionNames = regions.map(UiRegion::region).toSet()
        val hasQuestionSignals = "question" in regionNames || "options" in regionNames || "input" in regionNames
        val hasPopup = "popup" in regionNames
        val hasFooterActions = "footer_actions" in regionNames
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
        val packageName = snapshot.screen["packageName"]?.toString()
        val signals = linkedMapOf<String, Double>(
            "regionCount" to regions.size.toDouble(),
            "questionSignal" to if (hasQuestionSignals) 1.0 else 0.0,
            "popupSignal" to if (hasPopup) 1.0 else 0.0,
            "footerSignal" to if (hasFooterActions) 1.0 else 0.0,
            "nodeCount" to snapshot.elements.size.toDouble(),
        )
        return ScreenContextSummary(
            packageName = packageName,
            contextSignature = QuestionExtractor.buildContextSignature(
                screenPackage = packageName,
                regions = regions,
                actions = emptyList(),
                submitOnlyPage = false,
            ),
            confidence = confidence.coerceIn(0.12, 0.96),
            signals = signals,
            regionNames = regionNames.toList().sorted(),
        )
    }

    private fun rememberScreenContext(summary: ScreenContextSummary, regions: List<UiRegion>) {
        lastScreenContextSummary = summary
        lastRegions = regions
    }

    private fun enrichContextWithCachedActions(context: QuestionContext): QuestionContext {
        val actions = context.actions.toMutableList()
        val existingKinds = actions.map { action -> action.kind }.toSet()
        val actionCacheScope = resolveActionCacheScope(
            contextSignature = context.contextSignature,
            packageName = context.screenPackage,
        )

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
        val actionCacheScope = resolveActionCacheScope(
            contextSignature = context.contextSignature,
            packageName = context.screenPackage,
        )
        context.actions.forEach { action ->
            val locator = locatorCache[action.id] ?: return@forEach
            val key = buildActionLocatorKey(actionCacheScope, action.kind)
            actionLocatorCache[key] = locator
        }
        while (actionLocatorCache.size > actionCacheMaxSize) {
            val oldestKey = actionLocatorCache.keys.firstOrNull() ?: break
            actionLocatorCache.remove(oldestKey)
        }
    }

    private fun rememberLocatorForAction(elementId: String) {
        val locator = locatorCache[elementId] ?: return
        val actionCacheScope = currentActionCacheScope()
        val inferredKind = inferActionKind(locator)
        if (inferredKind.isBlank()) {
            return
        }
        actionLocatorCache[buildActionLocatorKey(actionCacheScope, inferredKind)] = locator
    }

    private fun resolveActionCacheScope(contextSignature: String, packageName: String?): String {
        // 缓存键加入包名，避免不同应用/页面之间出现动作定位串扰。
        val safePackage = packageName?.trim().orEmpty().ifBlank { "unknown_package" }
        val safeSignature = contextSignature.trim().ifBlank { "generic_context" }
        return "$safePackage#$safeSignature"
    }

    private fun currentActionCacheScope(): String {
        val packageName = lastQuestionContext?.screenPackage
            ?: lastScreenContextSummary?.packageName
        return resolveActionCacheScope(currentContextTag(), packageName)
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
        if (node == null) {
            return false
        }
        if (supportsClickAction(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (performGestureTapBestEffort(node)) {
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

    private fun canClick(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isEnabled) {
            return false
        }
        if (supportsClickAction(node) || isGestureTapCandidate(node)) {
            return true
        }
        var current = node.parent
        while (current != null) {
            if (current.isEnabled && (current.isClickable || isGestureTapCandidate(current))) {
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun supportsClickAction(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun performGestureTapBestEffort(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 4) {
            if (performGestureTap(current)) {
                return true
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    private fun performGestureTap(node: AccessibilityNodeInfo): Boolean {
        val service = ExamAccessibilityService.instance ?: return false
        if (!isGestureTapCandidate(node)) {
            return false
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val rootRect = rootBounds() ?: rect
        val minX = (rootRect.left + 1).toFloat()
        val maxX = (rootRect.right - 1).coerceAtLeast(rootRect.left + 1).toFloat()
        val minY = (rootRect.top + 1).toFloat()
        val maxY = (rootRect.bottom - 1).coerceAtLeast(rootRect.top + 1).toFloat()
        val path = Path().apply {
            moveTo(rect.exactCenterX().coerceIn(minX, maxX), rect.exactCenterY().coerceIn(minY, maxY))
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
        finished.await(gestureTapResultWaitMs, TimeUnit.MILLISECONDS)
        return completed
    }

    private fun isGestureTapCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) {
            return false
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val width = rect.width().coerceAtLeast(0)
        val height = rect.height().coerceAtLeast(0)
        if (width < minGestureTapSizePx || height < minGestureTapSizePx) {
            return false
        }
        val screenArea = rootBounds()?.let { bounds ->
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

    private fun rootBounds(): Rect? {
        val root = ExamAccessibilityService.instance?.currentRoot() ?: return null
        return Rect().also(root::getBoundsInScreen)
    }

    private fun setTextValue(node: AccessibilityNodeInfo?, value: String): Boolean {
        var current = node
        while (current != null) {
            if (current.isEditable && current.isEnabled) {
                if (!ensureInputReady(current)) {
                    return false
                }
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

    private fun ensureInputReady(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.isEditable) {
            return false
        }
        if (node.isFocused) {
            return true
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Thread.sleep(40)
            if (node.isFocused) {
                return true
            }
        }
        // 学习通 WebView 富文本框经常只有真实点击后才会进入编辑态，直接 focus 不一定生效。
        if (performGestureTapBestEffort(node)) {
            Thread.sleep(inputActivationWaitMs)
        }
        return node.isFocused || node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
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
        val target = node ?: return cleared
        if (target.isFocused && tapOutsideNode(target)) {
            Thread.sleep(inputBlurWaitMs)
        }
        return cleared || !target.isFocused
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
        return InputTextNormalizer.normalize(value)
    }

    private fun extractNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return ""
        }
        val texts = LinkedHashSet<String>()
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 2) {
            val nodeText = normalizeInputText(current.text?.toString().orEmpty())
            if (nodeText.isNotBlank() && !looksLikeInputPlaceholder(nodeText)) {
                texts += nodeText
            }
            val nodeDesc = normalizeInputText(current.contentDescription?.toString().orEmpty())
            if (nodeDesc.isNotBlank() && !looksLikeInputPlaceholder(nodeDesc)) {
                texts += nodeDesc
            }
            current = current.parent
            depth += 1
        }
        return texts.joinToString(" ").trim()
    }

    private fun looksLikeInputPlaceholder(text: String): Boolean {
        if (text.isBlank()) {
            return true
        }
        if (blankHintPattern.matches(text)) {
            return true
        }
        return inputPlaceholderKeywords.any { keyword -> text.contains(keyword) }
    }

    private fun tapOutsideNode(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also(node::getBoundsInScreen)
        val rootRect = rootBounds() ?: return false
        val x = rect.centerX().coerceIn(rootRect.left + 8, rootRect.right - 8)
        val candidates = listOf(
            rect.top - 140,
            rect.bottom + 140,
            rootRect.top + 180,
            rootRect.bottom - 180,
        )
        val targetY = candidates.firstOrNull { y ->
            y in (rootRect.top + 8)..(rootRect.bottom - 8) && y !in rect.top..rect.bottom
        } ?: return false
        return performGestureTapAt(x.toFloat(), targetY.toFloat())
    }

    private fun performGestureTapAt(x: Float, y: Float): Boolean {
        val service = ExamAccessibilityService.instance ?: return false
        val rootRect = rootBounds() ?: return false
        val minX = (rootRect.left + 1).toFloat()
        val maxX = (rootRect.right - 1).coerceAtLeast(rootRect.left + 1).toFloat()
        val minY = (rootRect.top + 1).toFloat()
        val maxY = (rootRect.bottom - 1).coerceAtLeast(rootRect.top + 1).toFloat()
        val path = Path().apply {
            moveTo(x.coerceIn(minX, maxX), y.coerceIn(minY, maxY))
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
        finished.await(gestureTapResultWaitMs, TimeUnit.MILLISECONDS)
        return completed
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

    private fun observeUiProgress(
        beforeFingerprint: String,
        timeoutMs: Long = actionPostCheckTimeoutMs,
    ): UiProgressObservation {
        val service = ExamAccessibilityService.instance
            ?: return UiProgressObservation(false, 0L)
        val start = SystemClock.uptimeMillis()
        var latestFingerprint = beforeFingerprint

        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            Thread.sleep(actionPostCheckPollMs)
            latestFingerprint = captureUiFingerprint(service.currentRoot())
            if (latestFingerprint != beforeFingerprint) {
                return UiProgressObservation(
                    changed = true,
                    waitedMs = SystemClock.uptimeMillis() - start,
                )
            }

            val quietFor = SystemClock.uptimeMillis() - ExamAccessibilityService.lastEventAtMs
            if (quietFor >= stableWindowMs && SystemClock.uptimeMillis() - start >= actionPostCheckPollMs) {
                break
            }
        }

        return UiProgressObservation(
            changed = false,
            waitedMs = SystemClock.uptimeMillis() - start,
        )
    }

    private fun captureUiFingerprint(root: AccessibilityNodeInfo?): String {
        if (root == null) {
            return "empty"
        }

        val rows = mutableListOf<String>()
        var nodeCount = 0
        var semanticCount = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (nodeCount >= uiFingerprintNodeLimit) {
                return
            }
            nodeCount += 1
            val rect = Rect().also(node::getBoundsInScreen)
            val text = normalizeFingerprintText(node.text)
            val desc = normalizeFingerprintText(node.contentDescription)
            val viewId = node.viewIdResourceName.orEmpty()
            val hasSemanticSignal = text.isNotEmpty() ||
                desc.isNotEmpty() ||
                viewId.isNotEmpty() ||
                node.isClickable ||
                node.isCheckable ||
                node.isEditable ||
                node.isScrollable
            if (hasSemanticSignal) {
                semanticCount += 1
                rows += listOf(
                    depth.toString(),
                    node.className?.toString().orEmpty(),
                    viewId,
                    text,
                    desc,
                    node.isChecked.toString(),
                    node.isSelected.toString(),
                    node.isEditable.toString(),
                    node.isClickable.toString(),
                    node.isScrollable.toString(),
                    node.childCount.toString(),
                    (rect.left / 6).toString(),
                    (rect.top / 6).toString(),
                    (rect.right / 6).toString(),
                    (rect.bottom / 6).toString(),
                ).joinToString("#")
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    visit(child, depth + 1)
                }
            }
        }

        visit(root, 0)
        val joined = if (rows.isEmpty()) {
            root.className?.toString().orEmpty()
        } else {
            rows.joinToString("|")
        }
        val hash = joined.hashCode().toLong() and 0xffffffffL
        val packageName = root.packageName?.toString().orEmpty()
        return "$packageName:$hash:$nodeCount:$semanticCount"
    }

    private fun normalizeFingerprintText(value: CharSequence?): String {
        val text = value?.toString()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
        if (text.length <= uiFingerprintTextLimit) {
            return text
        }
        val headLength = uiFingerprintTextLimit / 2
        val tailLength = uiFingerprintTextLimit - headLength
        return "${text.take(headLength)}${text.takeLast(tailLength)}"
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

    private fun currentContextTag(): String {
        return lastQuestionContext?.contextSignature
            ?: lastScreenContextSummary?.contextSignature
            ?: "unknown_context"
    }

    private fun currentContextConfidence(): Double {
        return lastQuestionContext?.structureConfidence
            ?: lastScreenContextSummary?.confidence
            ?: 0.0
    }

    private data class UiProgressObservation(
        val changed: Boolean,
        val waitedMs: Long,
    )

    private data class ScreenContextSummary(
        val packageName: String?,
        val contextSignature: String,
        val confidence: Double,
        val signals: Map<String, Double>,
        val regionNames: List<String>,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "packageName" to packageName,
            "contextSignature" to contextSignature,
            "confidence" to confidence,
            "signals" to signals,
            "regionNames" to regionNames,
        )
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
            contextTag = currentContextTag(),
            confidence = currentContextConfidence(),
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
