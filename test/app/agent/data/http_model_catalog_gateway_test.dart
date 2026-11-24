import 'dart:convert';

import 'package:flutter_answer/app/agent/data/http_model_catalog_gateway.dart';
import 'package:flutter_answer/app/agent/data/model_catalog_gateway.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  group('HttpModelCatalogGateway', () {
    test('归一化裸域名并解析 data[].id', () async {
      final requests = <http.Request>[];
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          requests.add(request);
          return http.Response(
            jsonEncode(<String, dynamic>{
              'data': <dynamic>[
                <String, dynamic>{'id': 'gpt-4o-mini'},
                <String, dynamic>{'id': 'gpt-4o'},
                <String, dynamic>{'id': 'gpt-4o'},
              ],
            }),
            200,
          );
        }),
      );

      final models = await gateway.fetchModels(
        providerUrl: 'https://api.openai.com',
        apiKey: 'sk-test',
      );

      expect(requests, hasLength(1));
      expect(
        requests.single.url.toString(),
        'https://api.openai.com/v1/models',
      );
      expect(requests.single.headers['authorization'], 'Bearer sk-test');
      expect(models, <String>['gpt-4o', 'gpt-4o-mini']);
    });

    test('保留提供商已有路径并兼容 models 字符串数组', () async {
      final requests = <http.Request>[];
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          requests.add(request);
          return http.Response(
            jsonEncode(<String, dynamic>{
              'models': <String>['zeta', 'alpha'],
            }),
            200,
          );
        }),
      );

      final models = await gateway.fetchModels(
        providerUrl: 'https://proxy.example.com/openai/v1/',
        apiKey: 'sk-test',
      );

      expect(
        requests.single.url.toString(),
        'https://proxy.example.com/openai/v1/models',
      );
      expect(models, <String>['alpha', 'zeta']);
    });

    test('兼容根数组和 name 字段', () async {
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          return http.Response(
            jsonEncode(<dynamic>[
              <String, dynamic>{'id': 'b'},
              <String, dynamic>{'name': 'a'},
            ]),
            200,
          );
        }),
      );

      final models = await gateway.fetchModels(
        providerUrl: 'https://api.openai.com/v1',
        apiKey: 'sk-test',
      );

      expect(models, <String>['a', 'b']);
    });

    test('鉴权失败时给出可展示错误', () async {
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          return http.Response('unauthorized', 401);
        }),
      );

      await expectLater(
        gateway.fetchModels(
          providerUrl: 'https://api.openai.com',
          apiKey: 'sk-test',
        ),
        throwsA(
          isA<ModelCatalogException>().having(
            (ModelCatalogException error) => error.message,
            'message',
            contains('HTTP 401'),
          ),
        ),
      );
    });

    test('模型接口不存在时提示手动填写', () async {
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          return http.Response('not found', 404);
        }),
      );

      await expectLater(
        gateway.fetchModels(
          providerUrl: 'https://api.openai.com',
          apiKey: 'sk-test',
        ),
        throwsA(
          isA<ModelCatalogException>().having(
            (ModelCatalogException error) => error.message,
            'message',
            allOf(contains('404'), contains('手动填写')),
          ),
        ),
      );
    });

    test('响应不是合法 JSON 时给出解析错误', () async {
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          return http.Response('not-json', 200);
        }),
      );

      await expectLater(
        gateway.fetchModels(
          providerUrl: 'https://api.openai.com',
          apiKey: 'sk-test',
        ),
        throwsA(
          isA<ModelCatalogException>().having(
            (ModelCatalogException error) => error.message,
            'message',
            contains('无法解析'),
          ),
        ),
      );
    });

    test('URL 无效时不发起请求', () async {
      var requested = false;
      final gateway = HttpModelCatalogGateway(
        client: MockClient((http.Request request) async {
          requested = true;
          return http.Response('{}', 200);
        }),
      );

      await expectLater(
        gateway.fetchModels(providerUrl: 'not-a-url', apiKey: 'sk-test'),
        throwsA(isA<ModelCatalogException>()),
      );
      expect(requested, isFalse);
    });
  });
}
