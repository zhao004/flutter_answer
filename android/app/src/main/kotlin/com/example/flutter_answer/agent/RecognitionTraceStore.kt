package com.example.flutter_answer.agent

object RecognitionTraceStore {

    private const val maxTraceSize = 200
    private val traces = ArrayDeque<RecognitionTrace>()

    @Synchronized
    fun record(
        stage: String,
        contextTag: String,
        confidence: Double,
        ok: Boolean,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        if (traces.size >= maxTraceSize) {
            traces.removeFirst()
        }
        traces.addLast(
            RecognitionTrace(
                id = "trace_${System.nanoTime()}",
                atMs = System.currentTimeMillis(),
                stage = stage,
                contextTag = contextTag,
                confidence = confidence,
                ok = ok,
                message = message,
                payload = payload,
            ),
        )
    }

    @Synchronized
    fun latest(limit: Int): List<Map<String, Any?>> {
        val safeLimit = limit.coerceIn(1, 100)
        return traces.takeLast(safeLimit).map(RecognitionTrace::toMap)
    }
}

data class RecognitionTrace(
    val id: String,
    val atMs: Long,
    val stage: String,
    val contextTag: String,
    val confidence: Double,
    val ok: Boolean,
    val message: String,
    val payload: Map<String, Any?>,
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "atMs" to atMs,
            "stage" to stage,
            "contextTag" to contextTag,
            "confidence" to confidence,
            "ok" to ok,
            "message" to message,
            "payload" to payload,
        )
    }
}
