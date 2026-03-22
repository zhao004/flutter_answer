package com.example.flutter_answer.agent

object QuestionExtractor {

    private val alphaOptionPattern = Regex("^([A-Ha-h])[.、:：．\\)]\\s*(.+)$")
    private val digitOptionPattern = Regex("^(\\d{1,2})[.、:：．\\)]\\s*(.+)$")
    private val chineseOptionPattern = Regex("^[（(]?([一二三四五六七八九十])[)）.、]\\s*(.+)$")
    private val looseOptionPrefixPattern = Regex("^([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])\\s+(.+)$")
    private val blankPlaceholderPattern = Regex("(_{2,}|\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】|\\{\\s*\\}|<\\s*>)")
    private val questionIndexPattern = Regex("^\\s*(?:第\\s*)?(\\d{1,4})\\s*(?:题|[.、:：\\)])")
    private val strictQuestionIndexPattern = Regex("^\\s*第\\s*\\d{1,4}\\s*题")
    private val optionPrefixCleanupPattern = Regex("^[A-Ha-h\\d一二三四五六七八九十]+[.、:：．\\)]\\s*")

    private val nextActionKeywords = listOf("下一题", "下一步", "继续", "下页", "完成")
    private val submitActionKeywords = listOf("提交", "交卷", "完成考试", "立即交卷")
    private val confirmActionKeywords = listOf("确认", "确定", "继续提交", "确认提交", "确定提交")
    private val cancelActionKeywords = listOf("取消", "返回", "再想想")
    private val multipleKeywords = listOf("多选", "可多选", "不定项", "至少选", "至少选择", "可选择多项")
    private val singleKeywords = listOf("单选", "仅选一项", "只能选一项")
    private val shortAnswerKeywords = listOf("简答", "问答", "论述", "主观题", "作答", "简述", "说明", "分析", "请回答", "写出")
    private val fillBlankKeywords = listOf("填空", "填入", "补全", "填写", "空格", "横线处", "括号内")
    private val booleanKeywords = listOf("判断题", "对错", "正确", "错误")
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
        val footerRegionIds = regionElementIds(regions, "footer_actions")
        val popupRegionIds = regionElementIds(regions, "popup")

        val actionsFromClickable = snapshot.elements
            .filter { it.clickable }
            .mapNotNull { element ->
                val text = cleanText(element.text ?: element.desc).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = inferActionKind(text) ?: return@mapNotNull null
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
                val targetId = if (element.clickable) {
                    element.id
                } else {
                    resolveActionTargetId(
                        hintElement = element,
                        expectedKind = kind,
                        hintText = textHint,
                        snapshot = snapshot,
                    ) ?: return@mapNotNull null
                }
                QuestionAction(
                    id = targetId,
                    text = textHint,
                    kind = kind,
                    riskLevel = actionRiskLevel(kind),
                )
            }

