import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';
import 'package:flutter_answer/app/agent/domain/services/agent_chat_model_factory.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('buildAgentChatModel', () {
    const config = AgentConfig(
      providerUrl: 'https://api.openai.com',
      apiKey: 'sk-test',
    );

    test('默认沿用 provider 的并行工具调用行为', () {
      final model = buildAgentChatModel(config);

      expect(model.defaultOptions.parallelToolCalls, isNull);
      model.close();
    });

    test('传入 false 时关闭并行工具调用', () {
      final model = buildAgentChatModel(config, parallelToolCalls: false);

      expect(model.defaultOptions.parallelToolCalls, isFalse);
      model.close();
    });
  });
}
