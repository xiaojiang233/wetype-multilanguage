package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.engine.CandidateLimits
import top.xiaojiang233.wetypemulti.common.engine.Context
import top.xiaojiang233.wetypemulti.common.layout.JaPunctuation
import top.xiaojiang233.wetypemulti.common.romaji.JaPreedit

/**
 * Hooks WeType 4.0.0's native-engine facade `com.tencent.wxhld.WxhldApi` (class and method names
 * are NOT obfuscated; found by decompiling the 4.0.0 APK, versionCode 58201). The IME runs in
 * the `:hld` process. While Japanese is active:
 *  - `process_input(session, key, aux)` is swallowed and fed to our engine instead,
 *  - composition and candidates are pushed through WeType's own batch entry point
 *    (`WxhldApi.onBatchEvent`, which its candidate bar consumes),
 *  - WeType's own batch events (its engine's suggestions, cloud candidates) are dropped, so only Rime's
 *    candidates show; see [filterBatch],
 *  - candidate paging calls (`candidate_get_n` / `candidate_next`) on our fake iterator are served here,
 *  - `select_candidate`, `drop_last` (backspace) and `force_emit_pending_input` (enter) are routed to us,
 *  - committed text goes straight to the editor via the InputConnection.
 *
 * Every hook is wrapped so a mismatch just disables that hook and logs, never crashes WeType.
 */
class WxhldHooks(private val ctl: ImeController, private val cl: ClassLoader) {
    private val FAKE_ITERATOR = -0x57455459L
    @Volatile private var listener: Any? = null
    @Volatile private var session = 0L
    // Written by key threads, read by WeType's candidate-paging calls: guard with [lock].
    private val lock = Any()
    private var lastCandidates: List<String> = emptyList()
    private var cursor = 0
    private val swallowedDown = java.util.Collections.synchronizedSet(HashSet<Char>())
    /** Event objects this module built for the batch entry point, newest last (identity, guarded by [lock]). */
    private val ours = ArrayDeque<Any>()
    /** Class names of WeType events already logged as dropped, so the log stays one line per kind. */
    private val droppedLogged = HashSet<String>()

    private val apiName = "com.tencent.wxhld.WxhldApi"

