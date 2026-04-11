package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeToolDispatcherTest {

    @Test
    fun `dispatch runs native tools on worker thread and keeps serial order`() {
        val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "native-tool-test-worker")
        }
        val completedTools = Collections.synchronizedList(mutableListOf<String>())
        val executionThreads = Collections.synchronizedList(mutableListOf<String>())
        val firstStarted = CountDownLatch(1)
        val allowFirstFinish = CountDownLatch(1)
        val allCompleted = CountDownLatch(2)
        val dispatcher = NativeToolDispatcher(
            workerExecutor = workerExecutor,
            callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "native-tool-test-callback")
            },
            invoker = NativeToolInvoker { requestId, toolName, _ ->
                executionThreads.add(Thread.currentThread().name)
                if (toolName == "first") {
                    firstStarted.countDown()
                    assertTrue(allowFirstFinish.await(1, TimeUnit.SECONDS))
                }
                mapOf(
                    "requestId" to requestId,
                    "tool" to toolName,
                    "ok" to true,
                    "result" to emptyMap<String, Any?>(),
                )
            },
        )

        dispatcher.dispatch("req_1", "first", emptyMap()) { payload ->
            completedTools.add(payload["tool"].toString())
            allCompleted.countDown()
        }
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
        dispatcher.dispatch("req_2", "second", emptyMap()) { payload ->
            completedTools.add(payload["tool"].toString())
            allCompleted.countDown()
        }
        allowFirstFinish.countDown()

        assertTrue(allCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second"), completedTools)
        assertEquals(2, executionThreads.size)
        executionThreads.forEach { threadName ->
            assertNotEquals("main", threadName)
            assertTrue(threadName.contains("native-tool-test-worker"))
        }
        workerExecutor.shutdownNow()
    }
}
