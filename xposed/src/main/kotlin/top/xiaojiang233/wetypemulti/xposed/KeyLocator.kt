package top.xiaojiang233.wetypemulti.xposed

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a key of WeType's self-drawn keyboard by what it is, not by what R8 called it.
 *
 * WeType draws every key itself, in one view per keyboard, and keeps a layout object for each key (`selfdraw.j`). Member
 * names are obfuscated and differ between the base APK and the hot update, so none of them is used here. What does not
 * depend on a name:
 *  - the layout object stores its key id (`chSwitch` on the Chinese keyboard, `enSwitch` on the English one) as a plain String,
 *  - it holds two [Rect]s in the keyboard view's own pixel coordinates: the touch cell and the drawn button (the smaller one),
 *  - the keyboard view keeps its layout objects in a Map, List or array field.
 * So the key is found by value (the String) and by type (the Rects). The [Field]s found are remembered, which turns the
 * steady state into a few field reads; a layout change that replaces the objects, or a keyboard that is no longer shown,
 * sends it back to the full search. The search only ever looks at views that are shown, so a hidden keyboard of another
 * layout (English, symbols) is never mistaken for the current one.
 */
internal class KeyLocator(private val keyIds: Set<String>) {
    /** A located key. Both rects are in [view]'s pixel coordinates; [shadow] and [corner] are in WeType's 1080-wide units. */
    class Hit(val view: View, val cell: Rect, val key: Rect, val shadow: Float?, val corner: Float?)

    private class Bean(val holder: Field, val shadow: Field?, val corner: Field?)

    private class Path(
        val view: WeakReference<View>,
        val container: WeakReference<Any>,
        val mapKey: Any?,
        val index: Int,
        val idFields: List<Field>,
        val cellField: Field,
        val keyField: Field,
        val bean: Bean?,
    )

    private var path: Path? = null
    /** Views searched without finding the key, with the time of the search, so a full search is not repeated on every layout pass. */
    private val searched = WeakHashMap<View, Long>()

    /** Main thread only. Null when no shown keyboard view has the key. */
    fun locate(root: View): Hit? {
        path?.let { p -> read(p)?.let { return it } }
        path = null
        val (found, hit) = search(root) ?: return null
        path = found
        return hit
    }

    private fun read(p: Path): Hit? = runCatching {
        val view = p.view.get() ?: return null
        if (!view.isAttachedToWindow || !view.isShown) return null
        val item = when (val c = p.container.get()) {
            is Map<*, *> -> c[p.mapKey]
            is List<*> -> c.getOrNull(p.index)
            is Array<*> -> c.getOrNull(p.index)
            else -> null
        } ?: return null
        if (!p.cellField.declaringClass.isInstance(item) || !idMatches(item, p.idFields)) return null
        val cell = p.cellField.get(item) as? Rect ?: return null
        val key = p.keyField.get(item) as? Rect ?: return null
        if (!isValidGeometry(view, cell, key)) return null
        hit(view, item, cell, key, p.bean)
    }.getOrNull()

    private fun isValidGeometry(view: View, cell: Rect, key: Rect): Boolean {
        if (cell.isEmpty || key.isEmpty) return false
        // 1. Drawn button must be inside the touch cell (with 4px margin for rounding)
        val margin = 4
        if (key.left < cell.left - margin || key.right > cell.right + margin ||
            key.top < cell.top - margin || key.bottom > cell.bottom + margin) return false
        // 2. Cell must be within the keyboard view bounds
        if (cell.left < 0 || cell.top < 0 || cell.right > view.width || cell.bottom > view.height) return false
        // 3. Cell width must be reasonable for a single key (roughly 0.04 to 0.40 of keyboard view width)
        if (view.width > 0) {
            val wRatio = cell.width().toFloat() / view.width.toFloat()
            if (wRatio !in 0.04f..0.40f) return false
        }
        // 4. Cell height must be reasonable (~4 rows in keyboard, so 0.10 to 0.50 of height)
        if (view.height > 0) {
            val hRatio = cell.height().toFloat() / view.height.toFloat()
            if (hRatio !in 0.10f..0.50f) return false
        }
        return true
    }

    private fun hit(view: View, item: Any, cell: Rect, key: Rect, bean: Bean?): Hit {
        val data = bean?.holder?.get(item)
        return Hit(
            view, Rect(cell), Rect(key),
            data?.let { d -> bean.shadow?.get(d) as? Number }?.toFloat(),
            data?.let { d -> bean.corner?.get(d) as? Number }?.toFloat(),
        )
    }

