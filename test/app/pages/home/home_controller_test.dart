import 'dart:async';

import 'package:flutter_answer/app/agent/data/agent_config_store.dart';
import 'package:flutter_answer/app/agent/data/agent_platform_gateway.dart';
import 'package:flutter_answer/app/agent/data/model_catalog_gateway.dart';
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
        modelCatalogGateway: _FakeModelCatalogGateway(),
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

    test('获取模型后可从列表选择并保存', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final modelGateway = _FakeModelCatalogGateway(
        models: <String>['gpt-4o', 'gpt-4o-mini'],
      );
      final controller = _createController(modelCatalogGateway: modelGateway);

      controller.providerUrlController.text = 'https://api.openai.com';
      controller.apiKeyController.text = 'sk-test';
      await controller.fetchAvailableModels();

      expect(modelGateway.callCount, 1);
      expect(modelGateway.lastProviderUrl, 'https://api.openai.com');
      expect(modelGateway.lastApiKey, 'sk-test');
      expect(controller.modelOptions.toList(), <String>[
        'gpt-4o',
        'gpt-4o-mini',
      ]);
      expect(
        controller.logs.any((String line) => line.contains('已获取 2 个模型')),
        isTrue,
      );

      controller.selectModel('gpt-4o-mini');
      expect(controller.modelController.text, 'gpt-4o-mini');

      final saved = await controller.saveAgentConfig(announce: false);
      expect(saved, isTrue);
      expect(controller.currentConfig.value.model, 'gpt-4o-mini');
      controller.onClose();
    });

    test('缺少提供商 URL 或 API 密钥时不会请求模型列表', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final modelGateway = _FakeModelCatalogGateway();
      final controller = _createController(modelCatalogGateway: modelGateway);

      await controller.fetchAvailableModels();
      expect(modelGateway.callCount, 0);
      expect(
        controller.logs.any((String line) => line.contains('请先填写提供商 URL。')),
        isTrue,
      );

      controller.providerUrlController.text = 'https://api.openai.com';
      await controller.fetchAvailableModels();
      expect(modelGateway.callCount, 0);
      expect(
        controller.logs.any((String line) => line.contains('请先填写提供商 API 密钥。')),
        isTrue,
      );
      controller.onClose();
    });

    test('获取模型失败时记录错误并保留手动输入', () async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      final modelGateway = _FakeModelCatalogGateway(
        error: const ModelCatalogException(
          '模型列表请求被拒绝（HTTP 401），请检查提供商 API 密钥。',
        ),
      );
      final controller = _createController(modelCatalogGateway: modelGateway);

      controller.providerUrlController.text = 'https://api.openai.com';
      controller.apiKeyController.text = 'sk-test';
      await controller.fetchAvailableModels();

      expect(controller.modelOptions, isEmpty);
      expect(controller.modelController.text, 'gpt-5-mini');
      expect(
        controller.logs.any((String line) => line.contains('HTTP 401')),
        isTrue,
      );
      controller.onClose();
    });
  });
}

HomeController _createController({
  required ModelCatalogGateway modelCatalogGateway,
  OverlayPlatformGateway? overlayPlatformGateway,
}) {
  return HomeController(
    platformGateway: _FakeAgentPlatformGateway(),
    overlayPlatformGateway:
        overlayPlatformGateway ?? _ThrowingOverlayPlatformGateway(),
    configStore: AgentConfigStore.instance,
    runtimeService: _FakeRuntimeService(),
    modelCatalogGateway: modelCatalogGateway,
  );
}

class _FakeModelCatalogGateway implements ModelCatalogGateway {
  _FakeModelCatalogGateway({this.models = const <String>[], this.error});

  final List<String> models;
  final Object? error;
  int callCount = 0;
  String? lastProviderUrl;
  String? lastApiKey;

  @override
  Future<List<String>> fetchModels({
    required String providerUrl,
    required String apiKey,
  }) async {
    callCount++;
    lastProviderUrl = providerUrl;
    lastApiKey = apiKey;
    if (error != null) {
      throw error!;
    }
    return models;
  }
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
