import 'dart:async';

import 'package:flutter_answer/app/agent/data/agent_config_store.dart';
import 'package:flutter_answer/app/agent/data/agent_platform_gateway.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_run_state.dart';
import 'package:flutter_answer/app/agent/domain/models/native_platform_event.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_definition.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/services/langchain_agent_runtime_service.dart';
import 'package:flutter_answer/app/models/overlay_platform_event.dart';
import 'package:flutter_answer/app/models/overlay_state_snapshot.dart';
import 'package:flutter_answer/app/models/overlay_window_position.dart';
import 'package:flutter_answer/app/pages/home/home_controller.dart';
import 'package:flutter_answer/app/services/overlay_platform_gateway.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('HomeController', () {
    test('悬浮窗消息发送失败时仍然可以保存配置', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final controller = HomeController(
        platformGateway: _FakeAgentPlatformGateway(),
        overlayPlatformGateway: _ThrowingOverlayPlatformGateway(),
        configStore: AgentConfigStore.instance,
        runtimeService: _FakeRuntimeService(),
      );

      controller.providerUrlController.text = 'https://api.openai.com';
      controller.apiKeyController.text = 'sk-test';
      controller.modelController.text = 'gpt-5-mini';

      final saved = await controller.saveAgentConfig();

      expect(saved, isTrue);
      expect(
        controller.currentConfig.value.providerUrl,
        'https://api.openai.com',
      );
      expect(
        controller.logs.any((String line) => line.contains('悬浮窗同步失败')),
        isTrue,
      );
      controller.onClose();
    });
  });
}

class _FakeAgentPlatformGateway implements AgentPlatformGateway {
  @override
  Stream<NativePlatformEvent> events() =>
      const Stream<NativePlatformEvent>.empty();

  @override
  Future<List<NativeToolDefinition>> listTools() async =>
      const <NativeToolDefinition>[];

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

class _ThrowingOverlayPlatformGateway implements OverlayPlatformGateway {
  @override
  Stream<OverlayPlatformEvent> events() =>
      const Stream<OverlayPlatformEvent>.empty();

  @override
  Future<void> closeOverlay() async {}

  @override
  Future<OverlayStateSnapshot> getOverlayState() async {
    return OverlayStateSnapshot.initial();
  }

  @override
  Future<bool> isActive() async => false;

  @override
  Future<bool> isPermissionGranted() async => false;

  @override
  Future<void> moveOverlay(OverlayWindowPosition position) async {}

  @override
  Future<bool> requestPermission() async => false;

  @override
  Future<void> sendMessage(Map<String, dynamic> payload) async {
    throw StateError('overlay unavailable');
  }

  @override
  Future<void> showOverlay({
    required String mode,
    required OverlayWindowPosition position,
  }) async {}
}

class _FakeRuntimeService extends LangChainAgentRuntimeService {
  _FakeRuntimeService() : super(platformGateway: _FakeAgentPlatformGateway());

  @override
  Future<AgentRunState> run({
    required AgentConfig config,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    return AgentRunState.idle();
  }
}
