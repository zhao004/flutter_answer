import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_answer/app/agent/data/agent_platform_gateway.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_config.dart';
import 'package:flutter_answer/app/agent/domain/models/agent_run_state.dart';
import 'package:flutter_answer/app/agent/domain/models/native_platform_event.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_definition.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/models/question_answer_plan.dart';
import 'package:flutter_answer/app/agent/domain/services/agent_runtime_service.dart';
import 'package:flutter_answer/app/agent/domain/services/question_answer_model.dart';
import 'package:flutter_answer/app/agent/domain/services/question_solver_runtime_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const config = AgentConfig(
    providerUrl: 'https://api.openai.com',
    apiKey: 'sk-test',
    model: 'gpt-5-mini',
  );

  test('每题只调用一次模型并批量应用答案', () async {
    final model = _FakeQuestionAnswerModel(
      plans: <QuestionAnswerPlanDraft>[
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_question',
          selectedOptionLabels: <String>['A'],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.96,
          needsManualReview: false,
        ),
      ],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_question',
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
            _option(id: 'option_b', label: 'B', text: '错误'),
          ],
        ),
        _submitContext(),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );
    final logs = <String>[];
    final states = <AgentRunState>[];

    final result = await runtime.run(
      config: config,
      onStatus: states.add,
      onLog: (String _, String message) => logs.add(message),
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(model.callCount, 1);
    expect(
      gateway.toolNames.where((name) => name == 'apply_answer_plan'),
      hasLength(1),
    );
    expect(logs.any((line) => line.contains('结构化答案计划已生成')), isTrue);
  });

  test('会话内命中缓存时跳过模型调用', () async {
    final model = _FakeQuestionAnswerModel(
      plans: const <QuestionAnswerPlanDraft>[],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_cached',
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
          ],
          cachedAnswerPlan: const <String, dynamic>{
            'fingerprint': 'qf_cached',
            'selectedOptionLabels': <String>['A'],
            'blankAnswers': <Map<String, dynamic>>[],
            'confidence': 0.95,
            'needsManualReview': false,
          },
        ),
        _submitContext(),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(model.callCount, 0);
    expect(
      gateway.toolNames.where((name) => name == 'apply_answer_plan'),
      hasLength(1),
    );
  });

  test('提交页直接停止且不调用模型或答案应用工具', () async {
    final model = _FakeQuestionAnswerModel(
      plans: const <QuestionAnswerPlanDraft>[],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[_submitContext()],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(model.callCount, 0);
    expect(
      gateway.toolNames.where((name) => name == 'apply_answer_plan'),
      isEmpty,
    );
  });

  test('未知页面只回退一次并避免无限循环', () async {
    final model = _FakeQuestionAnswerModel(
      plans: const <QuestionAnswerPlanDraft>[],
    );
    final fallback = _FakeFallbackRuntime();
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[_unknownContext()],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
      fallbackRuntime: fallback,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(fallback.runCount, 1);
    expect(model.callCount, 0);
  });

  test('需要视觉补充时把截图字节传给模型', () async {
    final model = _FakeQuestionAnswerModel(
      plans: <QuestionAnswerPlanDraft>[
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_visual',
          selectedOptionLabels: <String>['A'],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.9,
          needsManualReview: false,
        ),
      ],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_visual',
          needsVisual: true,
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
          ],
        ),
        _submitContext(),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(gateway.toolNames, contains('capture_question_image'));
    expect(model.lastImageBytes, isNotNull);
  });

  test('答案校验失败时最多修复一次', () async {
    final model = _FakeQuestionAnswerModel(
      plans: <QuestionAnswerPlanDraft>[
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_question',
          selectedOptionLabels: <String>['Z'],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.9,
          needsManualReview: false,
        ),
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_question',
          selectedOptionLabels: <String>['A'],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.9,
          needsManualReview: false,
        ),
      ],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_question',
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
          ],
        ),
        _submitContext(),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(model.callCount, 2);
    expect(
      gateway.toolNames.where((name) => name == 'apply_answer_plan'),
      hasLength(1),
    );
  });

  test('模型要求人工确认时停止消息保留模型给出的具体原因', () async {
    final model = _FakeQuestionAnswerModel(
      plans: <QuestionAnswerPlanDraft>[
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_question',
          selectedOptionLabels: <String>[],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.42,
          needsManualReview: true,
          manualReviewReason: '选项正文缺失，无法判断正确选项',
        ),
      ],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_question',
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
          ],
        ),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(result.message, contains('模型要求人工确认：'));
    expect(result.message, contains('选项正文缺失，无法判断正确选项'));
    expect(result.message, contains('选择题答案没有包含任何选项标签'));
    expect(result.message, contains('（置信度 0.42）'));
    expect(model.callCount, 2);
  });

  test('模型未给出人工确认原因时回退到结构校验错误', () async {
    final model = _FakeQuestionAnswerModel(
      plans: <QuestionAnswerPlanDraft>[
        const QuestionAnswerPlanDraft(
          fingerprint: 'qf_question',
          selectedOptionLabels: <String>[],
          blankAnswers: <BlankAnswerPlan>[],
          confidence: 0.42,
          needsManualReview: true,
        ),
      ],
    );
    final gateway = _ScriptedAgentGateway(
      contextResults: <Map<String, dynamic>>[
        _questionContext(
          fingerprint: 'qf_question',
          options: <Map<String, dynamic>>[
            _option(id: 'option_a', label: 'A', text: '正确'),
          ],
        ),
      ],
    );
    final runtime = QuestionSolverRuntimeService(
      platformGateway: gateway,
      answerModel: model,
    );

    final result = await runtime.run(
      config: config,
      onStatus: (_) {},
      onLog: (String _, String message) {},
      onToolResult: (_) {},
    );

    expect(result.status, 'stopped');
    expect(result.message, contains('选择题答案没有包含任何选项标签'));
    expect(result.message, contains('（置信度 0.42）'));
    expect(model.callCount, 2);
  });
}

