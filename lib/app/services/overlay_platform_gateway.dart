import '../models/overlay_state_snapshot.dart';
import '../models/overlay_window_position.dart';
import '../models/overlay_platform_event.dart';

abstract interface class OverlayPlatformGateway {
  Stream<OverlayPlatformEvent> events();

  Future<bool> isPermissionGranted();

  Future<bool> requestPermission();

  Future<bool> isActive();

  Future<OverlayStateSnapshot> getOverlayState();

  Future<void> showOverlay({
    required String mode,
    required OverlayWindowPosition position,
  });

  Future<void> closeOverlay();

  Future<void> moveOverlay(OverlayWindowPosition position);

  Future<void> sendMessage(Map<String, dynamic> payload);
}
