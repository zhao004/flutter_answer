package com.example.flutter_answer.agent

/**
 * 单个填空的目标值。
 *
 * order 使用题目中可见的填空顺序，避免模型返回与当前页面节点 id 绑定的结果。
 */
data class BlankAnswer(
    val order: Int,
    val text: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "order" to order,
        "text" to text,
    )
}

/**
 * 结构化答案计划。
 *
 * 选项使用语义标签而不是节点 id，缓存复用时由原生层重新映射到当前页面的可操作节点。
 */
data class AnswerPlan(
    val fingerprint: String,
    val selectedOptionLabels: List<String>,
    val blankAnswers: List<BlankAnswer>,
    val confidence: Double,
    val needsManualReview: Boolean = false,
    val source: String = "model",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "fingerprint" to fingerprint,
        "selectedOptionLabels" to selectedOptionLabels,
        "blankAnswers" to blankAnswers.map(BlankAnswer::toMap),
        "confidence" to confidence,
        "needsManualReview" to needsManualReview,
        "source" to source,
    )
}
