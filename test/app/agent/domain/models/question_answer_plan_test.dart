import 'package:flutter_answer/app/agent/domain/models/question_answer_plan.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('QuestionAnswerPlanDraft.fromMap manual review reason', () {
    test('兼容蛇形字段并去除首尾空白', () {
      final plan = QuestionAnswerPlanDraft.fromMap(<String, dynamic>{
        'question_fingerprint': 'qf_1',
        'selected_option_labels': <String>['A'],
        'blank_answers': <Map<String, dynamic>>[],
        'confidence': 0.8,
        'needs_manual_review': false,
        'manual_review_reason': '  题目缺少选项正文  ',
      });

      expect(plan.manualReviewReason, '题目缺少选项正文');
      expect(plan.needsManualReview, isFalse);
    });

    test('兼容驼峰字段并在缺失时回退为空字符串', () {
      final camel = QuestionAnswerPlanDraft.fromMap(<String, dynamic>{
        'fingerprint': 'qf_1',
        'confidence': 0.4,
        'needsManualReview': true,
        'manualReviewReason': '截图与结构化文本冲突',
      });

      expect(camel.manualReviewReason, '截图与结构化文本冲突');
      expect(camel.needsManualReview, isTrue);

      final missing = QuestionAnswerPlanDraft.fromMap(<String, dynamic>{
        'fingerprint': 'qf_1',
      });

      expect(missing.manualReviewReason, '');
    });

    test('超长原因会被截断并补充省略号', () {
      final plan = QuestionAnswerPlanDraft.fromMap(<String, dynamic>{
        'fingerprint': 'qf_1',
        'needs_manual_review': true,
        'manual_review_reason': '理' * 500,
      });

      expect(plan.manualReviewReason.length, 201);
      expect(plan.manualReviewReason.endsWith('…'), isTrue);
    });
  });
}
