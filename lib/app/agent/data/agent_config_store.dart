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

  Future<AgentConfig?> load() async {
    final prefs = await SharedPreferences.getInstance();
    final hasSavedConfig =
        prefs.containsKey(_providerUrlKey) ||
        prefs.containsKey(_apiKeyKey) ||
        prefs.containsKey(_modelKey) ||
        prefs.containsKey(_taskPromptKey) ||
        prefs.containsKey(_autoSubmitKey);
    if (!hasSavedConfig) {
      return null;
    }

    return AgentConfig.fromPersistedMap(<String, dynamic>{
      'providerUrl': prefs.getString(_providerUrlKey) ?? '',
      'apiKey': prefs.getString(_apiKeyKey) ?? '',
      'model': prefs.getString(_modelKey) ?? 'gpt-5-mini',
      'taskPrompt': prefs.getString(_taskPromptKey) ?? '',
      'autoSubmit': prefs.getBool(_autoSubmitKey) ?? false,
    });
  }

  Future<void> save(AgentConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_providerUrlKey, config.providerUrl);
    await prefs.setString(_apiKeyKey, config.apiKey);
    await prefs.setString(_modelKey, config.model);
    await prefs.setString(_taskPromptKey, config.taskPrompt);
    await prefs.setBool(_autoSubmitKey, config.autoSubmit);
  }
}
