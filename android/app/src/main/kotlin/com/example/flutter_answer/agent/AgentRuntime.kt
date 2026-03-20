package com.example.flutter_answer.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

data class AgentConfig(
    val providerUrl: String = "",
    val apiKey: String = "",
    val model: String = "gpt-5-mini",
    val taskPrompt: String = "读取当前答题页面并继续作答，优先使用工具，不要输出解释。",
    val autoSubmit: Boolean = false,
) {
    fun toPublicMap(): Map<String, Any?> = mapOf(
        "providerUrl" to providerUrl,
        "hasApiKey" to apiKey.isNotBlank(),
        "model" to model,
        "taskPrompt" to taskPrompt,
        "autoSubmit" to autoSubmit,
    )
}

data class AgentRunStatus(
    val running: Boolean = false,
    val status: String = "idle",
    val step: Int = 0,
    val message: String = "Idle.",
    val lastTool: String? = null,
    val lastModelText: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "running" to running,
        "status" to status,
        "step" to step,
        "message" to message,
        "lastTool" to lastTool,
        "lastModelText" to lastModelText,
        "updatedAt" to updatedAt,
    )
}

object AgentRuntime {

    private const val maxSteps = 12
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var config = AgentConfig()

    @Volatile
    private var runStatus = AgentRunStatus()

    @Volatile
    private var stopRequested = false

    fun getConfig(): Map<String, Any?> {
        return synchronized(this) { config.toPublicMap() }
    }

    fun setConfig(
        providerUrl: String?,
        apiKey: String?,
        model: String?,
        taskPrompt: String?,
        autoSubmit: Boolean?,
    ): Map<String, Any?> {
        synchronized(this) {
            val current = config
            config = config.copy(
                providerUrl = providerUrl?.trim().orEmpty(),
                apiKey = apiKey?.trim() ?: current.apiKey,
                model = model?.trim().takeUnless { it.isNullOrBlank() } ?: current.model,
                taskPrompt = taskPrompt?.trim().takeUnless { it.isNullOrBlank() } ?: current.taskPrompt,
                autoSubmit = autoSubmit ?: current.autoSubmit,
            )
        }
        val publicConfig = getConfig()
        AgentEventBus.log("info", "Agent config updated.")
        return mapOf(
            "ok" to true,
            "config" to publicConfig,
        )
    }

    fun getRunStatus(): Map<String, Any?> {
        return synchronized(this) { runStatus.toMap() }
    }

    fun startRun(): Map<String, Any?> {
        val activeConfig = synchronized(this) { config }
        synchronized(this) {
            if (runStatus.running) {
                return mapOf(
                    "ok" to false,
                    "message" to "Agent is already running.",
                    "status" to runStatus.toMap(),
                )
            }
            if (activeConfig.apiKey.isBlank()) {
                return mapOf(
                    "ok" to false,
                    "message" to "Set an OpenAI API key before starting the agent.",
                    "status" to runStatus.toMap(),
                )
            }
            if (activeConfig.providerUrl.isBlank()) {
                return mapOf(
                    "ok" to false,
                    "message" to "Set a provider URL before starting the agent.",
                    "status" to runStatus.toMap(),
                )
            }
            stopRequested = false
        }

        updateRunStatus(
            AgentRunStatus(
                running = true,
                status = "starting",
                step = 0,
                message = "Agent run started.",
                lastTool = null,
                lastModelText = null,
            ),
        )
        AgentEventBus.log("info", "Agent run started with model ${activeConfig.model}.")

        executor.execute {
            runLoop(activeConfig)
        }

        return mapOf(
            "ok" to true,
            "status" to getRunStatus(),
        )
    }

    fun stopRun(): Map<String, Any?> {
        synchronized(this) {
            if (!runStatus.running) {
                return mapOf(
                    "ok" to true,
                    "message" to "Agent is not running.",
                    "status" to runStatus.toMap(),
                )
            }
            stopRequested = true
        }

        updateRunStatus(
            runStatus.copy(
                status = "stopping",
                message = "Stop requested by operator.",
                updatedAt = System.currentTimeMillis(),
            ),
        )

        return mapOf(
            "ok" to true,
            "status" to getRunStatus(),
        )
    }

    private fun runLoop(activeConfig: AgentConfig) {
        val input = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("content", activeConfig.taskPrompt),
        )

