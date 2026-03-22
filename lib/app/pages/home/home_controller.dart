import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:get/get.dart';

import '../../agent/data/agent_config_store.dart';
import '../../agent/data/agent_platform_gateway.dart';
import '../../agent/domain/models/agent_config.dart';
import '../../agent/domain/models/agent_run_state.dart';
import '../../agent/domain/models/connectivity_probe_result.dart';
import '../../agent/domain/models/native_platform_event.dart';
import '../../agent/domain/models/native_tool_execution.dart';
import '../../agent/domain/services/langchain_agent_runtime_service.dart';
import '../../services/overlay_message_bridge.dart';

class ProbeConversationMessage {
  const ProbeConversationMessage({
    required this.role,
    required this.content,
    this.includeInContext = true,
    this.failed = false,
  });

  final String role;
  final String content;
  final bool includeInContext;
  final bool failed;

  bool get isUser => role == 'user';

  ProbeConversationMessage copyWith({
    String? role,
    String? content,
    bool? includeInContext,
    bool? failed,
  }) {
    return ProbeConversationMessage(
      role: role ?? this.role,
      content: content ?? this.content,
      includeInContext: includeInContext ?? this.includeInContext,
      failed: failed ?? this.failed,
    );
  }

  Map<String, String> toRequestMap() {
    return <String, String>{'role': role, 'content': content};
  }
}

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
  final connectivityInputController = TextEditingController(
    text: '请回复“模型连通正常”，并用一句话说明你当前可以继续对话。',
  );

  final autoSubmit = false.obs;
  final overlayPermissionGranted = false.obs;
  final overlayActive = false.obs;
  final isBusy = false.obs;
  final isConnectivityTesting = false.obs;
  final connectivityMessages = <ProbeConversationMessage>[].obs;
  final connectivityNotice = '发送一条消息，测试当前模型是否可以正常回复。'.obs;
  final connectivityLastOk = RxnBool();
  final connectivityLatencyMs = RxnInt();

  StreamSubscription<NativePlatformEvent>? _eventSubscription;
  StreamSubscription<dynamic>? _overlaySubscription;
  StreamSubscription<dynamic>? _overlayPortSubscription;

  AgentConfig _currentConfig = const AgentConfig();
  AgentRunState _currentRunState = AgentRunState.idle();
  bool _systemSettingsFlowActive = false;

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
    connectivityInputController.dispose();
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
      _appendLog(
        'info',
        'Agent config, including the API key, is now saved locally.',
      );
    }

    return true;
  }

  Future<void> startAgentRun() async {
    if (agentRunning) {
      return;
    }

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
          message: 'Set an API key before running the agent.',
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
      message: 'Preparing LangChain agent runtime.',
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
          message: 'Agent is not running.',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    _applyRunState(
      _currentRunState.copyWith(
        running: true,
        status: 'stopping',
        message: 'Stop requested by operator.',
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  Future<void> sendConnectivityMessage() async {
    if (isConnectivityTesting.value || isBusy.value || agentRunning) {
      return;
    }

    final prompt = connectivityInputController.text.trim();
    if (prompt.isEmpty) {
      connectivityLastOk.value = false;
      connectivityNotice.value = '请先输入一条测试消息。';
      _appendLog('warn', '模型连通性测试消息不能为空。');
      return;
    }

    final saved = await saveAgentConfig(announce: false);
    if (!saved) {
      connectivityLastOk.value = false;
      connectivityNotice.value = '请先补全提供商配置后再测试模型连通性。';
      return;
    }

    if (!_currentConfig.hasApiKey) {
      connectivityLastOk.value = false;
      connectivityNotice.value = '请先输入 API key。';
      return;
    }

    final pendingIndex = connectivityMessages.length;
    connectivityMessages.add(
      ProbeConversationMessage(role: 'user', content: prompt),
    );
    connectivityInputController.clear();
    connectivityLastOk.value = null;
    connectivityLatencyMs.value = null;
    connectivityNotice.value = '正在等待模型回复...';
    isConnectivityTesting.value = true;

    try {
      final payload = await _runtimeService.sendConnectivityProbe(
        config: _currentConfig,
        messages: connectivityMessages
            .where((message) => message.includeInContext)
            .map((message) => message.toRequestMap())
            .toList(),
      );
      _handleConnectivityResult(payload, pendingIndex);
    } catch (error) {
      _markConnectivityMessageFailed(pendingIndex);
      connectivityLastOk.value = false;
      connectivityNotice.value = error.toString();
      _setPayload(<String, dynamic>{'ok': false, 'message': error.toString()});
      _appendLog('error', '模型连通性测试失败：${error.toString()}');
    } finally {
      isConnectivityTesting.value = false;
    }
  }

  void clearConnectivityConversation() {
    connectivityMessages.clear();
    connectivityNotice.value = '发送一条消息，测试当前模型是否可以正常回复。';
    connectivityLastOk.value = null;
    connectivityLatencyMs.value = null;
  }

  Future<void> syncOverlayState() async {
    final granted = await FlutterOverlayWindow.isPermissionGranted() == true;
    final active = await FlutterOverlayWindow.isActive() == true;
    overlayPermissionGranted.value = granted;
    overlayActive.value = active;
    await _broadcastOverlaySnapshot();
  }

  Future<void> requestOverlayPermission() async {
    if (_systemSettingsFlowActive) {
      return;
    }

    _systemSettingsFlowActive = true;
    _appendLog('info', '正在打开系统悬浮窗权限页面。');
    try {
      unawaited(
        FlutterOverlayWindow.requestPermission()
            .then((bool? granted) async {
              overlayPermissionGranted.value = granted == true;
              if (granted == true) {
                _appendLog('info', '已获得悬浮窗权限。');
              }
              await _broadcastOverlaySnapshot();
            })
            .catchError((Object error) {
              _appendLog('error', error.toString());
            })
            .whenComplete(() {
              _systemSettingsFlowActive = false;
            }),
      );
    } catch (error) {
      _systemSettingsFlowActive = false;
      _appendLog('error', error.toString());
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

      await FlutterOverlayWindow.showOverlay(
        enableDrag: true,
        flag: OverlayFlag.focusPointer,
        alignment: OverlayAlignment.topCenter,
        height: 520,
        width: WindowSize.matchParent,
        startPosition: const OverlayPosition(0, 80),
        overlayTitle: '答题代理悬浮窗',
        overlayContent: '代理控制面板',
        visibility: NotificationVisibility.visibilityPublic,
      );
      overlayActive.value = true;
      await _broadcastOverlaySnapshot();
    });
  }

  Future<void> closeOverlay() async {
    await _runAction(() async {
      await FlutterOverlayWindow.closeOverlay();
      overlayActive.value = false;
      await _broadcastOverlaySnapshot();
    });
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
      _appendLog(event.level ?? 'info', event.message ?? '');
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
      unawaited(_broadcastOverlaySnapshot());
      return;
    }
    if (pluginEvent == 'overlay_closed') {
      overlayActive.value = false;
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

  void _handleConnectivityResult(
    ConnectivityProbeResult payload,
    int pendingIndex,
  ) {
    connectivityLastOk.value = payload.ok;
    connectivityLatencyMs.value = payload.latencyMs;

    if (payload.ok && payload.reply.trim().isNotEmpty) {
      connectivityMessages.add(
        ProbeConversationMessage(role: 'assistant', content: payload.reply),
      );
      connectivityNotice.value = '模型回复成功，可继续对话。';
      _appendLog(
        'info',
        '模型连通性测试成功${payload.latencyMs == null ? '' : '，耗时 ${payload.latencyMs}ms'}。',
      );
    } else {
      _markConnectivityMessageFailed(pendingIndex);
      connectivityNotice.value = payload.message;
      connectivityLastOk.value = false;
      _appendLog('error', '模型连通性测试失败：${payload.message}');
    }

    _setPayload(payload.toMap());
  }

  void _markConnectivityMessageFailed(int index) {
    if (index < 0 || index >= connectivityMessages.length) {
      return;
    }
    final message = connectivityMessages[index];
    connectivityMessages[index] = message.copyWith(
      includeInContext: false,
      failed: true,
    );
  }

  void _appendLog(String level, String message) {
    final levelLabel = switch (level.toLowerCase()) {
      'info' => '信息',
      'warn' => '警告',
      'error' => '错误',
      _ => level.toUpperCase(),
    };
    final line = '[$levelLabel] $message';
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

  Future<void> _broadcastOverlaySnapshot() {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': 'snapshot',
      'serviceStatus': Map<String, dynamic>.from(serviceStatus),
      'agentConfig': Map<String, dynamic>.from(agentConfig),
      'agentRunStatus': Map<String, dynamic>.from(agentRunStatus),
      'lastPayload': lastPayload.value,
      'logs': logs.take(8).toList(),
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
}
