import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../services/agent_diagnostic_hint.dart';
import 'home_controller.dart';

/// 首页视图，仅保留权限、配置、诊断和日志四个核心区域，避免首屏层级过深。
class HomeView extends GetView<HomeController> {
  const HomeView({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final horizontalPadding = constraints.maxWidth >= 920 ? 24.0 : 16.0;

            return Scrollbar(
              thumbVisibility: constraints.maxWidth >= 920,
              child: SingleChildScrollView(
                padding: EdgeInsets.fromLTRB(
                  horizontalPadding,
                  16,
                  horizontalPadding,
                  24,
                ),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 980),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _MainPanels(controller: controller),
                        _HintSection(controller: controller),
                        const SizedBox(height: 12),
                        Obx(() => _LogsCard(logs: controller.logs.toList())),
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
}

/// 首页头部摘要卡片，将运行状态和关键配置集中在同一视觉区域，减少用户视线跳转。
// class _HeaderCard extends StatelessWidget {
//   const _HeaderCard({required this.controller});
//
//   final HomeController controller;
//
//   @override
//   Widget build(BuildContext context) {
//     return Obx(() {
//       final theme = Theme.of(context);
//       final textTheme = theme.textTheme;
//       final accessibilityEnabled = controller.serviceStatus['enabled'] == true;
//       final overlayPermissionGranted =
//           controller.overlayPermissionGranted.value;
//       final overlayActive = controller.overlayActive.value;
//       final hasApiKey = controller.agentConfig['hasApiKey'] == true;
//       final providerUrl =
//           controller.agentConfig['providerUrl']?.toString().trim() ?? '';
//       final modelValue =
//           controller.agentConfig['model']?.toString().trim() ?? '';
//       final model = modelValue.isEmpty ? 'gpt-5-mini' : modelValue;
//       final runStatus =
//           controller.agentRunStatus['status']?.toString() ?? 'idle';
//       final runMessage =
//           controller.agentRunStatus['message']?.toString().trim() ?? '等待启动';
//       final runMeta = _runStatusMeta(theme.colorScheme, runStatus);
//
//       return Card(
//         child: Padding(
//           padding: const EdgeInsets.all(20),
//           child: Column(
//             crossAxisAlignment: CrossAxisAlignment.start,
//             children: [
//               LayoutBuilder(
//                 builder: (context, constraints) {
//                   final wide = constraints.maxWidth >= 560;
//                   final title = Text(
//                     'AI自动答题',
//                     style: textTheme.headlineSmall?.copyWith(
//                       color: theme.colorScheme.primary,
//                       fontWeight: FontWeight.w800,
//                       height: 1.1,
//                     ),
//                   );
//
//                   if (!wide) {
//                     return Column(
//                       crossAxisAlignment: CrossAxisAlignment.start,
//                       children: [
//                         title,
//                         const SizedBox(height: 12),
//                         _StatusPill(
//                           label: runMeta.$1,
//                           backgroundColor: runMeta.$2,
//                           foregroundColor: runMeta.$3,
//                         ),
//                       ],
//                     );
//                   }
//
//                   return Row(
//                     children: [
//                       Expanded(child: title),
//                       _StatusPill(
//                         label: runMeta.$1,
//                         backgroundColor: runMeta.$2,
//                         foregroundColor: runMeta.$3,
//                       ),
//                     ],
//                   );
//                 },
//               ),
//               const SizedBox(height: 10),
//               Text(
//                 '首页仅保留最常用的权限、配置和运行状态，减少查找路径，常见操作都能在一屏内完成。',
//                 style: textTheme.bodyMedium?.copyWith(
//                   color: theme.colorScheme.onSurfaceVariant,
//                   height: 1.55,
//                 ),
//               ),
//               const SizedBox(height: 16),
//               Wrap(
//                 spacing: 10,
//                 runSpacing: 10,
//                 children: [
//                   _StatusBadge(
//                     icon: accessibilityEnabled
//                         ? Icons.verified_user_outlined
//                         : Icons.privacy_tip_outlined,
//                     label: accessibilityEnabled ? '无障碍已开启' : '无障碍待开启',
//                   ),
//                   _StatusBadge(
//                     icon: overlayActive
//                         ? Icons.picture_in_picture_alt_outlined
//                         : Icons.layers_outlined,
//                     label: overlayActive
//                         ? '悬浮窗显示中'
//                         : overlayPermissionGranted
//                         ? '悬浮窗已授权'
//                         : '悬浮窗待授权',
//                   ),
//                   _StatusBadge(
//                     icon: hasApiKey
//                         ? Icons.key_outlined
//                         : Icons.key_off_outlined,
//                     label: hasApiKey ? '密钥已保存' : '密钥待填写',
//                   ),
//                 ],
//               ),
//               const SizedBox(height: 16),
//               Container(
//                 width: double.infinity,
//                 padding: const EdgeInsets.all(16),
//                 decoration: BoxDecoration(
//                   color: theme.colorScheme.primaryContainer.withValues(
//                     alpha: 0.32,
//                   ),
//                   borderRadius: BorderRadius.circular(20),
//                   border: Border.all(
//                     color: theme.colorScheme.outlineVariant.withValues(
//                       alpha: 0.8,
//                     ),
//                   ),
//                 ),
//                 child: Column(
//                   crossAxisAlignment: CrossAxisAlignment.start,
//                   children: [
//                     Text(
//                       '当前状态',
//                       style: textTheme.titleMedium?.copyWith(
//                         fontWeight: FontWeight.w800,
//                       ),
//                     ),
//                     const SizedBox(height: 10),
//                     Text(
//                       runMessage.isEmpty ? '等待启动' : runMessage,
//                       maxLines: 4,
//                       overflow: TextOverflow.ellipsis,
//                       style: textTheme.bodyMedium?.copyWith(
//                         color: theme.colorScheme.onSurface,
//                         height: 1.5,
//                       ),
//                     ),
//                   ],
//                 ),
//               ),
//               const SizedBox(height: 16),
//               Wrap(
//                 spacing: 10,
//                 runSpacing: 10,
//                 children: [
//                   _InfoPill(
//                     label: '提供商',
//                     value: providerUrl.isEmpty ? '未填写' : '已填写',
//                   ),
//                   _InfoPill(label: '模型', value: model),
//                 ],
//               ),
//             ],
//           ),
//         ),
//       );
//     });
//   }
// }

/// 首页主操作区，保留权限卡片和配置卡片，便于用户按任务顺序完成设置。
class _MainPanels extends StatelessWidget {
  const _MainPanels({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 900;
        final accessCard = _AccessCard(controller: controller);
        final configCard = _AgentConfigCard(controller: controller);

        if (!wide) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [accessCard, const SizedBox(height: 12), configCard],
          );
        }

        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: accessCard),
            const SizedBox(width: 12),
            Expanded(flex: 2, child: configCard),
          ],
        );
      },
    );
  }
}

