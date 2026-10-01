import '../models/agent_config.dart';
import '../models/agent_run_state.dart';
import '../models/native_tool_execution.dart';

/// Agent 运行时统一入口，便于主链路与通用回退链路互换。
abstract interface class AgentRuntimeService {
  void requestStop();

  Future<AgentRunState> run({
    required AgentConfig config,
    required void Function(AgentRunState state) onStatus,
    required void Function(String level, String message) onLog,
    required void Function(NativeToolExecutionResult result) onToolResult,
  });
}
