class ProviderUrlResolver {
  const ProviderUrlResolver._();

  static String resolveBaseUrl(String providerUrl) {
    final raw = providerUrl.trim();
    if (raw.isEmpty) {
      return '';
    }

    final normalized = raw.endsWith('/')
        ? raw.substring(0, raw.length - 1)
        : raw;
    final uri = Uri.tryParse(normalized);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      return normalized;
    }

    final path = uri.path.trim();
    if (path.isEmpty || path == '/') {
      return uri.replace(path: '/v1').toString();
    }

    return normalized;
  }
}
