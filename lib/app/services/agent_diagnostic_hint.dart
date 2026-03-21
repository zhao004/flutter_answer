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
}

AgentDiagnosticHint? resolveAgentDiagnosticHint({
  required Map<String, dynamic> serviceStatus,
  required Map<String, dynamic> agentConfig,
  required Map<String, dynamic> agentRunStatus,
}) {
  final status = agentRunStatus['status']?.toString() ?? '';
  final message = agentRunStatus['message']?.toString().trim() ?? '';
  final normalizedMessage = message.toLowerCase();
  final providerUrl = agentConfig['providerUrl']?.toString().trim() ?? '';
  final model = agentConfig['model']?.toString().trim() ?? '';

  if (status != 'failed') {
    return null;
  }

  if (_containsAny(normalizedMessage, ['api key'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '缺少 API 密钥',
      summary: '当前会话里没有可用的 API 密钥，所以代理无法启动。',
      actions: [
        '回到主界面重新填写提供商 API 密钥，再点击保存配置。',
        '如果刚重启过应用，需要重新输入一次密钥，因为密钥只保存在当前会话内存。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, ['provider url'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '缺少提供商地址',
      summary: '还没有可用的接口地址，程序无法发起 Responses API 请求。',
      actions: [
        '在主界面的“提供商 URL”里填写服务根地址。',
        'OpenAI 官方可直接填写 `https://api.openai.com`，程序会自动补全 `/v1/responses`。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, [
    '未触发任何工具调用',
    'model returned no tool calls',
    'no tool calls',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '模型没有调用工具',
      summary:
          '服务端返回了结果，但没有发起任何工具调用，自动答题无法继续。${_currentProvider(providerUrl)}${_currentModel(model)}',
      actions: [
        '把提供商 URL 改成支持 Responses API 的根地址，例如 `https://api.openai.com` 或兼容平台的 `/v1` 根路径。',
        '切换到支持 function calling 的模型后再试，优先使用 `gpt-5-mini`、`gpt-5` 或服务商明确标注支持工具调用的模型。',
        '如果你使用第三方兼容接口，确认它同时支持 `tools`、`tool_choice: "required"` 和 `function_call_output`。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, [
    'request failed (401)',
    'request failed (403)',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '鉴权失败',
      summary: '服务商拒绝了这次请求，通常是 API 密钥无效、过期或没有权限访问当前模型。${_currentModel(model)}',
      actions: [
        '检查 API 密钥是否填写正确，必要时重新生成后再保存配置。',
        '确认当前密钥有权访问所选模型，并且账户额度或配额正常。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, ['request failed (404)'])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '接口地址可能不对',
      summary:
          '服务端返回了 404，当前地址看起来不像是可用的 Responses API 入口。${_currentProvider(providerUrl)}',
      actions: [
        'OpenAI 官方只需要填写 `https://api.openai.com`，程序会自动补全路径。',
        '第三方平台建议填写域名或 `/v1` 根路径，不要手动拼出错误的 `/responses` 地址。',
        '如果平台只支持 `chat/completions` 而不支持 `/responses`，当前自动答题模式无法使用。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, [
    'tool_choice',
    'function calling',
    'function_call_output',
    'unsupported tool',
    'unsupported function',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: '当前服务不支持工具调用',
      summary:
          '请求已经发到服务端，但它不接受当前的工具调用参数。${_currentProvider(providerUrl)}${_currentModel(model)}',
      actions: [
        '改用原生支持 Responses API function calling 的服务商或网关。',
        '如果是兼容层，请确认它支持 `tools`、`tool_choice` 和多轮 `function_call_output`。',
        '必要时更换成服务商文档里明确支持工具调用的模型。',
      ],
    );
  }

  if (_containsAny(normalizedMessage, ['request failed (400)'])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.warning,
      title: '请求参数不兼容',
      summary:
          '服务端返回了 400，这通常表示当前模型或接口与请求格式不兼容。${_currentProvider(providerUrl)}${_currentModel(model)}',
      actions: ['先检查服务商文档是否支持 `/v1/responses` 和工具调用。', '再确认模型名称与服务商要求完全一致。'],
    );
  }

  if (_containsAny(normalizedMessage, [
    'unable to resolve host',
    'failed to connect',
    'connection refused',
    'timeout',
    'timed out',
  ])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.warning,
      title: '网络连接失败',
      summary: '应用暂时连不上模型服务。${_currentProvider(providerUrl)}',
      actions: [
        '确认手机当前网络能访问这个提供商地址。',
        '如果你使用局域网网关、代理或自建服务，检查安卓设备是否真的能直连。',
        '网络恢复后重新点击启动。',
      ],
    );
  }

  if (message.isEmpty) {
    return null;
  }

  final serviceEnabled = serviceStatus['enabled'] == true;
  return AgentDiagnosticHint(
    severity: AgentDiagnosticSeverity.warning,
    title: '启动失败',
    summary: message,
    actions: [
      if (!serviceEnabled) '先开启无障碍服务，再重新启动代理。',
      '查看最近载荷和日志流，确认失败发生在哪一步。',
      '如果错误来自服务商接口，优先检查提供商 URL、模型和 API 密钥。',
    ],
  );
}

bool _containsAny(String source, List<String> needles) {
  return needles.any(source.contains);
}

String _currentProvider(String providerUrl) {
  if (providerUrl.isEmpty) {
    return '当前还没有填写提供商地址。';
  }
  return '当前地址：$providerUrl。';
}

String _currentModel(String model) {
  if (model.isEmpty) {
    return '当前还没有填写模型。';
  }
  return '当前模型：$model。';
}
