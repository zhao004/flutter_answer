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

data class QuestionOption(
    val id: String,
    val label: String,
    val text: String,
    val checked: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "label" to label,
        "text" to text,
        "checked" to checked,
    )
}

data class InputFieldRef(
    val id: String,
    val text: String?,
    val desc: String?,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "text" to text,
        "desc" to desc,
    )
}

data class QuestionAction(
    val id: String,
    val text: String,
    val kind: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "text" to text,
        "kind" to kind,
    )
}

data class QuestionContext(
    val screenPackage: String?,
    val questionType: String,
    val questionTypeLabel: String,
    val questionTypeReason: String,
    val question: String,
    val options: List<QuestionOption>,
    val inputFields: List<InputFieldRef>,
    val actions: List<QuestionAction>,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "screenPackage" to screenPackage,
        "questionType" to questionType,
        "questionTypeLabel" to questionTypeLabel,
        "questionTypeReason" to questionTypeReason,
        "question" to question,
        "options" to options.map(QuestionOption::toMap),
        "inputFields" to inputFields.map(InputFieldRef::toMap),
        "actions" to actions.map(QuestionAction::toMap),
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
)
