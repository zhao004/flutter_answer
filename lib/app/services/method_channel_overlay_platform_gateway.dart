import 'dart:async';

import 'package:flutter/services.dart';

import '../models/overlay_window_position.dart';
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
  Stream<Map<String, dynamic>> events() {
    return _eventChannel
        .receiveBroadcastStream(_clientId)
        .map<Map<String, dynamic>>(_normalizeMap);
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
  Future<Map<String, dynamic>> getOverlayState() async {
    final response = await _methodChannel.invokeMethod<dynamic>(
      'getOverlayState',
    );
    return _normalizeMap(response);
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
      return value.map<dynamic>(_normalizeValue).toList(growable: false);
    }
    return value;
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
