class NativeToolDefinition {
  const NativeToolDefinition({
    required this.name,
    required this.description,
    required this.inputSchema,
    required this.atomic,
    required this.idempotent,
    required this.category,
  });

  final String name;
  final String description;
  final Map<String, dynamic> inputSchema;
  final bool atomic;
  final bool idempotent;
  final String category;

  factory NativeToolDefinition.fromMap(Map<String, dynamic> json) {
    return NativeToolDefinition(
      name: json['name']?.toString() ?? '',
      description: json['description']?.toString() ?? '',
      inputSchema: _asMap(json['inputSchema']) ?? const <String, dynamic>{},
      atomic: json['atomic'] == true,
      idempotent: json['idempotent'] == true,
      category: json['category']?.toString() ?? 'action',
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'name': name,
      'description': description,
      'inputSchema': inputSchema,
      'atomic': atomic,
      'idempotent': idempotent,
      'category': category,
    };
  }

  static Map<String, dynamic>? _asMap(dynamic value) {
    if (value is Map) {
      return value.map(
        (dynamic key, dynamic item) => MapEntry(key.toString(), item),
      );
    }
    return null;
  }
}
