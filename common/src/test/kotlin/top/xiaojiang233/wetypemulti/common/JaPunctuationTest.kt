package top.xiaojiang233.wetypemulti.common

import top.xiaojiang233.wetypemulti.common.layout.JaPunctuation
import kotlin.test.Test
import kotlin.test.assertEquals

class JaPunctuationTest {
    @Test
    fun chineseCommaBecomesIdeographicComma() {
        assertEquals("、", JaPunctuation.toJapanese("，"))
        assertEquals("日本、", JaPunctuation.toJapanese("日本，"))
        assertEquals("a、b", JaPunctuation.toJapanese("a，b"))
    }

    @Test
    fun chineseQuotesAndTildeBecomeJapaneseForms() {
        assertEquals("「", JaPunctuation.toJapanese("“"))
        assertEquals("」", JaPunctuation.toJapanese("”"))
        assertEquals("〜", JaPunctuation.toJapanese("～"))
    }

    @Test
    fun loneHyphenBecomesTheLongVowelMark() {
        assertEquals("ー", JaPunctuation.toJapanese("-"))
        assertEquals("a-b", JaPunctuation.toJapanese("a-b"))
    }

    @Test
    fun otherPunctuationIsUnchanged() {
        for (s in listOf("。", "「", "」", "〜", "、", "？", "！", "ー", "", "abc")) {
            assertEquals(s, JaPunctuation.toJapanese(s))
        }
    }
}
