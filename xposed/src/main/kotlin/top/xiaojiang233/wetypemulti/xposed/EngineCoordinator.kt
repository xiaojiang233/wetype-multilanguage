package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.engine.BuiltinJapaneseEngine
import top.xiaojiang233.wetypemulti.common.engine.InputEngine
import top.xiaojiang233.wetypemulti.common.engine.RimeInputEngine
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfile
import top.xiaojiang233.wetypemulti.common.language.Language
import top.xiaojiang233.wetypemulti.common.predict.Predictor
import java.io.File

/**
 * Coordinates InputEngine instances, Rime background deployment,
 * and seamless fallback between Rime and pure-Kotlin engines.
 */
class EngineCoordinator(
    private val dataDir: File,
    private val dictionary: Dictionary,
    private val predictor: Predictor,
) {
    private var engine: InputEngine? = null
    /** True while [engine] is the built-in engine only because Rime is still compiling. */
    private var standIn = false

    /**
     * Get or create the active [InputEngine] for [profile].
     * Never blocks on deployment: falls back to builtin if Rime is compiling.
     */
    fun getEngine(profile: KeyboardProfile, language: Language): InputEngine {
        var e = engine
        if (e != null && standIn && RimeSetup.ready() != null && e.context().preedit.isEmpty()) {
            dropEngine()
            e = null
        }
        return e ?: createEngine(profile, language).also { engine = it }
    }

    private fun createEngine(profile: KeyboardProfile, language: Language): InputEngine {
        val bridge = RimeSetup.ready()
        if (bridge != null) {
            val rime = runCatching {
                RimeInputEngine(bridge, language) { c -> predictor.suggest(c) }
            }.onFailure {
                XposedCompat.log("WeTypeMulti/engine", "rime session failed, using builtin: $it")
            }.getOrNull()

            if (rime != null) {
                standIn = false
                XposedCompat.log("WeTypeMulti/engine", "active engine: RimeInputEngine(${profile.id})")
                return rime
            }
        } else {
            RimeSetup.start(dataDir, dictionary.entries()) {
                XposedCompat.log("WeTypeMulti/engine", "rime deploy finished: ${RimeSetup.state}")
            }
        }

        standIn = RimeSetup.state == RimeSetup.State.BUILDING
        XposedCompat.log("WeTypeMulti/engine", "active engine: BuiltinJapaneseEngine (standIn=$standIn)")
        return BuiltinJapaneseEngine(dictionary, predictor, language)
    }

    /** Free the active engine and any allocated Rime session. */
    fun dropEngine() {
        (engine as? RimeInputEngine)?.let { runCatching { it.close() } }
        engine = null
        standIn = false
    }

    fun warmUp() {
        RimeSetup.start(dataDir, dictionary.entries(), retry = true) {
            XposedCompat.log("WeTypeMulti/engine", "rime deploy finished: ${RimeSetup.state}")
        }
    }

    /** Diagnostic probe running test strings through Rime. */
    fun probe(inputs: List<String>, language: Language) {
        val tag = "WeTypeMulti/probe"
        RimeSetup.start(dataDir, dictionary.entries(), retry = true)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val bridge = RimeSetup.await(300_000)
        if (bridge == null) {
            XposedCompat.log(tag, "rime is not ready: ${RimeSetup.state}")
            return
        }
        XposedCompat.log(tag, "rime ready, waited ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        for (s in inputs) {
            val e = RimeInputEngine(bridge, language) { emptyList() }
            val start = System.nanoTime()
            for (c in s) e.processKey(c)
            val ctx = e.context()
            val ms = (System.nanoTime() - start) / 1_000_000.0
            XposedCompat.log(tag, "'$s' -> '${ctx.preedit}' ${ctx.candidates.joinToString(" | ") { it.text }} [%.1f ms]".format(ms))
            e.reset()
            e.close()
        }
        XposedCompat.log(tag, "probe done")
    }

    fun reset() {
        engine?.reset()
    }

    val currentEngine: InputEngine? get() = engine
}
