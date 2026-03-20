import 'package:shared_preferences/shared_preferences.dart';

class AgentConfigStore {
  AgentConfigStore._();

  static final AgentConfigStore instance = AgentConfigStore._();

  static const String _providerUrlKey = 'agent.providerUrl';
  static const String _modelKey = 'agent.model';
  static const String _taskPromptKey = 'agent.taskPrompt';
  static const String _autoSubmitKey = 'agent.autoSubmit';

  Future<Map<String, dynamic>?> load() async {
    final prefs = await SharedPreferences.getInstance();
    final hasSavedConfig =
        prefs.containsKey(_providerUrlKey) ||
        prefs.containsKey(_modelKey) ||
        prefs.containsKey(_taskPromptKey) ||
        prefs.containsKey(_autoSubmitKey);
    if (!hasSavedConfig) {
      return null;
    }

    return <String, dynamic>{
      'providerUrl': prefs.getString(_providerUrlKey) ?? '',
      'model': prefs.getString(_modelKey) ?? 'gpt-5-mini',
      'taskPrompt':
          prefs.getString(_taskPromptKey) ?? '读取当前答题页面并继续作答，优先使用工具，不要输出解释。',
      'autoSubmit': prefs.getBool(_autoSubmitKey) ?? false,
    };
  }

  Future<void> save({
    required String providerUrl,
    required String model,
    required String taskPrompt,
    required bool autoSubmit,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_providerUrlKey, providerUrl);
    await prefs.setString(_modelKey, model);
    await prefs.setString(_taskPromptKey, taskPrompt);
    await prefs.setBool(_autoSubmitKey, autoSubmit);
  }
}
