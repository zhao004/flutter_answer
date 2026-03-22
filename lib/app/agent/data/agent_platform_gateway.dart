import '../domain/models/native_platform_event.dart';
import '../domain/models/native_tool_definition.dart';
import '../domain/models/native_tool_execution.dart';

abstract interface class AgentPlatformGateway {
  Stream<NativePlatformEvent> events();

  Future<Map<String, dynamic>> openAccessibilitySettings();

  Future<List<NativeToolDefinition>> listTools();

  Future<NativeToolExecutionResult> executeTool(
    NativeToolExecutionRequest request,
  );
}
