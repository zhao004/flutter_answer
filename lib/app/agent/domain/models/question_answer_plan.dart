import 'dart:convert';

class BlankAnswerPlan {
  const BlankAnswerPlan({required this.order, required this.text});

  final int order;
  final String text;

  Map<String, dynamic> toMap() => <String, dynamic>{
    'order': order,
    'text': text,
  };

  factory BlankAnswerPlan.fromMap(Map<String, dynamic> map) {
    return BlankAnswerPlan(
      order: _readInt(map['order']) ?? 0,
      text: map['text']?.toString().trim() ?? '',
    );
  }
}

class QuestionAnswerPlanDraft {
  const QuestionAnswerPlanDraft({
    required this.fingerprint,
    required this.selectedOptionLabels,
    required this.blankAnswers,
    required this.confidence,
    required this.needsManualReview,
  });

  final String fingerprint;
  final List<String> selectedOptionLabels;
  final List<BlankAnswerPlan> blankAnswers;
  final double confidence;
  final bool needsManualReview;

  factory QuestionAnswerPlanDraft.fromMap(Map<String, dynamic> map) {
    final rawLabels =
        map['selected_option_labels'] ?? map['selectedOptionLabels'];
    final rawAnswers = map['blank_answers'] ?? map['blankAnswers'];
    return QuestionAnswerPlanDraft(
      fingerprint:
          (map['question_fingerprint'] ?? map['fingerprint'])
              ?.toString()
              .trim() ??
          '',
      selectedOptionLabels: rawLabels is List
          ? rawLabels
                .map((dynamic value) => value?.toString().trim() ?? '')
                .where((String value) => value.isNotEmpty)
                .toList(growable: false)
          : const <String>[],
      blankAnswers: rawAnswers is List
          ? rawAnswers
                .whereType<Map>()
                .map(
                  (Map value) => BlankAnswerPlan.fromMap(
                    value.map(
                      (dynamic key, dynamic item) =>
                          MapEntry(key.toString(), item),
                    ),
                  ),
                )
                .toList(growable: false)
          : const <BlankAnswerPlan>[],
      confidence: _readDouble(map['confidence']) ?? 0,
      needsManualReview:
          map['needs_manual_review'] == true ||
          map['needsManualReview'] == true,
    );
  }

  Map<String, dynamic> toNativeArguments({required bool advance}) {
    return <String, dynamic>{
      'fingerprint': fingerprint,
      'selectedOptionLabels': selectedOptionLabels,
      'blankAnswers': blankAnswers.map((plan) => plan.toMap()).toList(),
      'confidence': confidence,
      'advance': advance,
    };
  }

  bool get isUsable => fingerprint.isNotEmpty && !needsManualReview;
}

class QuestionOptionSnapshot {
  const QuestionOptionSnapshot({
    required this.id,
    required this.label,
    required this.text,
    required this.checked,
    required this.normalizedText,
    this.contentMissing = false,
  });

  final String id;
  final String label;
  final String text;
  final bool checked;
  final String normalizedText;

  /// 页面是否只暴露了选项标签而没有暴露选项正文（正文仅存在于截图里）。
  final bool contentMissing;

  factory QuestionOptionSnapshot.fromMap(Map<String, dynamic> map) {
    return QuestionOptionSnapshot(
      id: map['id']?.toString() ?? '',
      label: map['label']?.toString().trim() ?? '',
      text: map['text']?.toString().trim() ?? '',
      checked: map['checked'] == true,
      normalizedText: map['normalizedText']?.toString().trim() ?? '',
      contentMissing: map['contentMissing'] == true,
    );
  }

  Map<String, dynamic> toPromptMap() => <String, dynamic>{
    'id': id,
    'label': label,
    'text': text,
    'checked': checked,
    'contentMissing': contentMissing,
  };
}

class QuestionInputSnapshot {
  const QuestionInputSnapshot({
    required this.id,
    required this.text,
    required this.desc,
    required this.hint,
    required this.order,
  });

  final String id;
  final String? text;
  final String? desc;
  final String? hint;
  final int? order;

  factory QuestionInputSnapshot.fromMap(Map<String, dynamic> map) {
    return QuestionInputSnapshot(
      id: map['id']?.toString() ?? '',
      text: map['text']?.toString(),
      desc: map['desc']?.toString(),
      hint: map['hint']?.toString(),
      order: _readInt(map['order']),
    );
  }

  Map<String, dynamic> toPromptMap() => <String, dynamic>{
    'id': id,
    'text': text,
    'desc': desc,
    'hint': hint,
    'order': order,
  };
}

class QuestionActionSnapshot {
  const QuestionActionSnapshot({
    required this.id,
    required this.text,
    required this.kind,
    required this.riskLevel,
  });

  final String id;
  final String text;
  final String kind;
  final String riskLevel;

  factory QuestionActionSnapshot.fromMap(Map<String, dynamic> map) {
    return QuestionActionSnapshot(
      id: map['id']?.toString() ?? '',
      text: map['text']?.toString() ?? '',
      kind: map['kind']?.toString() ?? '',
      riskLevel: map['riskLevel']?.toString() ?? '',
    );
  }
}

