package com.example.flutter_answer.agent

import kotlin.math.max

object PageClassifier {

    const val HOME = "home_page"
    const val COURSE = "course_page"
    const val TASK_LIST = "task_list_page"
    const val HOMEWORK_DETAIL = "homework_detail_page"
    const val QUESTION = "question_page"
    const val VOTE = "vote_page"
    const val SUBMIT_CONFIRM_DIALOG = "submit_confirm_dialog"
    const val RESULT = "result_page"
    const val NETWORK_ERROR = "network_error_page"
    const val LOGIN_EXPIRED = "login_expired_page"
    const val PERMISSION_PROMPT = "permission_prompt_page"
    const val ABNORMAL_INTERRUPT = "abnormal_interrupt_page"
    const val LOADING = "loading_page"
    const val UNKNOWN = "unknown_page"

    private val questionKeywords = listOf("单选", "多选", "判断", "填空", "简答", "下一题", "提交", "第", "题")
    private val voteKeywords = listOf("投票", "票数", "投票项", "提交投票", "赞成", "反对")
    private val submitDialogKeywords = listOf("确认提交", "确认交卷", "是否提交", "确定提交", "提交后")
    private val resultKeywords = listOf("得分", "成绩", "正确率", "已提交", "答题完成", "通过", "未通过")
    private val networkErrorKeywords = listOf("网络错误", "网络异常", "连接失败", "请求超时", "加载失败", "点击重试")
    private val loginExpiredKeywords = listOf("重新登录", "登录过期", "登录失效", "会话失效", "身份过期")
    private val permissionKeywords = listOf("权限", "授权", "允许", "拒绝", "去设置", "无权限")
    private val abnormalKeywords = listOf("异常中断", "系统异常", "服务异常", "发生错误", "崩溃")
    private val loadingKeywords = listOf("加载中", "请稍候", "正在加载", "提交中", "处理中")
    private val taskListKeywords = listOf("任务", "待完成", "未完成", "任务列表", "待办")
    private val homeworkDetailKeywords = listOf("作业详情", "截止时间", "开始答题", "作业要求", "分值")
    private val courseKeywords = listOf("课程", "章节", "目录", "课堂", "班级")
    private val homeKeywords = listOf("首页", "我的", "消息", "学习", "推荐")

    fun classify(snapshot: UiSnapshot): PageClassification {
        if (snapshot.elements.isEmpty()) {
            return PageClassification(
                pageType = UNKNOWN,
                confidence = 0.0,
                reason = "页面节点为空，无法分类。",
                scores = mapOf(UNKNOWN to 0.0),
                signals = emptyMap(),
            )
        }

        val allTexts = snapshot.elements.flatMap { element ->
            listOfNotNull(element.text, element.desc)
        }.map(::normalizeText)
            .filter(String::isNotBlank)

        val mergedText = allTexts.joinToString(separator = " ")
        val screenHeight = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(3) ?: 0 } ?: 0
        val screenWidth = snapshot.elements.maxOfOrNull { it.bounds.getOrNull(2) ?: 0 } ?: 0
        val checkableCount = snapshot.elements.count { it.checkable }
        val editableCount = snapshot.elements.count { it.editable }
        val clickableCount = snapshot.elements.count { it.clickable }
        val webViewCount = snapshot.elements.count {
            it.className.orEmpty().contains("WebView", ignoreCase = true)
        }
        val optionLikeCount = snapshot.elements.count { element ->
            val text = normalizeText(element.text)
            element.checkable || optionPattern.containsMatchIn(text)
        }
        val bottomActionText = snapshot.elements
            .filter { element ->
                val top = element.bounds.getOrNull(1) ?: Int.MAX_VALUE
                val bottomArea = screenHeight > 0 && top >= screenHeight * 0.72
                bottomArea && element.clickable
            }
            .mapNotNull { element -> normalizeText(element.text).takeIf(String::isNotBlank) }
            .joinToString(separator = " ")

        val dialogSignal = detectDialogSignal(snapshot.elements, screenWidth, screenHeight)

