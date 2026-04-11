package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationFlowStateMachineTest {

    @Test
    fun `transitionTo marks valid transitions correctly`() {
        var now = 100L
        val stateMachine = AutomationFlowStateMachine(nowProvider = { now })

        val transition = stateMachine.transitionTo(
            target = AutomationFlowState.PAGE_DETECTING,
            reason = "开始检测页面。",
        )

        assertTrue(transition.valid)
        assertEquals(AutomationFlowState.IDLE, transition.from)
        assertEquals(AutomationFlowState.PAGE_DETECTING, transition.to)
        assertEquals("开始检测页面。", transition.reason)
        assertEquals(100L, transition.atMs)
        assertEquals(AutomationFlowState.PAGE_DETECTING, stateMachine.currentState())

        now = 120L
        val invalidTransition = stateMachine.transitionTo(
            target = AutomationFlowState.WAITING_CONFIRM,
            reason = "直接跳到确认态。",
        )

        assertFalse(invalidTransition.valid)
        assertEquals(120L, invalidTransition.atMs)
        assertEquals(AutomationFlowState.WAITING_CONFIRM, stateMachine.currentState())
    }

    @Test
    fun `reset always returns state machine to idle`() {
        val stateMachine = AutomationFlowStateMachine()
        stateMachine.transitionTo(
            target = AutomationFlowState.PAGE_DETECTING,
            reason = "进入流程。",
        )
        stateMachine.transitionTo(
            target = AutomationFlowState.CONTEXT_ANALYZED,
            reason = "完成分析。",
        )

        stateMachine.reset("手动停止。")
        val snapshot = stateMachine.snapshot()
        val lastTransition = snapshot["lastTransition"] as Map<*, *>

        assertEquals(AutomationFlowState.IDLE, stateMachine.currentState())
        assertEquals("idle", snapshot["state"])
        assertEquals("idle", lastTransition["to"])
        assertEquals("手动停止。", lastTransition["reason"])
    }
}
