package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.mapping.ClassInfo
import top.xiaojiang233.wetypemulti.common.mapping.ClassSource
import top.xiaojiang233.wetypemulti.common.mapping.FieldInfo
import top.xiaojiang233.wetypemulti.common.mapping.MappingResolver
import top.xiaojiang233.wetypemulti.common.mapping.MethodInfo
import top.xiaojiang233.wetypemulti.common.mapping.Resolved
import top.xiaojiang233.wetypemulti.common.mapping.WeTypeMapping
import top.xiaojiang233.wetypemulti.common.mapping.summarize
import java.lang.reflect.Modifier

/** Live [ClassSource] backed by reflection. Reads declared members only and never initializes a class. */
class ReflectiveClassSource(private val cl: ClassLoader) : ClassSource {
    private val cache = HashMap<String, ClassInfo?>()

    override fun find(name: String): ClassInfo? = cache.getOrPut(name) {
        runCatching {
            val c = Class.forName(name, false, cl)
            ClassInfo(
                name = c.name,
                methods = c.declaredMethods.map { m ->
                    MethodInfo(m.name, m.parameterTypes.map { it.name }, m.returnType.name, Modifier.isStatic(m.modifiers))
                },
                fields = c.declaredFields.map { f -> FieldInfo(f.name, f.type.name, Modifier.isStatic(f.modifiers)) },
                isEnum = c.isEnum,
            )
        }.getOrNull()
    }
}

/**
 * Resolves the roles in `wetype-mapping.json` against the IME's class loader, once per process, and keeps the result.
 * Hooks read their class and member names and argument positions from here instead of hardcoding WeType's names.
 * A role that does not resolve makes [found] return null, so the hook that needs it stays off.
 */
object MappingRuntime {
    private const val TAG = "WeTypeMulti/map"
    private val lock = Any()
    @Volatile private var resolved: Map<String, Resolved>? = null

    /** Resolves every role and logs one summary line. Later calls return the first result. */
    fun load(cl: ClassLoader, weTypeVersionCode: Long?): Map<String, Resolved> = synchronized(lock) {
        resolved ?: runCatching {
            val mapping = WeTypeMapping.parse(BundledAssets.read("wetype-mapping.json"))
            val results = MappingResolver(ReflectiveClassSource(cl)).resolve(mapping)
            XposedCompat.log(TAG, "mapping v${mapping.versionCode} for WeType ${weTypeVersionCode ?: "unknown"}: ${summarize(results)}")
            results.associateBy { it.role }
        }.onFailure { XposedCompat.log(TAG, "mapping load failed: $it") }.getOrDefault(emptyMap())
            .also { resolved = it }
    }

    /** The class and member a role resolved to, or null when it did not resolve. */
    fun found(role: String): Resolved.Found? = resolved?.get(role) as? Resolved.Found

    /** Zero-based parameter index of a named argument of a resolved member (`args` in the JSON), or null. */
    fun argIndex(role: String, arg: String): Int? = found(role)?.args?.get(arg)
}
