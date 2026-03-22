package com.example.flutter_answer.agent

object QuestionFingerprintCache {

    private const val maxSize = 400
    private val cache = LinkedHashMap<String, CacheEntry>(maxSize)

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
            return QuestionCacheProbe(hit = true, seenCount = existing.seenCount, fingerprint = fingerprint)
        }

        if (cache.size >= maxSize) {
            val eldest = cache.entries.minByOrNull { (_, entry) -> entry.lastSeenAt }?.key
            if (eldest != null) {
                cache.remove(eldest)
            }
        }

        cache[fingerprint] = CacheEntry(
            fingerprint = fingerprint,
            seenCount = 1,
            lastSeenAt = now,
        )
        return QuestionCacheProbe(hit = false, seenCount = 1, fingerprint = fingerprint)
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
                )
            }
    }

    private data class CacheEntry(
        val fingerprint: String,
        var seenCount: Int,
        var lastSeenAt: Long,
    )
}
