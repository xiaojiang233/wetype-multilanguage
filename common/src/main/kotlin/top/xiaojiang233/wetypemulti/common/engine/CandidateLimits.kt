package top.xiaojiang233.wetypemulti.common.engine

/** Limits applied to every candidate list before it reaches an IME UI (WeType's bar or our own). */
object CandidateLimits {
    const val MAX_CANDIDATES = 64
    const val MAX_TEXT_CHARS = 64

    /** Drop blanks, de-duplicate keeping order, clip overlong text (never mid surrogate pair), cap the count. */
    fun sanitize(texts: List<String>, max: Int = MAX_CANDIDATES): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(minOf(texts.size, max))
        for (raw in texts) {
            if (out.size >= max) break
            val t = clip(raw.trim())
            if (t.isNotEmpty() && seen.add(t)) out.add(t)
        }
        return out
    }

    fun clip(s: String, maxChars: Int = MAX_TEXT_CHARS): String {
        if (s.length <= maxChars) return s
        var end = maxChars
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }
}
