package top.xiaojiang233.wetypemulti.xposed

import android.os.Bundle
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfileRegistry
import java.lang.reflect.Proxy

/**
 * Glue to WeType 4.0.0's keyboard switcher and long-press popup menu.
 * Dynamically injects all enabled multi-language keyboards into WeType's popup menu.
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
        runCatching { installOn(v0) }.onFailure { log("install failed: $it") }
    }

    private fun installOn(v0: Class<*>) {
        val n = XposedCompat.findClass("$pkg.model.N", cl)

        val gHooked = XposedCompat.hookAllMethods(v0, "g", after = { p ->
            runCatching {
                @Suppress("UNCHECKED_CAST") val list = ArrayList(p.result as List<Any>)
                if (list.isNotEmpty()) {
                    val items = buildProfileMenuItems()
                    val insertIndex = maxOf(0, list.size - 1)
                    list.addAll(insertIndex, items)
                    p.result = list
                }
            }.onFailure { log("popup item: $it") }
        })

        val l3Hooked = XposedCompat.hookAllMethods(n, "l3", before = { p ->
            val target = p.args.getOrNull(0) as? Int
            if (!selfSwitch && ctl.active && target != null && !isAllowedHostLayout(target)) {
                onHostLayoutChanged("layout $target")
            }
        })

        val itemHooked = runCatching {
            val itemCls = XposedCompat.findClass("$pkg.utils.v0\$f", cl)
            logLiveSignatures(n, itemCls)
            XposedCompat.hookAllMethods(itemCls, "invoke", before = {
                if (!selfSwitch && ctl.active) onHostLayoutChanged("WeType popup item")
            })
        }.getOrDefault(0)

        log("keyboard menu hooks installed (g matched $gHooked, l3 matched $l3Hooked, popup item matched $itemHooked)")
    }

    private fun buildProfileMenuItems(): List<Any> {
        val enabledIds = ConfigRepository.get().enabledKeyboardIds
        val cCls = runCatching { XposedCompat.findClass("$pkg.floatview.C", cl) }.getOrNull() ?: return emptyList()
        val action = runCatching { XposedCompat.findClass("xc.a", cl) }.getOrNull() ?: return emptyList()
        val unit = runCatching { XposedCompat.getStaticObjectField(XposedCompat.findClass("nc.F", cl), "a") }.getOrNull() ?: return emptyList()
        val icon = runCatching { XposedCompat.getStaticIntField(XposedCompat.findClass("$pkg.r", cl), "icon_quick_switch_chinese") }.getOrDefault(0)

        return enabledIds.mapNotNull { id ->
            val profile = KeyboardProfileRegistry.findById(id) ?: return@mapNotNull null
            if (profile.isHostHandled) return@mapNotNull null

            val click = Proxy.newProxyInstance(cl, arrayOf(action)) { _, m, _ ->
                if (m.name == "invoke") {
                    log("menu item clicked: ${profile.info.languageName} (${profile.id})")
                    InputMode.set(ctl, profile)
                }
                unit
            }
            XposedCompat.newInstance(cCls, "wtm_${profile.id}", "", false, profile.info.languageName, icon, false, false, click)
        }
    }

    private fun logLiveSignatures(n: Class<*>, itemCls: Class<*>) {
        val names = setOf("l3", "m3", "p3", "u0")
        n.declaredMethods.filter { it.name in names }.sortedBy { it.name }.forEach { m ->
            log("live model.N.${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
        }
        itemCls.declaredMethods.filter { it.name == "invoke" }.forEach { m ->
            log("live utils.v0\$f.invoke(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}")
        }
    }

    /** The layout WeType is showing right now, or null when it cannot be read. */
    fun layoutId(): Int? = runCatching { callN("u0") as Int }.onFailure { log("layoutId: $it") }.getOrNull()

    /** Chinese QWERTY (全键盘). */
    fun chineseLayoutId(): Int = keyboardId("d", 1)

    /** English QWERTY (英文). */
    fun englishLayoutId(): Int = keyboardId("l", 100)

    /** More-candidates panel (展开候选栏). */
    fun candidateLayoutId(): Int = 106

    /** Layouts that a multi-language profile is allowed to run on without deactivating. */
    fun isAllowedHostLayout(target: Int): Boolean =
        target == chineseLayoutId() || target == candidateLayoutId()

    private fun keyboardId(constant: String, fallback: Int): Int = runCatching {
        val t = XposedCompat.findClass("$pkg.keyboard.t", cl)
        XposedCompat.callMethod(XposedCompat.getStaticObjectField(t, constant) as Any, "c") as Int
    }.getOrDefault(fallback)

    fun switchLayout(layoutId: Int): Boolean = runCatching {
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
        log("layout -> $layoutId via l3, now showing ${layoutId()}")
        true
    }.onFailure { log("switchLayout($layoutId) failed: $it") }.getOrDefault(false)

    private fun modelN(): Any? = XposedCompat.getStaticObjectField(XposedCompat.findClass("$pkg.model.N", cl), "a")

    private fun callN(name: String, vararg args: Any?): Any? {
        val inst = modelN() ?: error("model.N not initialised")
        return XposedCompat.callMethod(inst, name, *args)
    }

    private fun onHostLayoutChanged(reason: String) {
        log("host layout changed: $reason")
        ctl.deactivate()
        LanguageSwitcher.refresh()
    }

    private fun log(m: String) = XposedCompat.log("WeTypeMulti/menu", m)
}