class _AccessCard extends StatelessWidget {
  const _AccessCard({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Obx(() {
      final accessibilityEnabled = controller.serviceStatus['enabled'] == true;
      final hasActiveWindow =
          controller.serviceStatus['hasActiveWindow'] == true;
      final isBusy = controller.isBusy.value;

      return _SectionCard(
        title: '系统状态',
        subtitle: '完成权限授权后，悬浮窗和自动化能力才能稳定工作。',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer.withValues(
                  alpha: 0.5,
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Text(
                accessibilityEnabled
                    ? hasActiveWindow
                          ? '当前已检测到前台页面，可以继续使用自动操作。'
                          : '无障碍服务已开启，但还没有读取到前台页面。'
                    : '建议先开启无障碍服务，再显示悬浮窗。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurface,
                  height: 1.5,
                ),
              ),
            ),
            const SizedBox(height: 14),
            _SwitchTile(
              icon: Icons.layers_outlined,
              title: '悬浮窗权限',
              subtitle: controller.overlayPermissionGranted.value
                  ? '已授权，可以直接打开悬浮窗。'
                  : '开启后才能显示悬浮窗控制面板。',
              value: controller.overlayPermissionGranted.value,
              enabled: !isBusy,
              onChanged: controller.toggleOverlayPermission,
            ),
            const SizedBox(height: 12),
            _SwitchTile(
              icon: Icons.settings_accessibility,
              title: '无障碍服务',
              subtitle: accessibilityEnabled
                  ? '已开启，切换时会跳转到系统设置。'
                  : '开启后才能读取当前页面和执行自动操作。',
              value: accessibilityEnabled,
              enabled: !isBusy,
              onChanged: controller.toggleAccessibilityService,
            ),
            const SizedBox(height: 12),
            _SwitchTile(
              icon: Icons.picture_in_picture_alt_outlined,
              title: '显示悬浮窗',
              subtitle: controller.overlayActive.value
                  ? '悬浮窗显示中。'
                  : '开启后会显示控制面板。',
              value: controller.overlayActive.value,
              enabled: !isBusy,
              onChanged: controller.toggleOverlayVisibility,
            ),
          ],
        ),
      );
    });
  }
}

