package top.xiaojiang233.wetypemulti.common.engine

import top.xiaojiang233.wetypemulti.common.language.Language

/**
 * Thin mirror of the librime C API (`rime_api.h`). A JNI implementation backed by the native
 * `librime.so` lives in the Android layer; tests can supply a fake.
 */
interface RimeBridge {
    /** Initialise rime with shared data (schemas, dictionaries) and a writable user dir. */
    fun initialize(sharedDataDir: String, userDataDir: String, appName: String = "WeTypeMulti")
    /** (Re)build schemas and prism/table binaries; call after importing dictionaries. */
    fun deploy(): Boolean
    fun createSession(): Long
    fun destroySession(session: Long)
    fun selectSchema(session: Long, schemaId: String): Boolean
    /** keycode is an X11 keysym (same as librime), mask the modifier bits. */
    fun processKey(session: Long, keycode: Int, mask: Int = 0): Boolean
    fun context(session: Long): Context
    fun selectCandidate(session: Long, index: Int): Boolean
    /** Text waiting to be committed after processKey/selectCandidate; consumed on read. */
    fun takeCommit(session: Long): String?
    fun clearComposition(session: Long)
    fun finalizeRime()
}

/** [InputEngine] backed by Rime through a [RimeBridge]. */
class RimeInputEngine(
    private val bridge: RimeBridge,
    override val language: Language,
    private val associate: (String) -> List<String> = { emptyList() },
) : InputEngine {
    private val session = bridge.createSession().also { bridge.selectSchema(it, language.rimeSchemaId) }
    private val pendingCommit = StringBuilder()

    private fun pump() { bridge.takeCommit(session)?.let { pendingCommit.append(it) } }

    override fun processKey(c: Char): Boolean = bridge.processKey(session, c.code).also { pump() }
    override fun backspace(): Boolean = bridge.processKey(session, 0xFF08).also { pump() } // XK_BackSpace
    override fun context(): Context = bridge.context(session)

    override fun select(index: Int): String? {
        if (!bridge.selectCandidate(session, index)) return null
        pump()
        return pendingCommit.toString().also { pendingCommit.setLength(0) }
    }

    override fun commitRaw(): String {
        bridge.processKey(session, 0xFF0D) // XK_Return commits the raw input in most schemas
        pump()
        return pendingCommit.toString().also { pendingCommit.setLength(0) }
    }

    override fun reset() { bridge.clearComposition(session); pendingCommit.setLength(0) }
    override fun takeCommitted(): String? {
        pump()
        return pendingCommit.toString().takeIf { it.isNotEmpty() }?.also { pendingCommit.setLength(0) }
    }
    override fun suggest(committed: String) = associate(committed)
    fun close() = bridge.destroySession(session)
}
