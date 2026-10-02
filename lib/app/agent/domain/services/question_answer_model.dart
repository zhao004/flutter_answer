import 'dart:convert';
import 'dart:typed_data';

import 'package:langchain/langchain.dart';
import 'package:langchain_openai/langchain_openai.dart';

import '../models/agent_config.dart';
import '../models/question_answer_plan.dart';
import 'agent_chat_model_factory.dart';

/// 结构化答题模型接口，便于在不发起真实网络请求的情况下测试运行时。
abstract interface class QuestionAnswerModel {
  Future<QuestionAnswerPlanDraft> solve({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    Uint8List? imageBytes,
    String? imageMimeType,
    String? validationError,
  });
}

class QuestionAnswerModelException implements Exception {
  const QuestionAnswerModelException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// 基于 OpenAI 兼容接口的答题模型实现。
///
/// 优先使用 JSON Schema 结构化输出；provider 不支持时回退到 JSON Object 模式。
class ChatOpenAiQuestionAnswerModel implements QuestionAnswerModel {
  ChatOpenAI? _schemaModel;
  ChatOpenAI? _jsonObjectModel;
  String? _modelCacheKey;
  bool _jsonObjectFallback = false;

  @override
  Future<QuestionAnswerPlanDraft> solve({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    Uint8List? imageBytes,
    String? imageMimeType,
    String? validationError,
  }) async {
    final model = _resolveModel(config);
    final messages = _buildMessages(
      config: config,
      context: context,
      imageBytes: imageBytes,
      imageMimeType: imageMimeType,
      validationError: validationError,
      jsonObjectFallback: _jsonObjectFallback,
    );

    try {
      return await _invokeModel(model, messages);
    } catch (error) {
      if (!_jsonObjectFallback && _isStructuredOutputUnsupported(error)) {
        _jsonObjectFallback = true;
        final fallbackModel = _resolveModel(config);
        return _invokeModel(fallbackModel, messages);
      }
      rethrow;
    }
  }

  ChatOpenAI _resolveModel(AgentConfig config) {
    final cacheKey =
        '${config.providerUrl}|${config.model}|${config.apiKey.hashCode}';
    if (_modelCacheKey != cacheKey) {
      _modelCacheKey = cacheKey;
      _schemaModel = null;
      _jsonObjectModel = null;
      _jsonObjectFallback = false;
    }
    if (_jsonObjectFallback) {
      return _jsonObjectModel ??= buildAgentChatModel(
        config,
        responseFormat: ChatOpenAIResponseFormat.jsonObject,
      );
    }
    return _schemaModel ??= buildAgentChatModel(
      config,
      responseFormat: ChatOpenAIResponseFormat.jsonSchema(
        const ChatOpenAIJsonSchema(
          name: 'QuestionAnswerPlan',
          description: 'A complete answer plan for one exam question.',
          strict: true,
          schema: _answerJsonSchema,
        ),
      ),
    );
  }

  Future<QuestionAnswerPlanDraft> _invokeModel(
    ChatOpenAI model,
    List<ChatMessage> messages,
  ) async {
    final response = await model.invoke(PromptValue.chat(messages));
    final content = response.output.content.trim();
    final decoded = _decodeJsonObject(content);
    return QuestionAnswerPlanDraft.fromMap(decoded);
  }

