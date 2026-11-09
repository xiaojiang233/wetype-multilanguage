package top.xiaojiang233.wetypemulti.common.language

/** A language that can be typed through the module. */
data class Language(
    val id: String,
    val displayName: String,
    /** Rime schema id used for this language. */
    val rimeSchemaId: String,
    /** Label shown on the language-switch key. */
    val shortLabel: String,
    /** Physical key layout the user types on (the module consumes latin letters). */
    val keyLayout: KeyLayout = KeyLayout.QWERTY,
)

enum class KeyLayout { QWERTY }

object Languages {
    val JAPANESE = Language("ja", "日本語", "japanese", "あ")

    private val registry = linkedMapOf(JAPANESE.id to JAPANESE)

    fun all(): List<Language> = registry.values.toList()
    fun byId(id: String): Language? = registry[id]

    /** Register an additional language (e.g. Korean, Russian) without touching core code. */
    fun register(language: Language) { registry[language.id] = language }

    /** Cycle to the next language after [current]; wraps around. */
    fun next(current: Language): Language {
        val list = all()
        return list[(list.indexOf(current) + 1) % list.size]
    }
}
