package com.example.flutter_answer.agent

object QuestionExtractor {

    private const val resumeEntryStructureConfidence = 0.72
    private const val resumeEntryConfidence = 0.76
    private const val submitReadyStructureConfidence = 0.74
    private const val submitReadyConfidence = 0.82
    private const val submitDialogStructureConfidence = 0.7
    private const val submitDialogConfidence = 0.78
    private val alphaOptionPattern = Regex("^([A-Ha-h])[.、:：．\\)]\\s*(.+)$")
    private val digitOptionPattern = Regex("^(\\d{1,2})[.、:：．\\)]\\s*(.+)$")
    private val chineseOptionPattern = Regex("^[（(]?([一二三四五六七八九十])[)）.、]\\s*(.+)$")
    private val namedOptionPattern = Regex("^选项\\s*([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])[.、:：．\\)]?\\s*(.+)$")
    private val looseOptionPrefixPattern = Regex("^([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])\\s+(.+)$")
    private val blankPlaceholderPattern = Regex("(_{2,}|\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】|\\{\\s*\\}|<\\s*>)")
    private val blankIndexPattern = Regex("^第\\s*(\\d{1,3})\\s*空[:：]?$")
    private val questionIndexPattern = Regex("^\\s*(?:第\\s*)?(\\d{1,4})\\s*(?:题|[.、:：\\)])")
    private val strictQuestionIndexPattern = Regex("^\\s*第\\s*\\d{1,4}\\s*题")
    private val questionTypePattern = Regex("^\\s*(?:\\d+[.、]\\s*)?(单选题|多选题|判断题|填空题|简答题|问答题|论述题)$")
    private val optionPrefixCleanupPattern = Regex("^(?:选项\\s*)?(?:[A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])[.、:：．\\)]\\s*")
    private val optionSelectedSuffixPattern = Regex("(?:[（(\\[]?\\s*)?(?:已选择|已选中|当前选择|已勾选|已作答)(?:\\s*[）)\\]]?)$")
    private val inputHintKeywords = listOf("请输入答案", "请填写答案", "未作答轻点两下来编辑", "轻点两下来编辑", "点击输入", "双击输入", "点击作答")
    private val optionSelectedKeywords = listOf("已选择", "已选中", "当前选择", "已勾选", "已作答")

    private val nextActionKeywords = listOf("下一题", "下一步", "继续", "继续答题", "继续作答", "开始答题", "去答题", "进入答题", "下页", "完成")
    private val resumeEntryActionKeywords = listOf("继续答题", "继续作答", "开始答题", "去答题", "进入答题")
    private val resumePageMetaKeywords = listOf("开始时间", "截止时间", "共包含", "道题目", "题目数量")
    private val submitDialogKeywords = listOf("提交", "交卷", "确认提交", "确定提交", "是否提交", "提交后")
    private val unansweredWarningKeywords = listOf("未作答", "未答题", "漏答", "未完成", "未填写", "尚未作答", "空题")
    private val progressTotalCurrentPattern = Regex("共\\s*(\\d{1,4})\\s*题[^\\d]{0,10}第\\s*(\\d{1,4})\\s*题")
    private val progressCurrentTotalPattern = Regex("第\\s*(\\d{1,4})\\s*题[^\\d]{0,10}共\\s*(\\d{1,4})\\s*题")
    private val progressSlashPattern = Regex("(?<!\\d)(\\d{1,4})\\s*/\\s*(\\d{1,4})(?!\\d)")
    private val submitActionKeywords = listOf("提交", "交卷", "完成考试", "立即交卷")
    private val confirmActionKeywords = listOf("确认", "确定", "继续提交", "确认提交", "确定提交")
    private val cancelActionKeywords = listOf("取消", "返回", "再想想")
    private val booleanOptionKeywords = listOf("正确", "错误", "对", "错", "是", "否", "true", "false")
    private val voteKeywords = listOf("投票", "票数", "赞成", "反对", "候选", "候选项", "投票项")
    private val requiredKeywords = listOf("必答", "必填", "必做", "*", "（必填）")
    private val questionNoiseKeywords = listOf(
        "下拉可以刷新",
        "下拉刷新",
        "上拉加载",
        "松开刷新",
        "点击刷新",
        "刷新试试",
        "仅供参考",
        "广告",
        "猜你喜欢",
        "加载中",
    )

