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
    enum class Mode { CN, EN, JA }

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
    }

    /** One step of the cycle: 中 -> 英 -> あ -> 中. Returns the new mode. */
    fun cycle(ctl: ImeController): Mode {
        val next = when (current(ctl)) {
            Mode.CN -> Mode.EN
            Mode.EN -> Mode.JA
            Mode.JA -> Mode.CN
        }
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
            Mode.JA -> {
                ctl.activate("ja")
                if (k != null) k.switchLayout(k.chineseLayoutId())
            }
        }
        LayoutHooks.refresh() // redraw from the layout read for this mode (JSON rewrite on Japanese)
        LanguageSwitcher.refresh()
        XposedCompat.log("WeTypeMulti", "mode -> $target")
    }
}
