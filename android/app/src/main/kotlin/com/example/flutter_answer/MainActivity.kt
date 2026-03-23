package com.example.flutter_answer

import com.example.flutter_answer.agent.AccessibilityAgent
import com.example.flutter_answer.agent.AgentEventBus
import com.example.flutter_answer.agent.FlutterAgentPlatform
import com.example.flutter_answer.overlay.FlutterOverlayPlatform
import com.example.flutter_answer.overlay.OverlayEventBus
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "flutter_answer/agent_methods"
    private val eventChannelName = "flutter_answer/agent_events"
    private val overlayMethodChannelName = "flutter_answer/overlay_methods"
    private val overlayEventChannelName = "flutter_answer/overlay_events"
    private lateinit var flutterAgentPlatform: FlutterAgentPlatform
    private lateinit var flutterOverlayPlatform: FlutterOverlayPlatform

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterAgentPlatform = FlutterAgentPlatform(this)
        flutterOverlayPlatform = FlutterOverlayPlatform(this)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(AgentEventBus)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, overlayEventChannelName)
            .setStreamHandler(OverlayEventBus)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler(flutterAgentPlatform::handleMethodCall)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, overlayMethodChannelName)
            .setMethodCallHandler(flutterOverlayPlatform::handleMethodCall)
    }
}
