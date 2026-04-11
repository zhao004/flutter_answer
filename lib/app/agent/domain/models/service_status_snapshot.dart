import '../../../services/platform_payload_exception.dart';

class ServiceStatusSnapshot {
  const ServiceStatusSnapshot({
    this.ok = true,
    this.enabled = false,
    this.lastEventAtMs = 0,
    this.hasActiveWindow = false,
    this.flow = const <String, dynamic>{},
    this.latestContextSignature = '',
  });

  final bool ok;
  final bool enabled;
  final int lastEventAtMs;
  final bool hasActiveWindow;
  final Map<String, dynamic> flow;
  final String latestContextSignature;

  factory ServiceStatusSnapshot.fromMap(Map<String, dynamic> json) {
    return ServiceStatusSnapshot(
      ok: json['ok'] != false,
      enabled: json['enabled'] == true,
      lastEventAtMs: PlatformPayloadReader.readInt(json['lastEventAtMs']) ?? 0,
      hasActiveWindow: json['hasActiveWindow'] == true,
      flow:
          PlatformPayloadReader.tryMap(json['flow']) ??
          const <String, dynamic>{},
      latestContextSignature:
          json['latestContextSignature']?.toString().trim() ?? '',
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'ok': ok,
      'enabled': enabled,
      'lastEventAtMs': lastEventAtMs,
      'hasActiveWindow': hasActiveWindow,
      'flow': flow,
      'latestContextSignature': latestContextSignature,
    };
  }
}
