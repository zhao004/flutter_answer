package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionFingerprintCacheTest {

    @Test
    fun `stores and reuses exact answer plan in session`() {
        val fingerprint = "qf_test_${System.nanoTime()}"
        val plan = AnswerPlan(
            fingerprint = fingerprint,
            selectedOptionLabels = listOf("A", "C"),
            blankAnswers = listOf(BlankAnswer(order = 1, text = "flutter")),
            confidence = 0.92,
        )

        assertFalse(QuestionFingerprintCache.record(fingerprint).hit)
        QuestionFingerprintCache.store(fingerprint, plan)

        val probe = QuestionFingerprintCache.record(fingerprint)
        assertTrue(probe.hit)
        assertEquals(listOf("A", "C"), probe.answerPlan?.selectedOptionLabels)
        assertEquals("flutter", probe.answerPlan?.blankAnswers?.first()?.text)
        assertEquals("cache", probe.answerPlan?.source)

        QuestionFingerprintCache.invalidate(fingerprint)
        val invalidated = QuestionFingerprintCache.record(fingerprint)
        assertFalse(invalidated.hit)
        assertNull(invalidated.answerPlan)
    }

    @Test
    fun `does not store plan that requires manual review`() {
        val fingerprint = "qf_manual_${System.nanoTime()}"
        QuestionFingerprintCache.store(
            fingerprint,
            AnswerPlan(
                fingerprint = fingerprint,
                selectedOptionLabels = emptyList(),
                blankAnswers = emptyList(),
                confidence = 0.4,
                needsManualReview = true,
            ),
        )

        assertFalse(QuestionFingerprintCache.record(fingerprint).hit)
    }
}
