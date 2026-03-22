import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import '../../services/agent_diagnostic_hint.dart';
import '../../services/overlay_message_bridge.dart';

enum _OverlayMode { collapsed, expanded }

String _runStatusLabel(String status) {
  switch (status) {
    case 'idle':
      return '空闲';
    case 'starting':
      return '启动中';
    case 'thinking':
      return '分析中';
    case 'acting':
      return '执行中';
    case 'completed':
      return '已完成';
    case 'failed':
      return '失败';
    case 'stopped':
      return '已停止';
    case 'stopping':
      return '停止中';
    default:
      return status;
  }
}

class OverlayApp extends StatelessWidget {
  const OverlayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0C655F)),
        scaffoldBackgroundColor: Colors.transparent,
        useMaterial3: true,
      ),
      home: const OverlayView(),
    );
  }
}

class OverlayView extends StatefulWidget {
  const OverlayView({super.key});

  @override
  State<OverlayView> createState() => _OverlayViewState();
}

class _OverlayViewState extends State<OverlayView> {
  static const double _edgeMargin = 12;
  static const int _maxLogLines = 60;

  final ReceivePort _overlayReceivePort = ReceivePort();

  StreamSubscription<dynamic>? _overlaySubscription;
  StreamSubscription<dynamic>? _overlayPortSubscription;

  Map<String, dynamic> _serviceStatus = const <String, dynamic>{};
  Map<String, dynamic> _agentConfig = const <String, dynamic>{};
  Map<String, dynamic> _agentRunStatus = const <String, dynamic>{};
  List<String> _logs = const <String>[];
  _OverlayMode _mode = _OverlayMode.expanded;
  OverlayPosition _overlayPosition = const OverlayPosition(12, 120);

  OverlayPosition? _dragStartPosition;
  Offset _dragDelta = Offset.zero;
  OverlayPosition? _pendingBubblePosition;
  bool _bubbleMoveInFlight = false;
  bool _dragFrameThrottleEnabled = true;
  int _dragFrameThrottleIntervalMs = 16;
  Timer? _bubbleMoveThrottleTimer;
  int _lastBubbleMoveAtMs = 0;

  bool get _running => _agentRunStatus['running'] == true;

  String get _currentPackageName {
    final value = _serviceStatus['currentPackageName']?.toString().trim() ?? '';
    if (value.isEmpty) {
      return '未识别前台应用';
    }
    return value;
  }

  bool get _accessibilityEnabled => _serviceStatus['enabled'] == true;

  bool get _hasActiveWindow => _serviceStatus['hasActiveWindow'] == true;

