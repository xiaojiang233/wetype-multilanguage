package top.xiaojiang233.wetypemulti.common.mapping

import com.google.gson.Gson

/**
 * Maps logical roles (for example "the layout switch method") to WeType's class and member names for one
 * WeType version. WeType is obfuscated and its names change between versions, so the code refers to roles and
 * the names live in a JSON file (`xposed/src/main/assets/wetype-mapping.json`). Updating WeType means editing
 * that file, not the Kotlin code.
 *
 * Resolution only needs facts that reflection can read: binary class names, member names, parameter and return
 * type names, static flags and enum-ness. So the same resolver runs against the live class loader at runtime and
 * against a fake [ClassSource] in unit tests.
 */

/**
 * One member matcher. A null field is a wildcard. Empty [names] matches by signature only. A matcher must find
 * exactly one member unless [allowMultiple] is true, which resolves to all of them (for example both `invoke`
 * bridge methods of a Kotlin lambda class).
 *
 * [args] names the parameter positions a hook needs (for example the argument that carries the punctuation WeType types
 * after a candidate), so the hook never hardcodes an index. Array parameters use the JVM name, which is what
 * `Class.getName()` returns: `[B` for `byte[]`, `[Lcom.x.Y;` for `Y[]`.
 */
data class MemberSpec(
    val kind: String? = null,
    val names: List<String>? = null,
    val params: List<String>? = null,
    val returns: String? = null,
    val isStatic: Boolean? = null,
    val allowMultiple: Boolean? = null,
    /** Named parameter positions of the matched method, for example `followUp` -> 3 (zero-based). */
    val args: Map<String, Int>? = null,
) {
    val isField: Boolean get() = kind == "field"
    val nameList: List<String> get() = names.orEmpty()
}

/**
 * One role. [classes] are candidate binary names (nested classes use `$`), most likely first. The role resolves
 * to the first candidate that has a member matching one of [members] (tried in order). With no [members] the
 * role is the class itself. With [enumConstant] the role is that static constant of an enum class.
 */
data class RoleSpec(
    val classes: List<String>? = null,
    val members: List<MemberSpec>? = null,
    val enumConstant: String? = null,
) {
    val candidates: List<String> get() = classes.orEmpty()
    val alternatives: List<MemberSpec> get() = members.orEmpty()
}

data class WeTypeMapping(
    val versionCode: Long? = null,
    val roles: Map<String, RoleSpec>? = null,
) {
    val allRoles: Map<String, RoleSpec> get() = roles.orEmpty()

    companion object {
        /** Unreadable JSON gives an empty mapping, so every role reports as missing instead of crashing. */
        fun parse(json: String): WeTypeMapping =
            runCatching { Gson().fromJson(json, WeTypeMapping::class.java) }.getOrNull() ?: WeTypeMapping()
    }
}

data class MethodInfo(val name: String, val params: List<String>, val returns: String, val isStatic: Boolean)

data class FieldInfo(val name: String, val type: String, val isStatic: Boolean)

data class ClassInfo(
    val name: String,
    val methods: List<MethodInfo> = emptyList(),
    val fields: List<FieldInfo> = emptyList(),
    val isEnum: Boolean = false,
)

/** Where class facts come from: the live class loader at runtime, a fake in tests. Null = class not present. */
fun interface ClassSource {
    fun find(name: String): ClassInfo?
}

sealed interface Resolved {
    val role: String

    /** [member] is the matched member name(s), or null when the role is the class itself. [via] says how it matched. */
    data class Found(
        override val role: String,
        val className: String,
        val member: String?,
        val via: String,
        /** Argument positions from the matched member's `args` (empty for class-only roles). */
        val args: Map<String, Int> = emptyMap(),
    ) : Resolved

    data class Missing(override val role: String, val reason: String) : Resolved
}

class MappingResolver(private val source: ClassSource) {

    fun resolve(mapping: WeTypeMapping): List<Resolved> =
        mapping.allRoles.map { (role, spec) -> resolveRole(role, spec) }

    fun resolveRole(role: String, spec: RoleSpec): Resolved {
        if (spec.candidates.isEmpty()) return Resolved.Missing(role, "no class candidates")
        val notes = ArrayList<String>()
        for (cls in spec.candidates) {
            val info = source.find(cls)
            if (info == null) {
                notes += "$cls not loaded"
                continue
            }
            val constant = spec.enumConstant
            if (constant != null) {
                if (info.isEnum && info.fields.any { it.name == constant && it.isStatic }) {
                    return Resolved.Found(role, cls, constant, "enum constant")
                }
                notes += "$cls has no enum constant $constant"
                continue
            }
            if (spec.alternatives.isEmpty()) return Resolved.Found(role, cls, null, "class")
            for (m in spec.alternatives) {
                val hits = matchMember(info, m)
                when {
                    hits.isEmpty() -> notes += "$cls: no ${describe(m)}"
                    hits.size > 1 && m.allowMultiple != true -> notes += "$cls: ${hits.size} matches for ${describe(m)}"
                    else -> return Resolved.Found(role, cls, hits.joinToString(","), describe(m), m.args.orEmpty())
                }
            }
        }
        return Resolved.Missing(role, notes.joinToString("; ").ifEmpty { "no candidate matched" })
    }

    private fun matchMember(info: ClassInfo, m: MemberSpec): List<String> {
        fun nameOk(name: String) = m.nameList.isEmpty() || name in m.nameList
        return if (m.isField) {
            info.fields.filter { nameOk(it.name) && (m.isStatic == null || it.isStatic == m.isStatic) && (m.returns == null || it.type == m.returns) }
                .map { it.name }
        } else {
            info.methods.filter {
                nameOk(it.name) && (m.params == null || it.params == m.params) && (m.returns == null || it.returns == m.returns) &&
                    (m.isStatic == null || it.isStatic == m.isStatic)
            }.map { it.name }
        }
    }

    private fun describe(m: MemberSpec): String {
        val names = m.nameList.ifEmpty { listOf("*") }.joinToString("|")
        return if (m.isField) {
            "field $names" + (m.returns?.let { ": $it" } ?: "")
        } else {
            "$names(${m.params?.joinToString(", ") ?: "*"})" + (m.returns?.let { ": $it" } ?: "")
        }
    }
}

/** One line for logs: how many roles resolved, and which did not (with the reason). */
fun summarize(results: List<Resolved>): String {
    val found = results.count { it is Resolved.Found }
    val missing = results.filterIsInstance<Resolved.Missing>().joinToString("; ") { "${it.role} (${it.reason})" }
    return "resolved $found/${results.size}" + if (missing.isEmpty()) "" else "; missing: $missing"
}
