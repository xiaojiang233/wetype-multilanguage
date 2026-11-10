package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.keyboard.HostBaseLayout
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfile
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfileRegistry

/**
 * Manages switching between keyboard profiles (e.g. WeType Chinese/English, Japanese Romaji/12-Key,
 * Korean, Russian) and coordinates with WeType's underlying layout.
 */
object InputMode {
    enum class Mode(val id: String, val shortLabel: String) {
        CN("zh_CN", "中"),
        EN("en_US", "英"),
        JA("ja_JP", "あ"),
        KANA12("ja_KANA12", "あ"),
        KO("ko_KR", "한"),
        RU("ru_RU", "РУ");

        companion object {
            fun fromId(id: String): Mode? = entries.firstOrNull { it.id == id }
        }
    }

    /** WeType's Chinese layout id to return to after English or non-host keyboards. */
    @Volatile private var cnLayout: Int? = null

    /** Read from live state. */
    fun current(ctl: ImeController): Mode {
        if (ctl.active) {
            return Mode.fromId(ctl.currentProfile.id) ?: Mode.JA
        }
        val k = KeyboardMenuHooks.current ?: return Mode.CN
        return if (k.layoutId() == k.englishLayoutId()) Mode.EN else Mode.CN
    }

    fun currentProfile(ctl: ImeController): KeyboardProfile {
        if (ctl.active) return ctl.currentProfile
        val k = KeyboardMenuHooks.current ?: return KeyboardProfileRegistry.ZH_CN
        return if (k.layoutId() == k.englishLayoutId()) KeyboardProfileRegistry.EN_US else KeyboardProfileRegistry.ZH_CN
    }

    fun label(ctl: ImeController): String = currentProfile(ctl).shortLabel

    /** One step of the cycle according to enabled keyboards list in config. Returns the new profile. */
    fun cycle(ctl: ImeController): KeyboardProfile {
        val enabledList = ConfigRepository.get().enabledKeyboardIds.ifEmpty { listOf("zh_CN", "en_US", "ja_JP") }
        val cycleProfiles = enabledList.mapNotNull { KeyboardProfileRegistry.findById(it) }
            .ifEmpty { listOf(KeyboardProfileRegistry.ZH_CN, KeyboardProfileRegistry.EN_US, KeyboardProfileRegistry.JA_JP) }

        val cur = currentProfile(ctl)
        val idx = cycleProfiles.indexOfFirst { it.id == cur.id }
        val next = if (idx == -1 || idx == cycleProfiles.lastIndex) cycleProfiles.first() else cycleProfiles[idx + 1]
        set(ctl, next)
        return next
    }

    /** Set keyboard profile directly. */
    fun set(ctl: ImeController, target: KeyboardProfile) {
        val k = KeyboardMenuHooks.current
        if (k != null && currentProfile(ctl).id == KeyboardProfileRegistry.ZH_CN.id) {
            k.layoutId()?.let { cnLayout = it }
        }

        when (target.hostLayout) {
            HostBaseLayout.CHINESE_QWERTY -> {
                if (target.isHostHandled) {
                    ctl.deactivate()
                    if (k != null) k.switchLayout(cnLayout ?: k.chineseLayoutId())
                } else {
                    ctl.activate(target)
                    if (k != null) k.switchLayout(k.chineseLayoutId())
                }
            }
            HostBaseLayout.ENGLISH_QWERTY -> {
                ctl.deactivate()
                if (k != null) k.switchLayout(k.englishLayoutId())
            }
            HostBaseLayout.CHINESE_NINE_KEY -> {
                if (target.isHostHandled) {
                    ctl.deactivate()
                    if (k != null) k.switchLayout(cnLayout ?: k.chineseLayoutId())
                } else {
                    ctl.activate(target)
                    if (k != null) k.switchLayout(k.chineseLayoutId())
                }
            }
        }
        LayoutHooks.refresh()
        LanguageSwitcher.refresh()
        XposedCompat.log("WeTypeMulti", "keyboard profile -> ${target.id}")
    }

    /** Compatibility helper for setting by Mode enum. */
    fun set(ctl: ImeController, target: Mode) {
        val profile = KeyboardProfileRegistry.findById(target.id) ?: KeyboardProfileRegistry.ZH_CN
        set(ctl, profile)
    }
}
