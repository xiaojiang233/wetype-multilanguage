package top.xiaojiang233.wetypemulti.common.engine

import top.xiaojiang233.wetypemulti.common.language.Language

data class Candidate(val text: String, val comment: String = "")

/** What the IME UI needs to draw: composition string and candidate list. */
data class Context(val preedit: String, val candidates: List<Candidate>)

/** Language-agnostic composition engine. Implemented by Rime and by the built-in fallback. */
interface InputEngine {
    val language: Language
    /** Feed a printable key. Returns true when the engine consumed it. */
    fun processKey(c: Char): Boolean
    fun backspace(): Boolean
    fun context(): Context
    /** Pick candidate [index]; returns text to commit to the editor, or null if invalid. */
    fun select(index: Int): String?
    /** Commit the raw composition (Enter key) and clear it. */
    fun commitRaw(): String
    fun reset()
    /**
     * Text committed while processing keys (Rime commits on its own, e.g. when space picks a candidate).
     * Consumed on read; null when nothing is pending.
     */
    fun takeCommitted(): String? = null
    /** 联想: suggestions after [committed] was committed. */
    fun suggest(committed: String): List<String>
}
