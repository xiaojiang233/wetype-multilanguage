package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.engine.Candidate
import top.xiaojiang233.wetypemulti.common.engine.Context
import top.xiaojiang233.wetypemulti.common.engine.RimeBridge

/** [RimeBridge] over librime via `libwetype_rime_jni.so` (built only when `-PrimeDir` is given). */
class NativeRimeBridge : RimeBridge {
    companion object {
        @Volatile private var loaded = false

        /**
         * Load librime + the JNI wrapper. Inside the target app the module's own lib dir is not on the
         * linker path, so try [libDirs] (from the Xposed module path) first, then the default path.
         */
        @Synchronized fun load(libDirs: List<java.io.File>): Boolean {
            if (loaded) return true
            for (d in libDirs) {
                val rime = java.io.File(d, "librime.so"); val jni = java.io.File(d, "libwetype_rime_jni.so")
                if (rime.isFile && jni.isFile) {
                    try { System.load(rime.path); System.load(jni.path); loaded = true; return true } catch (_: Throwable) {}
                }
            }
            loaded = try { System.loadLibrary("rime"); System.loadLibrary("wetype_rime_jni"); true } catch (_: Throwable) { false }
            return loaded
        }
        val available: Boolean get() = loaded
    }

    private external fun nInit(shared: String, user: String, app: String)
    private external fun nDeploy(): Boolean
    private external fun nCreateSession(): Long
    private external fun nDestroySession(s: Long)
    private external fun nSelectSchema(s: Long, id: String): Boolean
    private external fun nProcessKey(s: Long, code: Int, mask: Int): Boolean
    private external fun nContext(s: Long): Array<String>
    private external fun nSelectCandidate(s: Long, index: Int): Boolean
    private external fun nTakeCommit(s: Long): String?
    private external fun nClear(s: Long)
    private external fun nFinalize()

    override fun initialize(sharedDataDir: String, userDataDir: String, appName: String) = nInit(sharedDataDir, userDataDir, appName)
    override fun deploy() = nDeploy()
    override fun createSession() = nCreateSession()
    override fun destroySession(session: Long) = nDestroySession(session)
    override fun selectSchema(session: Long, schemaId: String) = nSelectSchema(session, schemaId)
    override fun processKey(session: Long, keycode: Int, mask: Int) = nProcessKey(session, keycode, mask)
    override fun context(session: Long): Context {
        val a = nContext(session)
        return Context(a.firstOrNull().orEmpty(), (1 until a.size step 2).map { Candidate(a[it], a.getOrElse(it + 1) { "" }) })
    }
    override fun selectCandidate(session: Long, index: Int) = nSelectCandidate(session, index)
    override fun takeCommit(session: Long) = nTakeCommit(session)
    override fun clearComposition(session: Long) = nClear(session)
    override fun finalizeRime() = nFinalize()
}
