import 'dart:async';

import 'package:flutter/services.dart';

import '../domain/models/native_platform_event.dart';
import '../domain/models/native_tool_definition.dart';
import '../domain/models/native_tool_execution.dart';
import '../../services/platform_payload_exception.dart';
import 'agent_platform_gateway.dart';

class MethodChannelAgentPlatformGateway implements AgentPlatformGateway {
  MethodChannelAgentPlatformGateway._();

  static final MethodChannelAgentPlatformGateway instance =
      MethodChannelAgentPlatformGateway._();

  static const MethodChannel _methodChannel = MethodChannel(
    'flutter_answer/agent_methods',
  );
  static const EventChannel _eventChannel = EventChannel(
    'flutter_answer/agent_events',
  );
  final String _clientId = 'main_${DateTime.now().microsecondsSinceEpoch}';

  @override
  Stream<NativePlatformEvent> events() {
    return _eventChannel
        .receiveBroadcastStream(_clientId)
        .map<Map<String, dynamic>>(
          (dynamic event) =>
              PlatformPayloadReader.requireMap(event, context: 'Agent 事件流'),
        )
        .map(NativePlatformEvent.fromMap);
  }

  @override
  Future<Map<String, dynamic>> openAccessibilitySettings() {
    return _invoke('openAccessibilitySettings');
  }

  @override
  Future<List<NativeToolDefinition>> listTools() async {
    final payload = await _invoke('listTools');
    final tools = payload['tools'];
    if (tools is! List) {
      throw const PlatformPayloadException('工具清单负载缺少 tools 数组。');
    }

    return tools
        .whereType<Map>()
        .map<Map<String, dynamic>>(
          (Map<dynamic, dynamic> item) => item.map(
            (dynamic key, dynamic value) => MapEntry(key.toString(), value),
          ),
        )
        .map(NativeToolDefinition.fromMap)
        .toList(growable: false);
  }

  @override
  Future<NativeToolExecutionResult> executeTool(
    NativeToolExecutionRequest request,
  ) async {
    final payload = await _invoke('executeTool', request.toMap());
    return NativeToolExecutionResult.fromMap(payload);
  }

  Future<Map<String, dynamic>> _invoke(
    String method, [
    Map<String, dynamic>? arguments,
  ]) async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      method,
      arguments,
    );
    return PlatformPayloadReader.requireMap(response, context: '平台方法 $method');
  }
}
