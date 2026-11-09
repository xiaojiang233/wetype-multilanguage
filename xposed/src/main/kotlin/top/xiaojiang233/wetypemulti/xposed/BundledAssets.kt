package top.xiaojiang233.wetypemulti.xposed

/** Reads files bundled in this module's APK without needing a Context (works under LSPosed and LSPatch). */
object BundledAssets {
    fun read(name: String): String = try {
        BundledAssets::class.java.classLoader?.getResourceAsStream("assets/$name")?.bufferedReader()?.readText() ?: "{}"
    } catch (_: Exception) { "{}" }
}
