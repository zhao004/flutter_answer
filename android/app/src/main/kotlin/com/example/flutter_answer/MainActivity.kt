package com.example.flutter_answer

import com.example.flutter_answer.agent.AccessibilityAgent
import com.example.flutter_answer.agent.AgentEventBus
import com.example.flutter_answer.agent.AgentRuntime
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val methodChannelName = "flutter_answer/agent_methods"
    private val eventChannelName = "flutter_answer/agent_events"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannelName)
            .setStreamHandler(AgentEventBus)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler(::handleMethodCall)
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getServiceStatus" -> result.success(AccessibilityAgent.getServiceStatus())
            "openAccessibilitySettings" -> {
                result.success(AccessibilityAgent.openAccessibilitySettings(this))
            }

            "getUiSnapshot" -> result.success(AccessibilityAgent.getUiSnapshot())
            "getQuestionContext" -> result.success(AccessibilityAgent.getQuestionContext())
            "getAgentConfig" -> result.success(AgentRuntime.getConfig())
            "getAgentRunStatus" -> result.success(AgentRuntime.getRunStatus())
            "scrollScreen" -> {
                val direction = call.argument<String>("direction")
                val amount = call.argument<String>("amount")
                result.success(AccessibilityAgent.scrollScreen(direction, amount))
            }

            "pressGlobal" -> {
                val action = call.argument<String>("action")
                result.success(AccessibilityAgent.pressGlobal(action))
            }

            "waitForIdle" -> {
                val timeoutMs = call.argument<Int>("timeoutMs") ?: 1200
                Thread {
                    val payload = AccessibilityAgent.waitForIdle(timeoutMs)
                    runOnUiThread { result.success(payload) }
                }.start()
            }

            "setAgentConfig" -> {
                result.success(
                    AgentRuntime.setConfig(
                        providerUrl = call.argument<String>("providerUrl"),
                        apiKey = call.argument<String>("apiKey"),
                        model = call.argument<String>("model"),
                        taskPrompt = call.argument<String>("taskPrompt"),
                        autoSubmit = call.argument<Boolean>("autoSubmit"),
                    ),
                )
            }

            "startAgentRun" -> result.success(AgentRuntime.startRun())
            "stopAgentRun" -> result.success(AgentRuntime.stopRun())
            "sendConnectivityProbe" -> {
                val messages = call.argument<List<*>>("messages") ?: emptyList<Any>()
                Thread {
                    val payload = AgentRuntime.sendConnectivityProbe(messages)
                    runOnUiThread { result.success(payload) }
                }.start()
            }

            else -> result.notImplemented()
        }
    }
}
