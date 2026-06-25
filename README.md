# flutter_answer

基于 Flutter、Android 无障碍服务和原生悬浮窗能力构建的答题辅助控制台。应用负责统一管理系统权限、模型配置、Agent 运行状态和原生工具调用，并在进入提交相关页面时停止，由用户人工确认最终提交。

> 本项目仅用于学习交流和技术验证。使用前请确认目标平台规则、设备环境和账号安全要求。

## 项目预览

<img src="assets/images/cab5e49b5d668b0c58cf67aa06d05d59.jpg" alt="主界面截图" width="280" />

学习通效果演示视频位于 [assets/videos/example.mp4](assets/videos/example.mp4)。

## 核心能力

- 申请并检测悬浮窗权限，控制原生悬浮窗显示、关闭和移动。
- 检测无障碍服务状态，并跳转系统设置页完成授权。
- 配置 AI 提供商地址、API Key、模型名称和运行保护阈值。
- 基于 LangChain 调度模型规划，并通过 Platform Channel 调用 Android 原生工具。
- 读取题目上下文、页面结构和 UI 快照，执行点击、输入、滚动、返回等原子操作。
- 同步主应用、Agent 运行时和悬浮窗面板之间的日志与状态。
- 到达提交页、提交弹窗或未答题警告时自动停止，避免直接执行最终提交。

## 适用环境

### 开发环境

- Flutter：`3.41.2` 或兼容版本
- Dart：`3.11.0` 或兼容版本
- Android Gradle Plugin 与 Kotlin 版本以项目 Gradle 配置为准
- JDK：`17`

### 运行环境

- Android 设备或模拟器。
- 需要网络访问能力，用于请求配置的大模型服务。
- 需要用户手动授予悬浮窗权限和无障碍服务权限。
- 悬浮窗与无障碍能力强依赖 Android 系统行为，非 Android 平台不属于当前主要目标范围。

## 快速开始

1. 安装依赖：

   ```shell
   flutter pub get
   ```

2. 运行应用：

   ```shell
   flutter run
   ```

3. 在应用首页依次完成：

   - 开启悬浮窗权限。
   - 开启无障碍服务。
   - 填写提供商 URL、API Key 和模型名称。
   - 保存配置。
   - 打开目标答题页面。
   - 显示悬浮窗并启动 Agent。

4. 观察首页或悬浮窗日志。当应用提示已到达提交相关页面时，手动检查答案并确认是否提交。

## Agent 配置

| 字段 | 默认值 | 有效范围 | 说明 |
| --- | --- | --- | --- |
| 提供商 URL | 空 | 合法服务地址 | 必填。若只填写域名根路径，运行时会自动补齐 `/v1`。 |
| API Key | 空 | 非空字符串 | 必填。保存在本地 `SharedPreferences` 中，请只在可信设备上使用。 |
| 模型 | `gpt-5-mini` | 非空字符串 | 留空时回退到默认模型。 |
| 最大执行轮数 | `720` | `80` 到 `2000` | 限制 LangChain executor 的最大推理和工具调用轮数。 |
| 最大运行时间 | `15` 分钟 | `2` 到 `60` 分钟 | 限制单次 Agent 运行时长。 |
| 相同工具重复阈值 | `12` | `4` 到 `40` | 同一工具以相同参数连续调用达到阈值后停止。 |
| 无进展动作阈值 | `32` | `8` 到 `120` | 动作工具连续未产生有效界面变化达到阈值后停止。 |

所有数值配置在保存和运行前都会被裁剪到有效范围内。Provider URL、API Key 和模型名称会去除首尾空白。

## 运行机制

```text
Flutter 首页
  -> GetX Controller 管理表单、权限状态、日志和运行状态
  -> LangChain Agent Runtime 调用模型并选择工具
  -> MethodChannel / EventChannel 与 Android 原生层通信
  -> AccessibilityService 读取页面并执行原子操作
  -> NativeOverlayService 显示悬浮窗并同步状态
```