    fun extract(snapshot: UiSnapshot): QuestionContext {
        val regions = UiRegionLocator.locate(snapshot)
        val textElements = snapshot.elements.filter { !it.text.isNullOrBlank() }
        val elementById = snapshot.elements.associateBy(UiElement::id)
        val questionRegionIds = regionElementIds(regions, "question")
        val optionRegionIds = regionElementIds(regions, "options")
        val inputRegionIds = regionElementIds(regions, "input")
        val footerRegionIds = regionElementIds(regions, "footer_actions")
        val popupRegionIds = regionElementIds(regions, "popup")

        val actionsFromClickable = snapshot.elements
            .filter { it.clickable }
            .mapNotNull { element ->
                val text = cleanText(element.text ?: element.desc).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = inferActionKind(text) ?: return@mapNotNull null
                if (!looksLikeActionLabel(text)) {
                    return@mapNotNull null
                }
                QuestionAction(
                    id = element.id,
                    text = text,
                    kind = kind,
                    riskLevel = actionRiskLevel(kind),
                )
            }

        val actionsFromTextHints = snapshot.elements
            .mapNotNull { element ->
                val textHint = cleanText(element.text ?: element.desc).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = inferActionKind(textHint) ?: return@mapNotNull null
                if (!looksLikeActionLabel(textHint)) {
                    return@mapNotNull null
                }
                val targetId = if (element.clickable) {
                    element.id
                } else {
                    resolveActionTargetId(
                        hintElement = element,
                        expectedKind = kind,
                        hintText = textHint,
                        snapshot = snapshot,
                    ) ?: element.id.takeIf { canUseHintElementAsActionTarget(element) }
                        ?: semanticFallbackActionId(
                            kind = kind,
                            hintElement = element,
                            snapshot = snapshot,
                        )
                        ?: return@mapNotNull null
                }
                QuestionAction(
                    id = targetId,
                    text = textHint,
                    kind = kind,
                    riskLevel = actionRiskLevel(kind),
                )
            }

        val actions = prioritizeActions(
            actions = (actionsFromClickable + actionsFromTextHints)
                .distinctBy { action -> "${action.id}:${action.kind}" },
            popupRegionIds = popupRegionIds,
        )

        val optionSourceElements = resolveOptionSourceElements(
            snapshot = snapshot,
            optionRegionIds = optionRegionIds,
            footerRegionIds = footerRegionIds,
            popupRegionIds = popupRegionIds,
        )
        val options = extractOptionsFromRows(optionSourceElements)
            .filter { option ->
                option.text.isNotBlank() &&
                    !isActionLikeText(option.text) &&
                    !isNoiseOptionText(option.text)
            }
            .distinctBy { option -> "${option.id}:${option.normalizedText}" }

        val inputs = resolveInputFields(snapshot.elements)

        val actionTexts = actions.map { it.text }.toSet()
        val optionTexts = options.flatMap { option ->
            buildList {
                add(option.text)
                if (option.label.isNotBlank()) {
                    add("${option.label}. ${option.text}")
                    add("${option.label}、${option.text}")
                }
            }
        }.toSet()

        val firstOptionTop = options
            .mapNotNull { option -> elementById[option.id]?.bounds?.getOrNull(1) }
            .minOrNull()

        val questionTextElements = if (questionRegionIds.isNotEmpty()) {
            textElements.filter { element -> element.id in questionRegionIds }
        } else {
            textElements
        }
        val excludedQuestionIds = optionSourceElements.map(UiElement::id).toSet() + inputRegionIds + footerRegionIds + popupRegionIds
        val primaryQuestionCandidates = questionTextElements
            .map { element -> element to cleanText(element.text) }
            .filter { (_, text) ->
                text.isNotBlank() &&
                    text !in actionTexts &&
                    text !in optionTexts &&
                    !isQuestionMetaText(text) &&
                    !isInputMetaText(text)
            }
            .filter { (element, text) ->
                if (element.id in excludedQuestionIds) {
                    return@filter false
                }
                val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
                text.length > 1 &&
                    (!element.clickable || text.length >= 10) &&
                    (firstOptionTop == null || top <= firstOptionTop + 36)
            }
            .sortedWith(
                compareBy<Pair<UiElement, String>> { (element, _) ->
                    element.bounds.getOrNull(1) ?: Int.MAX_VALUE
                }.thenBy { (element, _) -> element.depth },
            )
        val fallbackQuestionCandidates = snapshot.elements
            .filter { element ->
                element.id !in excludedQuestionIds && !element.text.isNullOrBlank()
            }
            .map { element -> element to cleanText(element.text) }
            .filter { (_, text) ->
                text.isNotBlank() &&
                    text !in actionTexts &&
                    text !in optionTexts &&
                    !isQuestionMetaText(text) &&
                    !isInputMetaText(text)
            }
            .sortedWith(
                compareByDescending<Pair<UiElement, String>> { (element, _) ->
                    if (!element.clickable) 1 else 0
                }.thenByDescending { (_, text) -> text.length },
            )
        // 题干通常会被拆成多个节点，且会混入“下拉刷新”等提示语。
        // 这里先做噪声过滤与归一化去重，再拼接候选文本，减少重复和误提取。
        val questionRaw = mergeQuestionCandidates(primaryQuestionCandidates, limit = 3)
            .ifBlank { mergeQuestionCandidates(fallbackQuestionCandidates, limit = 1) }

        val rawQuestionIndex = extractQuestionIndex(questionRaw)
        val rawCleanedQuestion = cleanupQuestionText(questionRaw)
        val resumeEntryPage = isResumeEntryPage(
            actions = actions,
            options = options,
            inputs = inputs,
            snapshot = snapshot,
        )
        val progress = extractQuestionProgress(snapshot)
        val finalQuestion = isFinalQuestion(rawQuestionIndex, progress)
        val currentAnswered = isCurrentQuestionAnswered(options, inputs)
        val unansweredWarning = hasUnansweredWarning(snapshot)
        val submitReadyPage = isSubmitReadyPage(
            actions = actions,
            finalQuestion = finalQuestion,
            currentAnswered = currentAnswered,
            unansweredWarning = unansweredWarning,
        )
        val submitDialogPage = isSubmitDialogPage(
            actions = actions,
            options = options,
            inputs = inputs,
            snapshot = snapshot,
            unansweredWarning = unansweredWarning,
        )
        val questionIndex = if (resumeEntryPage || submitDialogPage) null else rawQuestionIndex
        val cleanedQuestion = if (resumeEntryPage || submitDialogPage) "" else rawCleanedQuestion
        val normalizedQuestion = normalizeForMatching(cleanedQuestion)
        val attachments = collectAttachments(snapshot)
        val blanks = collectBlanks(cleanedQuestion, inputs, snapshot.elements)
        val required = detectRequired(cleanedQuestion, snapshot)

        val hasBlankPlaceholder = containsBlankPlaceholder(cleanedQuestion)
        val hasInputSignal = inputs.isNotEmpty()
        val hasChoiceSignal = options.isNotEmpty()

        val submitLikeActionCount = actions.count { action ->
            action.kind == "submit" || action.kind == "confirm"
        }
        val hasVoteKeyword = voteKeywords.any { keyword -> normalizedQuestion.contains(keyword) } ||
            actions.any { action -> action.text.contains("投票") } ||
            options.any { option -> voteKeywords.any { keyword -> option.text.contains(keyword) } }
        val hasQuestionSignals = cleanedQuestion.isNotBlank() || hasChoiceSignal || hasInputSignal || hasBlankPlaceholder
        val submitOnlyPage = submitLikeActionCount > 0 &&
            options.isEmpty() &&
            inputs.isEmpty() &&
            cleanedQuestion.isBlank()
        val structureSignals = buildStructureSignals(
            submitLikeActionCount = submitLikeActionCount,
            hasVoteKeyword = hasVoteKeyword,
            hasQuestionSignals = hasQuestionSignals,
            optionCount = options.size,
            inputCount = inputs.size,
            regions = regions,
            resumeEntryPage = resumeEntryPage,
            finalQuestion = finalQuestion,
            currentAnswered = currentAnswered,
            submitReadyPage = submitReadyPage,
            submitDialogPage = submitDialogPage,
            unansweredWarning = unansweredWarning,
            progress = progress,
        )
        val structureConfidence = estimateStructureConfidence(
            question = cleanedQuestion,
            options = options,
            inputs = inputs,
            actions = actions,
            regions = regions,
            hasVoteKeyword = hasVoteKeyword,
            submitOnlyPage = submitOnlyPage,
            resumeEntryPage = resumeEntryPage,
            submitReadyPage = submitReadyPage,
            submitDialogPage = submitDialogPage,
        )

        val confidence = estimateConfidence(
            structureConfidence = structureConfidence,
            questionText = cleanedQuestion,
            options = options,
            inputs = inputs,
            blanks = blanks,
            actions = actions,
            hasVoteKeyword = hasVoteKeyword,
            submitOnlyPage = submitOnlyPage,
            resumeEntryPage = resumeEntryPage,
            submitReadyPage = submitReadyPage,
            submitDialogPage = submitDialogPage,
        )
        val needConfirm = !resumeEntryPage && !submitReadyPage && !submitDialogPage && confidence < 0.64
        val contextSignature = buildContextSignature(
            regions = regions,
            actions = actions,
            submitOnlyPage = submitOnlyPage,
        )

        val fingerprint = buildFingerprint(
            normalizedQuestion = normalizedQuestion,
            options = options,
            blanks = blanks,
            inputCount = inputs.size,
        )
        val cacheProbe = QuestionFingerprintCache.record(fingerprint)

        return QuestionContext(
            contextSignature = contextSignature,
            questionIndex = questionIndex,
            question = cleanedQuestion,
            normalizedQuestion = normalizedQuestion,
            options = options,
            inputFields = inputs,
            actions = actions,
            blanks = blanks,
            attachments = attachments,
            required = required,
            confidence = confidence,
            structureConfidence = structureConfidence,
            needConfirm = needConfirm,
            fingerprint = fingerprint,
            cacheProbe = cacheProbe,
            regions = regions,
            structureSignals = structureSignals,
        )
    }

