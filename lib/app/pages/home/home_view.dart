import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../services/agent_diagnostic_hint.dart';
import 'home_controller.dart';

class HomeView extends GetView<HomeController> {
  const HomeView({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('代理控制台')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _AccessCard(controller: controller),
          const SizedBox(height: 16),
          _AgentConfigCard(controller: controller),
          const SizedBox(height: 16),
          _ConnectivityChatCard(controller: controller),
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
          Obx(() => _PayloadCard(payload: controller.lastPayload.value)),
          const SizedBox(height: 16),
          Obx(() => _LogsCard(logs: controller.logs.toList())),
        ],
      ),
    );
  }
}

class _ConnectivityChatCard extends StatelessWidget {
  const _ConnectivityChatCard({required this.controller});

  final HomeController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final messages = controller.connectivityMessages.toList();
      final isTesting = controller.isConnectivityTesting.value;
      final notice = controller.connectivityNotice.value;
      final lastOk = controller.connectivityLastOk.value;
      final latencyMs = controller.connectivityLatencyMs.value;
      final model = controller.agentConfig['model']?.toString() ?? '未设置模型';
      final statusLabel = switch ((isTesting, lastOk)) {
        (true, _) => '检测中',
        (false, true) => '最近一次成功',
        (false, false) => '最近一次失败',
        _ => '未检测',
      };
      final statusColor = switch ((isTesting, lastOk)) {
        (true, _) => Theme.of(context).colorScheme.primary,
        (false, true) => const Color(0xFF2E7D32),
        (false, false) => Theme.of(context).colorScheme.error,
        _ => Theme.of(context).colorScheme.outline,
      };

      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '模型对话检测',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  TextButton.icon(
                    onPressed: messages.isEmpty && !isTesting
                        ? null
                        : controller.clearConnectivityConversation,
                    icon: const Icon(Icons.refresh_outlined),
                    label: const Text('清空'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '发送前会自动同步当前提供商配置，适合检查接口地址、API 密钥和模型是否能正常对话。',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _InlineChip(label: statusLabel, color: statusColor),
                  _InlineChip(
                    label: latencyMs == null ? '耗时 --' : '耗时 ${latencyMs}ms',
                    color: Theme.of(context).colorScheme.secondary,
                  ),
                  _InlineChip(
                    label: '模型 $model',
                    color: const Color(0xFF0B5D5B),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                height: messages.isEmpty ? 148 : 280,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerLowest,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: Theme.of(context).colorScheme.outlineVariant,
                  ),
                ),
                child: messages.isEmpty
                    ? Center(
                        child: Text(
                          '先发一条消息试试，例如：请回复“模型连通正常”。',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      )
                    : ListView.separated(
                        itemCount: messages.length,
                        separatorBuilder: (context, index) =>
                            const SizedBox(height: 10),
                        itemBuilder: (context, index) {
                          return _ConversationBubble(message: messages[index]);
                        },
                      ),
              ),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Text(notice),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: controller.connectivityInputController,
                minLines: 1,
                maxLines: 4,
                textInputAction: TextInputAction.send,
                onSubmitted: (_) {
                  if (!isTesting) {
                    controller.sendConnectivityMessage();
                  }
                },
                decoration: const InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: '测试消息',
                  hintText: '输入一条消息，验证当前模型是否可正常回复',
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: isTesting || controller.isBusy.value
                          ? null
                          : controller.sendConnectivityMessage,
                      icon: isTesting
                          ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.send_outlined),
                      label: Text(isTesting ? '检测中...' : '发送检测消息'),
                    ),
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

class _InlineChip extends StatelessWidget {
  const _InlineChip({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
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

class _ConversationBubble extends StatelessWidget {
  const _ConversationBubble({required this.message});

  final ProbeConversationMessage message;

  @override
  Widget build(BuildContext context) {
    final isUser = message.isUser;
    final bubbleColor = message.failed
        ? Theme.of(context).colorScheme.errorContainer
        : isUser
        ? Theme.of(context).colorScheme.primaryContainer
        : Colors.white;
    final label = message.failed
        ? '未送达'
        : isUser
        ? '你'
        : '模型';

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 320),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: bubbleColor,
            borderRadius: BorderRadius.circular(16),
            border: message.failed
                ? Border.all(color: Theme.of(context).colorScheme.error)
                : null,
          ),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: message.failed
                        ? Theme.of(context).colorScheme.error
                        : null,
                  ),
                ),
                const SizedBox(height: 6),
                Text(message.content),
              ],
            ),
          ),
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
                  hintText: hasApiKey ? '仅保存在内存中' : 'sk-...',
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

class _PayloadCard extends StatelessWidget {
  const _PayloadCard({required this.payload});

  final String payload;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('最近载荷', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(12),
              ),
              child: SelectableText(
                payload,
                style: Theme.of(
                  context,
                ).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
              ),
            ),
          ],
        ),
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