    fun install() {
        val api = runCatching { XposedCompat.findClass(apiName, cl) }.getOrNull() ?: return // not the :hld process
        log("WxhldApi found, installing")
        if (ctl.sink !is WxhldSink) ctl.sink = WxhldSink(ctl.sink) // never wrap twice
        LayoutHooks.install({ ctl.active }, cl) { ctl.service?.window?.window?.decorView }
        MappingRuntime.load(cl, runCatching { ctl.service?.packageManager?.getPackageInfo("com.tencent.wetype", 0)?.longVersionCode }.getOrNull())
        hook(api, "SetWxhldEventListener", after = { listener = it.args[0] })
        hook(api, "onBatchEvent", before = { filterBatch(it) })
        // process_input(session, key, aux) is found through the mapping (role "wxhld.input"), like select_candidate.
        val inp = MappingRuntime.found("wxhld.input")
        val argSession = MappingRuntime.argIndex("wxhld.input", "session")
        val argKey = MappingRuntime.argIndex("wxhld.input", "key")
        if (inp != null && argSession != null && argKey != null) {
            hook(api, inp.member ?: "process_input", before = {
                session = it.args[argSession] as Long
                val key = it.args[argKey] as? String
                // Device-confirmed formats: "d x" (key down), "u x" (key up), and "c x" (cancel: the press turned into a slide
                // or a long press, so WeType sends no "u x"). Each char is fed on down and every half of a consumed key is
                // swallowed, so WeType's own engine never sees it. A cancel also takes back the letter its down composed.
                if (key != null && ctl.active && key.length == 3 && key[1] == ' ' && (key[0] == 'd' || key[0] == 'u' || key[0] == 'c')) {
                    val ch = key[2]
                    val consumed = when (key[0]) {
                        'd' -> ctl.onKey(ch).also { c -> if (c) swallowedDown.add(ch) else swallowedDown.remove(ch) }
                        'c' -> swallowedDown.remove(ch).also { wasOurs -> if (wasOurs && ch.isLetter()) ctl.onCancelledKey() }
                        else -> swallowedDown.remove(ch)
                    }
                    if (consumed) { it.result = null; it.skipOriginal = true }
                }
            })
        } else {
            log("wxhld.input not mapped; keys go straight to WeType")
        }
        hook(api, "drop_last", before = { if (ctl.isComposing && ctl.onBackspace()) { it.result = true; it.skipOriginal = true } })
        // WeType clears its whole session (long press on delete, among others): clear ours too, or the next letters join the old ones.
        // The original call is not skipped; WeType's own state has to be cleared as well.
        hook(api, "reset_session", before = { if (ctl.active) ctl.onSessionReset() })
        ctl.onTrace = { on -> trace(on) }
        hook(api, "force_emit_pending_input", before = { if (ctl.isComposing && ctl.onEnter()) { it.result = null; it.skipOriginal = true } })
        // select_candidate's name and argument layout come from the mapping (role "wxhld.select"). Its 4th argument is the
        // text WeType types right after the candidate: the punctuation of a punctuation key, null for an ordinary tap.
        val sel = MappingRuntime.found("wxhld.select")
        val argCandidate = MappingRuntime.argIndex("wxhld.select", "candidate")
        val argCandidateId = MappingRuntime.argIndex("wxhld.select", "candidateId")
        val argFollowUp = MappingRuntime.argIndex("wxhld.select", "followUp")
        if (sel != null && argCandidate != null && argCandidateId != null && argFollowUp != null) {
            hook(api, sel.member ?: "select_candidate", before = {
                val known = synchronized(lock) { lastCandidates }
                val text = (it.args.getOrNull(argCandidate) as? String)?.takeIf { s -> s in known }
                    ?: (it.args.getOrNull(argCandidateId) as? ByteArray)?.let { b -> String(b, Charsets.UTF_8) }?.takeIf { s -> s in known }
                if (text != null && ctl.onSelectCandidate(text)) {
                    // Skipping WeType's call would drop the follow-up text, so type it here, after the candidate.
                    (it.args.getOrNull(argFollowUp) as? String)?.takeIf { s -> s.isNotEmpty() }?.let { s -> ctl.onLiteral(s) }
                    it.result = null; it.skipOriginal = true
                }
            })
        } else {
            log("wxhld.select not mapped; candidate taps and punctuation after a candidate stay with WeType")
        }
        hook(api, "candidate_get_n", before = {
            if (it.args[0] == FAKE_ITERATOR) {
                val n = (it.args[1] as Int).coerceIn(0, CandidateLimits.MAX_CANDIDATES)
                val slice = synchronized(lock) {
                    if (cursor >= lastCandidates.size) cursor = 0
                    lastCandidates.drop(cursor).take(n).also { s -> cursor += s.size }
                }
                it.result = candidateArray(slice); it.skipOriginal = true
            }
        })
        hook(api, "candidate_next", before = {
            if (it.args[0] == FAKE_ITERATOR) {
                val t = synchronized(lock) {
                    if (cursor >= lastCandidates.size) cursor = 0
                    lastCandidates.getOrNull(cursor)?.also { cursor++ }
                }
                it.result = t?.let { x -> candidateArray(listOf(x))[0] }; it.skipOriginal = true
            }
        })
        hook(api, "delete_candidate_iterator", before = {
            if (it.args[0] == FAKE_ITERATOR) {
                synchronized(lock) { cursor = 0 }
                it.result = null; it.skipOriginal = true
            }
        })
        hookCommitText()
    }

