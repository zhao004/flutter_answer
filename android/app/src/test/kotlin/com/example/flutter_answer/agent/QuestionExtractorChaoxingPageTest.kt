package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionExtractorChaoxingPageTest {

    @Test
    fun `extract strips xuexitong option prefixes and keeps footer actions`() {
        val snapshot = UiSnapshot(
            screen = mapOf(
                "packageName" to "com.chaoxing.mobile",
                "timestamp" to 0L,
            ),
            elements = listOf(
                element(
                    id = "root",
                    className = "android.widget.FrameLayout",
                    bounds = listOf(0, 0, 1440, 3088),
                ),
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "1. 单选题",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 280, 1383, 512),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 508, 1383, 745),
                ),
                element(
                    id = "questionText",
                    text = "1+1等",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 236, 617),
                ),
                element(
                    id = "optionAContainer",
                    className = "android.view.View",
                    bounds = listOf(56, 741, 1383, 880),
                ),
                element(
                    id = "optionAText",
                    text = "选项A.1",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 745, 191, 880),
                ),
                element(
                    id = "optionBContainer",
                    className = "android.view.View",
                    bounds = listOf(56, 970, 1383, 1108),
                ),
                element(
                    id = "optionBText",
                    text = "选项B.2",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 973, 191, 1108),
                ),
                element(
                    id = "optionCContainer",
                    className = "android.view.View",
                    bounds = listOf(56, 1202, 1383, 1341),
                ),
                element(
                    id = "optionCText",
                    text = "选项C.3",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 1206, 191, 1341),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第1题",
                    className = "android.view.View",
                    bounds = listOf(517, 1487, 922, 1686),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 1487, 1327, 1686),
                ),
            ),
        )

        val context = QuestionExtractor.extract(snapshot)

        assertEquals("1+1等", context.question)
        assertEquals(listOf("A", "B", "C"), context.options.map(QuestionOption::label))
        assertEquals(listOf("1", "2", "3"), context.options.map(QuestionOption::text))
        assertTrue(context.actions.any { action -> action.kind == "next" && action.text.contains("下一题") })
        assertTrue(context.regions.any { region -> region.region == "footer_actions" })
        assertTrue(context.regions.any { region -> region.region == "question" })
        assertFalse(context.options.any { option -> option.text.startsWith("选项") })
    }

    private fun element(
        id: String,
        text: String? = null,
        className: String,
        bounds: List<Int>,
    ): UiElement {
        return UiElement(
            id = id,
            text = text,
            desc = null,
            className = className,
            packageName = "com.chaoxing.mobile",
            viewId = null,
            clickable = false,
            checkable = false,
            checked = false,
            editable = false,
            enabled = true,
            scrollable = false,
            bounds = bounds,
            depth = 0,
        )
    }
}
