import 'dart:async';

import 'package:flutter/services.dart';

class AgentBridge {
  AgentBridge._();

  static final AgentBridge instance = AgentBridge._();

  static const MethodChannel _methodChannel = MethodChannel(
    'flutter_answer/agent_methods',
  );
  static const EventChannel _eventChannel = EventChannel(
    'flutter_answer/agent_events',
  );
  final String _clientId = 'main_${DateTime.now().microsecondsSinceEpoch}';

  Stream<Map<String, dynamic>> events() {
    return _eventChannel
        .receiveBroadcastStream(_clientId)
        .map<Map<String, dynamic>>(_normalizeMap);
  }

  Future<Map<String, dynamic>> getServiceStatus() {
    return _invoke('getServiceStatus');
  }

  Future<Map<String, dynamic>> openAccessibilitySettings() {
    return _invoke('openAccessibilitySettings');
  }

  Future<Map<String, dynamic>> getUiSnapshot() {
    return _invoke('getUiSnapshot');
  }

  Future<Map<String, dynamic>> getQuestionContext() {
    return _invoke('getQuestionContext');
  }

  Future<Map<String, dynamic>> waitForIdle({int timeoutMs = 1200}) {
    return _invoke('waitForIdle', {'timeoutMs': timeoutMs});
  }

  Future<Map<String, dynamic>> scrollScreen({
    required String direction,
    required String amount,
  }) {
    return _invoke('scrollScreen', {'direction': direction, 'amount': amount});
  }

  Future<Map<String, dynamic>> pressGlobal(String action) {
    return _invoke('pressGlobal', {'action': action});
  }

  Future<Map<String, dynamic>> getAgentConfig() {
    return _invoke('getAgentConfig');
  }

  Future<Map<String, dynamic>> setAgentConfig({
    required String providerUrl,
    String? apiKey,
    required String model,
    required String taskPrompt,
    required bool autoSubmit,
  }) {
    final arguments = <String, dynamic>{
      'providerUrl': providerUrl,
      'model': model,
      'taskPrompt': taskPrompt,
      'autoSubmit': autoSubmit,
    };
    if (apiKey != null) {
      arguments['apiKey'] = apiKey;
    }
    return _invoke('setAgentConfig', arguments);
  }

  Future<Map<String, dynamic>> getAgentRunStatus() {
    return _invoke('getAgentRunStatus');
  }

  Future<Map<String, dynamic>> startAgentRun() {
    return _invoke('startAgentRun');
  }

  Future<Map<String, dynamic>> stopAgentRun() {
    return _invoke('stopAgentRun');
  }

  Future<Map<String, dynamic>> _invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      method,
      arguments,
    );
    return _normalizeMap(response);
  }

  static Map<String, dynamic> _normalizeMap(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) =>
            MapEntry(key.toString(), _normalizeValue(item)),
      );
    }
    return <String, dynamic>{};
  }

  static dynamic _normalizeValue(dynamic value) {
    if (value is Map) {
      return _normalizeMap(value);
    }
    if (value is List) {
      return value.map<dynamic>(_normalizeValue).toList();
    }
    return value;
  }
}
