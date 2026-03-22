import 'package:get/get.dart';

import '../../agent/data/agent_config_store.dart';
import '../../agent/data/agent_platform_gateway.dart';
import '../../agent/data/method_channel_agent_platform_gateway.dart';
import '../../agent/domain/services/langchain_agent_runtime_service.dart';
import 'home_controller.dart';

class HomeBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<AgentPlatformGateway>(
      () => MethodChannelAgentPlatformGateway.instance,
      fenix: true,
    );
    Get.lazyPut<AgentConfigStore>(() => AgentConfigStore.instance, fenix: true);
    Get.lazyPut<LangChainAgentRuntimeService>(
      () => LangChainAgentRuntimeService(
        platformGateway: Get.find<AgentPlatformGateway>(),
      ),
      fenix: true,
    );
    Get.lazyPut<HomeController>(
      () => HomeController(
        platformGateway: Get.find<AgentPlatformGateway>(),
        configStore: Get.find<AgentConfigStore>(),
        runtimeService: Get.find<LangChainAgentRuntimeService>(),
      ),
    );
  }
}
