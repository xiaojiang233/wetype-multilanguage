package top.xiaojiang233.wetypemulti.common.keyboard

/**
 * Information about a keyboard layout/language option available to the user.
 */
data class KeyboardInfo(
    val id: String,
    val languageCode: String,
    val languageName: String,
    val layoutName: String,
    val shortLabel: String,
    val rimeSchemaId: String? = null,
    val description: String = "",
)

object AvailableKeyboards {
    val CHINESE = KeyboardInfo(
        id = "zh_CN",
        languageCode = "zh",
        languageName = "中文（简体）",
        layoutName = "拼音全键盘 / 九键",
        shortLabel = "中",
        description = "WeType 微信输入法原生中文布局"
    )

    val ENGLISH = KeyboardInfo(
        id = "en_US",
        languageCode = "en",
        languageName = "English (US)",
        layoutName = "QWERTY 26键",
        shortLabel = "EN",
        description = "标准 26 键英文输入"
    )

    val JAPANESE_ROMAJI = KeyboardInfo(
        id = "ja_JP",
        languageCode = "ja",
        languageName = "日本語",
        layoutName = "罗马字 (QWERTY)",
        shortLabel = "あ",
        rimeSchemaId = "japanese",
        description = "日文罗马字输入，集成 Rime 核心、智能联想与长音键"
    )

    val JAPANESE_KANA12 = KeyboardInfo(
        id = "ja_KANA12",
        languageCode = "ja",
        languageName = "日本語",
        layoutName = "假名九键 (12键)",
        shortLabel = "あ",
        rimeSchemaId = "japanese_kana",
        description = "九宫格假名输入与滑动输入支持"
    )

    val KOREAN = KeyboardInfo(
        id = "ko_KR",
        languageCode = "ko",
        languageName = "한국어 (韩语)",
        layoutName = "2-Set (Dubeolsik)",
        shortLabel = "한",
        rimeSchemaId = "korean",
        description = "标准谚文双拼键盘"
    )

    val RUSSIAN = KeyboardInfo(
        id = "ru_RU",
        languageCode = "ru",
        languageName = "Русский (俄语)",
        layoutName = "ЙЦУКЕН 全键盘",
        shortLabel = "РУ",
        rimeSchemaId = "russian",
        description = "西里尔字母全键盘布局"
    )

    val ALL: List<KeyboardInfo> = listOf(
        CHINESE,
        ENGLISH,
        JAPANESE_ROMAJI,
        JAPANESE_KANA12,
        KOREAN,
        RUSSIAN,
    )

    fun findById(id: String): KeyboardInfo? = ALL.firstOrNull { it.id == id }
}
