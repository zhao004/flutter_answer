import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../agent/data/agent_config_store.dart';
import '../../agent/data/agent_platform_gateway.dart';
import '../../agent/domain/models/agent_config.dart';
import '../../agent/domain/models/agent_run_state.dart';
import '../../agent/domain/models/native_platform_event.dart';
import '../../agent/domain/models/native_tool_execution.dart';
import '../../agent/domain/models/service_status_snapshot.dart';
import '../../agent/domain/services/langchain_agent_runtime_service.dart';
import '../../models/overlay_platform_event.dart';
import '../../models/overlay_state_snapshot.dart';
import '../../models/overlay_window_position.dart';
import '../../services/agent_diagnostic_hint.dart';
import '../../services/overlay_platform_gateway.dart';
import '../../services/platform_payload_exception.dart';
import '../../theme/app_theme.dart';
import 'home_agent_config_form.dart';

part 'home_controller_actions.dart';
part 'home_controller_overlay.dart';
part 'home_controller_payload.dart';

class HomeController extends GetxController with WidgetsBindingObserver {
  static const double _expandedOverlayEdgeMargin = 12;
  static const double _collapsedOverlayContentPadding = 8;
  static const double _collapsedOverlayEdgeMargin =
      _expandedOverlayEdgeMargin - _collapsedOverlayContentPadding;

  HomeController({
    required AgentPlatformGateway platformGateway,
    required OverlayPlatformGateway overlayPlatformGateway,
    required AgentConfigStore configStore,
    required LangChainAgentRuntimeService runtimeService,
  }) : _platformGateway = platformGateway,
       _overlayPlatformGateway = overlayPlatformGateway,
       _configStore = configStore,
       _runtimeService = runtimeService;

  final AgentPlatformGateway _platformGateway;
  final OverlayPlatformGateway _overlayPlatformGateway;
  final AgentConfigStore _configStore;
  final LangChainAgentRuntimeService _runtimeService;

  final serviceStatus = const ServiceStatusSnapshot().obs;
  final currentConfig = const AgentConfig().obs;
  final agentRunState = AgentRunState.idle().obs;
  final logs = <String>[].obs;
  final lastPayload = '{}'.obs;
  final overlayPermissionGranted = false.obs;
  final overlayState = OverlayStateSnapshot.initial().obs;
  final overlayPermissionBusy = false.obs;
  final accessibilityServiceBusy = false.obs;
  final overlayVisibilityBusy = false.obs;
  final form = HomeAgentConfigForm();

  final Set<String> _busyGates = <String>{};
  StreamSubscription<NativePlatformEvent>? _eventSubscription;
  StreamSubscription<OverlayPlatformEvent>? _overlaySubscription;
  bool _systemSettingsFlowActive = false;
  String? _lastLogLine;
  int? _lastLogAtMs;

  TextEditingController get providerUrlController => form.providerUrlController;
  TextEditingController get apiKeyController => form.apiKeyController;
  TextEditingController get modelController => form.modelController;
  TextEditingController get maxIterationsController =>
      form.maxIterationsController;
  TextEditingController get maxExecutionMinutesController =>
      form.maxExecutionMinutesController;
  TextEditingController get maxRepeatedSameToolCallController =>
      form.maxRepeatedSameToolCallController;
  TextEditingController get maxNoProgressActionStreakController =>
      form.maxNoProgressActionStreakController;

  bool get agentRunning => agentRunState.value.running;
  bool get overlayActive => overlayState.value.active;
  AgentDiagnosticHint? get diagnosticHint => resolveAgentDiagnosticHint(
    serviceStatus: serviceStatus.value,
    agentConfig: currentConfig.value,
    agentRunState: agentRunState.value,
  );

  @override
  void onInit() {
    super.onInit();
    WidgetsBinding.instance.addObserver(this);
    _eventSubscription = _platformGateway.events().listen(
      _handleNativeEvent,
      onError: _handlePlatformError,
    );
    _overlaySubscription = _overlayPlatformGateway.events().listen(
      _handleOverlayEvent,
      onError: _handlePlatformError,
    );
    _applyRunState(agentRunState.value, updatePayload: false);
    unawaited(_bootstrap());
  }

  @override
  void onClose() {
    WidgetsBinding.instance.removeObserver(this);
    _runtimeService.requestStop();
    _eventSubscription?.cancel();
    _overlaySubscription?.cancel();
    form.dispose();
    super.onClose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _resetTransientBusyState();
      unawaited(_refreshSystemState());
    }
  }
}

extension _HomeControllerPlatformError on HomeController {
  void _handlePlatformError(Object error, [StackTrace? _]) {
    _appendLog('error', '平台协议错误：$error', syncOverlay: false);
  }
}

class _OverlayWindowGeometry {
  const _OverlayWindowGeometry({required this.width, required this.height});

  final int width;
  final int height;
}
