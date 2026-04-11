package com.example.flutter_answer.agent

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 将原生工具调用串行放到后台线程执行，避免 MethodChannel 主线程被阻塞。
 *
 * 这里保留“同一时刻只执行一个工具”的约束，原因是无障碍操作本身具备时序依赖，
 * 并发执行只会让页面状态更不可预测。
 */
internal class NativeToolDispatcher(
    private val workerExecutor: Executor,
    private val callbackExecutor: Executor,
    private val invoker: NativeToolInvoker,
) {

    fun dispatch(
        requestId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        onComplete: (Map<String, Any?>) -> Unit,
    ) {
        workerExecutor.execute {
            val payload = try {
                invoker.invoke(requestId, toolName, arguments)
            } catch (error: Throwable) {
                // 注册表理论上已经会把工具异常包装成失败结果，这里再兜底一层，
                // 防止调度器自身异常直接把 Flutter 侧挂死。
                val finishedAt = SystemClock.uptimeMillis()
                NativeToolResult(
                    requestId = requestId,
                    tool = toolName,
                    ok = false,
                    message = error.message ?: "原生工具调度失败。",
                    result = mapOf(
                        "ok" to false,
                        "code" to "tool_dispatch_failed",
                        "message" to (error.message ?: "原生工具调度失败。"),
                    ),
                    startedAt = finishedAt,
                    finishedAt = finishedAt,
                ).toMap()
            }
            callbackExecutor.execute {
                onComplete(payload)
            }
        }
    }

    companion object {
        val shared: NativeToolDispatcher by lazy {
            NativeToolDispatcher(
                workerExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "native-tool-worker").apply {
                        isDaemon = true
                    }
                },
                callbackExecutor = MainThreadExecutor(),
                invoker = NativeToolInvoker { requestId, toolName, arguments ->
                    NativeToolRegistry.execute(
                        requestId = requestId,
                        toolName = toolName,
                        arguments = arguments,
                    )
                },
            )
        }
    }
}

internal fun interface NativeToolInvoker {
    fun invoke(
        requestId: String,
        toolName: String,
        arguments: Map<String, Any?>,
    ): Map<String, Any?>
}

private class MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        if (Looper.myLooper() == handler.looper) {
            command.run()
            return
        }
        handler.post(command)
    }
}
