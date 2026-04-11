import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_run_state.dart';
import 'package:flutter_answer/app/agent/domain/models/service_status_snapshot.dart';
import 'package:flutter_answer/app/services/agent_diagnostic_hint.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('resolveAgentDiagnosticHint', () {
    test('401 错误返回中文认证失败提示', () {
      final hint = resolveAgentDiagnosticHint(
        serviceStatus: const ServiceStatusSnapshot(enabled: true),
        agentConfig: const AgentConfig(
          providerUrl: 'https://api.openai.com',
          model: 'gpt-5-mini',
        ),
        agentRunState: AgentRunState(
          running: false,
          status: 'failed',
          step: 0,
          message: 'Request failed with status code 401',
          updatedAt: 1,
        ),
      );

      expect(hint, isNotNull);
      expect(hint!.title, '身份认证失败');
      expect(hint.summary, contains('当前模型：gpt-5-mini'));
    });

    test('无障碍未开启时返回中文兜底提示', () {
      final hint = resolveAgentDiagnosticHint(
        serviceStatus: const ServiceStatusSnapshot(enabled: false),
        agentConfig: const AgentConfig(),
        agentRunState: AgentRunState(
          running: false,
          status: 'failed',
          step: 0,
          message: '',
          updatedAt: 1,
        ),
      );

      expect(hint, isNotNull);
      expect(hint!.title, '无障碍服务未就绪');
      expect(hint.actions.first, contains('无障碍服务'));
    });
  });
}