    private fun collectAttachments(snapshot: UiSnapshot): List<QuestionAttachment> {
        return snapshot.elements
            .filter { element ->
                val className = element.className.orEmpty()
                className.contains("ImageView", ignoreCase = true) ||
                    className.contains("WebView", ignoreCase = true) ||
                    cleanText(element.text).contains("附件") ||
                    cleanText(element.desc).contains("图片")
            }
            .take(6)
            .map { element ->
                val type = when {
                    element.className.orEmpty().contains("WebView", ignoreCase = true) -> "webview"
                    element.className.orEmpty().contains("ImageView", ignoreCase = true) -> "image"
                    else -> "unknown"
                }
                QuestionAttachment(
                    id = element.id,
                    type = type,
                    desc = cleanText(element.text).ifBlank { cleanText(element.desc) },
                )
            }
    }

    private fun resolveInputFields(elements: List<UiElement>): List<InputFieldRef> {
        val inputNodes = sortElementsByVisualOrder(elements.filter { element -> element.editable })
        if (inputNodes.isEmpty()) {
            return emptyList()
        }
        val blankLabels = resolveBlankLabels(elements)
        val labelAssignments = assignBlankLabelsToInputs(inputNodes, blankLabels)
        val orderedInputs = inputNodes
            .mapIndexed { index, element ->
                val assignedLabel = labelAssignments[element.id]
                val defaultOrder = index + 1
                val resolvedOrder = assignedLabel?.index ?: defaultOrder
                val rawText = cleanText(element.text)
                val rawDesc = cleanText(element.desc)
                val explicitHint = assignedLabel?.hint ?: sanitizeBlankHint(rawDesc.ifBlank { rawText }, resolvedOrder)
                InputFieldRef(
                    id = element.id,
                    text = rawText.ifBlank { null },
                    desc = rawDesc.ifBlank { null },
                    hint = explicitHint,
                    order = resolvedOrder,
                )
            }
            .sortedWith(
                compareBy<InputFieldRef> { input -> input.order ?: Int.MAX_VALUE }
                    .thenBy { input -> input.id },
            )
        return orderedInputs.mapIndexed { index, input ->
            if (input.order != null && input.order > 0) {
                input
            } else {
                input.copy(order = index + 1)
            }
        }
    }

    private fun resolveBlankLabels(elements: List<UiElement>): List<BlankLabelAnchor> {
        return elements
            .mapNotNull { element ->
                val text = cleanText(element.text ?: element.desc)
                val match = blankIndexPattern.matchEntire(text) ?: return@mapNotNull null
                val index = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                BlankLabelAnchor(
                    index = index,
                    hint = "第${index}空",
                    bounds = element.bounds,
                )
            }
            .distinctBy { label -> label.index }
            .sortedWith(
                compareBy<BlankLabelAnchor> { label -> label.index }
                    .thenBy { label -> label.bounds.getOrNull(1) ?: Int.MAX_VALUE }
                    .thenBy { label -> label.bounds.getOrNull(0) ?: Int.MAX_VALUE },
            )
    }

    private fun assignBlankLabelsToInputs(
        inputNodes: List<UiElement>,
        blankLabels: List<BlankLabelAnchor>,
    ): Map<String, BlankLabelAnchor> {
        if (inputNodes.isEmpty() || blankLabels.isEmpty()) {
            return emptyMap()
        }
        val remainingInputIds = inputNodes.map(UiElement::id).toMutableSet()
        val inputById = inputNodes.associateBy(UiElement::id)
        val assignments = linkedMapOf<String, BlankLabelAnchor>()
        blankLabels.forEach { label ->
            val matchedInput = remainingInputIds
                .mapNotNull(inputById::get)
                .minByOrNull { input -> scoreBlankLabelToInput(label, input) }
                ?: return@forEach
            assignments[matchedInput.id] = label
            remainingInputIds.remove(matchedInput.id)
        }
        return assignments
    }

    private fun scoreBlankLabelToInput(label: BlankLabelAnchor, input: UiElement): Int {
        val labelLeft = label.bounds.getOrNull(0) ?: Int.MAX_VALUE
        val labelTop = label.bounds.getOrNull(1) ?: Int.MAX_VALUE
        val labelRight = label.bounds.getOrNull(2) ?: Int.MIN_VALUE
        val labelBottom = label.bounds.getOrNull(3) ?: Int.MIN_VALUE
        val inputLeft = input.bounds.getOrNull(0) ?: Int.MAX_VALUE
        val inputTop = input.bounds.getOrNull(1) ?: Int.MAX_VALUE
        val inputRight = input.bounds.getOrNull(2) ?: Int.MIN_VALUE
        val inputBottom = input.bounds.getOrNull(3) ?: Int.MIN_VALUE
        val labelCenterX = (labelLeft + labelRight) / 2
        val labelCenterY = (labelTop + labelBottom) / 2
        val inputCenterX = (inputLeft + inputRight) / 2
        val inputCenterY = (inputTop + inputBottom) / 2
        val horizontalDistance = kotlin.math.abs(labelCenterX - inputCenterX)
        val verticalDistance = kotlin.math.abs(labelCenterY - inputCenterY)
        val labelAboveInput = labelBottom <= inputTop + 24
        val overlapsHorizontally = labelRight >= inputLeft - 24 && labelLeft <= inputRight + 24
        var score = (verticalDistance * 3) + horizontalDistance
        if (labelAboveInput) {
            score -= 220
        }
        if (overlapsHorizontally) {
            score -= 120
        }
        if (verticalDistance <= 220) {
            score -= 80
        }
        return score
    }

    private fun sortElementsByVisualOrder(elements: List<UiElement>): List<UiElement> {
        return elements.sortedWith(
            compareBy<UiElement> { element -> element.bounds.getOrNull(1) ?: Int.MAX_VALUE }
                .thenBy { element -> element.bounds.getOrNull(0) ?: Int.MAX_VALUE }
                .thenBy(UiElement::id),
        )
    }