  @override
  void initState() {
    super.initState();
    _registerOverlayPort();
    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen(
      _handleOverlayEvent,
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_syncSnapshot());
    });
  }

  @override
  void dispose() {
    _overlaySubscription?.cancel();
    _overlayPortSubscription?.cancel();
    _bubbleMoveThrottleTimer?.cancel();
    _overlayReceivePort.close();
    IsolateNameServer.removePortNameMapping(
      OverlayMessageBridge.overlayPortName,
    );
    super.dispose();
  }

  void _registerOverlayPort() {
    IsolateNameServer.removePortNameMapping(
      OverlayMessageBridge.overlayPortName,
    );
    IsolateNameServer.registerPortWithName(
      _overlayReceivePort.sendPort,
      OverlayMessageBridge.overlayPortName,
    );
    _overlayPortSubscription = _overlayReceivePort.listen(_handleOverlayEvent);
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final renderCollapsed =
                _mode == _OverlayMode.collapsed ||
                constraints.maxHeight < 240 ||
                constraints.maxWidth < 220;

            return Align(
              alignment: Alignment.topLeft,
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 180),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeInCubic,
                child: renderCollapsed
                    ? _buildCollapsedBubble(context, constraints)
                    : _buildExpandedPanel(context, constraints),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildCollapsedBubble(
    BuildContext context,
    BoxConstraints constraints,
  ) {
    final bubbleColor = _running
        ? const Color(0xFFE4A646)
        : const Color(0xFF0C655F);
    final indicatorColor = _accessibilityEnabled
        ? const Color(0xFF2E7D32)
        : Theme.of(context).colorScheme.error;

    return SizedBox(
      key: const ValueKey<String>('collapsed'),
      width: _safeWidth(constraints, _dimensionsFor(_OverlayMode.collapsed)),
      height: _safeHeight(constraints, _dimensionsFor(_OverlayMode.collapsed)),
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => unawaited(_changeMode(_OverlayMode.expanded)),
        onPanStart: (_) => _handleBubblePanStart(),
        onPanUpdate: (details) => _handleBubblePanUpdate(details.delta),
        onPanEnd: (_) => unawaited(_snapBubbleToEdge()),
        child: Center(
          child: SizedBox(
            width: 64,
            height: 64,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                Positioned.fill(
                  child: PhysicalModel(
                    color: bubbleColor,
                    shape: BoxShape.circle,
                    clipBehavior: Clip.antiAlias,
                    elevation: 6,
                    shadowColor: const Color(0x33000000),
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: LinearGradient(
                          colors: [
                            bubbleColor,
                            bubbleColor.withValues(alpha: 0.78),
                          ],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                      ),
                      child: const Icon(
                        Icons.smart_toy_outlined,
                        color: Colors.white,
                        size: 28,
                      ),
                    ),
                  ),
                ),
                Positioned(
                  top: 2,
                  right: 2,
                  child: Container(
                    width: 14,
                    height: 14,
                    decoration: BoxDecoration(
                      color: indicatorColor,
                      shape: BoxShape.circle,
                      border: Border.all(color: Colors.white, width: 2),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildExpandedPanel(BuildContext context, BoxConstraints constraints) {
    final theme = Theme.of(context);
    final panelHeight = _safeHeight(
      constraints,
      _dimensionsFor(_OverlayMode.expanded),
    );
    final status = _runStatusLabel(
      _agentRunStatus['status']?.toString() ?? 'idle',
    );
    final message =
        _agentRunStatus['message']?.toString().trim().isNotEmpty == true
        ? _agentRunStatus['message']!.toString()
        : '等待启动。';
    final model = _agentConfig['model']?.toString().trim().isNotEmpty == true
        ? _agentConfig['model']!.toString()
        : '未配置';
    final hint = resolveAgentDiagnosticHint(
      serviceStatus: _serviceStatus,
      agentConfig: _agentConfig,
      agentRunStatus: _agentRunStatus,
    );
    final logHeight = math.max(120.0, math.min(220.0, panelHeight * 0.34));

    return SizedBox(
      key: const ValueKey<String>('expanded'),
      width: _safeWidth(constraints, _dimensionsFor(_OverlayMode.expanded)),
      height: panelHeight,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            colors: [Color(0xFFF7F3EA), Color(0xFFE8F5F3)],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          ),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: const Color(0x22173B3A)),
          boxShadow: const [
            BoxShadow(
              color: Color(0x30000000),
              blurRadius: 26,
              offset: Offset(0, 14),
            ),
          ],
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: ListView(
            padding: EdgeInsets.zero,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '答题悬浮窗',
                          style: theme.textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w800,
                            color: const Color(0xFF153837),
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          '当前包名：$_currentPackageName',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: const Color(0xFF5A6E6C),
                            fontFamily: 'monospace',
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton.filledTonal(
                    tooltip: '最小化',
                    visualDensity: VisualDensity.compact,
                    onPressed: () =>
                        unawaited(_changeMode(_OverlayMode.collapsed)),
                    icon: const Icon(Icons.remove),
                  ),
                  const SizedBox(width: 6),
                  IconButton(
                    tooltip: '关闭',
                    visualDensity: VisualDensity.compact,
                    onPressed: () => unawaited(_sendCommand('close_overlay')),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              _SectionCard(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      width: 42,
                      height: 42,
                      decoration: BoxDecoration(
                        color: _running
                            ? const Color(0xFFF1D39A)
                            : const Color(0xFFB8D9D5),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: Icon(
                        _running
                            ? Icons.smart_toy_outlined
                            : Icons.pause_circle_outline,
                        color: const Color(0xFF153837),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '当前状态：$status',
                            style: theme.textTheme.labelLarge?.copyWith(
                              fontWeight: FontWeight.w800,
                              color: const Color(0xFF153837),
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            message,
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall?.copyWith(
                              color: const Color(0xFF4D6664),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              if (hint != null) ...[
                const SizedBox(height: 12),
                _buildHintCard(context, hint),
              ],
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _StatusChip(
                    label: _accessibilityEnabled ? '无障碍已开启' : '无障碍未开启',
                    color: _accessibilityEnabled
                        ? const Color(0xFF2E7D32)
                        : theme.colorScheme.error,
                  ),
                  _StatusChip(
                    label: _hasActiveWindow ? '目标窗口已就绪' : '未获取目标窗口',
                    color: _hasActiveWindow
                        ? const Color(0xFF0C655F)
                        : const Color(0xFF6B7280),
                  ),
                  _StatusChip(
                    label: _running ? 'Agent 运行中' : 'Agent 已停止',
                    color: _running
                        ? const Color(0xFF8A5A00)
                        : const Color(0xFF0C655F),
                  ),
                  _StatusChip(
                    label: '模型：$model',
                    color: const Color(0xFF0C655F),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  FilledButton.icon(
                    onPressed: _running ? null : _startAgent,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('启动'),
                  ),
                  FilledButton.tonalIcon(
                    onPressed: _running ? _stopAgent : null,
                    icon: const Icon(Icons.stop_circle_outlined),
                    label: const Text('停止'),
                  ),
                  OutlinedButton.icon(
                    onPressed: _syncSnapshot,
                    icon: const Icon(Icons.sync),
                    label: const Text('同步'),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Text(
                '运行日志',
                style: theme.textTheme.labelLarge?.copyWith(
                  fontWeight: FontWeight.w800,
                  color: const Color(0xFF153837),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                height: logHeight,
                child: _SectionCard(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 8),
                  child: _logs.isEmpty
                      ? Center(
                          child: Text(
                            '暂无日志输出。',
                            style: theme.textTheme.bodyMedium?.copyWith(
                              color: const Color(0xFF6A7B79),
                            ),
                          ),
                        )
                      : ListView.separated(
                          itemCount: _logs.length,
                          separatorBuilder: (context, index) =>
                              const SizedBox(height: 6),
                          itemBuilder: (context, index) {
                            final line = _logs[index];
                            return Text(
                              line,
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: const Color(0xFF284644),
                                fontFamily: 'monospace',
                                height: 1.35,
                              ),
                            );
                          },
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildHintCard(BuildContext context, AgentDiagnosticHint hint) {
    final theme = Theme.of(context);
    final (color, icon) = switch (hint.severity) {
      AgentDiagnosticSeverity.info => (
        const Color(0xFF0C655F),
        Icons.info_outline,
      ),
      AgentDiagnosticSeverity.warning => (
        const Color(0xFF9A6700),
        Icons.warning_amber_outlined,
      ),
      AgentDiagnosticSeverity.error => (
        theme.colorScheme.error,
        Icons.error_outline,
      ),
    };

    final actionText = hint.actions.isEmpty ? hint.summary : hint.actions.first;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              actionText,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: color,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _changeMode(_OverlayMode mode) async {
    if (!mounted) {
      return;
    }
    setState(() {
      _mode = mode;
    });
    await _resizeOverlayForMode(mode);
    await _sendCommand(
      'set_overlay_mode',
      arguments: <String, dynamic>{'mode': mode.name},
    );
  }

  Future<void> _resizeOverlayForMode(_OverlayMode mode) async {
    final dimensions = _dimensionsFor(mode);
    try {
      await FlutterOverlayWindow.resizeOverlay(
        dimensions.width,
        dimensions.height,
        false,
      );
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _appendOverlayLog('resize overlay failed: $error');
      });
    }
  }

  void _handleBubblePanStart() {
    _dragStartPosition = _overlayPosition;
    _dragDelta = Offset.zero;
    _pendingBubblePosition = null;
    _bubbleMoveThrottleTimer?.cancel();
    _bubbleMoveThrottleTimer = null;
  }

  void _handleBubblePanUpdate(Offset delta) {
    final start = _dragStartPosition;
    if (start == null) {
      return;
    }

    _dragDelta += delta;
    final target = _clampPosition(
      OverlayPosition(start.x + _dragDelta.dx, start.y + _dragDelta.dy),
      _dimensionsFor(_OverlayMode.collapsed),
    );
    if (_isPositionClose(_overlayPosition, target)) {
      return;
    }
    // 记录最新目标位置并串行提交，避免高频手势触发并发 moveOverlay 导致抖动与闪动。
    _overlayPosition = target;
    _pendingBubblePosition = target;
    _scheduleBubbleMoveFlush();
  }

  Future<void> _snapBubbleToEdge() async {
    _bubbleMoveThrottleTimer?.cancel();
    _bubbleMoveThrottleTimer = null;
    await _flushBubbleMoveQueue(forceImmediate: true);
    final dimensions = _dimensionsFor(_OverlayMode.collapsed);
    final screen = _logicalScreenSize();
    final targetX =
        _overlayPosition.x + (dimensions.width / 2) < (screen.width / 2)
        ? _edgeMargin
        : screen.width - dimensions.width - _edgeMargin;
    final target = _clampPosition(
      OverlayPosition(targetX, _overlayPosition.y),
      dimensions,
    );
    if (!_isPositionClose(_overlayPosition, target)) {
      await FlutterOverlayWindow.moveOverlay(target);
    }
    _overlayPosition = target;
    _dragStartPosition = null;
    _pendingBubblePosition = null;
    await _sendCommand(
      'move_overlay',
      arguments: <String, dynamic>{'x': target.x, 'y': target.y},
    );
  }

  void _scheduleBubbleMoveFlush() {
    if (_bubbleMoveInFlight) {
      return;
    }
    if (!_dragFrameThrottleEnabled) {
      unawaited(_flushBubbleMoveQueue(forceImmediate: true));
      return;
    }
    if (_bubbleMoveThrottleTimer?.isActive == true) {
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    final elapsed = now - _lastBubbleMoveAtMs;
    final delayMs = (elapsed >= _dragFrameThrottleIntervalMs)
        ? 0
        : (_dragFrameThrottleIntervalMs - elapsed);
    _bubbleMoveThrottleTimer = Timer(Duration(milliseconds: delayMs), () {
      _bubbleMoveThrottleTimer = null;
      unawaited(_flushBubbleMoveQueue());
    });
  }

  Future<void> _flushBubbleMoveQueue({bool forceImmediate = false}) async {
    if (_bubbleMoveInFlight) {
      return;
    }
    final target = _pendingBubblePosition;
    if (target == null) {
      return;
    }
    if (!forceImmediate && _dragFrameThrottleEnabled) {
      final now = DateTime.now().millisecondsSinceEpoch;
      final elapsed = now - _lastBubbleMoveAtMs;
      if (elapsed < _dragFrameThrottleIntervalMs) {
        _scheduleBubbleMoveFlush();
        return;
      }
    }

    _bubbleMoveInFlight = true;
    _pendingBubblePosition = null;
    try {
      await FlutterOverlayWindow.moveOverlay(target);
      _lastBubbleMoveAtMs = DateTime.now().millisecondsSinceEpoch;
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        // 仅在移动失败时写日志，避免拖动期间触发多余重建。
        _appendOverlayLog('move overlay failed: $error');
      });
    } finally {
      _bubbleMoveInFlight = false;
    }
    if (_pendingBubblePosition != null) {
      _scheduleBubbleMoveFlush();
    }
  }

  bool _isPositionClose(OverlayPosition a, OverlayPosition b) {
    // 浮点坐标允许极小抖动，避免重复提交几乎相同的位置导致系统层闪烁。
    return (a.x - b.x).abs() < 0.5 && (a.y - b.y).abs() < 0.5;
  }

  _OverlayDimensions _dimensionsFor(_OverlayMode mode) {
    final screen = _logicalScreenSize();
    final maxWidth = math.max(96.0, screen.width - (_edgeMargin * 2));
    final maxHeight = math.max(96.0, screen.height - (_edgeMargin * 2));

    switch (mode) {
      case _OverlayMode.collapsed:
        return _OverlayDimensions(
          width: _boundDimension(82, min: 74, max: maxWidth),
          height: _boundDimension(82, min: 74, max: maxHeight),
        );
      case _OverlayMode.expanded:
        return _OverlayDimensions(
          width: _boundDimension(
            math.min(380.0, maxWidth),
            min: 280,
            max: maxWidth,
          ),
          height: _boundDimension(
            math.min(560.0, maxHeight),
            min: 420,
            max: maxHeight,
          ),
        );
    }
  }

  Size _logicalScreenSize() {
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    return Size(
      view.display.size.width / view.display.devicePixelRatio,
      view.display.size.height / view.display.devicePixelRatio,
    );
  }

  OverlayPosition _clampPosition(
    OverlayPosition position,
    _OverlayDimensions dimensions,
  ) {
    final screen = _logicalScreenSize();
    final maxX = math.max(
      _edgeMargin,
      screen.width - dimensions.width - _edgeMargin,
    );
    final maxY = math.max(
      _edgeMargin,
      screen.height - dimensions.height - _edgeMargin,
    );

    return OverlayPosition(
      position.x.clamp(_edgeMargin, maxX).toDouble(),
      position.y.clamp(_edgeMargin, maxY).toDouble(),
    );
  }

  double _safeWidth(BoxConstraints constraints, _OverlayDimensions fallback) {
    if (constraints.maxWidth.isFinite && constraints.maxWidth > 0) {
      return constraints.maxWidth;
    }
    return fallback.width.toDouble();
  }

  double _safeHeight(BoxConstraints constraints, _OverlayDimensions fallback) {
    if (constraints.maxHeight.isFinite && constraints.maxHeight > 0) {
      return constraints.maxHeight;
    }
    return fallback.height.toDouble();
  }

  int _boundDimension(
    double preferred, {
    required double min,
    required double max,
  }) {
    final lowerBound = math.min(min, max);
    final upperBound = math.max(min, max);
    return preferred.clamp(lowerBound, upperBound).round();
  }

  Future<void> _syncSnapshot() async {
    await _sendCommand('sync_snapshot');
  }

  Future<void> _startAgent() async {
    await _sendCommand('start_agent');
  }

  Future<void> _stopAgent() async {
    await _sendCommand('stop_agent');
  }

  Future<void> _sendCommand(
    String action, {
    Map<String, dynamic> arguments = const <String, dynamic>{},
  }) async {
    final payload = jsonEncode(<String, dynamic>{
      'source': 'overlay',
      'type': 'command',
      'action': action,
      ...arguments,
    });
    final mainPort = IsolateNameServer.lookupPortByName(
      OverlayMessageBridge.mainPortName,
    );
    if (mainPort != null) {
      mainPort.send(payload);
      return;
    }

    await FlutterOverlayWindow.shareData(payload);

    if (!mounted) {
      return;
    }

    setState(() {
      _agentRunStatus = <String, dynamic>{
        ..._agentRunStatus,
        'running': false,
        'status': 'failed',
        'message': '未连接到主界面，请先返回应用主页。',
        'updatedAt': DateTime.now().millisecondsSinceEpoch,
      };
    });
  }

  void _handleOverlayEvent(dynamic rawEvent) {
    final event = _normalizeEvent(rawEvent);
    if (event == null || event['source'] != 'main') {
      return;
    }

    if (!mounted) {
      return;
    }

    setState(() {
      switch (event['type']) {
        case 'snapshot':
          _serviceStatus = _mapValue(event['serviceStatus']);
          _agentConfig = _mapValue(event['agentConfig']);
          _agentRunStatus = _mapValue(event['agentRunStatus']);
          final snapshotLogs = _stringList(event['logs']);
          if (snapshotLogs.isNotEmpty) {
            _logs = snapshotLogs;
          }
          _applyOverlayState(_mapValue(event['overlayState']));
          break;
        case 'service_status':
          _serviceStatus = _mapValue(event['payload']);
          break;
        case 'agent_config':
          _agentConfig = _mapValue(event['payload']);
          break;
        case 'agent_status':
          _agentRunStatus = _mapValue(event['payload']);
          break;
        case 'overlay_state':
          _applyOverlayState(_mapValue(event['payload']));
          break;
        case 'log':
          _appendOverlayLog(event['line']?.toString() ?? '');
          break;
      }
    });
  }

  void _applyOverlayState(Map<String, dynamic> state) {
    final modeValue = state['mode']?.toString();
    if (modeValue == 'collapsed') {
      _mode = _OverlayMode.collapsed;
    } else if (modeValue == 'expanded') {
      _mode = _OverlayMode.expanded;
    }

    final position = _mapValue(state['position']);
    final x = _readDouble(position['x']);
    final y = _readDouble(position['y']);
    if (x != null && y != null) {
      _overlayPosition = OverlayPosition(x, y);
    }

    final throttleEnabled = _readBool(state['dragFrameThrottleEnabled']);
    if (throttleEnabled != null) {
      final changed = _dragFrameThrottleEnabled != throttleEnabled;
      _dragFrameThrottleEnabled = throttleEnabled;
      if (changed && !_dragFrameThrottleEnabled) {
        // 关闭节流后立即消费待移动位置，避免仍被旧定时器延后造成拖动迟滞。
        _bubbleMoveThrottleTimer?.cancel();
        _bubbleMoveThrottleTimer = null;
        unawaited(_flushBubbleMoveQueue(forceImmediate: true));
      }
    }
    final throttleIntervalMs = _readInt(state['dragFrameThrottleIntervalMs']);
    if (throttleIntervalMs != null) {
      // 16ms 约等于 60fps，这里限定上下界避免异常值导致拖动行为失控。
      _dragFrameThrottleIntervalMs = throttleIntervalMs.clamp(8, 64).toInt();
    }
  }

  void _appendOverlayLog(String line) {
    final normalizedLine = line.trim();
    if (normalizedLine.isEmpty) {
      return;
    }
    final formattedLine = _hasTimePrefix(normalizedLine)
        ? normalizedLine
        : '[${_formatLogTime(DateTime.now())}][调试] $normalizedLine';
    _logs = <String>[formattedLine, ..._logs]
        .take(_maxLogLines)
        .toList(growable: false);
  }

  Map<String, dynamic>? _normalizeEvent(dynamic rawEvent) {
    if (rawEvent is Map) {
      return rawEvent.map(
        (dynamic key, dynamic value) => MapEntry(key.toString(), value),
      );
    }
    if (rawEvent is String && rawEvent.isNotEmpty) {
      try {
        final decoded = jsonDecode(rawEvent);
        if (decoded is Map) {
          return decoded.map(
            (dynamic key, dynamic value) => MapEntry(key.toString(), value),
          );
        }
      } on FormatException {
        return null;
      }
    }
    return null;
  }

  Map<String, dynamic> _mapValue(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) => MapEntry(key.toString(), item),
      );
    }
    return const <String, dynamic>{};
  }

  List<String> _stringList(dynamic value) {
    if (value is List) {
      return value
          .map((dynamic item) => item?.toString() ?? '')
          .where((String item) => item.trim().isNotEmpty)
          .toList(growable: false);
    }
    return const <String>[];
  }

  double? _readDouble(dynamic value) {
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value?.toString() ?? '');
  }

  int? _readInt(dynamic value) {
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value?.toString() ?? '');
  }

  bool? _readBool(dynamic value) {
    if (value is bool) {
      return value;
    }
    final normalized = value?.toString().trim().toLowerCase();
    if (normalized == 'true') {
      return true;
    }
    if (normalized == 'false') {
      return false;
    }
    return null;
  }

  bool _hasTimePrefix(String line) {
    return RegExp(r'^\[\d{2}:\d{2}:\d{2}\]').hasMatch(line);
  }

  String _formatLogTime(DateTime time) {
    final hour = time.hour.toString().padLeft(2, '0');
    final minute = time.minute.toString().padLeft(2, '0');
    final second = time.second.toString().padLeft(2, '0');
    return '$hour:$minute:$second';
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
          color: color,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.child,
    this.padding = const EdgeInsets.all(12),
  });

  final Widget child;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(18),
      ),
      child: child,
    );
  }
}

class _OverlayDimensions {
  const _OverlayDimensions({required this.width, required this.height});

  final int width;
  final int height;
}