        val scores = linkedMapOf(
            HOME to 0.0,
            COURSE to 0.0,
            TASK_LIST to 0.0,
            HOMEWORK_DETAIL to 0.0,
            QUESTION to 0.0,
            VOTE to 0.0,
            SUBMIT_CONFIRM_DIALOG to 0.0,
            RESULT to 0.0,
            NETWORK_ERROR to 0.0,
            LOGIN_EXPIRED to 0.0,
            PERMISSION_PROMPT to 0.0,
            ABNORMAL_INTERRUPT to 0.0,
            LOADING to 0.0,
        )

        accumulateKeywordScore(scores, HOME, mergedText, homeKeywords, 1.2)
        accumulateKeywordScore(scores, COURSE, mergedText, courseKeywords, 1.4)
        accumulateKeywordScore(scores, TASK_LIST, mergedText, taskListKeywords, 1.8)
        accumulateKeywordScore(scores, HOMEWORK_DETAIL, mergedText, homeworkDetailKeywords, 2.2)
        accumulateKeywordScore(scores, QUESTION, mergedText, questionKeywords, 1.9)
        accumulateKeywordScore(scores, VOTE, mergedText, voteKeywords, 2.2)
        accumulateKeywordScore(scores, SUBMIT_CONFIRM_DIALOG, mergedText, submitDialogKeywords, 2.5)
        accumulateKeywordScore(scores, RESULT, mergedText, resultKeywords, 2.3)
        accumulateKeywordScore(scores, NETWORK_ERROR, mergedText, networkErrorKeywords, 3.0)
        accumulateKeywordScore(scores, LOGIN_EXPIRED, mergedText, loginExpiredKeywords, 3.0)
        accumulateKeywordScore(scores, PERMISSION_PROMPT, mergedText, permissionKeywords, 2.7)
        accumulateKeywordScore(scores, ABNORMAL_INTERRUPT, mergedText, abnormalKeywords, 2.4)
        accumulateKeywordScore(scores, LOADING, mergedText, loadingKeywords, 2.3)

        if (optionLikeCount >= 2) {
            scores[QUESTION] = scores.getValue(QUESTION) + 2.3
        }
        if (checkableCount >= 2) {
            scores[QUESTION] = scores.getValue(QUESTION) + 1.8
        }
        if (editableCount >= 1) {
            scores[QUESTION] = scores.getValue(QUESTION) + 0.8
            scores[HOMEWORK_DETAIL] = scores.getValue(HOMEWORK_DETAIL) + 0.4
        }
        if (bottomActionText.contains("提交")) {
            scores[QUESTION] = scores.getValue(QUESTION) + 1.0
            scores[SUBMIT_CONFIRM_DIALOG] = scores.getValue(SUBMIT_CONFIRM_DIALOG) + 1.2
        }
        if (bottomActionText.contains("下一题") || bottomActionText.contains("下一步")) {
            scores[QUESTION] = scores.getValue(QUESTION) + 0.8
        }
        if (webViewCount > 0 && optionLikeCount > 0) {
            scores[QUESTION] = scores.getValue(QUESTION) + 0.7
        }
        if (dialogSignal > 0) {
            scores[SUBMIT_CONFIRM_DIALOG] = scores.getValue(SUBMIT_CONFIRM_DIALOG) + dialogSignal
        }

