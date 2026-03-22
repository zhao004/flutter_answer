import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';

void main() {
  group('AgentConfig persistence', () {
    test('persists api key alongside the rest of the config', () {
      const config = AgentConfig(
        providerUrl: 'https://api.openai.com',
        apiKey: 'sk-test',
        model: 'gpt-5-mini',
        taskPrompt: 'answer the page',
        autoSubmit: true,
      );

      final restored = AgentConfig.fromPersistedMap(config.toPersistedMap());

      expect(restored.providerUrl, config.providerUrl);
      expect(restored.apiKey, config.apiKey);
      expect(restored.model, config.model);
      expect(restored.taskPrompt, config.taskPrompt);
      expect(restored.autoSubmit, isTrue);
      expect(restored.hasApiKey, isTrue);
    });
  });
}
