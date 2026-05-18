package com.example.flutter_answer

import android.app.Application
import cn.vove7.andro_accessibility_api.AccessibilityApi
import com.example.flutter_answer.agent.ExamAccessibilityService

class FlutterAnswerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 第三方无障碍库需要在 Application 阶段显式初始化服务类，避免运行时反射失配。
        AccessibilityApi.init(
            ctx = this,
            baseServiceCls = ExamAccessibilityService::class.java,
            gestureServiceCls = ExamAccessibilityService::class.java,
        )
    }
}