    /**
     * While Japanese is on, WeType's own batch events (its engine's suggestions, cloud candidates) must not
     * reach its candidate bar: keep only the events this module built and drop the rest, logging each kind once.
     */
    private fun filterBatch(p: XposedCompat.HookParam) {
        if (!ctl.active || p.args.size != 2) return
        val events = p.args[1] as? Array<*> ?: return
        if (events.isEmpty()) return
        // Text WeType's keyboard commits by itself (punctuation, kana keys) arrives as EmitInput. Route it through
        // our composition so it lands after whatever Rime shows; WeType's copy is dropped below.
        for (e in events) {
            if (e != null && !isOurs(e) && e.javaClass.simpleName == "EmitInput") {
                (XposedCompat.getObjectField(e, "text") as? String)?.let { ctl.onLiteral(it) }
            }
        }
        val keep = events.filter { it != null && isOurs(it) }
        if (keep.size == events.size) return
        for (e in events) {
            if (e != null && !isOurs(e) && synchronized(lock) { droppedLogged.add(e.javaClass.simpleName) }) {
                log("dropping WeType event ${e.javaClass.simpleName} while Japanese is active")
            }
        }
        if (keep.isEmpty()) { p.result = null; p.skipOriginal = true; return }
        val arr = java.lang.reflect.Array.newInstance(events.javaClass.componentType, keep.size)
        keep.forEachIndexed { i, e -> java.lang.reflect.Array.set(arr, i, e) }
        p.newArgs = arrayOf<Any?>(p.args[0], arr)
    }

    /**
     * WeType's keyboard commits its text (key long presses and slide-ups included) through the input connection. While
     * Japanese is on, a single character is rewritten to its Japanese form on the way out (see [JaPunctuation]). Our own
     * commits pass through here too, and are unaffected because they are already Japanese.
     */
    private fun hookCommitText() {
        val ric = runCatching { XposedCompat.findClass("android.view.inputmethod.RemoteInputConnection", cl) }.getOrNull()
        if (ric == null) {
            log("RemoteInputConnection not found; key long presses keep WeType's characters")
            return
        }
        val n = XposedCompat.hookAllMethods(ric, "commitText", before = { p ->
            val text = p.args.getOrNull(0)?.toString()
            if (text != null && ctl.active && text.length == 1) {
                val mapped = JaPunctuation.toJapanese(text)
                if (mapped != text) p.newArgs = arrayOf<Any?>(mapped, p.args.getOrNull(1))
            }
        })
        log("commitText hook matched $n method(s)")
    }

    private fun remember(vararg objs: Any) = synchronized(lock) {
        for (o in objs) ours.addLast(o)
        while (ours.size > 16) ours.removeFirst()
    }

    private fun isOurs(e: Any): Boolean = synchronized(lock) { ours.any { it === e } }

