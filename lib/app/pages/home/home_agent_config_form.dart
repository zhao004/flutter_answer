import 'package:flutter/material.dart';

import '../../agent/domain/models/agent_config.dart';

/// 将首页表单控制器独立出来，避免页面控制器同时维护业务状态与输入组件生命周期。
class HomeAgentConfigForm {
  HomeAgentConfigForm();

  final providerUrlController = TextEditingController();
  final apiKeyController = TextEditingController();
  final modelController = TextEditingController(text: 'gpt-5-mini');
  final maxIterationsController = TextEditingController(
    text: AgentConfig.defaultMaxIterations.toString(),
  );
  final maxExecutionMinutesController = TextEditingController(
    text: AgentConfig.defaultMaxExecutionMinutes.toString(),
  );
  final maxRepeatedSameToolCallController = TextEditingController(
    text: AgentConfig.defaultMaxRepeatedSameToolCall.toString(),
  );
  final maxNoProgressActionStreakController = TextEditingController(
    text: AgentConfig.defaultMaxNoProgressActionStreak.toString(),
  );

  void syncFromConfig(AgentConfig config) {
    providerUrlController.text = config.providerUrl;
    apiKeyController.text = config.apiKey;
    modelController.text = config.model;
    maxIterationsController.text = config.maxIterations.toString();
    maxExecutionMinutesController.text = config.maxExecutionMinutes.toString();
    maxRepeatedSameToolCallController.text = config.maxRepeatedSameToolCall
        .toString();
    maxNoProgressActionStreakController.text = config.maxNoProgressActionStreak
        .toString();
  }

  AgentConfig buildConfig({required AgentConfig baseConfig}) {
    return AgentConfig(
      providerUrl: providerUrlController.text.trim(),
      apiKey: apiKeyController.text.trim(),
      model: modelController.text.trim().isEmpty
          ? 'gpt-5-mini'
          : modelController.text.trim(),
      taskPrompt: baseConfig.taskPrompt,
      maxIterations: _readInt(
        controller: maxIterationsController,
        fallback: AgentConfig.defaultMaxIterations,
      ),
      maxExecutionMinutes: _readInt(
        controller: maxExecutionMinutesController,
        fallback: AgentConfig.defaultMaxExecutionMinutes,
      ),
      maxRepeatedSameToolCall: _readInt(
        controller: maxRepeatedSameToolCallController,
        fallback: AgentConfig.defaultMaxRepeatedSameToolCall,
      ),
      maxNoProgressActionStreak: _readInt(
        controller: maxNoProgressActionStreakController,
        fallback: AgentConfig.defaultMaxNoProgressActionStreak,
      ),
    ).sanitized();
  }

  void dispose() {
    providerUrlController.dispose();
    apiKeyController.dispose();
    modelController.dispose();
    maxIterationsController.dispose();
    maxExecutionMinutesController.dispose();
    maxRepeatedSameToolCallController.dispose();
    maxNoProgressActionStreakController.dispose();
  }

  int _readInt({
    required TextEditingController controller,
    required int fallback,
  }) {
    return int.tryParse(controller.text.trim()) ?? fallback;
  }
}
