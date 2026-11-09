package top.xiaojiang233.wetypemulti.xposed

import android.os.Process
import android.os.SystemClock
import top.xiaojiang233.wetypemulti.common.dict.DictImporter
import top.xiaojiang233.wetypemulti.common.dict.Entry
import java.io.File
import java.security.MessageDigest

/**
 * Lays out Rime's shared/user data dirs and deploys (compiles) the Japanese schema.
 *
 * Compiling the dictionaries takes seconds and uses a lot of CPU, so it never runs on a key thread: [start] does it on a
 * background thread and [ready] hands out the bridge once it is deployed. Until then [ImeController] types with the
 * built-in engine. The compiled files live in `<user>/build` and survive process restarts; they are rebuilt only when
 * the schema, a dictionary or the user dictionary changed (a content hash is kept in `<user>/build.stamp`).
 *
 * Developer override: files in `<dataDir>/wetype_multi/rime/dev/` replace the bundled resource of the same name, so
 * a schema or dictionary can be tried on a device with `adb push` and no rebuild of the module.
 */
object RimeSetup {
    enum class State { IDLE, BUILDING, READY, FAILED }

    private val resources = listOf(
        "japanese.schema.yaml",
        "japanese.dict.yaml",
        "japanese.kana.dict.yaml",
        "japanese.core.dict.yaml",
        "default.yaml"
    )
    private const val USER_DICT = "japanese_user.dict.yaml"
    /** Bump when something outside the files themselves changes how they compile (the stamp covers the files). */
    private const val FORMAT = 2

    @Volatile var state: State = State.IDLE
        private set
    @Volatile private var bridge: NativeRimeBridge? = null

    /** The deployed bridge, or null while it is still building (or when librime is unavailable). Never blocks. */
    fun ready(): NativeRimeBridge? = if (state == State.READY) bridge else null

    /**
     * Starts the deploy on a background thread, once. Returns at once; [onFinished] runs on the deploy thread when it
     * ended, whatever the outcome (check [ready]). A failed deploy is only retried when [retry] is set.
     */
    @Synchronized fun start(root: File, userEntries: List<Entry>, retry: Boolean = false, onFinished: () -> Unit = {}) {
        when (state) {
            State.BUILDING, State.READY -> return
            State.FAILED -> if (!retry) return
            State.IDLE -> {}
        }
        if (!NativeRimeBridge.available) {
            state = State.FAILED
            log("librime is not loaded; Japanese uses the built-in engine")
            return
        }
        state = State.BUILDING
        val entries = userEntries.toList()
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            val t0 = SystemClock.elapsedRealtime()
            try {
                val b = deploy(root, entries)
                bridge = b
                state = State.READY
                log("rime ready after ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (t: Throwable) {
                state = State.FAILED
                log("rime deploy failed after ${SystemClock.elapsedRealtime() - t0} ms: $t ${t.cause ?: ""}")
            }
            runCatching(onFinished).onFailure { log("onFinished: $it") }
        }, "wetype-multi-rime").apply { isDaemon = true; start() }
    }

    /** Waits for a started deploy to end (diagnostics only: never call this from a key or UI thread). */
    fun await(timeoutMs: Long): NativeRimeBridge? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (state == State.BUILDING && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        return ready()
    }

    private fun deploy(root: File, userEntries: List<Entry>): NativeRimeBridge {
        val shared = File(root, "rime/shared").apply { mkdirs() }
        val user = File(root, "rime/user").apply { mkdirs() }
        val files = sources(File(root, "rime/dev"), userEntries)

        // Only touch a file whose content changed: its modification time is what Rime compares, too.
        var rewritten = false
        for ((name, bytes) in files) if (writeIfChanged(File(shared, name), bytes)) rewritten = true
        if (USER_DICT !in files && File(shared, USER_DICT).delete()) rewritten = true

        val stamp = stampOf(files)
        val stampFile = File(user, "build.stamp")
        val current = !rewritten && stampFile.isFile && stampFile.readText() == stamp &&
            File(user, "build/japanese.prism.bin").isFile
        if (!current) {
            File(user, "build").deleteRecursively()
            stampFile.delete()
        }
        trimLogs(user)
        log(if (current) "rime: compiled files are current" else "rime: compiling ${files.values.sumOf { it.size } / 1024} KiB of sources")

        val b = NativeRimeBridge()
        b.initialize(shared.path, user.path)
        try {
            if (!b.deploy()) throw IllegalStateException("rime deploy returned false")
            stampFile.writeText(stamp)
        } catch (t: Throwable) {
            runCatching { b.finalizeRime() } // so a retry can initialize again
            throw t
        }
        return b
    }

    /** Every file Rime needs, by name: the bundled resource or its developer override, plus the user dictionary. */
    private fun sources(dev: File, userEntries: List<Entry>): LinkedHashMap<String, ByteArray> {
        val cl = RimeSetup::class.java.classLoader!!
        val out = LinkedHashMap<String, ByteArray>()
        for (n in resources) {
            val bytes = File(dev, n).takeIf { it.isFile }?.readBytes()
                ?: cl.getResourceAsStream("rime/$n")?.use { it.readBytes() }
                ?: continue
            out[n] = bytes
        }
        if (userEntries.isNotEmpty()) {
            out[USER_DICT] = DictImporter.toRimeDict("japanese_user", userEntries).toByteArray(Charsets.UTF_8)
            // User dictionaries are pulled into the main dictionary through Rime's import_tables.
            out["japanese.dict.yaml"]?.let { out["japanese.dict.yaml"] = importUserTable(it.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) }
        }
        return out
    }

    private fun importUserTable(text: String): String =
        if ("import_tables:" in text) text.replace("import_tables:\n", "import_tables:\n  - japanese_user\n")
        else text.replace("\n...\n", "\nimport_tables:\n  - japanese_user\n...\n")

    private fun stampOf(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("format=$FORMAT".toByteArray())
        for ((name, bytes) in files) {
            md.update(name.toByteArray())
            md.update(bytes.size.toString().toByteArray())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeIfChanged(f: File, bytes: ByteArray): Boolean {
        if (f.isFile && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return false
        f.writeBytes(bytes)
        return true
    }

    /** glog opens a new file in the user dir at every process start; keep only the newest few. */
    private fun trimLogs(user: File) {
        val logs = user.listFiles { f -> f.isFile && f.name.startsWith("WeTypeMulti.") && ".log." in f.name } ?: return
        logs.sortedByDescending { it.lastModified() }.drop(4).forEach { it.delete() }
    }

    private fun log(m: String) = XposedCompat.log("WeTypeMulti", m)
}