    /** Pushes composition and candidates into WeType's own candidate UI, falling back to [UiSink] when that fails. */
    private inner class WxhldSink(private val fallback: UiSink) : UiSink {
        override fun update(ctx: Context?, suggestions: List<String>?) {
            val composing = ctx != null && ctx.preedit.isNotEmpty()
            val shown = CandidateLimits.sanitize(if (composing) ctx!!.candidates.map { it.text } else suggestions.orEmpty())
            synchronized(lock) { lastCandidates = shown; cursor = 0 }
            // WeType's own native->Java entry points are the static WxhldApi.on* methods; they wrap everything
            // into BatchEmitEvent[] and call listener.OnBatchEmitEventUpdate, which is what the keyboard UI
            // actually consumes (the listener's OnPendingInputUpdate/OnCandidateListUpdate are log-only stubs).
            val ok = runCatching {
                val api = XposedCompat.findClass(apiName, cl)
                val evCls = XposedCompat.findClass("com.tencent.wxhld.info.BatchEmitEvent", cl)
                val piCls = XposedCompat.findClass("com.tencent.wxhld.info.PendingInput", cl)
                val pending = java.lang.reflect.Array.newInstance(piCls, if (composing) 1 else 0)
                if (composing) {
                    val m = XposedCompat.newInstance(XposedCompat.findClass("com.tencent.wxhld.info.PendingExactlyMatch", cl)).also { fillDefaults(it, 0) }
                    // The reading in kana, as Japanese IMEs show it, not Rime's spelling with a delimiter between syllables.
                    XposedCompat.setObjectField(m, "match", JaPreedit.display(ctx!!.preedit))
                    java.lang.reflect.Array.set(pending, 0, m)
                }
                val pendingV2 = XposedCompat.newInstance(XposedCompat.findClass("com.tencent.wxhld.info.PendingInputV2", cl), pending, System.currentTimeMillis()).also { fillDefaults(it, 0) }
                val list = XposedCompat.newInstance(XposedCompat.findClass("com.tencent.wxhld.info.CandidateList", cl)).also { fillDefaults(it, 0) }
                remember(pendingV2, list)
                XposedCompat.setLongField(list, "newIterator", if (shown.isEmpty()) 0L else FAKE_ITERATOR)
                XposedCompat.setIntField(list, "type", 0) // CandidateListType.PROCESS_INPUT
                val events = java.lang.reflect.Array.newInstance(evCls, 2)
                java.lang.reflect.Array.set(events, 0, pendingV2)
                java.lang.reflect.Array.set(events, 1, list)
                val bm = api.declaredMethods.firstOrNull { it.name == "onBatchEvent" && it.parameterCount == 2 }
                    ?: throw NoSuchMethodException("onBatchEvent; api=${api.name} loader=${api.classLoader} methods=" +
                        api.declaredMethods.filter { it.name.contains("Batch", true) || it.name.startsWith("on") }.joinToString { m -> m.name + m.parameterTypes.joinToString(",", "(", ")") { t -> t.simpleName } })
                bm.isAccessible = true
                bm.invoke(null, session, events)
                true
            }.onFailure { log("onBatchEvent: $it ${it.cause ?: ""}") }.getOrDefault(false)
            if (!ok) log("sink.update failed preedit='${ctx?.preedit}' candidates=${shown.size} session=$session")
            if (!ok) fallback.update(ctx, suggestions)
        }

        override fun commit(text: String) {
            if (text.isEmpty()) return
            runOnMain { ctl.service?.currentInputConnection?.apply { finishComposingText(); commitText(text, 1) } }
        }
    }

