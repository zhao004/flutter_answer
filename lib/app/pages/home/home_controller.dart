import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:get/get.dart';

import '../../agent/data/agent_config_store.dart';
import '../../agent/data/agent_platform_gateway.dart';
import '../../agent/domain/models/agent_config.dart';
import '../../agent/domain/models/agent_run_state.dart';
import '../../agent/domain/models/native_platform_event.dart';
import '../../agent/domain/models/native_tool_execution.dart';
import '../../agent/domain/services/langchain_agent_runtime_service.dart';
import '../../services/overlay_message_bridge.dart';

class HomeController extends GetxController with WidgetsBindingObserver {
  HomeController({
    required AgentPlatformGateway platformGateway,
    required AgentConfigStore configStore,
    required LangChainAgentRuntimeService runtimeService,
  }) : _platformGateway = platformGateway,
       _configStore = configStore,
       _runtimeService = runtimeService;

  final AgentPlatformGateway _platformGateway;
  final AgentConfigStore _configStore;
  final LangChainAgentRuntimeService _runtimeService;
  final ReceivePort _mainReceivePort = ReceivePort();

  final serviceStatus = <String, dynamic>{}.obs;
  final agentConfig = <String, dynamic>{}.obs;
  final agentRunStatus = <String, dynamic>{}.obs;
  final logs = <String>[].obs;
  final lastPayload = '{}'.obs;

  final providerUrlController = TextEditingController();
  final apiKeyController = TextEditingController();
  final modelController = TextEditingController(text: 'gpt-5-mini');
  final taskPromptController = TextEditingController(
    text:
        'Read the current question page, answer carefully, prefer tools, and stop at the submit page unless auto submit is enabled.',
  );

  final autoSubmit = false.obs;
  final overlayDragFrameThrottleEnabled = true.obs;
  final overlayPermissionGranted = false.obs;
  final overlayActive = false.obs;
  final isBusy = false.obs;

  StreamSubscription<NativePlatformEvent>? _eventSubscription;
  StreamSubscription<dynamic>? _overlaySubscription;
  StreamSubscription<dynamic>? _overlayPortSubscription;

  AgentConfig _currentConfig = const AgentConfig();
  AgentRunState _currentRunState = AgentRunState.idle();
  bool _systemSettingsFlowActive = false;
  String _overlayWindowMode = 'expanded';
  OverlayPosition _overlayWindowPosition = const OverlayPosition(12, 120);
  static const int _overlayDragFrameThrottleIntervalMs = 16;
  String? _lastLogLine;
  int? _lastLogAtMs;

  bool get agentRunning => agentRunStatus['running'] == true;

