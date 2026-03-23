package com.example.flutter_answer.agent

object UiRegionLocator {

    private val optionPattern = Regex("^(?:选项\\s*)?([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])[.、:：．\\)]")
    private val blankIndexPattern = Regex("^第\\s*\\d{1,3}\\s*空[:：]?$")
    private val strictQuestionIndexPattern = Regex("^\\s*第\\s*\\d{1,4}\\s*题")
    private val questionTypePattern = Regex("^\\s*(?:\\d+[.、]\\s*)?(单选题|多选题|判断题|填空题|简答题|问答题|论述题)")
    private val actionKeywords = listOf("下一题", "下一步", "继续", "提交", "交卷", "确认", "取消", "返回")
    private val inputHintKeywords = listOf("请输入答案", "请填写答案", "未作答轻点两下来编辑", "轻点两下来编辑", "点击输入", "双击输入", "点击作答")
    private val editorChromeKeywords = listOf("上传附件", "拍照按钮", "录音按钮", "add", "photo", "voice")

    fun locate(snapshot: UiSnapshot): List<UiRegion> {
        if (snapshot.elements.isEmpty()) {
            return emptyList()
        }

        val screenWidth = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(2) ?: 0 } ?: 0
        val screenHeight = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(3) ?: 0 } ?: 0
        if (screenWidth <= 0 || screenHeight <= 0) {
            return emptyList()
        }

        val contentBand = resolveContentBand(snapshot.elements, screenHeight)
        val contentTop = contentBand?.top ?: (screenHeight * 0.18).toInt()
        val contentBottom = contentBand?.bottom ?: (screenHeight * 0.86).toInt()
        val contentHeight = (contentBottom - contentTop).coerceAtLeast((screenHeight * 0.32).toInt())
        val rawTitleLimit = (contentTop + maxOf((contentHeight * 0.18).toInt(), 180)).coerceAtMost(contentBottom)
        val firstQuestionContentTop = snapshot.elements
            .filter { element -> hasQuestionContentSignal(element) }
            .mapNotNull { element -> element.bounds.getOrNull(1) }
            .minOrNull()
        val titleLimit = firstQuestionContentTop
            ?.minus(8)
            ?.coerceAtLeast(contentTop + 80)
            ?.coerceAtMost(rawTitleLimit)
            ?: rawTitleLimit
        val footerSeedTop = snapshot.elements
            .filter { element -> hasFooterSignal(element) }
            .mapNotNull { element -> element.bounds.getOrNull(1) }
            .minOrNull()
        val footerStart = footerSeedTop
            ?.minus(48)
            ?.coerceAtLeast(contentTop + (contentHeight * 0.62).toInt())
            ?.coerceAtMost(contentBottom)
            ?: (contentTop + (contentHeight * 0.8).toInt()).coerceAtMost(contentBottom)
        val centerLeft = (screenWidth * 0.12).toInt()
        val centerRight = (screenWidth * 0.88).toInt()
        val centerTop = (screenHeight * 0.16).toInt()
        val centerBottom = (screenHeight * 0.82).toInt()

        val titleNodes = snapshot.elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            top >= contentTop && bottom <= titleLimit && hasReadableText(element)
        }

        val inputNodes = resolveInputNodes(snapshot.elements)

        val middleTextNodes = snapshot.elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            top >= titleLimit && bottom <= footerStart && hasReadableText(element)
        }
        val repeatedClassCount = middleTextNodes
            .map { element -> element.className.orEmpty() }
            .filter(String::isNotBlank)
            .groupingBy { className -> className }
            .eachCount()
        val optionSeedNodes = snapshot.elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            val text = normalizeText(element.text)
            val inMiddle = top >= titleLimit && bottom <= footerStart
            inMiddle && (element.checkable || optionPattern.containsMatchIn(text))
        }
        val optionTopHint = optionSeedNodes
            .mapNotNull { element -> element.bounds.getOrNull(1) }
            .minOrNull()

        val optionNodes = snapshot.elements.filter { element ->
            val text = normalizeText(element.text)
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            val inMiddle = top >= titleLimit && bottom <= footerStart
            if (!inMiddle) {
                return@filter false
            }
            if (strictQuestionIndexPattern.containsMatchIn(text) || isActionLikeText(text)) {
                return@filter false
            }
            if (element.checkable || optionPattern.containsMatchIn(text)) {
                return@filter true
            }
            if (text.isBlank()) {
                return@filter false
            }
            val classCount = repeatedClassCount[element.className.orEmpty()] ?: 0
            // 自定义样式题目通常无 checkable，退化为“重复类名 + 中部带状分布”判定。
            val inOptionBand = optionTopHint?.let { hint -> top >= hint - 24 } ?: (top >= screenHeight * 0.26)
            inOptionBand && classCount >= 2 && (element.clickable || classCount >= 3)
        }

        val rawDialogNodes = snapshot.elements.filter { element ->
            val left = element.bounds.getOrNull(0) ?: Int.MIN_VALUE
            val top = element.bounds.getOrNull(1) ?: Int.MIN_VALUE
            val right = element.bounds.getOrNull(2) ?: Int.MIN_VALUE
            val bottom = element.bounds.getOrNull(3) ?: Int.MIN_VALUE
            val inCenter = left >= centerLeft && right <= centerRight && top >= centerTop && bottom <= centerBottom
            val width = (right - left).coerceAtLeast(0)
            val height = (bottom - top).coerceAtLeast(0)
            val withinDialogSize = width <= (screenWidth * 0.78).toInt() && height <= (screenHeight * 0.42).toInt()
            inCenter && withinDialogSize && (hasReadableText(element) || element.clickable)
        }
        val popupDetected = rawDialogNodes.size >= 3 && rawDialogNodes.any { element ->
            isActionLikeText(normalizeText(element.text ?: element.desc))
        }
        val dialogNodes = if (popupDetected) rawDialogNodes else emptyList()
        val dialogIds = dialogNodes.map(UiElement::id).toSet()

        val footerNodes = snapshot.elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            top >= footerStart &&
                top <= contentBottom &&
                element.id !in dialogIds &&
                (element.clickable || hasReadableText(element))
        }

        val optionIds = optionNodes.map(UiElement::id).toSet()
        val inputIds = inputNodes.map(UiElement::id).toSet()
        val footerIds = footerNodes.map(UiElement::id).toSet()
        val titleIds = titleNodes.map(UiElement::id).toSet()
        val questionNodes = snapshot.elements.filter { element ->
            if (!hasReadableText(element)) {
                return@filter false
            }
            if (element.id in optionIds || element.id in inputIds || element.id in footerIds || element.id in titleIds || element.id in dialogIds) {
                return@filter false
            }
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val text = normalizeText(element.text ?: element.desc)
            top in contentTop..footerStart && !isQuestionMetaText(text)
        }

        val regions = mutableListOf<UiRegion>()
        addRegion(regions, "header", titleNodes, 0.82)
        addRegion(regions, "question", questionNodes, if (questionNodes.isNotEmpty()) 0.82 else 0.0)
        addRegion(regions, "options", optionNodes, if (optionNodes.isNotEmpty()) 0.9 else 0.0)
        addRegion(regions, "input", inputNodes, if (inputNodes.isNotEmpty()) 0.9 else 0.0)
        addRegion(regions, "footer_actions", footerNodes, if (footerNodes.isNotEmpty()) 0.84 else 0.0)
        if (dialogNodes.isNotEmpty()) {
            addRegion(regions, "popup", dialogNodes, 0.78)
        }

        return regions
    }

    private fun addRegion(
        output: MutableList<UiRegion>,
        region: String,
        nodes: List<UiElement>,
        confidence: Double,
    ) {
        if (nodes.isEmpty()) {
            return
        }
        val bounds = mergeBounds(nodes)
        val regionInfo = UiRegion(
            region = region,
            bounds = bounds,
            elementIds = nodes.map(UiElement::id),
            confidence = confidence.coerceIn(0.0, 0.99),
        )
        output += regionInfo
    }

    private fun mergeBounds(nodes: List<UiElement>): List<Int> {
        val left = nodes.minOfOrNull { it.bounds.getOrNull(0) ?: 0 } ?: 0
        val top = nodes.minOfOrNull { it.bounds.getOrNull(1) ?: 0 } ?: 0
        val right = nodes.maxOfOrNull { it.bounds.getOrNull(2) ?: 0 } ?: 0
        val bottom = nodes.maxOfOrNull { it.bounds.getOrNull(3) ?: 0 } ?: 0
        return listOf(left, top, right, bottom)
    }

    private fun hasReadableText(element: UiElement): Boolean {
        return normalizeText(element.text).isNotBlank() || normalizeText(element.desc).isNotBlank()
    }

    private fun hasFooterSignal(element: UiElement): Boolean {
        val text = normalizeText(element.text ?: element.desc)
        if (text.isBlank()) {
            return false
        }
        return isActionLikeText(text) || text.contains("答题卡") || strictQuestionIndexPattern.containsMatchIn(text)
    }

    private fun hasQuestionContentSignal(element: UiElement): Boolean {
        val text = normalizeText(element.text ?: element.desc)
        if (text.isBlank()) {
            return element.editable || element.checkable
        }
        return questionTypePattern.matches(text) ||
            text == "题干" ||
            text == "题目" ||
            text == "问题" ||
            blankIndexPattern.matches(text) ||
            optionPattern.containsMatchIn(text) ||
            element.editable ||
            element.checkable
    }

    private fun resolveInputNodes(elements: List<UiElement>): List<UiElement> {
        val editableNodes = elements.filter { element -> element.editable }
        if (editableNodes.isEmpty()) {
            return emptyList()
        }
        val inputTop = editableNodes.minOfOrNull { element -> element.bounds.getOrNull(1) ?: Int.MAX_VALUE } ?: return editableNodes
        val inputBottom = editableNodes.maxOfOrNull { element -> element.bounds.getOrNull(3) ?: 0 } ?: return editableNodes
        return elements.filter { element ->
            if (element.editable) {
                return@filter true
            }
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: Int.MIN_VALUE
            if (bottom < inputTop - 180 || top > inputBottom + 260) {
                return@filter false
            }
            val text = normalizeText(element.text ?: element.desc)
            val viewId = element.viewId.orEmpty()
            val className = element.className.orEmpty()
            isInputHelperText(text) ||
                viewId.contains("answer", ignoreCase = true) ||
                viewId.contains("editor", ignoreCase = true) ||
                viewId.contains("edui", ignoreCase = true) ||
                className.contains("EditText", ignoreCase = true) ||
                (className.contains("Image", ignoreCase = true) && text.isNotBlank())
        }
    }

    private fun isInputHelperText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        if (blankIndexPattern.matches(text)) {
            return true
        }
        return inputHintKeywords.any { keyword -> text.contains(keyword) } ||
            editorChromeKeywords.any { keyword -> text.equals(keyword, ignoreCase = true) || text.contains(keyword) }
    }

    private fun resolveContentBand(elements: List<UiElement>, screenHeight: Int): ContentBand? {
        val contentCandidates = elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            if (top == Int.MAX_VALUE || bottom <= top) {
                return@filter false
            }
            if (bottom < (screenHeight * 0.08).toInt() || top > (screenHeight * 0.95).toInt()) {
                return@filter false
            }
            hasReadableText(element) || element.editable || element.checkable
        }
        if (contentCandidates.isEmpty()) {
            return null
        }
        val top = contentCandidates.minOfOrNull { element -> element.bounds.getOrNull(1) ?: Int.MAX_VALUE } ?: return null
        val bottom = contentCandidates.maxOfOrNull { element -> element.bounds.getOrNull(3) ?: 0 } ?: return null
        if (bottom <= top) {
            return null
        }
        return ContentBand(top = top, bottom = bottom)
    }

    private fun isQuestionMetaText(text: String): Boolean {
        if (text.isBlank()) {
            return true
        }
        if (text == "题干" || text == "题目" || text == "问题") {
            return true
        }
        return questionTypePattern.matches(text)
    }

    private fun normalizeText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            .orEmpty()
    }

    private fun isActionLikeText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        return actionKeywords.any { keyword -> text.contains(keyword) }
    }

    private data class ContentBand(
        val top: Int,
        val bottom: Int,
    )
}
