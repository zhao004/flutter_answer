package com.example.flutter_answer.agent

object QuestionPageStage {
    const val unknown = "unknown"
    const val resumeEntry = "resume_entry"
    const val question = "question"
    const val submitReady = "submit_ready"
    const val submitDialog = "submit_dialog"
    const val submitWarning = "submit_warning"
}

data class UiElement(
    val id: String,
    val text: String?,
    val desc: String?,
    val className: String?,
    val packageName: String?,
    val viewId: String?,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val bounds: List<Int>,
    val depth: Int,
    val parentId: String? = null,
    val visibleToUser: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val hintText: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "text" to text,
        "desc" to desc,
        "className" to className,
        "packageName" to packageName,
        "viewId" to viewId,
        "clickable" to clickable,
        "checkable" to checkable,
        "checked" to checked,
        "editable" to editable,
        "enabled" to enabled,
        "scrollable" to scrollable,
        "bounds" to bounds,
        "depth" to depth,
        "parentId" to parentId,
        "visibleToUser" to visibleToUser,
        "focused" to focused,
        "selected" to selected,
        "hintText" to hintText,
    )
}

data class UiSnapshot(
    val screen: Map<String, Any?>,
    val elements: List<UiElement>,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "screen" to screen,
        "elements" to elements.map(UiElement::toMap),
    )
}

data class UiRegion(
    val region: String,
    val bounds: List<Int>,
    val elementIds: List<String>,
    val confidence: Double,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "region" to region,
        "bounds" to bounds,
        "elementIds" to elementIds,
        "confidence" to confidence,
    )
}

data class QuestionOption(
    val id: String,
    val label: String,
    val text: String,
    val checked: Boolean,
    val normalizedText: String = text,
    /**
     * 页面是否只暴露了选项标签而没有暴露选项正文。
     *
     * 部分 WebView 题型只把 A/B/C/D 标签放进无障碍树，正文仅存在于视觉层；
     * 此时必须依赖截图补齐，不能把标签当作选项内容参与理解。
     */
    val contentMissing: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to label,
        "text" to text,
        "checked" to checked,
        "normalizedText" to normalizedText,
        "contentMissing" to contentMissing,
    )
}

data class InputFieldRef(
    val id: String,
    val text: String?,
    val desc: String?,
    val hint: String?,
    val order: Int?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "text" to text,
        "desc" to desc,
        "hint" to hint,
        "order" to order,
    )
}

data class QuestionAction(
    val id: String,
    val text: String,
    val kind: String,
    val riskLevel: String = "normal",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "text" to text,
        "kind" to kind,
        "riskLevel" to riskLevel,
    )
}

data class QuestionBlank(
    val index: Int,
    val hint: String,
    val inputId: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "index" to index,
        "hint" to hint,
        "inputId" to inputId,
    )
}

data class QuestionAttachment(
    val id: String,
    val type: String,
    val desc: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to type,
        "desc" to desc,
    )
}

data class QuestionCacheProbe(
    val hit: Boolean,
    val seenCount: Int,
    val fingerprint: String,
    val answerPlan: AnswerPlan? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "hit" to hit,
        "seenCount" to seenCount,
        "fingerprint" to fingerprint,
        "answerPlan" to answerPlan?.toMap(),
    )
}

data class QuestionContext(
    val contextSignature: String,
    val pageStage: String,
    val questionIndex: Int?,
    val question: String,
    val normalizedQuestion: String,
    val options: List<QuestionOption>,
    val inputFields: List<InputFieldRef>,
    val actions: List<QuestionAction>,
    val blanks: List<QuestionBlank>,
    val attachments: List<QuestionAttachment>,
    val required: Boolean,
    val confidence: Double,
    val structureConfidence: Double,
    val needConfirm: Boolean,
    val fingerprint: String,
    val cacheProbe: QuestionCacheProbe,
    val regions: List<UiRegion>,
    val structureSignals: Map<String, Double>,
    val fieldConfidence: Map<String, Double> = emptyMap(),
    val needsVisual: Boolean = false,
    val timings: Map<String, Long> = emptyMap(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "contextSignature" to contextSignature,
        "pageStage" to pageStage,
        "questionIndex" to questionIndex,
        "question" to question,
        "normalizedQuestion" to normalizedQuestion,
        "options" to options.map(QuestionOption::toMap),
        "inputFields" to inputFields.map(InputFieldRef::toMap),
        "actions" to actions.map(QuestionAction::toMap),
        "blanks" to blanks.map(QuestionBlank::toMap),
        "attachments" to attachments.map(QuestionAttachment::toMap),
        "required" to required,
        "confidence" to confidence,
        "structureConfidence" to structureConfidence,
        "needConfirm" to needConfirm,
        "fingerprint" to fingerprint,
        "cache" to cacheProbe.toMap(),
        "regions" to regions.map(UiRegion::toMap),
        "structureSignals" to structureSignals,
        "fieldConfidence" to fieldConfidence,
        "needsVisual" to needsVisual,
        "timings" to timings,
    )
}

data class NodeLocator(
    val text: String?,
    val desc: String?,
    val className: String?,
    val viewId: String?,
    val bounds: List<Int>,
    val clickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val scrollable: Boolean,
    val parentClassName: String? = null,
    val siblingTextHints: List<String> = emptyList(),
    val region: String? = null,
)
