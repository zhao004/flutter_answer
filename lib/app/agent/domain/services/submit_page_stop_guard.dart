import '../models/native_tool_execution.dart';

const String submitPageStopMessage = '检测到提交页面，Agent 已自动停止，请人工确认后再提交。';
const String unansweredWarningStopMessage =
    '检测到未答完提交提示，Agent 已自动停止，请先检查未作答题目后再手动提交。';

String? resolveSubmitPageStopMessage(NativeToolExecutionResult result) {
  if (!result.ok || result.tool != 'get_question_context') {
    return null;
  }
  final context = _readMap(result.result['context']);
  if (context.isEmpty) {
    return null;
  }
  final structureSignals = _readMap(context['structureSignals']);
  if (structureSignals.isEmpty) {
    return null;
  }
  if (_readSignal(structureSignals['unansweredWarningSignal']) >= 1) {
    return unansweredWarningStopMessage;
  }
  final submitReadySignal = _readSignal(structureSignals['submitReadySignal']);
  final submitDialogSignal = _readSignal(
    structureSignals['submitDialogSignal'],
  );
  if (submitReadySignal >= 1 || submitDialogSignal >= 1) {
    return submitPageStopMessage;
  }
  return null;
}

Map<String, dynamic> _readMap(dynamic value) {
  if (value is Map) {
    return value.map(
      (dynamic key, dynamic item) => MapEntry(key.toString(), item),
    );
  }
  return const <String, dynamic>{};
}

double _readSignal(dynamic value) {
  if (value is num) {
    return value.toDouble();
  }
  return double.tryParse(value?.toString() ?? '') ?? 0;
}