class _AgentConfigCard extends StatelessWidget {
  const _AgentConfigCard({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final hasApiKey = controller.agentConfig['hasApiKey'] == true;
      final providerUrl =
          controller.agentConfig['providerUrl']?.toString().trim() ?? '';
      final modelValue =
          controller.agentConfig['model']?.toString().trim() ?? '';
      final model = modelValue.isEmpty ? 'gpt-5-mini' : modelValue;

      return _SectionCard(
        title: 'Agent 配置',
        subtitle: '首页只展示基础配置，高级阈值继续沿用当前已保存的值。',
        child: LayoutBuilder(
          builder: (context, constraints) {
            final wide = constraints.maxWidth >= 720;

            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  children: [
                    _InfoPill(
                      label: '提供商',
                      value: providerUrl.isEmpty ? '未填写' : '已填写',
                    ),
                    _InfoPill(label: '密钥', value: hasApiKey ? '已保存' : '待填写'),
                    _InfoPill(label: '模型', value: model),
                  ],
                ),
                const SizedBox(height: 16),
                if (wide)
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: TextField(
                          controller: controller.providerUrlController,
                          keyboardType: TextInputType.url,
                          decoration: const InputDecoration(
                            labelText: '提供商 URL',
                            hintText: 'https://api.openai.com',
                          ),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: TextField(
                          controller: controller.modelController,
                          decoration: const InputDecoration(
                            labelText: '模型',
                            hintText: 'gpt-5-mini',
                          ),
                        ),
                      ),
                    ],
                  )
                else
                  Column(
                    children: [
                      TextField(
                        controller: controller.providerUrlController,
                        keyboardType: TextInputType.url,
                        decoration: const InputDecoration(
                          labelText: '提供商 URL',
                          hintText: 'https://api.openai.com',
                        ),
                      ),
                      const SizedBox(height: 16),
                      TextField(
                        controller: controller.modelController,
                        decoration: const InputDecoration(
                          labelText: '模型',
                          hintText: 'gpt-5-mini',
                        ),
                      ),
                    ],
                  ),
                const SizedBox(height: 16),
                TextField(
                  controller: controller.apiKeyController,
                  obscureText: true,
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: InputDecoration(
                    labelText: '提供商 API 密钥',
                    hintText: hasApiKey ? '已保存到本地，可直接覆盖' : 'sk-...',
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  '如需调整运行阈值，可在后续高级设置中再修改，这里默认沿用已有配置。',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: () async {
                      await controller.saveAgentConfig();
                    },
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('保存配置'),
                  ),
                ),
              ],
            );
          },
        ),
      );
    });
  }
}

class _HintSection extends StatelessWidget {
  const _HintSection({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final hint = resolveAgentDiagnosticHint(
        serviceStatus: controller.serviceStatus,
        agentConfig: controller.agentConfig,
        agentRunStatus: controller.agentRunStatus,
      );

      if (hint == null) {
        return const SizedBox.shrink();
      }

      return Padding(
        padding: const EdgeInsets.only(top: 16),
        child: _DiagnosticHintCard(hint: hint),
      );
    });
  }
}

class _DiagnosticHintCard extends StatelessWidget {
  const _DiagnosticHintCard({required this.hint});

  final AgentDiagnosticHint hint;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tone = switch (hint.severity) {
      AgentDiagnosticSeverity.info => (
        theme.colorScheme.primary,
        '信息',
        Icons.info_outline,
      ),
      AgentDiagnosticSeverity.warning => (
        const Color(0xFF9A6700),
        '警告',
        Icons.warning_amber_outlined,
      ),
      AgentDiagnosticSeverity.error => (
        theme.colorScheme.error,
        '异常',
        Icons.error_outline,
      ),
    };

    return _SectionCard(
      title: hint.title,
      subtitle: hint.summary,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(tone.$3, color: tone.$1),
              const SizedBox(width: 8),
              _StatusPill(
                label: tone.$2,
                backgroundColor: tone.$1.withValues(alpha: 0.12),
                foregroundColor: tone.$1,
              ),
            ],
          ),
          if (hint.actions.isNotEmpty) ...[
            const SizedBox(height: 14),
            for (var index = 0; index < hint.actions.length; index++) ...[
              _HintStep(index: index + 1, text: hint.actions[index]),
              if (index != hint.actions.length - 1) const SizedBox(height: 10),
            ],
          ],
        ],
      ),
    );
  }
}

class _LogsCard extends StatefulWidget {
  const _LogsCard({required this.logs});

  final List<String> logs;

  @override
  State<_LogsCard> createState() => _LogsCardState();
}

