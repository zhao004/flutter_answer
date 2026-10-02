package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionExtractorChaoxingCoverageTest {

    @Test
    fun `extract strips combined question label on chaoxing multi select page`() {
        val context = QuestionExtractor.extract(multiSelectSnapshot())

        assertEquals("flutter可以开发哪些系统软件", context.question)
        assertEquals(listOf("A", "B", "C", "D"), context.options.map(QuestionOption::label))
        assertEquals(listOf("iOS", "Windows", "Android", "Linux"), context.options.map(QuestionOption::text))
        assertTrue(context.actions.any { action -> action.kind == "next" && action.text.contains("下一题") })
        assertFalse(context.question.contains("题干"))
        assertTrue((context.fieldConfidence["options"] ?: 0.0) > 0.8)
        assertFalse(context.needsVisual)
    }

    @Test
    fun `extract label-only webview options marks content missing and requires visual fallback`() {
        val context = QuestionExtractor.extract(labelOnlyOptionSnapshot())

        // 题干经过标点统一（。→ .）与空白压缩，这里按归一化后的完整文本校验。
        assertEquals(
            "计算机网络是由多台独立的计算机通过通信线路和通信设备连接而成的.( )",
            context.question,
        )
        assertFalse(context.question.contains("A"))
        assertEquals(listOf("A", "B"), context.options.map(QuestionOption::label))
        assertTrue(context.options.all { option -> option.contentMissing })
        assertEquals(
            listOf("optionAContainer", "optionBContainer"),
            context.options.map(QuestionOption::id),
        )
        assertTrue((context.fieldConfidence["options"] ?: 1.0) < 0.6)
        assertTrue(context.needsVisual)
    }

    @Test
    fun `extract selected suffix keeps option content and counts as observable state`() {
        val context = QuestionExtractor.extract(selectedSuffixOptionSnapshot())

        assertEquals(listOf("A", "B"), context.options.map(QuestionOption::label))
        assertEquals(listOf("对", "错"), context.options.map(QuestionOption::text))
        assertTrue(context.options.first { option -> option.label == "A" }.checked)
        assertTrue(QuestionExtractor.hasObservableOptionSelection(context.options))
        assertFalse(context.options.any { option -> option.contentMissing })
        assertFalse(context.needsVisual)
    }

    @Test
    fun `extract fill blank page keeps prompt clean and infers blank hint from label`() {
        val context = QuestionExtractor.extract(fillBlankSnapshot())

        assertEquals("flutter哪个企业发布的？", context.question)
        assertEquals(QuestionPageStage.question, context.pageStage)
        assertEquals(1, context.inputFields.size)
        assertEquals(listOf("第1空"), context.inputFields.map(InputFieldRef::hint))
        assertEquals(listOf("第1空"), context.blanks.map(QuestionBlank::hint))
        assertTrue(context.actions.any { action -> action.kind == "next" && action.text.contains("下一题") })
        assertTrue(context.regions.any { region -> region.region == "input" && "blankLabel" in region.elementIds })
        assertEquals(0.0, context.structureSignals["unansweredWarningSignal"])
        assertFalse(context.question.contains("请输入答案"))
        assertFalse(context.question.contains("未作答"))
    }

    @Test
    fun `extract multi blank page sorts inputs by visual order and binds hints`() {
        val context = QuestionExtractor.extract(multiBlankSnapshot())

        assertEquals("flutter使用哪种语言开发,并由哪个公司发布？", context.question)
        assertEquals(listOf("blankOneInput", "blankTwoInput"), context.inputFields.map(InputFieldRef::id))
        assertEquals(listOf("第1空", "第2空"), context.inputFields.map(InputFieldRef::hint))
        assertEquals(listOf(1, 2), context.inputFields.map(InputFieldRef::order))
        assertEquals(listOf("blankOneInput", "blankTwoInput"), context.blanks.map(QuestionBlank::inputId))
        assertFalse(context.question.contains("轻点两下来编辑"))
    }

    @Test
    fun `extract real five blank page from adb dump keeps all blanks aligned`() {
        val context = QuestionExtractor.extract(realFiveBlankSnapshot())

        assertEquals("flutter那个企业推出的？支持哪些平台的开发？正式版推出日期是什么时候？使用的什么编程语言？使用的编程语言初衷是什么？", context.question)
        assertEquals(listOf("realBlankOneInput", "realBlankTwoInput", "realBlankThreeInput", "realBlankFourInput", "realBlankFiveInput"), context.inputFields.map(InputFieldRef::id))
        assertEquals(listOf("第1空", "第2空", "第3空", "第4空", "第5空"), context.inputFields.map(InputFieldRef::hint))
        assertEquals(listOf(1, 2, 3, 4, 5), context.inputFields.map(InputFieldRef::order))
        assertEquals(listOf("realBlankOneInput", "realBlankTwoInput", "realBlankThreeInput", "realBlankFourInput", "realBlankFiveInput"), context.blanks.map(QuestionBlank::inputId))
        assertTrue(context.actions.any { action -> action.kind == "next" && action.text.contains("下一题") })
        assertTrue(context.regions.any { region -> region.region == "footer_actions" && "nextQuestion" in region.elementIds })
        assertFalse(context.question.contains("请输入答案"))
        assertFalse(context.question.contains("轻点两下来编辑"))
        assertTrue(context.options.isEmpty())
        assertTrue((context.fieldConfidence["inputs"] ?: 0.0) > 0.8)
        assertFalse(context.needsVisual)
    }

    @Test
    fun `extract final fill blank page with submit footer does not confuse input placeholder as submit warning`() {
        val context = QuestionExtractor.extract(finalFillBlankWithSubmitFooterSnapshot())

        assertEquals("flutter由哪个公司发布？", context.question)
        assertEquals(QuestionPageStage.question, context.pageStage)
        assertEquals(1, context.inputFields.size)
        assertEquals(listOf("第1空"), context.inputFields.map(InputFieldRef::hint))
        assertTrue(context.actions.any { action -> action.kind == "submit" && action.text == "提交" })
        assertEquals(1.0, context.structureSignals["finalQuestionSignal"])
        assertEquals(0.0, context.structureSignals["answeredSignal"])
        assertEquals(0.0, context.structureSignals["submitReadySignal"])
        assertEquals(0.0, context.structureSignals["submitWarningSignal"])
        assertEquals(0.0, context.structureSignals["unansweredWarningSignal"])
    }

    @Test
    fun `extract short answer page ignores editor helper and toolbar noise`() {
        val context = QuestionExtractor.extract(shortAnswerSnapshot())

        assertEquals("介绍一下flutter框架", context.question)
        assertEquals(QuestionPageStage.question, context.pageStage)
        assertEquals(1, context.inputFields.size)
        assertTrue(context.actions.any { action -> action.kind == "next" && action.text.contains("下一题") })
        assertTrue(context.regions.any { region -> region.region == "input" && "uploadIcon" in region.elementIds })
        assertEquals(0.0, context.structureSignals["unansweredWarningSignal"])
        assertFalse(context.question.contains("未作答"))
        assertFalse(context.question.contains("add"))
        assertFalse(context.question.contains("photo"))
        assertFalse(context.question.contains("voice"))
    }

    @Test
    fun `extract popup prefers centered submit action over masked footer submit`() {
        val context = QuestionExtractor.extract(submitPopupSnapshot())

        assertEquals("flutter是一个跨平台开发框架", context.question)
        assertTrue(context.regions.any { region -> region.region == "popup" })
        assertEquals(1, context.actions.count { action -> action.kind == "submit" })
        assertEquals("popupSubmitText", context.actions.first { action -> action.kind == "submit" }.id)
        assertTrue(context.actions.any { action -> action.kind == "cancel" && action.id == "popupCancelText" })
        assertFalse(context.actions.any { action -> action.id == "footerSubmitText" && action.kind == "submit" })
    }

    @Test
    fun `extract provides semantic next action when footer next node is zero height`() {
        val context = QuestionExtractor.extract(zeroHeightNextSnapshot())

        val nextAction = context.actions.firstOrNull { action -> action.kind == "next" }
        assertTrue(nextAction != null)
        assertEquals("action:next", nextAction?.id)
    }

    @Test
    fun `extract resume page marks continue answer as next without waiting confirm`() {
        val context = QuestionExtractor.extract(resumeEntrySnapshot())

        val nextAction = context.actions.firstOrNull { action -> action.kind == "next" }
        assertTrue(nextAction != null)
        assertEquals("continueAnswerButton", nextAction?.id)
        assertEquals(QuestionPageStage.resumeEntry, context.pageStage)
        assertEquals("", context.question)
        assertFalse(context.needConfirm)
        assertEquals(1.0, context.structureSignals["resumeEntrySignal"])
    }

    @Test
    fun `extract resume page uses semantic next when continue text node is non clickable`() {
        val context = QuestionExtractor.extract(resumeEntryZeroHeightActionSnapshot())

        val nextAction = context.actions.firstOrNull { action -> action.kind == "next" }
        assertTrue(nextAction != null)
        assertEquals("action:next", nextAction?.id)
        assertEquals(QuestionPageStage.resumeEntry, context.pageStage)
    }

    @Test
    fun `extract submit ready page on final answered question enables submit signal`() {
        val context = QuestionExtractor.extract(finalSubmitReadySnapshot())

        val submitAction = context.actions.firstOrNull { action -> action.kind == "submit" }
        assertTrue(submitAction != null)
        assertEquals("submitButton", submitAction?.id)
        assertEquals(QuestionPageStage.submitReady, context.pageStage)
        assertTrue(context.options.any { option -> option.label == "A" && option.text == "对" && option.checked })
        assertEquals(1.0, context.structureSignals["finalQuestionSignal"])
        assertEquals(1.0, context.structureSignals["answeredSignal"])
        assertEquals(1.0, context.structureSignals["submitReadySignal"])
        assertFalse(context.needConfirm)
    }

    @Test
    fun `extract submit confirm dialog without warning marks dialog signal`() {
        val context = QuestionExtractor.extract(submitConfirmDialogSnapshot())

        assertEquals("", context.question)
        assertEquals(QuestionPageStage.submitDialog, context.pageStage)
        assertTrue(context.actions.any { action -> action.kind == "submit" || action.kind == "confirm" })
        assertTrue(context.actions.any { action -> action.kind == "cancel" })
        assertEquals(1.0, context.structureSignals["submitDialogSignal"])
        assertEquals(0.0, context.structureSignals["unansweredWarningSignal"])
        assertFalse(context.needConfirm)
    }

    @Test
    fun `extract submit confirm dialog with unanswered warning blocks auto confirm signal`() {
        val context = QuestionExtractor.extract(submitConfirmDialogWithWarningSnapshot())

        assertEquals(QuestionPageStage.submitWarning, context.pageStage)
        assertEquals(1.0, context.structureSignals["submitWarningSignal"])
        assertEquals(1.0, context.structureSignals["unansweredWarningSignal"])
        assertEquals(0.0, context.structureSignals["submitDialogSignal"])
        assertFalse(context.needConfirm)
    }

    /**
     * 与真机（2026-10，学习通 WebView）一致的裸标签题型：
     * 题干可读，选项只暴露 A/B 标签，“对/错”正文仅存在于视觉层，
     * 可点击的整行容器是标签的父节点。
     */
    private fun labelOnlyOptionSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "questionText",
                    text = "计算机网络是由多台独立的计算机通过通信线路和通信设备连接而成的。(        )",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 545, 1361, 721),
                ),
                element(
                    id = "optionAContainer",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(56, 826, 1383, 965),
                ),
                element(
                    id = "optionALabel",
                    text = "A",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 830, 191, 965),
                    parentId = "optionAContainer",
                ),
                element(
                    id = "optionBContainer",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(56, 1055, 1383, 1193),
                ),
                element(
                    id = "optionBLabel",
                    text = "B",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 1058, 191, 1193),
                    parentId = "optionBContainer",
                ),
                element(
                    id = "prevQuestion",
                    text = " 上一题",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(112, 1343, 521, 1538),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 ",
                    className = "android.widget.TextView",
                    bounds = listOf(641, 1445, 798, 1508),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题 ",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(918, 1343, 1327, 1538),
                ),
            ),
        )
    }

    /**
     * 旧版学习通题型：选项正文直接进入无障碍树，选中态以“已选择”后缀表达。
     */
    private fun selectedSuffixOptionSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "questionType",
                    text = "5. 判断题",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 280, 1383, 512),
                ),
                element(
                    id = "questionText",
                    text = "计算机网络是由多台独立的计算机通过通信线路和通信设备连接而成的。",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 885, 617),
                ),
                element(
                    id = "optionAText",
                    text = "A.对已选择",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 745, 191, 880),
                ),
                element(
                    id = "optionBText",
                    text = "B.错",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 973, 191, 1108),
                ),
                element(
                    id = "prevQuestion",
                    text = " 上一题",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(112, 1258, 521, 1457),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第5题",
                    className = "android.view.View",
                    bounds = listOf(517, 1258, 922, 1457),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(918, 1258, 1327, 1457),
                ),
            ),
        )
    }

    private fun multiSelectSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "2. 多选题",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 280, 1383, 512),
                ),
                element(
                    id = "questionGroup",
                    text = "题干 flutter可以开发哪些系统软件",
                    className = "android.view.View",
                    bounds = listOf(56, 508, 1383, 745),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 508, 1383, 745),
                ),
                element(
                    id = "questionText",
                    text = "flutter可以开发哪些系统软件",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 885, 617),
                ),
                element(
                    id = "optionAText",
                    text = "选项A.iOS",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 760, 191, 895),
                ),
                element(
                    id = "optionBText",
                    text = "选项B.Windows",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 1003, 260, 1142),
                ),
                element(
                    id = "optionCText",
                    text = "选项C.Android",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 1251, 286, 1386),
                ),
                element(
                    id = "optionDText",
                    text = "选项D.Linux",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 1498, 248, 1633),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第2题",
                    className = "android.view.View",
                    bounds = listOf(517, 1780, 922, 1978),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 1780, 1327, 1978),
                ),
            ),
        )
    }

    private fun fillBlankSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "3. 填空题",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 280, 1387, 512),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 508, 1387, 745),
                ),
                element(
                    id = "questionText",
                    text = "flutter哪个企业发布的？",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 538, 750, 617),
                ),
                element(
                    id = "blankLabel",
                    text = "第1空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 741, 220, 906),
                ),
                element(
                    id = "editorHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer1",
                    bounds = listOf(52, 906, 1387, 1135),
                ),
                element(
                    id = "blankInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 925, 1368, 1078),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第3题",
                    className = "android.view.View",
                    bounds = listOf(517, 1288, 922, 1483),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 1288, 1327, 1483),
                ),
            ),
        )
    }

    private fun shortAnswerSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "4. 简答题",
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
                    text = "介绍一下flutter框架",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 622, 617),
                ),
                element(
                    id = "essayHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.widget.TextView",
                    viewId = "answerAria",
                    bounds = listOf(56, 801, 1383, 1555),
                ),
                element(
                    id = "essayInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 820, 1365, 1498),
                ),
                element(
                    id = "uploadIcon",
                    text = "add",
                    desc = "上传附件",
                    className = "android.widget.Image",
                    bounds = listOf(56, 1667, 142, 1753),
                ),
                element(
                    id = "photoIcon",
                    text = "photo",
                    desc = "拍照按钮",
                    className = "android.widget.Image",
                    bounds = listOf(285, 1667, 371, 1753),
                ),
                element(
                    id = "voiceIcon",
                    text = "voice",
                    desc = "录音按钮",
                    className = "android.widget.Image",
                    bounds = listOf(513, 1667, 600, 1753),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第4题",
                    className = "android.view.View",
                    bounds = listOf(517, 1956, 922, 2155),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 1956, 1327, 2155),
                ),
            ),
        )
    }

    private fun multiBlankSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "6. 填空题",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 280, 1387, 512),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 508, 1387, 745),
                ),
                element(
                    id = "questionText",
                    text = "flutter使用哪种语言开发，并由哪个公司发布？",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 538, 1260, 650),
                ),
                element(
                    id = "blankTwoLabel",
                    text = "第2空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 1110, 220, 1190),
                ),
                element(
                    id = "blankTwoHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer2",
                    bounds = listOf(52, 1190, 1387, 1415),
                ),
                element(
                    id = "blankTwoInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_1",
                    editable = true,
                    bounds = listOf(75, 1205, 1368, 1360),
                ),
                element(
                    id = "blankOneLabel",
                    text = "第1空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 760, 220, 840),
                ),
                element(
                    id = "blankOneHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer1",
                    bounds = listOf(52, 840, 1387, 1065),
                ),
                element(
                    id = "blankOneInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 855, 1368, 1010),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共6题 第6题",
                    className = "android.view.View",
                    bounds = listOf(517, 1588, 922, 1783),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 1588, 1327, 1783),
                ),
            ),
        )
    }

    private fun realFiveBlankSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "作业a",
                    className = "android.widget.TextView",
                    bounds = listOf(634, 142, 805, 237),
                ),
                element(
                    id = "questionType",
                    text = "1. 填空题",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 280, 1387, 291),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 287, 1387, 718),
                ),
                element(
                    id = "questionText",
                    text = "flutter那个企业推出的？支持哪些平台的开发？正式版推出日期是什么时候？使用的什么编程语言？使用的编程语言初衷是什么？",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 317, 1361, 595),
                ),
                element(
                    id = "blankOneLabel",
                    text = "第1空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 715, 1387, 883),
                ),
                element(
                    id = "blankOneHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer1",
                    bounds = listOf(52, 880, 1387, 1108),
                ),
                element(
                    id = "realBlankOneInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 898, 1368, 1056),
                ),
                element(
                    id = "blankTwoLabel",
                    text = "第2空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 1108, 1387, 1277),
                ),
                element(
                    id = "blankTwoHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer2",
                    bounds = listOf(52, 1273, 1387, 1506),
                ),
                element(
                    id = "realBlankTwoInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_1",
                    editable = true,
                    bounds = listOf(75, 1292, 1368, 1450),
                ),
                element(
                    id = "blankThreeLabel",
                    text = "第3空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 1502, 1387, 1671),
                ),
                element(
                    id = "blankThreeHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer3",
                    bounds = listOf(52, 1671, 1387, 1900),
                ),
                element(
                    id = "realBlankThreeInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_2",
                    editable = true,
                    bounds = listOf(75, 1686, 1368, 1843),
                ),
                element(
                    id = "blankFourLabel",
                    text = "第4空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 1896, 1387, 2065),
                ),
                element(
                    id = "blankFourHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer4",
                    bounds = listOf(52, 2065, 1387, 2293),
                ),
                element(
                    id = "realBlankFourInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_3",
                    editable = true,
                    bounds = listOf(75, 2083, 1368, 2237),
                ),
                element(
                    id = "blankFiveLabel",
                    text = "第5空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 2293, 1387, 2458),
                ),
                element(
                    id = "blankFiveHelper",
                    text = "未作答轻点两下来编辑",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer5",
                    bounds = listOf(52, 2458, 1387, 2687),
                ),
                element(
                    id = "realBlankFiveInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_4",
                    editable = true,
                    bounds = listOf(75, 2477, 1368, 2631),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第1题",
                    className = "android.view.View",
                    bounds = listOf(517, 2841, 922, 3032),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 2841, 1327, 3032),
                ),
            ),
        )
    }

    private fun submitPopupSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "toolbarTitle",
                    text = "题目3",
                    className = "android.widget.TextView",
                    bounds = listOf(638, 142, 802, 237),
                ),
                element(
                    id = "questionType",
                    text = "5. 判断题",
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
                    text = "flutter是一个跨平台开发框架",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 885, 617),
                ),
                element(
                    id = "optionAText",
                    text = "A.对",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 745, 191, 880),
                ),
                element(
                    id = "optionBText",
                    text = "B.错",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 973, 191, 1108),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第5题",
                    className = "android.view.View",
                    bounds = listOf(517, 1258, 922, 1457),
                ),
                element(
                    id = "footerSubmitText",
                    text = "提交",
                    className = "android.view.View",
                    bounds = listOf(918, 1258, 1327, 1457),
                ),
                element(
                    id = "popupMessage",
                    text = "您还有没做完的试题，提交将影响成绩！",
                    className = "android.widget.TextView",
                    bounds = listOf(315, 1105, 1125, 1416),
                ),
                element(
                    id = "popupCancelText",
                    text = "取消",
                    className = "android.widget.TextView",
                    bounds = listOf(198, 1416, 720, 1592),
                ),
                element(
                    id = "popupSubmitText",
                    text = "提交",
                    className = "android.widget.TextView",
                    bounds = listOf(720, 1416, 1241, 1592),
                ),
            ),
        )
    }

    private fun finalFillBlankWithSubmitFooterSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "questionType",
                    text = "5. 填空题",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 280, 1387, 512),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 508, 1387, 745),
                ),
                element(
                    id = "questionText",
                    text = "flutter由哪个公司发布？",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 538, 980, 617),
                ),
                element(
                    id = "blankLabel",
                    text = "第1空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 741, 220, 906),
                ),
                element(
                    id = "blankHelper",
                    text = "未填写答案",
                    className = "android.view.View",
                    viewId = "ananas-editor-answer1",
                    bounds = listOf(52, 906, 1387, 1135),
                ),
                element(
                    id = "blankInput",
                    text = "",
                    desc = "未填写答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 925, 1368, 1078),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第5题",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(517, 1288, 922, 1483),
                ),
                element(
                    id = "submitButton",
                    text = "提交",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(918, 1288, 1327, 1483),
                ),
            ),
        )
    }

    private fun zeroHeightNextSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "questionType",
                    text = "1. 填空题",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 280, 1387, 512),
                ),
                element(
                    id = "questionLabel",
                    text = "题干",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 508, 1387, 745),
                ),
                element(
                    id = "questionText",
                    text = "flutter由哪个企业推出？",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 538, 980, 617),
                ),
                element(
                    id = "blankLabel",
                    text = "第1空",
                    className = "android.widget.TextView",
                    bounds = listOf(52, 741, 220, 906),
                ),
                element(
                    id = "blankInput",
                    text = "请输入答案",
                    className = "android.widget.EditText",
                    viewId = "ueditor_0",
                    editable = true,
                    bounds = listOf(75, 925, 1368, 1078),
                ),
                element(
                    id = "nextQuestion",
                    text = "下一题",
                    className = "android.view.View",
                    bounds = listOf(918, 3032, 1327, 3032),
                ),
            ),
        )
    }

    private fun resumeEntrySnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "startTime",
                    text = "开始时间03月23日11时01分00秒",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 340, 1406, 415),
                ),
                element(
                    id = "endTime",
                    text = "截止时间07月23日12时01分00秒",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 415, 1406, 493),
                ),
                element(
                    id = "summary",
                    text = "共包含5道题目，其中单选题1道，多选题1道，填空题1道，判断题1道，简答题1道。",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 523, 1406, 700),
                ),
                element(
                    id = "continueAnswerButton",
                    text = "继续答题",
                    className = "android.widget.Button",
                    clickable = true,
                    bounds = listOf(33, 913, 1406, 1067),
                ),
            ),
        )
    }

    private fun resumeEntryZeroHeightActionSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "startTime",
                    text = "开始时间03月23日11时01分00秒",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 340, 1406, 415),
                ),
                element(
                    id = "endTime",
                    text = "截止时间07月23日12时01分00秒",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 415, 1406, 493),
                ),
                element(
                    id = "summary",
                    text = "共包含5道题目，其中单选题1道，多选题1道，填空题1道，判断题1道，简答题1道。",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 523, 1406, 700),
                ),
                element(
                    id = "continueAnswerText",
                    text = "继续答题",
                    className = "android.widget.TextView",
                    bounds = listOf(33, 990, 1406, 990),
                ),
            ),
        )
    }

    private fun finalSubmitReadySnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "questionType",
                    text = "5. 判断题",
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
                    text = "flutter是Google推出的跨平台开发框架。",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 538, 1222, 617),
                ),
                element(
                    id = "optionA",
                    text = "A.对已选择",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 745, 191, 880),
                ),
                element(
                    id = "optionB",
                    text = "B.错",
                    className = "android.widget.TextView",
                    bounds = listOf(56, 973, 191, 1108),
                ),
                element(
                    id = "answerCard",
                    text = "答题卡 共5题 第5题",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(517, 1258, 922, 1457),
                ),
                element(
                    id = "submitButton",
                    text = "提交",
                    className = "android.view.View",
                    clickable = true,
                    bounds = listOf(918, 1258, 1327, 1457),
                ),
            ),
        )
    }

    private fun submitConfirmDialogSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "submitDialogText",
                    text = "确认提交后将无法修改，是否提交？",
                    className = "android.widget.TextView",
                    bounds = listOf(260, 940, 1180, 1180),
                ),
                element(
                    id = "dialogConfirm",
                    text = "确认提交",
                    className = "android.widget.TextView",
                    bounds = listOf(782, 1250, 1080, 1350),
                ),
                element(
                    id = "dialogCancel",
                    text = "取消",
                    className = "android.widget.TextView",
                    bounds = listOf(350, 1250, 650, 1350),
                ),
            ),
        )
    }

    private fun submitConfirmDialogWithWarningSnapshot(): UiSnapshot {
        return chaoxingSnapshot(
            listOf(
                element(
                    id = "warningText",
                    text = "你还有1道题未作答，确定提交吗？",
                    className = "android.widget.TextView",
                    bounds = listOf(260, 940, 1180, 1180),
                ),
                element(
                    id = "dialogConfirm",
                    text = "确认提交",
                    className = "android.widget.TextView",
                    bounds = listOf(782, 1250, 1080, 1350),
                ),
                element(
                    id = "dialogCancel",
                    text = "取消",
                    className = "android.widget.TextView",
                    bounds = listOf(350, 1250, 650, 1350),
                ),
            ),
        )
    }

    private fun chaoxingSnapshot(elements: List<UiElement>): UiSnapshot {
        return UiSnapshot(
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
            ) + elements,
        )
    }

    private fun element(
        id: String,
        text: String? = null,
        desc: String? = null,
        className: String,
        viewId: String? = null,
        editable: Boolean = false,
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        parentId: String? = null,
        bounds: List<Int>,
    ): UiElement {
        return UiElement(
            id = id,
            text = text,
            desc = desc,
            className = className,
            packageName = "com.chaoxing.mobile",
            viewId = viewId,
            clickable = clickable,
            checkable = checkable,
            checked = checked,
            editable = editable,
            enabled = true,
            scrollable = false,
            bounds = bounds,
            depth = 0,
            parentId = parentId,
        )
    }
}
