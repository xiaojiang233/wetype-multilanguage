package top.xiaojiang233.wetypemulti.common

import top.xiaojiang233.wetypemulti.common.romaji.JaPreedit
import top.xiaojiang233.wetypemulti.common.romaji.Romaji
import kotlin.test.Test
import kotlin.test.assertEquals

class JaPreeditTest {
    @Test
    fun rimesSyllableDelimitersDisappearAndTheReadingIsKana() {
        assertEquals("わたしはちゅごくじんです", JaPreedit.display("watashi'ha'chu'goku'jin'desu"))
        assertEquals("こんにちは", JaPreedit.display("ko'n'ni'chi'ha"))
        assertEquals("かんぱい", JaPreedit.display("ka'n'pa'i"))
    }

    @Test
    fun textChosenFromTheCandidatesIsKeptAndOnlyTheRestBecomesKana() {
        assertEquals("私はがくせい", JaPreedit.display("私ha'gakusei"))
        assertEquals("日本語を", JaPreedit.display("日本語を"))
    }

    @Test
    fun theTypistsApostropheAfterNStillMeansN() {
        assertEquals("かんい", JaPreedit.display("kan'i"))
        assertEquals("かに", JaPreedit.display("kani"))
        assertEquals("かん", JaPreedit.display("kan'"))
    }

    @Test
    fun theLongVowelHyphenIsTheLongVowelMark() {
        assertEquals("らーめんです", JaPreedit.display("ra-men'desu"))
        assertEquals("らーめn", JaPreedit.display("ra-men")) // the last n may still become na, ni ... so it stays as typed
        assertEquals("らーめん", JaPreedit.display("ra-menn"))
        assertEquals("ー", JaPreedit.display("-"))
        assertEquals("こーひー", JaPreedit.display("ko'-'hi'-"))
    }

    @Test
    fun anUnfinishedTailStaysAsTyped() {
        assertEquals("かn", JaPreedit.display("kan"))
        assertEquals("わたしsh", JaPreedit.display("wata'shi'sh"))
        assertEquals("", JaPreedit.display(""))
    }

    @Test
    fun doubledConsonantsAndTchMakeTheSmallTsu() {
        assertEquals("きって", JaPreedit.display("kitte"))
        assertEquals("まっちゃ", JaPreedit.display("matcha"))
        assertEquals("まっちゃ", Romaji.convert("matcha").kana, "Romaji.convert agrees")
    }

    @Test
    fun uppercaseIsReadLikeLowercase() {
        assertEquals("わたし", JaPreedit.display("Watashi"))
    }
}
