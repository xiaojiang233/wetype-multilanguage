package top.xiaojiang233.wetypemulti.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Modern (io.github.libxposed) API glue. [xposed] is set once from [MainHook.onModuleLoaded];
 * everything here is a no-op fallback (plain [android.util.Log]) before that, which only matters
 * for log calls that could theoretically happen before the framework attaches us (shouldn't, in
 * practice, since nothing else runs before [MainHook.onModuleLoaded]).
 */
object XposedCompat {
    @Volatile var xposed: XposedInterface? = null

    fun log(tag: String, m: String) {
        val x = xposed
        if (x != null) x.log(android.util.Log.INFO, tag, m) else android.util.Log.i(tag, m)
    }

    /** Hook every declared method of [cls] named [name] (mirrors legacy `hookAllMethods` by name). */
    fun hookAllMethods(cls: Class<*>, name: String, before: ((HookParam) -> Unit)? = null, after: ((HookParam) -> Unit)? = null): Int {
        val x = xposed ?: return 0
        val methods = cls.declaredMethods.filter { it.name == name }
        for (m in methods) {
            runCatching {
                m.isAccessible = true
                x.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(XposedInterface.Hooker { chain -> runHook(chain, before, after) })
            }.onFailure { log("WeTypeMulti", "hook ${cls.simpleName}#$name failed: $it") }
        }
        return methods.size
    }

    /** Hook one exact overload (mirrors legacy `findAndHookMethod`). */
    fun hookMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>, before: ((HookParam) -> Unit)? = null, after: ((HookParam) -> Unit)? = null): Boolean {
        val x = xposed ?: return false
        return runCatching {
            val m = cls.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
            x.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(XposedInterface.Hooker { chain -> runHook(chain, before, after) })
            true
        }.getOrElse { log("WeTypeMulti", "hook ${cls.simpleName}#$name failed: $it"); false }
    }

    private fun runHook(chain: XposedInterface.Chain, before: ((HookParam) -> Unit)?, after: ((HookParam) -> Unit)?): Any? {
        val p = HookParam(chain)
        before?.let { cb -> runCatching { cb(p) }.onFailure { e -> log("WeTypeMulti", "hook before: $e") } }
        if (p.skipOriginal) return p.result
        val na = p.newArgs
        p.result = if (na != null) chain.proceed(na) else chain.proceed()
        after?.let { cb -> runCatching { cb(p) }.onFailure { e -> log("WeTypeMulti", "hook after: $e") } }
        return p.result
    }

    /** Mirrors the subset of `XC_MethodHook.MethodHookParam` our hooks actually use. */
    class HookParam internal constructor(chain: XposedInterface.Chain) {
        val thisObject: Any? = chain.thisObject
        val args: List<Any?> = chain.args
        var result: Any? = null
        /** Set alongside [result] in a `before` callback to skip calling the original method. */
        var skipOriginal: Boolean = false
        /** Set in a `before` callback to call the original method with these arguments instead. */
        var newArgs: Array<Any?>? = null
    }

    // --- Plain-reflection replacements for the legacy XposedHelpers convenience calls we used. ---

    fun findClass(name: String, cl: ClassLoader): Class<*> = Class.forName(name, false, cl)

    fun getStaticObjectField(cls: Class<*>, name: String): Any? = field(cls, name).get(null)
    fun getStaticIntField(cls: Class<*>, name: String): Int = field(cls, name).getInt(null)

    fun getObjectField(obj: Any, name: String): Any? = field(obj.javaClass, name).get(obj)
    fun setStaticObjectField(cls: Class<*>, name: String, value: Any?) = field(cls, name).set(null, value)
    fun setObjectField(obj: Any, name: String, value: Any?) = field(obj.javaClass, name).set(obj, value)
    fun setIntField(obj: Any, name: String, value: Int) = field(obj.javaClass, name).setInt(obj, value)
    fun setLongField(obj: Any, name: String, value: Long) = field(obj.javaClass, name).setLong(obj, value)

    private fun field(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { return c.getDeclaredField(name).also { it.isAccessible = true } }
            c = c.superclass
        }
        throw NoSuchFieldException("$name on $cls")
    }

    fun callMethod(obj: Any, name: String, vararg args: Any?): Any? {
        val m = findMethod(obj.javaClass, name, args) ?: throw NoSuchMethodException("$name(${args.size}) on ${obj.javaClass}")
        return m.invoke(obj, *args)
    }

    fun newInstance(cls: Class<*>, vararg args: Any?): Any {
        val c = findConstructor(cls, args) ?: throw NoSuchMethodException("<init>(${args.size}) on $cls")
        return c.newInstance(*args)
    }

    private fun findMethod(cls: Class<*>, name: String, args: Array<out Any?>): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name && m.parameterCount == args.size && paramsMatch(m.parameterTypes, args)) {
                    m.isAccessible = true; return m
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun findConstructor(cls: Class<*>, args: Array<out Any?>): Constructor<*>? =
        cls.declaredConstructors.firstOrNull { it.parameterCount == args.size && paramsMatch(it.parameterTypes, args) }
            ?.also { it.isAccessible = true }

    private fun paramsMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in types.indices) {
            val a = args[i] ?: continue // null matches any reference parameter
            if (!box(types[i]).isInstance(a)) return false
        }
        return true
    }

    private fun box(t: Class<*>): Class<*> = when (t) {
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> t
    }
}
