package top.xiaojiang233.wetypemulti.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.io.File

/**
 * Handles all diagnostic and control broadcasts for the IME module.
 * Decouples broadcast registration and diagnostic logic from core IME flow.
 */
object ImeBroadcastManager {
    private var registered = false

    fun register(context: Context, ctl: ImeController, dataDir: File) {
        if (registered) return
        registered = true
        val appCtx = context.applicationContext

        runCatching {
            val toggleReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    ctl.switchLanguage()
                }
            }

            val syncReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val ov = i?.getStringExtra("backend")?.let { b ->
                        SyncConfig(
                            b,
                            i.getStringExtra("path").orEmpty(),
                            i.getStringExtra("url").orEmpty(),
                            i.getStringExtra("user").orEmpty(),
                            i.getStringExtra("password").orEmpty(),
                            i.getStringExtra("passphrase").orEmpty()
                        )
                    }
                    val probe = i?.getBooleanExtra("probe", false) == true
                    Thread { ctl.syncNow(ov, probe) }.start()
                }
            }

            val userdictReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val word = i?.getStringExtra("word").orEmpty().ifEmpty { "测试词条" }
                    val out = runCatching {
                        val api = Class.forName("com.tencent.wxhld.WxhldApi", true, ctl.service?.classLoader ?: context.classLoader)
                        val ok = api.getMethod("add_user_history", String::class.java).invoke(null, word)
                        "add_user_history(\"$word\") -> $ok"
                    }.getOrElse { "failed: $it" }
                    runCatching { File(dataDir, "userdict_probe.txt").appendText(out + "\n") }
                    XposedCompat.log("WeTypeMulti", out)
                }
            }

            val rimeProbeReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val inputs = i?.getStringExtra("inputs").orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    Thread { ctl.probe(inputs) }.start()
                }
            }

            val dumpViewsReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val decor = ctl.service?.window?.window?.decorView ?: return
                    decor.post {
                        runCatching { ViewDump.dump(decor) }.onFailure {
                            XposedCompat.log("WeTypeMulti/view", "dump failed: $it")
                        }
                    }
                }
            }

            val traceApiReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    ctl.onTrace?.invoke(i?.getBooleanExtra("on", true) != false)
                }
            }

            val receivers = listOf(
                toggleReceiver to IntentFilter("top.xiaojiang233.wetypemulti.TOGGLE"),
                syncReceiver to IntentFilter("top.xiaojiang233.wetypemulti.SYNC"),
                userdictReceiver to IntentFilter("top.xiaojiang233.wetypemulti.USERDICT_PROBE"),
                rimeProbeReceiver to IntentFilter("top.xiaojiang233.wetypemulti.RIME_PROBE"),
                dumpViewsReceiver to IntentFilter("top.xiaojiang233.wetypemulti.DUMP_VIEWS"),
                traceApiReceiver to IntentFilter("top.xiaojiang233.wetypemulti.TRACE_API")
            )

            for ((rcv, flt) in receivers) {
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(rcv, flt, 2 /* RECEIVER_EXPORTED */)
                } else {
                    appCtx.registerReceiver(rcv, flt)
                }
            }
        }.onFailure {
            XposedCompat.log("WeTypeMulti", "register broadcast receivers failed: $it")
        }
    }
}
