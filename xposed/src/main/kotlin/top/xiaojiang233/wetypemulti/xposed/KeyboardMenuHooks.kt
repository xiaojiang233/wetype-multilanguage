package top.xiaojiang233.wetypemulti.xposed

import android.os.Bundle
import java.lang.reflect.Proxy

/**
 * Glue to WeType 4.0.0's keyboard switcher. Names come from the hot-updated dex that the phone actually
 * runs (see docs/wetype-live-hotupdate.md), not from the base APK in apk/, which differs.
 *
 *  - `model.N.l3(int layoutId, Bundle)` switches WeType's layout (live build). It forwards to
 *    `m3(id, bundle, true)`. The base APK has a three-argument `l3`; [switchLayout] accepts either.
 *  - Layout ids are the `value`s of the `keyboard.t` constants: `d` = ChineseQwerty (全键盘, 1),
 *    `l` = EnglishQwerty (英文, 100). Same in the live build.
 *  - `model.N.u0()` returns the layout WeType is showing now ([layoutId]).
 *  - The long-press popup (`utils.v0.g`) gets a 日语 item before 设置. Its action goes through [InputMode].
 *  - Tapping one of WeType's own popup items (click class `utils.v0$f`) while Japanese is on turns Japanese
 *    off, since WeType moved to another layout on the user's own choice.
 *
 * A missing class or method disables only that part and logs it; nothing else breaks.
 */
class KeyboardMenuHooks(private val ctl: ImeController, private val cl: ClassLoader) {
    private val pkg = "com.tencent.wetype.plugin.hld"
    /** True only while we ask WeType for a layout ourselves, so our own switches are not treated as WeType's. */
    @Volatile private var selfSwitch = false

    companion object {
        /** One instance per process: the IME service's own loader, set in [install]. */
        @Volatile var current: KeyboardMenuHooks? = null
    }

    fun install() {
        val v0 = runCatching { XposedCompat.findClass("$pkg.utils.v0", cl) }.getOrNull() ?: return
        current = this
        runCatching { installOn(v0) }.onFailure { log("install failed (WeType build mismatch?): $it") }
    }

    /** Separated so a renamed internal class never crashes the host; it only disables that hook. */
    private fun installOn(v0: Class<*>) {
        val n = XposedCompat.findClass("$pkg.model.N", cl)

        val gHooked = XposedCompat.hookAllMethods(v0, "g", after = { p ->
            runCatching {
                @Suppress("UNCHECKED_CAST") val list = ArrayList(p.result as List<Any>)
                if (list.isNotEmpty()) {
                    list.add(maxOf(0, list.size - 1), japaneseItem()) // before the trailing 设置 item
                    p.result = list
                }
            }.onFailure { log("popup item: $it") }
        })
        // A layout switch we did not start: leave Japanese, unless it is the Chinese layout Japanese itself
        // runs on (WeType may re-select its last layout by itself), or a candidate panel layout.
        val l3Hooked = XposedCompat.hookAllMethods(n, "l3", before = { p ->
            val target = p.args.getOrNull(0) as? Int
            if (!selfSwitch && ctl.active && target != null && !isAllowedJapaneseLayout(target)) leaveJapanese("layout $target")
        })
        val itemHooked = runCatching {
            val itemCls = XposedCompat.findClass("$pkg.utils.v0\$f", cl)
            logLiveSignatures(n, itemCls)
            XposedCompat.hookAllMethods(itemCls, "invoke", before = {
                if (!selfSwitch && ctl.active) leaveJapanese("WeType popup item")
            })
        }.getOrDefault(0)
        log("keyboard menu hooks installed (g matched $gHooked, l3 matched $l3Hooked, popup item matched $itemHooked)")
    }

