part of 'home_controller.dart';

extension HomeControllerPayload on HomeController {
  void _handleNativeEvent(NativePlatformEvent event) {
    try {
      if (event.type == 'log') {
        _appendLog(
          event.level ?? 'info',
          event.message ?? '',
          timestampMs: event.timestamp,
        );
        return;
      }

      if (event.type == 'service_status') {
        final payload = ServiceStatusSnapshot.fromMap(
          event.payload ?? const <String, dynamic>{},
        );
        serviceStatus.value = payload;
        unawaited(_pushOverlayEvent('service_status', payload.toMap()));
        return;
      }

      if (event.type == 'agent_status') {
        final payload = AgentRunState.fromMap(
          event.payload ?? const <String, dynamic>{},
        );
        agentRunState.value = payload;
        unawaited(_pushOverlayEvent('agent_status', payload.toMap()));
        return;
      }

      if (event.type == 'tool_result') {
        _setPayload(<String, dynamic>{
          'tool': event.tool,
          'ok': event.ok,
          'result': event.payload ?? const <String, dynamic>{},
          'timestamp': event.timestamp,
        });
      }
    } on PlatformPayloadException catch (error) {
      _appendLog('error', error.toString(), syncOverlay: false);
    }
  }

  void _applyRunState(AgentRunState state, {bool updatePayload = true}) {
    agentRunState.value = state;
    if (updatePayload) {
      _setPayload(state.toMap());
    } else {
      unawaited(_pushOverlayEvent('agent_status', state.toMap()));
    }
  }

  void _setPayload(Map<String, dynamic> payload) {
    try {
      final resultPayload = PlatformPayloadReader.tryMap(payload['result']);
      final toolName = payload['tool']?.toString();

      if (toolName == 'get_service_status' && resultPayload != null) {
        final nextStatus = ServiceStatusSnapshot.fromMap(resultPayload);
        serviceStatus.value = nextStatus;
        unawaited(_pushOverlayEvent('service_status', nextStatus.toMap()));
      } else if (payload.containsKey('enabled')) {
        final nextStatus = ServiceStatusSnapshot.fromMap(payload);
        serviceStatus.value = nextStatus;
        unawaited(_pushOverlayEvent('service_status', nextStatus.toMap()));
      }

      if (payload.containsKey('hasApiKey') ||
          payload.containsKey('taskPrompt')) {
        final nextConfig = AgentConfig.fromPublicMap(
          payload,
          apiKey: currentConfig.value.apiKey,
        );
        _syncAgentConfig(nextConfig);
        unawaited(_pushOverlayEvent('agent_config', nextConfig.toPublicMap()));
      }

      if (payload.containsKey('running') || payload.containsKey('status')) {
        final nextState = AgentRunState.fromMap(payload);
        agentRunState.value = nextState;
        unawaited(_pushOverlayEvent('agent_status', nextState.toMap()));
      }
    } on PlatformPayloadException catch (error) {
      _appendLog('error', error.toString(), syncOverlay: false);
    }

    lastPayload.value = const JsonEncoder.withIndent('  ').convert(payload);
    unawaited(
      _sendOverlayMessage(<String, dynamic>{
        'source': 'main',
        'type': 'payload',
        'payload': lastPayload.value,
      }, logFailure: false),
    );
  }

  void _appendLog(
    String level,
    String message, {
    int? timestampMs,
    bool syncOverlay = true,
  }) {
    final normalizedMessage = message.trim();
    if (normalizedMessage.isEmpty) {
      return;
    }

    final nowMs = timestampMs ?? DateTime.now().millisecondsSinceEpoch;
    final line =
        '[${_formatLogTime(DateTime.fromMillisecondsSinceEpoch(nowMs))}]'
        '[${_levelLabel(level)}] $normalizedMessage';
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

    if (syncOverlay) {
      unawaited(
        _sendOverlayMessage(<String, dynamic>{
          'source': 'main',
          'type': 'log',
          'line': line,
        }, logFailure: false),
      );
    }
  }

  String _formatLogTime(DateTime time) {
    final hour = time.hour.toString().padLeft(2, '0');
    final minute = time.minute.toString().padLeft(2, '0');
    final second = time.second.toString().padLeft(2, '0');
    return '$hour:$minute:$second';
  }

  String _levelLabel(String level) {
    return switch (level.toLowerCase()) {
      'info' => '信息',
      'warn' => '警告',
      'error' => '错误',
      _ => level.toUpperCase(),
    };
  }

  Future<void> _broadcastOverlaySnapshot() {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': 'snapshot',
      'theme': AppThemeTokens.overlayThemePayload(),
      'serviceStatus': serviceStatus.value.toMap(),
      'agentConfig': currentConfig.value.toPublicMap(),
      'agentRunStatus': agentRunState.value.toMap(),
      'diagnosticHint': _currentOverlayDiagnosticHintPayload(),
      'overlayState': overlayState.value.toMap(),
      'lastPayload': lastPayload.value,
      'logs': logs.take(20).toList(),
    }, logFailure: false);
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
    return diagnosticHint?.toPayload();
  }

  Future<void> _sendOverlayMessage(
    Map<String, dynamic> payload, {
    bool logFailure = true,
  }) async {
    try {
      await _overlayPlatformGateway.sendMessage(payload);
    } catch (error) {
      if (logFailure) {
        _appendLog('warn', '悬浮窗同步失败：$error', syncOverlay: false);
      }
    }
  }

  Future<NativeToolExecutionResult> _executeTool(
    String toolName, {
    Map<String, dynamic> arguments = const <String, dynamic>{},
  }) {
    return _platformGateway.executeTool(
      NativeToolExecutionRequest(toolName: toolName, arguments: arguments),
    );
  }
}
