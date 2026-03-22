package com.example.flutter_answer.agent

import android.os.SystemClock

object NativeToolRegistry {

    private val tools: List<NativeTool> = listOf(
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "get_service_status",
                description = "Inspect whether the Android accessibility service is enabled and whether an active window is available.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "diagnostic",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.getServiceStatus()
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "get_question_context",
                description = "Extract a structured question context from the current screen. Prefer this before get_ui_snapshot.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "inspection",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.getQuestionContext()
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "get_ui_snapshot",
                description = "Capture the current UI hierarchy with element ids, bounds, and capabilities.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "inspection",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.getUiSnapshot()
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "click_element",
                description = "Click the UI element identified by elementId from the latest context or snapshot.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "elementId" to stringSchema(
                            description = "The element id captured from get_question_context or get_ui_snapshot.",
                        ),
                    ),
                    required = listOf("elementId"),
                ),
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.clickElement(arguments.string("elementId"))
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "toggle_element",
                description = "Toggle a checkable element to the requested checked state.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "elementId" to stringSchema(
                            description = "The target element id captured from the latest context.",
                        ),
                        "targetChecked" to booleanSchema(
                            description = "The desired checked state.",
                        ),
                    ),
                    required = listOf("elementId", "targetChecked"),
                ),
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.toggleElement(
                    arguments.string("elementId"),
                    arguments.boolean("targetChecked"),
                )
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "set_text",
                description = "Fill a text input identified by elementId with the provided text value.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "elementId" to stringSchema(
                            description = "The editable element id from the latest context.",
                        ),
                        "text" to stringSchema(
                            description = "The text to input.",
                        ),
                    ),
                    required = listOf("elementId", "text"),
                ),
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.setText(
                    arguments.string("elementId"),
                    arguments.string("text"),
                )
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "wait_for_idle",
                description = "Wait until the current Android window is stable before reading the UI again.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "timeoutMs" to integerSchema(
                            description = "Maximum wait time in milliseconds.",
                            minimum = 200,
                            maximum = 5000,
                        ),
                    ),
                    required = listOf("timeoutMs"),
                ),
                idempotent = true,
                category = "synchronization",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.waitForIdle(arguments.int("timeoutMs") ?: 1200)
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "scroll_screen",
                description = "Scroll the current screen in the requested direction and amount.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "direction" to enumSchema(
                            description = "The scroll direction.",
                            values = listOf("up", "down", "left", "right"),
                        ),
                        "amount" to enumSchema(
                            description = "The scroll distance bucket.",
                            values = listOf("small", "medium", "large"),
                        ),
                    ),
                    required = listOf("direction", "amount"),
                ),
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.scrollScreen(
                    arguments.string("direction"),
                    arguments.string("amount"),
                )
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "press_global",
                description = "Trigger an Android global action such as back, home, recents, notifications, or quick_settings.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "action" to enumSchema(
                            description = "The Android global action to perform.",
                            values = listOf("back", "home", "recents", "notifications", "quick_settings"),
                        ),
                    ),
                    required = listOf("action"),
                ),
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.pressGlobal(arguments.string("action"))
            }
        },
    )

    private val toolsByName = tools.associateBy { it.definition.name }

    fun listDefinitions(): List<Map<String, Any?>> {
        return tools.map { it.definition.toMap() }
    }

    fun execute(
        requestId: String,
        toolName: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?> {
        val startedAt = SystemClock.uptimeMillis()
        val tool = toolsByName[toolName]
        if (tool == null) {
            val payload = mapOf(
                "ok" to false,
                "code" to "unknown_tool",
                "message" to "Unknown tool: $toolName",
            )
            return NativeToolResult(
                requestId = requestId,
                tool = toolName,
                ok = false,
                message = payload["message"]?.toString(),
                result = payload,
                startedAt = startedAt,
                finishedAt = SystemClock.uptimeMillis(),
            ).toMap()
        }

        val result = tool.execute(arguments)
        return NativeToolResult(
            requestId = requestId,
            tool = toolName,
            ok = result["ok"] == true,
            message = result["message"]?.toString(),
            result = result,
            startedAt = startedAt,
            finishedAt = SystemClock.uptimeMillis(),
        ).toMap()
    }

    private fun emptySchema(): Map<String, Any?> {
        return objectSchema(properties = emptyMap(), required = emptyList())
    }

    private fun objectSchema(
        properties: Map<String, Any?>,
        required: List<String>,
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    private fun stringSchema(description: String): Map<String, Any?> {
        return linkedMapOf(
            "type" to "string",
            "description" to description,
        )
    }

    private fun booleanSchema(description: String): Map<String, Any?> {
        return linkedMapOf(
            "type" to "boolean",
            "description" to description,
        )
    }

    private fun integerSchema(
        description: String,
        minimum: Int,
        maximum: Int,
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "integer",
            "description" to description,
            "minimum" to minimum,
            "maximum" to maximum,
        )
    }

    private fun enumSchema(
        description: String,
        values: List<String>,
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "string",
            "description" to description,
            "enum" to values,
        )
    }

    private fun Map<String, Any?>.string(key: String): String? {
        return this[key]?.toString()
    }

    private fun Map<String, Any?>.int(key: String): Int? {
        val value = this[key]
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull()
        }
    }

    private fun Map<String, Any?>.boolean(key: String): Boolean? {
        val value = this[key]
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString()?.lowercase()?.let { normalized ->
                when (normalized) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
            }
        }
    }
}
