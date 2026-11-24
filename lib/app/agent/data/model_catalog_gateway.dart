/// 从 OpenAI 兼容提供商读取可用模型列表，供首页模型输入框下拉选择。
abstract interface class ModelCatalogGateway {
  /// 请求 `{providerUrl 归一化后的 baseUrl}/models`。
  ///
  /// [providerUrl] 与 [apiKey] 应由调用方完成非空校验；
  /// 返回的模型 ID 已去重并按名称排序。
  Future<List<String>> fetchModels({
    required String providerUrl,
    required String apiKey,
  });
}

/// 模型目录查询失败时抛出的可展示异常。
///
/// [message] 面向用户，不包含 API 密钥等敏感信息。
class ModelCatalogException implements Exception {
  const ModelCatalogException(this.message);

  final String message;

  @override
  String toString() => message;
}
