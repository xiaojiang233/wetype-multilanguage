package top.xiaojiang233.wetypemulti.common.engine

import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.dict.Entry
import top.xiaojiang233.wetypemulti.common.language.Language
import top.xiaojiang233.wetypemulti.common.language.Languages
import top.xiaojiang233.wetypemulti.common.predict.Predictor
import top.xiaojiang233.wetypemulti.common.romaji.Romaji

/**
 * Pure-Kotlin Japanese engine: romaji -> kana, dictionary candidates, learning and association.
 * Used when librime is unavailable, and as a reference implementation for tests.
 * Single-segment conversion only; sentence-level conversion is provided by Rime.
 */
class BuiltinJapaneseEngine(
    private val dictionary: Dictionary,
    private val predictor: Predictor = Predictor(dictionary),
    override val language: Language = Languages.JAPANESE,
) : InputEngine {
    private val buffer = StringBuilder()
    private var lastCommitted = ""

    override fun processKey(c: Char): Boolean {
        if (!(c.isLetter() && c.code < 128) && c !in "-',.?![]") return false
        if (c in ",.?![]-" && buffer.isEmpty()) return false // idle punctuation goes to the app
        buffer.append(c.lowercaseChar())
        return true
    }

    override fun backspace(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.setLength(buffer.length - 1); return true
    }

    private fun conv() = Romaji.convert(buffer.toString())

    override fun context(): Context {
        if (buffer.isEmpty()) return Context("", emptyList())
        val r = conv()
        // A trailing lone "n" is shown as typed, but already looked up as ん.
        val kana = if (r.pending == "n") r.kana + "ん" else r.kana
        val pending = if (r.pending == "n") "" else r.pending
        val out = LinkedHashMap<String, Candidate>()
        fun add(t: String, c: String = "") { if (t.isNotEmpty()) out.putIfAbsent(t, Candidate(t, c)) }
        if (pending.isEmpty()) {
            dictionary.lookup(kana).forEach { add(it.word) }
            add(kana, "ひらがな")
            add(Romaji.toKatakana(kana), "カタカナ")
            dictionary.prefix(kana, 8).forEach { add(it.word, it.reading) }
        } else {
            add(kana + pending)
        }
        return Context(r.kana + r.pending, out.values.toList())
    }

    override fun select(index: Int): String? {
        val c = context().candidates.getOrNull(index) ?: return null
        buffer.setLength(0)
        learn(c.text)
        return c.text
    }

    private fun learn(text: String) {
        predictor.learn(lastCommitted, text)
        lastCommitted = text
    }

    override fun commitRaw(): String {
        val t = Romaji.toKana(buffer.toString())
        buffer.setLength(0)
        learn(t)
        return t
    }

    override fun reset() { buffer.setLength(0); lastCommitted = "" }

    override fun suggest(committed: String) = predictor.suggest(committed)
}
