import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/services/submit_page_stop_guard.dart';

void main() {
  group('resolveSubmitPageStopMessage', () {
    test('页面阶段为提交页时返回人工确认提示', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_1',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{'pageStage': 'submit_ready'},
        },
      );

      expect(resolveSubmitPageStopMessage(result), submitPageStopMessage);
    });

    test('页面阶段为未答完提交警告时返回未答完提示', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_2',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{'pageStage': 'submit_warning'},
        },
      );

      expect(
        resolveSubmitPageStopMessage(result),
        unansweredWarningStopMessage,
      );
    });

    test('继续答题入口页不触发停止', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_3',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{'pageStage': 'resume_entry'},
        },
      );

      expect(resolveSubmitPageStopMessage(result), isNull);
    });

    test('旧版本上下文仍可回退到 structureSignals', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_4',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{
            'structureSignals': <String, dynamic>{'submitDialogSignal': 1},
          },
        },
      );

      expect(resolveSubmitPageStopMessage(result), submitPageStopMessage);
    });
  });
}
