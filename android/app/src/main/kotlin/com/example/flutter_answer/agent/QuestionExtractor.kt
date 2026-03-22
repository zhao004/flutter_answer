package com.example.flutter_answer.agent

object QuestionExtractor {

    private val alphaOptionPattern = Regex("^([A-H])[.、:：．\\)]\\s*(.+)$")
    private val digitOptionPattern = Regex("^(\\d{1,2})[.、:：．\\)]\\s*(.+)$")
    private val chineseOptionPattern = Regex("^[（(]?([一二三四五六七八九十])[)）.、]\\s*(.+)$")
    private val blankPlaceholderPattern = Regex("(_{2,}|\\(\\s*\\)|（\\s*）|\\[\\s*\\]|【\\s*】|\\{\\s*\\}|<\\s*>)")
    private val nextActionKeywords = listOf("下一题", "下一步", "继续", "下页", "完成")
    private val submitActionKeywords = listOf("提交", "交卷", "完成考试", "立即交卷")
    private val multipleKeywords = listOf("多选", "可多选", "不定项", "至少选", "至少选择", "可选择多项")
    private val singleKeywords = listOf("单选", "仅选一项", "只能选一项")
    private val shortAnswerKeywords = listOf("简答", "问答", "论述", "主观题", "作答", "简述", "说明", "分析", "请回答", "写出")
    private val fillBlankKeywords = listOf("填空", "填入", "补全", "填写", "空格", "横线处", "括号内")
    private val booleanKeywords = listOf("判断题", "对错", "正确", "错误")
    private val booleanOptionKeywords = listOf("正确", "错误", "对", "错", "是", "否")

