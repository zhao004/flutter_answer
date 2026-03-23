class OverlayWindowPosition {
  const OverlayWindowPosition(this.x, this.y);

  final double x;
  final double y;

  Map<String, double> toMap() {
    return <String, double>{'x': x, 'y': y};
  }

  static OverlayWindowPosition? tryParse(dynamic value) {
    if (value is! Map) {
      return null;
    }

    final map = value.map(
      (dynamic key, dynamic item) => MapEntry(key.toString(), item),
    );
    final x = _readDouble(map['x']);
    final y = _readDouble(map['y']);
    if (x == null || y == null) {
      return null;
    }
    return OverlayWindowPosition(x, y);
  }

  static double? _readDouble(dynamic value) {
    if (value is num) {
      return value.toDouble();
    }
    return double.tryParse(value?.toString() ?? '');
  }
}