    /** WeType's generated info classes leave object fields null and its handlers (e.g. model.i0.L3 reads
     *  CandidateList.textAroundCursorInfo.prevChangeReason) dereference them -- v20 crashed the :hld process.
     *  Fill null String/array/nested-object fields with empty defaults (a few levels deep). */
    private fun fillDefaults(obj: Any, depth: Int) {
        if (depth > 3) return
        var c: Class<*>? = obj.javaClass
        while (c != null && c.name.startsWith("com.tencent.wxhld")) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                runCatching {
                    f.isAccessible = true
                    val cur = f.get(obj)
                    if (cur != null) {
                        // already-built nested objects can still hold null members (v21: CloudTextRecommendPosition)
                        if (!f.type.isArray && f.type.name.startsWith("com.tencent.wxhld") && !f.type.isEnum) fillDefaults(cur, depth + 1)
                        return@runCatching
                    }
                    val t = f.type
                    val v: Any? = when {
                        t == String::class.java -> ""
                        t.isArray -> java.lang.reflect.Array.newInstance(t.componentType, 0)
                        t.name.startsWith("com.tencent.wxhld") && !t.isEnum -> t.getDeclaredConstructor().also { k -> k.isAccessible = true }.newInstance()
                        else -> null
                    }
                    if (v != null) { f.set(obj, v); if (!t.isArray && t != String::class.java) fillDefaults(v, depth + 1) }
                }
            }
            c = c.superclass
        }
    }

    /** WeType registers its listener once at engine start, which can be before our live-loader hooks
     *  exist (so [listener] stays null); WxhldApi keeps it in `mWxhldEventListener`, read it from there. */
    private fun fetchListener(): Any? = runCatching {
        val api = XposedCompat.findClass(apiName, cl)
        val inst = api.getMethod("getInstance").invoke(null)
        val f = api.getDeclaredField("mWxhldEventListener").also { it.isAccessible = true }
        (if (java.lang.reflect.Modifier.isStatic(f.modifiers)) f.get(null) else f.get(inst))
            .also { if (it != null) { listener = it; log("listener recovered from WxhldApi field") } }
    }.onFailure { log("fetchListener: $it") }.getOrNull()

    private fun runOnMain(block: () -> Unit) {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() === main) runCatching(block).onFailure { log("ui: $it") }
        else android.os.Handler(main).post { runCatching(block).onFailure { log("ui: $it") } }
    }

    private fun candidateArray(texts: List<String>): Array<Any?> {
        val cls = XposedCompat.findClass("com.tencent.wxhld.info.Candidate", cl)
        val arr = java.lang.reflect.Array.newInstance(cls, texts.size) as Array<Any?>
        texts.forEachIndexed { i, t ->
            arr[i] = XposedCompat.newInstance(cls, 0).also { c ->
                XposedCompat.setObjectField(c, "text", t)
                XposedCompat.setObjectField(c, "comment", "")
                XposedCompat.setObjectField(c, "id", t.toByteArray(Charsets.UTF_8))
                XposedCompat.setIntField(c, "id_len", t.toByteArray(Charsets.UTF_8).size)
            }
        }
        return arr
    }

    @Volatile private var tracing = false
    private var traceInstalled = false

    /**
     * Diagnostics: while [on], log every call into WeType's engine API that has to do with input, sessions, the cursor or candidates
     * (tag WeTypeMulti/trace). It is how to find which method a gesture ends up in. The hooks are installed the first time this is
     * switched on and only look at a flag afterwards.
     */
    private fun trace(on: Boolean) {
        tracing = on
        log("api trace ${if (on) "on" else "off"}")
        if (!on || traceInstalled) return
        traceInstalled = true
        val api = runCatching { XposedCompat.findClass(apiName, cl) }.getOrNull() ?: return
        val names = api.declaredMethods.map { it.name }.distinct().filter { n ->
            TRACE_WORDS.any { n.contains(it, ignoreCase = true) } && n !in TRACE_QUIET
        }
        for (name in names) {
            XposedCompat.hookAllMethods(api, name, before = { p ->
                if (tracing) XposedCompat.log("WeTypeMulti/trace", "$name(${p.args.joinToString(", ") { traceArg(it) }})")
            })
        }
        log("api trace: ${names.size} methods hooked")
    }

    private fun traceArg(a: Any?): String = when (a) {
        null -> "null"
        is String -> "\"${a.take(40)}\""
        is Number, is Boolean -> a.toString()
        is ByteArray -> "bytes[${a.size}]"
        is Array<*> -> "${a.javaClass.componentType?.simpleName}[${a.size}]"
        else -> a.javaClass.simpleName
    }

    private fun hook(api: Class<*>, name: String, before: ((XposedCompat.HookParam) -> Unit)? = null, after: ((XposedCompat.HookParam) -> Unit)? = null) {
        // XposedCompat.hookAllMethods silently hooks nothing (no exception) when the method name
        // doesn't exist on this WeType build, so logging the match count tells "wrong method name"
        // apart from "hooked but the callback itself is a no-op" when diagnosing from device logs.
        val matched = XposedCompat.hookAllMethods(api, name, before, after)
        log("hook $name: matched $matched method(s)")
    }

    private fun log(m: String) = XposedCompat.log("WeTypeMulti/wxhld", m)

    private companion object {
        /** Method names worth tracing contain one of these. */
        val TRACE_WORDS = listOf("session", "input", "candidate", "drop", "emit", "reset", "clear", "cursor", "select", "commit", "cancel", "abort", "text_around")
        /** ... except the ones called all the time or only to read something. */
        val TRACE_QUIET = setOf("candidate_get_n", "candidate_next", "delete_candidate_iterator", "has_session", "report_keystroke",
            "onPendingInputUpdate", "onPendingInputUpdateV2", "onCandidateListUpdate", "get_most_likely_sequel")
    }
}
