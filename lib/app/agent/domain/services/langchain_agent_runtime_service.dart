import 'dart:convert';

import 'package:langchain/langchain.dart';
import 'package:langchain_openai/langchain_openai.dart';

import '../../data/agent_platform_gateway.dart';
import '../models/agent_config.dart';
import '../models/agent_run_state.dart';
import '../models/connectivity_probe_result.dart';
import '../models/native_tool_definition.dart';
import '../models/native_tool_execution.dart';
import 'provider_url_resolver.dart';

typedef AgentStatusCallback = void Function(AgentRunState state);
typedef AgentLogCallback = void Function(String level, String message);
typedef AgentToolResultCallback =
    void Function(NativeToolExecutionResult result);

class LangChainAgentRuntimeService {
  LangChainAgentRuntimeService({required AgentPlatformGateway platformGateway})
    : _platformGateway = platformGateway;

  final AgentPlatformGateway _platformGateway;

  bool _stopRequested = false;

  void requestStop() {
    _stopRequested = true;
  }

  Future<ConnectivityProbeResult> sendConnectivityProbe({
    required AgentConfig config,
    required List<Map<String, String>> messages,
  }) async {
    _validateConfig(config);
    final prompt = _buildConnectivityPrompt(messages);
    final startedAt = DateTime.now();

    try {
      final model = _buildModel(config);
      final response = await model.invoke(PromptValue.string(prompt));
      final reply = response.output.content.trim();
      final latencyMs = DateTime.now().difference(startedAt).inMilliseconds;

      if (reply.isEmpty) {
        return ConnectivityProbeResult(
          ok: false,
          message: 'Model returned an empty response.',
          reply: '',
          latencyMs: latencyMs,
          model: config.model,
          providerUrl: ProviderUrlResolver.resolveBaseUrl(config.providerUrl),
        );
      }

      return ConnectivityProbeResult(
        ok: true,
        message: 'Model connectivity is healthy.',
        reply: reply,
        latencyMs: latencyMs,
        model: config.model,
        providerUrl: ProviderUrlResolver.resolveBaseUrl(config.providerUrl),
      );
    } catch (error) {
      return ConnectivityProbeResult(
        ok: false,
        message: error.toString(),
        reply: '',
        latencyMs: DateTime.now().difference(startedAt).inMilliseconds,
        model: config.model,
        providerUrl: ProviderUrlResolver.resolveBaseUrl(config.providerUrl),
      );
    }
  }