    private fun collectBlanks(
        question: String,
        inputs: List<InputFieldRef>,
        elements: List<UiElement>,
    ): List<QuestionBlank> {
        val blanksFromInputs = inputs
            .sortedWith(
                compareBy<InputFieldRef> { input -> input.order ?: Int.MAX_VALUE }
                    .thenBy(InputFieldRef::id),
            )
            .mapIndexed { index, input ->
                val resolvedIndex = input.order ?: index + 1
                QuestionBlank(
                    index = resolvedIndex,
                    hint = input.hint ?: sanitizeBlankHint(input.desc ?: input.text, resolvedIndex),
                    inputId = input.id,
                )
            }
        if (blanksFromInputs.isNotEmpty()) {
            return blanksFromInputs
        }

        val blanksFromLabels = resolveBlankLabels(elements).map { label ->
            QuestionBlank(
                index = label.index,
                hint = label.hint,
            )
        }
        if (blanksFromLabels.isNotEmpty()) {
            return blanksFromLabels
        }

        val placeholderCount = blankPlaceholderPattern.findAll(question).count()
        if (placeholderCount <= 0) {
            return emptyList()
        }
        return (1..placeholderCount).map { index ->
            QuestionBlank(index = index, hint = "第${index}空")
        }
    }

    private fun detectRequired(question: String, snapshot: UiSnapshot): Boolean {
        val text = buildString {
            append(question)
            append(' ')
            append(snapshot.elements.mapNotNull { it.text }.joinToString(" "))
        }
        if (requiredKeywords.any { keyword -> text.contains(keyword) }) {
            return true
        }
        if (text.contains("可选") || text.contains("选做")) {
            return false
        }
        return true
    }

    private fun estimateStructureConfidence(
        question: String,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        actions: List<QuestionAction>,
        regions: List<UiRegion>,
        hasVoteKeyword: Boolean,
        submitOnlyPage: Boolean,
        resumeEntryPage: Boolean,
        submitReadyPage: Boolean,
        submitDialogPage: Boolean,
    ): Double {
        if (resumeEntryPage) {
            // “继续答题”入口页虽然没有题目结构，但属于可安全推进页面，显式抬高结构置信度避免停滞。
            return resumeEntryStructureConfidence
        }
        if (submitReadyPage) {
            // 最后一题且满足提交条件时，结构目标明确，提升置信度帮助自动提交流程收敛。
            return submitReadyStructureConfidence
        }
        if (submitDialogPage) {
            // 提交确认弹窗属于高风险动作页，给中高置信度避免反复确认造成卡住。
            return submitDialogStructureConfidence
        }
        var score = 0.34
        if (question.length >= 8) {
            score += 0.18
        }
        if (options.isNotEmpty()) {
            score += 0.24
        }
        if (inputs.isNotEmpty()) {
            score += 0.2
        }
        if (actions.isNotEmpty()) {
            score += 0.08
        }
        if (regions.any { region -> region.region == "question" }) {
            score += 0.08
        }
        if (regions.any { region -> region.region == "options" }) {
            score += 0.06
        }
        if (hasVoteKeyword && options.isNotEmpty()) {
            score += 0.04
        }
        if (submitOnlyPage) {
            score -= 0.14
        }
        if (question.isBlank() && options.isEmpty() && inputs.isEmpty()) {
            score -= 0.18
        }
        return score.coerceIn(0.12, 0.96)
    }

    private fun buildStructureSignals(
        submitLikeActionCount: Int,
        hasVoteKeyword: Boolean,
        hasQuestionSignals: Boolean,
        optionCount: Int,
        inputCount: Int,
        regions: List<UiRegion>,
        resumeEntryPage: Boolean,
        finalQuestion: Boolean,
        currentAnswered: Boolean,
        submitReadyPage: Boolean,
        submitDialogPage: Boolean,
        unansweredWarning: Boolean,
        progress: QuestionProgress?,
    ): Map<String, Double> {
        return linkedMapOf(
            "submitActionSignal" to submitLikeActionCount.toDouble(),
            "voteSignal" to if (hasVoteKeyword) 1.0 else 0.0,
            "questionSignal" to if (hasQuestionSignals) 1.0 else 0.0,
            "optionCount" to optionCount.toDouble(),
            "inputCount" to inputCount.toDouble(),
            "regionCount" to regions.size.toDouble(),
            "popupSignal" to if (regions.any { it.region == "popup" }) 1.0 else 0.0,
            "footerSignal" to if (regions.any { it.region == "footer_actions" }) 1.0 else 0.0,
            "resumeEntrySignal" to if (resumeEntryPage) 1.0 else 0.0,
            "finalQuestionSignal" to if (finalQuestion) 1.0 else 0.0,
            "answeredSignal" to if (currentAnswered) 1.0 else 0.0,
            "submitReadySignal" to if (submitReadyPage) 1.0 else 0.0,
            "submitDialogSignal" to if (submitDialogPage) 1.0 else 0.0,
            "unansweredWarningSignal" to if (unansweredWarning) 1.0 else 0.0,
            "progressCurrent" to (progress?.current?.toDouble() ?: 0.0),
            "progressTotal" to (progress?.total?.toDouble() ?: 0.0),
        )
    }

    private fun estimateConfidence(
        structureConfidence: Double,
        questionText: String,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        blanks: List<QuestionBlank>,
        actions: List<QuestionAction>,
        hasVoteKeyword: Boolean,
        submitOnlyPage: Boolean,
        resumeEntryPage: Boolean,
        submitReadyPage: Boolean,
        submitDialogPage: Boolean,
    ): Double {
        if (resumeEntryPage) {
            // 入口页动作目标明确且风险可控，直接给到可执行置信度，减少 Agent 在入口页反复确认。
            return resumeEntryConfidence
        }
        if (submitReadyPage) {
            // 末题提交流程命中后，提升置信度避免 Agent 在“提交”动作前犹豫。
            return submitReadyConfidence
        }
        if (submitDialogPage) {
            // 提交确认弹窗需要快速完成闭环，避免低置信度造成重复读取。
            return submitDialogConfidence
        }
        // 仅基于可观测结构估计置信度，避免主观推断偏差污染结果。
        var score = 0.22
        score += (structureConfidence * 0.32)
        if (questionText.length >= 8) {
            score += 0.14
        } else if (questionText.isNotBlank()) {
            score += 0.06
        }
        if (options.isNotEmpty()) {
            score += 0.12
        }
        if (inputs.isNotEmpty() || blanks.isNotEmpty()) {
            score += 0.14
        }
        if (actions.isNotEmpty()) {
            score += 0.06
        }
        if (hasVoteKeyword && options.isNotEmpty()) {
            score += 0.05
        }
        if (submitOnlyPage) {
            score -= 0.16
        }
        if (questionText.isBlank()) {
            score -= 0.16
        }
        return score.coerceIn(0.08, 0.98)
    }

    fun buildContextSignature(
        regions: List<UiRegion>,
        actions: List<QuestionAction>,
        submitOnlyPage: Boolean,
    ): String {
        val regionPart = regions
            .map(UiRegion::region)
            .distinct()
            .sorted()
            .joinToString("+")
            .ifBlank { "no_regions" }
        val actionPart = actions
            .map(QuestionAction::kind)
            .distinct()
            .sorted()
            .joinToString("+")
            .ifBlank { "no_actions" }
        val modePart = if (submitOnlyPage) "submit_only" else "interactive"
        return "$regionPart#$actionPart#$modePart"
    }

