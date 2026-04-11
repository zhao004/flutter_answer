import 'dart:convert';

/// 平台通道负载异常。
///
/// 这里单独建模异常类型，避免协议漂移时被静默吞成空对象，
/// 让调用方能明确区分“业务失败”和“平台协议不兼容”两类问题。
class PlatformPayloadException implements Exception {
  const PlatformPayloadException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// 平台负载读取工具。
///
/// 统一负责：
/// 1. 将 MethodChannel/EventChannel 返回值归一化成只含字符串 key 的 Map；
/// 2. 在结构错误时抛出明确异常，而不是回退成空对象；
/// 3. 提供常用数值读取，避免各处重复写脆弱的解析逻辑。
class PlatformPayloadReader {
  const PlatformPayloadReader._();

  static Map<String, dynamic> requireMap(
    dynamic value, {
    required String context,
  }) {
    final normalized = tryMap(value);
    if (normalized == null) {
      throw PlatformPayloadException('$context 返回的负载不是合法对象。');
    }
    return normalized;
  }

  static Map<String, dynamic>? tryMap(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) =>
            MapEntry(key.toString(), _normalizeValue(item)),
      );
    }
    if (value is String && value.isNotEmpty) {
      try {
        final decoded = jsonDecode(value);
        if (decoded is Map) {
          return tryMap(decoded);
        }
      } on FormatException {
        return null;
      }
    }
    return null;
  }

  static List<dynamic>? tryList(dynamic value) {
    if (value is! List) {
      return null;
    }
    return value.map<dynamic>(_normalizeValue).toList(growable: false);
  }

  static int? readInt(dynamic value) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '');
  }

  static double? readDouble(dynamic value) {
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value?.toString() ?? '');
  }

  static dynamic _normalizeValue(dynamic value) {
    if (value is Map) {
      return tryMap(value);
    }
    if (value is List) {
      return value.map<dynamic>(_normalizeValue).toList(growable: false);
    }
    return value;
  }
}
