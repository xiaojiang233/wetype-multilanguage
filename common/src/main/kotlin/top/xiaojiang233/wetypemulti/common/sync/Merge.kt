package top.xiaojiang233.wetypemulti.common.sync

/** Conflict resolution between two versions of the same record. */
object Merge {
    /** Last-writer-wins; the device id breaks timestamp ties so every replica picks the same winner. */
    private fun newer(a: SyncItem, b: SyncItem) =
        if (a.timestamp != b.timestamp) a.timestamp > b.timestamp else a.device >= b.device

    fun resolve(local: SyncItem?, remote: SyncItem): SyncItem {
        if (local == null) return remote
        val winner = if (newer(local, remote)) local else remote
        // Counters are monotonic learning signals: two live replicas keep the larger count instead of
        // losing the other device's learning (WeType likewise "full merges" frequency dictionaries).
        if (!local.deleted && !remote.deleted && local.type in COUNTERS) {
            val l = local.value.toLongOrNull() ?: 0; val r = remote.value.toLongOrNull() ?: 0
            return winner.copy(value = maxOf(l, r).toString())
        }
        return winner
    }

    private val COUNTERS = setOf(DataType.USER_WORD, DataType.BIGRAM)
}
