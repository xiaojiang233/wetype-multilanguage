package top.xiaojiang233.wetypemulti.xposed

import org.json.JSONObject
import java.io.File

/**
 * An extra hook on an app-specific (typically obfuscated) method, filled in after inspecting the
 * WeType APK. [action] is one of: "key" (arg at [argIndex] is a Char/Int keycode), "backspace",
 * "enter", "switchLanguage".
 */
data class ExtraHook(val className: String, val method: String, val action: String, val argIndex: Int = 0)

/** backend: none | file | webdav. */
data class SyncConfig(
    val backend: String = "none",
    val path: String = "", val url: String = "", val user: String = "", val password: String = "",
    val passphrase: String = "",
)

data class HookConfig(
    /**
     * Packages this module attaches to. A package attaches if it equals an entry exactly, OR starts
     * with "<entry>." — so a side-loaded copy of WeType renamed to e.g. "com.tencent.wetype.test"
     * (applicationId/manifest `package` changed, Java class namespace untouched — see
     * docs/wetype-parallel-install.md) attaches automatically with no rebuild, under both LSPosed
     * (once its scope includes that package) and LSPatch (embedded into that copy's APK).
     */
    val targetPackages: List<String> = listOf("com.tencent.wetype"),
    val defaultLanguage: String = "ja",
    /** Start with [defaultLanguage] already active, instead of WeType's native 中/英 (testing convenience). */
    val startActive: Boolean = false,
    /**
     * Load the native librime bridge at all. Diagnostic/kill-switch: our librime.so is built with a
     * different toolchain than WeType's own bundled native libs, so if it ever corrupts memory shared
     * with WeType's process (seen as an unrelated native SIGSEGV elsewhere in the host, not in our own
     * code), setting this false drops straight to the pure-Kotlin [top.xiaojiang233.wetypemulti.common.engine.BuiltinJapaneseEngine]
     * with no native code loaded into the host process at all.
     */
    val enableNativeRime: Boolean = true,
    val switchKeyCode: Int = 62, // KEYCODE_SPACE
    val switchCtrl: Boolean = true,
    val extraHooks: List<ExtraHook> = emptyList(),
    val sync: SyncConfig = SyncConfig(),
) {
    /** Exact match, or [pkg] is a dotted sub-name of a configured target (see [targetPackages] doc). */
    fun matches(pkg: String): Boolean = targetPackages.any { pkg == it || pkg.startsWith("$it.") }

    companion object {
        /** Parse [json]; falls back to defaults on any error. */
        fun parse(json: String): HookConfig = try {
            val o = JSONObject(json)
            val sk = o.optJSONObject("switchKey")
            val hooks = o.optJSONArray("extraHooks")
            val sy = o.optJSONObject("sync")
            HookConfig(
                targetPackages = o.optJSONArray("targetPackages")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    ?: listOf("com.tencent.wetype"),
                defaultLanguage = o.optString("defaultLanguage", "ja"),
                startActive = o.optBoolean("startActive", false),
                enableNativeRime = o.optBoolean("enableNativeRime", true),
                switchKeyCode = sk?.optInt("keyCode", 62) ?: 62,
                switchCtrl = sk?.optBoolean("ctrl", true) ?: true,
                extraHooks = hooks?.let { a ->
                    (0 until a.length()).map {
                        val h = a.getJSONObject(it)
                        ExtraHook(h.getString("class"), h.getString("method"), h.getString("action"), h.optInt("argIndex", 0))
                    }
                } ?: emptyList(),
                sync = sy?.let { SyncConfig(it.optString("backend", "none"), it.optString("path"), it.optString("url"),
                    it.optString("user"), it.optString("password"), it.optString("passphrase")) } ?: SyncConfig(),
            )
        } catch (_: Exception) { HookConfig() }

        /** User override at `<dataDir>/wetype_multi/hook_config.json`, so hooks can change without rebuilding. */
        fun load(dataDir: File, bundledJson: String): HookConfig {
            val f = File(dataDir, "wetype_multi/hook_config.json")
            return parse(if (f.isFile) f.readText() else bundledJson)
        }
    }
}
