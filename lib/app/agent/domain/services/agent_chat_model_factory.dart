import 'package:langchain_openai/langchain_openai.dart';

import '../models/agent_config.dart';
import 'provider_url_resolver.dart';

/// 统一构建 OpenAI 兼容聊天模型，避免主链路和回退链路各自解析 provider 配置。
ChatOpenAI buildAgentChatModel(
  AgentConfig config, {
  ChatOpenAIResponseFormat? responseFormat,
  double temperature = 0,
}) {
  final baseUrl = ProviderUrlResolver.resolveBaseUrl(config.providerUrl);
  final options = ChatOpenAIOptions(
    model: config.model,
    temperature: temperature,
    responseFormat: responseFormat,
  );
  if (baseUrl.isEmpty) {
    return ChatOpenAI(apiKey: config.apiKey, defaultOptions: options);
  }
  return ChatOpenAI(
    apiKey: config.apiKey,
    baseUrl: baseUrl,
    defaultOptions: options,
  );
}