        if (containsAny(mergedText, networkErrorKeywords)) {
            scores[NETWORK_ERROR] = scores.getValue(NETWORK_ERROR) + 1.4
        }
        if (containsAny(mergedText, loginExpiredKeywords)) {
            scores[LOGIN_EXPIRED] = scores.getValue(LOGIN_EXPIRED) + 1.4
        }
        if (containsAny(mergedText, permissionKeywords) && containsAny(mergedText, listOf("允许", "拒绝", "去设置"))) {
            scores[PERMISSION_PROMPT] = scores.getValue(PERMISSION_PROMPT) + 1.3
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()
        val second = ranked.getOrNull(1)

        if (top == null || top.value < 1.6) {
            return PageClassification(
                pageType = UNKNOWN,
                confidence = 0.18,
                reason = "关键特征不足，按未知页面处理。",
                scores = scores.toSortedMap(),
                signals = buildSignals(
                    checkableCount = checkableCount,
                    editableCount = editableCount,
                    clickableCount = clickableCount,
                    optionLikeCount = optionLikeCount,
                    webViewCount = webViewCount,
                    dialogSignal = dialogSignal,
                    bottomActionText = bottomActionText,
                ),
            )
        }

        val topScore = top.value
        val secondScore = second?.value ?: 0.0
        val margin = max(0.0, topScore - secondScore)
        val confidence = (0.46 + margin / (topScore + 1.0)).coerceIn(0.2, 0.99)
        val reason = when (top.key) {
            SUBMIT_CONFIRM_DIALOG -> "命中确认提交文案，且弹窗结构得分较高。"
            NETWORK_ERROR -> "命中网络异常关键词并检测到重试类提示。"
            LOGIN_EXPIRED -> "命中登录失效关键词。"
            PERMISSION_PROMPT -> "命中权限提示关键词且存在授权操作文案。"
            QUESTION -> "检测到题目结构关键词/选项控件/底部答题操作区。"
            RESULT -> "命中成绩或提交完成关键词。"
            VOTE -> "命中投票相关文案与可选择项。"
            LOADING -> "命中加载中关键词。"
            else -> "多特征得分最高。"
        }

        return PageClassification(
            pageType = top.key,
            confidence = confidence,
            reason = reason,
            scores = ranked.associate { it.key to it.value },
            signals = buildSignals(
                checkableCount = checkableCount,
                editableCount = editableCount,
                clickableCount = clickableCount,
                optionLikeCount = optionLikeCount,
                webViewCount = webViewCount,
                dialogSignal = dialogSignal,
                bottomActionText = bottomActionText,
            ),
        )
    }

    private fun normalizeText(value: String?): String {
        return value
            ?.replace("\\s+".toRegex(), " ")
            ?.replace('：', ':')
            ?.trim()
            .orEmpty()
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        if (text.isBlank()) {
            return false
        }
        return keywords.any { keyword -> text.contains(keyword) }
    }

    private fun accumulateKeywordScore(
        scores: MutableMap<String, Double>,
        pageType: String,
        text: String,
        keywords: List<String>,
        unit: Double,
    ) {
        if (text.isBlank()) {
            return
        }
        val matched = keywords.count { keyword -> text.contains(keyword) }
        if (matched > 0) {
            scores[pageType] = scores.getValue(pageType) + (matched * unit)
        }
    }

    private fun detectDialogSignal(elements: List<UiElement>, width: Int, height: Int): Double {
        if (width <= 0 || height <= 0) {
            return 0.0
        }
        val centerLeft = width * 0.15
        val centerRight = width * 0.85
        val centerTop = height * 0.18
        val centerBottom = height * 0.82
        val centerTextCount = elements.count { element ->
            val bounds = element.bounds
            val left = bounds.getOrNull(0)?.toDouble() ?: return@count false
            val top = bounds.getOrNull(1)?.toDouble() ?: return@count false
            val right = bounds.getOrNull(2)?.toDouble() ?: return@count false
            val bottom = bounds.getOrNull(3)?.toDouble() ?: return@count false
            val inCenter = left >= centerLeft && right <= centerRight && top >= centerTop && bottom <= centerBottom
            inCenter && !normalizeText(element.text).isBlank()
        }
        if (centerTextCount >= 5) {
            return 1.6
        }
        if (centerTextCount >= 2) {
            return 0.8
        }
        return 0.0
    }

    private fun buildSignals(
        checkableCount: Int,
        editableCount: Int,
        clickableCount: Int,
        optionLikeCount: Int,
        webViewCount: Int,
        dialogSignal: Double,
        bottomActionText: String,
    ): Map<String, Any?> {
        return mapOf(
            "checkableCount" to checkableCount,
            "editableCount" to editableCount,
            "clickableCount" to clickableCount,
            "optionLikeCount" to optionLikeCount,
            "webViewCount" to webViewCount,
            "dialogSignal" to dialogSignal,
            "bottomActionText" to bottomActionText,
        )
    }

    private val optionPattern = Regex("^([A-Ha-h]|\\d{1,2}|[一二三四五六七八九十])[.、:：．\\)]")
}
