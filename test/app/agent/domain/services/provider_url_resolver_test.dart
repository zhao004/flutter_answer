import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_answer/app/agent/domain/services/provider_url_resolver.dart';

void main() {
  group('ProviderUrlResolver', () {
    test('adds /v1 when the provider url has no path', () {
      expect(
        ProviderUrlResolver.resolveBaseUrl('https://api.openai.com'),
        'https://api.openai.com/v1',
      );
    });

    test('keeps /v1 paths unchanged', () {
      expect(
        ProviderUrlResolver.resolveBaseUrl('https://api.openai.com/v1'),
        'https://api.openai.com/v1',
      );
    });

    test('keeps custom api paths unchanged', () {
      expect(
        ProviderUrlResolver.resolveBaseUrl('https://openrouter.ai/api/v1'),
        'https://openrouter.ai/api/v1',
      );
    });
  });
}
