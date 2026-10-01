package com.example.flutter_answer.agent

object QuestionFingerprintCache {

    private const val maxSize = 400
    private val cache = LinkedHashMap<String, CacheEntry>(maxSize)

    /**
     * 记录题目指纹并返回本次是否命中以及命中的可复用答案计划。
     *
     * 这里只处理精确指纹命中；不同表述或不同选项集合的题目不会复用答案。
     */
    @Synchronized
    fun record(fingerprint: String): QuestionCacheProbe {
        if (fingerprint.isBlank()) {
            return QuestionCacheProbe(hit = false, seenCount = 0, fingerprint = "")
        }

        val now = System.currentTimeMillis()
        val existing = cache[fingerprint]
        if (existing != null) {
            existing.seenCount += 1
            existing.lastSeenAt = now
            return QuestionCacheProbe(
                hit = existing.answerPlan != null,
                seenCount = existing.seenCount,
                fingerprint = fingerprint,
                answerPlan = existing.answerPlan,
            )
        }

        ensureCapacity()
        cache[fingerprint] = CacheEntry(
            fingerprint = fingerprint,
            seenCount = 1,
            lastSeenAt = now,
            answerPlan = null,
        )
        return QuestionCacheProbe(hit = false, seenCount = 1, fingerprint = fingerprint)
    }

    /**
     * 在答案全部应用并校验成功后写入缓存；失败路径不得调用。
     */
    @Synchronized
    fun store(fingerprint: String, answerPlan: AnswerPlan) {
        if (fingerprint.isBlank() || answerPlan.fingerprint != fingerprint || answerPlan.needsManualReview) {
            return
        }
        val now = System.currentTimeMillis()
        val existing = cache[fingerprint]
        if (existing != null) {
            existing.answerPlan = answerPlan.copy(source = "cache")
            existing.lastSeenAt = now
            return
        }
        ensureCapacity()
        cache[fingerprint] = CacheEntry(
            fingerprint = fingerprint,
            seenCount = 1,
            lastSeenAt = now,
            answerPlan = answerPlan.copy(source = "cache"),
        )
    }

    /**
     * 清空当前进程内的会话答案缓存。
     *
     * 每次启动新的 Agent 运行时调用，避免跨运行复用旧题目的模型答案。
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun invalidate(fingerprint: String) {
        if (fingerprint.isBlank()) {
            return
        }
        cache.remove(fingerprint)
    }

    @Synchronized
    fun snapshot(limit: Int): List<Map<String, Any?>> {
        val safeLimit = limit.coerceIn(1, 100)
        return cache.values
            .sortedByDescending { it.lastSeenAt }
            .take(safeLimit)
            .map { entry ->
                mapOf(
                    "fingerprint" to entry.fingerprint,
                    "seenCount" to entry.seenCount,
                    "lastSeenAt" to entry.lastSeenAt,
                    "answerAvailable" to (entry.answerPlan != null),
                    "selectedOptionLabels" to entry.answerPlan?.selectedOptionLabels.orEmpty(),
                    "blankCount" to (entry.answerPlan?.blankAnswers?.size ?: 0),
                )
            }
    }

    private fun ensureCapacity() {
        while (cache.size >= maxSize) {
            val eldest = cache.entries.minByOrNull { (_, entry) -> entry.lastSeenAt }?.key ?: break
            cache.remove(eldest)
        }
    }

    private data class CacheEntry(
        val fingerprint: String,
        var seenCount: Int,
        var lastSeenAt: Long,
        var answerPlan: AnswerPlan?,
    )
}
