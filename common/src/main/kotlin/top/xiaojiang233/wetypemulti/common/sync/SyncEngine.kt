package top.xiaojiang233.wetypemulti.common.sync

/** Outcome of one [SyncEngine.sync] round. */
data class SyncReport(val pulled: Int, val pushed: Int, val retries: Int, val fullMerge: Boolean, val version: Long)

/**
 * Pull-merge-push, following WeType's flow (`user_dict_syncer_inc_impl` / `_full_impl`): incremental pull
 * from the last known version, merge, incremental push; on [PushResult.Conflict] pull again and re-push;
 * if the backend lost our history ([PullResult.fullSyncRequired]) re-upload everything.
 */
class SyncEngine(
    private val store: UserDataStore,
    private val provider: SyncProvider,
    private val maxRetries: Int = 5,
) {
    /** Last backend version merged. Persist it next to the store and pass it back to [restore]. */
    var version: Long = 0
        private set

    fun restore(version: Long) { this.version = version }

    fun sync(): SyncReport {
        var pulled = 0; var retries = 0; var full = false
        while (true) {
            val r = provider.pull(version)
            if (r.fullSyncRequired) { full = true; store.markAllDirty() }
            pulled += r.items.size
            store.mergeRemote(r.items)
            version = r.version
            val out = store.pending()
            if (out.isEmpty()) return SyncReport(pulled, 0, retries, full, version)
            when (val p = provider.push(out, version)) {
                is PushResult.Ok -> { store.markPushed(); version = p.version; return SyncReport(pulled, out.size, retries, full, version) }
                PushResult.Conflict -> if (++retries > maxRetries) error("sync: too many conflicts")
            }
        }
    }
}
