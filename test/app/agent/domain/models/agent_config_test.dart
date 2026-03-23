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
        maxIterations: 960,
        maxExecutionMinutes: 18,
        maxRepeatedSameToolCall: 16,
        maxNoProgressActionStreak: 48,
      );

      final restored = AgentConfig.fromPersistedMap(config.toPersistedMap());

      expect(restored.providerUrl, config.providerUrl);
      expect(restored.apiKey, config.apiKey);
      expect(restored.model, config.model);
      expect(restored.taskPrompt, config.taskPrompt);
      expect(restored.maxIterations, 960);
      expect(restored.maxExecutionMinutes, 18);
      expect(restored.maxRepeatedSameToolCall, 16);
      expect(restored.maxNoProgressActionStreak, 48);
      expect(restored.hasApiKey, isTrue);
    });

    test('sanitizes execution thresholds into supported ranges', () {
      const config = AgentConfig(
        maxIterations: 9_999,
        maxExecutionMinutes: 999,
        maxRepeatedSameToolCall: 1,
        maxNoProgressActionStreak: 999,
      );

      final sanitized = config.sanitized();

      expect(sanitized.maxIterations, AgentConfig.maxMaxIterations);
      expect(sanitized.maxExecutionMinutes, AgentConfig.maxMaxExecutionMinutes);
      expect(
        sanitized.maxRepeatedSameToolCall,
        AgentConfig.minMaxRepeatedSameToolCall,
      );
      expect(
        sanitized.maxNoProgressActionStreak,
        AgentConfig.maxMaxNoProgressActionStreak,
      );
    });
  });
}
