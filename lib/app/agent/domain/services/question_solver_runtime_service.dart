import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import '../../data/agent_platform_gateway.dart';
import '../models/agent_config.dart';
import '../models/agent_run_state.dart';
import '../models/native_tool_execution.dart';
import '../models/question_answer_plan.dart';
import 'agent_runtime_service.dart';
import 'langchain_agent_runtime_service.dart';
import 'question_answer_model.dart';
import 'submit_page_stop_guard.dart';

/// 结构化答题主运行时。
///
/// 每题最多调用一次模型，答案由原生 `apply_answer_plan` 批量执行；
/// 只有遇到未知页面时才回退到通用 LangChain Agent。
class QuestionSolverRuntimeService implements AgentRuntimeService {
  QuestionSolverRuntimeService({
    required AgentPlatformGateway platformGateway,
    QuestionAnswerModel? answerModel,
    AgentRuntimeService? fallbackRuntime,
  }) : _platformGateway = platformGateway,
       _answerModel = answerModel ?? ChatOpenAiQuestionAnswerModel(),
       _fallbackRuntime =
           fallbackRuntime ??
           LangChainAgentRuntimeService(platformGateway: platformGateway);

  static const String _manualReviewMessage =
      '当前题目未通过结构化校验，Agent 已停止，请人工确认题目与答案。';
  static const String _nextActionStopMessage =
      '当前题答案已应用，但无法安全推进到下一题，Agent 已停止，请人工确认。';

  final AgentPlatformGateway _platformGateway;
  final QuestionAnswerModel _answerModel;
  final AgentRuntimeService _fallbackRuntime;

  bool _stopRequested = false;
  bool _visualUnavailable = false;
  int _toolStep = 0;
  int _modelStep = 0;
  String? _lastTool;
  String? _lastModelText;
  final Set<String> _fallbackAttemptKeys = <String>{};

  @override
  void requestStop() {
    _stopRequested = true;
    _fallbackRuntime.requestStop();
  }

