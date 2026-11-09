package top.xiaojiang233.wetypemulti.xposed

import top.xiaojiang233.wetypemulti.common.sync.*
import java.io.File
import java.util.UUID

/** Owns the persisted [UserDataStore] and runs [SyncEngine] against the configured backend. */
class UserDataSync(private val dir: File, private val defaultCfg: SyncConfig) {
    private val cfgFile = File(dir, "sync_config.json")
    private val statusFile = File(dir, "sync_status.txt")

    /** `sync_config.json` (written by the SYNC broadcast) overrides `hook_config.json`, so a phone can be set up with adb only. */
    var cfg: SyncConfig = loadCfg()
        private set

    private fun loadCfg(): SyncConfig = runCatching {
        if (!cfgFile.isFile) return defaultCfg
        val o = org.json.JSONObject(cfgFile.readText())
        SyncConfig(o.optString("backend", "none"), o.optString("path"), o.optString("url"), o.optString("user"),
            o.optString("password"), o.optString("passphrase"))
    }.getOrDefault(defaultCfg)

    fun configure(c: SyncConfig) {
        cfg = c; dir.mkdirs()
        cfgFile.writeText(org.json.JSONObject().put("backend", c.backend).put("path", c.path).put("url", c.url)
            .put("user", c.user).put("password", c.password).put("passphrase", c.passphrase).toString())
    }

    fun writeStatus(text: String) { dir.mkdirs(); statusFile.writeText("${java.util.Date()}\n$text\n") }

    /** Read-only check of the backend: how many records it holds. Writes nothing anywhere. */
    fun probe(): String {
        val p = provider() ?: return "backend=${cfg.backend}: no provider"
        val r = p.pull(0)
        return "backend=${cfg.backend} remote_records=${r.items.size} version=${r.version} " +
            "sample=${r.items.take(3).joinToString { it.type.name + ":" + it.key.replace(SEP, '|') }}"
    }

    private val dataFile = File(dir, "user_data.tsv")
    private val versionFile = File(dir, "user_data.version")
    private val idFile = File(dir, "device_id")
    val store: UserDataStore = UserDataStore(
        idFile.takeIf { it.isFile }?.readText()?.trim() ?: UUID.randomUUID().toString().also { dir.mkdirs(); idFile.writeText(it) },
    ).also { s -> if (dataFile.isFile) s.load(dataFile.readText()) }

    val enabled get() = cfg.backend != "none"
    val localRecords get() = store.all().size

    private fun provider(): SyncProvider? {
        val codec = if (cfg.passphrase.isNotEmpty()) AesGcmCodec(cfg.passphrase.toCharArray()) else SyncCodec.Identity
        return when (cfg.backend) {
            "file" -> BlobSyncProvider(FileBlobStore(File(cfg.path)), codec)
            "webdav" -> BlobSyncProvider(WebDavBlobStore(cfg.url, cfg.user.ifEmpty { null }, cfg.password), codec)
            else -> null
        }
    }

    fun save() { dir.mkdirs(); dataFile.writeText(store.serialize()) }

    /** Blocking; call off the main thread. */
    @Synchronized fun sync(): SyncReport? {
        val p = provider() ?: return null
        val engine = SyncEngine(store, p).apply { restore(versionFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0) }
        val r = engine.sync()
        versionFile.writeText(engine.version.toString()); save()
        return r
    }
}
