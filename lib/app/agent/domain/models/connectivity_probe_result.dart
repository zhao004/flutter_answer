class ConnectivityProbeResult {
  const ConnectivityProbeResult({
    required this.ok,
    required this.message,
    required this.reply,
    required this.latencyMs,
    required this.model,
    required this.providerUrl,
  });

  final bool ok;
  final String message;
  final String reply;
  final int? latencyMs;
  final String model;
  final String providerUrl;

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'ok': ok,
      'message': message,
      'reply': reply,
      'latencyMs': latencyMs,
      'model': model,
      'providerUrl': providerUrl,
    };
  }
}
