import 'dart:async';

import 'package:flutter/services.dart';

import '../models/overlay_state_snapshot.dart';
import '../models/overlay_window_position.dart';
import '../models/overlay_platform_event.dart';
import 'platform_payload_exception.dart';
import 'overlay_platform_gateway.dart';

class MethodChannelOverlayPlatformGateway implements OverlayPlatformGateway {
  MethodChannelOverlayPlatformGateway._();

  static final MethodChannelOverlayPlatformGateway instance =
      MethodChannelOverlayPlatformGateway._();

  static const MethodChannel _methodChannel = MethodChannel(
    'flutter_answer/overlay_methods',
  );
  static const EventChannel _eventChannel = EventChannel(
    'flutter_answer/overlay_events',
  );
  final String _clientId = 'overlay_${DateTime.now().microsecondsSinceEpoch}';

  @override
  Stream<OverlayPlatformEvent> events() {
    return _eventChannel
        .receiveBroadcastStream(_clientId)
        .map<OverlayPlatformEvent>(OverlayPlatformEvent.fromDynamic);
  }

  @override
  Future<bool> isPermissionGranted() async {
    return _readBool(
      await _methodChannel.invokeMethod<dynamic>('isPermissionGranted'),
    );
  }

  @override
  Future<bool> requestPermission() async {
    return _readBool(
      await _methodChannel.invokeMethod<dynamic>('requestPermission'),
    );
  }

  @override
  Future<bool> isActive() async {
    return _readBool(await _methodChannel.invokeMethod<dynamic>('isActive'));
  }

  @override
  Future<OverlayStateSnapshot> getOverlayState() async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'getOverlayState',
    );
    final payload = PlatformPayloadReader.requireMap(
      response,
      context: '读取悬浮窗状态',
    );
    try {
      return OverlayStateSnapshot.fromMap(payload);
    } on ArgumentError catch (error) {
      throw PlatformPayloadException('读取悬浮窗状态失败：$error');
    }
  }

  @override
  Future<void> showOverlay({
    required String mode,
    required OverlayWindowPosition position,
  }) {
    return _methodChannel.invokeMethod<void>('showOverlay', <String, dynamic>{
      'mode': mode,
      'position': position.toMap(),
    });
  }

  @override
  Future<void> closeOverlay() {
    return _methodChannel.invokeMethod<void>('closeOverlay');
  }

  @override
  Future<void> moveOverlay(OverlayWindowPosition position) {
    return _methodChannel.invokeMethod<void>('moveOverlay', <String, dynamic>{
      'position': position.toMap(),
    });
  }

  @override
  Future<void> sendMessage(Map<String, dynamic> payload) {
    return _methodChannel.invokeMethod<void>(
      'sendOverlayMessage',
      <String, dynamic>{'payload': payload},
    );
  }

  static bool _readBool(dynamic value) {
    if (value is bool) {
      return value;
    }
    if (value is num) {
      return value != 0;
    }
    return value?.toString().toLowerCase() == 'true';
  }
}