    /**
     * One-time log of the live signatures this module depends on, so a device log shows what the running
     * build really has. Reflection only: no static initialisers run here (touching model.N before the
     * context is set crashes; see [switchLayout]).
     */
    private fun logLiveSignatures(n: Class<*>, itemCls: Class<*>) {
        val names = setOf("l3", "m3", "p3", "u0")
        n.declaredMethods.filter { it.name in names }.sortedBy { it.name }.forEach { m ->
            log("live model.N.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
        }
        itemCls.declaredMethods.filter { it.name == "invoke" }.forEach { m ->
            log("live utils.v0\$f.invoke(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
        }
        var c: ClassLoader? = cl
        val chain = ArrayList<String>()
        while (c != null && chain.size < 6) { chain.add(c.javaClass.simpleName); c = c.parent }
        log("loader chain for keyboard hooks: ${chain.joinToString(" -> ")}")
    }

    /** The layout WeType is showing right now, or null when it cannot be read. */
    fun layoutId(): Int? = runCatching { callN("u0") as Int }.onFailure { log("layoutId: $it") }.getOrNull()

    /** Chinese QWERTY (全键盘). Read from WeType so a renumbered build still works; 1 is the 4.0.0 value. */
    fun chineseLayoutId(): Int = keyboardId("d", 1)

    /** English QWERTY (英文). 100 is the 4.0.0 value. */
    fun englishLayoutId(): Int = keyboardId("l", 100)

    /** More-candidates panel (展开候选栏). 106 is the WeType 4.0.0 value. */
    fun candidateLayoutId(): Int = 106

    /** Layouts that Japanese is allowed to run on without deactivating: Chinese QWERTY and candidate panels. */
    fun isAllowedJapaneseLayout(target: Int): Boolean =
        target == chineseLayoutId() || target == candidateLayoutId()

    private fun keyboardId(constant: String, fallback: Int): Int = runCatching {
        val t = XposedCompat.findClass("$pkg.keyboard.t", cl)
        XposedCompat.callMethod(XposedCompat.getStaticObjectField(t, constant) as Any, "c") as Int
    }.getOrDefault(fallback)

    /**
     * Switch WeType to [layoutId] (`model.N.l3`, main thread only). The live build has `l3(int, Bundle)`; the
     * base APK has `l3(int, Bundle, boolean)`. Whichever this build declares is called, and the layout WeType
     * reports afterwards is logged so a device test shows whether the switch took effect.
     */
    fun switchLayout(layoutId: Int): Boolean = runCatching {
        // WeType's layout code reads a Context from e7.u.a. Set it from the live IME service first, otherwise
        // touching model.N throws in its static initialiser (MMKV needs a Context).
        ctl.service?.applicationContext?.let {
            XposedCompat.setStaticObjectField(XposedCompat.findClass("e7.u", cl), "a", it)
        }
        val inst = modelN() ?: error("model.N not initialised")
        val m = inst.javaClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "l3" && candidate.parameterTypes.size in 2..3 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[1] == Bundle::class.java
        } ?: error("no l3(int, Bundle[, boolean]) on model.N")
        val extra = m.parameterTypes.drop(2).map { t ->
            if (t == java.lang.Boolean.TYPE) false else error("unexpected l3 parameter $t")
        }
        m.isAccessible = true
        selfSwitch = true
        try {
            m.invoke(inst, layoutId, Bundle(), *extra.toTypedArray())
        } finally {
            selfSwitch = false
        }
        log("layout -> $layoutId via l3(${m.parameterTypes.joinToString { it.simpleName }}), now showing ${layoutId()}")
        true
    }.onFailure { log("switchLayout($layoutId) failed: $it") }.getOrDefault(false)

    private fun modelN(): Any? = XposedCompat.getStaticObjectField(XposedCompat.findClass("$pkg.model.N", cl), "a")

    private fun callN(name: String, vararg args: Any?): Any? {
        val inst = modelN() ?: error("model.N not initialised")
        return XposedCompat.callMethod(inst, name, *args)
    }

    private fun japaneseItem(): Any {
        val cCls = XposedCompat.findClass("$pkg.floatview.C", cl)
        val action = XposedCompat.findClass("xc.a", cl)
        val unit = XposedCompat.getStaticObjectField(XposedCompat.findClass("nc.F", cl), "a")
        val click = Proxy.newProxyInstance(cl, arrayOf(action)) { _, m, _ ->
            if (m.name == "invoke") {
                log("日语 item clicked")
                InputMode.set(ctl, InputMode.Mode.JA)
            }
            unit
        }
        val icon = XposedCompat.getStaticIntField(XposedCompat.findClass("$pkg.r", cl), "icon_quick_switch_chinese")
        return XposedCompat.newInstance(cCls, "wtm_japanese", "", false, "日语", icon, false, false, click)
    }

    private fun leaveJapanese(reason: String) {
        log("leaving Japanese: $reason")
        ctl.deactivate()
        LanguageSwitcher.refresh()
    }

    private fun log(m: String) = XposedCompat.log("WeTypeMulti/menu", m)
}
