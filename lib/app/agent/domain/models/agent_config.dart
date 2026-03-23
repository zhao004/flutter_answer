class AgentConfig {
  const AgentConfig({
    this.providerUrl = '',
    this.apiKey = '',
    this.model = 'gpt-5-mini',
    this.taskPrompt =
        'Read the current question page, answer carefully, prefer tools, and stop at the submit page for manual confirmation.',
    this.maxIterations = defaultMaxIterations,
    this.maxExecutionMinutes = defaultMaxExecutionMinutes,
    this.maxRepeatedSameToolCall = defaultMaxRepeatedSameToolCall,
    this.maxNoProgressActionStreak = defaultMaxNoProgressActionStreak,
  });

  static const int defaultMaxIterations = 720;
  static const int minMaxIterations = 80;
  static const int maxMaxIterations = 2000;
  static const int defaultMaxExecutionMinutes = 15;
  static const int minMaxExecutionMinutes = 2;
  static const int maxMaxExecutionMinutes = 60;
  static const int defaultMaxRepeatedSameToolCall = 12;
  static const int minMaxRepeatedSameToolCall = 4;
  static const int maxMaxRepeatedSameToolCall = 40;
  static const int defaultMaxNoProgressActionStreak = 32;
  static const int minMaxNoProgressActionStreak = 8;
  static const int maxMaxNoProgressActionStreak = 120;

  final String providerUrl;
  final String apiKey;
  final String model;
  final String taskPrompt;
  final int maxIterations;
  final int maxExecutionMinutes;
  final int maxRepeatedSameToolCall;
  final int maxNoProgressActionStreak;

  bool get hasApiKey => apiKey.trim().isNotEmpty;
  int get sanitizedMaxIterations =>
      _clampInt(maxIterations, minMaxIterations, maxMaxIterations);
  int get sanitizedMaxExecutionMinutes => _clampInt(
    maxExecutionMinutes,
    minMaxExecutionMinutes,
    maxMaxExecutionMinutes,
  );
  int get sanitizedMaxRepeatedSameToolCall => _clampInt(
    maxRepeatedSameToolCall,
    minMaxRepeatedSameToolCall,
    maxMaxRepeatedSameToolCall,
  );
  int get sanitizedMaxNoProgressActionStreak => _clampInt(
    maxNoProgressActionStreak,
    minMaxNoProgressActionStreak,
    maxMaxNoProgressActionStreak,
  );

  AgentConfig copyWith({
    String? providerUrl,
    String? apiKey,
    String? model,
    String? taskPrompt,
    int? maxIterations,
    int? maxExecutionMinutes,
    int? maxRepeatedSameToolCall,
    int? maxNoProgressActionStreak,
  }) {
    return AgentConfig(
      providerUrl: providerUrl ?? this.providerUrl,
      apiKey: apiKey ?? this.apiKey,
      model: model ?? this.model,
      taskPrompt: taskPrompt ?? this.taskPrompt,
      maxIterations: maxIterations ?? this.maxIterations,
      maxExecutionMinutes: maxExecutionMinutes ?? this.maxExecutionMinutes,
      maxRepeatedSameToolCall:
          maxRepeatedSameToolCall ?? this.maxRepeatedSameToolCall,
      maxNoProgressActionStreak:
          maxNoProgressActionStreak ?? this.maxNoProgressActionStreak,
    );
  }

  AgentConfig sanitized() {
    return copyWith(
      maxIterations: sanitizedMaxIterations,
      maxExecutionMinutes: sanitizedMaxExecutionMinutes,
      maxRepeatedSameToolCall: sanitizedMaxRepeatedSameToolCall,
      maxNoProgressActionStreak: sanitizedMaxNoProgressActionStreak,
    );
  }

  Map<String, dynamic> toPublicMap() {
    final sanitizedConfig = sanitized();
    return <String, dynamic>{
      'providerUrl': sanitizedConfig.providerUrl,
      'hasApiKey': hasApiKey,
      'model': sanitizedConfig.model,
      'taskPrompt': sanitizedConfig.taskPrompt,
      'maxIterations': sanitizedConfig.maxIterations,
      'maxExecutionMinutes': sanitizedConfig.maxExecutionMinutes,
      'maxRepeatedSameToolCall': sanitizedConfig.maxRepeatedSameToolCall,
      'maxNoProgressActionStreak': sanitizedConfig.maxNoProgressActionStreak,
    };
  }

  Map<String, dynamic> toPersistedMap() {
    final sanitizedConfig = sanitized();
    return <String, dynamic>{
      'providerUrl': sanitizedConfig.providerUrl,
      'apiKey': sanitizedConfig.apiKey,
      'model': sanitizedConfig.model,
      'taskPrompt': sanitizedConfig.taskPrompt,
      'maxIterations': sanitizedConfig.maxIterations,
      'maxExecutionMinutes': sanitizedConfig.maxExecutionMinutes,
      'maxRepeatedSameToolCall': sanitizedConfig.maxRepeatedSameToolCall,
      'maxNoProgressActionStreak': sanitizedConfig.maxNoProgressActionStreak,
    };
  }

  factory AgentConfig.fromPersistedMap(Map<String, dynamic> json) {
    return AgentConfig(
      providerUrl: json['providerUrl']?.toString() ?? '',
      apiKey: json['apiKey']?.toString() ?? '',
      model: json['model']?.toString().trim().isNotEmpty == true
          ? json['model'].toString()
          : 'gpt-5-mini',
      taskPrompt: json['taskPrompt']?.toString().trim().isNotEmpty == true
          ? json['taskPrompt'].toString()
          : const AgentConfig().taskPrompt,
      maxIterations: _readInt(json['maxIterations'], defaultMaxIterations),
      maxExecutionMinutes: _readInt(
        json['maxExecutionMinutes'],
        defaultMaxExecutionMinutes,
      ),
      maxRepeatedSameToolCall: _readInt(
        json['maxRepeatedSameToolCall'],
        defaultMaxRepeatedSameToolCall,
      ),
      maxNoProgressActionStreak: _readInt(
        json['maxNoProgressActionStreak'],
        defaultMaxNoProgressActionStreak,
      ),
    ).sanitized();
  }

  static int _readInt(dynamic value, int fallback) {
    if (value is int) {
      return value;
    }
    return int.tryParse(value?.toString() ?? '') ?? fallback;
  }

  static int _clampInt(int value, int min, int max) {
    return value.clamp(min, max).toInt();
  }
}
