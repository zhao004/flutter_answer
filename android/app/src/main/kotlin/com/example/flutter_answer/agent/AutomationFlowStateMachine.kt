package com.example.flutter_answer.agent

import java.util.Locale

enum class AutomationFlowState {
    IDLE,
    PAGE_DETECTING,
    PAGE_CLASSIFIED,
    QUESTION_PARSING,
    ANSWER_MATCHING,
    WAITING_CONFIRM,
    ACTION_EXECUTING,
    RESULT_VERIFYING,
    NEXT_PAGE_WAITING,
    ERROR_RECOVERING,
    FINISHED,
}

data class AutomationFlowTransition(
    val from: AutomationFlowState,
    val to: AutomationFlowState,
    val reason: String,
    val atMs: Long,
    val valid: Boolean,
)

class AutomationFlowStateMachine(
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    @Volatile
    private var state: AutomationFlowState = AutomationFlowState.IDLE

    @Volatile
    private var lastTransition = AutomationFlowTransition(
        from = AutomationFlowState.IDLE,
        to = AutomationFlowState.IDLE,
        reason = "初始化状态机。",
        atMs = nowProvider(),
        valid = true,
    )

    private val transitions: Map<AutomationFlowState, Set<AutomationFlowState>> = mapOf(
        AutomationFlowState.IDLE to setOf(
            AutomationFlowState.PAGE_DETECTING,
            AutomationFlowState.FINISHED,
            AutomationFlowState.ERROR_RECOVERING,
        ),
        AutomationFlowState.PAGE_DETECTING to setOf(
            AutomationFlowState.PAGE_CLASSIFIED,
            AutomationFlowState.ERROR_RECOVERING,
        ),
        AutomationFlowState.PAGE_CLASSIFIED to setOf(
            AutomationFlowState.QUESTION_PARSING,
            AutomationFlowState.ANSWER_MATCHING,
            AutomationFlowState.ACTION_EXECUTING,
            AutomationFlowState.NEXT_PAGE_WAITING,
            AutomationFlowState.ERROR_RECOVERING,
        ),
        AutomationFlowState.QUESTION_PARSING to setOf(
            AutomationFlowState.ANSWER_MATCHING,
            AutomationFlowState.WAITING_CONFIRM,
            AutomationFlowState.ERROR_RECOVERING,
        ),
        AutomationFlowState.ANSWER_MATCHING to setOf(
            AutomationFlowState.WAITING_CONFIRM,
            AutomationFlowState.ACTION_EXECUTING,
            AutomationFlowState.NEXT_PAGE_WAITING,
            AutomationFlowState.ERROR_RECOVERING,
            AutomationFlowState.FINISHED,
        ),
        AutomationFlowState.WAITING_CONFIRM to setOf(
            AutomationFlowState.ACTION_EXECUTING,
            AutomationFlowState.NEXT_PAGE_WAITING,
            AutomationFlowState.ERROR_RECOVERING,
            AutomationFlowState.FINISHED,
        ),
        AutomationFlowState.ACTION_EXECUTING to setOf(
            AutomationFlowState.RESULT_VERIFYING,
            AutomationFlowState.ERROR_RECOVERING,
        ),
        AutomationFlowState.RESULT_VERIFYING to setOf(
            AutomationFlowState.NEXT_PAGE_WAITING,
            AutomationFlowState.PAGE_DETECTING,
            AutomationFlowState.ERROR_RECOVERING,
            AutomationFlowState.FINISHED,
        ),
        AutomationFlowState.NEXT_PAGE_WAITING to setOf(
            AutomationFlowState.PAGE_DETECTING,
            AutomationFlowState.QUESTION_PARSING,
            AutomationFlowState.ACTION_EXECUTING,
            AutomationFlowState.ERROR_RECOVERING,
            AutomationFlowState.FINISHED,
        ),
        AutomationFlowState.ERROR_RECOVERING to setOf(
            AutomationFlowState.PAGE_DETECTING,
            AutomationFlowState.NEXT_PAGE_WAITING,
            AutomationFlowState.FINISHED,
            AutomationFlowState.IDLE,
        ),
        AutomationFlowState.FINISHED to setOf(
            AutomationFlowState.IDLE,
            AutomationFlowState.PAGE_DETECTING,
        ),
    )

    @Synchronized
    fun transitionTo(target: AutomationFlowState, reason: String): AutomationFlowTransition {
        val source = state
        val valid = transitions[source]?.contains(target) == true || source == target
        state = target
        val transition = AutomationFlowTransition(
            from = source,
            to = target,
            reason = reason,
            atMs = nowProvider(),
            valid = valid,
        )
        lastTransition = transition
        return transition
    }

    fun snapshot(): Map<String, Any?> {
        val stateName = state.name.lowercase(Locale.ROOT)
        return mapOf(
            "state" to stateName,
            "lastTransition" to mapOf(
                "from" to lastTransition.from.name.lowercase(Locale.ROOT),
                "to" to lastTransition.to.name.lowercase(Locale.ROOT),
                "reason" to lastTransition.reason,
                "atMs" to lastTransition.atMs,
                "valid" to lastTransition.valid,
            ),
        )
    }

    fun currentState(): AutomationFlowState {
        return state
    }

    fun reset(reason: String = "流程已重置。") {
        transitionTo(AutomationFlowState.IDLE, reason)
    }
}
