package top.xiaojiang233.wetypemulti.common.sync

import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.dict.Entry
import top.xiaojiang233.wetypemulti.common.predict.Predictor

/** Local user data with change tracking, the unit [SyncEngine] works on. */
class UserDataStore(val deviceId: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val items = LinkedHashMap<String, SyncItem>()
    private val dirty = LinkedHashSet<String>()

    fun all(): List<SyncItem> = items.values.filter { !it.deleted }
    fun get(type: DataType, key: String) = items["${type.name}$SEP$key"]?.takeIf { !it.deleted }

    private fun put(type: DataType, key: String, value: String, deleted: Boolean) {
        val it = SyncItem(type, key, value, deleted, clock(), deviceId)
        items[it.id] = it; dirty.add(it.id)
    }

    fun upsertWord(reading: String, word: String, weight: Int) =
        put(DataType.USER_WORD, "$reading$SEP$word", weight.toString(), false)
    fun removeWord(reading: String, word: String) = put(DataType.USER_WORD, "$reading$SEP$word", "", true)
    fun block(reading: String, word: String) = put(DataType.BLOCK_LIST, "$reading$SEP$word", "", false)
    fun unblock(reading: String, word: String) = put(DataType.BLOCK_LIST, "$reading$SEP$word", "", true)
    fun pin(reading: String, word: String, position: Int) = put(DataType.HARD_FIX, reading, "$word$SEP$position", false)
    fun unpin(reading: String) = put(DataType.HARD_FIX, reading, "", true)
    fun setBigram(previous: String, next: String, count: Int) =
        put(DataType.BIGRAM, "$previous$SEP$next", count.toString(), false)

    /** Records changed since the last successful push. */
    fun pending(): List<SyncItem> = dirty.mapNotNull { items[it] }

    fun markPushed() = dirty.clear()

    /** Schedule every record for upload (used when the backend lost its history). */
    fun markAllDirty() { dirty.addAll(items.keys) }

    /** Merge remote records in; returns how many changed local state. Never marks them dirty. */
    fun mergeRemote(remote: List<SyncItem>): Int {
        var changed = 0
        for (r in remote) {
            val merged = Merge.resolve(items[r.id], r)
            if (merged != items[r.id]) { items[r.id] = merged; changed++ }
            // If our merged value differs from what the backend has, it must be pushed back.
            if (merged != r) dirty.add(r.id)
        }
        return changed
    }

    /** Persist everything including tombstones and the dirty flags: one `D|C TAB item` line each. */
    fun serialize(): String = buildString {
        for ((id, it) in items) append(if (id in dirty) "D" else "C").append('\t').append(SyncFormat.encode(it)).append('\n')
    }

    fun load(text: String) {
        for (line in text.lineSequence()) {
            if (line.length < 3) continue
            val item = SyncFormat.decode(line.substring(2)) ?: continue
            items[item.id] = item; if (line[0] == 'D') dirty.add(item.id)
        }
    }

    /** Load words/bigrams/blocks into the live engine structures. */
    fun applyTo(dictionary: Dictionary, predictor: Predictor, blocked: MutableSet<Pair<String, String>> = HashSet()) {
        for (it in all()) when (it.type) {
            DataType.USER_WORD -> it.key.split(SEP).let { (r, w) -> dictionary.add(Entry(r, w, it.value.toIntOrNull() ?: 0)) }
            DataType.BLOCK_LIST -> it.key.split(SEP).let { (r, w) -> blocked.add(r to w) }
            DataType.BIGRAM -> it.key.split(SEP).let { (p, n) -> repeat(it.value.toIntOrNull() ?: 0) { predictor.learn(p, n) } }
            DataType.HARD_FIX -> Unit // consumed by the engine's candidate ordering
        }
    }
}
