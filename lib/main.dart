import 'package:flutter/material.dart';
import 'package:get/get.dart';

import 'app/routes/app_pages.dart';
import 'app/theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    GetMaterialApp(
      debugShowCheckedModeBanner: false,
      title: "AI自动答题",
      theme: AppThemeTokens.buildTheme(),
      initialRoute: AppPages.initial,
      getPages: AppPages.routes,
    ),
  );
}
