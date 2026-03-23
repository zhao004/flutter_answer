package com.example.flutter_answer.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTextNormalizerTest {

    @Test
    fun `normalize removes zero width chars and trailing line breaks`() {
        assertEquals("Google", InputTextNormalizer.normalize("\u200BGoogle\n"))
    }

    @Test
    fun `normalize strips editor saved html wrapper`() {
        assertEquals("Google", InputTextNormalizer.normalize("已作答<p>Google<br></p>"))
    }

    @Test
    fun `normalize strips plain saved prefix and decodes html entities`() {
        assertEquals("Flutter & Dart", InputTextNormalizer.normalize("已作答Flutter&nbsp;&amp;&nbsp;Dart"))
    }

    @Test
    fun `normalize flattens complex rich text blocks`() {
        assertEquals(
            "第一段 第二项 <完成>",
            InputTextNormalizer.normalize("<div>第一段</div><ul><li>第二项</li></ul><p>&lt;完成&gt;</p>"),
        )
    }

    @Test
    fun `normalize collapses whitespace only placeholder`() {
        assertEquals("", InputTextNormalizer.normalize("\n\n  \t"))
    }
}
