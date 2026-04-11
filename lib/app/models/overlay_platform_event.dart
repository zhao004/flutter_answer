import '../services/platform_payload_exception.dart';
import 'overlay_state_snapshot.dart';

class OverlayPlatformEvent {
  const OverlayPlatformEvent({
    this.source,
    this.type,
    this.action,
    this.pluginEvent,
    this.mode,
    this.x,
    this.y,
    this.payload,
    this.overlayState,
  });

  final String? source;
  final String? type;
  final String? action;
  final String? pluginEvent;
  final String? mode;
  final double? x;
  final double? y;
  final Map<String, dynamic>? payload;
  final OverlayStateSnapshot? overlayState;

  factory OverlayPlatformEvent.fromDynamic(dynamic raw) {
    final json = PlatformPayloadReader.requireMap(raw, context: '悬浮窗事件');
    final payload = PlatformPayloadReader.tryMap(json['payload']);
    OverlayStateSnapshot? overlayState;
    if (json['type']?.toString() == 'overlay_state' && payload != null) {
      try {
        overlayState = OverlayStateSnapshot.fromMap(payload);
      } on ArgumentError catch (error) {
        throw PlatformPayloadException('悬浮窗状态事件格式错误：$error');
      }
    }
    return OverlayPlatformEvent(
      source: json['source']?.toString(),
      type: json['type']?.toString(),
      action: json['action']?.toString(),
      pluginEvent: json['event']?.toString(),
      mode: json['mode']?.toString(),
      x: PlatformPayloadReader.readDouble(json['x']),
      y: PlatformPayloadReader.readDouble(json['y']),
      payload: payload,
      overlayState: overlayState,
    );
  }

  bool get isFromMain => source == 'main';
  bool get isCommand => source == 'overlay' && type == 'command';
  bool get isOverlayShown => pluginEvent == 'overlay_shown';
  bool get isOverlayClosed => pluginEvent == 'overlay_closed';
}
