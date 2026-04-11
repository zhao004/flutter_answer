import '../agent/domain/models/agent_config.dart';
import '../agent/domain/models/agent_run_state.dart';
import '../agent/domain/models/service_status_snapshot.dart';

enum AgentDiagnosticSeverity { info, warning, error }

class AgentDiagnosticHint {
  const AgentDiagnosticHint({
    required this.severity,
    required this.title,
    required this.summary,
    required this.actions,
  });

  final AgentDiagnosticSeverity severity;
  final String title;
  final String summary;
  final List<String> actions;

  Map<String, dynamic> toPayload() {
    return <String, dynamic>{
      'severity': switch (severity) {
        AgentDiagnosticSeverity.info => 'info',
        AgentDiagnosticSeverity.warning => 'warning',
        AgentDiagnosticSeverity.error => 'error',
      },
      'title': title,
      'summary': summary,
      'actions': actions,
    };
  }
}

AgentDiagnosticHint? resolveAgentDiagnosticHint({
  required ServiceStatusSnapshot serviceStatus,
  required AgentConfig agentConfig,
  required AgentRunState agentRunState,
}) {
  final status = agentRunState.status;
  if (status != 'failed') {
    return null;
  }

  final message = agentRunState.message.trim();
  final normalizedMessage = message.toLowerCase();
  final providerUrl = agentConfig.providerUrl.trim();
  final model = agentConfig.model.trim();
  final serviceEnabled = serviceStatus.enabled;

  if (_containsAny(normalizedMessage, <String>['api key', 'apikey'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '缺少 API 密钥',
      summary: '当前会话没有可用的提供商 API 密钥。',
      actions: <String>['返回首页后重新填写提供商 API 密钥。', '填写后点击保存，确保当前配置已经生效。'],
    );
  }

  if (_containsAny(normalizedMessage, <String>['provider url', 'base url'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '缺少提供商地址',
      summary: '没有提供商基础地址时，Agent 无法连接模型服务。',
      actions: <String>[
        '在首页填写正确的提供商 URL。',
        '如果使用 OpenAI，填写 `https://api.openai.com` 即可自动补全 `/v1`。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, <String>[
    '401',
    '403',
    'unauthorized',
    'forbidden',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '身份认证失败',
      summary: '模型服务拒绝了当前请求，可能是密钥无效、已过期，或没有目标模型权限。${_currentModel(model)}',
      actions: const <String>['检查 API 密钥是否填写正确且仍然有效。', '确认当前账号拥有所选模型的访问权限。'],
    );
  }

  if (_containsAny(normalizedMessage, <String>['404', 'not found'])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '模型接口不存在',
      summary:
          '提供商返回了 404，当前配置的基础地址可能没有暴露兼容的工具调用接口。${_currentProvider(providerUrl)}',
      actions: const <String>[
        '核对提供商 URL 或代理基础路径是否正确。',
        '如果使用兼容层或代理服务，确认它支持工具调用。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, <String>[
    'tool',
    'function calling',
    'no native tools',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '工具调用不可用',
      summary:
          '运行时无法加载或使用 Android 原生工具目录。${_currentProvider(providerUrl)}${_currentModel(model)}',
      actions: const <String>[
        '确认 Android 宿主已经暴露原生工具注册表。',
        '如果使用第三方模型服务，确认它支持工具调用。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, <String>[
    'unable to resolve host',
    'failed host lookup',
    'connection refused',
    'timed out',
    'timeout',
    'socketexception',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.warning,
      title: '网络连接失败',
      summary: '应用暂时无法连接到模型提供商。${_currentProvider(providerUrl)}',
      actions: const <String>[
        '确认 Android 设备可以访问当前提供商地址。',
        '如果使用局域网代理或自托管网关，确认手机可以直接连通。',
      ],
    );
  }

  return AgentDiagnosticHint(
    severity: serviceEnabled
        ? AgentDiagnosticSeverity.warning
        : AgentDiagnosticSeverity.error,
    title: serviceEnabled ? 'Agent 运行失败' : '无障碍服务未就绪',
    summary: message.isEmpty ? 'Agent 在完成任务前提前停止。' : message,
    actions: <String>[
      if (!serviceEnabled) '重新启动 Agent 前先开启 Android 无障碍服务。',
      '查看最近负载和日志，定位失败步骤。',
      '如果失败来自模型服务，重新检查提供商地址、模型和 API 密钥。',
    ],
  );
}

bool _containsAny(String source, List<String> needles) {
  return needles.any(source.contains);
}

String _currentProvider(String providerUrl) {
  if (providerUrl.isEmpty) {
    return ' 当前尚未配置提供商地址。';
  }
  return ' 当前提供商：$providerUrl。';
}

String _currentModel(String model) {
  if (model.isEmpty) {
    return ' 当前尚未配置模型。';
  }
  return ' 当前模型：$model。';
}
