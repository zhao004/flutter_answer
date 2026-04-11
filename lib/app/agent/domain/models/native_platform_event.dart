import 'dart:convert';

class NativePlatformEvent {
  const NativePlatformEvent({
    required this.type,
    this.level,
    this.message,
    this.tool,
    this.ok,
    this.payload,
    this.timestamp,
  });

  final String type;
  final String? level;
  final String? message;
  final String? tool;
  final bool? ok;
  final Map<String, dynamic>? payload;
  final int? timestamp;

  factory NativePlatformEvent.fromDynamic(dynamic raw) {
    return NativePlatformEvent.fromMap(_parseMap(raw));
  }

  factory NativePlatformEvent.fromMap(Map<String, dynamic> json) {
    return NativePlatformEvent(
      type: json['type']?.toString() ?? 'event',
      level: json['level']?.toString(),
      message: json['message']?.toString(),
      tool: json['tool']?.toString(),
      ok: json['ok'] as bool?,
      payload: _asMap(json['payload']),
      timestamp: _readInt(json['timestamp']),
    );
  }

  static Map<String, dynamic> _parseMap(dynamic raw) {
    if (raw is Map) {
      return raw.map(
        (dynamic key, dynamic value) => MapEntry(key.toString(), value),
      );
    }
    if (raw is String && raw.isNotEmpty) {
      final decoded = jsonDecode(raw);
      if (decoded is Map) {
        return decoded.map(
          (dynamic key, dynamic value) => MapEntry(key.toString(), value),
        );
      }
    }
    throw const FormatException('原生事件不是合法对象。');
  }

  static Map<String, dynamic>? _asMap(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) => MapEntry(key.toString(), item),
      );
    }
    return null;
  }

  static int? _readInt(dynamic value) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '');
  }
}
