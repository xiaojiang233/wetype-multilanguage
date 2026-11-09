package top.xiaojiang233.wetypemulti.common

import top.xiaojiang233.wetypemulti.common.dict.*
import top.xiaojiang233.wetypemulti.common.engine.*
import top.xiaojiang233.wetypemulti.common.language.Languages
import top.xiaojiang233.wetypemulti.common.romaji.Romaji
import java.io.StringReader
import kotlin.test.*

class CommonTest {
    @Test fun romajiBasics() {
        assertEquals("こんにちは", Romaji.toKana("konnichiha"))
        assertEquals("がっこう", Romaji.toKana("gakkou"))
        assertEquals("きょう", Romaji.toKana("kyou"))
        assertEquals("にほんご", Romaji.toKana("nihongo"))
        assertEquals("ほん", Romaji.toKana("hon"))
        assertEquals("かんい", Romaji.toKana("kan'i"))
        assertEquals("し", Romaji.toKana("si"))
        assertEquals(Romaji.Result("か", "k"), Romaji.convert("kak"))
        assertEquals(Romaji.Result("", "n"), Romaji.convert("n"))
    }

    @Test fun romajiRoundTrip() {
        for (w in listOf("konnichiha", "gakkou", "nihongo", "arigatou", "kyou", "nyuuryoku", "kan'i", "shinbun"))
            assertEquals(Romaji.toKana(w), Romaji.toKana(Romaji.toRomaji(Romaji.toKana(w))), w)
        assertEquals("gakkou", Romaji.toRomaji("がっこう"))
    }

    @Test fun importFormats() {
        val tsv = DictImporter.import(StringReader("# c\nにほん\t日本\t10\n"))
        assertEquals(listOf(Entry("にほん", "日本", 10)), tsv)
        val mozc = DictImporter.import(StringReader("にほん\t1\t1\t3000\t日本\n"))
        assertEquals("日本", mozc.single().word); assertEquals(7000, mozc.single().weight)
        val rime = DictImporter.import(StringReader("---\nname: x\n...\n日本\tnihon\t5\n"))
        assertEquals(Entry("にほん", "日本", 5), rime.single())
        val out = DictImporter.toRimeDict("x", rime)
        assertEquals(rime, DictImporter.import(StringReader(out)))
    }

    private fun engine(): BuiltinJapaneseEngine {
        val d = Dictionary()
        d.addAll(DictImporter.import(StringReader(
            "にほん\t日本\t9\nにほんご\t日本語\t8\nにほんじん\t日本人\t7\nきょう\t今日\t5\n")))
        return BuiltinJapaneseEngine(d)
    }

    @Test fun engineConvertsAndSuggests() {
        val e = engine()
        "nihon".forEach { assertTrue(e.processKey(it)) }
        val ctx = e.context()
        assertEquals("にほn", ctx.preedit)
        assertEquals(listOf("日本", "にほん", "ニホン", "日本語", "日本人"), ctx.candidates.map { it.text })
        assertEquals("日本", e.select(0))
        assertEquals(listOf("語", "人"), e.suggest("日本"))
        assertEquals("", e.context().preedit)
    }

    @Test fun selectOutOfRangeIsHarmless() {
        val e = engine()
        "nihon".forEach { e.processKey(it) }
        assertNull(e.select(-1)); assertNull(e.select(999))
        assertEquals("にほn", e.context().preedit) // composition untouched
        assertEquals("日本", e.select(0))
    }

    @Test fun learnedBigramRanksFirst() {
        val e = engine()
        "kyou".forEach { e.processKey(it) }; e.select(0)
        "nihon".forEach { e.processKey(it) }; e.select(0)
        assertEquals("日本", e.suggest("今日")[0])
    }

    @Test fun backspaceAndRaw() {
        val e = engine()
        "kyoux".forEach { e.processKey(it) }
        assertTrue(e.backspace())
        assertEquals("きょう", e.context().preedit)
        assertEquals("きょう", e.commitRaw())
        assertFalse(e.processKey('.'))
    }

    @Test fun languages() {
        assertEquals(Languages.JAPANESE, Languages.byId("ja"))
        assertEquals(Languages.JAPANESE, Languages.next(Languages.JAPANESE))
    }

    @Test fun rimeAdapter() {
        val b = object : RimeBridge {
            var commit: String? = null
            override fun initialize(sharedDataDir: String, userDataDir: String, appName: String) {}
            override fun deploy() = true
            override fun createSession() = 1L
            override fun destroySession(session: Long) {}
            override fun selectSchema(session: Long, schemaId: String) = schemaId == "japanese"
            override fun processKey(session: Long, keycode: Int, mask: Int) = true
            override fun context(session: Long) = Context("a", listOf(Candidate("あ")))
            override fun selectCandidate(session: Long, index: Int): Boolean { commit = "あ"; return true }
            override fun takeCommit(session: Long) = commit.also { commit = null }
            override fun clearComposition(session: Long) {}
            override fun finalizeRime() {}
        }
        val e = RimeInputEngine(b, Languages.JAPANESE) { listOf("x") }
        assertEquals("あ", e.select(0)); assertEquals(listOf("x"), e.suggest("あ"))
    }

    @Test fun candidateLimits() {
        assertEquals(listOf("日本", "日本語"), CandidateLimits.sanitize(listOf("日本", " ", "日本", "", "日本語")))
        assertEquals(64, CandidateLimits.sanitize((1..500).map { "w$it" }).size)
        assertEquals(3, CandidateLimits.sanitize((1..500).map { "w$it" }, 3).size)
        val long = "a".repeat(63) + "\uD83D\uDE00" + "b" // emoji straddles the 64-char cut
        val clipped = CandidateLimits.clip(long)
        assertEquals(63, clipped.length)
        assertFalse(Character.isHighSurrogate(CandidateLimits.sanitize(listOf(long)).single().last()))
    }
}
