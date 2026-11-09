package top.xiaojiang233.wetypemulti.xposed

/**
 * The modes the globe key and Ctrl+Space cycle through: WeType's Chinese layout (中), its English
 * layout (英) and our Japanese romaji mode (あ). Japanese runs on WeType's Chinese QWERTY layout so the
 * letter keys reach `WxhldApi.process_input`.
 *
 * Every change goes through [set], so our mode ([ImeController.active]) and WeType's layout
 * ([KeyboardMenuHooks.switchLayout], `model.N.l3`) move together. Nothing here replays a tap on
 * WeType's keyboard.
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

    /** WeType's Chinese layout id to return to after English or Japanese (remembered when we leave it). */
    @Volatile private var cnLayout: Int? = null

    /** Read from live state, so a layout change made by WeType itself shows up here as well. */
    fun current(ctl: ImeController): Mode {
        if (ctl.active) return Mode.JA
        val k = KeyboardMenuHooks.current ?: return Mode.CN
        return if (k.layoutId() == k.englishLayoutId()) Mode.EN else Mode.CN
    }

    fun label(ctl: ImeController): String = when (current(ctl)) {
        Mode.CN -> "中"
        Mode.EN -> "英"
        Mode.JA -> ctl.label
        Mode.KANA12 -> "あ"
        Mode.KO -> "한"
        Mode.RU -> "РУ"
    }

    /** One step of the cycle according to enabled keyboards list in config. Returns the new mode. */
    fun cycle(ctl: ImeController): Mode {
        val enabledList = runCatching {
            val f = java.io.File(ctl.service?.filesDir ?: java.io.File("/data/data/com.tencent.wetype/files"), "config.json")
            if (f.exists()) top.xiaojiang233.wetypemulti.common.config.MultiLanguageConfig.fromJson(f.readText()).enabledKeyboardIds else null
        }.getOrNull() ?: listOf("zh_CN", "en_US", "ja_JP")

        val cycleModes = enabledList.mapNotNull { Mode.fromId(it) }.ifEmpty { listOf(Mode.CN, Mode.EN, Mode.JA) }
        val cur = current(ctl)
        val idx = cycleModes.indexOf(cur)
        val next = if (idx == -1 || idx == cycleModes.lastIndex) cycleModes.first() else cycleModes[idx + 1]
        set(ctl, next)
        return next
    }

    /**
     * Main thread only (touch, key or broadcast). Called without [ImeController]'s lock held, because
     * WeType's layout switch can call back into our hooks.
     */
    fun set(ctl: ImeController, target: Mode) {
        val k = KeyboardMenuHooks.current
        if (k != null && current(ctl) == Mode.CN) k.layoutId()?.let { cnLayout = it }
        when (target) {
            Mode.CN -> {
                ctl.deactivate()
                if (k != null) k.switchLayout(cnLayout ?: k.chineseLayoutId())
            }
            Mode.EN -> {
                ctl.deactivate()
                if (k != null) k.switchLayout(k.englishLayoutId())
            }
            Mode.JA, Mode.KANA12 -> {
                ctl.activate("ja")
                if (k != null) k.switchLayout(k.chineseLayoutId())
            }
            Mode.KO -> {
                ctl.activate("ko")
                if (k != null) k.switchLayout(k.chineseLayoutId())
            }
            Mode.RU -> {
                ctl.activate("ru")
                if (k != null) k.switchLayout(k.chineseLayoutId())
            }
        }
        LayoutHooks.refresh() // redraw from the layout read for this mode (JSON rewrite on Japanese)
        LanguageSwitcher.refresh()
        XposedCompat.log("WeTypeMulti", "mode -> $target")
    }
}
