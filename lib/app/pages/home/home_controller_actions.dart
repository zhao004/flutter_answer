part of 'home_controller.dart';

extension HomeControllerActions on HomeController {
  Future<void> _bootstrap() async {
    await _restoreSavedConfig();
    await refreshAgentConfig();
    await refreshServiceStatus();
    await refreshAgentRunStatus();
    await syncOverlayState();
  }

  Future<void> _restoreSavedConfig() async {
    final savedConfig = await _configStore.load();
    if (savedConfig == null) {
      _syncAgentConfig(currentConfig.value);
      return;
    }

    final restoredConfig = savedConfig
        .copyWith(taskPrompt: const AgentConfig().taskPrompt)
        .sanitized();
    currentConfig.value = restoredConfig;
    _syncAgentConfig(restoredConfig);
  }

  Future<void> refreshServiceStatus() async {
    await _runAction('service_status', () async {
      final payload = await _executeTool('get_service_status');
      _setPayload(payload.toMap());
    });
  }

  Future<void> openAccessibilitySettings() async {
    await _launchSystemSettingsFlow(() async {
      await _dismissOverlayForSystemSettings();
      _appendLog('info', '正在打开无障碍设置页面。');
      final payload = await _platformGateway.openAccessibilitySettings();
      _setPayload(payload);
    }, busyFlag: accessibilityServiceBusy);
  }

  Future<void> toggleAccessibilityService(bool enabled) async {
    final current = serviceStatus.value.enabled;
    if (current == enabled) {
      return;
    }

    _appendLog('info', enabled ? '请在系统设置中启用无障碍服务。' : '请在系统设置中关闭无障碍服务。');
    await openAccessibilitySettings();
  }

  Future<void> fetchQuestionContext() async {
    await _runAction('question_context', () async {
      final payload = await _executeTool('get_question_context');
      _setPayload(payload.toMap());
    });
  }

  Future<void> scrollScreen({
    String direction = 'down',
    String amount = 'medium',
  }) async {
    await _runAction('scroll_screen', () async {
      final payload = await _executeTool(
        'scroll_screen',
        arguments: <String, dynamic>{'direction': direction, 'amount': amount},
      );
      _setPayload(payload.toMap());
    });
  }

  Future<void> pressGlobal({String action = 'back'}) async {
    await _runAction('press_global', () async {
      final payload = await _executeTool(
        'press_global',
        arguments: <String, dynamic>{'action': action},
      );
      _setPayload(payload.toMap());
    });
  }

  Future<void> refreshAgentConfig() async {
    _syncAgentConfig(currentConfig.value);
    _setPayload(currentConfig.value.toPublicMap());
  }

  Future<void> refreshAgentRunStatus() async {
    _applyRunState(agentRunState.value);
  }

  /// 从当前表单中的提供商读取可用模型，填充模型输入框的下拉选项。
  ///
  /// 提供商 URL 与 API 密钥都必须填写；获取失败时保留上一批可用模型，
  /// 用户仍可以手动输入模型名称。
  Future<void> fetchAvailableModels() async {
    if (modelFetchBusy.value) {
      return;
    }

    final providerUrl = form.providerUrlController.text.trim();
    final apiKey = form.apiKeyController.text.trim();
    if (providerUrl.isEmpty) {
      _appendLog('error', '请先填写提供商 URL。');
      return;
    }
    if (apiKey.isEmpty) {
      _appendLog('error', '请先填写提供商 API 密钥。');
      return;
    }

    modelFetchBusy.value = true;
    try {
      final models = await _modelCatalogGateway.fetchModels(
        providerUrl: providerUrl,
        apiKey: apiKey,
      );
      modelOptions.assignAll(models);
      if (models.isEmpty) {
        _appendLog('warn', '提供商未返回可用模型，可以继续手动填写模型名称。');
      } else {
        _appendLog('info', '已获取 ${models.length} 个模型，可点击模型输入框右侧箭头选择，也可继续手动输入。');
      }
    } on ModelCatalogException catch (error) {
      _appendLog('error', error.message);
    } catch (error) {
      _appendLog('error', '获取模型失败：$error');
    } finally {
      modelFetchBusy.value = false;
    }
  }

