package top.xiaojiang233.wetypemulti.common.layout

/**
 * Characters WeType emits from its Chinese tables while Japanese is on, rewritten to the Japanese form:
 *  - the comma key types the full-width Chinese comma; Japanese text uses the ideographic comma `、`,
 *  - the Chinese curly quotes from a key's long press or slide-up become `「` and `」`,
 *  - the full-width tilde becomes `〜`,
 *  - a lone hyphen (the long vowel's slide-up) becomes the katakana long vowel mark `ー`.
 * It is applied where text reaches the editor, so keys, candidates' follow-up text and long presses all get it.
 * Only the characters listed here change; a longer text containing a hyphen is left alone.
 */
object JaPunctuation {
    private val OUTPUT = mapOf('，' to '、', '“' to '「', '”' to '」', '～' to '〜')

    fun toJapanese(text: String): String = when {
        text == "-" -> "ー"
        text.none { it in OUTPUT } -> text
        else -> buildString(text.length) { text.forEach { append(OUTPUT[it] ?: it) } }
    }
}
