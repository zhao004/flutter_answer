package com.example.flutter_answer.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object AccessibilityAgent {

    private const val stableWindowMs = 180L
    private const val actionPostCheckTimeoutMs = 800L
    private const val actionPostCheckPollMs = 50L
    private const val toggleVerifyTimeoutMs = 600L
    private const val toggleVerifyPollMs = 40L
    private const val inputVerifyTimeoutMs = 420L
    private const val inputVerifyPollMs = 40L
    private const val uiFingerprintNodeLimit = 220
    private const val uiFingerprintTextLimit = 48
    private const val minMatchScore = 25
    private const val actionCacheMaxSize = 80
    private const val minGestureTapSizePx = 48
    private const val inputActivationWaitMs = 100L
    private const val inputBlurWaitMs = 80L
    private const val questionImageCaptureTimeoutMs = 2000L
    private const val questionImageMaxDimension = 1280
    private const val questionImageJpegQuality = 82
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
    private val progressActionKeywords = listOf("下一", "继续", "继续答题", "继续作答", "开始答题", "去答题", "进入答题")

    fun peekServiceStatus(): Map<String, Any?> {
        val service = ExamAccessibilityService.instance
        return mapOf(
            "ok" to true,
            "enabled" to (service != null),
            "lastEventAtMs" to ExamAccessibilityService.lastEventAtMs,
            "hasActiveWindow" to (service?.currentRoot() != null),
            "flow" to flowStateMachine.snapshot(),
            "latestContextSignature" to currentContextTag(),
        )
    }

    fun getServiceStatus(): Map<String, Any?> {
        val payload = peekServiceStatus()
        AgentEventBus.log("info", "服务状态已刷新。")
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
        AgentEventBus.toolResult("getUiSnapshot", true, payload)
        return payload
    }

    fun getQuestionContext(): Map<String, Any?> {
        transitionTo(AutomationFlowState.PAGE_DETECTING, "开始提取题目上下文。")
        val service = ExamAccessibilityService.instance
            ?: return reportFailure("getQuestionContext", "无障碍服务尚未开启。", "service_unavailable")
        if (service.currentRoot() == null) {
            return reportFailure("getQuestionContext", "未获取到可用节点，无法提取题目上下文。", "window_unavailable")
        }

        val startedAt = System.nanoTime()
        val context = loadCurrentQuestionContext()
            ?: return reportFailure("getQuestionContext", "当前窗口不可用，无法提取题目上下文。", "window_unavailable")
        val totalMs = elapsedMs(startedAt)

        when {
            context.needConfirm -> {
                transitionTo(AutomationFlowState.WAITING_CONFIRM, "题目置信度较低，等待确认。")
            }
            context.pageStage == QuestionPageStage.resumeEntry -> {
                transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "识别到继续答题入口，等待推进动作。")
            }
            context.pageStage in setOf(
                QuestionPageStage.submitReady,
                QuestionPageStage.submitDialog,
                QuestionPageStage.submitWarning,
            ) -> {
                transitionTo(AutomationFlowState.WAITING_CONFIRM, "识别到提交相关页面，等待停止守卫结束流程。")
            }
            else -> {
                transitionTo(AutomationFlowState.ANSWER_MATCHING, "题目解析完成，进入答案匹配阶段。")
            }
        }

        val payload = success(
            message = "结构化上下文已提取，识别到 ${context.options.size} 个选项、${context.inputFields.size} 个输入框。",
            extra = mapOf(
                "context" to context.toMap(),
                "timings" to (context.timings + ("totalMs" to totalMs)),
            ),
        )
        RecognitionTraceStore.record(
            stage = "question_context",
            contextTag = context.contextSignature,
            confidence = context.confidence,
            ok = true,
            message = payload["message"].toString(),
            payload = mapOf(
                "needConfirm" to context.needConfirm,
                "needsVisual" to context.needsVisual,
                "fingerprint" to context.fingerprint,
                "timings" to context.timings,
            ),
        )
        AgentEventBus.toolResult("getQuestionContext", true, payload)
        return payload
    }

    /**
     * 读取并缓存当前题目上下文。
     *
     * 该函数不发送工具事件，供批量答案应用和截图裁剪复用。
     */
    private fun loadCurrentQuestionContext(): QuestionContext? {
        val service = ExamAccessibilityService.instance ?: return null
        val root = service.currentRoot() ?: return null

        val snapshotStartedAt = System.nanoTime()
        val snapshot = dumpUiTree(root)
        val snapshotMs = elapsedMs(snapshotStartedAt)
        transitionTo(AutomationFlowState.CONTEXT_ANALYZED, "页面采集完成，进入结构提取。")
        transitionTo(AutomationFlowState.QUESTION_PARSING, "题目结构化解析中。")

        val extractionStartedAt = System.nanoTime()
        val extracted = QuestionExtractor.extract(snapshot)
        val extractionMs = elapsedMs(extractionStartedAt)
        val context = enrichContextWithCachedActions(extracted).copy(
            timings = linkedMapOf(
                "snapshotMs" to snapshotMs,
                "extractionMs" to extractionMs,
            ),
        )
        lastQuestionContext = context
        rememberScreenContext(
            summary = ScreenContextSummary(
                contextSignature = context.contextSignature,
                confidence = context.structureConfidence,
                signals = context.structureSignals,
                regionNames = context.regions.map(UiRegion::region).distinct().sorted(),
            ),
            regions = context.regions,
        )
        cacheActionLocators(context)
        return context
    }

    private fun elapsedMs(startedAtNanos: Long): Long {
        return ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
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

    /**
     * 批量应用结构化答案计划。
     *
     * 选项标签和填空序号在应用前会重新映射到当前页面节点，避免缓存计划复用旧节点 id。
     */
    fun applyAnswerPlan(
        fingerprint: String?,
        selectedOptionLabels: List<String>?,
        blankAnswers: List<BlankAnswer>?,
        confidence: Double?,
        advance: Boolean?,
    ): Map<String, Any?> {
        transitionTo(AutomationFlowState.ACTION_EXECUTING, "准备批量应用答案计划。")
        val safeFingerprint = fingerprint?.trim().orEmpty()
        if (safeFingerprint.isBlank()) {
            return reportFailure("applyAnswerPlan", "缺少题目指纹，无法应用答案。", "invalid_arguments")
        }

        val desiredLabels = selectedOptionLabels.orEmpty()
            .map(::normalizePlanLabel)
            .filter(String::isNotBlank)
        if (desiredLabels.distinct().size != desiredLabels.size) {
            return reportFailure("applyAnswerPlan", "选项标签存在重复值。", "invalid_answer_plan")
        }
        val answers = blankAnswers.orEmpty()
            .map { answer -> BlankAnswer(order = answer.order, text = answer.text.trim()) }
        if (answers.any { answer -> answer.order <= 0 || answer.text.isBlank() || answer.text.length > 2000 }) {
            return reportFailure("applyAnswerPlan", "填空答案序号或文本无效。", "invalid_answer_plan")
        }
        if (answers.map(BlankAnswer::order).distinct().size != answers.size) {
            return reportFailure("applyAnswerPlan", "填空答案存在重复序号。", "invalid_answer_plan")
        }

        val context = loadCurrentQuestionContext()
            ?: return reportFailure("applyAnswerPlan", "无法读取当前题目上下文。", "context_unavailable")
        if (context.pageStage != QuestionPageStage.question) {
            return reportFailure(
                "applyAnswerPlan",
                "当前页面阶段为 ${context.pageStage}，拒绝执行答案写入。",
                "page_stage_protected",
            )
        }
        if (context.fingerprint != safeFingerprint) {
            QuestionFingerprintCache.invalidate(safeFingerprint)
            return reportFailure("applyAnswerPlan", "题目页面已变化，答案计划已失效。", "context_changed")
        }

        val optionGroups = context.options.groupBy { option -> normalizePlanLabel(option.label) }
        if (optionGroups.values.any { group -> group.size > 1 }) {
            return reportFailure("applyAnswerPlan", "当前页面存在重复选项标签，无法安全应用答案。", "ambiguous_options")
        }
        val unknownLabels = desiredLabels.filter { label -> optionGroups[label].isNullOrEmpty() }
        if (unknownLabels.isNotEmpty()) {
            return reportFailure(
                "applyAnswerPlan",
                "答案包含当前页面不存在的选项：${unknownLabels.joinToString()}。",
                "invalid_option_label",
            )
        }
        if (context.options.isNotEmpty() && desiredLabels.isEmpty()) {
            return reportFailure("applyAnswerPlan", "选择题缺少目标选项。", "invalid_answer_plan")
        }

        val inputOrders = context.inputFields.mapNotNull { input -> input.order }
        if (inputOrders.size != context.inputFields.size || inputOrders.distinct().size != inputOrders.size) {
            return reportFailure("applyAnswerPlan", "页面输入框缺少稳定且唯一的顺序，无法安全写入。", "input_order_unavailable")
        }
        val inputByOrder = context.inputFields.associateBy { input -> input.order }
        val answerByOrder = answers.associateBy(BlankAnswer::order)
        if (context.inputFields.isNotEmpty()) {
            val expectedOrders = inputByOrder.keys.filterNotNull().toSet()
            val actualOrders = answerByOrder.keys
            if (expectedOrders != actualOrders) {
                return reportFailure(
                    "applyAnswerPlan",
                    "填空答案序号与当前页面输入框不一致。",
                    "blank_order_mismatch",
                )
            }
        } else if (answers.isNotEmpty()) {
            return reportFailure("applyAnswerPlan", "当前题目没有可写入的输入框。", "invalid_answer_plan")
        }

        var appliedActionCount = 0
        context.options.forEach { option ->
            val label = normalizePlanLabel(option.label)
            val targetChecked = label in desiredLabels
            if (option.checked != targetChecked) {
                val checkableNode = locatorCache[option.id]?.checkable == true
                val actionResult = if (checkableNode) {
                    toggleElement(option.id, targetChecked)
                } else {
                    // WebView 选项经常只是可点击 View，没有 checkable 状态；回退为点击后由整页回读确认。
                    clickElement(option.id)
                }
                if (actionResult["ok"] != true) {
                    QuestionFingerprintCache.invalidate(safeFingerprint)
                    return failureWithDetails(
                        message = "选项 $label 应用失败：${actionResult["message"]}",
                        code = "answer_apply_failed",
                        details = mapOf("failedOptionLabel" to label, "cause" to actionResult),
                    )
                }
                appliedActionCount += 1
            }
        }

        context.inputFields
            .sortedWith(compareBy<InputFieldRef> { input -> input.order ?: Int.MAX_VALUE })
            .forEach { input ->
                val desiredText = answerByOrder[input.order]?.text ?: return@forEach
                if (!isInputPlanAlreadySatisfied(input, desiredText)) {
                    val setTextResult = setText(input.id, desiredText)
                    if (setTextResult["ok"] != true) {
                        QuestionFingerprintCache.invalidate(safeFingerprint)
                        return failureWithDetails(
                            message = "第 ${input.order} 空写入失败：${setTextResult["message"]}",
                            code = "answer_apply_failed",
                            details = mapOf("failedBlankOrder" to input.order, "cause" to setTextResult),
                        )
                    }
                    appliedActionCount += 1
                }
            }

        val refreshedContext = if (appliedActionCount > 0) {
            loadCurrentQuestionContext() ?: context
        } else {
            context
        }
        val verificationError = verifyAppliedPlan(
            context = refreshedContext,
            desiredLabels = desiredLabels,
            answerByOrder = answerByOrder,
        )
        if (verificationError != null) {
            QuestionFingerprintCache.invalidate(safeFingerprint)
            return reportFailure("applyAnswerPlan", verificationError, "answer_verification_failed")
        }

        val plan = AnswerPlan(
            fingerprint = safeFingerprint,
            selectedOptionLabels = desiredLabels.sorted(),
            blankAnswers = answers.sortedBy(BlankAnswer::order),
            confidence = confidence
                ?.takeIf { value -> value.isFinite() }
                ?.coerceIn(0.0, 1.0)
                ?: 1.0,
            needsManualReview = false,
        )
        QuestionFingerprintCache.store(safeFingerprint, plan)

        val finalPageStage = refreshedContext.pageStage
        if (finalPageStage in setOf(
                QuestionPageStage.submitReady,
                QuestionPageStage.submitDialog,
                QuestionPageStage.submitWarning,
            )
        ) {
            val payload = success(
                message = "答案已应用，当前页面已进入提交相关阶段，已停止自动推进。",
                extra = mapOf(
                    "answerApplied" to true,
                    "advanced" to false,
                    "manualReviewRequired" to true,
                    "finalPageStage" to finalPageStage,
                    "appliedActionCount" to appliedActionCount,
                ),
            )
            AgentEventBus.log("warn", payload["message"].toString())
            AgentEventBus.toolResult("applyAnswerPlan", true, payload)
            return payload
        }

        if (advance != true) {
            val payload = success(
                message = "答案已验证并缓存。",
                extra = mapOf(
                    "answerApplied" to true,
                    "advanced" to false,
                    "manualReviewRequired" to false,
                    "finalPageStage" to finalPageStage,
                    "appliedActionCount" to appliedActionCount,
                ),
            )
            AgentEventBus.toolResult("applyAnswerPlan", true, payload)
            return payload
        }

        val nextAction = refreshedContext.actions.firstOrNull { action -> action.kind == "next" }
        if (nextAction == null) {
            val payload = success(
                message = "答案已验证并缓存，但当前页面未找到可用的下一题动作。",
                extra = mapOf(
                    "answerApplied" to true,
                    "advanced" to false,
                    "manualReviewRequired" to true,
                    "finalPageStage" to finalPageStage,
                    "appliedActionCount" to appliedActionCount,
                ),
            )
            AgentEventBus.log("warn", payload["message"].toString())
            AgentEventBus.toolResult("applyAnswerPlan", true, payload)
            return payload
        }

        val clickResult = clickElement(nextAction.id)
        val advanced = clickResult["ok"] == true
        if (!advanced) {
            return failureWithDetails(
                message = "答案已应用并缓存，但下一题推进失败：${clickResult["message"]}",
                code = "next_action_failed",
                details = mapOf(
                    "answerApplied" to true,
                    "advanced" to false,
                    "nextActionId" to nextAction.id,
                    "cause" to clickResult,
                ),
            )
        }

        val payload = success(
            message = "答案已应用并完成下一题推进。",
            extra = mapOf(
                "answerApplied" to true,
                "advanced" to true,
                "manualReviewRequired" to false,
                "nextActionId" to nextAction.id,
                "finalPageStage" to finalPageStage,
                "appliedActionCount" to appliedActionCount,
            ),
        )
        AgentEventBus.toolResult("applyAnswerPlan", true, payload)
        return payload
    }

    /**
     * 截取题目相关区域，供多模态模型在文本识别置信度不足时补充理解。
     *
     * 图片只在内存中编码为 base64，不写入文件、不进入日志和识别轨迹。
     */
    fun captureQuestionImage(): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return failure("当前 Android 版本不支持无障碍服务截图，已降级为文本识别。", "unsupported_platform")
        }
        val service = ExamAccessibilityService.instance
            ?: return failure("无障碍服务尚未开启，无法截图。", "service_unavailable")

        val context = lastQuestionContext ?: loadCurrentQuestionContext()
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val captureStartedAt = SystemClock.uptimeMillis()

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            bitmapRef.set(softwareBitmap)
                        } catch (error: Throwable) {
                            errorRef.set(error.message ?: "screenshot_decode_failed")
                        } finally {
                            buffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errorRef.set("screenshot_error_$errorCode")
                        latch.countDown()
                    }
                },
            )
        } catch (error: Throwable) {
            errorRef.set(error.message ?: "screenshot_request_failed")
        }

        val completed = runCatching {
            latch.await(questionImageCaptureTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!completed) {
            return failure("截图请求超时，已降级为文本识别。", "screenshot_timeout")
        }
        val screenshotBitmap = bitmapRef.get()
        if (screenshotBitmap == null) {
            return failure(
                "截图失败：${errorRef.get() ?: "unknown_error"}，已降级为文本识别。",
                "screenshot_failed",
            )
        }

        val captureBounds = resolveCaptureBounds(context, screenshotBitmap.width, screenshotBitmap.height)
        var workingBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            val sourceBitmap = if (captureBounds != null && !captureBounds.isEmpty) {
                Bitmap.createBitmap(
                    screenshotBitmap,
                    captureBounds.left,
                    captureBounds.top,
                    captureBounds.width(),
                    captureBounds.height(),
                )
            } else {
                screenshotBitmap
            }
            workingBitmap = sourceBitmap
            scaledBitmap = scaleQuestionImageIfNeeded(sourceBitmap)
            val output = ByteArrayOutputStream()
            val compressed = scaledBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                questionImageJpegQuality,
                output,
            )
            if (!compressed) {
                return failure("截图压缩失败，已降级为文本识别。", "screenshot_encode_failed")
            }
            val bytes = output.toByteArray()
            val payload = success(
                message = "题目区域截图已生成，将用于多模态识别。",
                extra = mapOf(
                    "imageBase64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
                    "mimeType" to "image/jpeg",
                    "cropped" to (captureBounds != null && !captureBounds.isEmpty),
                    "bounds" to listOfNotNull(
                        captureBounds?.left,
                        captureBounds?.top,
                        captureBounds?.right,
                        captureBounds?.bottom,
                    ),
                    "imageWidth" to scaledBitmap.width,
                    "imageHeight" to scaledBitmap.height,
                    "captureMs" to (SystemClock.uptimeMillis() - captureStartedAt),
                ),
            )
            AgentEventBus.log(
                "info",
                "题目截图已生成：${scaledBitmap.width}x${scaledBitmap.height}，耗时 ${payload["captureMs"]}ms。",
            )
            return payload
        } catch (error: Throwable) {
            return failure(
                "截图处理失败：${error.message ?: "unknown_error"}，已降级为文本识别。",
                "screenshot_processing_failed",
            )
        } finally {
            if (scaledBitmap != null && scaledBitmap !== workingBitmap) {
                scaledBitmap.recycle()
            }
            if (workingBitmap != null && workingBitmap !== screenshotBitmap) {
                workingBitmap.recycle()
            }
            screenshotBitmap.recycle()
        }
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
        val semanticKind = resolveSemanticActionKind(elementId, node)
        val canPerformTap = canClick(node) || semanticKind != null
        if (!canPerformTap) {
            return reportFailure("clickElement", "节点 $elementId 不可点击。", "click_failed")
        }

        val service = ExamAccessibilityService.instance
        val beforeFingerprint = captureUiFingerprint(service?.currentRoot())
        val riskLevel = resolveActionRisk(node)
        val semanticTapUsed = if (click(node)) {
            false
        } else {
            val fallbackTapped = semanticKind?.let { kind ->
                performSemanticActionTap(node = node, kind = kind)
            } == true
            if (!fallbackTapped) {
                return reportFailure("clickElement", "节点 $elementId 执行点击失败。", "click_failed")
            }
            true
        }

        if (semanticTapUsed) {
            AgentEventBus.log("info", "节点 $elementId 常规点击不可用，已使用语义手势兜底。")
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
                    "semanticActionKind" to semanticKind,
                    "semanticTapFallbackUsed" to semanticTapUsed,
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
            payload = mapOf(
                "riskLevel" to riskLevel,
                "semanticActionKind" to semanticKind,
                "semanticTapFallbackUsed" to semanticTapUsed,
                "postCheckChanged" to changed,
            ),
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
        val refreshedNode = findNodeById(elementId)
        val refreshedText = extractNodeText(refreshedNode)
        val refreshedRawText = extractRawNodeText(refreshedNode)
        val postCheckMatched = writeResult.matched || isInputTextMatched(refreshedText, text)
        val changed = progress.changed
        val weakSignals = inferWeakInputSignals(
            rawReadText = refreshedRawText,
            changed = changed,
        )
        val weakConfirmed = !postCheckMatched && writeResult.writeSent && weakSignals.isNotEmpty()
        val verificationLevel = when {
            postCheckMatched -> "strong"
            weakConfirmed -> "weak"
            else -> "none"
        }

        val result = if (postCheckMatched || weakConfirmed) {
            transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "输入动作回读通过。")
            success(
                message = "已向节点 $elementId 输入内容。",
                extra = mapOf(
                    "elementId" to elementId,
                    "text" to text,
                    "attempts" to writeResult.attempts,
                    "lastReadText" to writeResult.lastReadText,
                    "lastRawReadText" to refreshedRawText,
                    "postCheckMatched" to postCheckMatched,
                    "postCheckChanged" to changed,
                    "postCheckWaitedMs" to progress.waitedMs,
                    "verificationLevel" to verificationLevel,
                    "weakSignals" to weakSignals,
                ),
            )
        } else {
            failure("节点 $elementId 输入后未读取到目标文本。", "set_text_failed")
        }

        if (weakConfirmed) {
            AgentEventBus.log("warn", "节点 $elementId 输入采用弱确认通过：$weakSignals")
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
            payload = mapOf(
                "postCheckMatched" to postCheckMatched,
                "postCheckChanged" to changed,
                "verificationLevel" to verificationLevel,
                "weakSignals" to weakSignals,
            ),
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
                    "postCheckChanged" to false,
                    "postCheckSatisfied" to true,
                ),
            )
        } else if (!performToggle(toggleNode)) {
            failure("切换节点 $elementId 失败。", "toggle_failed")
        } else {
            transitionTo(AutomationFlowState.RESULT_VERIFYING, "勾选切换已发送，开始回读校验。")
            val verification = waitForToggleState(elementId, targetChecked)
            if (verification.checked == targetChecked) {
                transitionTo(AutomationFlowState.NEXT_PAGE_WAITING, "勾选切换回读通过。")
                success(
                    message = "已切换节点：$elementId。",
                    extra = mapOf(
                        "elementId" to elementId,
                        "checked" to verification.checked,
                        "postCheckChanged" to (currentChecked != verification.checked),
                        "postCheckSatisfied" to true,
                        "postCheckWaitedMs" to verification.waitedMs,
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
                Thread.sleep(80)
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
        val result = if (AccessibilityAutomationDriver.performGlobalAction(globalAction, service)) {
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
        val signals = linkedMapOf<String, Double>(
            "regionCount" to regions.size.toDouble(),
            "questionSignal" to if (hasQuestionSignals) 1.0 else 0.0,
            "popupSignal" to if (hasPopup) 1.0 else 0.0,
            "footerSignal" to if (hasFooterActions) 1.0 else 0.0,
            "nodeCount" to snapshot.elements.size.toDouble(),
        )
        return ScreenContextSummary(
            contextSignature = QuestionExtractor.buildContextSignature(
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
        )
        context.actions.forEach { action ->
            val locator = locatorCache[action.id]
                ?: resolveFallbackActionLocator(action)
                ?: return@forEach
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

    private fun resolveActionCacheScope(contextSignature: String): String {
        // 动作缓存仅按结构签名分桶，避免前台包名切换导致缓存命中率抖动。
        val safeSignature = contextSignature.trim().ifBlank { "generic_context" }
        return safeSignature
    }

    private fun currentActionCacheScope(): String {
        return resolveActionCacheScope(currentContextTag())
    }

    private fun buildActionLocatorKey(contextScope: String, kind: String): String {
        return "$contextScope:${kind.lowercase()}"
    }

    private fun inferActionKind(locator: NodeLocator): String {
        val text = (locator.text ?: locator.desc).orEmpty()
        return inferActionKindFromText(text)
    }

    private fun inferActionKindFromText(text: String): String {
        return when {
            text.contains("提交") || text.contains("交卷") -> "submit"
            containsProgressActionKeyword(text) -> "next"
            text.contains("确认") || text.contains("确定") -> "confirm"
            text.contains("取消") || text.contains("返回") -> "cancel"
            else -> ""
        }
    }

    private fun containsProgressActionKeyword(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        return progressActionKeywords.any { keyword -> text.contains(keyword) }
    }

    private fun resolveFallbackActionLocator(action: QuestionAction): NodeLocator? {
        val rootBottom = rootBounds()?.bottom ?: 0
        val expectedKind = action.kind.lowercase()
        val actionText = action.text.trim()
        val candidates = locatorCache.values
            .filter { locator ->
                val text = (locator.text ?: locator.desc).orEmpty().trim()
                text.isNotBlank()
            }
        if (candidates.isEmpty()) {
            return null
        }
        return candidates.maxByOrNull { locator ->
            val text = (locator.text ?: locator.desc).orEmpty().trim()
            val inferredKind = inferActionKindFromText(text)
            val top = locator.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = locator.bounds.getOrNull(3) ?: Int.MIN_VALUE
            var score = 0
            if (inferredKind == expectedKind) {
                score += 36
            }
            if (actionText.isNotBlank()) {
                if (text == actionText) {
                    score += 28
                } else if (text.contains(actionText) || actionText.contains(text)) {
                    score += 14
                }
            }
            if (expectedKind in setOf("next", "submit") &&
                rootBottom > 0 &&
                top >= (rootBottom * 0.55).toInt()
            ) {
                score += 14
            }
            if (bottom <= top) {
                score -= 8
            }
            score
        }
    }

    private fun dumpUiTree(root: AccessibilityNodeInfo?, refreshLocatorCache: Boolean = true): UiSnapshot {
        if (refreshLocatorCache) {
            locatorCache.clear()
        }
        if (root == null) {
            return UiSnapshot(
                screen = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "rootBounds" to listOf(0, 0, 0, 0),
                ),
                elements = emptyList(),
            )
        }

        val rootRect = Rect().also(root::getBoundsInScreen)
        val elements = mutableListOf<UiElement>()
        traverse(root, 0, elements, IdGenerator(), refreshLocatorCache, null)
        return UiSnapshot(
            screen = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "rootBounds" to listOf(rootRect.left, rootRect.top, rootRect.right, rootRect.bottom),
                "width" to rootRect.width(),
                "height" to rootRect.height(),
                "packageName" to root.packageName?.toString(),
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
        parentId: String?,
    ) {
        val rect = Rect().also(node::getBoundsInScreen)
        val id = idGenerator.next()
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else {
            null
        }
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
            parentId = parentId,
            visibleToUser = node.isVisibleToUser,
            focused = node.isFocused,
            selected = node.isSelected,
            hintText = hintText,
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
                traverse(child, depth + 1, out, idGenerator, shouldCacheLocator, id)
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
        return AccessibilityAutomationDriver.clickNode(node, ::rootBounds)
    }

    private fun canClick(node: AccessibilityNodeInfo?): Boolean {
        return AccessibilityAutomationDriver.canClickNode(node, ::rootBounds)
    }

    private fun performGestureTapBestEffort(node: AccessibilityNodeInfo): Boolean {
        return AccessibilityAutomationDriver.tapNodeBestEffort(node, ::rootBounds)
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
            val verification = waitForInputText(
                elementId = elementId,
                expectedText = text,
                timeoutMs = inputVerifyTimeoutMs,
            )
            lastReadText = verification.lastReadText
            if (!verification.matched) {
                continue
            }
            // 输入后主动清焦点，触发部分题库页面的“失焦保存”逻辑，减少下一题后答案丢失。
            clearInputFocus(findNodeById(elementId))
            val blurVerification = waitForInputText(
                elementId = elementId,
                expectedText = text,
                timeoutMs = inputBlurWaitMs + inputVerifyPollMs,
            )
            lastReadText = blurVerification.lastReadText
            matched = blurVerification.matched
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

    private fun waitForToggleState(elementId: String, targetChecked: Boolean): ToggleVerification {
        val startedAt = SystemClock.uptimeMillis()
        var latestChecked: Boolean? = null
        while (SystemClock.uptimeMillis() - startedAt < toggleVerifyTimeoutMs) {
            latestChecked = findNodeById(elementId)?.let(::findToggleNode)?.isChecked
            if (latestChecked == targetChecked) {
                break
            }
            Thread.sleep(toggleVerifyPollMs)
        }
        return ToggleVerification(
            checked = latestChecked,
            waitedMs = SystemClock.uptimeMillis() - startedAt,
        )
    }

    private fun waitForInputText(
        elementId: String,
        expectedText: String,
        timeoutMs: Long,
    ): InputVerification {
        val startedAt = SystemClock.uptimeMillis()
        var latestText = ""
        while (SystemClock.uptimeMillis() - startedAt < timeoutMs) {
            latestText = extractNodeText(findNodeById(elementId))
            if (isInputTextMatched(latestText, expectedText)) {
                return InputVerification(
                    matched = true,
                    lastReadText = latestText,
                    waitedMs = SystemClock.uptimeMillis() - startedAt,
                )
            }
            Thread.sleep(inputVerifyPollMs)
        }
        return InputVerification(
            matched = false,
            lastReadText = latestText,
            waitedMs = SystemClock.uptimeMillis() - startedAt,
        )
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

    private fun extractRawNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return ""
        }
        val texts = LinkedHashSet<String>()
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 2) {
            val nodeText = current.text?.toString()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
            if (nodeText.isNotBlank()) {
                texts += nodeText
            }
            val nodeDesc = current.contentDescription?.toString()?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
            if (nodeDesc.isNotBlank()) {
                texts += nodeDesc
            }
            current = current.parent
            depth += 1
        }
        return texts.joinToString(" ").trim()
    }

    private fun inferWeakInputSignals(rawReadText: String, changed: Boolean): List<String> {
        val signals = linkedSetOf<String>()
        val normalizedRaw = rawReadText.replace("\\s+".toRegex(), " ").trim()
        val normalized = normalizeInputText(rawReadText)
        if (changed) {
            signals += "ui_changed"
        }
        if (normalizedRaw.contains("已作答") || normalizedRaw.contains("已填写")) {
            signals += "answered_prefix"
        }
        if (normalized.isNotBlank() && !looksLikeInputPlaceholder(normalized)) {
            signals += "non_placeholder_text"
        }
        return signals.toList()
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
        return AccessibilityAutomationDriver.tapPoint(x, y, ::rootBounds)
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
        } else if (containsProgressActionKeyword(text)) {
            "medium"
        } else {
            "low"
        }
    }

    private fun resolveSemanticActionKind(elementId: String, node: AccessibilityNodeInfo): String? {
        val fromId = elementId
            .takeIf { it.startsWith("action:") }
            ?.removePrefix("action:")
            ?.trim()
            ?.lowercase()
        if (!fromId.isNullOrBlank()) {
            return fromId
        }
        val inferred = inferActionKindFromText(
            buildString {
                append(node.text?.toString().orEmpty())
                append(" ")
                append(node.contentDescription?.toString().orEmpty())
            },
        )
        return inferred.ifBlank { null }
    }

    private fun performSemanticActionTap(node: AccessibilityNodeInfo, kind: String): Boolean {
        if (kind !in setOf("next", "submit", "confirm", "cancel")) {
            return false
        }
        val rect = Rect().also(node::getBoundsInScreen)
        val rootRect = rootBounds() ?: return false
        val centerX = rect.exactCenterX().takeUnless { it.isNaN() } ?: ((rect.left + rect.right) / 2f)
        val primaryY = if (rect.height() >= minGestureTapSizePx) {
            rect.exactCenterY()
        } else {
            (rect.top - 56).toFloat()
        }
        if (performGestureTapAt(centerX, primaryY)) {
            return true
        }
        if (kind !in setOf("next", "submit")) {
            return false
        }
        // 底部按钮节点在 WebView 中偶发退化为零高度，二次尝试点击底部安全带，提升可推进性。
        val fallbackY = (rootRect.bottom - 96).toFloat()
        return performGestureTapAt(centerX, fallbackY)
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
        val contextSignature: String,
        val confidence: Double,
        val signals: Map<String, Double>,
        val regionNames: List<String>,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "contextSignature" to contextSignature,
            "confidence" to confidence,
            "signals" to signals,
            "regionNames" to regionNames,
        )
    }

    private fun normalizePlanLabel(value: String): String {
        val normalized = value.trim()
        return if (normalized.length == 1 && normalized[0].isLetter()) {
            normalized.uppercase()
        } else {
            normalized
        }
    }

    private fun isInputPlanAlreadySatisfied(input: InputFieldRef, desiredText: String): Boolean {
        val currentText = input.text.orEmpty()
        return currentText.isNotBlank() && isInputTextMatched(currentText, desiredText)
    }

    private fun verifyAppliedPlan(
        context: QuestionContext,
        desiredLabels: List<String>,
        answerByOrder: Map<Int, BlankAnswer>,
    ): String? {
        val optionByLabel = context.options.associateBy { option -> normalizePlanLabel(option.label) }
        desiredLabels.forEach { label ->
            val option = optionByLabel[label] ?: return "选项 $label 在校验阶段不存在。"
            if (!option.checked) {
                return "选项 $label 未进入目标选中状态。"
            }
        }
        answerByOrder.forEach { (order, answer) ->
            val input = context.inputFields.firstOrNull { field -> field.order == order }
                ?: return "第 $order 空在校验阶段不存在。"
            if (!isInputPlanAlreadySatisfied(input, answer.text)) {
                return "第 $order 空回读内容与目标答案不一致。"
            }
        }
        return null
    }

    private fun failureWithDetails(
        message: String,
        code: String,
        details: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>(
            "ok" to false,
            "code" to code,
            "message" to message,
        ).apply {
            putAll(details)
        }
        AgentEventBus.log("error", message)
        return payload
    }

    private fun resolveCaptureBounds(
        context: QuestionContext?,
        imageWidth: Int,
        imageHeight: Int,
    ): Rect? {
        if (imageWidth <= 0 || imageHeight <= 0 || context == null) {
            return null
        }
        val relevantRegions = context.regions.filter { region ->
            region.region in setOf("question", "options", "input")
        }
        if (relevantRegions.isEmpty()) {
            return null
        }
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        relevantRegions.forEach { region ->
            val bounds = region.bounds
            if (bounds.size < 4) {
                return@forEach
            }
            left = minOf(left, bounds[0])
            top = minOf(top, bounds[1])
            right = maxOf(right, bounds[2])
            bottom = maxOf(bottom, bounds[3])
        }
        if (left >= right || top >= bottom) {
            return null
        }
        val rect = Rect(left, top, right, bottom)
        rect.inset(-24, -24)
        rect.intersect(0, 0, imageWidth, imageHeight)
        return rect.takeUnless(Rect::isEmpty)
    }

    private fun scaleQuestionImageIfNeeded(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= questionImageMaxDimension) {
            return bitmap
        }
        val scale = questionImageMaxDimension.toFloat() / longestSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
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

    private data class InputVerification(
        val matched: Boolean,
        val lastReadText: String,
        val waitedMs: Long,
    )

    private data class ToggleVerification(
        val checked: Boolean?,
        val waitedMs: Long,
    )
}