class _LogsCardState extends State<_LogsCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final visibleCount = _expanded ? widget.logs.length : 3;
    final visibleLogs = widget.logs.take(visibleCount).toList();
    final canExpand = widget.logs.length > 3;

    return _SectionCard(
      title: '最近日志',
      subtitle: _expanded ? '已展开全部日志，便于排查细节。' : '默认只展示最近 3 条日志，需要时再展开查看全部内容。',
      child: widget.logs.isEmpty
          ? Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest.withValues(
                  alpha: 0.4,
                ),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Text(
                '暂时没有原生日志。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            )
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF7F1F0),
                    borderRadius: BorderRadius.circular(16),
                    border: Border.all(
                      color: theme.colorScheme.outlineVariant.withValues(
                        alpha: 0.7,
                      ),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (
                        var index = 0;
                        index < visibleLogs.length;
                        index++
                      ) ...[
                        Text(
                          visibleLogs[index],
                          style: theme.textTheme.bodySmall?.copyWith(
                            height: 1.5,
                            color: theme.colorScheme.onSurface,
                          ),
                        ),
                        if (index != visibleLogs.length - 1)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 10),
                            child: Divider(
                              height: 1,
                              color: theme.colorScheme.outlineVariant
                                  .withValues(alpha: 0.5),
                            ),
                          ),
                      ],
                    ],
                  ),
                ),
                if (canExpand) ...[
                  const SizedBox(height: 10),
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton.icon(
                      onPressed: () {
                        setState(() {
                          _expanded = !_expanded;
                        });
                      },
                      icon: Icon(
                        _expanded ? Icons.unfold_less : Icons.unfold_more,
                      ),
                      label: Text(
                        _expanded ? '收起日志' : '展开全部 ${widget.logs.length} 条日志',
                      ),
                    ),
                  ),
                ],
              ],
            ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({
    required this.title,
    required this.subtitle,
    required this.child,
  });

  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              subtitle,
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
            const SizedBox(height: 16),
            child,
          ],
        ),
      ),
    );
  }
}

class _SwitchTile extends StatelessWidget {
  const _SwitchTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.value,
    required this.enabled,
    required this.onChanged,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final bool value;
  final bool enabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = value
        ? theme.colorScheme.primary
        : theme.colorScheme.onSurfaceVariant;

    return AnimatedOpacity(
      duration: const Duration(milliseconds: 180),
      opacity: enabled ? 1 : 0.6,
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: theme.colorScheme.surfaceContainerHighest.withValues(
            alpha: 0.32,
          ),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: accent.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(icon, color: accent),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    subtitle,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                      height: 1.45,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 12),
            Switch(value: value, onChanged: enabled ? onChanged : null),
          ],
        ),
      ),
    );
  }
}

class _InfoPill extends StatelessWidget {
  const _InfoPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: theme.colorScheme.primaryContainer.withValues(alpha: 0.42),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: theme.textTheme.labelMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: theme.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(
          alpha: 0.55,
        ),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: theme.colorScheme.primary),
          const SizedBox(width: 6),
          Text(
            label,
            style: theme.textTheme.labelLarge?.copyWith(
              color: theme.colorScheme.onSurface,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({
    required this.label,
    required this.backgroundColor,
    required this.foregroundColor,
  });

  final String label;
  final Color backgroundColor;
  final Color foregroundColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
          color: foregroundColor,
          fontWeight: FontWeight.w800,
        ),
      ),
    );
  }
}

class _HintStep extends StatelessWidget {
  const _HintStep({required this.index, required this.text});

  final int index;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 24,
          height: 24,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: theme.colorScheme.primary.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Text(
            '$index',
            style: theme.textTheme.labelMedium?.copyWith(
              color: theme.colorScheme.primary,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            text,
            style: theme.textTheme.bodyMedium?.copyWith(
              height: 1.5,
              color: theme.colorScheme.onSurface,
            ),
          ),
        ),
      ],
    );
  }
}

(String, Color, Color) _runStatusMeta(ColorScheme colorScheme, String status) {
  return switch (status) {
    'running' ||
    'starting' => ('运行中', colorScheme.primary, colorScheme.onPrimary),
    'stopping' => ('停止中', const Color(0xFFFFE6B3), const Color(0xFF7A4B00)),
    'failed' => (
      '失败',
      colorScheme.errorContainer,
      colorScheme.onErrorContainer,
    ),
    'completed' => ('已完成', const Color(0xFFD8F3DC), const Color(0xFF1B5E20)),
    _ => (
      '待启动',
      colorScheme.surfaceContainerHighest,
      colorScheme.onSurfaceVariant,
    ),
  };
}
