package top.xiaojiang233.wetypemulti.xposed

import android.inputmethodservice.InputMethodService
import top.xiaojiang233.wetypemulti.common.dict.DictImporter
import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.engine.BuiltinJapaneseEngine
import top.xiaojiang233.wetypemulti.common.engine.Context
import top.xiaojiang233.wetypemulti.common.engine.InputEngine
import top.xiaojiang233.wetypemulti.common.engine.RimeInputEngine
import top.xiaojiang233.wetypemulti.common.language.Language
import top.xiaojiang233.wetypemulti.common.language.Languages
import top.xiaojiang233.wetypemulti.common.layout.JaPunctuation
import top.xiaojiang233.wetypemulti.common.predict.Predictor
import top.xiaojiang233.wetypemulti.common.romaji.JaPreedit
import java.io.File

/**
 * Glue between the hooked IME and the engine in :common. When [active] is false, every key falls
 * through to WeType untouched, so the module is invisible for Chinese/English.
 */
/** Where composition, candidates and committed text go. Default: Android framework + [CandidateBar]. */
interface UiSink {
    fun update(ctx: Context?, suggestions: List<String>?)
    fun commit(text: String)
}

class ImeController(private val config: HookConfig, private val dataDir: File) {
    var service: InputMethodService? = null
    /** Replaced by [WxhldHooks] so candidates appear in WeType's own candidate bar. */
    var sink: UiSink = object : UiSink {
        override fun update(ctx: Context?, suggestions: List<String>?) {
            service?.currentInputConnection?.setComposingText(JaPreedit.display(ctx?.preedit ?: ""), 1)
            CandidateBar.update(service, ctx, suggestions)
        }
        override fun commit(text: String) {
            service?.currentInputConnection?.apply { setComposingText("", 1); commitText(text, 1) }
        }
    }
    var active = false
        private set
    private var language: Language = Languages.byId(config.defaultLanguage) ?: Languages.JAPANESE
    private var engine: InputEngine? = null
    private val dictionary = Dictionary()
    private val predictor = Predictor(dictionary)
    private val dir get() = File(dataDir, "wetype_multi").apply { mkdirs() }

    val userData by lazy { UserDataSync(dir, config.sync) }
    private var lastCommitted = ""
    /** When this controller last committed or selected something ([commit]); see [onSessionReset]. */
    @Volatile private var lastOwnCommitAt = 0L
    /** Keys arrive on WeType's engine threads, the UI on main, sync on a worker: serialize all state changes. */
    private val lock = Any()
    /** Set by [WxhldHooks]: switches the diagnostic trace of WeType's engine API on or off (broadcast TRACE_API). */
    var onTrace: ((Boolean) -> Unit)? = null