    private fun search(root: View): Pair<Path, Hit>? {
        var result: Pair<Path, Hit>? = null
        val now = SystemClock.uptimeMillis()
        fun walk(v: View) {
            if (result != null || v.visibility != View.VISIBLE) return
            val last = searched[v]
            val due = last == null || now - last > RETRY_MS
            if (v.isShown && v.width >= root.width / 2 && v.height > 0 && isAppClass(v.javaClass) && due) {
                result = findIn(v)
                if (result == null) searched[v] = now
            }
            if (result == null && v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return result
    }

    private fun findIn(view: View): Pair<Path, Hit>? {
        var c: Class<*>? = view.javaClass
        while (c != null && isAppClass(c)) {
            for (f in declared(c)) {
                when (val v = runCatching { f.get(view) }.getOrNull()) {
                    is Map<*, *> -> for ((k, e) in v) match(view, v, k, -1, e)?.let { return it }
                    is List<*> -> v.forEachIndexed { i, e -> match(view, v, null, i, e)?.let { return it } }
                    is Array<*> -> v.forEachIndexed { i, e -> match(view, v, null, i, e)?.let { return it } }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun match(view: View, container: Any, mapKey: Any?, index: Int, item: Any?): Pair<Path, Hit>? {
        if (item == null || !isAppClass(item.javaClass)) return null
        val idFields = idFields(item) ?: return null
        // The two Rect fields: the larger is WeType's touch cell, the smaller the button that is drawn.
        val rects = declared(item.javaClass).filter { it.type == Rect::class.java }
            .mapNotNull { f -> (runCatching { f.get(item) }.getOrNull() as? Rect)?.takeIf { !it.isEmpty }?.let { f to it } }
        val cellField = rects.maxByOrNull { it.second.width() * it.second.height() } ?: return null
        val keyField = rects.minByOrNull { it.second.width() * it.second.height() } ?: return null
        if (!isValidGeometry(view, cellField.second, keyField.second)) return null
        val bean = beanOf(item)
        val path = Path(WeakReference(view), WeakReference(container), mapKey, index, idFields, cellField.first, keyField.first, bean)
        return path to hit(view, item, cellField.second, keyField.second, bean)
    }

    /** The field (or, one level down, the field of a field) that holds the key id, as the chain of fields leading to it. */
    private fun idFields(item: Any): List<Field>? {
        val own = declared(item.javaClass)
        own.firstOrNull { it.type == String::class.java && runCatching { it.get(item) }.getOrNull() in keyIds }?.let { return listOf(it) }
        for (f in own) {
            val inner = runCatching { f.get(item) }.getOrNull() ?: continue
            if (!isAppClass(inner.javaClass) || inner is View) continue
            declared(inner.javaClass).firstOrNull { it.type == String::class.java && runCatching { it.get(inner) }.getOrNull() in keyIds }
                ?.let { return listOf(f, it) }
        }
        return null
    }

    private fun idMatches(item: Any, chain: List<Field>): Boolean {
        var holder: Any? = item
        for (f in chain.dropLast(1)) holder = f.get(holder ?: return false)
        return chain.last().get(holder ?: return false) in keyIds
    }

    /** The key's data bean (WeType parses its layout JSON into it): corner radius and shadow height, if they can be found. */
    private fun beanOf(item: Any): Bean? {
        for (f in declared(item.javaClass)) {
            val data = runCatching { f.get(item) }.getOrNull() ?: continue
            if (data is View || !isAppClass(data.javaClass) || ".bean." !in data.javaClass.name) continue
            val numeric = declared(data.javaClass).filter { Number::class.java.isAssignableFrom(it.type) || it.type == Float::class.javaPrimitiveType || it.type == Int::class.javaPrimitiveType }
            val shadow = numeric.firstOrNull { it.name.equals("shadowHeight", ignoreCase = true) }
            val corner = numeric.firstOrNull { it.name.equals("bgCorner", ignoreCase = true) }
            if (shadow != null || corner != null) return Bean(f, shadow, corner)
        }
        return null
    }

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /** Non-static fields of [cls] and of its app superclasses, made accessible. */
    private fun declared(cls: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && isAppClass(c)) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                if (runCatching { f.isAccessible = true }.isSuccess) out += f
            }
            c = c.superclass
        }
        out
    }

    /** True for WeType's and this module's own classes; false for the framework, AndroidX, Kotlin and the JDK. */
    private fun isAppClass(c: Class<*>): Boolean {
        val n = c.name
        return !(n.startsWith("android.") || n.startsWith("androidx.") || n.startsWith("java.") || n.startsWith("javax.") ||
            n.startsWith("kotlin.") || n.startsWith("kotlinx.") || n.startsWith("dalvik.") || n.startsWith("com.android.") ||
            n.startsWith("com.google."))
    }

    private companion object {
        const val RETRY_MS = 500L
    }
}
