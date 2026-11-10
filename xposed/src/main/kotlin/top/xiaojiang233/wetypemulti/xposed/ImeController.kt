package top.xiaojiang233.wetypemulti.xposed

import android.inputmethodservice.InputMethodService
import top.xiaojiang233.wetypemulti.common.dict.DictImporter
import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.engine.BuiltinJapaneseEngine
import top.xiaojiang233.wetypemulti.common.engine.Context
import top.xiaojiang233.wetypemulti.common.engine.InputEngine
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfile
import top.xiaojiang233.wetypemulti.common.keyboard.KeyboardProfileRegistry
import top.xiaojiang233.wetypemulti.common.language.Language
import top.xiaojiang233.wetypemulti.common.language.Languages
import top.xiaojiang233.wetypemulti.common.predict.Predictor
import java.io.File

/** Where composition, candidates and committed text go. Default: Android framework + [CandidateBar]. */
interface UiSink {
    fun update(ctx: Context?, suggestions: List<String>?)
    fun commit(text: String)
}

/**
 * Controller orchestrating IME input flow, active [KeyboardProfile],
 * key handling, and communication with WeType's candidate sink.
 */
class ImeController(private val config: HookConfig, private val dataDir: File) {
    var service: InputMethodService? = null
    var sink: UiSink = object : UiSink {
        override fun update(ctx: Context?, suggestions: List<String>?) {
            service?.currentInputConnection?.setComposingText(
                currentProfile.preeditFormatter.format(ctx?.preedit ?: ""),
                1
            )
            CandidateBar.update(service, ctx, suggestions)
        }
        override fun commit(text: String) {
            service?.currentInputConnection?.apply { setComposingText("", 1); commitText(text, 1) }
        }
    }

    var currentProfile: KeyboardProfile = KeyboardProfileRegistry.ZH_CN
        private set

    var active = false
        private set

    private var language: Language = Languages.byId(config.defaultLanguage) ?: Languages.JAPANESE
    private val dictionary = Dictionary()
    private val predictor = Predictor(dictionary)
    private val dir get() = File(dataDir, "wetype_multi").apply { mkdirs() }

    private val coordinator = EngineCoordinator(dir, dictionary, predictor)
    val userData by lazy { UserDataSync(dir, config.sync) }
    private var lastCommitted = ""
    @Volatile private var lastOwnCommitAt = 0L
    private val lock = Any()
    var onTrace: ((Boolean) -> Unit)? = null

    fun syncNow(override: SyncConfig? = null, probe: Boolean = false) {
        val tag = "WeTypeMulti"
        runCatching {
            override?.let { userData.configure(it) }
            if (probe) {
                val msg = "probe: " + userData.probe()
                android.util.Log.i(tag, msg); userData.writeStatus(msg); return
            }
            val r = userData.sync() ?: run {
                val msg = "sync skipped: backend=${userData.cfg.backend}"; android.util.Log.i(tag, msg); userData.writeStatus(msg); return
            }
            userData.store.applyTo(dictionary, predictor)
            synchronized(lock) { coordinator.dropEngine() }
            val msg = "sync ok: $r local_records=${userData.localRecords} backend=${userData.cfg.backend}"
            android.util.Log.i(tag, msg); userData.writeStatus(msg)
        }.onFailure {
            android.util.Log.w(tag, "sync failed", it); userData.writeStatus("sync failed: $it")
        }
    }

    private var startActiveApplied = false

    fun init(svc: InputMethodService) {
        service = svc
        ConfigRepository.init(dataDir)
        loadDictionaries()
        userData.store.applyTo(dictionary, predictor)
        if (userData.enabled) Thread { syncNow() }.start()

        if (config.startActive && !startActiveApplied) {
            startActiveApplied = true
            val profile = KeyboardProfileRegistry.findById(config.defaultLanguage)
                ?: KeyboardProfileRegistry.JA_JP
            synchronized(lock) { activate(profile) }
        }
        XposedCompat.log("WeTypeMulti", "init: startActive=${config.startActive} active=$active profile=${currentProfile.id}")

        ImeBroadcastManager.register(svc, this, dir)

        runCatching {
            RimeSetup::class.java.classLoader?.getResourceAsStream("rime/predict.tsv")?.use { s ->
                predictor.load(s.bufferedReader(Charsets.UTF_8).readText())
            }
        }
        File(dir, "predict.tsv").takeIf { it.isFile }?.let { predictor.load(it.readText()) }
    }

    fun loadDictionaries() {
        val ddir = File(dir, "dicts").apply { mkdirs() }
        ddir.listFiles()?.filter { it.isFile }?.forEach { f ->
            runCatching { f.reader().use { dictionary.addAll(DictImporter.import(it)) } }
        }
    }

    private fun engine(): InputEngine = coordinator.getEngine(currentProfile, language)

    fun probe(inputs: List<String>) = coordinator.probe(inputs, language)

    /** Turn on a specific [KeyboardProfile]. */
    fun activate(profile: KeyboardProfile) = synchronized(lock) {
        currentProfile = profile
        Languages.byId(profile.languageCode)?.let { language = it }
        coordinator.reset()
        coordinator.dropEngine()
        active = !profile.isHostHandled
        if (active) coordinator.warmUp()
        LayoutHooks.invalidate()
        commitCandidatesView()
        onLanguageChanged?.invoke(label)
        XposedCompat.log("WeTypeMulti", "activate(${profile.id}) -> active=$active language=${language.id}")
    }

    /** Activate by profile ID or language ID. */
    fun activate(id: String) = synchronized(lock) {
        val profile = KeyboardProfileRegistry.findById(id)
            ?: when (id) {
                "ja" -> KeyboardProfileRegistry.JA_JP
                "ko" -> KeyboardProfileRegistry.KO_KR
                "ru" -> KeyboardProfileRegistry.RU_RU
                else -> KeyboardProfileRegistry.ZH_CN
            }
        activate(profile)
    }