  @override
  Future<AgentRunState> run({
    required AgentConfig config,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    final sanitizedConfig = config.sanitized();
    _stopRequested = false;
    _visualUnavailable = false;
    _toolStep = 0;
    _modelStep = 0;
    _lastTool = null;
    _lastModelText = null;
    _fallbackAttemptKeys.clear();

    var latestState = AgentRunState.idle().copyWith(
      running: true,
      status: 'starting',
      message: '结构化答题运行时正在启动。',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    onStatus(latestState);

    try {
      _validateConfig(sanitizedConfig);
      final tools = await _platformGateway.listTools();
      final toolNames = tools.map((tool) => tool.name).toSet();
      if (!toolNames.contains('get_question_context')) {
        throw StateError('Android 原生侧缺少 get_question_context 工具。');
      }
      if (!toolNames.contains('apply_answer_plan')) {
        throw StateError('Android 原生侧缺少 apply_answer_plan 工具，请升级应用。');
      }
      onLog('info', '结构化答题运行时已加载 ${tools.length} 个原生工具。');
      if (toolNames.contains('reset_answer_cache')) {
        final resetResult = await _executeTool(
          toolName: 'reset_answer_cache',
          arguments: const <String, dynamic>{},
          onStatus: onStatus,
          onLog: onLog,
          onToolResult: onToolResult,
        );
        if (!resetResult.ok) {
          onLog('warn', '会话答案缓存清理失败，将继续运行但不复用旧缓存。');
        }
      }
      final executionDeadline = DateTime.now().add(
        Duration(minutes: sanitizedConfig.sanitizedMaxExecutionMinutes),
      );
      final maxSteps = sanitizedConfig.sanitizedMaxIterations;

      while (!_stopRequested) {
        if (_toolStep + _modelStep >= maxSteps) {
          return _finishStopped('已达到最大执行轮数，Agent 已自动停止。', onStatus, onLog);
        }
        if (DateTime.now().isAfter(executionDeadline)) {
          return _finishStopped('已达到最大运行时间，Agent 已自动停止。', onStatus, onLog);
        }
        final contextResult = await _executeTool(
          toolName: 'get_question_context',
          arguments: const <String, dynamic>{},
          onStatus: onStatus,
          onLog: onLog,
          onToolResult: onToolResult,
        );
        if (!contextResult.ok) {
          throw StateError(contextResult.message ?? '无法读取当前题目上下文。');
        }
        final context = QuestionContextSnapshot.fromToolResult(
          contextResult.result,
        );
        latestState = _thinkingState(
          step: _toolStep,
          message: '已识别页面：${context.pageStage}。',
        );
        onStatus(latestState);

        final submitStopMessage =
            resolveSubmitPageStopMessage(contextResult) ??
            _resolveSubmitStopMessage(context);
        if (submitStopMessage != null) {
          return _finishStopped(submitStopMessage, onStatus, onLog);
        }
        if (context.pageStage == 'resume_entry') {
          final resumeResult = await _clickNextAction(
            context: context,
            onStatus: onStatus,
            onLog: onLog,
            onToolResult: onToolResult,
          );
          if (!resumeResult.ok) {
            throw StateError(resumeResult.message ?? '继续答题入口推进失败。');
          }
          continue;
        }
        if (context.pageStage == 'question') {
          final questionState = await _solveQuestion(
            config: sanitizedConfig,
            context: context,
            onStatus: onStatus,
            onLog: onLog,
            onToolResult: onToolResult,
          );
          if (questionState != null) {
            onStatus(questionState);
            return questionState;
          }
          continue;
        }

        await _runUnknownPageFallback(
          config: sanitizedConfig,
          context: context,
          onStatus: onStatus,
          onLog: onLog,
          onToolResult: onToolResult,
        );
        if (_stopRequested) {
          return _finishStopped('已按操作要求停止。', onStatus, onLog);
        }
      }

      return _finishStopped('已按操作要求停止。', onStatus, onLog);
    } on _SolverStopRequested {
      return _finishStopped('已按操作要求停止。', onStatus, onLog);
    } on _SolverManualReviewException catch (error) {
      return _finishStopped(error.message, onStatus, onLog);
    } catch (error) {
      final message = _normalizeError(error);
      final failed = AgentRunState(
        running: false,
        status: 'failed',
        step: _toolStep,
        message: message,
        lastTool: _lastTool,
        lastModelText: _lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      onLog('error', message);
      onStatus(failed);
      return failed;
    }
  }

  Future<AgentRunState?> _solveQuestion({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    final cachedPlan = context.cachedAnswerPlan;
    if (cachedPlan != null && cachedPlan.isUsable) {
      onLog('info', '题目 ${context.fingerprint} 命中会话缓存，跳过模型调用。');
      final cachedState = await _applyPlan(
        plan: cachedPlan,
        context: context,
        fromCache: true,
        onStatus: onStatus,
        onLog: onLog,
        onToolResult: onToolResult,
      );
      if (cachedState != null) {
        return cachedState;
      }
      return null;
    }

    Uint8List? imageBytes;
    String? imageMimeType;
    if (context.needsVisual && !_visualUnavailable) {
      final imageResult = await _executeTool(
        toolName: 'capture_question_image',
        arguments: const <String, dynamic>{},
        onStatus: onStatus,
        onLog: onLog,
        onToolResult: onToolResult,
      );
      if (imageResult.ok) {
        imageMimeType =
            imageResult.result['mimeType']?.toString() ?? 'image/jpeg';
        final encodedImage = imageResult.result['imageBase64']?.toString();
        if (encodedImage != null && encodedImage.isNotEmpty) {
          try {
            imageBytes = base64Decode(encodedImage);
            onLog('info', '已获取题目截图，将使用多模态模型进行补充识别。');
          } on FormatException {
            imageBytes = null;
            onLog('warn', '题目截图编码无效，已降级为文本识别。');
          }
        }
      } else {
        _visualUnavailable = true;
        onLog('warn', '视觉补充不可用：${imageResult.message ?? '未知原因'}，后续继续使用文本识别。');
      }
    }

    final plan = await _requestAnswerPlan(
      config: config,
      context: context,
      imageBytes: imageBytes,
      imageMimeType: imageMimeType,
      onLog: onLog,
    );
    _lastModelText = jsonEncode(plan.toNativeArguments(advance: true));
    return _applyPlan(
      plan: plan,
      context: context,
      fromCache: false,
      onStatus: onStatus,
      onLog: onLog,
      onToolResult: onToolResult,
    );
  }

  Future<QuestionAnswerPlanDraft> _requestAnswerPlan({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    required Uint8List? imageBytes,
    required String? imageMimeType,
    required AgentLogCallback onLog,
  }) async {
    String? validationError;
    var effectiveImageBytes = imageBytes;
    var effectiveImageMimeType = imageMimeType;
    for (var attempt = 0; attempt < 2; attempt += 1) {
      try {
        _modelStep += 1;
        final modelStartedAt = DateTime.now().millisecondsSinceEpoch;
        final plan = await _answerModel.solve(
          config: config,
          context: context,
          imageBytes: effectiveImageBytes,
          imageMimeType: effectiveImageMimeType,
          validationError: validationError,
        );
        final planError = _validatePlan(plan, context);
        if (plan.needsManualReview) {
          // 模型理由与结构校验错误可能同时存在，两者都保留，避免丢失可诊断信息。
          final details = <String>[
            if (plan.manualReviewReason.isNotEmpty) plan.manualReviewReason,
            ?planError,
          ];
          validationError =
              '模型要求人工确认：'
              '${details.isEmpty ? '答案置信度不足或题目不完整' : details.join('；')}'
              '（置信度 ${plan.confidence.toStringAsFixed(2)}）';
        } else if (!plan.confidence.isFinite ||
            plan.confidence < 0.6 ||
            plan.confidence > 1.0) {
          validationError =
              '模型置信度 ${plan.confidence.toStringAsFixed(2)} 不在 0.60 到 1.00 范围内。';
        } else if (planError != null) {
          validationError = planError;
        } else {
          final modelDurationMs =
              DateTime.now().millisecondsSinceEpoch - modelStartedAt;
          onLog(
            'info',
            '结构化答案计划已生成，置信度 ${plan.confidence.toStringAsFixed(2)}，模型耗时 ${modelDurationMs}ms。',
          );
          return plan;
        }
      } catch (error) {
        if (effectiveImageBytes != null && _isImageInputUnsupported(error)) {
          _visualUnavailable = true;
          effectiveImageBytes = null;
          effectiveImageMimeType = null;
          validationError = '当前模型不支持图片输入，已降级为文本识别。';
          onLog('warn', '当前模型拒绝图片输入，本次运行后续改为纯文本识别。');
        } else {
          validationError = '模型调用失败：${_normalizeError(error)}';
        }
      }
      if (attempt == 0) {
        onLog('warn', '答案计划未通过校验，发起一次修复请求：$validationError');
      }
    }
    throw _SolverManualReviewException(
      validationError == null
          ? _manualReviewMessage
          : '$_manualReviewMessage 原因：$validationError',
    );
  }

  Future<AgentRunState?> _applyPlan({
    required QuestionAnswerPlanDraft plan,
    required QuestionContextSnapshot context,
    required bool fromCache,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    final result = await _executeTool(
      toolName: 'apply_answer_plan',
      arguments: plan.toNativeArguments(advance: true),
      onStatus: onStatus,
      onLog: onLog,
      onToolResult: onToolResult,
    );
    if (!result.ok) {
      final code = result.result['code']?.toString() ?? '';
      onLog('warn', '答案计划应用失败：${result.message ?? code}');
      if (fromCache &&
          (code == 'context_changed' || code == 'page_stage_protected')) {
        return null;
      }
      throw _SolverManualReviewException(
        '${result.message ?? _manualReviewMessage}（代码：$code）',
      );
    }

    final resultPayload = result.result;
    final manualReviewRequired = resultPayload['manualReviewRequired'] == true;
    final advanced = resultPayload['advanced'] == true;
    final answerApplied = resultPayload['answerApplied'] == true;
    if (manualReviewRequired) {
      throw const _SolverManualReviewException(_manualReviewMessage);
    }
    if (!advanced) {
      onLog('warn', answerApplied ? _nextActionStopMessage : '答案计划没有产生可验证的推进。');
      throw const _SolverManualReviewException(_nextActionStopMessage);
    }
    onLog('info', '答案已应用并推进到下一题。');
    return null;
  }

  Future<NativeToolExecutionResult> _clickNextAction({
    required QuestionContextSnapshot context,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    final nextAction = context.actions
        .where((action) => action.kind == 'next')
        .cast<QuestionActionSnapshot?>()
        .firstWhere((action) => action != null, orElse: () => null);
    if (nextAction == null) {
      throw const _SolverManualReviewException('继续答题入口缺少可用的下一题动作。');
    }
    return _executeTool(
      toolName: 'click_element',
      arguments: <String, dynamic>{'elementId': nextAction.id},
      onStatus: onStatus,
      onLog: onLog,
      onToolResult: onToolResult,
    );
  }

  Future<void> _runUnknownPageFallback({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    final fallbackKey = '${context.fingerprint}|${context.contextSignature}';
    if (!_fallbackAttemptKeys.add(fallbackKey)) {
      throw const _SolverManualReviewException(
        '当前页面无法识别为题目页，通用回退也未进入题目流程，请人工检查页面。',
      );
    }

    final fallbackConfig = config.copyWith(
      taskPrompt:
          '${config.taskPrompt}\n'
          '当前页面不是可识别的题目页。只处理当前非题目页面，优先点击继续答题、开始答题、进入答题或类似入口；'
          '一旦 pageStage 变为 question、resume_entry 或 submit_*，立即停止并返回。不要回答题目，不要点击提交或确认提交。',
      maxExecutionMinutes: math.min(config.sanitizedMaxExecutionMinutes, 3),
    );
    onLog('warn', '当前页面结构未知，启动一次有界的通用 Agent 回退。');
    final fallbackState = await _fallbackRuntime.run(
      config: fallbackConfig,
      onStatus: (state) {
        onStatus(state.copyWith(message: '通用回退：${state.message}'));
      },
      onLog: onLog,
      onToolResult: (result) {
        onToolResult(_sanitizeToolResultForUi(result.tool, result));
        final stage = _extractPageStage(result);
        if (_isKnownPageStage(stage)) {
          _fallbackRuntime.requestStop();
        }
      },
    );
    if (_stopRequested) {
      return;
    }
    if (fallbackState.status == 'failed') {
      throw StateError('通用回退失败：${fallbackState.message}');
    }
    onLog('info', '通用回退已结束，重新读取页面状态。');
  }

  Future<NativeToolExecutionResult> _executeTool({
    required String toolName,
    required Map<String, dynamic> arguments,
    required AgentStatusCallback onStatus,
    required AgentLogCallback onLog,
    required AgentToolResultCallback onToolResult,
  }) async {
    if (_stopRequested) {
      throw const _SolverStopRequested();
    }
    _toolStep += 1;
    _lastTool = toolName;
    onStatus(
      AgentRunState(
        running: true,
        status: 'acting',
        step: _toolStep,
        message: '正在执行工具：$toolName',
        lastTool: toolName,
        lastModelText: _lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    final result = await _platformGateway.executeTool(
      NativeToolExecutionRequest(toolName: toolName, arguments: arguments),
    );
    onToolResult(_sanitizeToolResultForUi(toolName, result));
    onStatus(
      AgentRunState(
        running: true,
        status: 'thinking',
        step: _toolStep,
        message: result.message?.isNotEmpty == true
            ? result.message!
            : '工具 $toolName 已执行完成。',
        lastTool: toolName,
        lastModelText: _lastModelText,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    return result;
  }

  NativeToolExecutionResult _sanitizeToolResultForUi(
    String toolName,
    NativeToolExecutionResult result,
  ) {
    if (toolName != 'capture_question_image') {
      return result;
    }
    final sanitized = Map<String, dynamic>.from(result.result)
      ..remove('imageBase64');
    return NativeToolExecutionResult(
      requestId: result.requestId,
      tool: result.tool,
      ok: result.ok,
      message: result.message,
      result: sanitized,
      startedAt: result.startedAt,
      finishedAt: result.finishedAt,
      durationMs: result.durationMs,
    );
  }

  String? _validatePlan(
    QuestionAnswerPlanDraft plan,
    QuestionContextSnapshot context,
  ) {
    if (plan.fingerprint != context.fingerprint) {
      return '答案指纹与当前题目不一致。';
    }
    final labels = context.options
        .map((option) => _normalizeOptionLabel(option.label))
        .toList();
    if (labels.toSet().length != labels.length) {
      return '当前题目存在重复选项标签，无法可靠映射答案。';
    }
    final normalizedPlanLabels = plan.selectedOptionLabels
        .map(_normalizeOptionLabel)
        .where((label) => label.isNotEmpty)
        .toList();
    if (normalizedPlanLabels.toSet().length != normalizedPlanLabels.length) {
      return '答案中包含重复选项标签。';
    }
    final unknownLabels = normalizedPlanLabels
        .where((label) => !labels.contains(label))
        .toList();
    if (unknownLabels.isNotEmpty) {
      return '答案包含不存在的选项标签：${unknownLabels.join('、')}。';
    }
    if (context.options.isNotEmpty && normalizedPlanLabels.isEmpty) {
      return '选择题答案没有包含任何选项标签。';
    }
    if (context.options.isEmpty && normalizedPlanLabels.isNotEmpty) {
      return '非选择题答案不应包含选项标签。';
    }

    final inputOrders = context.inputFields
        .map((input) => input.order)
        .whereType<int>()
        .toList();
    if (context.inputFields.isNotEmpty &&
        inputOrders.length != context.inputFields.length) {
      return '题目输入框缺少稳定顺序，无法映射填空答案。';
    }
    final answerOrders = plan.blankAnswers
        .map((answer) => answer.order)
        .toList();
    if (answerOrders.toSet().length != answerOrders.length) {
      return '填空答案存在重复序号。';
    }
    if (context.inputFields.isNotEmpty) {
      if (answerOrders.toSet().difference(inputOrders.toSet()).isNotEmpty ||
          inputOrders.toSet().difference(answerOrders.toSet()).isNotEmpty) {
        return '填空答案序号与输入框顺序不一致。';
      }
      for (final answer in plan.blankAnswers) {
        if (answer.text.trim().isEmpty || answer.text.length > 2000) {
          return '填空答案为空或长度超过 2000 字符。';
        }
      }
    } else if (plan.blankAnswers.isNotEmpty) {
      return '当前题目没有输入框，但答案包含填空内容。';
    }
    return null;
  }

  bool _isImageInputUnsupported(Object error) {
    final message = _normalizeError(error).toLowerCase();
    final mentionsImage =
        message.contains('image') ||
        message.contains('vision') ||
        message.contains('multimodal');
    final mentionsUnsupported =
        message.contains('unsupported') ||
        message.contains('not support') ||
        message.contains('invalid') ||
        message.contains('400');
    return mentionsImage && mentionsUnsupported;
  }

  String _normalizeOptionLabel(String value) {
    final normalized = value.trim();
    if (normalized.length == 1 && RegExp(r'[A-Za-z]').hasMatch(normalized)) {
      return normalized.toUpperCase();
    }
    return normalized;
  }

  String? _resolveSubmitStopMessage(QuestionContextSnapshot context) {
    if (context.pageStage == 'submit_warning') {
      return unansweredWarningStopMessage;
    }
    if (context.pageStage == 'submit_ready' ||
        context.pageStage == 'submit_dialog') {
      return submitPageStopMessage;
    }
    return null;
  }

  String _extractPageStage(NativeToolExecutionResult result) {
    final context = result.result['context'];
    if (context is Map) {
      return context['pageStage']?.toString() ?? '';
    }
    return result.result['finalPageStage']?.toString() ?? '';
  }

  bool _isKnownPageStage(String stage) {
    return stage == 'question' ||
        stage == 'resume_entry' ||
        stage == 'submit_ready' ||
        stage == 'submit_dialog' ||
        stage == 'submit_warning';
  }

  void _validateConfig(AgentConfig config) {
    if (config.providerUrl.trim().isEmpty) {
      throw StateError('请先填写提供商 URL。');
    }
    if (config.apiKey.trim().isEmpty) {
      throw StateError('请先填写提供商密钥。');
    }
  }

  AgentRunState _thinkingState({required int step, required String message}) {
    return AgentRunState(
      running: true,
      status: 'thinking',
      step: step,
      message: message,
      lastTool: _lastTool,
      lastModelText: _lastModelText,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
  }

  AgentRunState _finishStopped(
    String message,
    AgentStatusCallback onStatus,
    AgentLogCallback onLog,
  ) {
    final stopped = AgentRunState(
      running: false,
      status: 'stopped',
      step: _toolStep,
      message: message,
      lastTool: _lastTool,
      lastModelText: _lastModelText,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    onLog('warn', message);
    onStatus(stopped);
    return stopped;
  }

  String _normalizeError(Object error) {
    final raw = error.toString().trim();
    if (raw.isEmpty) {
      return '结构化答题运行时发生未知错误。';
    }
    return raw.replaceFirst(RegExp(r'^(Bad state|StateError):\s*'), '').trim();
  }
}

class _SolverStopRequested implements Exception {
  const _SolverStopRequested();
}

class _SolverManualReviewException implements Exception {
  const _SolverManualReviewException(this.message);

  final String message;
}
