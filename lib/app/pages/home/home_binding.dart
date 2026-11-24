import 'package:get/get.dart';

import '../../agent/data/agent_config_store.dart';
import '../../agent/data/agent_platform_gateway.dart';
import '../../agent/data/http_model_catalog_gateway.dart';
import '../../agent/data/method_channel_agent_platform_gateway.dart';
import '../../agent/data/model_catalog_gateway.dart';
import '../../agent/domain/services/question_solver_runtime_service.dart';
import '../../services/method_channel_overlay_platform_gateway.dart';
import '../../services/overlay_platform_gateway.dart';
import 'home_controller.dart';

class HomeBinding extends Bindings {
  @override
  void dependencies() {
    Get.lazyPut<AgentPlatformGateway>(
      () => MethodChannelAgentPlatformGateway.instance,
      fenix: true,
    );
    Get.lazyPut<OverlayPlatformGateway>(
      () => MethodChannelOverlayPlatformGateway.instance,
      fenix: true,
    );
    Get.lazyPut<AgentConfigStore>(() => AgentConfigStore.instance, fenix: true);
    Get.lazyPut<ModelCatalogGateway>(
      () => HttpModelCatalogGateway(),
      fenix: true,
    );
    Get.lazyPut<QuestionSolverRuntimeService>(
      () => QuestionSolverRuntimeService(
        platformGateway: Get.find<AgentPlatformGateway>(),
      ),
      fenix: true,
    );
    Get.lazyPut<HomeController>(
      () => HomeController(
        platformGateway: Get.find<AgentPlatformGateway>(),
        overlayPlatformGateway: Get.find<OverlayPlatformGateway>(),
        configStore: Get.find<AgentConfigStore>(),
        runtimeService: Get.find<QuestionSolverRuntimeService>(),
        modelCatalogGateway: Get.find<ModelCatalogGateway>(),
      ),
    );
  }
}
