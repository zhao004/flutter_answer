import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../services/agent_diagnostic_hint.dart';
import 'home_controller.dart';

class HomeView extends GetView<HomeController> {
  const HomeView({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('AI自动答题')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _AccessCard(controller: controller),
          const SizedBox(height: 16),
          _AgentConfigCard(controller: controller),
          Obx(() {
            final hint = resolveAgentDiagnosticHint(
              serviceStatus: controller.serviceStatus,
              agentConfig: controller.agentConfig,
              agentRunStatus: controller.agentRunStatus,
            );
            if (hint == null) {
              return const SizedBox.shrink();
            }
            return Column(
              children: [
                const SizedBox(height: 16),
                _AgentHintCard(hint: hint),
              ],
            );
          }),
          const SizedBox(height: 16),
          Obx(() => _LogsCard(logs: controller.logs.toList())),
        ],
      ),
    );
  }
}

class _AgentHintCard extends StatelessWidget {
  const _AgentHintCard({required this.hint});

  final AgentDiagnosticHint hint;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final (accentColor, iconData) = switch (hint.severity) {
      AgentDiagnosticSeverity.info => (colorScheme.primary, Icons.info_outline),
      AgentDiagnosticSeverity.warning => (
        const Color(0xFF9A6700),
        Icons.warning_amber_outlined,
      ),
      AgentDiagnosticSeverity.error => (colorScheme.error, Icons.error_outline),
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(iconData, color: accentColor),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    hint.title,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            Text(hint.summary),
            if (hint.actions.isNotEmpty) ...[
              const SizedBox(height: 10),
              ...List.generate(
                hint.actions.length,
                (index) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text('${index + 1}. ${hint.actions[index]}'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _AccessCard extends StatelessWidget {
  const _AccessCard({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final accessibilityEnabled = controller.serviceStatus['enabled'] == true;
      final isBusy = controller.isBusy.value;

      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('悬浮窗与无障碍', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 12),
              Container(
                decoration: BoxDecoration(
                  border: Border.all(
                    color: Theme.of(context).colorScheme.outlineVariant,
                  ),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  children: [
                    _ToggleListTile(
                      icon: Icons.layers_outlined,
                      title: '悬浮窗权限',
                      subtitle: controller.overlayPermissionGranted.value
                          ? '已授权，可直接显示悬浮窗'
                          : '开启后可显示悬浮窗',
                      value: controller.overlayPermissionGranted.value,
                      enabled: !isBusy,
                      onChanged: controller.toggleOverlayPermission,
                    ),
                    const Divider(height: 1),
                    _ToggleListTile(
                      icon: Icons.settings_accessibility,
                      title: '无障碍服务',
                      subtitle: accessibilityEnabled
                          ? '已开启，切换时会跳转系统设置'
                          : '开启后可读取页面并执行操作',
                      value: accessibilityEnabled,
                      enabled: !isBusy,
                      onChanged: controller.toggleAccessibilityService,
                    ),
                    const Divider(height: 1),
                    _ToggleListTile(
                      icon: Icons.picture_in_picture_alt_outlined,
                      title: '显示悬浮窗',
                      subtitle: controller.overlayActive.value
                          ? '悬浮窗显示中'
                          : '开启后显示控制面板',
                      value: controller.overlayActive.value,
                      enabled: !isBusy,
                      onChanged: controller.toggleOverlayVisibility,
                    ),
                    const Divider(height: 1),
                    _ToggleListTile(
                      icon: Icons.animation_outlined,
                      title: '拖动帧率节流',
                      subtitle: controller.overlayDragFrameThrottleEnabled.value
                          ? '已开启（16ms），低端机更稳'
                          : '已关闭，拖动响应更直接',
                      value: controller.overlayDragFrameThrottleEnabled.value,
                      enabled: !isBusy,
                      onChanged: controller.toggleOverlayDragFrameThrottle,
                    ),
                  ],
                ),
              ),
            ],
          ),
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

      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('代理配置', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 12),
              TextField(
                controller: controller.providerUrlController,
                keyboardType: TextInputType.url,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: '提供商 URL',
                  hintText: 'https://api.openai.com 或 https://host/v1',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller.apiKeyController,
                obscureText: true,
                decoration: InputDecoration(
                  border: const OutlineInputBorder(),
                  labelText: '提供商 API 密钥',
                  hintText: hasApiKey ? '已保存到本地，可直接覆盖' : 'sk-...',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller.modelController,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: '模型',
                  hintText: 'gpt-5-mini',
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller.taskPromptController,
                maxLines: 3,
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: '任务提示词',
                ),
              ),
              const SizedBox(height: 12),
              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                title: const Text('允许自动提交'),
                subtitle: const Text('关闭后，代理到提交页会停止，不会自动点击提交。'),
                value: controller.autoSubmit.value,
                onChanged: (value) => controller.autoSubmit.value = value,
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  FilledButton.icon(
                    onPressed: controller.saveAgentConfig,
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('保存配置'),
                  ),
                ],
              ),
            ],
          ),
        ),
      );
    });
  }
}

class _ToggleListTile extends StatelessWidget {
  const _ToggleListTile({
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
    return ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(subtitle),
      enabled: enabled,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      onTap: enabled ? () => onChanged(!value) : null,
      trailing: Switch.adaptive(
        value: value,
        onChanged: enabled ? onChanged : null,
      ),
    );
  }
}

class _LogsCard extends StatelessWidget {
  const _LogsCard({required this.logs});

  final List<String> logs;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('日志流', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            if (logs.isEmpty)
              Text('暂时没有原生日志。', style: Theme.of(context).textTheme.bodyMedium)
            else
              ...logs
                  .take(12)
                  .map(
                    (line) => Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text(line),
                    ),
                  ),
          ],
        ),
      ),
    );
  }
}
