import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_answer/app/agent/data/agent_config_store.dart';
import 'package:flutter_answer/app/agent/data/agent_platform_gateway.dart';
import 'package:flutter_answer/app/agent/domain/models/native_platform_event.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_definition.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/services/langchain_agent_runtime_service.dart';
import 'package:flutter_answer/app/models/overlay_platform_event.dart';
import 'package:flutter_answer/app/models/overlay_state_snapshot.dart';
import 'package:flutter_answer/app/models/overlay_window_position.dart';
import 'package:flutter_answer/app/pages/home/home_controller.dart';
import 'package:flutter_answer/app/pages/home/home_view.dart';
import 'package:flutter_answer/app/services/overlay_platform_gateway.dart';
import 'package:get/get.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    Get.testMode = true;
  });

  tearDown(() {
    Get.reset();
  });

  testWidgets('首页会渲染各卡片说明文案', (WidgetTester tester) async {
    final controller = HomeController(
      platformGateway: _FakeAgentPlatformGateway(),
      overlayPlatformGateway: _FakeOverlayPlatformGateway(),
      configStore: AgentConfigStore.instance,
      runtimeService: LangChainAgentRuntimeService(
        platformGateway: _FakeAgentPlatformGateway(),
      ),
    );
    Get.put<HomeController>(controller);

    await tester.pumpWidget(
      const GetMaterialApp(home: Scaffold(body: HomeView())),
    );
    await tester.pumpAndSettle();

    expect(find.text('完成权限授权后，悬浮窗和自动化能力才能稳定工作。'), findsOneWidget);
    expect(find.text('首页只展示基础配置，高级阈值继续沿用当前已保存的值。'), findsOneWidget);
    expect(find.text('默认只展示最近 3 条日志，需要时再展开查看全部内容。'), findsOneWidget);
  });
}

class _FakeAgentPlatformGateway implements AgentPlatformGateway {
  @override
  Stream<NativePlatformEvent> events() {
    return const Stream<NativePlatformEvent>.empty();
  }

  @override
  Future<List<NativeToolDefinition>> listTools() async {
    return const <NativeToolDefinition>[];
  }

  @override
  Future<Map<String, dynamic>> openAccessibilitySettings() async {
    return <String, dynamic>{'ok': true, 'message': 'opened'};
  }

  @override
  Future<NativeToolExecutionResult> executeTool(
    NativeToolExecutionRequest request,
  ) async {
    if (request.toolName == 'get_service_status') {
      return NativeToolExecutionResult(
        requestId: request.requestId,
        tool: request.toolName,
        ok: true,
        result: const <String, dynamic>{
          'ok': true,
          'enabled': true,
          'lastEventAtMs': 1,
          'hasActiveWindow': true,
        },
      );
    }
    return NativeToolExecutionResult(
      requestId: request.requestId,
      tool: request.toolName,
      ok: true,
      result: const <String, dynamic>{'ok': true},
    );
  }
}

class _FakeOverlayPlatformGateway implements OverlayPlatformGateway {
  @override
  Stream<OverlayPlatformEvent> events() {
    return const Stream<OverlayPlatformEvent>.empty();
  }

  @override
  Future<void> closeOverlay() async {}

  @override
  Future<OverlayStateSnapshot> getOverlayState() async {
    return OverlayStateSnapshot.initial();
  }

  @override
  Future<bool> isActive() async {
    return false;
  }

  @override
  Future<bool> isPermissionGranted() async {
    return false;
  }

  @override
  Future<void> moveOverlay(OverlayWindowPosition position) async {}

  @override
  Future<bool> requestPermission() async {
    return false;
  }

  @override
  Future<void> sendMessage(Map<String, dynamic> payload) async {}

  @override
  Future<void> showOverlay({
    required String mode,
    required OverlayWindowPosition position,
  }) async {}
}