  Future<AgentRunState> run({
    required AgentConfig config,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    _validateConfig(config);
    _stopRequested = false;

    final nativeTools = await _platformGateway.listTools();
    if (nativeTools.isEmpty) {
      throw StateError('No native tools are registered on the Android side.');
    }

    onLog(
      'info',
      'Loaded ${nativeTools.length} Android tools through the platform channel.',
    );

    var toolStep = 0;
    var latestState = AgentRunState.idle().copyWith(
      running: true,
      status: 'starting',
      message: 'LangChain agent is starting.',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    onStatus(latestState);

    try {
      final model = _buildModel(config);
      final tools = nativeTools
          .map(
            (definition) => _createTool(
              definition: definition,
              onToolInvoked: () {
                toolStep += 1;
                latestState = latestState.copyWith(
                  running: true,
                  status: 'acting',
                  step: toolStep,
                  message: 'Executing tool: ${definition.name}',
                  lastTool: definition.name,
                  updatedAt: DateTime.now().millisecondsSinceEpoch,
                );
                onStatus(latestState);
              },
              onToolCompleted: (result) {
                onToolResult(result);
                latestState = latestState.copyWith(
                  running: true,
                  status: 'thinking',
                  step: toolStep,
                  message: result.message?.isNotEmpty == true
                      ? result.message!
                      : 'Tool ${definition.name} completed.',
                  lastTool: definition.name,
                  updatedAt: DateTime.now().millisecondsSinceEpoch,
                );
                onStatus(latestState);
                onLog(
                  result.ok ? 'info' : 'warn',
                  '[${definition.name}] ${result.message ?? 'Tool finished.'}',
                );
              },
            ),
          )
          .toList(growable: false);

      final agent = ToolsAgent.fromLLMAndTools(llm: model, tools: tools);
      final executor = AgentExecutor(
        agent: agent,
        maxIterations: 12,
        maxExecutionTime: const Duration(minutes: 2),
      );

      latestState = latestState.copyWith(
        running: true,
        status: 'thinking',
        message: 'Waiting for the model to plan the next step.',
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(latestState);

      final output = await executor.run(_buildAgentTask(config, nativeTools));
      final finalText = output.trim();

      if (_stopRequested) {
        return AgentRunState(
          running: false,
          status: 'stopped',
          step: toolStep,
          message: 'Stopped by operator.',
          lastTool: latestState.lastTool,
          lastModelText: latestState.lastModelText,
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        );
      }

      final completed = AgentRunState(
        running: false,
        status: 'completed',
        step: toolStep,
        message: finalText.isEmpty ? 'Agent finished.' : finalText,
        lastTool: latestState.lastTool,
        lastModelText: finalText.isEmpty
            ? latestState.lastModelText
            : finalText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(completed);
      return completed;
    } on _AgentStoppedException {
      final stopped = AgentRunState(
        running: false,
        status: 'stopped',
        step: toolStep,
        message: 'Stopped by operator.',
        lastTool: latestState.lastTool,
        lastModelText: latestState.lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(stopped);
      return stopped;
    } catch (error) {
      final failed = AgentRunState(
        running: false,
        status: 'failed',
        step: toolStep,
        message: error.toString(),
        lastTool: latestState.lastTool,
        lastModelText: latestState.lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(failed);
      return failed;
    }
  }

  ChatOpenAI _buildModel(AgentConfig config) {
    final baseUrl = ProviderUrlResolver.resolveBaseUrl(config.providerUrl);
    final options = ChatOpenAIOptions(model: config.model, temperature: 0);
    if (baseUrl.isEmpty) {
      return ChatOpenAI(apiKey: config.apiKey, defaultOptions: options);
    }
    return ChatOpenAI(
      apiKey: config.apiKey,
      baseUrl: baseUrl,
      defaultOptions: options,
    );
  }

  Tool _createTool({
    required NativeToolDefinition definition,
    required void Function() onToolInvoked,
    required void Function(NativeToolExecutionResult result) onToolCompleted,
  }) {
    final tool = Tool.fromFunction<Map<String, dynamic>, String>(
      name: definition.name,
      description: definition.description,
      inputJsonSchema: definition.inputSchema,
      getInputFromJson: (Map<String, dynamic> json) => json,
      func: (Map<String, dynamic> input) async {
        if (_stopRequested) {
          throw const _AgentStoppedException();
        }

        onToolInvoked();
        final result = await _platformGateway.executeTool(
          NativeToolExecutionRequest(
            toolName: definition.name,
            arguments: input,
          ),
        );
        onToolCompleted(result);

        return jsonEncode(result.toMap());
      },
    );
    return tool;
  }

  String _buildAgentTask(AgentConfig config, List<NativeToolDefinition> tools) {
    final toolNames = tools.map((tool) => tool.name).join(', ');
    return '''
You are an Android automation agent running inside a Flutter control layer.

System architecture:
- The Android layer exposes atomic tools only.
- Flutter orchestrates state, context, and platform channel calls.
- You must observe the screen and act only through the provided tools.

Execution rules:
- Start by understanding the current page before taking action.
- Prefer `get_question_context` before `get_ui_snapshot`.
- After `click_element`, `toggle_element`, `set_text`, `scroll_screen`, or `press_global`, call `wait_for_idle` before reading the UI again.
- If a tool fails, re-read the current context before retrying.
- Keep actions minimal and deterministic.
- If auto submit is disabled, stop at the submit page and explain that manual confirmation is required.
- If auto submit is enabled, you may submit only when the submit intent is clear from the UI.
- Do not fabricate UI state. If you are unsure, call a tool.

Available Android tools:
$toolNames

Task:
${config.taskPrompt}

Auto submit:
${config.autoSubmit ? 'enabled' : 'disabled'}
''';
  }

  String _buildConnectivityPrompt(List<Map<String, String>> messages) {
    final buffer = StringBuffer()
      ..writeln(
        'You are validating model connectivity for an Android agent app.',
      )
      ..writeln('Reply briefly to the last user message.')
      ..writeln(
        'If the conversation is clearly a connectivity check, explicitly include "妯″瀷杩為€氭甯? in the reply.',
      )
      ..writeln()
      ..writeln('Conversation:');

    for (final message in messages) {
      final role = message['role']?.trim().isNotEmpty == true
          ? message['role']!.trim()
          : 'user';
      final content = message['content']?.trim() ?? '';
      if (content.isEmpty) {
        continue;
      }
      buffer.writeln('$role: $content');
    }

    return buffer.toString();
  }

  void _validateConfig(AgentConfig config) {
    if (config.providerUrl.trim().isEmpty) {
      throw StateError('Set a provider URL before running the agent.');
    }
    if (config.apiKey.trim().isEmpty) {
      throw StateError('Set an API key before running the agent.');
    }
  }
}

class _AgentStoppedException implements Exception {
  const _AgentStoppedException();
}
