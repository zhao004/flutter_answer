package com.example.flutter_answer.agent

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
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to label,
        "text" to text,
        "checked" to checked,
        "normalizedText" to normalizedText,
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
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "hit" to hit,
        "seenCount" to seenCount,
        "fingerprint" to fingerprint,
    )
}

data class QuestionContext(
    val screenPackage: String?,
    val contextSignature: String,
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
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "screenPackage" to screenPackage,
        "contextSignature" to contextSignature,
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