  @override
  void onInit() {
    super.onInit();
    WidgetsBinding.instance.addObserver(this);
    _registerMainPort();
    _eventSubscription = _platformGateway.events().listen(_handleNativeEvent);
    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen(
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
    _overlayPortSubscription?.cancel();
    _mainReceivePort.close();
    IsolateNameServer.removePortNameMapping(OverlayMessageBridge.mainPortName);
    providerUrlController.dispose();
    apiKeyController.dispose();
    modelController.dispose();
    taskPromptController.dispose();
    super.onClose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      if (_systemSettingsFlowActive || isBusy.value) {
        _systemSettingsFlowActive = false;
        isBusy.value = false;
      }
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

  void _registerMainPort() {
    IsolateNameServer.removePortNameMapping(OverlayMessageBridge.mainPortName);
    IsolateNameServer.registerPortWithName(
      _mainReceivePort.sendPort,
      OverlayMessageBridge.mainPortName,
    );
    _overlayPortSubscription = _mainReceivePort.listen(_handleOverlayEvent);
  }

  Future<void> _restoreSavedConfig() async {
    final savedConfig = await _configStore.load();
    if (savedConfig == null) {
      _syncAgentConfig(_currentConfig.toPublicMap());
      return;
    }

    _currentConfig = savedConfig;
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
    });
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

  Future<void> fetchUiSnapshot() async {
    await _runAction(() async {
      final payload = await _executeTool('get_ui_snapshot');
      _setPayload(payload.toMap());
    });
  }

  Future<void> waitForIdle() async {
    await _runAction(() async {
      final payload = await _executeTool(
        'wait_for_idle',
        arguments: <String, dynamic>{'timeoutMs': 1200},
      );
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
      taskPrompt: taskPromptController.text.trim().isEmpty
          ? const AgentConfig().taskPrompt
          : taskPromptController.text.trim(),
      autoSubmit: autoSubmit.value,
    );

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
    final granted = await FlutterOverlayWindow.isPermissionGranted() == true;
    final active = await FlutterOverlayWindow.isActive() == true;
    overlayPermissionGranted.value = granted;
    overlayActive.value = active;
    if (active) {
      final position = await _safeGetOverlayPosition();
      if (position != null) {
        _overlayWindowPosition = position;
      }
    }
    await _broadcastOverlaySnapshot();
  }

  Future<void> requestOverlayPermission() async {
    if (_systemSettingsFlowActive) {
      return;
    }

    _systemSettingsFlowActive = true;
    await _dismissOverlayForSystemSettings();
    _appendLog('info', '正在打开系统悬浮窗权限页面。');
    try {
      final granted = await FlutterOverlayWindow.requestPermission();
      overlayPermissionGranted.value = granted == true;
      if (granted == true) {
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
    });
  }

  Future<void> closeOverlay() async {
    try {
      await FlutterOverlayWindow.closeOverlay();
      overlayActive.value = false;
      await _broadcastOverlaySnapshot();
    } catch (error) {
      _appendLog('error', error.toString());
    }
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

  Future<void> toggleOverlayDragFrameThrottle(bool enabled) async {
    if (overlayDragFrameThrottleEnabled.value == enabled) {
      return;
    }
    overlayDragFrameThrottleEnabled.value = enabled;
    _appendLog(
      'info',
      enabled ? '已开启悬浮窗拖动帧率节流（16ms）。' : '已关闭悬浮窗拖动帧率节流。',
    );
    await _pushOverlayState();
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
          unawaited(_moveOverlayWindow(OverlayPosition(x, y)));
        }
        break;
      case 'close_overlay':
        unawaited(closeOverlay());
        break;
    }
  }

  Future<void> _runAction(Future<void> Function() action) async {
    if (isBusy.value) {
      return;
    }
    isBusy.value = true;
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
      isBusy.value = false;
    }
  }

  Future<void> _refreshSystemState() async {
    await refreshServiceStatus();
    await syncOverlayState();
  }

