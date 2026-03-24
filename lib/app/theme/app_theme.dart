import 'package:flutter/material.dart';

/// 应用主题与原生悬浮窗主题的唯一颜色来源，避免两端长期维护两套不一致的色板。
class AppThemeTokens {
  AppThemeTokens._();

  static const Color brandSeedColor = Color(0xFF8EC5FF);
  static const Color appBackgroundColor = Color(0xFFF3F9FF);
  static const Color appForegroundColor = Color(0xFF18324A);
  static const Color cardShadowColor = Color(0x1A8EC5FF);
  static const Color warningColor = Color(0xFF9A6700);
  static const Color successColor = Color(0xFF2F7D4A);
  static const Color logPanelBackgroundColor = Color(0xFFEFF7FF);
  static const Color cardFillColor = Color(0xF0FFFFFF);
  static const Color inputFillColor = Colors.white;

  static ColorScheme buildColorScheme() {
    return ColorScheme.fromSeed(
      seedColor: brandSeedColor,
      brightness: Brightness.light,
    );
  }

  /// 统一生成 Flutter 主题，确保首页和其他页面都从同一套 token 派生颜色。
  static ThemeData buildTheme() {
    final colorScheme = buildColorScheme();
    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: appBackgroundColor,
      textTheme: Typography.material2021().black.apply(
        bodyColor: appForegroundColor,
        displayColor: appForegroundColor,
      ),
      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 0,
        backgroundColor: Colors.transparent,
        foregroundColor: appForegroundColor,
        surfaceTintColor: Colors.transparent,
        titleTextStyle: Typography.material2021().black.titleLarge?.copyWith(
          fontWeight: FontWeight.w800,
          color: appForegroundColor,
        ),
      ),
      cardTheme: CardThemeData(
        margin: EdgeInsets.zero,
        elevation: 0,
        color: cardFillColor,
        shadowColor: cardShadowColor,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(28),
          side: BorderSide(
            color: colorScheme.outlineVariant.withValues(alpha: 0.65),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: inputFillColor,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16,
          vertical: 16,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(20),
          borderSide: BorderSide(color: colorScheme.outlineVariant),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(20),
          borderSide: BorderSide(color: colorScheme.outlineVariant),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(20),
          borderSide: BorderSide(color: colorScheme.primary, width: 1.6),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(20),
          borderSide: BorderSide(color: colorScheme.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(20),
          borderSide: BorderSide(color: colorScheme.error, width: 1.6),
        ),
        labelStyle: TextStyle(color: colorScheme.onSurfaceVariant),
        hintStyle: TextStyle(color: colorScheme.onSurfaceVariant),
        helperStyle: TextStyle(color: colorScheme.onSurfaceVariant),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 52),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size(0, 52),
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          side: BorderSide(color: colorScheme.outlineVariant),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
          ),
          textStyle: const TextStyle(fontWeight: FontWeight.w700),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbIcon: WidgetStateProperty.resolveWith<Icon?>((states) {
          if (states.contains(WidgetState.selected)) {
            return const Icon(Icons.check_rounded, size: 14);
          }
          return const Icon(Icons.close_rounded, size: 14);
        }),
        trackOutlineColor: WidgetStatePropertyAll(
          colorScheme.outlineVariant.withValues(alpha: 0.35),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: colorScheme.outlineVariant.withValues(alpha: 0.75),
        thickness: 1,
        space: 1,
      ),
    );
  }

  /// 将主题 token 发送给原生悬浮窗，避免原生层继续维持独立硬编码色板。
  static Map<String, dynamic> overlayThemePayload() {
    final colorScheme = buildColorScheme();
    return <String, dynamic>{
      'primary': _colorToHex(colorScheme.primary),
      'primaryStrong': _colorToHex(brandSeedColor),
      'onPrimary': _colorToHex(colorScheme.onPrimary),
      'surface': _colorToHex(cardFillColor),
      'surfaceCard': _colorToHex(inputFillColor),
      'foreground': _colorToHex(appForegroundColor),
      'muted': _colorToHex(colorScheme.onSurfaceVariant),
      'outline': _colorToHex(
        colorScheme.outlineVariant.withValues(alpha: 0.65),
      ),
      'warning': _colorToHex(warningColor),
      'success': _colorToHex(successColor),
      'error': _colorToHex(colorScheme.error),
    };
  }

  static String _colorToHex(Color color) {
    final value = color.toARGB32();
    return '#${value.toRadixString(16).padLeft(8, '0').toUpperCase()}';
  }
}
