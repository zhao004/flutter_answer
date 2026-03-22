package com.example.flutter_answer.agent

object QuestionExtractor {

    private val alphaOptionPattern = Regex("^([A-Ha-h])[.、:：．\\)]\\s*(.+)$")
    private val digitOptionPattern = Regex("^(\\d{1,2})[.、:：．\\)]\\s*(.+)$")
    private val chineseOptionPattern = Regex("^[（(]?([一二三四五六七八九十])[)）.、]\\s*(.+)$")
    private val blankPlaceholderPattern = Regex("(_{2,}|\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】|\\{\\s*\\}|<\\s*>)")
    private val questionIndexPattern = Regex("^\\s*(?:第\\s*)?(\\d{1,4})\\s*(?:题|[.、:：\\)])")
    private val optionPrefixCleanupPattern = Regex("^[A-Ha-h\\d一二三四五六七八九十]+[.、:：．\\)]\\s*")

    private val nextActionKeywords = listOf("下一题", "下一步", "继续", "下页", "完成")
    private val submitActionKeywords = listOf("提交", "交卷", "完成考试", "立即交卷")
    private val confirmActionKeywords = listOf("确认", "确定", "是", "继续提交")
    private val cancelActionKeywords = listOf("取消", "返回", "再想想")
    private val multipleKeywords = listOf("多选", "可多选", "不定项", "至少选", "至少选择", "可选择多项")
    private val singleKeywords = listOf("单选", "仅选一项", "只能选一项")
    private val shortAnswerKeywords = listOf("简答", "问答", "论述", "主观题", "作答", "简述", "说明", "分析", "请回答", "写出")
    private val fillBlankKeywords = listOf("填空", "填入", "补全", "填写", "空格", "横线处", "括号内")
    private val booleanKeywords = listOf("判断题", "对错", "正确", "错误")
    private val booleanOptionKeywords = listOf("正确", "错误", "对", "错", "是", "否")
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
        val page = PageClassifier.classify(snapshot)
        val regions = UiRegionLocator.locate(snapshot, page)
        val textElements = snapshot.elements.filter { !it.text.isNullOrBlank() }
        val elementById = snapshot.elements.associateBy(UiElement::id)

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

        var fallbackOptionIndex = 0
        val options = textElements.mapNotNull { element ->
            val text = cleanText(element.text)
            val matchedOption = matchOptionText(text)
            if (matchedOption != null) {
                val (label, optionText) = matchedOption
                return@mapNotNull QuestionOption(
                    id = element.id,
                    label = normalizeOptionLabel(label),
                    text = optionText,
                    checked = element.checked,
                    normalizedText = normalizeForMatching(optionText),
                )
            }

            if (element.checkable && text.isNotBlank()) {
                val label = fallbackLabel(fallbackOptionIndex)
                fallbackOptionIndex += 1
                val normalizedText = normalizeOptionText(text)
                return@mapNotNull QuestionOption(
                    id = element.id,
                    label = label,
                    text = normalizedText,
                    checked = element.checked,
                    normalizedText = normalizeForMatching(normalizedText),
                )
            }

            null
        }.distinctBy { it.id }

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

