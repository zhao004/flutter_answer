package com.example.flutter_answer.agent

data class NativeToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val atomic: Boolean = true,
    val idempotent: Boolean = false,
    val category: String = "action",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to inputSchema,
        "atomic" to atomic,
        "idempotent" to idempotent,
        "category" to category,
    )
}

data class NativeToolResult(
    val requestId: String,
    val tool: String,
    val ok: Boolean,
    val message: String?,
    val result: Map<String, Any?>,
    val startedAt: Long,
    val finishedAt: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "requestId" to requestId,
        "tool" to tool,
        "ok" to ok,
        "message" to (message ?: result["message"]?.toString()),
        "result" to result,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "durationMs" to (finishedAt - startedAt),
    )
}

interface NativeTool {
    val definition: NativeToolDefinition

    fun execute(arguments: Map<String, Any?>): Map<String, Any?>
}
