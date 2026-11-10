package top.xiaojiang233.wetypemulti.xposed

import android.content.res.AssetManager
import android.view.View
import android.view.ViewGroup
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfile
import top.xiaojiang233.wetypemulti.common.layout.LayoutRewriter
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy

/**
 * Dynamically rewrites WeType layout JSON files on-the-fly according to the active [KeyboardProfile].
 * Completely decoupled from specific languages; patches and target paths are defined by the active profile.
 */
object LayoutHooks {
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var activeProfile: (() -> KeyboardProfile?)? = null
    @Volatile private var windowRoot: (() -> View?)? = null

    /**
     * [activeProfile] returns the currently active keyboard profile (or null if host handles input directly).
     * [cl] is the IME's class loader; [windowRoot] returns the IME window's root view.
     */
    fun install(activeProfile: () -> KeyboardProfile?, cl: ClassLoader, windowRoot: () -> View?) {
        if (loader != null) return
        loader = cl
        this.activeProfile = activeProfile
        this.windowRoot = windowRoot
        val opened = XposedCompat.hookMethod(AssetManager::class.java, "open", String::class.java, after = { p ->
            val name = p.args[0] as? String
            val profile = activeProfile()
            if (name != null && profile != null && name in profile.targetLayoutPaths && profile.layoutPatches.isNotEmpty()) {
                val original = p.result as? InputStream
                if (original != null) {
                    val text = original.use { it.readBytes() }.toString(Charsets.UTF_8)
                    val rewrite = LayoutRewriter.rewrite(text, profile.layoutPatches)
                    XposedCompat.log("WeTypeMulti/layout", "patched layout $name for ${profile.id}: applied=${rewrite.applied.size} missing=${rewrite.missing}")
                    p.result = ByteArrayInputStream(rewrite.json.toByteArray(Charsets.UTF_8))
                }
            }
        })
        XposedCompat.log("WeTypeMulti", "layout open hook installed=$opened")
    }

    /** Forget WeType's parsed layouts. Call before the layout switch that should read the JSON again. */
    fun invalidate() {
        val cl = loader ?: return
        runCatching {
            val s0 = Class.forName(MODEL_CLASS, false, cl)
            for (name in listOf("b", "c")) {
                (s0.getDeclaredField(name).apply { isAccessible = true }.get(null) as? MutableMap<*, *>)?.clear()
            }
        }.onFailure { XposedCompat.log("WeTypeMulti", "layout cache not cleared: $it") }
    }

    /**
     * Redraw the keyboard view from a fresh read of the layout. Main thread only.
     */
    fun refresh() {
        val cl = loader ?: return
        val root = windowRoot?.invoke() ?: return
        val viewClass = runCatching { Class.forName(VIEW_CLASS, false, cl) }.getOrNull() ?: return
        val keyboard = findInstances(root, viewClass).firstOrNull { isChineseQwerty(it, cl) }
        if (keyboard == null) {
            if (activeProfile?.invoke() != null) {
                XposedCompat.log("WeTypeMulti", "keyboard view not in the IME window; layout not refreshed")
            }
            return
        }
        runCatching {
            val m = viewClass.declaredMethods.first {
                it.name in REFRESH_NAMES && it.parameterCount == 2 && it.returnType == Void.TYPE
            }
            m.isAccessible = true
            m.invoke(keyboard, *m.parameterTypes.map { noOp(it) }.toTypedArray())
        }.onFailure { XposedCompat.log("WeTypeMulti", "keyboard refresh failed: $it") }
    }

    /** True for the view whose keyboard type is `keyboard.t.d` (WeType's Chinese QWERTY). */
    private fun isChineseQwerty(view: Any, cl: ClassLoader): Boolean = runCatching {
        val type = XposedCompat.callMethod(view, "getKeyboardType")
        val chinese = XposedCompat.getStaticObjectField(XposedCompat.findClass(KEYBOARD_T, cl), "d")
        type != null && type === chinese
    }.getOrDefault(false)

    private fun findInstances(root: View, cls: Class<*>, out: MutableList<Any> = ArrayList()): List<Any> {
        if (cls.isInstance(root)) out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findInstances(root.getChildAt(i), cls, out)
        }
        return out
    }

    private fun noOp(type: Class<*>): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }

    private const val MODEL_CLASS = "com.tencent.wetype.plugin.hld.model.s0"
    private const val KEYBOARD_T = "com.tencent.wetype.plugin.hld.keyboard.t"
    private const val VIEW_CLASS = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.o"
    private val REFRESH_NAMES = setOf("s2", "q2")
}
