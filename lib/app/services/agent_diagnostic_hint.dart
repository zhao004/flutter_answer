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
  if (status != 'failed') {
    return null;
  }

  final message = agentRunStatus['message']?.toString().trim() ?? '';
  final normalizedMessage = message.toLowerCase();
  final providerUrl = agentConfig['providerUrl']?.toString().trim() ?? '';
  final model = agentConfig['model']?.toString().trim() ?? '';
  final serviceEnabled = serviceStatus['enabled'] == true;

  if (_containsAny(normalizedMessage, <String>['api key', 'apikey'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: 'Missing API Key',
      summary: 'The current session does not have a usable API key.',
      actions: <String>[
        'Return to the main screen and enter a provider API key again.',
        'Save the config after entering the key so it remains available locally.',
      ],
    );
  }

  if (_containsAny(normalizedMessage, <String>['provider url', 'base url'])) {
    return const AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: 'Missing Provider URL',
      summary:
          'The agent cannot contact the model provider without a base URL.',
      actions: <String>[
        'Fill in the provider URL on the main screen.',
        'For OpenAI, the app can derive `/v1` automatically from `https://api.openai.com`.',
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
      title: 'Authentication Failed',
      summary:
          'The provider rejected the request. The key may be invalid, expired, or missing access to the selected model.${_currentModel(model)}',
      actions: const <String>[
        'Check whether the API key is correct and still active.',
        'Confirm that the selected model is available to the current account.',
      ],
    );
  }

  if (_containsAny(normalizedMessage, <String>['404', 'not found'])) {
    return AgentDiagnosticHint(
      severity: AgentDiagnosticSeverity.error,
      title: 'Provider Endpoint Not Found',
      summary:
          'The provider returned 404. The configured base URL may not expose a compatible tool-calling endpoint.${_currentProvider(providerUrl)}',
      actions: const <String>[
        'Verify the provider URL or proxy base path.',
        'If you use a compatibility layer, make sure it supports tool calling.',
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
      title: 'Tool Calling Is Not Available',
      summary:
          'The runtime could not load or use the Android tool catalog.${_currentProvider(providerUrl)}${_currentModel(model)}',
      actions: const <String>[
        'Confirm that the Android host is exposing the native tool registry.',
        'If you use a third-party provider, make sure it supports tool calling.',
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
      title: 'Network Connection Failed',
      summary:
          'The app could not reach the model provider.${_currentProvider(providerUrl)}',
      actions: const <String>[
        'Confirm that the Android device can access the provider URL.',
        'If you use a LAN proxy or self-hosted gateway, confirm the phone can reach it directly.',
      ],
    );
  }

  return AgentDiagnosticHint(
    severity: serviceEnabled
        ? AgentDiagnosticSeverity.warning
        : AgentDiagnosticSeverity.error,
    title: serviceEnabled ? 'Agent Run Failed' : 'Accessibility Not Ready',
    summary: message.isEmpty
        ? 'The agent stopped before it could complete the task.'
        : message,
    actions: <String>[
      if (!serviceEnabled)
        'Enable the Android accessibility service before starting the agent again.',
      'Inspect the latest payload and log stream to find the failing step.',
      'Re-check the provider URL, model, and API key if the failure came from the model service.',
    ],
  );
}

bool _containsAny(String source, List<String> needles) {
  return needles.any(source.contains);
}

String _currentProvider(String providerUrl) {
  if (providerUrl.isEmpty) {
    return ' No provider URL is currently configured.';
  }
  return ' Current provider: $providerUrl.';
}

String _currentModel(String model) {
  if (model.isEmpty) {
    return ' No model is currently configured.';
  }
  return ' Current model: $model.';
}