    private fun extractQuestionIndex(rawQuestion: String): Int? {
        val match = questionIndexPattern.find(rawQuestion) ?: return null
        val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
        return value.toIntOrNull()
    }

    private fun cleanupQuestionText(raw: String): String {
        val unified = normalizePunctuation(cleanText(raw))
        val withoutNoise = stripQuestionNoiseSegments(unified)
        val withoutIndex = withoutNoise.replace(questionIndexPattern, "")
        val withoutMetaPrefix = stripQuestionMetaPrefix(withoutIndex)
        return collapseDuplicatedQuestionText(withoutMetaPrefix)
            .replace("^[※★☆#【】\\[\\]]+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun normalizeOptionText(text: String): String {
        return normalizePunctuation(
            cleanText(text)
                .replace(optionPrefixCleanupPattern, ""),
        )
            .replace(optionSelectedSuffixPattern, "")
            .trim()
    }

    private fun normalizeOptionLabel(label: String): String {
        val normalized = cleanText(label)
        return if (normalized.length == 1 && normalized[0].isLetter()) {
            normalized.uppercase()
        } else {
            normalized
        }
    }

    private fun regionElementIds(regions: List<UiRegion>, region: String): Set<String> {
        return regions.firstOrNull { it.region == region }
            ?.elementIds
            ?.toSet()
            .orEmpty()
    }

    private fun resolveOptionSourceElements(
        snapshot: UiSnapshot,
        optionRegionIds: Set<String>,
        footerRegionIds: Set<String>,
        popupRegionIds: Set<String>,
    ): List<UiElement> {
        // 优先使用区域定位结果，避免全量扫描把题干/底部按钮误收进选项集合。
        val fromRegion = if (optionRegionIds.isNotEmpty()) {
            snapshot.elements.filter { element -> element.id in optionRegionIds }
        } else {
            emptyList()
        }
        if (fromRegion.isNotEmpty()) {
            return fromRegion
        }

        val screenHeight = snapshot.elements.maxOfOrNull { element ->
            element.bounds.getOrNull(3) ?: 0
        } ?: 0
        val titleLimit = (screenHeight * 0.2).toInt()
        val footerStart = (screenHeight * 0.74).toInt()
        return snapshot.elements.filter { element ->
            if (element.id in footerRegionIds || element.id in popupRegionIds) {
                return@filter false
            }
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            val text = cleanText(element.text ?: element.desc)
            val inMiddle = top >= titleLimit && bottom <= footerStart
            if (!inMiddle) {
                return@filter false
            }
            if (strictQuestionIndexPattern.containsMatchIn(text)) {
                return@filter false
            }
            // 区域缺失时做保守回退：允许 checkable、标准选项前缀、或中部可点击文本。
            element.checkable ||
                matchOptionText(text) != null ||
                (element.clickable && text.isNotBlank() && !isActionLikeText(text))
        }
    }

    private fun extractOptionsFromRows(elements: List<UiElement>): List<QuestionOption> {
        if (elements.isEmpty()) {
            return emptyList()
        }
        val sorted = elements
            .filter { element -> element.bounds.size >= 4 }
            .sortedWith(
                compareBy<UiElement> { element ->
                    element.bounds.getOrNull(1) ?: Int.MAX_VALUE
                }.thenBy { element ->
                    element.bounds.getOrNull(0) ?: Int.MAX_VALUE
                },
            )
        if (sorted.isEmpty()) {
            return emptyList()
        }

        // 通过同一行聚类把“无文本可点击容器 + 子文本节点”合并，避免只拿到不可点击子节点。
        val rows = mutableListOf<List<UiElement>>()
        var currentRow = mutableListOf<UiElement>()
        var currentCenterY = Int.MIN_VALUE
        sorted.forEach { element ->
            val top = element.bounds.getOrNull(1) ?: return@forEach
            val bottom = element.bounds.getOrNull(3) ?: return@forEach
            val centerY = (top + bottom) / 2
            if (currentRow.isEmpty()) {
                currentRow = mutableListOf(element)
                currentCenterY = centerY
                return@forEach
            }
            if (kotlin.math.abs(centerY - currentCenterY) <= 28) {
                currentRow += element
                val size = currentRow.size
                currentCenterY = ((currentCenterY * (size - 1)) + centerY) / size
            } else {
                rows += currentRow
                currentRow = mutableListOf(element)
                currentCenterY = centerY
            }
        }
        if (currentRow.isNotEmpty()) {
            rows += currentRow
        }

        val output = mutableListOf<QuestionOption>()
        var fallbackIndex = 0
        rows.forEach { row ->
            val sortedRow = row.sortedBy { element -> element.bounds.getOrNull(0) ?: Int.MAX_VALUE }
            val rowTexts = sortedRow
                .map { element -> cleanText(element.text ?: element.desc) }
                .filter(String::isNotBlank)
                .distinct()
            if (rowTexts.isEmpty()) {
                return@forEach
            }

            val mergedText = rowTexts.joinToString(" ")
            if (isActionLikeText(mergedText) || isNoiseQuestionText(mergedText)) {
                return@forEach
            }
            if (strictQuestionIndexPattern.containsMatchIn(mergedText)) {
                return@forEach
            }

            // 先走显式选项标签匹配，再回退到松散前缀匹配，最后使用整行文本作为选项内容。
            val explicit = rowTexts
                .asSequence()
                .mapNotNull(::matchOptionText)
                .firstOrNull()
                ?: matchLooseOptionText(mergedText)
            val optionText = if (explicit != null) {
                explicit.second
            } else {
                normalizeOptionText(mergedText)
            }.takeIf { text -> text.isNotBlank() } ?: return@forEach
            val label = if (explicit != null) {
                normalizeOptionLabel(explicit.first)
            } else {
                fallbackLabel(fallbackIndex)
            }
            if (explicit == null) {
                fallbackIndex += 1
            }
            // 锚点优先选择可勾选/可点击节点，保证后续执行点击时尽量可操作。
            val anchor = sortedRow.firstOrNull { element -> element.checkable }
                ?: sortedRow.firstOrNull { element -> element.clickable }
                ?: sortedRow.firstOrNull { element -> cleanText(element.text ?: element.desc).isNotBlank() }
                ?: sortedRow.first()
            output += QuestionOption(
                id = anchor.id,
                label = label,
                text = optionText,
                checked = isOptionMarkedSelected(rowTexts, sortedRow),
                normalizedText = normalizeForMatching(optionText),
            )
        }
        return output
    }

    private fun isOptionMarkedSelected(rowTexts: List<String>, rowElements: List<UiElement>): Boolean {
        if (rowElements.any { element -> element.checked }) {
            return true
        }
        return rowTexts.any { text ->
            optionSelectedKeywords.any { keyword -> text.contains(keyword) }
        }
    }

    private fun matchLooseOptionText(text: String): Pair<String, String>? {
        val match = looseOptionPrefixPattern.find(cleanText(text)) ?: return null
        val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val optionText = normalizeOptionText(match.groupValues.getOrNull(2)?.trim().orEmpty())
        if (label.isBlank() || optionText.isBlank()) {
            return null
        }
        return label to optionText
    }

    private fun isActionLikeText(text: String): Boolean {
        val normalized = cleanText(text)
        if (normalized.isBlank()) {
            return false
        }
        return inferActionKind(normalized) != null
    }

    private fun looksLikeActionLabel(text: String): Boolean {
        val normalized = cleanText(text)
        if (normalized.isBlank()) {
            return false
        }
        if (normalized.length > 12) {
            return false
        }
        return !normalized.any { ch -> ch in listOf('，', '。', '！', '？', ',', '.', '!', '?', ';', '；') }
    }

    private fun normalizePunctuation(text: String): String {
        return text
            .replace('，', ',')
            .replace('。', '.')
            .replace('：', ':')
            .replace('；', ';')
            .replace('（', '(')
            .replace('）', ')')
            .replace('【', '[')
            .replace('】', ']')
    }

    private fun matchOptionText(text: String): Pair<String, String>? {
        val match = alphaOptionPattern.find(text)
            ?: digitOptionPattern.find(text)
            ?: chineseOptionPattern.find(text)
            ?: namedOptionPattern.find(text)
        if (match == null) {
            return null
        }
        val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val optionText = normalizeOptionText(match.groupValues.getOrNull(2)?.trim().orEmpty())
        if (label.isBlank() || optionText.isBlank()) {
            return null
        }
        return label to optionText
    }

    private fun containsBlankPlaceholder(question: String): Boolean {
        if (question.isBlank()) {
            return false
        }
        return blankPlaceholderPattern.containsMatchIn(question)
    }

    private fun isBooleanLikeOptionText(text: String): Boolean {
        val normalized = normalizeForMatching(text)
        if (normalized.isBlank()) {
            return false
        }
        if (booleanOptionKeywords.any { keyword -> normalized == normalizeForMatching(keyword) }) {
            return true
        }
        return normalized.contains("true") || normalized.contains("false")
    }

    private fun fallbackLabel(index: Int): String {
        val labels = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (index in labels.indices) {
            return labels[index].toString()
        }
        return (index + 1).toString()
    }

    private fun normalizeForMatching(value: String): String {
        return normalizePunctuation(
            value
                .lowercase()
                .replace("\\s+".toRegex(), "")
                .trim(),
        )
    }

    private fun cleanText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            .orEmpty()
    }

