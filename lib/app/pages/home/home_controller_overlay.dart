part of 'home_controller.dart';

extension HomeControllerOverlay on HomeController {
  Future<void> syncOverlayState() async {
    final granted = await _overlayPlatformGateway.isPermissionGranted();
    final active = await _overlayPlatformGateway.isActive();
    overlayPermissionGranted.value = granted;

    if (active) {
      final snapshot = await _overlayPlatformGateway.getOverlayState();
      _applyOverlayState(snapshot);
    } else {
      overlayState.value = overlayState.value.copyWith(active: false);
    }
    await _broadcastOverlaySnapshot();
  }

  Future<void> requestOverlayPermission() async {
    if (_systemSettingsFlowActive || overlayPermissionBusy.value) {
      return;
    }

    _systemSettingsFlowActive = true;
    overlayPermissionBusy.value = true;
    await _dismissOverlayForSystemSettings();
    _appendLog('info', '正在打开系统悬浮窗权限页面。');
    try {
      final granted = await _overlayPlatformGateway.requestPermission();
      overlayPermissionGranted.value = granted;
      if (granted) {
        _appendLog('info', '已获得悬浮窗权限。');
      } else {
        _appendLog('warn', '悬浮窗权限尚未开启，请在系统页面中完成授权。');
      }
      await syncOverlayState();
    } catch (error) {
      _appendLog('error', '请求悬浮窗权限失败：$error');
      await syncOverlayState();
    } finally {
      _systemSettingsFlowActive = false;
      overlayPermissionBusy.value = false;
    }
  }

  Future<void> toggleOverlayPermission(bool enabled) async {
    final current = overlayPermissionGranted.value;
    if (current == enabled) {
      return;
    }
    if (enabled) {
      await requestOverlayPermission();
      return;
    }
    _appendLog('info', '请在系统设置中手动关闭悬浮窗权限。');
  }

  Future<void> showOverlay() async {
    await _runAction('overlay_visibility', () async {
      if (!overlayPermissionGranted.value) {
        _appendLog('warn', '请先开启悬浮窗权限后再显示悬浮窗。');
        return;
      }
      await _showOverlayWindow();
      await syncOverlayState();
    }, busyFlag: overlayVisibilityBusy);
  }

  Future<void> closeOverlay() async {
    await _runAction('overlay_visibility', () async {
      await _overlayPlatformGateway.closeOverlay();
      overlayState.value = overlayState.value.copyWith(active: false);
      await _broadcastOverlaySnapshot();
    }, busyFlag: overlayVisibilityBusy);
  }

  Future<void> toggleOverlayVisibility(bool visible) async {
    if (visible) {
      if (!overlayPermissionGranted.value) {
        await requestOverlayPermission();
      }
      if (overlayPermissionGranted.value && !overlayActive) {
        await showOverlay();
      }
      return;
    }

    if (overlayActive) {
      await closeOverlay();
    }
  }

  void _handleOverlayEvent(OverlayPlatformEvent event) {
    if (event.isFromMain) {
      return;
    }

    if (event.isOverlayShown) {
      overlayState.value = overlayState.value.copyWith(active: true);
      unawaited(syncOverlayState());
      unawaited(_broadcastOverlaySnapshot());
      return;
    }
    if (event.isOverlayClosed) {
      overlayState.value = overlayState.value.copyWith(active: false);
      unawaited(_broadcastOverlaySnapshot());
      return;
    }

    if (event.type == 'overlay_state' && event.overlayState != null) {
      _applyOverlayState(event.overlayState!);
      return;
    }

    if (!event.isCommand) {
      return;
    }

    switch (event.action) {
      case 'start_agent':
        unawaited(startAgentRun());
        break;
      case 'stop_agent':
        unawaited(stopAgentRun());
        break;
      case 'refresh_question_context':
        unawaited(fetchQuestionContext());
        break;
      case 'scroll_down':
        unawaited(scrollScreen(direction: 'down', amount: 'medium'));
        break;
      case 'press_back':
        unawaited(pressGlobal(action: 'back'));
        break;
      case 'sync_snapshot':
        unawaited(_broadcastOverlaySnapshot());
        break;
      case 'set_overlay_mode':
        unawaited(_applyOverlayMode(event.mode));
        break;
      case 'move_overlay':
        if (event.x != null && event.y != null) {
          unawaited(
            _moveOverlayWindow(OverlayWindowPosition(event.x!, event.y!)),
          );
        }
        break;
      case 'close_overlay':
        unawaited(closeOverlay());
        break;
    }
  }

  Future<void> _showOverlayWindow() async {
    final currentState = overlayState.value;
    await _overlayPlatformGateway.showOverlay(
      mode: currentState.mode,
      position: currentState.position,
    );
    overlayState.value = currentState.copyWith(active: true);
  }