  List<ChatMessage> _buildMessages({
    required AgentConfig config,
    required QuestionContextSnapshot context,
    required Uint8List? imageBytes,
    required String? imageMimeType,
    required String? validationError,
    required bool jsonObjectFallback,
  }) {
    final systemPrompt = StringBuffer()
      ..writeln('你是 Android 答题理解模型，只负责给出结构化答案计划，不执行 UI 操作。')
      ..writeln('必须只返回 JSON，不要输出 Markdown 或解释。')
      ..writeln('选择题使用页面中稳定存在的选项标签；填空题使用 inputFields 的 order，从 1 开始。')
      ..writeln('selected_option_labels 表示题目最终应处于选中状态的完整标签集合。')
      ..writeln('blank_answers 必须覆盖所有输入框且每题号只出现一次。')
      ..writeln('当选项 contentMissing=true 时，选项正文只存在于截图中，必须结合截图判断每个标签的含义。')
      ..writeln('如果截图与结构化文本冲突、题目缺失或答案不确定，设置 needs_manual_review=true。')
      ..writeln('confidence 必须为 0 到 1 之间的小数。');
    if (jsonObjectFallback) {
      systemPrompt.writeln('返回对象必须符合以下 JSON Schema：');
      systemPrompt.writeln(jsonEncode(_answerJsonSchema));
    }

    final userPrompt = StringBuffer()
      ..writeln('任务策略：')
      ..writeln(config.taskPrompt)
      ..writeln()
      ..writeln('题目结构化上下文：')
      ..writeln(context.toPromptJson());
    if (imageBytes != null && imageBytes.isNotEmpty) {
      userPrompt.writeln();
      userPrompt.writeln('已附带题目区域截图。截图用于理解题干、公式和图表；结构化文本用于映射选项标签与填空顺序。');
    }
    if (validationError != null && validationError.trim().isNotEmpty) {
      userPrompt.writeln();
      userPrompt.writeln('上一次答案计划未通过校验，请修正以下问题：');
      userPrompt.writeln(validationError.trim());
    }

    if (imageBytes != null && imageBytes.isNotEmpty) {
      return <ChatMessage>[
        ChatMessage.system(systemPrompt.toString()),
        ChatMessage.human(
          ChatMessageContent.multiModal(<ChatMessageContent>[
            ChatMessageContent.text(userPrompt.toString()),
            ChatMessageContent.image(
              data: base64Encode(imageBytes),
              mimeType: imageMimeType ?? 'image/jpeg',
              imageDetail: ChatMessageContentImageDetail.high,
            ),
          ]),
        ),
      ];
    }

    return <ChatMessage>[
      ChatMessage.system(systemPrompt.toString()),
      ChatMessage.humanText(userPrompt.toString()),
    ];
  }

  Map<String, dynamic> _decodeJsonObject(String content) {
    if (content.isEmpty) {
      throw const QuestionAnswerModelException('模型返回了空答案。');
    }
    try {
      final decoded = jsonDecode(content);
      if (decoded is Map) {
        return decoded.map(
          (dynamic key, dynamic value) => MapEntry(key.toString(), value),
        );
      }
    } on FormatException {
      // 部分兼容 provider 会在 JSON 外套一层文本，这里做一次保守截取。
    }
    final start = content.indexOf('{');
    final end = content.lastIndexOf('}');
    if (start >= 0 && end > start) {
      final decoded = jsonDecode(content.substring(start, end + 1));
      if (decoded is Map) {
        return decoded.map(
          (dynamic key, dynamic value) => MapEntry(key.toString(), value),
        );
      }
    }
    throw const QuestionAnswerModelException('模型返回内容不是合法 JSON 对象。');
  }

  bool _isStructuredOutputUnsupported(Object error) {
    final message = error.toString().toLowerCase();
    final mentionsSchema =
        message.contains('json_schema') ||
        message.contains('response_format') ||
        message.contains('structured output') ||
        message.contains('schema');
    final mentionsUnsupported =
        message.contains('unsupported') ||
        message.contains('not supported') ||
        message.contains('invalid') ||
        message.contains('400');
    return mentionsSchema && mentionsUnsupported;
  }

  static const Map<String, dynamic> _answerJsonSchema = <String, dynamic>{
    'type': 'object',
    'properties': <String, dynamic>{
      'question_fingerprint': <String, dynamic>{'type': 'string'},
      'selected_option_labels': <String, dynamic>{
        'type': 'array',
        'items': <String, dynamic>{'type': 'string'},
      },
      'blank_answers': <String, dynamic>{
        'type': 'array',
        'items': <String, dynamic>{
          'type': 'object',
          'properties': <String, dynamic>{
            'order': <String, dynamic>{'type': 'integer'},
            'text': <String, dynamic>{'type': 'string'},
          },
          'required': <String>['order', 'text'],
          'additionalProperties': false,
        },
      },
      'confidence': <String, dynamic>{'type': 'number'},
      'needs_manual_review': <String, dynamic>{'type': 'boolean'},
    },
    'required': <String>[
      'question_fingerprint',
      'selected_option_labels',
      'blank_answers',
      'confidence',
      'needs_manual_review',
    ],
    'additionalProperties': false,
  };
}
