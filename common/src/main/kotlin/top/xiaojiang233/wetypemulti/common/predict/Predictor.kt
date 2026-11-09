package top.xiaojiang233.wetypemulti.common.predict

import top.xiaojiang233.wetypemulti.common.dict.Dictionary

/**
 * Suggestion ("联想") after a commit. Combines
 *  1. learned bigrams (what the user typed after the previous word), and
 *  2. dictionary words that extend the committed text (e.g. 日本 -> 日本語, 日本人).
 */
class Predictor(private val dictionary: Dictionary) {
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    fun learn(previous: String, next: String) {
        if (previous.isEmpty() || next.isEmpty()) return
        val m = bigrams.getOrPut(previous) { HashMap() }
        m[next] = (m[next] ?: 0) + 1
    }

    fun suggest(committed: String, limit: Int = 8): List<String> {
        if (committed.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        bigrams[committed]?.entries?.sortedByDescending { it.value }?.forEach { out.add(it.key) }
        // Longest-suffix match so that a long committed sentence still triggers association.
        for (start in committed.indices) {
            val tail = committed.substring(start)
            val hits = dictionary.wordsStartingWith(tail).map { it.word.substring(tail.length) }
            hits.forEach(out::add)
            if (hits.isNotEmpty()) break
        }
        return out.take(limit)
    }

    fun serialize(): String = buildString {
        for ((p, m) in bigrams) for ((n, c) in m) append("$p\t$n\t$c\n")
    }

    fun load(text: String) {
        text.lineSequence().forEach { l ->
            val c = l.split('\t'); if (c.size == 3) c[2].toIntOrNull()?.let { bigrams.getOrPut(c[0]) { HashMap() }[c[1]] = it }
        }
    }
}
