package top.xiaojiang233.wetypemulti.common.sync

/**
 * Kinds of user data that sync. Modelled on the dictionaries inside WeType's engine
 * (`libwxhld.so`: UserDict, UserBlockListDict, UserHardFixDict, user word-freq dict, user hot words);
 * [BIGRAM] is ours (联想 learning).
 */
enum class DataType {
    /** key = `reading SEP word`, value = weight (higher is better). */
    USER_WORD,
    /** key = `reading SEP word`; the word must never be offered for that reading. */
    BLOCK_LIST,
    /** key = reading, value = `word SEP position`; pins a word to a candidate position. */
    HARD_FIX,
    /** key = `previous SEP next`, value = count. */
    BIGRAM,
}

const val SEP = '\u0001'

/**
 * One syncable record. A deletion is a tombstone ([deleted] = true) so it propagates, like WeType's
 * incremental "action" items. Ordering between replicas is last-writer-wins on ([timestamp], [device]).
 */
data class SyncItem(
    val type: DataType,
    val key: String,
    val value: String = "",
    val deleted: Boolean = false,
    val timestamp: Long,
    val device: String,
) {
    val id: String get() = "${type.name}$SEP$key"
}

/** Result of asking a backend for changes after [since]. */
data class PullResult(
    val items: List<SyncItem>,
    /** Backend version after these items; store it as the next `since`. */
    val version: Long,
    /** The backend no longer has a consistent history for [since] (e.g. it was reset): do a full merge. */
    val fullSyncRequired: Boolean = false,
)

sealed class PushResult {
    data class Ok(val version: Long) : PushResult()
    /** Someone else pushed after [baseVersion]; pull, merge and push again (WeType: "Merge conflict, re-push"). */
    data object Conflict : PushResult()
}

/** Pluggable backend: a local folder, WebDAV, a custom server... */
interface SyncProvider {
    /** Items changed after [since] (use 0 for everything). */
    fun pull(since: Long): PullResult
    /** Upload [items] on top of backend version [baseVersion]. */
    fun push(items: List<SyncItem>, baseVersion: Long): PushResult
}
