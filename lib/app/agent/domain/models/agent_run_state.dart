class AgentRunState {
  const AgentRunState({
    required this.running,
    required this.status,
    required this.step,
    required this.message,
    required this.updatedAt,
    this.lastTool,
    this.lastModelText,
  });

  final bool running;
  final String status;
  final int step;
  final String message;
  final String? lastTool;
  final String? lastModelText;
  final int updatedAt;

  factory AgentRunState.idle() {
    return AgentRunState(
      running: false,
      status: 'idle',
      step: 0,
      message: '空闲。',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
  }

  factory AgentRunState.fromMap(Map<String, dynamic> json) {
    return AgentRunState(
      running: json['running'] == true,
      status: json['status']?.toString() ?? 'idle',
      step: _readInt(json['step']),
      message: json['message']?.toString() ?? '',
      lastTool: json['lastTool']?.toString(),
      lastModelText: json['lastModelText']?.toString(),
      updatedAt: _readInt(json['updatedAt']),
    );
  }

  AgentRunState copyWith({
    bool? running,
    String? status,
    int? step,
    String? message,
    String? lastTool,
    String? lastModelText,
    int? updatedAt,
  }) {
    return AgentRunState(
      running: running ?? this.running,
      status: status ?? this.status,
      step: step ?? this.step,
      message: message ?? this.message,
      lastTool: lastTool ?? this.lastTool,
      lastModelText: lastModelText ?? this.lastModelText,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'running': running,
      'status': status,
      'step': step,
      'message': message,
      'lastTool': lastTool,
      'lastModelText': lastModelText,
      'updatedAt': updatedAt,
    };
  }

  static int _readInt(dynamic value) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }
}
