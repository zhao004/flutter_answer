package com.example.flutter_answer.agent

internal object InputTextNormalizer {

    private val invisibleCharPattern = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val htmlWhitespaceEntityPattern = Regex("&(nbsp|ensp|emsp);", RegexOption.IGNORE_CASE)
    private val htmlAmpPattern = Regex("&amp;", RegexOption.IGNORE_CASE)
    private val htmlLtPattern = Regex("&lt;", RegexOption.IGNORE_CASE)
    private val htmlGtPattern = Regex("&gt;", RegexOption.IGNORE_CASE)
    private val htmlQuotePattern = Regex("&quot;", RegexOption.IGNORE_CASE)
    private val htmlAposPattern = Regex("&#39;|&apos;", RegexOption.IGNORE_CASE)
    private val htmlBreakPattern = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val htmlOpenDivPattern = Regex("<div[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlCloseDivPattern = Regex("</div>", RegexOption.IGNORE_CASE)
    private val htmlOpenParagraphPattern = Regex("<p[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlCloseParagraphPattern = Regex("</p>", RegexOption.IGNORE_CASE)
    private val htmlOpenListItemPattern = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val htmlCloseListItemPattern = Regex("</li>", RegexOption.IGNORE_CASE)
    private val htmlTagPattern = Regex("<[^>]+>")
    private val answeredPrefixPattern = Regex("^已作答(?:[:：\\s])?")

    /**
     * normalize 统一 WebView 编辑器回读文本，避免状态包装、HTML 实体和富文本标签导致写入校验误判。
     */
    fun normalize(value: String): String {
        if (value.isBlank()) {
            return ""
        }
        var normalized = value
            .replace(htmlWhitespaceEntityPattern, " ")
            .replace(htmlAmpPattern, "&")
            .replace(htmlQuotePattern, "\"")
            .replace(htmlAposPattern, "'")
            .replace(invisibleCharPattern, "")
            .replace(htmlBreakPattern, "\n")
            .replace(htmlOpenDivPattern, "")
            .replace(htmlCloseDivPattern, "\n")
            .replace(htmlOpenParagraphPattern, "")
            .replace(htmlCloseParagraphPattern, "\n")
            .replace(htmlOpenListItemPattern, "")
            .replace(htmlCloseListItemPattern, "\n")
            .replace(htmlTagPattern, " ")
            .replace(htmlLtPattern, "<")
            .replace(htmlGtPattern, ">")
            .replace("\\s+".toRegex(), " ")
            .trim()
        if (normalized.startsWith("已作答") && normalized.length > 3) {
            // 学习通保存后会把“已作答”拼到回读值前面；这里统一剥离状态包装，保留真正答案内容。
            val stripped = normalized.replace(answeredPrefixPattern, "").trim()
            if (stripped.isNotBlank()) {
                normalized = stripped
            }
        }
        return normalized
    }
}
