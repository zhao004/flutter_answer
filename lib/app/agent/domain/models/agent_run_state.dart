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
      message: 'Idle.',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
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
}