        try {
            repeat(maxSteps) { index ->
                if (stopRequested) {
                    finishRun("stopped", "Stopped by operator.")
                    return
                }

                val step = index + 1
                updateRunStatus(
                    runStatus.copy(
                        running = true,
                        status = "thinking",
                        step = step,
                        message = "Waiting for model decision.",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )

                val response = OpenAiResponsesClient.createResponse(
                    providerUrl = activeConfig.providerUrl,
                    apiKey = activeConfig.apiKey,
                    model = activeConfig.model,
                    instructions = buildInstructions(activeConfig),
                    tools = toolDefinitions(),
                    input = input,
                )

                val output = response.optJSONArray("output") ?: JSONArray()
                val functionCalls = mutableListOf<JSONObject>()
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    input.put(JSONObject(item.toString()))
                    if (item.optString("type") == "function_call") {
                        functionCalls += item
                    }
                }

                val outputText = response.optString("output_text").ifBlank {
                    extractText(output)
                }
                if (outputText.isNotBlank()) {
                    updateRunStatus(
                        runStatus.copy(
                            lastModelText = outputText,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }

                if (functionCalls.isEmpty()) {
                    finishRun(
                        if (outputText.isNotBlank()) "completed" else "failed",
                        outputText.ifBlank { "Model returned no tool calls." },
                    )
                    return
                }

                for (call in functionCalls) {
                    if (stopRequested) {
                        finishRun("stopped", "Stopped by operator.")
                        return
                    }

                    val toolName = call.optString("name")
                    val callId = call.optString("call_id")
                    val arguments = parseArguments(call.optString("arguments"))

                    updateRunStatus(
                        runStatus.copy(
                            running = true,
                            status = "acting",
                            step = step,
                            message = "Executing tool: $toolName",
                            lastTool = toolName,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )

                    val result = dispatchTool(toolName, arguments)
                    input.put(
                        JSONObject()
                            .put("type", "function_call_output")
                            .put("call_id", callId)
                            .put("output", JSONObject(result).toString()),
                    )

                    if (toolName == "finish_task") {
                        return
                    }
                }
            }

            finishRun("failed", "Reached the step limit without finishing.")
        } catch (error: Exception) {
            AgentEventBus.log("error", error.message ?: error.toString())
            finishRun("failed", error.message ?: "Agent runtime crashed.")
        }
    }

    private fun dispatchTool(name: String, arguments: JSONObject): Map<String, Any?> {
        return when (name) {
            "get_question_context" -> AccessibilityAgent.getQuestionContext()
            "get_ui_snapshot" -> AccessibilityAgent.getUiSnapshot()
            "click_element" -> AccessibilityAgent.clickElement(arguments.optString("elementId"))
            "toggle_element" -> AccessibilityAgent.toggleElement(
                arguments.optString("elementId"),
                arguments.optBoolean("targetChecked"),
            )
            "set_text" -> AccessibilityAgent.setText(
                arguments.optString("elementId"),
                arguments.optString("text"),
            )

            "wait_for_idle" -> AccessibilityAgent.waitForIdle(arguments.optInt("timeoutMs", 1200))
            "scroll_screen" -> AccessibilityAgent.scrollScreen(
                arguments.optString("direction"),
                arguments.optString("amount"),
            )
            "press_global" -> AccessibilityAgent.pressGlobal(arguments.optString("action"))
            "log_message" -> handleLogMessage(arguments)
            "finish_task" -> handleFinishTask(arguments)
            else -> mapOf(
                "ok" to false,
                "message" to "Unknown tool: $name",
            )
        }
    }

    private fun handleLogMessage(arguments: JSONObject): Map<String, Any?> {
        val level = arguments.optString("level").ifBlank { "info" }
        val message = arguments.optString("message").ifBlank { "Agent log." }
        AgentEventBus.log(level, message)
        return mapOf(
            "ok" to true,
            "message" to message,
        )
    }

    private fun handleFinishTask(arguments: JSONObject): Map<String, Any?> {
        val status = arguments.optString("status").ifBlank { "success" }
        val reason = arguments.optString("reason").ifBlank { "Agent finished." }
        val terminalStatus = if (status == "success") "completed" else "failed"
        finishRun(terminalStatus, reason)
        return mapOf(
            "ok" to (status == "success"),
            "status" to status,
            "reason" to reason,
        )
    }

    private fun finishRun(status: String, message: String) {
        AgentEventBus.log(if (status == "failed") "error" else "info", message)
        updateRunStatus(
            runStatus.copy(
                running = false,
                status = status,
                message = message,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun updateRunStatus(status: AgentRunStatus) {
        synchronized(this) {
            runStatus = status
        }
        AgentEventBus.runtimeStatus(status.toMap())
    }

    private fun parseArguments(raw: String): JSONObject {
        if (raw.isBlank()) {
            return JSONObject()
        }
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            JSONObject()
        }
    }

    private fun extractText(output: JSONArray): String {
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") {
                continue
            }
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val entry = content.optJSONObject(j) ?: continue
                if (entry.optString("type") == "output_text") {
                    val text = entry.optString("text")
                    if (text.isNotBlank()) {
                        parts += text
                    }
                }
            }
        }
        return parts.joinToString(" ").trim()
    }

    private fun buildInstructions(config: AgentConfig): String {
        return """
            你是一个 Android 答题自动化代理。
            
            当前任务：${config.taskPrompt}
            自动提交：${if (config.autoSubmit) "已开启" else "未开启"}
            
            严格规则：
            - 你只能通过 tools 感知界面和执行操作。
            - 进入新页面后优先调用 get_question_context；只有结构化信息不够时才调用 get_ui_snapshot。
            - 点击、切换、输入、滚动或执行全局动作之后，下一次读界面前先调用 wait_for_idle。
            - 单选题只选一个最可能正确的答案。
            - 多选题只在题目明确说明多选时选择多个答案，优先使用 toggle_element。
            - 填空题直接填写简洁答案，不要解释。
            - 如果页面内容超出当前可见区域，可使用 scroll_screen。
            - 只有在需要返回上一层或离开错误页面时才使用 press_global。
            - 如果发现提交页且自动提交未开启，不要点击提交，直接调用 finish_task 并说明已到提交页。
            - 如果发现提交页且自动提交已开启，可以点击提交。
            - 如果工具失败，不要盲点，重新读取上下文再决定下一步。
            - 不要重复点击同一控件超过 2 次。
            - 如果连续 3 轮没有进展，调用 finish_task。
            - 最终结束时必须调用 finish_task。
            - 需要记录简短说明时使用 log_message。
        """.trimIndent()
    }

    private fun toolDefinitions(): JSONArray {
        return JSONArray()
            .put(functionTool("get_question_context", "获取当前页面中题干、选项、输入框和操作按钮等结构化信息", emptyParameters()))
            .put(functionTool("get_ui_snapshot", "获取当前前台窗口的压缩 UI 树快照", emptyParameters()))
            .put(
                functionTool(
                    "click_element",
                    "点击指定元素 id",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put("elementId", JSONObject().put("type", "string")))
                        .put("required", JSONArray().put("elementId"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "set_text",
                    "在指定输入框写入文本",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("elementId", JSONObject().put("type", "string"))
                                .put("text", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("elementId").put("text"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "toggle_element",
                    "将可勾选元素切换到目标选中状态，适合多选题或判断题",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("elementId", JSONObject().put("type", "string"))
                                .put("targetChecked", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray().put("elementId").put("targetChecked"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "wait_for_idle",
                    "等待页面稳定后再继续",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "timeoutMs",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 200)
                                    .put("maximum", 5000),
                            ),
                        )
                        .put("required", JSONArray().put("timeoutMs"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "scroll_screen",
                    "滚动当前页面，适合查找屏幕外的选项或按钮",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right")),
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("small").put("medium").put("large")),
                                ),
                        )
                        .put("required", JSONArray().put("direction").put("amount"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "press_global",
                    "执行 Android 全局动作，例如返回上一页",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("back")
                                            .put("home")
                                            .put("recents")
                                            .put("notifications")
                                            .put("quick_settings"),
                                    ),
                            ),
                        )
                        .put("required", JSONArray().put("action"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "log_message",
                    "输出一条简短运行日志",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "level",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("info").put("warn").put("error")),
                                )
                                .put("message", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("level").put("message"))
                        .put("additionalProperties", false),
                ),
            )
            .put(
                functionTool(
                    "finish_task",
                    "结束当前任务并写明原因",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "status",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("success").put("failed")),
                                )
                                .put("reason", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("status").put("reason"))
                        .put("additionalProperties", false),
                ),
            )
    }

    private fun emptyParameters(): JSONObject {
        return JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("required", JSONArray())
            .put("additionalProperties", false)
    }

    private fun functionTool(name: String, description: String, parameters: JSONObject): JSONObject {
        return JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("strict", true)
            .put("parameters", parameters)
    }
}
