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

  static const int _executorMaxIterations = 100;
  static const Duration _executorMaxExecutionTime = Duration(minutes: 1);
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
              onToolInvoked: () {
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
              onToolCompleted: (result) {
                onToolResult(result);
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
- Prefer `get_page_context` first, then `get_question_context`, and use `get_ui_snapshot` only when needed for raw node details.
- After finishing the current question input/selection, you must attempt to advance using the `next` action when present.
- If `next` is not explicitly listed but a progress action is likely, re-read context and try available candidate navigation actions before stopping.
- After `click_element`, `toggle_element`, `set_text`, `scroll_screen`, or `press_global`, call `wait_for_idle` before reading the UI again.
- If a tool fails, re-read the current context before retrying.
- If the same strategy fails repeatedly and progress stalls, stop and return a concise blocker summary instead of looping.
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

  String _buildExecutorLimitMessage({required int step}) {
    return 'Agent 已达到执行保护上限（最多 $_executorMaxIterations 轮或 ${_executorMaxExecutionTime.inMinutes} 分钟，当前执行 $step 步）并自动停止。请先确认题目页面已稳定，再缩小任务范围后重试。';
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