        val actions = (actionsFromClickable + actionsFromTextHints)
            .distinctBy { action -> "${action.id}:${action.kind}" }

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
                    !isNoiseQuestionText(option.text)
            }
            .distinctBy { option -> "${option.id}:${option.normalizedText}" }

        val inputs = snapshot.elements
            .filter { it.editable }
            .map {
                InputFieldRef(
                    id = it.id,
                    text = cleanText(it.text).ifBlank { null },
                    desc = cleanText(it.desc).ifBlank { null },
                )
            }

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
        val excludedQuestionIds = optionSourceElements.map(UiElement::id).toSet() + footerRegionIds + popupRegionIds
        val primaryQuestionCandidates = questionTextElements
            .map { element -> element to cleanText(element.text) }
            .filter { (_, text) ->
                text.isNotBlank() &&
                    text !in actionTexts &&
                    text !in optionTexts
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
                    text !in optionTexts
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

        val questionIndex = extractQuestionIndex(questionRaw)
        val cleanedQuestion = cleanupQuestionText(questionRaw)
        val normalizedQuestion = normalizeForMatching(cleanedQuestion)
        val attachments = collectAttachments(snapshot)
        val blanks = collectBlanks(cleanedQuestion, inputs)
        val required = detectRequired(cleanedQuestion, snapshot)

        val checkedOptionCount = options.count { it.checked }
        val optionElements = options.mapNotNull { option -> elementById[option.id] }
        val hasCheckboxOption = optionElements.any { element ->
            val className = element.className.orEmpty()
            className.contains("CheckBox", ignoreCase = true) ||
                className.contains("CheckedTextView", ignoreCase = true)
        }
        val hasRadioOption = optionElements.any { element ->
            element.className.orEmpty().contains("RadioButton", ignoreCase = true)
        }
        val hasMultipleKeyword = multipleKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBooleanKeyword = booleanKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasShortAnswerKeyword = shortAnswerKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasFillBlankKeyword = fillBlankKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBlankPlaceholder = containsBlankPlaceholder(cleanedQuestion)
        val hasBooleanOptionPair = options.size in 2..3 && options.all { option ->
            isBooleanLikeOptionText(option.text)
        }
        val hasInputSignal = inputs.isNotEmpty()
        val hasChoiceSignal = options.isNotEmpty()
        val hasFillBlankSignal = hasInputSignal &&
            (inputs.size >= 2 || hasFillBlankKeyword || hasBlankPlaceholder)
        val hasMultipleSignal = hasChoiceSignal && (
            hasMultipleKeyword ||
                (checkedOptionCount >= 2) ||
                (hasCheckboxOption && !hasRadioOption)
        )

        val submitLikeActionCount = actions.count { action ->
            action.kind == "submit" || action.kind == "confirm"
        }
        val hasVoteKeyword = voteKeywords.any { keyword -> normalizedQuestion.contains(keyword) } ||
            actions.any { action -> action.text.contains("投票") } ||
            options.any { option -> voteKeywords.any { keyword -> option.text.contains(keyword) } }
        val hasQuestionSignals = cleanedQuestion.isNotBlank() || options.isNotEmpty() || inputs.isNotEmpty()
        val submitOnlyPage = submitLikeActionCount > 0 &&
            options.isEmpty() &&
            inputs.isEmpty() &&
            cleanedQuestion.isBlank()

        val questionTypeDecision = when {
            hasVoteKeyword && hasChoiceSignal -> QuestionTypeDecision(
                code = "vote_selection",
                label = "投票项选择页",
                reason = "题干或选项命中投票关键词，且存在可选项。",
                strength = 0.88,
            )
            submitOnlyPage -> QuestionTypeDecision(
                code = "submit_page",
                label = "交卷页",
                reason = "检测到提交/确认类操作区，且题目要素不足。",
                strength = 0.9,
            )
            hasBooleanOptionPair || hasBooleanKeyword -> QuestionTypeDecision(
                code = "true_false",
                label = "判断题",
                reason = if (hasBooleanOptionPair) {
                    "选项命中“对/错/是/否/true/false”判断题特征。"
                } else {
                    "题干包含判断题关键词。"
                },
                strength = 0.88,
            )
            hasFillBlankSignal -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = "检测到输入框/空白占位符等填空结构信号。",
                strength = 0.84,
            )
            hasChoiceSignal -> QuestionTypeDecision(
                code = "choice_question",
                label = "选择题",
                reason = if (hasMultipleSignal) {
                    "检测到多选结构信号，按通用选择题处理。"
                } else {
                    "检测到可选项结构，按通用选择题处理。"
                },
                strength = if (hasMultipleSignal) 0.82 else 0.76,
            )
            hasInputSignal -> QuestionTypeDecision(
                code = "text_input",
                label = "文本输入题",
                reason = if (hasShortAnswerKeyword) {
                    "检测到输入框且题干包含主观作答关键词。"
                } else {
                    "检测到输入框且无稳定选项结构，按文本输入题处理。"
                },
                strength = 0.74,
            )
            else -> QuestionTypeDecision(
                code = "unknown",
                label = "未知题型",
                reason = "题型字段仅作辅助，当前未检测到稳定结构信号。",
                strength = 0.36,
            )
        }
        val pageType = resolveContextType(
            submitOnlyPage = submitOnlyPage,
            hasVoteKeyword = hasVoteKeyword,
            hasQuestionSignals = hasQuestionSignals,
        )
        val pageTypeReason = resolveContextTypeReason(
            pageType = pageType,
            submitLikeActionCount = submitLikeActionCount,
            hasVoteKeyword = hasVoteKeyword,
            hasQuestionSignals = hasQuestionSignals,
        )
        val pageTypeConfidence = estimateContextTypeConfidence(
            pageType = pageType,
            question = cleanedQuestion,
            options = options,
            inputs = inputs,
            actions = actions,
        )
        val pageScores = buildContextScores(
            submitLikeActionCount = submitLikeActionCount,
            hasVoteKeyword = hasVoteKeyword,
            hasQuestionSignals = hasQuestionSignals,
            optionCount = options.size,
            inputCount = inputs.size,
        )

        val confidence = estimateConfidence(
            decisionStrength = questionTypeDecision.strength,
            pageConfidence = pageTypeConfidence,
            questionText = cleanedQuestion,
            options = options,
            inputs = inputs,
            blanks = blanks,
        )
        val needConfirm = confidence < 0.64 || questionTypeDecision.code == "unknown"

        val fingerprint = buildFingerprint(
            questionType = questionTypeDecision.code,
            normalizedQuestion = normalizedQuestion,
            options = options,
            blanks = blanks,
        )
        val cacheProbe = QuestionFingerprintCache.record(fingerprint, questionTypeDecision.code)

        return QuestionContext(
            screenPackage = snapshot.screen["packageName"]?.toString(),
            pageType = pageType,
            pageTypeConfidence = pageTypeConfidence,
            pageTypeReason = pageTypeReason,
            questionType = questionTypeDecision.code,
            questionTypeLabel = questionTypeDecision.label,
            questionTypeReason = questionTypeDecision.reason,
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
            needConfirm = needConfirm,
            fingerprint = fingerprint,
            cacheProbe = cacheProbe,
            regions = regions,
            pageScores = pageScores,
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

    private fun collectBlanks(question: String, inputs: List<InputFieldRef>): List<QuestionBlank> {
        val blanksFromInputs = inputs.mapIndexed { index, input ->
            QuestionBlank(
                index = index + 1,
                hint = cleanText(input.desc ?: input.text).ifBlank { "第${index + 1}空" },
            )
        }
        if (blanksFromInputs.isNotEmpty()) {
            return blanksFromInputs
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

    private fun resolveContextType(
        submitOnlyPage: Boolean,
        hasVoteKeyword: Boolean,
        hasQuestionSignals: Boolean,
    ): String {
        // 不再依赖页面分类器，直接根据题目结构信号给出上下文类型，避免误分类传导到题型识别。
        return when {
            submitOnlyPage -> "submit_action_context"
            hasVoteKeyword -> "vote_context"
            hasQuestionSignals -> "question_context"
            else -> "generic_context"
        }
    }

    private fun resolveContextTypeReason(
        pageType: String,
        submitLikeActionCount: Int,
        hasVoteKeyword: Boolean,
        hasQuestionSignals: Boolean,
    ): String {
        return when (pageType) {
            "submit_action_context" -> "检测到 $submitLikeActionCount 个提交/确认动作，且题目结构要素不足。"
            "vote_context" -> "检测到投票关键词信号，按投票型上下文处理。"
            "question_context" -> "检测到题干、选项或输入框等题目结构信号。"
            else -> if (hasQuestionSignals || hasVoteKeyword) {
                "识别到弱结构信号，暂按通用上下文处理。"
            } else {
                "未识别到稳定题目结构信号，按通用上下文处理。"
            }
        }
    }

    private fun estimateContextTypeConfidence(
        pageType: String,
        question: String,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        actions: List<QuestionAction>,
    ): Double {
        // 置信度只使用可观测结构特征，弱化不可控页面标签带来的偏差。
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
        if (pageType == "submit_action_context") {
            score = score.coerceAtMost(0.9) + 0.04
        }
        if (pageType == "generic_context" && question.isBlank() && options.isEmpty() && inputs.isEmpty()) {
            score -= 0.18
        }
        return score.coerceIn(0.12, 0.96)
    }

    private fun buildContextScores(
        submitLikeActionCount: Int,
        hasVoteKeyword: Boolean,
        hasQuestionSignals: Boolean,
        optionCount: Int,
        inputCount: Int,
    ): Map<String, Double> {
        return linkedMapOf(
            "submitActionSignal" to submitLikeActionCount.toDouble(),
            "voteSignal" to if (hasVoteKeyword) 1.0 else 0.0,
            "questionSignal" to if (hasQuestionSignals) 1.0 else 0.0,
            "optionCount" to optionCount.toDouble(),
            "inputCount" to inputCount.toDouble(),
        )
    }

    private fun estimateConfidence(
        decisionStrength: Double,
        pageConfidence: Double,
        questionText: String,
        options: List<QuestionOption>,
        inputs: List<InputFieldRef>,
        blanks: List<QuestionBlank>,
    ): Double {
        var score = 0.2
        score += (decisionStrength * 0.45)
        score += (pageConfidence * 0.25)
        if (questionText.length >= 8) {
            score += 0.08
        }
        if (options.isNotEmpty()) {
            score += 0.1
        }
        if (inputs.isNotEmpty() || blanks.isNotEmpty()) {
            score += 0.08
        }
        if (questionText.isBlank()) {
            score -= 0.16
        }
        return score.coerceIn(0.08, 0.98)
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
        return collapseDuplicatedQuestionText(withoutIndex)
            .replace("^[※★☆#【】\\[\\]]+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun normalizeOptionText(text: String): String {
        return normalizePunctuation(
            cleanText(text)
                .replace(optionPrefixCleanupPattern, ""),
        )
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
                ?: sortedRow.first()
            output += QuestionOption(
                id = anchor.id,
                label = label,
                text = optionText,
                checked = sortedRow.any { element -> element.checked },
                normalizedText = normalizeForMatching(optionText),
            )
        }
        return output
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
        questionType: String,
        normalizedQuestion: String,
        options: List<QuestionOption>,
        blanks: List<QuestionBlank>,
    ): String {
        val optionsPart = options
            .map { option -> "${option.label}:${option.normalizedText}" }
            .sorted()
            .joinToString("|")
        val blankPart = blanks.size.toString()
        val raw = "$questionType#$normalizedQuestion#$optionsPart#$blankPart"
        val hash = raw.hashCode().toLong() and 0xffffffffL
        return "qf_${hash.toString(16)}"
    }

    private data class QuestionTypeDecision(
        val code: String,
        val label: String,
        val reason: String,
        val strength: Double,
    )

    private fun mergeQuestionCandidates(
        candidates: List<Pair<UiElement, String>>,
        limit: Int,
    ): String {
        if (candidates.isEmpty()) {
            return ""
        }
        return candidates
            .asSequence()
            .map { (_, text) -> stripQuestionNoiseSegments(cleanText(text)) }
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
        val normalized = normalizeForQuestionDedup(text)
        if (normalized.length <= 1) {
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

        val screenHeight = snapshot.elements.maxOfOrNull { element ->
            element.bounds.getOrNull(3) ?: 0
        } ?: 0

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
}