  /// 将下拉选择的模型写回输入框，保证选择与手动输入共用同一份配置。
  void selectModel(String model) {
    final normalized = model.trim();
    if (normalized.isEmpty) {
      return;
    }
    form.modelController.value = TextEditingValue(
      text: normalized,
      selection: TextSelection.collapsed(offset: normalized.length),
    );
  }

  Future<bool> saveAgentConfig({bool announce = true}) async {
    final nextConfig = form.buildConfig(baseConfig: currentConfig.value);
    if (nextConfig.providerUrl.isEmpty) {
      _appendLog('error', '请先填写提供商 URL。');
      return false;
    }

    currentConfig.value = nextConfig;
    await _configStore.save(nextConfig);
    _syncAgentConfig(nextConfig);
    _setPayload(nextConfig.toPublicMap());
    await _pushOverlayEvent('agent_config', nextConfig.toPublicMap());

    if (announce) {
      _appendLog('info', 'Agent 配置已保存到本地，包含提供商密钥。');
    }

    return true;
  }

  Future<void> startAgentRun() async {
    if (agentRunning) {
      return;
    }

    await refreshServiceStatus();
    final saved = await saveAgentConfig(announce: false);
    if (!saved) {
      _applyRunState(
        AgentRunState.idle().copyWith(
          running: false,
          status: 'failed',
          message: '请先填写提供商 URL。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    if (!currentConfig.value.hasApiKey) {
      _applyRunState(
        AgentRunState.idle().copyWith(
          running: false,
          status: 'failed',
          message: '请先填写并保存提供商密钥。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    unawaited(_runAgentLoop());
  }

  Future<void> _runAgentLoop() async {
    final starting = agentRunState.value.copyWith(
      running: true,
      status: 'starting',
      message: '正在准备 LangChain Agent 运行环境。',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _applyRunState(starting);

    final finalState = await _runtimeService.run(
      config: currentConfig.value,
      onStatus: _applyRunState,
      onLog: _appendLog,
      onToolResult: (NativeToolExecutionResult result) {
        _setPayload(result.toMap());
      },
    );

    _applyRunState(finalState);
  }

  Future<void> stopAgentRun() async {
    _runtimeService.requestStop();
    if (!agentRunning) {
      _applyRunState(
        agentRunState.value.copyWith(
          running: false,
          status: 'idle',
          message: 'Agent 当前未运行。',
          updatedAt: DateTime.now().millisecondsSinceEpoch,
        ),
      );
      return;
    }

    _applyRunState(
      agentRunState.value.copyWith(
        running: true,
        status: 'stopping',
        message: '已收到停止指令，正在结束当前流程。',
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  Future<void> _runAction(
    String gate,
    Future<void> Function() action, {
    RxBool? busyFlag,
  }) async {
    if (_busyGates.contains(gate) || (busyFlag?.value ?? false)) {
      return;
    }
    _busyGates.add(gate);
    busyFlag?.value = true;
    try {
      await action();
    } catch (error) {
      _setPayload(<String, dynamic>{'ok': false, 'message': error.toString()});
      _appendLog('error', error.toString());
    } finally {
      busyFlag?.value = false;
      _busyGates.remove(gate);
    }
  }

  Future<void> _launchSystemSettingsFlow(
    Future<void> Function() action, {
    RxBool? busyFlag,
  }) async {
    if (_systemSettingsFlowActive || (busyFlag?.value ?? false)) {
      return;
    }

    _systemSettingsFlowActive = true;
    busyFlag?.value = true;
    try {
      await action();
    } catch (error) {
      _setPayload(<String, dynamic>{'ok': false, 'message': error.toString()});
      _appendLog('error', error.toString());
    } finally {
      _systemSettingsFlowActive = false;
      busyFlag?.value = false;
    }
  }

  void _resetTransientBusyState() {
    _systemSettingsFlowActive = false;
    _busyGates.clear();
    overlayPermissionBusy.value = false;
    accessibilityServiceBusy.value = false;
    overlayVisibilityBusy.value = false;
  }

  void _syncAgentConfig(AgentConfig config) {
    currentConfig.value = config;
    form.syncFromConfig(config);
  }

  Future<void> _refreshSystemState() async {
    await refreshServiceStatus();
    await syncOverlayState();
  }
}
