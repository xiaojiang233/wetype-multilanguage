package top.xiaojiang233.wetypemulti.common.romaji

/**
 * What the composition line shows while Japanese is typed: the reading in kana, the way Japanese IMEs show it (Mozc,
 * Google, Microsoft), not Rime's raw spelling with a delimiter between its syllables ("watashi'ha'chu'goku'jin'desu").
 *
 * Rime's preedit is the text of the segments already chosen from the candidate list, followed by the spelling of what is
 * left. The chosen text is kept as it is; the spelling is converted to kana, and the part the kana cannot yet complete (a
 * trailing "k", "sh", "n") stays as typed, as it does in those IMEs.
 */
object JaPreedit {
    fun display(preedit: String): String {
        val start = preedit.indexOfFirst { isSpelling(it) }
        if (start < 0) return preedit
        val reading = Romaji.convert(withoutDelimiters(preedit.substring(start)))
        return preedit.substring(0, start) + reading.kana + reading.pending
    }

    private fun isSpelling(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '\'' || c == '-'

    /**
     * Rime puts an apostrophe between the syllables it found; Japanese romaji has no such mark, except that "n'" is ん before
     * a vowel or y ("kan'i" is かんい, "kani" is かに). An apostrophe after n and before a vowel (or at the end) cannot have
     * come from Rime, which would have read "na" as one syllable, so it is the typist's and stays. All others go.
     */
    private fun withoutDelimiters(spelling: String): String = buildString(spelling.length) {
        for ((i, c) in spelling.withIndex()) {
            if (c != '\'') { append(c); continue }
            val after = spelling.getOrNull(i + 1)
            if (i > 0 && spelling[i - 1] == 'n' && (after == null || after.lowercaseChar() in "aiueoy")) append(c)
        }
    }
}
