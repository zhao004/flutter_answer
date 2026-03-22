package com.example.flutter_answer.agent

object UiRegionLocator {

    private val optionPattern = Regex("^([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])[.、:：．\\)]")

    fun locate(snapshot: UiSnapshot, page: PageClassification): List<UiRegion> {
        if (snapshot.elements.isEmpty()) {
            return emptyList()
        }

        val screenWidth = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(2) ?: 0 } ?: 0
        val screenHeight = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(3) ?: 0 } ?: 0
        if (screenWidth <= 0 || screenHeight <= 0) {
            return emptyList()
        }

        val titleLimit = (screenHeight * 0.22).toInt()
        val footerStart = (screenHeight * 0.74).toInt()
        val centerLeft = (screenWidth * 0.15).toInt()
        val centerRight = (screenWidth * 0.85).toInt()
        val centerTop = (screenHeight * 0.18).toInt()
        val centerBottom = (screenHeight * 0.82).toInt()

        val titleNodes = snapshot.elements.filter { element ->
            val bottom = element.bounds.getOrNull(3) ?: 0
            bottom <= titleLimit && hasReadableText(element)
        }

        val footerNodes = snapshot.elements.filter { element ->
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            top >= footerStart && (element.clickable || hasReadableText(element))
        }

        val inputNodes = snapshot.elements.filter { it.editable }

        val optionNodes = snapshot.elements.filter { element ->
            val text = normalizeText(element.text)
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            val bottom = element.bounds.getOrNull(3) ?: 0
            val inMiddle = top > titleLimit && bottom < footerStart
            inMiddle && (element.checkable || optionPattern.containsMatchIn(text))
        }

        val dialogNodes = snapshot.elements.filter { element ->
            val left = element.bounds.getOrNull(0) ?: Int.MIN_VALUE
            val top = element.bounds.getOrNull(1) ?: Int.MIN_VALUE
            val right = element.bounds.getOrNull(2) ?: Int.MIN_VALUE
            val bottom = element.bounds.getOrNull(3) ?: Int.MIN_VALUE
            val inCenter = left >= centerLeft && right <= centerRight && top >= centerTop && bottom <= centerBottom
            inCenter && (hasReadableText(element) || element.clickable)
        }

        val optionIds = optionNodes.map(UiElement::id).toSet()
        val footerIds = footerNodes.map(UiElement::id).toSet()
        val titleIds = titleNodes.map(UiElement::id).toSet()
        val questionNodes = snapshot.elements.filter { element ->
            if (!hasReadableText(element)) {
                return@filter false
            }
            if (element.id in optionIds || element.id in footerIds || element.id in titleIds) {
                return@filter false
            }
            val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
            top in titleLimit..footerStart
        }

        val regions = mutableListOf<UiRegion>()
        addRegion(regions, "header", titleNodes, 0.82)
        addRegion(regions, "question", questionNodes, if (page.pageType == PageClassifier.QUESTION) 0.86 else 0.62)
        addRegion(regions, "options", optionNodes, if (optionNodes.isNotEmpty()) 0.9 else 0.0)
        addRegion(regions, "input", inputNodes, if (inputNodes.isNotEmpty()) 0.9 else 0.0)
        addRegion(regions, "footer_actions", footerNodes, if (footerNodes.isNotEmpty()) 0.84 else 0.0)
        if (page.pageType == PageClassifier.SUBMIT_CONFIRM_DIALOG || dialogNodes.size >= 4) {
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

    private fun normalizeText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            .orEmpty()
    }
}
