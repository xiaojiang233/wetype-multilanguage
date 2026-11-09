package top.xiaojiang233.wetypemulti.xposed

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Modifier

/**
 * Diagnostics for finding a key on WeType's keyboard views: the view tree of the IME window, and the fields of every
 * `selfdraw` view together with the key object that holds one of the ids [KEY_IDS]. WeType is obfuscated and its member names differ
 * between the base APK and the hot update, so what to read has to be found by looking at a running device. Triggered by
 * the broadcast `top.xiaojiang233.wetypemulti.DUMP_VIEWS` (see [ImeController]); output goes to logcat, tag `WeTypeMulti/view`.
 */
object ViewDump {
    private const val TAG = "WeTypeMulti/view"
    /** The 中/英 key: `chSwitch` on the Chinese keyboard, `enSwitch` on the English one. */
    val KEY_IDS = setOf("chSwitch", "enSwitch")

    fun dump(root: View) {
        val screen = IntArray(2)
        root.getLocationOnScreen(screen)
        log("window root ${root.javaClass.name} ${root.width}x${root.height} at screen (${screen[0]},${screen[1]}) density=${root.resources.displayMetrics.density}")
        walk(root, 0)
        log("dump done")
    }

    private fun walk(v: View, depth: Int) {
        val at = IntArray(2)
        v.getLocationInWindow(at)
        val visible = Rect()
        val shown = v.getGlobalVisibleRect(visible)
        log("${"  ".repeat(depth)}${v.javaClass.name} ${v.width}x${v.height} at (${at[0]},${at[1]}) vis=${v.visibility} " +
            "shown=${v.isShown} alpha=${v.alpha}" + (if (shown) " visibleRect=$visible" else " notVisible") +
            (if (v.translationX != 0f || v.translationY != 0f) " translation=(${v.translationX},${v.translationY})" else ""))
        if (v.javaClass.name.contains(".selfdraw.")) dumpKeyboard(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
    }

    private fun dumpKeyboard(v: View) {
        log("  fields of ${v.javaClass.name}:")
        fieldLines(v, View::class.java).forEach { log("    $it") }
        val item = findKeyItem(v)
        if (item == null) {
            log("  no key object holding $KEY_IDS in ${v.javaClass.name}")
            return
        }
        log("  key object ${item.javaClass.name}:")
        fieldLines(item, Any::class.java).forEach { log("    $it") }
        // The key's data bean (colors, corner radius, shadow, ...); skip the key's back reference to its view.
        for (value in fieldValues(item)) {
            if (value != null && value !is View && isAppObject(value) && ".bean." in value.javaClass.name) {
                log("    -> ${value.javaClass.name}:")
                fieldLines(value, Any::class.java).forEach { log("       $it") }
            }
        }
    }

    /** The key object whose fields (or the fields of an object it holds) include one of the strings [KEY_IDS]. */
    fun findKeyItem(view: View): Any? {
        for (value in fieldValues(view, View::class.java)) {
            when (value) {
                is Map<*, *> -> for ((k, e) in value) if (e != null && (k in KEY_IDS || holdsString(e))) return e
                is Collection<*> -> for (e in value) if (e != null && holdsString(e)) return e
                is Array<*> -> for (e in value) if (e != null && holdsString(e)) return e
            }
        }
        return null
    }

    private fun holdsString(obj: Any): Boolean {
        if (!isAppObject(obj)) return false
        for (value in fieldValues(obj)) {
            if (value in KEY_IDS) return true
            if (value != null && isAppObject(value) && fieldValues(value).any { it in KEY_IDS }) return true
        }
        return false
    }

    /** True for classes that belong to the app (WeType or this module), false for framework and java.* objects. */
    private fun isAppObject(o: Any): Boolean {
        val n = o.javaClass.name
        return !(n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.") || n.startsWith("javax.") ||
            n.startsWith("dalvik.") || n.startsWith("libcore."))
    }

    private fun fieldValues(obj: Any, stopAt: Class<*> = Any::class.java): List<Any?> {
        val out = ArrayList<Any?>()
        var c: Class<*>? = obj.javaClass
        while (c != null && c != stopAt && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                out += runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
            }
            c = c.superclass
        }
        return out
    }

    /** Declared fields of [obj]'s class and its app superclasses; the framework's own (View, LinearLayout, ...) are not listed. */
    private fun fieldLines(obj: Any, stopAt: Class<*>): List<String> {
        val out = ArrayList<String>()
        var c: Class<*>? = obj.javaClass
        while (c != null && c != stopAt && c != Any::class.java && !c.name.startsWith("android.")) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                val value = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull()
                out += "${c.simpleName}.${f.name}: ${f.type.simpleName} = ${summary(value)}"
            }
            c = c.superclass
        }
        return out
    }

    private fun summary(value: Any?): String = when (value) {
        null -> "null"
        is Rect, is RectF, is Number, is Boolean, is Char, is Enum<*> -> value.toString()
        is String -> "\"" + value.take(40) + "\""
        is Map<*, *> -> "Map(${value.size}) keys=" + value.keys.take(8).joinToString(",")
        is Collection<*> -> "${value.javaClass.simpleName}(${value.size}) first=" + (value.firstOrNull()?.javaClass?.name ?: "-")
        is Array<*> -> "Array(${value.size}) of ${value.javaClass.componentType?.name}"
        else -> value.javaClass.name
    }

    private fun log(m: String) = XposedCompat.log(TAG, m)
}