    /**
     * Merge the sync backend into the live dictionary/predictor (blocking: run on a worker thread).
     * With [probe] it only reads the backend and reports; nothing is written anywhere.
     */
    fun syncNow(override: top.xiaojiang233.wetypemulti.xposed.SyncConfig? = null, probe: Boolean = false) {
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
            synchronized(lock) { dropEngine() } // rebuild so merged words take effect (Rime needs a restart to redeploy)
            val msg = "sync ok: $r local_records=${userData.localRecords} backend=${userData.cfg.backend}"
            android.util.Log.i(tag, msg); userData.writeStatus(msg)
        }.onFailure {
            android.util.Log.w(tag, "sync failed", it); userData.writeStatus("sync failed: $it")
        }
    }

    private var startActiveApplied = false
    // WeType's IME service can be torn down and recreated by the system at any time (low memory,
    // process restart after a crash elsewhere, ...), so [init] runs again on the *new* Service
    // instance. Registering our receivers again each time — without ever unregistering the old
    // ones — leaked a receiver per recreation (IntentReceiverLeaked). Register once per process,
    // on the long-lived application Context, not the short-lived IME service Context.
    private var receiversRegistered = false

    fun init(svc: InputMethodService) {
        service = svc
        loadDictionaries()
        userData.store.applyTo(dictionary, predictor)
        if (userData.enabled) Thread { syncNow() }.start()
        // Testing convenience: begin with the configured language already on, instead of requiring a
        // manual toggle first. Applied once per process so a user who switches back to 中/英 stays there.
        if (config.startActive && !startActiveApplied) {
            startActiveApplied = true
            synchronized(lock) { activate(config.defaultLanguage) }
        }
        XposedCompat.log("WeTypeMulti", "init: startActive=${config.startActive} active=$active language=${language.id}")
        if (receiversRegistered) return
        receiversRegistered = true
        val appCtx = svc.applicationContext
        // Soft-keyboard toggle: adb shell am broadcast -a top.xiaojiang233.wetypemulti.TOGGLE -p com.tencent.wetype
        runCatching {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { switchLanguage() }
            }
            val f = android.content.IntentFilter("top.xiaojiang233.wetypemulti.TOGGLE")
            val sr = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    // adb shell am broadcast -a ...SYNC -p com.tencent.wetype --es backend file --es path ... [--ez probe true]
                    val ov = i?.getStringExtra("backend")?.let { b ->
                        SyncConfig(b, i.getStringExtra("path").orEmpty(), i.getStringExtra("url").orEmpty(),
                            i.getStringExtra("user").orEmpty(), i.getStringExtra("password").orEmpty(),
                            i.getStringExtra("passphrase").orEmpty())
                    }
                    val probe = i?.getBooleanExtra("probe", false) == true
                    Thread { syncNow(ov, probe) }.start()
                }
            }
            val sf = android.content.IntentFilter("top.xiaojiang233.wetypemulti.SYNC")
            // Experiment (off unless broadcast): feed one word to WeType's own user dictionary through the
            // public WxhldApi.add_user_history(String). adb shell am broadcast -a ...USERDICT_PROBE -p com.tencent.wetype --es word 猫
            val pr = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val word = i?.getStringExtra("word").orEmpty().ifEmpty { "测试词条" }
                    val out = runCatching {
                        val api = Class.forName("com.tencent.wxhld.WxhldApi", true, service?.classLoader ?: svc.classLoader)
                        val ok = api.getMethod("add_user_history", String::class.java).invoke(null, word)
                        "add_user_history(\"$word\") -> $ok"
                    }.getOrElse { "failed: $it" }
                    runCatching { File(dir, "userdict_probe.txt").appendText(out + "\n") }
                    XposedCompat.log("WeTypeMulti", out)
                }
            }
            val pf = android.content.IntentFilter("top.xiaojiang233.wetypemulti.USERDICT_PROBE")
            // Diagnostics, log only. Types romaji into fresh Rime sessions and logs the candidates (tag WeTypeMulti/probe):
            // adb shell am broadcast -a top.xiaojiang233.wetypemulti.RIME_PROBE -p com.tencent.wetype --es inputs watashi,kyou
            val rr = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val inputs = i?.getStringExtra("inputs").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    Thread { probe(inputs) }.start()
                }
            }
            val rf = android.content.IntentFilter("top.xiaojiang233.wetypemulti.RIME_PROBE")
            // Diagnostics, log only. Logs the IME window's view tree and the key objects of WeType's keyboard views (tag WeTypeMulti/view):
            // adb shell am broadcast -a top.xiaojiang233.wetypemulti.DUMP_VIEWS -p com.tencent.wetype
            val dr = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    val decor = service?.window?.window?.decorView ?: return
                    decor.post { runCatching { ViewDump.dump(decor) }.onFailure { XposedCompat.log("WeTypeMulti/view", "dump failed: $it") } }
                }
            }
            val df = android.content.IntentFilter("top.xiaojiang233.wetypemulti.DUMP_VIEWS")
            // Diagnostics, log only. Logs every call into WeType's engine API that has to do with input, sessions, cursor and candidates
            // (tag WeTypeMulti/trace), to find out which method a gesture ends up in: adb shell am broadcast -a
            // top.xiaojiang233.wetypemulti.TRACE_API -p com.tencent.wetype --ez on true   (and --ez on false to stop)
            val tr = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    onTrace?.invoke(i?.getBooleanExtra("on", true) != false)
                }
            }
            val tf = android.content.IntentFilter("top.xiaojiang233.wetypemulti.TRACE_API")
            for ((rcv, flt) in listOf(r to f, sr to sf, pr to pf, rr to rf, dr to df, tr to tf)) {
                if (android.os.Build.VERSION.SDK_INT >= 33) appCtx.registerReceiver(rcv, flt, 2 /* RECEIVER_EXPORTED */)
                else appCtx.registerReceiver(rcv, flt)
            }
        }
        runCatching {
            RimeSetup::class.java.classLoader?.getResourceAsStream("rime/predict.tsv")?.use { s ->
                predictor.load(s.bufferedReader(Charsets.UTF_8).readText())
            }
        }
        File(dir, "predict.tsv").takeIf { it.isFile }?.let { predictor.load(it.readText()) }
    }

    /** Import every `*.tsv` / `*.txt` / `*.dict.yaml` dropped in `<dataDir>/wetype_multi/dicts/`. */
    fun loadDictionaries() {
        val ddir = File(dir, "dicts").apply { mkdirs() }
        ddir.listFiles()?.filter { it.isFile }?.forEach { f ->
            runCatching { f.reader().use { dictionary.addAll(DictImporter.import(it)) } }
        }
    }

    /** True while [engine] is the built-in engine only because Rime is still compiling; Rime replaces it between compositions. */
    private var standIn = false

    /**
     * Rime once it is deployed, otherwise the pure-Kotlin engine. This never waits for the deploy: [RimeSetup] compiles on a
     * background thread, which used to run here on the first key and froze typing (and held [lock]) for the whole build.
     * Call with [lock] held.
     */
    private fun engine(): InputEngine {
        var e = engine
        if (e != null && standIn && RimeSetup.ready() != null && e.context().preedit.isEmpty()) {
            engine = null; e = null; standIn = false // Rime finished while nothing is being composed: switch over now
        }
        return e ?: createEngine().also { engine = it }
    }

    private fun createEngine(): InputEngine {
        val bridge = RimeSetup.ready()
        if (bridge != null) {
            val rime = runCatching { RimeInputEngine(bridge, language) { c -> predictor.suggest(c) } }
                .onFailure { XposedCompat.log("WeTypeMulti", "rime session failed, using builtin: $it ${it.cause ?: ""}") }.getOrNull()
            if (rime != null) {
                standIn = false
                XposedCompat.log("WeTypeMulti", "engine = RimeInputEngine")
                return rime
            }
        } else {
            RimeSetup.start(dir, dictionary.entries()) { onRimeFinished() }
        }
        // A stand-in only while a deploy is running that can still end with a usable Rime.
        standIn = RimeSetup.state == RimeSetup.State.BUILDING
        XposedCompat.log("WeTypeMulti", "engine = BuiltinJapaneseEngine (rime ${RimeSetup.state}${if (standIn) ", stand-in until it is ready" else ""})")
        return BuiltinJapaneseEngine(dictionary, predictor, language)
    }

    /** Forget the engine and free its Rime session; the next key builds a fresh one. Call with [lock] held. */
    private fun dropEngine() {
        (engine as? RimeInputEngine)?.let { runCatching { it.close() } }
        engine = null
    }

    /** Start compiling Rime now (switching to Japanese), so it is ready by the time the first key is typed. */
    private fun warmUpRime() = RimeSetup.start(dir, dictionary.entries(), retry = true) { onRimeFinished() }

    /** Runs on the deploy thread. [engine] notices the finished deploy at the next key that starts a composition. */
    private fun onRimeFinished() = XposedCompat.log("WeTypeMulti", "rime deploy finished: ${RimeSetup.state}")

    /** Diagnostics: type [inputs] into fresh Rime sessions and log what they show. Worker thread only: it waits for the deploy. */
    private fun probe(inputs: List<String>) {
        val tag = "WeTypeMulti/probe"
        RimeSetup.start(dir, dictionary.entries(), retry = true)
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
            e.reset(); e.close()
        }
        XposedCompat.log(tag, "probe done")
    }

    /** Turn on [langId] directly (used by the keyboard popup's 日语 entry). */
    fun activate(langId: String) = synchronized(lock) {
        Languages.byId(langId)?.let { language = it }
        engine?.reset(); dropEngine(); active = true
        warmUpRime()
        LayoutHooks.invalidate() // the Japanese variant of the layout must be read again
        commitCandidatesView(); onLanguageChanged?.invoke(label)
        XposedCompat.log("WeTypeMulti", "activate($langId) -> active=$active language=${language.id}")
    }

    /** Hand input back to WeType (it switched keyboard itself). */
    fun deactivate() = synchronized(lock) {
        if (!active) return@synchronized
        engine?.reset(); dropEngine(); active = false
        LayoutHooks.invalidate() // WeType's own layout is read again on the next switch
        commitCandidatesView(); onLanguageChanged?.invoke(label)
        XposedCompat.log("WeTypeMulti", "deactivate() -> active=$active")
    }

    /** Ctrl+Space and the TOGGLE broadcast: the same cycle as the globe key (中 -> 英 -> あ -> 中). */
    fun switchLanguage(): InputMode.Mode = InputMode.cycle(this)

    /** Text for the on-screen switcher: current language, or 中/英 while WeType handles input. */
    val label: String get() = if (active) language.shortLabel else "中/英"
    var onLanguageChanged: ((String) -> Unit)? = null

    fun onKey(typed: Char): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        // The long vowel mark belongs to the reading: Rime's kana table knows it as the hyphen.
        val c = if (typed == 'ー') '-' else typed
        // Space converts while composing (first candidate). Rime does it inside processKey; the built-in engine does not.
        if (c == ' ' && engine().context().preedit.isNotEmpty()) return@synchronized onSelectKey(0)
        var e = engine()
        var consumed = e.processKey(c)
        // Romaji letters must always be consumed; a native Rime that rejects them (no usable schema
        // deployed) would otherwise leave WeType's own Pinyin engine to handle the key.
        if (!consumed && c.isLetter() && e !is BuiltinJapaneseEngine) {
            XposedCompat.log("WeTypeMulti", "engine ${e.javaClass.simpleName} rejected '$c', falling back to builtin")
            e.reset()
            dropEngine(); standIn = false
            e = BuiltinJapaneseEngine(dictionary, predictor, language).also { engine = it }
            consumed = e.processKey(c)
        }
        if (consumed) {
            // Rime can commit while processing a key (punctuation, space); that text goes to the editor now.
            e.takeCommitted()?.let { commit(it) }
            commitCandidatesView()
            return@synchronized true
        }
        // Japanese punctuation and symbols the engine leaves alone (、。「」ー〜…): finish the composition, then insert.
        if (!isRomajiOrDigit(c) && !c.isWhitespace()) {
            flushComposition()
            commit(JaPunctuation.toJapanese(c.toString()))
            return@synchronized true
        }
        false
    }

    /**
     * The long vowel key (drawn by [LanguageSwitcher]): ー goes into the composition like a letter, so "koーhiー" becomes コーヒー.
     * With nothing composed it is committed as ー by itself, through [JaPunctuation] like any lone hyphen.
     */
    fun onLongVowel(): Boolean = onKey('-')

    /**
     * WeType cleared its session (a long press on delete, among other things). Its pending input is gone from the screen, so ours
     * has to go as well, or the next letters would join the old ones. Not acted on right after this controller committed or
     * selected something itself: the call may belong to that, and what is left of a partly chosen composition is still wanted.
     */
    fun onSessionReset(): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        val hasPreedit = engine?.context()?.preedit?.isNotEmpty() == true
        val since = android.os.SystemClock.uptimeMillis() - lastOwnCommitAt
        if (since < RESET_GRACE_MS) {
            XposedCompat.log("WeTypeMulti", "WeType session reset ${since} ms after our own commit: ignored")
            return@synchronized false
        }
        if (hasPreedit || lastCommitted.isNotEmpty()) {
            lastCommitted = ""
            engine?.reset()
            commitCandidatesView()
            XposedCompat.log("WeTypeMulti", "WeType session reset: cleared")
            return@synchronized true
        }
        false
    }

    /** Text WeType commits on its own while Japanese is on: finish the composition, then insert the text. */
    fun onLiteral(text: String) = synchronized(lock) {
        if (!active || text.isEmpty()) return@synchronized
        flushComposition()
        commit(JaPunctuation.toJapanese(text))
    }

    private fun isRomajiOrDigit(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** Commit what the engine shows (its first candidate, or the raw composition) before a literal character. */
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

    /** WeType cancelled a press it had passed on as "d x" (the press became a slide or a long press): take back its letter. */
    fun onCancelledKey(): Boolean = onBackspace()

    fun onEnter(): Boolean = synchronized(lock) {
        if (!active || engine().context().preedit.isEmpty()) return@synchronized false
        commit(engine().commitRaw()); true
    }

    /** Space selects the first candidate; digits 1-9 select by index. */
    fun onSelectKey(index: Int): Boolean = synchronized(lock) {
        if (!active || engine().context().preedit.isEmpty()) return@synchronized false
        // Unknown candidate (index < 0 or out of range): swallow the event so WeType's native engine,
        // which knows nothing about our composition, is not asked to select anything.
        val t = engine().select(index) ?: return@synchronized true
        commit(t); true
    }

    /** Selects a candidate by text (either from active composition or from post-commit suggestions). */
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
        // Record the learned bigram in the syncable store (counts only ever grow, so max-merge is safe).
        if (lastCommitted.isNotEmpty() && text.isNotEmpty()) runCatching {
            val st = userData.store
            val n = (st.get(top.xiaojiang233.wetypemulti.common.sync.DataType.BIGRAM,
                "$lastCommitted${top.xiaojiang233.wetypemulti.common.sync.SEP}$text")?.value?.toIntOrNull() ?: 0) + 1
            st.setBigram(lastCommitted, text, n); userData.save()
        }
        lastCommitted = text
        lastOwnCommitAt = android.os.SystemClock.uptimeMillis()
        sink.commit(text)
        runCatching { File(dir, "predict.tsv").writeText(predictor.serialize()) }
        commitCandidatesView()
    }

    /** Show the composition as inline composing text; candidate UI is rendered by [CandidateBar] or [WxhldSink]. */
    private fun commitCandidatesView() {
        val ctx = engine?.context()
        val composing = ctx != null && ctx.preedit.isNotEmpty()
        val suggestions = if (!composing && lastCommitted.isNotEmpty()) {
            engine?.suggest(lastCommitted)
        } else null
        sink.update(ctx, suggestions)
    }

    val isComposing get() = synchronized(lock) { active && engine?.context()?.preedit?.isNotEmpty() == true }
    fun candidateIndex(text: String?): Int = synchronized(lock) { engine?.context()?.candidates?.indexOfFirst { it.text == text } ?: -1 }

    fun reset() = synchronized(lock) { lastCommitted = ""; engine?.reset(); commitCandidatesView() }

    private companion object {
        /** See [onSessionReset]. */
        const val RESET_GRACE_MS = 400L
    }
}
