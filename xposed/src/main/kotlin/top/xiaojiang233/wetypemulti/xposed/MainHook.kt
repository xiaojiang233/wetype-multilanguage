package top.xiaojiang233.wetypemulti.xposed

import android.app.Application
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import top.xiaojiang233.wetypemulti.xposed.XposedCompat.HookParam

/**
 * Entry point for both LSPosed and LSPatch (embedded or Manager mode), on the modern
 * (io.github.libxposed) Xposed API. [onPackageLoaded] fires with WeType's classloader.
 *
 * Generic hooks use only public Android framework classes, so they survive WeType's obfuscation.
 * Soft-keyboard key events inside WeType's own views are hooked via `extraHooks` in the config.
 */
class MainHook : XposedModule() {
    private var controller: ImeController? = null
    private var config = HookConfig()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedCompat.xposed = this
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val bundled = BundledAssets.read("hook_config.json")
        // Package list from bundled defaults decides whether to attach (LSPatch: always the app itself).
        val defaults = HookConfig.parse(bundled)
        if (!defaults.matches(param.packageName)) return
        val cl = param.defaultClassLoader
        log("attaching to ${param.packageName}")
        if (!defaults.enableNativeRime) {
            log("native librime disabled by config, using built-in engine only")
        } else runCatching {
            // getModuleApplicationInfo() is our own (module's) ApplicationInfo, not WeType's --
            // nativeLibraryDir there is the extracted-.so directory for our own APK, which is where
            // librime.so/libwetype_rime_jni.so get installed to.
            val libDir = moduleApplicationInfo.nativeLibraryDir
            val ok = NativeRimeBridge.load(listOfNotNull(libDir?.let(::File)))
            log("librime native ${if (ok) "loaded" else "unavailable, using built-in engine"}")
        }.onFailure { log("librime load step failed (continuing with built-in engine): $it") }

        // Everything below this point is best-effort: under LSPatch especially, WeType's internal
        // (obfuscated) class names can shift between builds. A failure anywhere here must disable
        // that one piece, never crash the host app -- so every step from here on is its own runCatching.
        runCatching {
            XposedCompat.hookMethod(Application::class.java, "onCreate", after = { p ->
                runCatching {
                    val app = p.thisObject as Application
                    config = HookConfig.load(app.filesDir, bundled).also { cfg -> hookExtra(cl, cfg) }
                    // Hooks are installed later, in InputMethodService.onCreate, on the IME service's own loader.
                    controller = ImeController(config, app.filesDir)
                }.onFailure { log("Application.onCreate setup failed: $it") }
            })
        }.onFailure { log("hooking Application.onCreate failed: $it") }
        runCatching { hookFramework() }.onFailure { log("hookFramework failed: $it") }
    }

    private val hookedLoaders = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ClassLoader, Boolean>())

    /** WeType is Tinker-patched: the live classes come from a DelegateLastClassLoader, a *different copy*
     *  from `param.defaultClassLoader`'s. Hooks and reflection on the wrong copy silently do nothing
     *  (process_input never fires; touching model.N there even tries to re-load libmmkv.so), and hooks on
     *  both copies push every event twice. So only the running IME service's loader gets hooks. */
    private fun installOn(c: ImeController, loader: ClassLoader) {
        synchronized(hookedLoaders) { if (!hookedLoaders.add(loader)) return }
        log("installing hooks on loader $loader")
        runCatching { WxhldHooks(c, loader).install() }.onFailure { e -> log("WxhldHooks: $e") }
        runCatching { KeyboardMenuHooks(c, loader).install() }.onFailure { e -> log("KeyboardMenuHooks: $e") }
    }

    private fun hookFramework() {
        val ims = InputMethodService::class.java
        XposedCompat.hookMethod(ims, "onCreate", after = { p ->
            runCatching {
                val svc = p.thisObject as InputMethodService
                controller?.init(svc)
                controller?.let { installOn(it, svc.javaClass.classLoader ?: svc.classLoader) }
            }.onFailure { log("IME init: $it") }
        })
        XposedCompat.hookMethod(ims, "onStartInput", android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType!!,
            after = { runCatching { controller?.reset() }.onFailure { log("IME reset: $it") } })
        // Show the language pill whenever the keyboard becomes visible.
        XposedCompat.hookMethod(ims, "onStartInputView", android.view.inputmethod.EditorInfo::class.java, Boolean::class.javaPrimitiveType!!,
            after = { p ->
                val c = controller
                if (c != null) runCatching { LanguageSwitcher.attach(p.thisObject as InputMethodService, c) }.onFailure { log("language switcher: $it") }
            })
        // Hardware keyboard / key events routed through the framework.
        XposedCompat.hookMethod(ims, "onKeyDown", Int::class.javaPrimitiveType!!, KeyEvent::class.java, before = { p ->
            runCatching {
                val c = controller ?: return@runCatching
                val code = p.args[0] as Int; val ev = p.args[1] as KeyEvent
                val cfg = config
                if (code == cfg.switchKeyCode && ev.isCtrlPressed == cfg.switchCtrl) { c.switchLanguage(); p.result = true; p.skipOriginal = true; return@runCatching }
                val handled = when {
                    code == KeyEvent.KEYCODE_DEL -> c.onBackspace()
                    code == KeyEvent.KEYCODE_ENTER -> c.onEnter()
                    code == KeyEvent.KEYCODE_SPACE -> c.onSelectKey(0)
                    code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> c.onSelectKey(code - KeyEvent.KEYCODE_1)
                    else -> ev.unicodeChar.takeIf { it > 0 }?.toChar()?.let(c::onKey) ?: false
                }
                if (handled) { p.result = true; p.skipOriginal = true }
            }.onFailure { log("onKeyDown: $it") }
        })
    }

    private fun hookExtra(cl: ClassLoader, cfg: HookConfig) {
        for (h in cfg.extraHooks) runCatching {
            val cls = XposedCompat.findClass(h.className, cl)
            XposedCompat.hookAllMethods(cls, h.method, before = { p ->
                val c = controller
                if (c != null) {
                    val handled = when (h.action) {
                        "key" -> when (val a = p.args.getOrNull(h.argIndex)) {
                            is Char -> c.onKey(a); is Int -> c.onKey(a.toChar()); is String -> a.singleOrNull()?.let(c::onKey) ?: false
                            else -> false
                        }
                        "backspace" -> c.onBackspace()
                        "enter" -> c.onEnter()
                        "switchLanguage" -> { c.switchLanguage(); true }
                        else -> false
                    }
                    if (handled) { p.result = null; p.skipOriginal = true }
                }
            })
        }.onFailure { log("extra hook ${h.className}#${h.method} failed: $it") }
    }

    private fun log(m: String) = XposedCompat.log("WeTypeMulti", m)
}

private typealias File = java.io.File