    private fun buildFingerprint(
        normalizedQuestion: String,
        options: List<QuestionOption>,
        blanks: List<QuestionBlank>,
        inputCount: Int,
    ): String {
        val optionsPart = options
            .map { option -> "${option.label}:${option.normalizedText}" }
            .sorted()
            .joinToString("|")
        val structurePart = "${blanks.size}#$inputCount"
        val raw = "$normalizedQuestion#$optionsPart#$structurePart"
        val hash = raw.hashCode().toLong() and 0xffffffffL
        return "qf_${hash.toString(16)}"
    }

    private fun mergeQuestionCandidates(
        candidates: List<Pair<UiElement, String>>,
        limit: Int,
    ): String {
        if (candidates.isEmpty()) {
            return ""
        }
        return candidates
            .asSequence()
            .map { (_, text) -> stripQuestionMetaPrefix(stripQuestionNoiseSegments(cleanText(text))) }
            .filter { text -> text.isNotBlank() && !isNoiseQuestionText(text) }
            .distinctBy(::normalizeForQuestionDedup)
            .take(limit.coerceAtLeast(1))
            .joinToString(separator = " ")
            .trim()
    }

    private fun isNoiseQuestionText(text: String): Boolean {
        if (text.isBlank()) {
            return true
        }
        if (isQuestionMetaText(text)) {
            return true
        }
        if (isInputMetaText(text)) {
            return true
        }
        val normalized = normalizeForQuestionDedup(text)
        if (normalized.length <= 1) {
            return true
        }
        return questionNoiseKeywords.any { keyword ->
            normalized.contains(normalizeForQuestionDedup(keyword))
        }
    }

    private fun isNoiseOptionText(text: String): Boolean {
        if (text.isBlank()) {
            return true
        }
        val normalized = normalizeForQuestionDedup(text)
        if (normalized.isBlank()) {
            return true
        }
        return questionNoiseKeywords.any { keyword ->
            normalized.contains(normalizeForQuestionDedup(keyword))
        }
    }

    private fun normalizeForQuestionDedup(value: String): String {
        return normalizePunctuation(
            cleanText(value)
                .lowercase()
                .replace("\\s+".toRegex(), ""),
        )
    }