class QuestionBlankSnapshot {
  const QuestionBlankSnapshot({
    required this.index,
    required this.hint,
    required this.inputId,
  });

  final int index;
  final String hint;
  final String? inputId;

  factory QuestionBlankSnapshot.fromMap(Map<String, dynamic> map) {
    return QuestionBlankSnapshot(
      index: _readInt(map['index']) ?? 0,
      hint: map['hint']?.toString() ?? '',
      inputId: map['inputId']?.toString(),
    );
  }
}

class QuestionContextSnapshot {
  const QuestionContextSnapshot({
    required this.contextSignature,
    required this.pageStage,
    required this.questionIndex,
    required this.question,
    required this.normalizedQuestion,
    required this.options,
    required this.inputFields,
    required this.actions,
    required this.blanks,
    required this.confidence,
    required this.structureConfidence,
    required this.needsVisual,
    required this.fingerprint,
    required this.cachedAnswerPlan,
    required this.raw,
  });

  final String contextSignature;
  final String pageStage;
  final int? questionIndex;
  final String question;
  final String normalizedQuestion;
  final List<QuestionOptionSnapshot> options;
  final List<QuestionInputSnapshot> inputFields;
  final List<QuestionActionSnapshot> actions;
  final List<QuestionBlankSnapshot> blanks;
  final double confidence;
  final double structureConfidence;
  final bool needsVisual;
  final String fingerprint;
  final QuestionAnswerPlanDraft? cachedAnswerPlan;
  final Map<String, dynamic> raw;

  factory QuestionContextSnapshot.fromToolResult(
    Map<String, dynamic> toolResult,
  ) {
    final rawContext = _asMap(toolResult['context']);
    if (rawContext.isEmpty) {
      throw const FormatException('题目上下文负载缺少 context 字段。');
    }
    return QuestionContextSnapshot.fromMap(rawContext);
  }

  factory QuestionContextSnapshot.fromMap(Map<String, dynamic> map) {
    final cache = _asMap(map['cache']);
    final rawCachedPlan = _asMap(cache['answerPlan']);
    return QuestionContextSnapshot(
      contextSignature: map['contextSignature']?.toString() ?? '',
      pageStage: map['pageStage']?.toString() ?? 'unknown',
      questionIndex: _readInt(map['questionIndex']),
      question: map['question']?.toString() ?? '',
      normalizedQuestion: map['normalizedQuestion']?.toString() ?? '',
      options: _mapList(map['options'], QuestionOptionSnapshot.fromMap),
      inputFields: _mapList(map['inputFields'], QuestionInputSnapshot.fromMap),
      actions: _mapList(map['actions'], QuestionActionSnapshot.fromMap),
      blanks: _mapList(map['blanks'], QuestionBlankSnapshot.fromMap),
      confidence: _readDouble(map['confidence']) ?? 0,
      structureConfidence: _readDouble(map['structureConfidence']) ?? 0,
      needsVisual: map['needsVisual'] == true,
      fingerprint: map['fingerprint']?.toString() ?? '',
      cachedAnswerPlan: rawCachedPlan.isEmpty
          ? null
          : QuestionAnswerPlanDraft.fromMap(rawCachedPlan),
      raw: Map<String, dynamic>.from(map),
    );
  }

  Map<String, dynamic> toPromptMap() {
    return <String, dynamic>{
      'contextSignature': contextSignature,
      'pageStage': pageStage,
      'questionIndex': questionIndex,
      'question': question,
      'normalizedQuestion': normalizedQuestion,
      'options': options.map((option) => option.toPromptMap()).toList(),
      'inputFields': inputFields.map((input) => input.toPromptMap()).toList(),
      'actions': actions
          .map(
            (action) => <String, dynamic>{
              'id': action.id,
              'text': action.text,
              'kind': action.kind,
              'riskLevel': action.riskLevel,
            },
          )
          .toList(),
      'blanks': blanks
          .map(
            (blank) => <String, dynamic>{
              'index': blank.index,
              'hint': blank.hint,
              'inputId': blank.inputId,
            },
          )
          .toList(),
      'confidence': confidence,
      'structureConfidence': structureConfidence,
      'needsVisual': needsVisual,
      'fingerprint': fingerprint,
    };
  }

  String toPromptJson() => jsonEncode(toPromptMap());
}

List<T> _mapList<T>(dynamic value, T Function(Map<String, dynamic>) mapper) {
  if (value is! List) {
    return <T>[];
  }
  return value
      .whereType<Map>()
      .map(
        (Map item) => mapper(
          item.map(
            (dynamic key, dynamic item) => MapEntry(key.toString(), item),
          ),
        ),
      )
      .toList(growable: false);
}

Map<String, dynamic> _asMap(dynamic value) {
  if (value is Map) {
    return value.map(
      (dynamic key, dynamic item) => MapEntry(key.toString(), item),
    );
  }
  return const <String, dynamic>{};
}

int? _readInt(dynamic value) {
  if (value is int) {
    return value;
  }
  return int.tryParse(value?.toString() ?? '');
}

double? _readDouble(dynamic value) {
  if (value is num) {
    return value.toDouble();
  }
  return double.tryParse(value?.toString() ?? '');
}
