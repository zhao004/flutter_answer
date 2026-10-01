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
                name = "get_page_context",
                description = "Analyze the current screen structure and return context summary plus region segmentation for title, question, options, input, footer, and popup areas.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "inspection",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.getPageContext()
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "get_question_context",
                description = "Extract a structured question context from the current screen without relying on fixed page classification. Prefer this before get_ui_snapshot.",
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
                name = "get_recognition_history",
                description = "Return recent recognition traces and question fingerprint cache for replay and diagnosis.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "limit" to integerSchema(
                            description = "Maximum number of records to return.",
                            minimum = 1,
                            maximum = 100,
                        ),
                    ),
                    required = emptyList(),
                ),
                idempotent = true,
                category = "diagnostic",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.getRecognitionHistory(arguments.int("limit"))
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "reset_answer_cache",
                description = "Clear the in-memory exact question answer cache before starting a new Agent run.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "diagnostic",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                QuestionFingerprintCache.clear()
                return mapOf(
                    "ok" to true,
                    "message" to "会话答案缓存已清空。",
                )
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
                name = "apply_answer_plan",
                description = "Apply a validated structured answer plan, verify all fields, and optionally advance to the next question. Never clicks submit or confirm actions.",
                inputSchema = objectSchema(
                    properties = mapOf(
                        "fingerprint" to stringSchema(
                            description = "The question fingerprint returned by get_question_context.",
                        ),
                        "selectedOptionLabels" to arraySchema(
                            description = "Complete set of option labels that should be selected.",
                            items = stringSchema("A stable option label such as A, B, or 1."),
                        ),
                        "blankAnswers" to arraySchema(
                            description = "Answers for all fill-in-the-blank inputs.",
                            items = objectSchema(
                                properties = mapOf(
                                    "order" to integerSchema(
                                        description = "One-based blank order from the question context.",
                                        minimum = 1,
                                        maximum = 100,
                                    ),
                                    "text" to stringSchema("The answer text for this blank."),
                                ),
                                required = listOf("order", "text"),
                            ),
                        ),
                        "confidence" to numberSchema(
                            description = "Model confidence for the answer plan.",
                            minimum = 0.0,
                            maximum = 1.0,
                        ),
                        "advance" to booleanSchema(
                            description = "Whether to click the next-question action after verification.",
                        ),
                    ),
                    required = listOf("fingerprint", "selectedOptionLabels", "blankAnswers", "advance"),
                ),
                atomic = false,
                category = "action",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.applyAnswerPlan(
                    fingerprint = arguments.string("fingerprint"),
                    selectedOptionLabels = arguments.stringList("selectedOptionLabels"),
                    blankAnswers = arguments.blankAnswerList("blankAnswers"),
                    confidence = arguments.double("confidence"),
                    advance = arguments.boolean("advance"),
                )
            }
        },
        object : NativeTool {
            override val definition = NativeToolDefinition(
                name = "capture_question_image",
                description = "Capture and crop the current question area as a base64 JPEG for multimodal reasoning. Use only when structured context reports needsVisual=true.",
                inputSchema = emptySchema(),
                idempotent = true,
                category = "inspection",
            )

            override fun execute(arguments: Map<String, Any?>): Map<String, Any?> {
                return AccessibilityAgent.captureQuestionImage()
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
        val startedAt = currentTimeMs()
        val tool = toolsByName[toolName]
        if (tool == null) {
            val payload = mapOf(
                "ok" to false,
                "code" to "unknown_tool",
                "message" to "未知工具：$toolName",
            )
            return NativeToolResult(
                requestId = requestId,
                tool = toolName,
                ok = false,
                message = payload["message"]?.toString(),
                result = payload,
                startedAt = startedAt,
                finishedAt = currentTimeMs(),
            ).toMap()
        }

        val result = try {
            tool.execute(arguments)
        } catch (error: Throwable) {
            mapOf(
                "ok" to false,
                "code" to "tool_execution_failed",
                "message" to (error.message ?: "工具执行失败。"),
            )
        }
        return NativeToolResult(
            requestId = requestId,
            tool = toolName,
            ok = result["ok"] == true,
            message = result["message"]?.toString(),
            result = result,
            startedAt = startedAt,
            finishedAt = currentTimeMs(),
        ).toMap()
    }

    private fun currentTimeMs(): Long {
        // Android 运行时使用单调时钟；JVM 单测没有 mock SystemClock 时退回墙钟，避免测试环境异常。
        return runCatching {
            SystemClock.uptimeMillis()
        }.getOrElse {
            System.currentTimeMillis()
        }
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

    private fun arraySchema(
        description: String,
        items: Map<String, Any?>,
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "array",
            "description" to description,
            "items" to items,
        )
    }

    private fun numberSchema(
        description: String,
        minimum: Double,
        maximum: Double,
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "number",
            "description" to description,
            "minimum" to minimum,
            "maximum" to maximum,
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

    private fun Map<String, Any?>.stringList(key: String): List<String>? {
        val value = this[key] as? List<*> ?: return null
        return value.mapNotNull { item -> item?.toString() }
    }

    private fun Map<String, Any?>.blankAnswerList(key: String): List<BlankAnswer>? {
        val value = this[key] as? List<*> ?: return null
        val result = mutableListOf<BlankAnswer>()
        value.forEach { item ->
            val map = item as? Map<*, *> ?: return null
            val order = (map["order"] as? Number)?.toInt()
                ?: map["order"]?.toString()?.toIntOrNull()
                ?: return null
            val text = map["text"]?.toString() ?: return null
            result += BlankAnswer(order = order, text = text)
        }
        return result
    }

    private fun Map<String, Any?>.double(key: String): Double? {
        val value = this[key]
        return when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
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