    private fun stripQuestionNoiseSegments(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        var cleaned = value
        // 节点文本可能混入“下拉刷新”等提示语，按片段剔除而不是整句丢弃，避免题干被误清空。
        questionNoiseKeywords.forEach { keyword ->
            if (keyword.isBlank()) {
                return@forEach
            }
            cleaned = cleaned.replace(keyword, " ")
            cleaned = cleaned.replace(normalizePunctuation(keyword), " ")
        }
        return cleaned
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun stripQuestionMetaPrefix(value: String): String {
        var cleaned = cleanText(value)
        while (cleaned.startsWith("题干") || cleaned.startsWith("题目")) {
            cleaned = cleaned
                .removePrefix("题干")
                .removePrefix("题目")
                .trimStart(':', '：', ' ')
                .trim()
        }
        return cleaned
    }

    private fun collapseDuplicatedQuestionText(value: String): String {
        val normalized = cleanText(value)
        if (normalized.isBlank()) {
            return ""
        }
        val doubled = Regex("^(.{2,160})\\s+\\1$").find(normalized)
        return doubled?.groupValues?.getOrNull(1)?.trim().takeIf { text -> !text.isNullOrBlank() } ?: normalized
    }

    private fun inferActionKind(text: String): String? {
        val normalized = normalizeForMatching(text)
        // “是/否/对/错/true/false”常见于判断题选项，不应被识别为确认按钮。
        if (isBooleanLikeOptionText(normalized)) {
            return null
        }
        return when {
            submitActionKeywords.any { keyword -> text.contains(keyword) } -> "submit"
            nextActionKeywords.any { keyword -> text.contains(keyword) } -> "next"
            confirmActionKeywords.any { keyword -> text.contains(keyword) } -> "confirm"
            cancelActionKeywords.any { keyword -> text.contains(keyword) } -> "cancel"
            else -> null
        }
    }

    private fun actionRiskLevel(kind: String): String {
        return when (kind) {
            "submit", "confirm" -> "high"
            "next" -> "medium"
            else -> "low"
        }
    }

    private fun prioritizeActions(
        actions: List<QuestionAction>,
        popupRegionIds: Set<String>,
    ): List<QuestionAction> {
        if (actions.isEmpty() || popupRegionIds.isEmpty()) {
            return actions
        }
        val popupKinds = actions
            .filter { action -> action.id in popupRegionIds }
            .map(QuestionAction::kind)
            .toSet()
        if (popupKinds.isEmpty()) {
            return actions
        }
        // 弹窗出现时，背景层同类动作通常已不可用；优先保留弹窗动作，避免误点底部原按钮。
        return actions.filter { action ->
            action.kind !in popupKinds || action.id in popupRegionIds
        }
    }

    private fun resolveActionTargetId(
        hintElement: UiElement,
        expectedKind: String,
        hintText: String,
        snapshot: UiSnapshot,
    ): String? {
        val hintBounds = hintElement.bounds
        val hintLeft = hintBounds.getOrNull(0) ?: return null
        val hintTop = hintBounds.getOrNull(1) ?: return null
        val hintRight = hintBounds.getOrNull(2) ?: return null
        val hintBottom = hintBounds.getOrNull(3) ?: return null
        val hintCenterX = (hintLeft + hintRight) / 2
        val hintCenterY = (hintTop + hintBottom) / 2
        val hintArea = (hintRight - hintLeft).coerceAtLeast(0) * (hintBottom - hintTop).coerceAtLeast(0)

        val screenWidth = snapshot.elements.maxOfOrNull { element ->
            element.bounds.getOrNull(2) ?: 0
        } ?: 0
        val screenHeight = snapshot.elements.maxOfOrNull { element ->
            element.bounds.getOrNull(3) ?: 0
        } ?: 0
        val screenArea = screenWidth * screenHeight

        val candidates = snapshot.elements.filter { element ->
            element.clickable && element.enabled
        }
        if (candidates.isEmpty()) {
            return null
        }

        var bestId: String? = null
        var bestScore = Int.MIN_VALUE
        candidates.forEach { candidate ->
            val bounds = candidate.bounds
            val left = bounds.getOrNull(0) ?: return@forEach
            val top = bounds.getOrNull(1) ?: return@forEach
            val right = bounds.getOrNull(2) ?: return@forEach
            val bottom = bounds.getOrNull(3) ?: return@forEach
            val candidateText = cleanText(candidate.text ?: candidate.desc)
            val candidateArea = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
            var score = 0

            if (hintCenterX in left..right && hintCenterY in top..bottom) {
                score += 90
            }

            val overlapArea = overlapArea(
                leftA = hintLeft,
                topA = hintTop,
                rightA = hintRight,
                bottomA = hintBottom,
                leftB = left,
                topB = top,
                rightB = right,
                bottomB = bottom,
            )
            if (overlapArea > 0) {
                score += 36
            }

            val verticalDistance = when {
                hintBottom < top -> top - hintBottom
                bottom < hintTop -> hintTop - bottom
                else -> 0
            }
            if (verticalDistance <= 80) {
                score += 24
            } else if (verticalDistance <= 220) {
                score += 10
            }

            val candidateCenterX = (left + right) / 2
            val horizontalDistance = kotlin.math.abs(candidateCenterX - hintCenterX)
            if (horizontalDistance <= 160) {
                score += 14
            } else if (horizontalDistance <= 320) {
                score += 6
            }

            if (candidateText.isNotBlank()) {
                if (candidateText == hintText) {
                    score += 28
                } else if (candidateText.contains(hintText) || hintText.contains(candidateText)) {
                    score += 16
                }
                if (inferActionKind(candidateText) == expectedKind) {
                    score += 18
                }
            }

            if ((expectedKind == "next" || expectedKind == "submit") && screenHeight > 0 && top >= screenHeight * 0.55) {
                score += 12
            }

            if ((candidate.className.orEmpty()).contains("Button", ignoreCase = true)) {
                score += 8
            }

            if (hintArea > 0 && candidateArea > hintArea * 5 && candidateText.isBlank()) {
                score -= 36
            }
            if (screenArea > 0 && candidateArea > (screenArea * 0.42).toInt() && candidateText.isBlank()) {
                score -= 80
            }

            if (score > bestScore) {
                bestScore = score
                bestId = candidate.id
            }
        }

        // 文案提示可能是按钮子节点，候选得分过低时放弃，避免误点无关区域。
        return bestId.takeIf { bestScore >= 30 }
    }

    private fun overlapArea(
        leftA: Int,
        topA: Int,
        rightA: Int,
        bottomA: Int,
        leftB: Int,
        topB: Int,
        rightB: Int,
        bottomB: Int,
    ): Int {
        val overlapLeft = maxOf(leftA, leftB)
        val overlapTop = maxOf(topA, topB)
        val overlapRight = minOf(rightA, rightB)
        val overlapBottom = minOf(bottomA, bottomB)
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) {
            return 0
        }
        return (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
    }

    private fun detectOptionLabelSequence(options: List<QuestionOption>): Boolean {
        if (options.size < 2) {
            return false
        }
        val alphaOrder = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() }
        val numericOrder = (1..26).map { it.toString() }
        val labels = options
            .map { option -> option.label.trim().uppercase() }
            .filter { label -> label.isNotBlank() }
            .distinct()
        if (labels.size < 2) {
            return false
        }

        val alphaIndex = alphaOrder.indexOf(labels.first())
        if (alphaIndex >= 0) {
            val expected = alphaOrder.drop(alphaIndex).take(labels.size)
            if (labels == expected) {
                return true
            }
        }

        val numericIndex = numericOrder.indexOf(labels.first())
        if (numericIndex >= 0) {
            val expected = numericOrder.drop(numericIndex).take(labels.size)
            if (labels == expected) {
                return true
            }
        }
        return false
    }

    private fun detectCustomOptionLayout(optionElements: List<UiElement>): Boolean {
        if (optionElements.size < 2) {
            return false
        }
        val classGroups = optionElements.groupingBy { it.className.orEmpty() }.eachCount()
        val hasRepeatedClass = classGroups.values.any { count -> count >= 2 }
        val topValues = optionElements.mapNotNull { it.bounds.getOrNull(1) }.sorted()
        if (topValues.size < 2) {
            return false
        }
        val verticalDiffs = topValues.zipWithNext { a, b -> b - a }
        val stableSpacingCount = verticalDiffs.count { diff -> diff in 6..220 }
        return hasRepeatedClass && stableSpacingCount >= 1
    }

    private fun isQuestionMetaText(text: String): Boolean {
        val normalized = cleanText(text)
        if (normalized.isBlank()) {
            return true
        }
        if (normalized == "题干" || normalized == "题目" || normalized == "问题") {
            return true
        }
        return questionTypePattern.matches(normalized)
    }

    private fun isInputMetaText(text: String): Boolean {
        val normalized = cleanText(text)
        if (normalized.isBlank()) {
            return true
        }
        if (blankIndexPattern.matches(normalized)) {
            return true
        }
        return inputHintKeywords.any { keyword -> normalized.contains(keyword) }
    }

    private fun sanitizeBlankHint(rawHint: String?, index: Int): String {
        val hint = cleanText(rawHint)
        if (hint.isBlank() || isInputMetaText(hint)) {
            return "第${index}空"
        }
        val match = blankIndexPattern.matchEntire(hint)
        val explicitIndex = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (explicitIndex != null) {
            return "第${explicitIndex}空"
        }
        return hint
    }

