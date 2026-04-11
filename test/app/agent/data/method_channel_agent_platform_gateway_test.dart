import 'package:flutter/services.dart';
import 'package:flutter_answer/app/agent/data/method_channel_agent_platform_gateway.dart';
import 'package:flutter_answer/app/services/platform_payload_exception.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('flutter_answer/agent_methods');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('listTools 在协议缺少 tools 数组时抛出异常而不是静默返回空列表', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          if (call.method == 'listTools') {
            return <String, dynamic>{'ok': true};
          }
          return null;
        });

    expect(
      MethodChannelAgentPlatformGateway.instance.listTools(),
      throwsA(isA<PlatformPayloadException>()),
    );
  });
}
