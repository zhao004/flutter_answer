class NativeToolExecutionRequest {
  NativeToolExecutionRequest({
    required this.toolName,
    this.arguments = const <String, dynamic>{},
    String? requestId,
  }) : requestId = requestId ?? 'req_${DateTime.now().microsecondsSinceEpoch}';

  final String requestId;
  final String toolName;
  final Map<String, dynamic> arguments;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'requestId': requestId,
      'toolName': toolName,
      'arguments': arguments,
    };
  }
}

class NativeToolExecutionResult {
  const NativeToolExecutionResult({
    required this.requestId,
    required this.tool,
    required this.ok,
    required this.result,
    this.message,
    this.startedAt,
    this.finishedAt,
    this.durationMs,
  });

  final String requestId;
  final String tool;
  final bool ok;
  final String? message;
  final Map<String, dynamic> result;
  final int? startedAt;
  final int? finishedAt;
  final int? durationMs;

  factory NativeToolExecutionResult.fromMap(Map<String, dynamic> json) {
    return NativeToolExecutionResult(
      requestId: json['requestId']?.toString() ?? '',
      tool: json['tool']?.toString() ?? '',
      ok: json['ok'] == true,
      message: json['message']?.toString(),
      result: _asMap(json['result']) ?? const <String, dynamic>{},
      startedAt: _readInt(json['startedAt']),
      finishedAt: _readInt(json['finishedAt']),
      durationMs: _readInt(json['durationMs']),
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'requestId': requestId,
      'tool': tool,
      'ok': ok,
      'message': message,
      'result': result,
      'startedAt': startedAt,
      'finishedAt': finishedAt,
      'durationMs': durationMs,
    };
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
