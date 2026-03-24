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
import '../../agent/domain/services/langchain_agent_runtime_service.dart';
import '../../models/overlay_window_position.dart';
import '../../services/agent_diagnostic_hint.dart';
import '../../services/overlay_platform_gateway.dart';

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

  final serviceStatus = <String, dynamic>{}.obs;
  final agentConfig = <String, dynamic>{}.obs;
  final agentRunStatus = <String, dynamic>{}.obs;
  final logs = <String>[].obs;
  final lastPayload = '{}'.obs;

  final providerUrlController = TextEditingController();
  final apiKeyController = TextEditingController();
  final modelController = TextEditingController(text: 'gpt-5-mini');
  final maxIterationsController = TextEditingController(
    text: AgentConfig.defaultMaxIterations.toString(),
  );
  final maxExecutionMinutesController = TextEditingController(
    text: AgentConfig.defaultMaxExecutionMinutes.toString(),
  );
  final maxRepeatedSameToolCallController = TextEditingController(
    text: AgentConfig.defaultMaxRepeatedSameToolCall.toString(),
  );
  final maxNoProgressActionStreakController = TextEditingController(
    text: AgentConfig.defaultMaxNoProgressActionStreak.toString(),
  );

  final overlayPermissionGranted = false.obs;
  final overlayActive = false.obs;
  final isBusy = false.obs;
  final overlayPermissionBusy = false.obs;
  final accessibilityServiceBusy = false.obs;
  final overlayVisibilityBusy = false.obs;

  StreamSubscription<NativePlatformEvent>? _eventSubscription;
  StreamSubscription<Map<String, dynamic>>? _overlaySubscription;

  AgentConfig _currentConfig = const AgentConfig();
  AgentRunState _currentRunState = AgentRunState.idle();
  bool _systemSettingsFlowActive = false;
  String _overlayWindowMode = 'expanded';
  OverlayWindowPosition _overlayWindowPosition = const OverlayWindowPosition(
    _expandedOverlayEdgeMargin,
    120,
  );
  String? _lastLogLine;
  int? _lastLogAtMs;

  bool get agentRunning => agentRunStatus['running'] == true;

  @override
  void onInit() {
    super.onInit();
    WidgetsBinding.instance.addObserver(this);
    _eventSubscription = _platformGateway.events().listen(_handleNativeEvent);
    _overlaySubscription = _overlayPlatformGateway.events().listen(
      _handleOverlayEvent,
    );
    _applyRunState(_currentRunState, updatePayload: false);
    unawaited(_bootstrap());
  }

  @override
  void onClose() {
    WidgetsBinding.instance.removeObserver(this);
    _runtimeService.requestStop();
    _eventSubscription?.cancel();
    _overlaySubscription?.cancel();
    providerUrlController.dispose();
    apiKeyController.dispose();
    modelController.dispose();
    maxIterationsController.dispose();
    maxExecutionMinutesController.dispose();
    maxRepeatedSameToolCallController.dispose();
    maxNoProgressActionStreakController.dispose();
    super.onClose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _resetTransientBusyState();
      unawaited(_refreshSystemState());
    }
  }

  Future<void> _bootstrap() async {
    await _restoreSavedConfig();
    await refreshAgentConfig();
    await refreshServiceStatus();
    await refreshAgentRunStatus();
    await syncOverlayState();
  }

  Future<void> _restoreSavedConfig() async {
    final savedConfig = await _configStore.load();
    if (savedConfig == null) {
      _syncAgentConfig(_currentConfig.toPublicMap());
      return;
    }

    _currentConfig = savedConfig
        .copyWith(taskPrompt: const AgentConfig().taskPrompt)
        .sanitized();
    _syncAgentConfig(_currentConfig.toPublicMap());
    apiKeyController.text = _currentConfig.apiKey;
  }

  Future<void> refreshServiceStatus() async {
    await _runAction(() async {
      final payload = await _executeTool('get_service_status');
      _setPayload(payload.toMap());
    });
  }

  Future<void> openAccessibilitySettings() async {
    await _launchSystemSettingsFlow(() async {
      await _dismissOverlayForSystemSettings();
      _appendLog('info', '正在打开无障碍设置页面。');
      final payload = await _platformGateway.openAccessibilitySettings();
      _setPayload(payload);
    }, busyFlag: accessibilityServiceBusy);
  }

  Future<void> toggleAccessibilityService(bool enabled) async {
    final current = serviceStatus['enabled'] == true;
    if (current == enabled) {
      return;
    }

    _appendLog('info', enabled ? '请在系统设置中启用无障碍服务。' : '请在系统设置中关闭无障碍服务。');
    await openAccessibilitySettings();
  }

  Future<void> fetchQuestionContext() async {
    await _runAction(() async {
      final payload = await _executeTool('get_question_context');
      _setPayload(payload.toMap());
    });
  }

  Future<void> scrollScreen({
    String direction = 'down',
    String amount = 'medium',
  }) async {
    await _runAction(() async {
      final payload = await _executeTool(
        'scroll_screen',
        arguments: <String, dynamic>{'direction': direction, 'amount': amount},
      );
      _setPayload(payload.toMap());
    });
  }

  Future<void> pressGlobal({String action = 'back'}) async {
    await _runAction(() async {
      final payload = await _executeTool(
        'press_global',
        arguments: <String, dynamic>{'action': action},
      );
      _setPayload(payload.toMap());
    });
  }

  Future<void> refreshAgentConfig() async {
    _syncAgentConfig(_currentConfig.toPublicMap());
    _setPayload(_currentConfig.toPublicMap());
  }

  Future<void> refreshAgentRunStatus() async {
    _applyRunState(_currentRunState);
  }

  Future<bool> saveAgentConfig({bool announce = true}) async {
    final providerUrl = providerUrlController.text.trim();
    if (providerUrl.isEmpty) {
      _appendLog('error', '请先填写提供商 URL。');
      return false;
    }

    _currentConfig = AgentConfig(
      providerUrl: providerUrl,
      apiKey: apiKeyController.text.trim(),
      model: modelController.text.trim().isEmpty
          ? 'gpt-5-mini'
          : modelController.text.trim(),
      taskPrompt: const AgentConfig().taskPrompt,
      maxIterations: _readConfigInt(
        controller: maxIterationsController,
        fallback: AgentConfig.defaultMaxIterations,
      ),
      maxExecutionMinutes: _readConfigInt(
        controller: maxExecutionMinutesController,
        fallback: AgentConfig.defaultMaxExecutionMinutes,
      ),
      maxRepeatedSameToolCall: _readConfigInt(
        controller: maxRepeatedSameToolCallController,
        fallback: AgentConfig.defaultMaxRepeatedSameToolCall,
      ),
      maxNoProgressActionStreak: _readConfigInt(
        controller: maxNoProgressActionStreakController,
        fallback: AgentConfig.defaultMaxNoProgressActionStreak,
      ),
    ).sanitized();

    await _configStore.save(_currentConfig);
    _syncAgentConfig(_currentConfig.toPublicMap());
    apiKeyController.text = _currentConfig.apiKey;
    _setPayload(_currentConfig.toPublicMap());
    await _pushOverlayEvent('agent_config', _currentConfig.toPublicMap());

    if (announce) {
      _appendLog('info', 'Agent 配置已保存到本地，包含提供商密钥。');
    }

    return true;
  }

  Future<void> startAgentRun() async {
    if (agentRunning) {
      return;
    }

    await refreshServiceStatus();

    final saved = await saveAgentConfig(announce: false);
    if (!saved) {
      _applyRunState(
        AgentRunState.idle().copyWith(
          running: false,
          status: 'failed',
          message: '请先填写提供商 URL。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    if (!_currentConfig.hasApiKey) {
      _applyRunState(
        AgentRunState.idle().copyWith(
          running: false,
          status: 'failed',
          message: '请先填写并保存提供商密钥。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    unawaited(_runAgentLoop());
  }

  Future<void> _runAgentLoop() async {
    final starting = _currentRunState.copyWith(
      running: true,
      status: 'starting',
      message: '正在准备 LangChain Agent 运行环境。',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _applyRunState(starting);

    final finalState = await _runtimeService.run(
      config: _currentConfig,
      onStatus: (AgentRunState state) {
        _applyRunState(state);
      },
      onLog: _appendLog,
      onToolResult: (NativeToolExecutionResult result) {
        _setPayload(result.toMap());
      },
    );

    _applyRunState(finalState);
  }

  Future<void> stopAgentRun() async {
    _runtimeService.requestStop();
    if (!agentRunning) {
      _applyRunState(
        _currentRunState.copyWith(
          running: false,
          status: 'idle',
          message: 'Agent 当前未运行。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    _applyRunState(
      _currentRunState.copyWith(
        running: true,
        status: 'stopping',
        message: '已收到停止指令，正在结束当前流程。',
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  Future<void> syncOverlayState() async {
    final granted = await _overlayPlatformGateway.isPermissionGranted();
    final active = await _overlayPlatformGateway.isActive();
    overlayPermissionGranted.value = granted;
    overlayActive.value = active;
    if (active) {
      _applyOverlayStatePayload(
        await _overlayPlatformGateway.getOverlayState(),
      );
    }
    await _broadcastOverlaySnapshot();
  }

  Future<void> requestOverlayPermission() async {
    if (_systemSettingsFlowActive || overlayPermissionBusy.value) {
      return;
    }

    _systemSettingsFlowActive = true;
    overlayPermissionBusy.value = true;
    await _dismissOverlayForSystemSettings();
    _appendLog('info', '正在打开系统悬浮窗权限页面。');
    try {
      final granted = await _overlayPlatformGateway.requestPermission();
      overlayPermissionGranted.value = granted;
      if (granted) {
        _appendLog('info', '已获得悬浮窗权限。');
      } else {
        _appendLog('warn', '悬浮窗权限尚未开启，请在系统页面中完成授权。');
      }
      await syncOverlayState();
    } catch (error) {
      _appendLog('error', '请求悬浮窗权限失败：$error');
      await syncOverlayState();
    } finally {
      _systemSettingsFlowActive = false;
      overlayPermissionBusy.value = false;
    }
  }

  Future<void> toggleOverlayPermission(bool enabled) async {
    final current = overlayPermissionGranted.value;
    if (current == enabled) {
      return;
    }

    if (enabled) {
      await requestOverlayPermission();
      return;
    }

    _appendLog('info', '请在系统设置中手动关闭悬浮窗权限。');
  }

  Future<void> showOverlay() async {
    await _runAction(() async {
      final granted = overlayPermissionGranted.value;
      if (!granted) {
        _appendLog('warn', '请先开启悬浮窗权限后再显示悬浮窗。');
        return;
      }

      await _showOverlayWindow();
      await syncOverlayState();
    }, busyFlag: overlayVisibilityBusy);
  }

  Future<void> closeOverlay() async {
    await _runAction(() async {
      await _overlayPlatformGateway.closeOverlay();
      overlayActive.value = false;
      await _broadcastOverlaySnapshot();
    }, busyFlag: overlayVisibilityBusy);
  }

  Future<void> toggleOverlayVisibility(bool visible) async {
    if (visible) {
      if (!overlayPermissionGranted.value) {
        await requestOverlayPermission();
      }
      if (overlayPermissionGranted.value && !overlayActive.value) {
        await showOverlay();
      }
      return;
    }

    if (overlayActive.value) {
      await closeOverlay();
    }
  }

  void _handleNativeEvent(NativePlatformEvent event) {
    if (event.type == 'log') {
      _appendLog(
        event.level ?? 'info',
        event.message ?? '',
        timestampMs: event.timestamp,
      );
      return;
    }

    if (event.type == 'service_status') {
      final payload = event.payload ?? const <String, dynamic>{};
      serviceStatus.assignAll(payload);
      unawaited(_pushOverlayEvent('service_status', payload));
      return;
    }

    if (event.type == 'agent_status') {
      final payload = event.payload ?? const <String, dynamic>{};
      agentRunStatus.assignAll(payload);
      unawaited(_pushOverlayEvent('agent_status', payload));
      return;
    }

    if (event.type == 'tool_result') {
      final envelope = <String, dynamic>{
        'tool': event.tool,
        'ok': event.ok,
        'result': event.payload ?? const <String, dynamic>{},
        'timestamp': event.timestamp,
      };
      _setPayload(envelope);
    }
  }

  void _handleOverlayEvent(dynamic rawEvent) {
    final event = _normalizeEvent(rawEvent);
    if (event == null) {
      return;
    }

    if (event['source'] == 'main') {
      return;
    }

    final pluginEvent = event['event']?.toString();
    if (pluginEvent == 'overlay_shown') {
      overlayActive.value = true;
      unawaited(syncOverlayState());
      unawaited(_broadcastOverlaySnapshot());
      return;
    }
    if (pluginEvent == 'overlay_closed') {
      overlayActive.value = false;
      unawaited(_broadcastOverlaySnapshot());
      return;
    }

    if (event['type'] == 'overlay_state') {
      final payload = _normalizeMap(event['payload']);
      if (payload != null) {
        _applyOverlayStatePayload(payload);
      }
      return;
    }

    if (event['source'] != 'overlay' || event['type'] != 'command') {
      return;
    }

    switch (event['action']) {
      case 'start_agent':
        unawaited(startAgentRun());
        break;
      case 'stop_agent':
        unawaited(stopAgentRun());
        break;
      case 'refresh_question_context':
        unawaited(fetchQuestionContext());
        break;
      case 'scroll_down':
        unawaited(scrollScreen(direction: 'down', amount: 'medium'));
        break;
      case 'press_back':
        unawaited(pressGlobal(action: 'back'));
        break;
      case 'sync_snapshot':
        unawaited(_broadcastOverlaySnapshot());
        break;
      case 'set_overlay_mode':
        unawaited(_applyOverlayMode(event['mode']?.toString()));
        break;
      case 'move_overlay':
        final x = _readDouble(event['x']);
        final y = _readDouble(event['y']);
        if (x != null && y != null) {
          unawaited(_moveOverlayWindow(OverlayWindowPosition(x, y)));
        }
        break;
      case 'close_overlay':
        unawaited(closeOverlay());
        break;
    }
  }

  /// 将“当前开关是否忙碌”单独建模，避免一个开关的异步过程把其他开关一起切到禁用态。
  Future<void> _runAction(
    Future<void> Function() action, {
    RxBool? busyFlag,
  }) async {
    if (isBusy.value || (busyFlag?.value ?? false)) {
      return;
    }
    isBusy.value = true;
    busyFlag?.value = true;
    try {
      await action();
    } catch (error) {
      final payload = <String, dynamic>{
        'ok': false,
        'message': error.toString(),
      };
      _setPayload(payload);
      _appendLog('error', error.toString());
    } finally {
      busyFlag?.value = false;
      isBusy.value = false;
    }
  }

  Future<void> _refreshSystemState() async {
    await refreshServiceStatus();
    await syncOverlayState();
  }

  Future<void> _showOverlayWindow() async {
    await _overlayPlatformGateway.showOverlay(
      mode: _overlayWindowMode,
      position: _overlayWindowPosition,
    );

    overlayActive.value = true;
  }

  Future<void> _applyOverlayMode(String? mode) async {
    final nextMode = _normalizeOverlayMode(mode);
    final currentMode = _overlayWindowMode;
    final currentPosition =
        await _safeGetOverlayPosition() ?? _overlayWindowPosition;
    _overlayWindowPosition = _resolveOverlayPositionForMode(
      currentMode: currentMode,
      nextMode: nextMode,
      currentPosition: currentPosition,
    );
    _overlayWindowMode = nextMode;
    await _showOverlayWindow();
    await syncOverlayState();
    await _pushOverlayState();
  }

  Future<void> _moveOverlayWindow(OverlayWindowPosition position) async {
    final geometry = _overlayGeometryForMode(_overlayWindowMode);
    final clamped = _clampOverlayPosition(position, geometry);
    await _overlayPlatformGateway.moveOverlay(clamped);
    _overlayWindowPosition = clamped;
    await _pushOverlayState();
  }

  Future<OverlayWindowPosition?> _safeGetOverlayPosition() async {
    try {
      final overlayState = await _overlayPlatformGateway.getOverlayState();
      if (overlayState.isEmpty) {
        return null;
      }
      _applyOverlayStatePayload(overlayState);
      return OverlayWindowPosition.tryParse(overlayState['position']);
    } catch (_) {
      return null;
    }
  }

  Map<String, dynamic> _overlayStatePayload() {
    final geometry = _overlayGeometryForMode(_overlayWindowMode);
    return <String, dynamic>{
      'mode': _overlayWindowMode,
      'position': _overlayWindowPosition.toMap(),
      'width': geometry.width,
      'height': geometry.height,
      'active': overlayActive.value,
    };
  }

  Future<void> _pushOverlayState() {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': 'overlay_state',
      'payload': _overlayStatePayload(),
    });
  }

  String _normalizeOverlayMode(String? mode) {
    return mode == 'collapsed' ? 'collapsed' : 'expanded';
  }

  _OverlayWindowGeometry _overlayGeometryForMode(String mode) {
    if (mode == 'collapsed') {
      return const _OverlayWindowGeometry(width: 72, height: 72);
    }
    return const _OverlayWindowGeometry(width: 288, height: 272);
  }

  double _overlayEdgeMarginForMode(String mode) {
    // 缩小态气泡本身还有 8dp 内边距，因此外层边距要更小，才能让视觉贴边距离与展开态一致。
    if (mode == 'collapsed') {
      return _collapsedOverlayEdgeMargin;
    }
    return _expandedOverlayEdgeMargin;
  }

  OverlayWindowPosition _resolveOverlayPositionForMode({
    required String currentMode,
    required String nextMode,
    required OverlayWindowPosition currentPosition,
  }) {
    final currentGeometry = _overlayGeometryForMode(currentMode);
    final nextGeometry = _overlayGeometryForMode(nextMode);
    final screen = _logicalScreenSize();
    final anchorX = currentPosition.x + (currentGeometry.width / 2);
    final anchorY =
        currentPosition.y +
        (currentGeometry.height / 2) -
        (nextGeometry.height / 2);
    final edgeMargin = _overlayEdgeMarginForMode(nextMode);
    final targetX = anchorX < (screen.width / 2)
        ? edgeMargin
        : screen.width - nextGeometry.width - edgeMargin;

    return _clampOverlayPosition(
      OverlayWindowPosition(targetX, anchorY),
      nextGeometry,
      mode: nextMode,
    );
  }

  OverlayWindowPosition _clampOverlayPosition(
    OverlayWindowPosition position,
    _OverlayWindowGeometry geometry, {
    String? mode,
  }) {
    final screen = _logicalScreenSize();
    final edgeMargin = _overlayEdgeMarginForMode(mode ?? _overlayWindowMode);
    final maxX = math.max(
      edgeMargin,
      screen.width - geometry.width - edgeMargin,
    );
    final maxY = math.max(
      edgeMargin,
      screen.height - geometry.height - edgeMargin,
    );
    return OverlayWindowPosition(
      position.x.clamp(edgeMargin, maxX).toDouble(),
      position.y.clamp(edgeMargin, maxY).toDouble(),
    );
  }

  Size _logicalScreenSize() {
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    return Size(
      view.display.size.width / view.display.devicePixelRatio,
      view.display.size.height / view.display.devicePixelRatio,
    );
  }

  Future<void> _dismissOverlayForSystemSettings() async {
    final active =
        overlayActive.value || await _overlayPlatformGateway.isActive();
    if (!active) {
      return;
    }

    await _overlayPlatformGateway.closeOverlay();
    overlayActive.value = false;
    _appendLog('info', '进入系统设置前已关闭悬浮窗，避免遮挡权限页面。');
    await _broadcastOverlaySnapshot();
  }

  void _applyOverlayStatePayload(Map<String, dynamic> payload) {
    final mode = payload['mode']?.toString();
    if (mode != null) {
      _overlayWindowMode = _normalizeOverlayMode(mode);
    }

    final position = OverlayWindowPosition.tryParse(payload['position']);
    if (position != null) {
      _overlayWindowPosition = position;
    }

    final active = payload['active'];
    if (active is bool) {
      overlayActive.value = active;
    }
  }

  Future<void> _launchSystemSettingsFlow(
    Future<void> Function() action, {
    RxBool? busyFlag,
  }) async {
    if (_systemSettingsFlowActive || (busyFlag?.value ?? false)) {
      return;
    }

    _systemSettingsFlowActive = true;
    busyFlag?.value = true;
    try {
      await action();
    } catch (error) {
      _systemSettingsFlowActive = false;
      busyFlag?.value = false;
      final payload = <String, dynamic>{
        'ok': false,
        'message': error.toString(),
      };
      _setPayload(payload);
      _appendLog('error', error.toString());
    }
  }

  /// 系统设置页返回后统一清理瞬时忙碌态，避免开关残留在禁用视觉状态。
  void _resetTransientBusyState() {
    _systemSettingsFlowActive = false;
    isBusy.value = false;
    overlayPermissionBusy.value = false;
    accessibilityServiceBusy.value = false;
    overlayVisibilityBusy.value = false;
  }

  void _syncAgentConfig(Map<String, dynamic> payload) {
    agentConfig.assignAll(payload);
    providerUrlController.text = payload['providerUrl']?.toString() ?? '';
    modelController.text = payload['model']?.toString() ?? modelController.text;
    apiKeyController.text = _currentConfig.apiKey;
    maxIterationsController.text =
        payload['maxIterations']?.toString() ??
        AgentConfig.defaultMaxIterations.toString();
    maxExecutionMinutesController.text =
        payload['maxExecutionMinutes']?.toString() ??
        AgentConfig.defaultMaxExecutionMinutes.toString();
    maxRepeatedSameToolCallController.text =
        payload['maxRepeatedSameToolCall']?.toString() ??
        AgentConfig.defaultMaxRepeatedSameToolCall.toString();
    maxNoProgressActionStreakController.text =
        payload['maxNoProgressActionStreak']?.toString() ??
        AgentConfig.defaultMaxNoProgressActionStreak.toString();
  }

  int _readConfigInt({
    required TextEditingController controller,
    required int fallback,
  }) {
    final parsed = int.tryParse(controller.text.trim());
    return parsed ?? fallback;
  }

  void _applyRunState(AgentRunState state, {bool updatePayload = true}) {
    _currentRunState = state;
    agentRunStatus.assignAll(state.toMap());
    if (updatePayload) {
      _setPayload(state.toMap());
    } else {
      unawaited(_pushOverlayEvent('agent_status', state.toMap()));
    }
  }

  void _setPayload(Map<String, dynamic> payload) {
    final resultPayload = _normalizeMap(payload['result']);
    final toolName = payload['tool']?.toString();

    if (toolName == 'get_service_status' && resultPayload != null) {
      serviceStatus.assignAll(resultPayload);
      unawaited(_pushOverlayEvent('service_status', resultPayload));
    } else if (payload.containsKey('enabled')) {
      serviceStatus.assignAll(payload);
      unawaited(_pushOverlayEvent('service_status', payload));
    }

    if (payload.containsKey('hasApiKey') || payload.containsKey('taskPrompt')) {
      agentConfig.assignAll(payload);
      unawaited(_pushOverlayEvent('agent_config', payload));
    }

    if (payload.containsKey('running') || payload.containsKey('status')) {
      agentRunStatus.assignAll(payload);
      unawaited(_pushOverlayEvent('agent_status', payload));
    }

    lastPayload.value = const JsonEncoder.withIndent('  ').convert(payload);
    unawaited(
      _sendOverlayMessage(<String, dynamic>{
        'source': 'main',
        'type': 'payload',
        'payload': lastPayload.value,
      }),
    );
  }

  void _appendLog(String level, String message, {int? timestampMs}) {
    final normalizedMessage = message.trim();
    if (normalizedMessage.isEmpty) {
      return;
    }

    final nowMs = timestampMs ?? DateTime.now().millisecondsSinceEpoch;
    final timeLabel = _formatLogTime(
      DateTime.fromMillisecondsSinceEpoch(nowMs),
    );
    final levelLabel = switch (level.toLowerCase()) {
      'info' => '信息',
      'warn' => '警告',
      'error' => '错误',
      _ => level.toUpperCase(),
    };
    final line = '[$timeLabel][$levelLabel] $normalizedMessage';
    // 部分原生日志可能在短时间内重复上报，这里去重避免日志刷屏。
    if (_lastLogLine == line &&
        _lastLogAtMs != null &&
        nowMs - _lastLogAtMs! < 600) {
      return;
    }
    _lastLogLine = line;
    _lastLogAtMs = nowMs;
    logs.insert(0, line);
    if (logs.length > 60) {
      logs.removeRange(60, logs.length);
    }
    unawaited(
      _sendOverlayMessage(<String, dynamic>{
        'source': 'main',
        'type': 'log',
        'line': line,
      }),
    );
  }

  String _formatLogTime(DateTime time) {
    final hour = time.hour.toString().padLeft(2, '0');
    final minute = time.minute.toString().padLeft(2, '0');
    final second = time.second.toString().padLeft(2, '0');
    return '$hour:$minute:$second';
  }

  Future<void> _broadcastOverlaySnapshot() {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': 'snapshot',
      'serviceStatus': Map<String, dynamic>.from(serviceStatus),
      'agentConfig': Map<String, dynamic>.from(agentConfig),
      'agentRunStatus': Map<String, dynamic>.from(agentRunStatus),
      'diagnosticHint': _currentOverlayDiagnosticHintPayload(),
      'overlayState': _overlayStatePayload(),
      'lastPayload': lastPayload.value,
      'logs': logs.take(20).toList(),
    });
  }

  Future<void> _pushOverlayEvent(
    String type,
    Map<String, dynamic> payload,
  ) async {
    await _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': type,
      'payload': payload,
    });

    if (type == 'service_status' ||
        type == 'agent_config' ||
        type == 'agent_status') {
      await _sendOverlayMessage(<String, dynamic>{
        'source': 'main',
        'type': 'diagnostic_hint',
        'payload': _currentOverlayDiagnosticHintPayload(),
      });
    }
  }

  Map<String, dynamic>? _currentOverlayDiagnosticHintPayload() {
    final hint = resolveAgentDiagnosticHint(
      serviceStatus: Map<String, dynamic>.from(serviceStatus),
      agentConfig: Map<String, dynamic>.from(agentConfig),
      agentRunStatus: Map<String, dynamic>.from(agentRunStatus),
    );
    if (hint == null) {
      return null;
    }
    return <String, dynamic>{
      'severity': _diagnosticSeverityValue(hint.severity),
      'title': hint.title,
      'summary': hint.summary,
      'actions': hint.actions,
    };
  }

  String _diagnosticSeverityValue(AgentDiagnosticSeverity severity) {
    switch (severity) {
      case AgentDiagnosticSeverity.info:
        return 'info';
      case AgentDiagnosticSeverity.warning:
        return 'warning';
      case AgentDiagnosticSeverity.error:
        return 'error';
    }
  }

  Future<void> _sendOverlayMessage(Map<String, dynamic> payload) async {
    await _overlayPlatformGateway.sendMessage(payload);
  }

  Future<NativeToolExecutionResult> _executeTool(
    String toolName, {
    Map<String, dynamic> arguments = const <String, dynamic>{},
  }) {
    return _platformGateway.executeTool(
      NativeToolExecutionRequest(toolName: toolName, arguments: arguments),
    );
  }

  Map<String, dynamic>? _normalizeEvent(dynamic rawEvent) {
    if (rawEvent is Map) {
      return rawEvent.map(
        (dynamic key, dynamic value) => MapEntry(key.toString(), value),
      );
    }
    if (rawEvent is String && rawEvent.isNotEmpty) {
      try {
        final decoded = jsonDecode(rawEvent);
        if (decoded is Map) {
          return decoded.map(
            (dynamic key, dynamic value) => MapEntry(key.toString(), value),
          );
        }
      } on FormatException {
        return null;
      }
    }
    return null;
  }

  Map<String, dynamic>? _normalizeMap(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) => MapEntry(key.toString(), item),
      );
    }
    return null;
  }

  double? _readDouble(dynamic value) {
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value?.toString() ?? '');
  }
}

class _OverlayWindowGeometry {
  const _OverlayWindowGeometry({required this.width, required this.height});

  final int width;
  final int height;
}
