package com.example.flutter_answer.overlay

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object OverlayEventBus : EventChannel.StreamHandler {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backlog = ArrayDeque<Map<String, Any?>>()
    private val listeners = linkedMapOf<String, EventChannel.EventSink>()

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        val sink = events ?: return
        val clientId = arguments?.toString().takeUnless { it.isNullOrBlank() }
            ?: "overlay_${System.nanoTime()}"
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

    fun send(event: Map<String, Any?>) {
        if (listeners.isEmpty()) {
            if (backlog.size >= 60) {
                backlog.removeFirst()
            }
            backlog.addLast(event)
            return
        }

        mainHandler.post {
            listeners.values.forEach { sink ->
                sink.success(event)
            }
        }
    }

    private fun flushBacklog() {
        while (backlog.isNotEmpty()) {
            send(backlog.removeFirst())
        }
    }
}
