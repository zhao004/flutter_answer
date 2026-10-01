package com.example.flutter_answer.agent

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object AgentEventBus : EventChannel.StreamHandler {

    // 使用惰性初始化，避免 JVM 单元测试在没有 Android Looper 时加载事件总线失败。
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val backlog = ArrayDeque<Map<String, Any?>>()
    private val listeners = linkedMapOf<String, EventChannel.EventSink>()

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        val sink = events ?: return
        val clientId = arguments?.toString().takeUnless { it.isNullOrBlank() }
            ?: "client_${System.nanoTime()}"
        listeners[clientId] = sink
        flushBacklog()
    }

    override fun onCancel(arguments: Any?) {
        val clientId = arguments?.toString()
        if (clientId != null) {
            listeners.remove(clientId)
        } else if (listeners.isNotEmpty()) {
            listeners.remove(listeners.keys.first())
        }
    }

    fun log(level: String, message: String) {
        send(
            mapOf(
                "type" to "log",
                "level" to level,
                "message" to message,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    fun toolResult(tool: String, ok: Boolean, payload: Map<String, Any?>) {
        send(
            mapOf(
                "type" to "tool_result",
                "tool" to tool,
                "ok" to ok,
                "payload" to payload,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    fun runtimeStatus(payload: Map<String, Any?>) {
        send(
            mapOf(
                "type" to "agent_status",
                "payload" to payload,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    fun serviceStatus(payload: Map<String, Any?>) {
        send(
            mapOf(
                "type" to "service_status",
                "payload" to payload,
                "timestamp" to System.currentTimeMillis(),
            ),
        )
    }

    private fun send(event: Map<String, Any?>) {
        if (listeners.isEmpty()) {
            if (backlog.size >= 50) {
                backlog.removeFirst()
            }
            backlog.addLast(event)
            return
        }

        runCatching {
            mainHandler.post {
                listeners.values.toList().forEach { sink ->
                    runCatching { sink.success(event) }
                }
            }
        }
    }

    private fun flushBacklog() {
        while (backlog.isNotEmpty()) {
            send(backlog.removeFirst())
        }
    }
}
