import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

import '../../services/overlay_message_bridge.dart';

enum _OverlayWindowMode { compact, standard, expanded }

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
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0B5D5B)),
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
  final ReceivePort _overlayReceivePort = ReceivePort();

  StreamSubscription<dynamic>? _overlaySubscription;
  StreamSubscription<dynamic>? _overlayPortSubscription;
  Map<String, dynamic> _serviceStatus = const <String, dynamic>{};
  Map<String, dynamic> _agentConfig = const <String, dynamic>{};
  Map<String, dynamic> _agentRunStatus = const <String, dynamic>{};
  _OverlayWindowMode _windowMode = _OverlayWindowMode.standard;

  bool get _running => _agentRunStatus['running'] == true;

  @override
  void initState() {
    super.initState();
    _registerOverlayPort();
    _overlaySubscription = FlutterOverlayWindow.overlayListener.listen(
      _handleOverlayEvent,
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_syncSnapshot());
      unawaited(_applyWindowMode(_windowMode));
    });
  }

  @override
  void dispose() {
    _overlaySubscription?.cancel();
    _overlayPortSubscription?.cancel();
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
    final theme = Theme.of(context);
    final enabled = _serviceStatus['enabled'] == true;
    final hasWindow = _serviceStatus['hasActiveWindow'] == true;
    final model = _agentConfig['model']?.toString() ?? '未设置';
    final providerUrl = _agentConfig['providerUrl']?.toString() ?? '';
    final status = _runStatusLabel(
      _agentRunStatus['status']?.toString() ?? 'idle',
    );
    final message = _agentRunStatus['message']?.toString() ?? '就绪';

    return Material(
      color: Colors.transparent,
      child: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final isCompact =
                _windowMode == _OverlayWindowMode.compact ||
                constraints.maxHeight < 300;
            final showStatusChips = !isCompact && constraints.maxHeight >= 340;
            final showConfig =
                _windowMode == _OverlayWindowMode.expanded &&
                constraints.maxHeight >= 420;
            final denseActions = constraints.maxWidth < 360;
            final cardWidth = _cardMaxWidth(constraints.maxWidth);
            final viewLabel = switch (_windowMode) {
              _OverlayWindowMode.compact => '紧凑视图',
              _OverlayWindowMode.standard => '常规视图',
              _OverlayWindowMode.expanded => '放大视图',
            };

            return Align(
              alignment: Alignment.topCenter,
              child: Container(
                width: cardWidth,
                constraints: BoxConstraints(maxHeight: constraints.maxHeight),
                margin: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFFF8F5EE), Color(0xFFF0F7F6)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(isCompact ? 22 : 26),
                  border: Border.all(color: const Color(0x1A173B3A)),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x33000000),
                      blurRadius: 24,
                      offset: Offset(0, 12),
                    ),
                  ],
                ),
                child: Padding(
                  padding: EdgeInsets.all(
                    isCompact
                        ? 12
                        : denseActions
                        ? 14
                        : 16,
                  ),
                  child: DefaultTextStyle(
                    style:
                        theme.textTheme.bodyMedium?.copyWith(
                          color: const Color(0xFF173B3A),
                        ) ??
                        const TextStyle(color: Color(0xFF173B3A)),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '答题代理悬浮窗',
                                    style: theme.textTheme.titleMedium
                                        ?.copyWith(
                                          fontWeight: FontWeight.w800,
                                          color: const Color(0xFF123332),
                                        ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    '$viewLabel · 拖动顶部空白区域移动',
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: const Color(0xFF58706E),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            IconButton.filledTonal(
                              tooltip: '缩小',
                              visualDensity: VisualDensity.compact,
                              onPressed: () =>
                                  _applyWindowMode(_OverlayWindowMode.compact),
                              icon: Icon(
                                Icons.remove,
                                color: _windowMode == _OverlayWindowMode.compact
                                    ? theme.colorScheme.primary
                                    : null,
                              ),
                            ),
                            const SizedBox(width: 6),
                            IconButton.filledTonal(
                              tooltip: '常规',
                              visualDensity: VisualDensity.compact,
                              onPressed: () =>
                                  _applyWindowMode(_OverlayWindowMode.standard),
                              icon: Icon(
                                Icons.crop_square,
                                color:
                                    _windowMode == _OverlayWindowMode.standard
                                    ? theme.colorScheme.primary
                                    : null,
                              ),
                            ),
                            const SizedBox(width: 6),
                            IconButton.filledTonal(
                              tooltip: '放大',
                              visualDensity: VisualDensity.compact,
                              onPressed: () =>
                                  _applyWindowMode(_OverlayWindowMode.expanded),
                              icon: Icon(
                                Icons.open_in_full,
                                color:
                                    _windowMode == _OverlayWindowMode.expanded
                                    ? theme.colorScheme.primary
                                    : null,
                              ),
                            ),
                            const SizedBox(width: 6),
                            IconButton(
                              visualDensity: VisualDensity.compact,
                              onPressed: () =>
                                  FlutterOverlayWindow.closeOverlay(),
                              icon: const Icon(Icons.close),
                            ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: const Color(0xFFEEF5F4),
                            borderRadius: BorderRadius.circular(18),
                          ),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Container(
                                width: 34,
                                height: 34,
                                decoration: BoxDecoration(
                                  color: _running
                                      ? const Color(0xFFF2C078)
                                      : const Color(0xFFB7D8D4),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Icon(
                                  _running
                                      ? Icons.smart_toy_outlined
                                      : Icons.pause_circle_outline,
                                  color: const Color(0xFF123332),
                                ),
                              ),
                              const SizedBox(width: 10),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      '当前状态：$status',
                                      style: theme.textTheme.labelLarge
                                          ?.copyWith(
                                            fontWeight: FontWeight.w700,
                                          ),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      message,
                                      maxLines: isCompact ? 2 : 3,
                                      overflow: TextOverflow.ellipsis,
                                      style: theme.textTheme.bodySmall
                                          ?.copyWith(
                                            color: const Color(0xFF4A6260),
                                          ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                        if (showStatusChips) ...[
                          const SizedBox(height: 12),
                          Wrap(
                            spacing: 8,
                            runSpacing: 8,
                            children: [
                              _StatusChip(
                                label: enabled ? '无障碍已开启' : '无障碍未开启',
                                color: enabled
                                    ? const Color(0xFF2E7D32)
                                    : const Color(0xFFB3261E),
                              ),
                              _StatusChip(
                                label: hasWindow ? '窗口已就绪' : '没有活动窗口',
                                color: hasWindow
                                    ? const Color(0xFF0B5D5B)
                                    : const Color(0xFF6B7280),
                              ),
                              _StatusChip(
                                label: _running ? '代理运行中' : '代理空闲',
                                color: _running
                                    ? const Color(0xFF7C4D00)
                                    : const Color(0xFF0B5D5B),
                              ),
                            ],
                          ),
                        ],
                        if (showConfig) ...[
                          const SizedBox(height: 12),
                          _SectionCard(
                            title: '当前配置',
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                _ConfigRow(
                                  icon: Icons.cloud_outlined,
                                  label: '提供商',
                                  value: providerUrl.isEmpty
                                      ? '未设置'
                                      : providerUrl,
                                ),
                                const SizedBox(height: 8),
                                _ConfigRow(
                                  icon: Icons.memory_outlined,
                                  label: '模型',
                                  value: model,
                                ),
                              ],
                            ),
                          ),
                        ],
                        const SizedBox(height: 12),
                        if (denseActions)
                          Row(
                            children: [
                              Expanded(
                                child: FilledButton(
                                  onPressed: _running ? null : _startAgent,
                                  child: const Text('启动'),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: FilledButton.tonal(
                                  onPressed: _running ? _stopAgent : null,
                                  child: const Text('停止'),
                                ),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: OutlinedButton(
                                  onPressed: _syncSnapshot,
                                  child: const Text('同步'),
                                ),
                              ),
                            ],
                          )
                        else
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
                      ],
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Future<void> _applyWindowMode(_OverlayWindowMode mode) async {
    if (!mounted) {
      return;
    }
    setState(() {
      _windowMode = mode;
    });
    final dimensions = _resolveWindowDimensions();
    await FlutterOverlayWindow.resizeOverlay(
      dimensions.width,
      dimensions.height,
      true,
    );
  }

  _OverlayDimensions _resolveWindowDimensions() {
    final view = WidgetsBinding.instance.platformDispatcher.views.first;
    final screenWidth = view.physicalSize.width / view.devicePixelRatio;
    final screenHeight = view.physicalSize.height / view.devicePixelRatio;
    final availableWidth = math.max(260.0, screenWidth - 24);
    final availableHeight = math.max(220.0, screenHeight - 96);

    switch (_windowMode) {
      case _OverlayWindowMode.compact:
        return _OverlayDimensions(
          width: math.min(320.0, availableWidth).round(),
          height: _boundDimension(230, min: 190, max: availableHeight),
        );
      case _OverlayWindowMode.standard:
        return _OverlayDimensions(
          width: math.min(420.0, availableWidth).round(),
          height: _boundDimension(400, min: 340, max: availableHeight),
        );
      case _OverlayWindowMode.expanded:
        return _OverlayDimensions(
          width: math.min(560.0, availableWidth).round(),
          height: _boundDimension(
            availableHeight * 0.82,
            min: 480,
            max: availableHeight,
          ),
        );
    }
  }

  double _cardMaxWidth(double maxWidth) {
    if (!maxWidth.isFinite) {
      return _windowMode == _OverlayWindowMode.expanded ? 560 : 460;
    }
    final preferred = _windowMode == _OverlayWindowMode.expanded
        ? 560.0
        : 460.0;
    return math.min(maxWidth, preferred);
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

  Future<void> _sendCommand(String action) async {
    final payload = jsonEncode(<String, dynamic>{
      'source': 'overlay',
      'type': 'command',
      'action': action,
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
        'message': '未连接到主界面，请先打开代理控制台。',
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
      }
    });
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
  const _SectionCard({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.92),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: Theme.of(
              context,
            ).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          child,
        ],
      ),
    );
  }
}

class _ConfigRow extends StatelessWidget {
  const _ConfigRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 18, color: const Color(0xFF58706E)),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: const Color(0xFF58706E),
                ),
              ),
              const SizedBox(height: 2),
              Text(
                value,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(
                  context,
                ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _OverlayDimensions {
  const _OverlayDimensions({required this.width, required this.height});

  final int width;
  final int height;
}
