package top.xiaojiang233.wetypemulti.common.keyboard

import top.xiaojiang233.wetypemulti.common.layout.JaLayoutRules
import top.xiaojiang233.wetypemulti.common.layout.JaPunctuation
import top.xiaojiang233.wetypemulti.common.layout.KeyPatch
import top.xiaojiang233.wetypemulti.common.romaji.JaPreedit

/** Which underlying WeType keyboard layout should be active in the host IME. */
enum class HostBaseLayout {
    CHINESE_QWERTY,
    ENGLISH_QWERTY,
    CHINESE_NINE_KEY,
}

/** Formatter for in-progress preedit text. */
fun interface PreeditFormatter {
    fun format(raw: String): String
}

object PassthroughPreeditFormatter : PreeditFormatter {
    override fun format(raw: String): String = raw
}

object JapanesePreeditFormatter : PreeditFormatter {
    override fun format(raw: String): String = JaPreedit.display(raw)
}

/** Processor for punctuation conversion. */
fun interface PunctuationMapper {
    fun map(input: String): String
}

object PassthroughPunctuationMapper : PunctuationMapper {
    override fun map(input: String): String = input
}

object JapanesePunctuationMapper : PunctuationMapper {
    override fun map(input: String): String = JaPunctuation.toJapanese(input)
}

/**
 * Decoupled specification for a keyboard layout and language combination.
 * Decouples the physical layout/patches from the linguistic input engine.
 */
data class KeyboardProfile(
    val info: KeyboardInfo,
    val hostLayout: HostBaseLayout,
    /** True if WeType handles keystrokes directly without our engine intercepting. */
    val isHostHandled: Boolean = false,
    /** Which asset paths in the host IME should receive layout JSON patches. */
    val targetLayoutPaths: Set<String> = emptySet(),
    /** Key property patches to apply to the host layout JSON. */
    val layoutPatches: List<KeyPatch> = emptyList(),
    val preeditFormatter: PreeditFormatter = PassthroughPreeditFormatter,
    val punctuationMapper: PunctuationMapper = PassthroughPunctuationMapper,
    /** Slot name for extra overlay key (e.g. "shift" for Japanese long vowel mark ー). */
    val overlayKeySlot: String? = null,
) {
    val id: String get() = info.id
    val languageCode: String get() = info.languageCode
    val shortLabel: String get() = info.shortLabel
    val rimeSchemaId: String? get() = info.rimeSchemaId
}

/** Registry of available decoupled keyboard profiles. */
object KeyboardProfileRegistry {
    val ZH_CN = KeyboardProfile(
        info = AvailableKeyboards.CHINESE,
        hostLayout = HostBaseLayout.CHINESE_QWERTY,
        isHostHandled = true
    )

    val EN_US = KeyboardProfile(
        info = AvailableKeyboards.ENGLISH,
        hostLayout = HostBaseLayout.ENGLISH_QWERTY,
        isHostHandled = true
    )

    val JA_JP = KeyboardProfile(
        info = AvailableKeyboards.JAPANESE_ROMAJI,
        hostLayout = HostBaseLayout.CHINESE_QWERTY,
        isHostHandled = false,
        targetLayoutPaths = JaLayoutRules.TARGET_PATHS,
        layoutPatches = JaLayoutRules.PATCHES,
        preeditFormatter = JapanesePreeditFormatter,
        punctuationMapper = JapanesePunctuationMapper,
        overlayKeySlot = JaLayoutRules.LONG_VOWEL_SLOT
    )

    val JA_KANA12 = KeyboardProfile(
        info = AvailableKeyboards.JAPANESE_KANA12,
        hostLayout = HostBaseLayout.CHINESE_NINE_KEY,
        isHostHandled = false,
        preeditFormatter = JapanesePreeditFormatter,
        punctuationMapper = JapanesePunctuationMapper
    )

    val KO_KR = KeyboardProfile(
        info = AvailableKeyboards.KOREAN,
        hostLayout = HostBaseLayout.CHINESE_QWERTY,
        isHostHandled = false
    )

    val RU_RU = KeyboardProfile(
        info = AvailableKeyboards.RUSSIAN,
        hostLayout = HostBaseLayout.CHINESE_QWERTY,
        isHostHandled = false
    )

    val ALL = listOf(ZH_CN, EN_US, JA_JP, JA_KANA12, KO_KR, RU_RU)

    private val map = ALL.associateBy { it.id }

    fun findById(id: String): KeyboardProfile? = map[id]

    fun requireById(id: String): KeyboardProfile = map[id] ?: ZH_CN
}
