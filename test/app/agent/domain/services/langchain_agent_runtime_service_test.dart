import 'dart:async';

import 'package:flutter_answer/app/agent/data/agent_platform_gateway.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_run_state.dart';
import 'package:flutter_answer/app/agent/domain/models/native_platform_event.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_definition.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/services/langchain_agent_runtime_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('LangChainAgentRuntimeService', () {
    test('缺少 provider url 时返回 failed 状态而不是直接抛异常', () async {
      final service = LangChainAgentRuntimeService(
        platformGateway: _FakeAgentPlatformGateway(),
      );
      final states = <AgentRunState>[];
      final logs = <String>[];

      final result = await service.run(
        config: const AgentConfig(apiKey: 'sk-test'),
        onStatus: states.add,
        onLog: (String _, String message) => logs.add(message),
        onToolResult: (_) {},
      );

      expect(result.status, 'failed');
      expect(result.message, '请先填写提供商 URL。');
      expect(states.last.status, 'failed');
      expect(logs.last, '请先填写提供商 URL。');
    });

    test('原生工具列表为空时返回 failed 状态', () async {
      final service = LangChainAgentRuntimeService(
        platformGateway: _FakeAgentPlatformGateway(
          tools: const <NativeToolDefinition>[],
        ),
      );

      final result = await service.run(
        config: const AgentConfig(
          providerUrl: 'https://api.openai.com',
          apiKey: 'sk-test',
        ),
        onStatus: (_) {},
        onLog: (String level, String message) {},
        onToolResult: (_) {},
      );

      expect(result.status, 'failed');
      expect(result.message, 'Android 原生侧未注册可用工具，无法启动 Agent。');
    });
  });
}

class _FakeAgentPlatformGateway implements AgentPlatformGateway {
  _FakeAgentPlatformGateway({List<NativeToolDefinition>? tools})
    : _tools = tools ?? const <NativeToolDefinition>[];

  final List<NativeToolDefinition> _tools;

  @override
  Stream<NativePlatformEvent> events() =>
      const Stream<NativePlatformEvent>.empty();

  @override
  Future<List<NativeToolDefinition>> listTools() async => _tools;

  @override
  Future<Map<String, dynamic>> openAccessibilitySettings() async {
    return const <String, dynamic>{'ok': true};
  }

  @override
  Future<NativeToolExecutionResult> executeTool(
    NativeToolExecutionRequest request,
  ) async {
    return NativeToolExecutionResult(
      requestId: request.requestId,
      tool: request.toolName,
      ok: true,
      result: const <String, dynamic>{},
    );
  }
}