  Future<void> _applyOverlayMode(String? mode) async {
    final currentState = overlayState.value;
    final nextMode = _normalizeOverlayMode(mode);
    final currentPosition =
        await _safeGetOverlayPosition() ?? currentState.position;
    final nextPosition = _resolveOverlayPositionForMode(
      currentMode: currentState.mode,
      nextMode: nextMode,
      currentPosition: currentPosition,
    );
    final geometry = _overlayGeometryForMode(nextMode);
    overlayState.value = currentState.copyWith(
      mode: nextMode,
      position: nextPosition,
      width: geometry.width.toDouble(),
      height: geometry.height.toDouble(),
    );
    await _showOverlayWindow();
    await syncOverlayState();
    await _pushOverlayState();
  }

  Future<void> _moveOverlayWindow(OverlayWindowPosition position) async {
    final currentState = overlayState.value;
    final geometry = _overlayGeometryForMode(currentState.mode);
    final clamped = _clampOverlayPosition(position, geometry);
    await _overlayPlatformGateway.moveOverlay(clamped);
    overlayState.value = currentState.copyWith(position: clamped);
    await _pushOverlayState();
  }

  Future<OverlayWindowPosition?> _safeGetOverlayPosition() async {
    try {
      final snapshot = await _overlayPlatformGateway.getOverlayState();
      _applyOverlayState(snapshot);
      return snapshot.position;
    } catch (_) {
      return null;
    }
  }

  Future<void> _pushOverlayState() {
    return _sendOverlayMessage(<String, dynamic>{
      'source': 'main',
      'type': 'overlay_state',
      'payload': overlayState.value.toMap(),
    }, logFailure: false);
  }

  String _normalizeOverlayMode(String? mode) {
    return mode == 'collapsed' ? 'collapsed' : 'expanded';
  }

  _OverlayWindowGeometry _overlayGeometryForMode(String mode) {
    if (mode == 'collapsed') {
      return const _OverlayWindowGeometry(width: 72, height: 72);
    }
    return const _OverlayWindowGeometry(width: 288, height: 272);
  }

  double _overlayEdgeMarginForMode(String mode) {
    if (mode == 'collapsed') {
      return HomeController._collapsedOverlayEdgeMargin;
    }
    return HomeController._expandedOverlayEdgeMargin;
  }

  OverlayWindowPosition _resolveOverlayPositionForMode({
    required String currentMode,
    required String nextMode,
    required OverlayWindowPosition currentPosition,
  }) {
    final currentGeometry = _overlayGeometryForMode(currentMode);
    final nextGeometry = _overlayGeometryForMode(nextMode);
    final screen = _logicalScreenSize();
    final anchorX = currentPosition.x + (currentGeometry.width / 2);
    final anchorY =
        currentPosition.y +
        (currentGeometry.height / 2) -
        (nextGeometry.height / 2);
    final edgeMargin = _overlayEdgeMarginForMode(nextMode);
    final targetX = anchorX < (screen.width / 2)
        ? edgeMargin
        : screen.width - nextGeometry.width - edgeMargin;

    return _clampOverlayPosition(
      OverlayWindowPosition(targetX, anchorY),
      nextGeometry,
      mode: nextMode,
    );
  }

  OverlayWindowPosition _clampOverlayPosition(
    OverlayWindowPosition position,
    _OverlayWindowGeometry geometry, {
    String? mode,
  }) {
    final screen = _logicalScreenSize();
    final edgeMargin = _overlayEdgeMarginForMode(
      mode ?? overlayState.value.mode,
    );
    final maxX = math.max(
      edgeMargin,
      screen.width - geometry.width - edgeMargin,
    );
    final maxY = math.max(
      edgeMargin,
      screen.height - geometry.height - edgeMargin,
    );
    return OverlayWindowPosition(
      position.x.clamp(edgeMargin, maxX).toDouble(),
      position.y.clamp(edgeMargin, maxY).toDouble(),
    );
  }

  Size _logicalScreenSize() {
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    return Size(
      view.display.size.width / view.display.devicePixelRatio,
      view.display.size.height / view.display.devicePixelRatio,
    );
  }

  Future<void> _dismissOverlayForSystemSettings() async {
    final active = overlayActive || await _overlayPlatformGateway.isActive();
    if (!active) {
      return;
    }

    await _overlayPlatformGateway.closeOverlay();
    overlayState.value = overlayState.value.copyWith(active: false);
    _appendLog('info', '进入系统设置前已关闭悬浮窗，避免遮挡权限页面。');
    await _broadcastOverlaySnapshot();
  }

  void _applyOverlayState(OverlayStateSnapshot snapshot) {
    overlayState.value = snapshot;
  }
}
