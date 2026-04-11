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
        assertTrue(toolNames.contains("press_global"))
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
