import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';
import 'package:get/get.dart';

import '../../services/agent_config_store.dart';
import '../../services/agent_bridge.dart';
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
  HomeController();

  final AgentBridge _agentBridge = AgentBridge.instance;
  final AgentConfigStore _configStore = AgentConfigStore.instance;
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
    text: '读取当前答题页面并继续作答，优先使用工具，不要输出解释。',
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

  StreamSubscription<Map<String, dynamic>>? _eventSubscription;
  StreamSubscription<dynamic>? _overlaySubscription;
  StreamSubscription<dynamic>? _overlayPortSubscription;

  bool get agentRunning => agentRunStatus['running'] == true;

  @override
  void onInit() {
    super.onInit();
    WidgetsBinding.instance.addObserver(this);
    _registerMainPort();
    _eventSubscription = _agentBridge.events().listen(_handleNativeEvent);
    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen(
      _handleOverlayEvent,
    );
    unawaited(_bootstrap());
  }

  @override
  void onClose() {
    WidgetsBinding.instance.removeObserver(this);
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
      unawaited(_refreshSystemState());
    }
  }

  Future<void> _bootstrap() async {
    await _restoreSavedConfig();
    await refreshServiceStatus();
    await refreshAgentConfig();
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
      return;
    }

    _syncAgentConfig(savedConfig);
    final payload = await _agentBridge.setAgentConfig(
      providerUrl: savedConfig['providerUrl']?.toString() ?? '',
      model: savedConfig['model']?.toString() ?? modelController.text,
      taskPrompt:
          savedConfig['taskPrompt']?.toString() ?? taskPromptController.text,
      autoSubmit: savedConfig['autoSubmit'] == true,
    );
    final configPayload = _normalizeMap(payload['config']) ?? payload;
    _syncAgentConfig(configPayload);
  }

  Future<void> refreshServiceStatus() async {
    await _runAction(() async {
      final payload = await _agentBridge.getServiceStatus();
      serviceStatus.assignAll(payload);
      _setPayload(payload);
      await _pushOverlayEvent('service_status', payload);
    });
  }

  Future<void> openAccessibilitySettings() async {
    await _runAction(() async {
      final payload = await _agentBridge.openAccessibilitySettings();
      _setPayload(payload);
    });
  }

  Future<void> toggleAccessibilityService(bool enabled) async {
    final current = serviceStatus['enabled'] == true;
    if (current == enabled) {
      return;
    }

    _appendLog('info', enabled ? '请在系统设置中开启无障碍服务。' : '请在系统设置中关闭无障碍服务。');
    await openAccessibilitySettings();
  }

  Future<void> fetchQuestionContext() async {
    await _runAction(() async {
      final payload = await _agentBridge.getQuestionContext();
      _setPayload(payload);
    });
  }

  Future<void> fetchUiSnapshot() async {
    await _runAction(() async {
      final payload = await _agentBridge.getUiSnapshot();
      _setPayload(payload);
    });
  }

  Future<void> waitForIdle() async {
    await _runAction(() async {
      final payload = await _agentBridge.waitForIdle();
      _setPayload(payload);
    });
  }

  Future<void> scrollScreen({
    String direction = 'down',
    String amount = 'medium',
  }) async {
    await _runAction(() async {
      final payload = await _agentBridge.scrollScreen(
        direction: direction,
        amount: amount,
      );
      _setPayload(payload);
    });
  }

  Future<void> pressGlobal({String action = 'back'}) async {
    await _runAction(() async {
      final payload = await _agentBridge.pressGlobal(action);
      _setPayload(payload);
    });
  }

  Future<void> refreshAgentConfig() async {
    await _runAction(() async {
      final payload = await _agentBridge.getAgentConfig();
      _syncAgentConfig(payload);
      _setPayload(payload);
      await _pushOverlayEvent('agent_config', payload);
    });
  }

  Future<void> refreshAgentRunStatus() async {
    await _runAction(() async {
      final payload = await _agentBridge.getAgentRunStatus();
      agentRunStatus.assignAll(payload);
      _setPayload(payload);
      await _pushOverlayEvent('agent_status', payload);
    });
  }

  Future<bool> saveAgentConfig({bool announce = true}) async {
    final providerUrl = providerUrlController.text.trim();
    if (providerUrl.isEmpty) {
      _appendLog('error', '请先填写提供商 URL。');
      return false;
    }

    var saved = false;
    await _runAction(() async {
      final payload = await _agentBridge.setAgentConfig(
        providerUrl: providerUrl,
        apiKey: apiKeyController.text.trim().isEmpty
            ? null
            : apiKeyController.text.trim(),
        model: modelController.text.trim(),
        taskPrompt: taskPromptController.text.trim(),
        autoSubmit: autoSubmit.value,
      );
      final configPayload = _normalizeMap(payload['config']) ?? payload;
      _syncAgentConfig(configPayload);
      await _configStore.save(
        providerUrl: providerUrl,
        model: modelController.text.trim(),
        taskPrompt: taskPromptController.text.trim(),
        autoSubmit: autoSubmit.value,
      );
      if (configPayload['hasApiKey'] == true) {
        apiKeyController.clear();
      }
      _setPayload(payload);
      await _pushOverlayEvent('agent_config', configPayload);
      if (announce) {
        _appendLog('info', '代理配置已保存到本地，API 密钥仅保存在当前会话内存。');
      }
      saved = true;
    });
    return saved;
  }

  Future<void> startAgentRun() async {
    final saved = await saveAgentConfig(announce: false);
    if (!saved) {
      final failure = <String, dynamic>{
        ...agentRunStatus,
        'running': false,
        'status': 'failed',
        'message': '请先填写提供商 URL。',
        'updatedAt': DateTime.now().millisecondsSinceEpoch,
      };
      agentRunStatus.assignAll(failure);
      _setPayload(failure);
      await _pushOverlayEvent('agent_status', failure);
      return;
    }
    await _runAction(() async {
      final payload = await _agentBridge.startAgentRun();
      final statusPayload = _resolveAgentStatusPayload(
        payload,
        fallbackStatus: 'failed',
      );
      agentRunStatus.assignAll(statusPayload);
      _setPayload(statusPayload);
      await _pushOverlayEvent('agent_status', statusPayload);
    });
  }

  Future<void> stopAgentRun() async {
    await _runAction(() async {
      final payload = await _agentBridge.stopAgentRun();
      final statusPayload = _resolveAgentStatusPayload(payload);
      agentRunStatus.assignAll(statusPayload);
      _setPayload(statusPayload);
      await _pushOverlayEvent('agent_status', statusPayload);
    });
  }

  Future<void> sendConnectivityMessage() async {
    if (isConnectivityTesting.value || isBusy.value) {
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
      connectivityNotice.value = '请先完善提供商配置后再测试模型连通性。';
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
      final payload = await _agentBridge.sendConnectivityProbe(
        messages: connectivityMessages
            .where((message) => message.includeInContext)
            .map((message) => message.toRequestMap())
            .toList(),
      );
      final ok = payload['ok'] == true;
      final reply = payload['reply']?.toString().trim() ?? '';
      final latencyMs = _readInt(payload['latencyMs']);
      connectivityLastOk.value = ok;
      connectivityLatencyMs.value = latencyMs;
      if (ok && reply.isNotEmpty) {
        connectivityMessages.add(
          ProbeConversationMessage(role: 'assistant', content: reply),
        );
        connectivityNotice.value = '模型回复成功，可继续对话。';
        _appendLog(
          'info',
          '模型连通性测试成功${latencyMs == null ? '' : '，耗时 ${latencyMs}ms'}。',
        );
      } else {
        _markConnectivityMessageFailed(pendingIndex);
        final message = payload['message']?.toString().trim();
        connectivityNotice.value = (message == null || message.isEmpty)
            ? '模型未返回有效回复。'
            : message;
        connectivityLastOk.value = false;
        _appendLog('error', '模型连通性测试失败：${connectivityNotice.value}');
      }
      _setPayload(payload);
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
    await _runAction(() async {
      final granted = await FlutterOverlayWindow.requestPermission() == true;
      overlayPermissionGranted.value = granted;
      _appendLog(
        granted ? 'info' : 'warn',
        granted ? '已获得悬浮窗权限。' : '尚未获得悬浮窗权限。',
      );
      await _broadcastOverlaySnapshot();
    });
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

  void _handleNativeEvent(Map<String, dynamic> event) {
    final type = event['type'] as String? ?? 'event';
    if (type == 'log') {
      _appendLog(
        event['level']?.toString() ?? 'info',
        event['message']?.toString() ?? '',
      );
      return;
    }

    if (type == 'tool_result') {
      final payload = _normalizeMap(event['payload']);
      if (payload != null) {
        _setPayload(payload);
      }
      return;
    }

    if (type == 'agent_status') {
      final payload = _normalizeMap(event['payload']);
      if (payload != null) {
        agentRunStatus.assignAll(payload);
        _setPayload(payload);
        unawaited(_pushOverlayEvent('agent_status', payload));
      }
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

  void _syncAgentConfig(Map<String, dynamic> payload) {
    agentConfig.assignAll(payload);
    providerUrlController.text = payload['providerUrl']?.toString() ?? '';
    modelController.text = payload['model']?.toString() ?? modelController.text;
    taskPromptController.text =
        payload['taskPrompt']?.toString() ?? taskPromptController.text;
    autoSubmit.value = payload['autoSubmit'] == true;
    if (payload['hasApiKey'] != true) {
      apiKeyController.clear();
    }
  }

  void _setPayload(Map<String, dynamic> payload) {
    if (payload.containsKey('enabled')) {
      serviceStatus.assignAll(payload);
      unawaited(_pushOverlayEvent('service_status', payload));
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

  Map<String, dynamic> _resolveAgentStatusPayload(
    Map<String, dynamic> payload, {
    String fallbackStatus = 'idle',
  }) {
    final nested = _normalizeMap(payload['status']);
    final message = payload['message']?.toString();
    if (nested != null && nested.isNotEmpty) {
      return <String, dynamic>{
        ...nested,
        if (message != null && message.isNotEmpty) 'message': message,
      };
    }
    return <String, dynamic>{
      ...agentRunStatus,
      'running': false,
      'status': fallbackStatus,
      if (message != null && message.isNotEmpty) 'message': message,
      'updatedAt': DateTime.now().millisecondsSinceEpoch,
    };
  }

  int? _readInt(dynamic value) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '');
  }
}
