package com.example.flutter_answer

import com.example.flutter_answer.agent.AccessibilityAgent
import com.example.flutter_answer.agent.AgentEventBus
import com.example.flutter_answer.agent.FlutterAgentPlatform
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "flutter_answer/agent_methods"
    private val eventChannelName = "flutter_answer/agent_events"
    private lateinit var flutterAgentPlatform: FlutterAgentPlatform

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterAgentPlatform = FlutterAgentPlatform(this)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(AgentEventBus)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler(flutterAgentPlatform::handleMethodCall)
    }
}