    private fun canUseHintElementAsActionTarget(element: UiElement): Boolean {
        val left = element.bounds.getOrNull(0) ?: return false
        val top = element.bounds.getOrNull(1) ?: return false
        val right = element.bounds.getOrNull(2) ?: return false
        val bottom = element.bounds.getOrNull(3) ?: return false
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        if (width < 48 || height < 48) {
            return false
        }
        return cleanText(element.text ?: element.desc).isNotBlank()
    }

    private fun semanticFallbackActionId(
        kind: String,
        hintElement: UiElement,
        snapshot: UiSnapshot,
    ): String? {
        if (kind !in setOf("next", "submit", "confirm", "cancel")) {
            return null
        }
        val top = hintElement.bounds.getOrNull(1) ?: return null
        val bottom = hintElement.bounds.getOrNull(3) ?: return null
        val screenHeight = snapshot.elements.maxOfOrNull { element ->
            element.bounds.getOrNull(3) ?: 0
        } ?: return null
        if (screenHeight <= 0) {
            return null
        }
        val isFooterLike = top >= (screenHeight * 0.55).toInt() || bottom >= (screenHeight * 0.6).toInt()
        val isPopupLike = kind == "confirm" || kind == "cancel"
        val hintText = cleanText(hintElement.text ?: hintElement.desc)
        val isResumeEntryAction = kind == "next" && resumeEntryActionKeywords.any { keyword ->
            hintText.contains(keyword)
        }
        if (!isFooterLike && !isPopupLike && !isResumeEntryAction) {
            return null
        }
        // 当底部动作或“继续答题”入口节点退化为不可点击/零高度时，输出语义动作 id，交给原生层按语义兜底定位。
        return "action:$kind"
    }

    private fun isResumeEntryPage(
        actions: List<QuestionAction>,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        snapshot: UiSnapshot,
    ): Boolean {
        if (options.isNotEmpty() || inputs.isNotEmpty()) {
            return false
        }
        val hasResumeAction = actions.any { action ->
            action.kind == "next" && resumeEntryActionKeywords.any { keyword -> action.text.contains(keyword) }
        }
        if (!hasResumeAction) {
            return false
        }
        val pageTexts = snapshot.elements
            .asSequence()
            .map { element -> cleanText(element.text ?: element.desc) }
            .filter(String::isNotBlank)
            .toList()
        if (pageTexts.isEmpty()) {
            return false
        }
        val metaHitCount = resumePageMetaKeywords.count { keyword ->
            pageTexts.any { text -> text.contains(keyword) }
        }
        return metaHitCount >= 2
    }

    private fun isCurrentQuestionAnswered(options: List<QuestionOption>, inputs: List<InputFieldRef>): Boolean {
        if (options.isEmpty() && inputs.isEmpty()) {
            return false
        }
        val choiceSatisfied = options.isEmpty() || options.any { option -> option.checked }
        val inputSatisfied = inputs.isEmpty() || inputs.all(::isInputFieldFilled)
        return choiceSatisfied && inputSatisfied
    }

    private fun isInputFieldFilled(input: InputFieldRef): Boolean {
        val value = cleanText(input.text ?: input.desc)
        if (value.isBlank()) {
            return false
        }
        if (inputHintKeywords.any { keyword -> value.contains(keyword) }) {
            return false
        }
        return !blankIndexPattern.matches(value)
    }

    private fun extractQuestionProgress(snapshot: UiSnapshot): QuestionProgress? {
        val pageTexts = snapshot.elements
            .asSequence()
            .map { element -> cleanText(element.text ?: element.desc) }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (pageTexts.isEmpty()) {
            return null
        }
        val candidates = mutableListOf<QuestionProgress>()
        pageTexts.forEach { text ->
            progressTotalCurrentPattern.findAll(text).forEach { match ->
                val total = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val current = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
                buildProgressCandidate(current = current, total = total)?.let(candidates::add)
            }
            progressCurrentTotalPattern.findAll(text).forEach { match ->
                val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
                buildProgressCandidate(current = current, total = total)?.let(candidates::add)
            }
            progressSlashPattern.findAll(text).forEach { match ->
                val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@forEach
                val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@forEach
                buildProgressCandidate(current = current, total = total)?.let(candidates::add)
            }
        }
        return candidates.maxWithOrNull(
            compareBy<QuestionProgress> { progress -> progress.total }
                .thenBy { progress -> progress.current },
        )
    }

    private fun buildProgressCandidate(current: Int, total: Int): QuestionProgress? {
        if (current <= 0 || total <= 0 || current > total || total > 500) {
            return null
        }
        return QuestionProgress(current = current, total = total)
    }

    private fun isFinalQuestion(questionIndex: Int?, progress: QuestionProgress?): Boolean {
        if (progress == null) {
            return false
        }
        if (progress.current >= progress.total) {
            return true
        }
        return questionIndex != null && questionIndex >= progress.total
    }

    private fun hasUnansweredWarning(snapshot: UiSnapshot): Boolean {
        return snapshot.elements.any { element ->
            val text = cleanText(element.text ?: element.desc)
            text.isNotBlank() && unansweredWarningKeywords.any { keyword -> text.contains(keyword) }
        }
    }

    private fun isSubmitReadyPage(
        actions: List<QuestionAction>,
        finalQuestion: Boolean,
        currentAnswered: Boolean,
        unansweredWarning: Boolean,
    ): Boolean {
        if (unansweredWarning) {
            return false
        }
        val hasSubmitAction = actions.any { action -> action.kind == "submit" }
        if (!hasSubmitAction) {
            return false
        }
        val hasNextAction = actions.any { action -> action.kind == "next" }
        val likelyFinalQuestion = finalQuestion || !hasNextAction
        return likelyFinalQuestion && currentAnswered
    }

    private fun isSubmitDialogPage(
        actions: List<QuestionAction>,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        snapshot: UiSnapshot,
        unansweredWarning: Boolean,
    ): Boolean {
        if (unansweredWarning) {
            return false
        }
        if (options.isNotEmpty() || inputs.isNotEmpty()) {
            return false
        }
        val hasConfirmLikeAction = actions.any { action -> action.kind == "confirm" || action.kind == "submit" }
        val hasCancelAction = actions.any { action -> action.kind == "cancel" }
        if (!hasConfirmLikeAction || !hasCancelAction) {
            return false
        }
        val pageTexts = snapshot.elements
            .asSequence()
            .map { element -> cleanText(element.text ?: element.desc) }
            .filter(String::isNotBlank)
            .toList()
        if (pageTexts.isEmpty()) {
            return false
        }
        return submitDialogKeywords.any { keyword ->
            pageTexts.any { text -> text.contains(keyword) }
        }
    }

    private data class QuestionProgress(
        val current: Int,
        val total: Int,
    )

    private data class BlankLabelAnchor(
        val index: Int,
        val hint: String,
        val bounds: List<Int>,
    )
}