    /** Hand input back to host WeType keyboard. */
    fun deactivate() = synchronized(lock) {
        if (!active) return@synchronized
        currentProfile = KeyboardProfileRegistry.ZH_CN
        coordinator.reset()
        coordinator.dropEngine()
        active = false
        LayoutHooks.invalidate()
        commitCandidatesView()
        onLanguageChanged?.invoke(label)
        XposedCompat.log("WeTypeMulti", "deactivate() -> active=$active")
    }

    fun switchLanguage() = InputMode.cycle(this)

    val label: String get() = if (active) currentProfile.shortLabel else "中/英"
    var onLanguageChanged: ((String) -> Unit)? = null

    fun onKey(typed: Char): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        val c = if (typed == 'ー') '-' else typed
        if (c == ' ' && engine().context().preedit.isNotEmpty()) return@synchronized onSelectKey(0)

        var e = engine()
        var consumed = e.processKey(c)
        if (!consumed && c.isLetter() && e !is BuiltinJapaneseEngine) {
            XposedCompat.log("WeTypeMulti", "engine rejected '$c', falling back to builtin")
            e.reset()
            coordinator.dropEngine()
            e = engine()
            consumed = e.processKey(c)
        }
        if (consumed) {
            e.takeCommitted()?.let { commit(it) }
            commitCandidatesView()
            return@synchronized true
        }
        if (!isRomajiOrDigit(c) && !c.isWhitespace()) {
            flushComposition()
            commit(currentProfile.punctuationMapper.map(c.toString()))
            return@synchronized true
        }
        false
    }

    fun onLongVowel(): Boolean = onKey('-')

    fun onSessionReset(): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        val hasPreedit = coordinator.currentEngine?.context()?.preedit?.isNotEmpty() == true
        val since = android.os.SystemClock.uptimeMillis() - lastOwnCommitAt
        if (since < RESET_GRACE_MS) {
            XposedCompat.log("WeTypeMulti", "WeType session reset ${since} ms after commit: ignored")
            return@synchronized false
        }
        if (hasPreedit || lastCommitted.isNotEmpty()) {
            lastCommitted = ""
            coordinator.reset()
            commitCandidatesView()
            XposedCompat.log("WeTypeMulti", "WeType session reset: cleared")
            return@synchronized true
        }
        false
    }

    fun onLiteral(text: String) = synchronized(lock) {
        if (!active || text.isEmpty()) return@synchronized
        flushComposition()
        commit(currentProfile.punctuationMapper.map(text))
    }

    private fun isRomajiOrDigit(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun flushComposition() {
        if (engine().context().preedit.isEmpty()) return
        engine().select(0)?.let { commit(it) } ?: commit(engine().commitRaw())
    }

    fun onBackspace(): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        val e = engine()
        val hadPreedit = e.context().preedit.isNotEmpty()
        val r = e.backspace()
        if (r) commitCandidatesView()
        else if (!hadPreedit && lastCommitted.isNotEmpty()) {
            lastCommitted = ""
            commitCandidatesView()
        }
        r
    }

    fun onCancelledKey(): Boolean = onBackspace()

    fun onEnter(): Boolean = synchronized(lock) {
        if (!active || engine().context().preedit.isEmpty()) return@synchronized false
        commit(engine().commitRaw()); true
    }

    fun onSelectKey(index: Int): Boolean = synchronized(lock) {
        if (!active || engine().context().preedit.isEmpty()) return@synchronized false
        val t = engine().select(index) ?: return@synchronized true
        commit(t); true
    }

    fun onSelectCandidate(text: String?): Boolean = synchronized(lock) {
        if (!active || text.isNullOrEmpty()) return@synchronized false
        val preedit = engine().context().preedit
        if (preedit.isNotEmpty()) {
            val index = engine().context().candidates.indexOfFirst { it.text == text }
            val t = if (index >= 0) engine().select(index) else null
            if (t != null) commit(t)
            return@synchronized true
        } else {
            commit(text)
            return@synchronized true
        }
    }

    private fun commit(text: String) {
        if (lastCommitted.isNotEmpty() && text.isNotEmpty()) runCatching {
            val st = userData.store
            val n = (st.get(top.xiaojiang233.wetypemulti.common.sync.DataType.BIGRAM,
                "$lastCommitted${top.xiaojiang233.wetypemulti.common.sync.SEP}$text")?.value?.toIntOrNull() ?: 0) + 1
            st.setBigram(lastCommitted, text, n)
            userData.save()
        }
        lastCommitted = text
        lastOwnCommitAt = android.os.SystemClock.uptimeMillis()
        sink.commit(text)
        runCatching { File(dir, "predict.tsv").writeText(predictor.serialize()) }
        commitCandidatesView()
    }

    private fun commitCandidatesView() {
        val ctx = coordinator.currentEngine?.context()
        val composing = ctx != null && ctx.preedit.isNotEmpty()
        val suggestions = if (!composing && lastCommitted.isNotEmpty()) {
            coordinator.currentEngine?.suggest(lastCommitted)
        } else null
        sink.update(ctx, suggestions)
    }

    val isComposing get() = synchronized(lock) { active && coordinator.currentEngine?.context()?.preedit?.isNotEmpty() == true }
    fun candidateIndex(text: String?): Int = synchronized(lock) { coordinator.currentEngine?.context()?.candidates?.indexOfFirst { it.text == text } ?: -1 }

    fun reset() = synchronized(lock) {
        lastCommitted = ""
        coordinator.reset()
        commitCandidatesView()
    }

    private companion object {
        const val RESET_GRACE_MS = 400L
    }
}