class _FakeQuestionAnswerModel implements QuestionAnswerModel {
  _FakeQuestionAnswerModel({required List<QuestionAnswerPlanDraft> plans})
    : _plans = List<QuestionAnswerPlanDraft>.from(plans);

  final List<QuestionAnswerPlanDraft> _plans;
  int callCount = 0;
  Uint8List? lastImageBytes;

  @override
  Future<QuestionAnswerPlanDraft> solve({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    Uint8List? imageBytes,
    String? imageMimeType,
    String? validationError,
  }) async {
    lastImageBytes = imageBytes;
    final index = callCount < _plans.length ? callCount : _plans.length - 1;
    callCount += 1;
    if (_plans.isEmpty) {
      throw StateError('测试模型没有配置答案计划。');
    }
    return _plans[index];
  }
}

class _FakeFallbackRuntime implements AgentRuntimeService {
  int runCount = 0;

  @override
  void requestStop() {}

  @override
  Future<AgentRunState> run({
    required AgentConfig config,
    required void Function(AgentRunState state) onStatus,
    required void Function(String level, String message) onLog,
    required void Function(NativeToolExecutionResult result) onToolResult,
  }) async {
    runCount += 1;
    final state = AgentRunState(
      running: false,
      status: 'completed',
      step: 0,
      message: 'fallback completed',
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    onStatus(state);
    return state;
  }
}

class _ScriptedAgentGateway implements AgentPlatformGateway {
  _ScriptedAgentGateway({required List<Map<String, dynamic>> contextResults})
    : _contextResults = contextResults;

  final List<Map<String, dynamic>> _contextResults;
  final List<String> toolNames = <String>[];
  int _contextIndex = 0;

  @override
  Stream<NativePlatformEvent> events() =>
      const Stream<NativePlatformEvent>.empty();

  @override
  Future<List<NativeToolDefinition>> listTools() async {
    return <NativeToolDefinition>[
      _tool('get_question_context'),
      _tool('apply_answer_plan', category: 'action'),
      _tool('capture_question_image'),
    ];
  }

