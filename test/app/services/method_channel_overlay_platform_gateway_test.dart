import 'package:flutter/services.dart';
import 'package:flutter_answer/app/services/method_channel_overlay_platform_gateway.dart';
import 'package:flutter_answer/app/services/platform_payload_exception.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('flutter_answer/overlay_methods');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('getOverlayState 在返回非法结构时抛出异常', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
          if (call.method == 'getOverlayState') {
            return <String, dynamic>{'mode': 'expanded'};
          }
          return null;
        });

    expect(
      MethodChannelOverlayPlatformGateway.instance.getOverlayState(),
      throwsA(isA<PlatformPayloadException>()),
    );
  });
}
