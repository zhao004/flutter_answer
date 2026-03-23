import 'package:shared_preferences/shared_preferences.dart';

import '../domain/models/agent_config.dart';

class AgentConfigStore {
  AgentConfigStore._();

  static final AgentConfigStore instance = AgentConfigStore._();

  static const String _providerUrlKey = 'agent.providerUrl';
  static const String _apiKeyKey = 'agent.apiKey';
  static const String _modelKey = 'agent.model';
  static const String _taskPromptKey = 'agent.taskPrompt';
  static const String _autoSubmitKey = 'agent.autoSubmit';
  static const String _maxIterationsKey = 'agent.maxIterations';
  static const String _maxExecutionMinutesKey = 'agent.maxExecutionMinutes';
  static const String _maxRepeatedSameToolCallKey =
      'agent.maxRepeatedSameToolCall';
  static const String _maxNoProgressActionStreakKey =
      'agent.maxNoProgressActionStreak';

  Future<AgentConfig?> load() async {
    final prefs = await SharedPreferences.getInstance();
    final hasSavedConfig =
        prefs.containsKey(_providerUrlKey) ||
        prefs.containsKey(_apiKeyKey) ||
        prefs.containsKey(_modelKey) ||
        prefs.containsKey(_taskPromptKey) ||
        prefs.containsKey(_autoSubmitKey) ||
        prefs.containsKey(_maxIterationsKey) ||
        prefs.containsKey(_maxExecutionMinutesKey) ||
        prefs.containsKey(_maxRepeatedSameToolCallKey) ||
        prefs.containsKey(_maxNoProgressActionStreakKey);
    if (!hasSavedConfig) {
      return null;
    }

    return AgentConfig.fromPersistedMap(<String, dynamic>{
      'providerUrl': prefs.getString(_providerUrlKey) ?? '',
      'apiKey': prefs.getString(_apiKeyKey) ?? '',
      'model': prefs.getString(_modelKey) ?? 'gpt-5-mini',
      'taskPrompt': prefs.getString(_taskPromptKey) ?? '',
      'autoSubmit': prefs.getBool(_autoSubmitKey) ?? false,
      'maxIterations':
          prefs.getInt(_maxIterationsKey) ?? AgentConfig.defaultMaxIterations,
      'maxExecutionMinutes':
          prefs.getInt(_maxExecutionMinutesKey) ??
          AgentConfig.defaultMaxExecutionMinutes,
      'maxRepeatedSameToolCall':
          prefs.getInt(_maxRepeatedSameToolCallKey) ??
          AgentConfig.defaultMaxRepeatedSameToolCall,
      'maxNoProgressActionStreak':
          prefs.getInt(_maxNoProgressActionStreakKey) ??
          AgentConfig.defaultMaxNoProgressActionStreak,
    });
  }

  Future<void> save(AgentConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    final sanitizedConfig = config.sanitized();
    await prefs.setString(_providerUrlKey, config.providerUrl);
    await prefs.setString(_apiKeyKey, config.apiKey);
    await prefs.setString(_modelKey, sanitizedConfig.model);
    await prefs.setString(_taskPromptKey, sanitizedConfig.taskPrompt);
    await prefs.setBool(_autoSubmitKey, sanitizedConfig.autoSubmit);
    await prefs.setInt(_maxIterationsKey, sanitizedConfig.maxIterations);
    await prefs.setInt(
      _maxExecutionMinutesKey,
      sanitizedConfig.maxExecutionMinutes,
    );
    await prefs.setInt(
      _maxRepeatedSameToolCallKey,
      sanitizedConfig.maxRepeatedSameToolCall,
    );
    await prefs.setInt(
      _maxNoProgressActionStreakKey,
      sanitizedConfig.maxNoProgressActionStreak,
    );
  }
}
