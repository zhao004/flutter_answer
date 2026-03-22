class AgentConfig {
  const AgentConfig({
    this.providerUrl = '',
    this.apiKey = '',
    this.model = 'gpt-5-mini',
    this.taskPrompt =
        'Read the current question page, answer carefully, prefer tools, and stop at the submit page unless auto submit is enabled.',
    this.autoSubmit = false,
  });

  final String providerUrl;
  final String apiKey;
  final String model;
  final String taskPrompt;
  final bool autoSubmit;

  bool get hasApiKey => apiKey.trim().isNotEmpty;

  AgentConfig copyWith({
    String? providerUrl,
    String? apiKey,
    String? model,
    String? taskPrompt,
    bool? autoSubmit,
  }) {
    return AgentConfig(
      providerUrl: providerUrl ?? this.providerUrl,
      apiKey: apiKey ?? this.apiKey,
      model: model ?? this.model,
      taskPrompt: taskPrompt ?? this.taskPrompt,
      autoSubmit: autoSubmit ?? this.autoSubmit,
    );
  }

  Map<String, dynamic> toPublicMap() {
    return <String, dynamic>{
      'providerUrl': providerUrl,
      'hasApiKey': hasApiKey,
      'model': model,
      'taskPrompt': taskPrompt,
      'autoSubmit': autoSubmit,
    };
  }

  Map<String, dynamic> toPersistedMap() {
    return <String, dynamic>{
      'providerUrl': providerUrl,
      'apiKey': apiKey,
      'model': model,
      'taskPrompt': taskPrompt,
      'autoSubmit': autoSubmit,
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
      autoSubmit: json['autoSubmit'] == true,
    );
  }
}
