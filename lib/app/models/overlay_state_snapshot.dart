import '../services/platform_payload_exception.dart';
import 'overlay_window_position.dart';

class OverlayStateSnapshot {
  const OverlayStateSnapshot({
    required this.mode,
    required this.position,
    required this.width,
    required this.height,
    required this.active,
  });

  factory OverlayStateSnapshot.initial() {
    return const OverlayStateSnapshot(
      mode: 'expanded',
      position: OverlayWindowPosition(12, 120),
      width: 288,
      height: 272,
      active: false,
    );
  }

  final String mode;
  final OverlayWindowPosition position;
  final double width;
  final double height;
  final bool active;

  factory OverlayStateSnapshot.fromMap(Map<String, dynamic> json) {
    final position = OverlayWindowPosition.tryParse(json['position']);
    final width = PlatformPayloadReader.readDouble(json['width']);
    final height = PlatformPayloadReader.readDouble(json['height']);
    if (position == null || width == null || height == null) {
      throw const PlatformPayloadException('悬浮窗状态缺少必要字段。');
    }
    return OverlayStateSnapshot(
      mode: json['mode']?.toString() == 'collapsed' ? 'collapsed' : 'expanded',
      position: position,
      width: width,
      height: height,
      active: json['active'] == true,
    );
  }

  OverlayStateSnapshot copyWith({
    String? mode,
    OverlayWindowPosition? position,
    double? width,
    double? height,
    bool? active,
  }) {
    return OverlayStateSnapshot(
      mode: mode ?? this.mode,
      position: position ?? this.position,
      width: width ?? this.width,
      height: height ?? this.height,
      active: active ?? this.active,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'mode': mode,
      'position': position.toMap(),
      'width': width,
      'height': height,
      'active': active,
    };
  }
}
