import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_answer/app/agent/domain/models/native_tool_execution.dart';
import 'package:flutter_answer/app/agent/domain/services/submit_page_stop_guard.dart';

void main() {
  group('resolveSubmitPageStopMessage', () {
    test('在提交页信号出现时返回人工确认提示', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_1',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{
            'structureSignals': <String, dynamic>{'submitReadySignal': 1},
          },
        },
      );

      expect(resolveSubmitPageStopMessage(result), submitPageStopMessage);
    });

    test('在未答完警告出现时返回未答完提示', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_2',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{
            'structureSignals': <String, dynamic>{
              'submitDialogSignal': 1,
              'unansweredWarningSignal': 1,
            },
          },
        },
      );

      expect(
        resolveSubmitPageStopMessage(result),
        unansweredWarningStopMessage,
      );
    });

    test('普通题目页不触发停止', () {
      final result = NativeToolExecutionResult(
        requestId: 'req_3',
        tool: 'get_question_context',
        ok: true,
        result: <String, dynamic>{
          'context': <String, dynamic>{
            'structureSignals': <String, dynamic>{'finalQuestionSignal': 1},
          },
        },
      );

      expect(resolveSubmitPageStopMessage(result), isNull);
    });
  });
}