  Future<void> _showOverlayWindow() async {
    final geometry = _overlayGeometryForMode(_overlayWindowMode);
    final enableDrag = _overlayDragEnabledForMode(_overlayWindowMode);
    final active = await FlutterOverlayWindow.isActive();

    if (!active) {
      await FlutterOverlayWindow.showOverlay(
        enableDrag: enableDrag,
        flag: OverlayFlag.focusPointer,
        alignment: OverlayAlignment.topLeft,
        height: geometry.height,
        width: geometry.width,
        positionGravity: PositionGravity.none,
        startPosition: _overlayWindowPosition,
        overlayTitle: '答题代理悬浮窗',
        overlayContent: '代理控制面板',
        visibility: NotificationVisibility.visibilityPublic,
      );
    } else {
      await FlutterOverlayWindow.moveOverlay(_overlayWindowPosition);
    }

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

  Future<void> _moveOverlayWindow(OverlayPosition position) async {
    final geometry = _overlayGeometryForMode(_overlayWindowMode);
    final clamped = _clampOverlayPosition(position, geometry);
    await FlutterOverlayWindow.moveOverlay(clamped);
    _overlayWindowPosition = clamped;
    await _pushOverlayState();
  }

  Future<OverlayPosition?> _safeGetOverlayPosition() async {
    try {
      return await FlutterOverlayWindow.getOverlayPosition();
    } catch (_) {
      return null;
    }
  }

  Map<String, dynamic> _overlayStatePayload() {
    final geometry = _overlayGeometryForMode(_overlayWindowMode);
    return <String, dynamic>{
      'mode': _overlayWindowMode,
      'position': <String, double>{
        'x': _overlayWindowPosition.x,
        'y': _overlayWindowPosition.y,
      },
      'width': geometry.width,
      'height': geometry.height,
      'active': overlayActive.value,
      'dragFrameThrottleEnabled': overlayDragFrameThrottleEnabled.value,
      'dragFrameThrottleIntervalMs': _overlayDragFrameThrottleIntervalMs,
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
      return const _OverlayWindowGeometry(width: 82, height: 82);
    }
    return const _OverlayWindowGeometry(width: 380, height: 560);
  }

  bool _overlayDragEnabledForMode(String mode) => mode == 'collapsed';

  OverlayPosition _resolveOverlayPositionForMode({
    required String currentMode,
    required String nextMode,
    required OverlayPosition currentPosition,
  }) {
    final currentGeometry = _overlayGeometryForMode(currentMode);
    final nextGeometry = _overlayGeometryForMode(nextMode);
    final screen = _logicalScreenSize();
    final anchorX = currentPosition.x + (currentGeometry.width / 2);
    final anchorY =
        currentPosition.y +
        (currentGeometry.height / 2) -
        (nextGeometry.height / 2);
    final targetX = anchorX < (screen.width / 2)
        ? 12.0
        : screen.width - nextGeometry.width - 12.0;

    return _clampOverlayPosition(
      OverlayPosition(targetX, anchorY),
      nextGeometry,
    );
  }

  OverlayPosition _clampOverlayPosition(
    OverlayPosition position,
    _OverlayWindowGeometry geometry,
  ) {
    final screen = _logicalScreenSize();
    final maxX = math.max(12.0, screen.width - geometry.width - 12);
    final maxY = math.max(12.0, screen.height - geometry.height - 12);
    return OverlayPosition(
      position.x.clamp(12.0, maxX).toDouble(),
      position.y.clamp(12.0, maxY).toDouble(),
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
    final active = overlayActive.value || await FlutterOverlayWindow.isActive();
    if (!active) {
      return;
    }

    await FlutterOverlayWindow.closeOverlay();
    overlayActive.value = false;
    _appendLog('info', '进入系统设置前已关闭悬浮窗，避免遮挡权限页面。');
    await _broadcastOverlaySnapshot();
  }

  Future<void> _launchSystemSettingsFlow(Future<void> Function() action) async {
    if (_systemSettingsFlowActive) {
      return;
    }

    _systemSettingsFlowActive = true;
    try {
      await action();
    } catch (error) {
      _systemSettingsFlowActive = false;
      final payload = <String, dynamic>{
        'ok': false,
        'message': error.toString(),
      };
      _setPayload(payload);
      _appendLog('error', error.toString());
    }
  }

  void _syncAgentConfig(Map<String, dynamic> payload) {
    agentConfig.assignAll(payload);
    providerUrlController.text = payload['providerUrl']?.toString() ?? '';
    modelController.text = payload['model']?.toString() ?? modelController.text;
    taskPromptController.text =
        payload['taskPrompt']?.toString() ?? taskPromptController.text;
    apiKeyController.text = _currentConfig.apiKey;
    autoSubmit.value = payload['autoSubmit'] == true;
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
    final timeLabel = _formatLogTime(DateTime.fromMillisecondsSinceEpoch(nowMs));
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
      'overlayState': _overlayStatePayload(),
      'lastPayload': lastPayload.value,
      'logs': logs.take(20).toList(),
    });
  }

  Future<void> _pushOverlayEvent(String type, Map<String, dynamic> payload) {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': type,
      'payload': payload,
    });
  }

  Future<void> _sendOverlayMessage(Map<String, dynamic> payload) async {
    final overlayPort = IsolateNameServer.lookupPortByName(
      OverlayMessageBridge.overlayPortName,
    );
    if (overlayPort != null) {
      overlayPort.send(jsonEncode(payload));
      return;
    }
    if (!overlayActive.value) {
      return;
    }
    await FlutterOverlayWindow.shareData(jsonEncode(payload));
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
