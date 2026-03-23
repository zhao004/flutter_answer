import '../models/overlay_window_position.dart';

abstract interface class OverlayPlatformGateway {
  Stream<Map<String, dynamic>> events();

  Future<bool> isPermissionGranted();

  Future<bool> requestPermission();

  Future<bool> isActive();

  Future<Map<String, dynamic>> getOverlayState();

  Future<void> showOverlay({
    required String mode,
    required OverlayWindowPosition position,
  });

  Future<void> closeOverlay();

  Future<void> moveOverlay(OverlayWindowPosition position);

  Future<void> sendMessage(Map<String, dynamic> payload);
}
