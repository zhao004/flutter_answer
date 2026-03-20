package com.example.flutter_answer.agent

object QuestionExtractor {

    private val optionPattern = Regex("^([A-H])[.、:：．\\)]\\s*(.+)$")
    private val nextActionKeywords = listOf("下一题", "下一步", "继续", "下页", "完成")
    private val submitActionKeywords = listOf("提交", "交卷", "完成考试", "立即交卷")
    private val multipleKeywords = listOf("多选", "可多选")
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
            val match = optionPattern.find(text)
            if (match != null) {
                return@mapNotNull QuestionOption(
                    id = element.id,
                    label = match.groupValues[1],
                    text = match.groupValues[2].trim(),
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

        val submitOnlyPage = actions.isNotEmpty() && options.isEmpty() && inputs.isEmpty() && question.isBlank()

        val questionType = when {
            submitOnlyPage -> "submit_page"
            inputs.isNotEmpty() -> "fill_blank"
            multipleKeywords.any { question.contains(it) } -> "multiple_choice"
            booleanKeywords.any { question.contains(it) } || hasBooleanOptions -> "true_false"
            options.isNotEmpty() -> "single_choice"
            else -> "unknown"
        }

        return QuestionContext(
            screenPackage = snapshot.screen["packageName"]?.toString(),
            questionType = questionType,
            question = question,
            options = options,
            inputFields = inputs,
            actions = actions,
        )
    }

    private fun cleanText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            .orEmpty()
    }
}
