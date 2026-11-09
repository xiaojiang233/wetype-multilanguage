package top.xiaojiang233.wetypemulti.xposed

import android.content.res.AssetManager
import android.view.View
import android.view.ViewGroup
import top.xiaojiang233.wetypemulti.common.layout.JaLayoutRewriter
import top.xiaojiang233.wetypemulti.common.layout.JaLayoutRules
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy

/**
 * Serves a Japanese variant of WeType's Chinese QWERTY layout while Japanese mode is on.
 *
 * WeType reads each layout as `model.s0.a(Context, String)`: `context.getAssets().open(path)`, then Gson into
 * `KeyboardOriginData`. The parsed result is cached in the static maps `model.s0.b` and `model.s0.c`. Each keyboard
 * view reads the layout for its own keyboard type (`x1()` calls `model.s0.b(context, getKeyboardType(), mode)`), so
 * a mode change needs three steps:
 *  1. [invalidate] clears the static caches,
 *  2. [refresh] finds the Chinese keyboard view in the IME window and runs its refresh method again. That reads the
 *     layout through the accessor, which reaches `model.s0.a` and therefore the hook below,
 *  3. the `AssetManager.open` hook returns the rewritten JSON while Japanese is on.
 *
 * Names differ between the base APK and the hot update (see `assets/wetype-mapping.json`), so each lookup tries
 * both. Installed once per process, on the IME's class loader. Every failure is logged, never thrown.
 */
object LayoutHooks {
    @Volatile private var loader: ClassLoader? = null
    @Volatile private var japaneseOn: (() -> Boolean)? = null
    @Volatile private var windowRoot: (() -> View?)? = null

    /**
     * [japaneseOn] answers whether Japanese mode is on right now; [cl] is the IME's class loader; [windowRoot]
     * returns the IME window's root view (used to find the keyboard views).
     */
    fun install(japaneseOn: () -> Boolean, cl: ClassLoader, windowRoot: () -> View?) {
        if (loader != null) return
        loader = cl
        this.japaneseOn = japaneseOn
        this.windowRoot = windowRoot
        val opened = XposedCompat.hookMethod(AssetManager::class.java, "open", String::class.java, after = { p ->
            val name = p.args[0] as? String
            if (name != null && name in JaLayoutRules.TARGET_PATHS && japaneseOn()) {
                val original = p.result as? InputStream
                if (original != null) {
                    val text = original.use { it.readBytes() }.toString(Charsets.UTF_8)
                    val rewrite = JaLayoutRewriter.rewrite(text, JaLayoutRules.PATCHES)
                    XposedCompat.log("WeTypeMulti", "ja layout $name: applied=${rewrite.applied.size} missing=${rewrite.missing}")
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
     * Redraw the Chinese keyboard from a fresh read of the layout. Main thread only. The refresh method takes two
     * callbacks, so they are passed as no-op proxies instead of null (safe whether or not a build checks for null).
     */
    fun refresh() {
        val cl = loader ?: return
        val root = windowRoot?.invoke() ?: return
        val viewClass = runCatching { Class.forName(VIEW_CLASS, false, cl) }.getOrNull() ?: return
        val chinese = findInstances(root, viewClass).firstOrNull { isChineseQwerty(it, cl) }
        if (chinese == null) {
            if (japaneseOn?.invoke() == true) XposedCompat.log("WeTypeMulti", "Chinese keyboard view not in the IME window; layout not refreshed")
            return
        }
        runCatching {
            val m = viewClass.declaredMethods.first {
                it.name in REFRESH_NAMES && it.parameterCount == 2 && it.returnType == Void.TYPE
            }
            m.isAccessible = true
            m.invoke(chinese, *m.parameterTypes.map { noOp(it) }.toTypedArray())
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