Agent 启动后会先获取 Android 原生层注册的工具清单，再把工具暴露给 LangChain。模型只能通过这些工具观察和操作页面，不能直接访问 Android UI。运行过程中如触发用户停止、提交页保护、重复调用保护、无进展保护、轮数上限或时间上限，Runtime 会更新状态并写入日志。

## 目录结构

```text
lib/
  main.dart                         应用入口
  app/pages/home/                   首页视图、控制器、配置表单和操作编排
  app/agent/data/                   Agent 配置持久化与平台网关
  app/agent/domain/models/          Agent 配置、状态、工具和事件模型
  app/agent/domain/services/        LangChain 运行时、URL 解析和提交页保护
  app/services/                     悬浮窗网关、诊断提示和平台负载校验
  app/theme/                        应用主题
android/app/src/main/kotlin/
  com/example/flutter_answer/agent/ 无障碍服务、题目解析、工具注册和调度
  com/example/flutter_answer/overlay/ 原生悬浮窗服务与控制器
test/                               Flutter 单元测试与 Widget 测试
android/app/src/test/               Android 原生单元测试
assets/                             图片、图标和演示视频资源
```

## 开发验证

常用验证命令：

```shell
flutter analyze
flutter test
cd android
./gradlew testDebugUnitTest
```

CI 工作流默认覆盖 Flutter 静态分析、Flutter 测试和 Android 单元测试。修改代码后至少执行其中一类验证；涉及跨端协议、运行时保护或 Android 原生工具时，建议同时执行 Flutter 测试和 Android 单元测试。

## Release 构建

`release` 构建不会复用 `debug` 签名。默认 `applicationId` 为 `com.zhao.flutter_answer`，可通过 Gradle 属性或环境变量 `APP_ID` 覆盖。

签名配置支持两种方式：

1. 在 `android/keystore.properties` 中配置：

   ```properties
   storeFile=/absolute/path/to/release.keystore
   storePassword=your_store_password
   keyAlias=your_key_alias
   keyPassword=your_key_password
   ```

2. 或使用环境变量：

   ```shell
   ANDROID_KEYSTORE_PATH=/absolute/path/to/release.keystore
   ANDROID_KEYSTORE_PASSWORD=your_store_password
   ANDROID_KEY_ALIAS=your_key_alias
   ANDROID_KEY_PASSWORD=your_key_password
   ```

根据项目规则，生成可交付 APK 时必须使用 V2 和 V3 签名，并在签名后执行 `apksigner verify`。只有验证结果同时显示 `Verified using v2 scheme (APK Signature Scheme v2): true` 和 `Verified using v3 scheme (APK Signature Scheme v3): true` 时，APK 才可交付。

## 常见问题

### 启动 Agent 时提示需要填写提供商 URL 或密钥

首页配置缺少必填项。填写提供商 URL、API Key 和模型名称后保存配置，再重新启动 Agent。

### 悬浮窗无法显示

先确认系统已授予悬浮窗权限。部分系统会限制后台悬浮窗或前台服务行为，需要在系统设置中允许应用后台运行和显示在其他应用上层。

### 无法读取题目或执行点击

确认无障碍服务已开启，并且当前目标页面处于前台。若页面刚发生跳转，等待页面稳定后重新读取上下文或重启 Agent。

### Agent 频繁提前停止

查看日志中的停止原因。若是重复工具或无进展保护触发，通常表示页面没有响应、目标控件不可操作或当前页面结构无法被稳定识别。先人工确认页面状态，再适当调整保护阈值。

### 已进入提交页但没有继续提交

这是预期行为。Runtime 会在提交页、提交弹窗或未答题警告处停止，最终提交必须由用户人工确认。

## 安全与边界

- API Key 保存在本地配置中，不应在不可信设备上使用。
- 应用不会自动点击最终提交、确认提交等高风险动作。
- 无障碍服务和悬浮窗权限属于高敏感权限，安装和使用前应确认代码来源可信。
- 本项目面向 Android 自动化学习场景，不保证适配所有题型、网页结构或第三方应用版本。

## 开源协议

本项目基于 MIT 协议开源。允许在保留版权与许可声明的前提下使用、修改和分发，完整内容见 [LICENSE](LICENSE)。