  @override
  Future<Map<String, dynamic>> openAccessibilitySettings() async {
    return <String, dynamic>{'ok': true};
  }

  @override
  Future<NativeToolExecutionResult> executeTool(
    NativeToolExecutionRequest request,
  ) async {
    toolNames.add(request.toolName);
    switch (request.toolName) {
      case 'get_question_context':
        final index = _contextIndex < _contextResults.length
            ? _contextIndex++
            : _contextResults.length - 1;
        return _ok(request.toolName, _contextResults[index]);
      case 'apply_answer_plan':
        return _ok(request.toolName, <String, dynamic>{
          'answerApplied': true,
          'advanced': true,
          'manualReviewRequired': false,
        });
      case 'capture_question_image':
        return _ok(request.toolName, <String, dynamic>{
          'imageBase64': 'ZmFrZS1pbWFnZQ==',
          'mimeType': 'image/jpeg',
        });
      default:
        return _ok(request.toolName, <String, dynamic>{'ok': true});
    }
  }

  NativeToolExecutionResult _ok(String tool, Map<String, dynamic> result) {
    return NativeToolExecutionResult(
      requestId: 'req_$tool',
      tool: tool,
      ok: true,
      message: '$tool ok',
      result: result,
    );
  }

  NativeToolDefinition _tool(String name, {String category = 'inspection'}) {
    return NativeToolDefinition(
      name: name,
      description: name,
      inputSchema: const <String, dynamic>{},
      atomic: false,
      idempotent: false,
      category: category,
    );
  }
}

Map<String, dynamic> _questionContext({
  required String fingerprint,
  required List<Map<String, dynamic>> options,
  Map<String, dynamic>? cachedAnswerPlan,
  bool needsVisual = false,
}) {
  return <String, dynamic>{
    'context': <String, dynamic>{
      'contextSignature': 'question_context',
      'pageStage': 'question',
      'questionIndex': 1,
      'question': '测试题目',
      'normalizedQuestion': '测试题目',
      'options': options,
      'inputFields': <Map<String, dynamic>>[],
      'actions': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'next_button',
          'text': '下一题',
          'kind': 'next',
          'riskLevel': 'medium',
        },
      ],
      'blanks': <Map<String, dynamic>>[],
      'confidence': 0.95,
      'structureConfidence': 0.95,
      'needsVisual': needsVisual,
      'fingerprint': fingerprint,
      'cache': <String, dynamic>{
        'hit': cachedAnswerPlan != null,
        'seenCount': 1,
        'fingerprint': fingerprint,
        'answerPlan': cachedAnswerPlan,
      },
    },
  };
}

Map<String, dynamic> _unknownContext() {
  return <String, dynamic>{
    'context': <String, dynamic>{
      'contextSignature': 'unknown_context',
      'pageStage': 'unknown',
      'options': <Map<String, dynamic>>[],
      'inputFields': <Map<String, dynamic>>[],
      'actions': <Map<String, dynamic>>[],
      'blanks': <Map<String, dynamic>>[],
      'confidence': 0.2,
      'structureConfidence': 0.2,
      'needsVisual': false,
      'fingerprint': 'qf_unknown',
      'cache': <String, dynamic>{'hit': false, 'seenCount': 1},
    },
  };
}

Map<String, dynamic> _submitContext() {
  return <String, dynamic>{
    'context': <String, dynamic>{
      'contextSignature': 'submit_context',
      'pageStage': 'submit_ready',
      'options': <Map<String, dynamic>>[],
      'inputFields': <Map<String, dynamic>>[],
      'actions': <Map<String, dynamic>>[],
      'blanks': <Map<String, dynamic>>[],
      'confidence': 0.9,
      'structureConfidence': 0.9,
      'needsVisual': false,
      'fingerprint': 'qf_submit',
      'cache': <String, dynamic>{'hit': false, 'seenCount': 1},
    },
  };
}

Map<String, dynamic> _option({
  required String id,
  required String label,
  required String text,
}) {
  return <String, dynamic>{
    'id': id,
    'label': label,
    'text': text,
    'checked': false,
    'normalizedText': text,
  };
}