    fun extract(snapshot: UiSnapshot): QuestionContext {
        val textElements = snapshot.elements.filter { !it.text.isNullOrBlank() }
        val elementById = snapshot.elements.associateBy(UiElement::id)
        val actions = snapshot.elements
            .filter { it.clickable }
            .mapNotNull { element ->
                val text = cleanText(element.text ?: element.desc).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = when {
                    submitActionKeywords.any { keyword -> text.contains(keyword) } -> "submit"
                    nextActionKeywords.any { keyword -> text.contains(keyword) } -> "next"
                    else -> null
                }
                if (kind == null) {
                    return@mapNotNull null
                }
                QuestionAction(id = element.id, text = text, kind = kind)
            }
            .distinctBy { it.id }

        val options = textElements.mapNotNull { element ->
            val text = cleanText(element.text)
            val matchedOption = matchOptionText(text)
            if (matchedOption != null) {
                val (label, optionText) = matchedOption
                return@mapNotNull QuestionOption(
                    id = element.id,
                    label = label,
                    text = optionText,
                    checked = element.checked,
                )
            }

            if (element.checkable && text.isNotBlank()) {
                return@mapNotNull QuestionOption(
                    id = element.id,
                    label = "",
                    text = text,
                    checked = element.checked,
                )
            }

            null
        }.distinctBy { it.id }

        val inputs = snapshot.elements
            .filter { it.editable }
            .map {
                InputFieldRef(
                    id = it.id,
                    text = it.text,
                    desc = it.desc,
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

        val question = textElements
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
            .take(3)
            .joinToString(separator = " ") { (_, text) -> text }
            .ifBlank {
                textElements
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
                    .firstOrNull()
                    ?.second
                    .orEmpty()
            }

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
        val normalizedQuestion = question.replace("\\s+".toRegex(), "")
        val hasMultipleKeyword = multipleKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasSingleKeyword = singleKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBooleanKeyword = booleanKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasShortAnswerKeyword = shortAnswerKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasFillBlankKeyword = fillBlankKeywords.any { keyword -> normalizedQuestion.contains(keyword) }
        val hasBlankPlaceholder = containsBlankPlaceholder(question)

        val submitOnlyPage = actions.isNotEmpty() && options.isEmpty() && inputs.isEmpty() && question.isBlank()

        // 题型识别按“强信号优先”的顺序执行，降低页面布局差异对识别结果的干扰。
        // 先判定交卷页/判断题，再判定选择题，最后判定填空与简答，避免控件噪声导致误判。
        val questionTypeDecision = when {
            submitOnlyPage -> QuestionTypeDecision(
                code = "submit_page",
                label = "交卷页",
                reason = "检测到提交/完成按钮，且没有题干、选项和输入框。",
            )
            hasBooleanKeyword || hasBooleanOptionPair -> QuestionTypeDecision(
                code = "true_false",
                label = "判断题",
                reason = if (hasBooleanKeyword) {
                    "题干包含判断题关键词。"
                } else {
                    "仅检测到 2 个选项，且都命中“对/错/是/否”等判断题特征。"
                },
            )
            hasMultipleKeyword -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "题干包含多选关键词。",
            )
            options.isNotEmpty() && checkedOptionCount >= 2 -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "当前已勾选 $checkedOptionCount 个选项，符合多选特征。",
            )
            hasSingleKeyword -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "题干包含单选关键词。",
            )
            options.isNotEmpty() && hasCheckboxOption && !hasRadioOption -> QuestionTypeDecision(
                code = "multiple_choice",
                label = "多选题",
                reason = "检测到选项控件为 CheckBox/CheckedTextView，且未检测到单选控件。",
            )
            options.isNotEmpty() && hasRadioOption -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "检测到 RadioButton 单选控件。",
            )
            (hasFillBlankKeyword || hasBlankPlaceholder) && options.isEmpty() -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = if (hasFillBlankKeyword) {
                    "题干包含填空关键词，且未检测到选项。"
                } else {
                    "题干命中空白占位符（如 ____ / （ ）），且未检测到选项。"
                },
            )
            inputs.size >= 2 -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = "检测到多个输入框，按填空场景处理。",
            )
            options.isNotEmpty() -> QuestionTypeDecision(
                code = "single_choice",
                label = "单选题",
                reason = "存在候选选项，且没有命中多选/判断强特征。",
            )
            inputs.isNotEmpty() && hasFillBlankKeyword -> QuestionTypeDecision(
                code = "fill_blank",
                label = "填空题",
                reason = "存在输入框且题干包含填空关键词。",
            )
            inputs.isNotEmpty() && hasShortAnswerKeyword -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "存在输入框且题干包含简答关键词。",
            )
            inputs.size == 1 -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "检测到单个输入框且没有选项控件，按简答场景处理。",
            )
            hasShortAnswerKeyword && options.isEmpty() -> QuestionTypeDecision(
                code = "short_answer",
                label = "简答题",
                reason = "题干包含简答关键词，且未检测到选项。",
            )
            hasBooleanOptions && options.size <= 3 -> QuestionTypeDecision(
                code = "true_false",
                label = "判断题",
                reason = "选项文本包含判断题特征词，且选项数量较少。",
            )
            else -> QuestionTypeDecision(
                code = "unknown",
                label = "未知题型",
                reason = "未检测到稳定的题型特征，请结合 UI 快照人工确认。",
            )
        }

        return QuestionContext(
            screenPackage = snapshot.screen["packageName"]?.toString(),
            questionType = questionTypeDecision.code,
            questionTypeLabel = questionTypeDecision.label,
            questionTypeReason = questionTypeDecision.reason,
            question = question,
            options = options,
            inputFields = inputs,
            actions = actions,
        )
    }

    private fun matchOptionText(text: String): Pair<String, String>? {
        val match = alphaOptionPattern.find(text)
            ?: digitOptionPattern.find(text)
            ?: chineseOptionPattern.find(text)
        if (match == null) {
            return null
        }
        val label = match.groupValues.getOrNull(1)?.trim().orEmpty()
        val optionText = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (label.isBlank() || optionText.isBlank()) {
            return null
        }
        return label to optionText
    }

    private fun cleanText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            .orEmpty()
    }

    private fun containsBlankPlaceholder(question: String): Boolean {
        if (question.isBlank()) {
            return false
        }
        // 一些考试系统不会暴露输入框，只会在题干中给出占位符，这里作为填空题兜底信号。
        return blankPlaceholderPattern.containsMatchIn(question)
    }

    private data class QuestionTypeDecision(
        val code: String,
        val label: String,
        val reason: String,
    )
}
