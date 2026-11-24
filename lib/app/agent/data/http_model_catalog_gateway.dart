import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../domain/services/provider_url_resolver.dart';
import 'model_catalog_gateway.dart';

/// 基于 OpenAI 兼容 `GET /models` 接口的模型目录实现。
///
/// 默认每次请求创建并关闭独立的 [http.Client]，避免长期持有连接；
/// 测试可以通过 [client] 注入 `MockClient`，此时由调用方负责关闭。
class HttpModelCatalogGateway implements ModelCatalogGateway {
  HttpModelCatalogGateway({
    http.Client? client,
    Duration timeout = const Duration(seconds: 15),
  }) : _client = client,
       _timeout = timeout;

  final http.Client? _client;
  final Duration _timeout;

  @override
  Future<List<String>> fetchModels({
    required String providerUrl,
    required String apiKey,
  }) async {
    final baseUrl = ProviderUrlResolver.resolveBaseUrl(providerUrl);
    final uri = Uri.tryParse('$baseUrl/models');
    if (uri == null ||
        (uri.scheme != 'http' && uri.scheme != 'https') ||
        uri.host.isEmpty) {
      throw const ModelCatalogException(
        '提供商 URL 无效，请填写完整地址（例如 https://api.openai.com）。',
      );
    }

    final normalizedApiKey = apiKey.trim();
    final ownsClient = _client == null;
    final client = _client ?? http.Client();
    try {
      final response = await client
          .get(
            uri,
            headers: <String, String>{
              'Accept': 'application/json',
              if (normalizedApiKey.isNotEmpty)
                'Authorization': 'Bearer $normalizedApiKey',
            },
          )
          .timeout(_timeout);
      return _parseResponse(response);
    } on TimeoutException {
      throw const ModelCatalogException('获取模型超时，请检查网络连接或提供商 URL。');
    } on http.ClientException {
      throw const ModelCatalogException('无法连接提供商模型列表接口，请检查网络连接或提供商 URL。');
    } finally {
      if (ownsClient) {
        client.close();
      }
    }
  }

  List<String> _parseResponse(http.Response response) {
    if (response.statusCode == 401 || response.statusCode == 403) {
      throw ModelCatalogException(
        '模型列表请求被拒绝（HTTP ${response.statusCode}），请检查提供商 API 密钥。',
      );
    }
    if (response.statusCode == 404) {
      throw const ModelCatalogException('当前提供商未提供模型列表接口（HTTP 404），可以手动填写模型名称。');
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw ModelCatalogException(
        '获取模型失败（HTTP ${response.statusCode}）：${_responseSnippet(response)}',
      );
    }
    if (response.bodyBytes.isEmpty) {
      return const <String>[];
    }

    final dynamic decoded;
    try {
      decoded = jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw const ModelCatalogException('提供商返回的模型列表格式无法解析，请检查提供商 URL。');
    }

    final List<dynamic> rawModels;
    if (decoded is List<dynamic>) {
      rawModels = decoded;
    } else if (decoded is Map<String, dynamic>) {
      final dynamic data = decoded['data'];
      final dynamic models = decoded['models'];
      final dynamic candidate = data is List<dynamic> ? data : models;
      if (candidate is! List<dynamic>) {
        throw const ModelCatalogException('提供商返回的模型列表格式无法解析，请检查提供商 URL。');
      }
      rawModels = candidate;
    } else {
      throw const ModelCatalogException('提供商返回的模型列表格式无法解析，请检查提供商 URL。');
    }

    final modelIds = <String>{};
    for (final dynamic item in rawModels) {
      final id = _extractModelId(item);
      if (id != null) {
        modelIds.add(id);
      }
    }

    return modelIds.toList()..sort(
      (String left, String right) =>
          left.toLowerCase().compareTo(right.toLowerCase()),
    );
  }

  /// 兼容 OpenAI 的 `data[].id` 以及部分代理返回的 `models[].name` / 纯字符串数组。
  String? _extractModelId(dynamic item) {
    if (item is String) {
      final id = item.trim();
      return id.isEmpty ? null : id;
    }
    if (item is Map<String, dynamic>) {
      final dynamic rawId = item['id'] ?? item['name'] ?? item['model'];
      final id = rawId?.toString().trim() ?? '';
      return id.isEmpty ? null : id;
    }
    return null;
  }

  /// 截断错误响应，既保留排查线索，又避免日志被大段 HTML 或堆栈淹没。
  String _responseSnippet(http.Response response) {
    final body = utf8.decode(response.bodyBytes, allowMalformed: true);
    final collapsed = body.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (collapsed.isEmpty) {
      return '提供商未返回错误详情';
    }
    if (collapsed.length <= 200) {
      return collapsed;
    }
    return '${collapsed.substring(0, 200)}…';
  }
}