        val primaryQuestionCandidates = textElements
            .map { element -> element to cleanText(element.text) }
            .filter { (_, text) ->
                text.isNotBlank() &&
                    text !in actionTexts &&
                    text !in optionTexts
            }
            .filter { (element, text) ->
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
        val fallbackQuestionCandidates = textElements
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

        val hasBooleanOptions = options.any { option ->
            booleanOptionKeywords.any { keyword -> option.text.contains(keyword) }
        }
        val hasBooleanOptionPair = options.size == 2 && options.all { option ->
            booleanOptionKeywords.any { keyword -> option.text.contains(keyword) }
        }
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
        val hasOptionLabelSequence = detectOptionLabelSequence(options)
        val hasCustomOptionLayout = detectCustomOptionLayout(optionElements)
        val hasMultipleKeyword = multipleKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasSingleKeyword = singleKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBooleanKeyword = booleanKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasShortAnswerKeyword = shortAnswerKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasFillBlankKeyword = fillBlankKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBlankPlaceholder = containsBlankPlaceholder(cleanedQuestion)

        val submitOnlyPage = actions.isNotEmpty() && options.isEmpty() && inputs.isEmpty() && cleanedQuestion.isBlank()

        val questionTypeDecision = when {
            page.pageType == PageClassifier.VOTE -> QuestionTypeDecision(
                code = "vote_selection",
                label = "投票项选择页",
                reason = "页面分类已命中投票页特征。",
                strength = 0.92,
            )
            page.pageType == PageClassifier.SUBMIT_CONFIRM_DIALOG || submitOnlyPage -> QuestionTypeDecision(
                code = "submit_page",
                label = "交卷页",
                reason = "检测到提交/确认类操作区，且题目要素不足。",
                strength = 0.9,
            )
            hasBooleanKeyword || hasBooleanOptionPair -> QuestionTypeDecision(
                code = "true_false",
                label = "判断题",
                reason = if (hasBooleanKeyword) {
                    "题干包含判断题关键词。"
                } else {
                    "仅检测到 2 个选项，且都命中“对/错/是/否”特征。"
                },
                strength = 0.88,
            )
            hasMultipleKeyword -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "题干包含多选关键词。",
                strength = 0.9,
            )
            options.isNotEmpty() && checkedOptionCount >= 2 -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "当前已勾选 $checkedOptionCount 个选项，符合多选特征。",
                strength = 0.86,
            )
            hasSingleKeyword -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "题干包含单选关键词。",
                strength = 0.88,
            )
            options.isNotEmpty() && hasCheckboxOption && !hasRadioOption -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "检测到选项控件为 CheckBox/CheckedTextView，且未检测到单选控件。",
                strength = 0.82,
            )
            options.isNotEmpty() && hasRadioOption -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "检测到 RadioButton 单选控件。",
                strength = 0.84,
            )
            options.isNotEmpty() && (hasOptionLabelSequence || hasCustomOptionLayout) -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = if (hasOptionLabelSequence) {
                    "检测到连续选项标签（A/B/C 或 1/2/3）并形成候选选项组。"
                } else {
                    "检测到自定义选项布局（无标准单选控件），按客观选择题处理。"
                },
                strength = 0.78,
            )
            (hasFillBlankKeyword || hasBlankPlaceholder) && options.isEmpty() -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = if (hasFillBlankKeyword) {
                    "题干包含填空关键词，且未检测到选项。"
                } else {
                    "题干命中空白占位符（如 ____ / （ ）），且未检测到选项。"
                },
                strength = 0.84,
            )
            inputs.size >= 2 -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = "检测到多个输入框，按填空场景处理。",
                strength = 0.8,
            )
            options.isNotEmpty() -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "存在候选选项，且没有命中多选/判断强特征。",
                strength = 0.72,
            )
            inputs.isNotEmpty() && hasFillBlankKeyword -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = "存在输入框且题干包含填空关键词。",
                strength = 0.74,
            )
            inputs.isNotEmpty() && hasShortAnswerKeyword -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "存在输入框且题干包含简答关键词。",
                strength = 0.74,
            )
            inputs.size == 1 -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "检测到单个输入框且没有选项控件，按简答场景处理。",
                strength = 0.7,
            )
            hasShortAnswerKeyword && options.isEmpty() -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "题干包含简答关键词，且未检测到选项。",
                strength = 0.66,
            )
            hasBooleanOptions && options.size <= 3 -> QuestionTypeDecision(
                code = "true_false",
                label = "判断题",
                reason = "选项文本包含判断题特征词，且选项数量较少。",
                strength = 0.64,
            )
            else -> QuestionTypeDecision(
                code = "unknown",
                label = "未知题型",
                reason = "未检测到稳定的题型特征，请结合 UI 快照人工确认。",
                strength = 0.32,
            )
        }

        val confidence = estimateConfidence(
            decisionStrength = questionTypeDecision.strength,
            pageConfidence = page.confidence,
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
            pageType = page.pageType,
            pageTypeConfidence = page.confidence,
            pageTypeReason = page.reason,
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
            pageScores = page.scores,
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
        val withoutIndex = unified.replace(questionIndexPattern, "")
        return withoutIndex
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
            .map { (_, text) -> cleanText(text) }
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

    private fun inferActionKind(text: String): String? {
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
