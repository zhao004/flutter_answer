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

  // 提高复杂场景容忍度，并配合重复调用熔断，降低误触发保护上限概率。
  static const int _executorMaxIterations = 260;
  static const Duration _executorMaxExecutionTime = Duration(minutes: 5);
  static const int _maxRepeatedSameToolCall = 8;
  static const int _maxNoProgressActionStreak = 12;
  static const String _executorLimitStopMessage =
      'Agent stopped due to iteration limit or time limit.';

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
          message: '模型返回了空内容。',
          reply: '',
          latencyMs: latencyMs,
          model: config.model,
          providerUrl: ProviderUrlResolver.resolveBaseUrl(config.providerUrl),
        );
      }

      return ConnectivityProbeResult(
        ok: true,
        message: '模型连通性正常。',
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
      throw StateError('Android 原生侧未注册可用工具，无法启动 Agent。');
    }

    onLog(
      'info',
      '已通过 Platform Channel 加载 ${nativeTools.length} 个 Android 工具。',
    );

    final actionToolNames = nativeTools
        .where((tool) => tool.category == 'action')
        .map((tool) => tool.name)
        .toSet();
    String? lastToolCallSignature;
    int repeatedToolCallStreak = 0;
    int noProgressActionStreak = 0;
    var toolStep = 0;
    var latestState = AgentRunState.idle().copyWith(
      running: true,
      status: 'starting',
      message: 'LangChain Agent 正在启动。',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    onStatus(latestState);

    try {
      final model = _buildModel(config);
      final tools = nativeTools
          .map(
            (definition) => _createTool(
              definition: definition,
              onToolInvoked: (callSignature) {
                if (lastToolCallSignature == callSignature) {
                  repeatedToolCallStreak += 1;
                } else {
                  lastToolCallSignature = callSignature;
                  repeatedToolCallStreak = 1;
                }
                if (repeatedToolCallStreak >= _maxRepeatedSameToolCall) {
                  throw _AgentStalledException(
                    _buildRepeatedCallMessage(
                      toolName: definition.name,
                      repeatCount: repeatedToolCallStreak,
                    ),
                  );
                }
                toolStep += 1;
                latestState = latestState.copyWith(
                  running: true,
                  status: 'acting',
                  step: toolStep,
                  message: '正在执行工具：${definition.name}',
                  lastTool: definition.name,
                  updatedAt: DateTime.now().millisecondsSinceEpoch,
                );
                onStatus(latestState);
              },
              onToolCompleted: (result, _) {
                onToolResult(result);
                if (_isNoProgressActionResult(
                  result: result,
                  actionToolNames: actionToolNames,
                )) {
                  noProgressActionStreak += 1;
                  if (noProgressActionStreak >= _maxNoProgressActionStreak) {
                    throw _AgentStalledException(
                      _buildNoProgressActionMessage(
                        streak: noProgressActionStreak,
                        lastTool: definition.name,
                      ),
                    );
                  }
                } else if (actionToolNames.contains(result.tool)) {
                  noProgressActionStreak = 0;
                }
                latestState = latestState.copyWith(
                  running: true,
                  status: 'thinking',
                  step: toolStep,
                  message: result.message?.isNotEmpty == true
                      ? result.message!
                      : '工具 ${definition.name} 已执行完成。',
                  lastTool: definition.name,
                  updatedAt: DateTime.now().millisecondsSinceEpoch,
                );
                onStatus(latestState);
                // 原生层会输出每次工具结果日志，这里仅保留状态更新，避免重复刷屏。
              },
            ),
          )
          .toList(growable: false);

      final agent = ToolsAgent.fromLLMAndTools(llm: model, tools: tools);
      final executor = AgentExecutor(
        agent: agent,
        maxIterations: _executorMaxIterations,
        maxExecutionTime: _executorMaxExecutionTime,
      );

      latestState = latestState.copyWith(
        running: true,
        status: 'thinking',
        message: '正在等待模型规划下一步。',
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
          message: '已按操作要求停止。',
          lastTool: latestState.lastTool,
          lastModelText: latestState.lastModelText,
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        );
      }

      if (_isExecutorStoppedByLimit(finalText)) {
        final message = _buildExecutorLimitMessage(step: toolStep);
        final stoppedByLimit = AgentRunState(
          running: false,
          status: 'stopped',
          step: toolStep,
          message: message,
          lastTool: latestState.lastTool,
          lastModelText: latestState.lastModelText,
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        );
        onLog('warn', message);
        onStatus(stoppedByLimit);
        return stoppedByLimit;
      }

      final completed = AgentRunState(
        running: false,
        status: 'completed',
        step: toolStep,
        message: finalText.isEmpty ? 'Agent 已完成。' : finalText,
        lastTool: latestState.lastTool,
        lastModelText: finalText.isEmpty
            ? latestState.lastModelText
            : finalText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(completed);
      return completed;
    } on _AgentStalledException catch (error) {
      final stalled = AgentRunState(
        running: false,
        status: 'stopped',
        step: toolStep,
        message: error.message,
        lastTool: latestState.lastTool,
        lastModelText: latestState.lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onLog('warn', error.message);
      onStatus(stalled);
      return stalled;
    } on _AgentStoppedException {
      final stopped = AgentRunState(
        running: false,
        status: 'stopped',
        step: toolStep,
        message: '已按操作要求停止。',
        lastTool: latestState.lastTool,
        lastModelText: latestState.lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onStatus(stopped);
      return stopped;
    } catch (error) {
      final normalizedMessage = _normalizeRuntimeError(error);
      final failed = AgentRunState(
        running: false,
        status: 'failed',
        step: toolStep,
        message: normalizedMessage,
        lastTool: latestState.lastTool,
        lastModelText: latestState.lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onLog('error', normalizedMessage);
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
    required void Function(String callSignature) onToolInvoked,
    required void Function(
      NativeToolExecutionResult result,
      String callSignature,
    ) onToolCompleted,
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

        final callSignature = _buildToolCallSignature(definition.name, input);
        onToolInvoked(callSignature);
        final result = await _platformGateway.executeTool(
          NativeToolExecutionRequest(
            toolName: definition.name,
            arguments: input,
          ),
        );
        onToolCompleted(result, callSignature);

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
- Use `get_question_context` as the primary source of question data, and use `get_page_context` only as supplemental structural context.
- Avoid relying on fixed page-type assumptions; decide based on extracted question text, options, inputs, and actions.
- After finishing the current question input/selection, you must attempt to advance using the `next` action when present.
- If `next` is not explicitly listed but a progress action is likely, re-read context and try available candidate navigation actions before stopping.
- After `click_element`, `toggle_element`, `set_text`, `scroll_screen`, or `press_global`, call `wait_for_idle` before reading the UI again.
- For text-input/fill-blank questions, verify each required input is actually written by re-reading `get_question_context` before moving to next.
- For true/false questions, match options using text features like `对/错/是/否/true/false` and avoid using confirm-style assumptions.
- If a tool fails, re-read the current context before retrying.
- Do not repeat the same failed strategy more than 2 times. If progress still stalls, stop and return a concise blocker summary instead of looping.
- Do not call the same tool with the same arguments more than 4 times in a row.
- If action calls keep returning no UI change, stop early and report the blocker instead of brute-force retries.
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
      ..writeln('你正在为一个 Android Agent 应用验证模型连通性。')
      ..writeln('请简短回复最后一条用户消息。')
      ..writeln('如果这是一次连通性测试，请在回复中明确包含“模型连通正常”。')
      ..writeln()
      ..writeln('对话如下：');

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
      throw StateError('请先填写提供商 URL。');
    }
    if (config.apiKey.trim().isEmpty) {
      throw StateError('请先填写提供商密钥。');
    }
  }

  bool _isExecutorStoppedByLimit(String output) {
    return output.trim() == _executorLimitStopMessage;
  }

  bool _isNoProgressActionResult({
    required NativeToolExecutionResult result,
    required Set<String> actionToolNames,
  }) {
    if (!actionToolNames.contains(result.tool)) {
      return false;
    }
    if (!result.ok) {
      return true;
    }
    final postCheckChanged = result.result['postCheckChanged'];
    if (postCheckChanged is bool) {
      return !postCheckChanged;
    }
    return false;
  }

  String _buildToolCallSignature(String toolName, Map<String, dynamic> input) {
    final stableInput = _normalizeStableValue(input);
    return '$toolName:${jsonEncode(stableInput)}';
  }

  Object? _normalizeStableValue(Object? value) {
    if (value is Map) {
      final entries = value.entries
          .map((entry) => MapEntry(entry.key.toString(), entry.value))
          .toList()
        ..sort((left, right) => left.key.compareTo(right.key));
      final normalized = <String, Object?>{};
      for (final entry in entries) {
        normalized[entry.key] = _normalizeStableValue(entry.value);
      }
      return normalized;
    }
    if (value is List) {
      return value.map(_normalizeStableValue).toList(growable: false);
    }
    return value;
  }

  String _buildRepeatedCallMessage({
    required String toolName,
    required int repeatCount,
  }) {
    return '检测到工具 $toolName 使用相同参数连续执行 $repeatCount 次，已提前停止以避免触发保护上限。请先确认界面是否变化，再重试。';
  }

  String _buildNoProgressActionMessage({
    required int streak,
    required String lastTool,
  }) {
    return '检测到动作调用连续 $streak 次无有效界面变化（最后工具：$lastTool），已提前停止以避免触发保护上限。请先检查页面是否可操作。';
  }

  String _buildExecutorLimitMessage({required int step}) {
    return 'Agent 已达到执行保护上限（最多 $_executorMaxIterations 轮或 ${_executorMaxExecutionTime.inMinutes} 分钟，当前执行 $step 步）并自动停止。若仍无法推进，请检查当前页面是否可操作并提供更明确任务目标后重试。';
  }

  String _normalizeRuntimeError(Object error) {
    final raw = error.toString().trim();
    if (raw.isEmpty) {
      return 'Agent 运行失败：未知错误。';
    }
    if (_isExecutorStoppedByLimit(raw)) {
      return _buildExecutorLimitMessage(step: 0);
    }
    return raw;
  }
}

class _AgentStoppedException implements Exception {
  const _AgentStoppedException();
}

class _AgentStalledException implements Exception {
  const _AgentStalledException(this.message);

  final String message;
}
