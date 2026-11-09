package top.xiaojiang233.wetypemulti.common.dict

import java.util.TreeMap

/** One dictionary entry. [reading] is hiragana. */
data class Entry(val reading: String, val word: String, val weight: Int = 0)

/** In-memory dictionary indexed by hiragana reading, supporting exact and prefix lookup. */
class Dictionary {
    private val index = TreeMap<String, MutableList<Entry>>()

    val size: Int get() = index.values.sumOf { it.size }

    fun add(entry: Entry) {
        val list = index.getOrPut(entry.reading) { mutableListOf() }
        val i = list.indexOfFirst { it.word == entry.word }
        if (i >= 0) { if (entry.weight > list[i].weight) list[i] = entry } else list.add(entry)
    }

    fun addAll(entries: Iterable<Entry>) = entries.forEach(::add)

    fun lookup(reading: String): List<Entry> = index[reading].orEmpty().sortedByDescending { it.weight }

    /** Entries whose reading starts with [prefix] but is longer than it (predictive completion). */
    fun prefix(prefix: String, limit: Int = 20): List<Entry> {
        if (prefix.isEmpty()) return emptyList()
        val end = prefix + Char.MAX_VALUE
        return index.subMap(prefix, false, end, true).values.flatten()
            .sortedWith(compareByDescending<Entry> { it.weight }.thenBy { it.reading.length })
            .take(limit)
    }

    /** Entries whose written form starts with [wordPrefix] (used for 联想 / association). */
    fun wordsStartingWith(wordPrefix: String): List<Entry> =
        index.values.flatten().filter { it.word.length > wordPrefix.length && it.word.startsWith(wordPrefix) }
            .sortedByDescending { it.weight }

    fun entries(): List<Entry> = index.values.flatten()
}
