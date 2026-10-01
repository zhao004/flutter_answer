package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolRegistryTest {

    @Test
    fun `listDefinitions exposes known native tools`() {
        val definitions = NativeToolRegistry.listDefinitions()
        val toolNames = definitions.mapNotNull { it["name"]?.toString() }.toSet()

        assertTrue(toolNames.contains("get_service_status"))
        assertTrue(toolNames.contains("get_question_context"))
        assertTrue(toolNames.contains("apply_answer_plan"))
        assertTrue(toolNames.contains("reset_answer_cache"))
        assertTrue(toolNames.contains("capture_question_image"))
        assertTrue(toolNames.contains("press_global"))
    }

    @Test
    fun `apply answer plan rejects missing fingerprint before touching UI`() {
        val payload = NativeToolRegistry.execute(
            requestId = "req_apply",
            toolName = "apply_answer_plan",
            arguments = mapOf(
                "selectedOptionLabels" to emptyList<String>(),
                "blankAnswers" to emptyList<Map<String, Any?>>(),
                "advance" to true,
            ),
        )

        assertFalse(payload["ok"] as Boolean)
        val result = payload["result"] as Map<*, *>
        assertEquals("invalid_arguments", result["code"])
    }

    @Test
    fun `execute returns structured failure for unknown tool`() {
        val payload = NativeToolRegistry.execute(
            requestId = "req_unknown",
            toolName = "not_exists",
            arguments = emptyMap(),
        )

        assertFalse(payload["ok"] as Boolean)
        assertEquals("not_exists", payload["tool"])
        val result = payload["result"] as Map<*, *>
        assertEquals("unknown_tool", result["code"])
    }
}
