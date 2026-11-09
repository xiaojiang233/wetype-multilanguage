package top.xiaojiang233.wetypemulti.common.sync

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/** A single remote file with compare-and-swap write semantics. */
interface BlobStore {
    class Blob(val bytes: ByteArray, val etag: String)
    fun read(): Blob?
    /** Write [bytes] only if the blob still has [expectedEtag] (null = must not exist). False on mismatch. */
    fun write(bytes: ByteArray, expectedEtag: String?): Boolean
}

/**
 * [SyncProvider] over any [BlobStore]: the whole sync state lives in one text blob, so a shared folder
 * (Syncthing, Dropbox...) or a WebDAV server is enough. Per-record server versions give incremental
 * pulls; the blob's CAS write gives push-conflict detection.
 */
class BlobSyncProvider(private val store: BlobStore, private val codec: SyncCodec = SyncCodec.Identity) : SyncProvider {
    private class State(val version: Long, val records: LinkedHashMap<String, Pair<Long, SyncItem>>)

    private fun load(): Pair<State, String?> {
        val b = store.read() ?: return State(0, LinkedHashMap()) to null
        return parse(String(codec.decode(b.bytes), Charsets.UTF_8)) to b.etag
    }

    override fun pull(since: Long): PullResult {
        val (s, _) = load()
        if (since > s.version) return PullResult(s.records.values.map { it.second }, s.version, fullSyncRequired = true)
        return PullResult(s.records.values.filter { it.first > since }.map { it.second }, s.version)
    }

    override fun push(items: List<SyncItem>, baseVersion: Long): PushResult {
        val (s, etag) = load()
        if (s.version != baseVersion) return PushResult.Conflict
        val v = s.version + 1
        for (i in items) {
            val cur = s.records[i.id]?.second
            val merged = Merge.resolve(cur, i)
            if (merged != cur) s.records[i.id] = v to merged
        }
        val ok = store.write(codec.encode(render(State(v, s.records)).toByteArray(Charsets.UTF_8)), etag)
        return if (ok) PushResult.Ok(v) else PushResult.Conflict
    }

    private fun render(s: State) = buildString {
        append("#wtm-sync 1\n#version ${s.version}\n")
        for ((sv, i) in s.records.values)
            append("$sv\t${SyncFormat.encode(i)}\n")
    }

    private fun parse(text: String): State {
        var version = 0L; val rec = LinkedHashMap<String, Pair<Long, SyncItem>>()
        for (line in text.lineSequence()) {
            if (line.startsWith("#version ")) { version = line.removePrefix("#version ").trim().toLong(); continue }
            if (line.isEmpty() || line.startsWith("#")) continue
            val tab = line.indexOf('\t'); if (tab < 0) continue
            val item = SyncFormat.decode(line.substring(tab + 1)) ?: continue
            rec[item.id] = line.substring(0, tab).toLong() to item
        }
        return State(version, rec)
    }
}

/** [BlobStore] on a local file (point it at a synced folder). Writes are atomic. */
class FileBlobStore(private val file: File) : BlobStore {
    private fun etag(b: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))
    @Synchronized override fun read(): BlobStore.Blob? =
        if (file.isFile) file.readBytes().let { BlobStore.Blob(it, etag(it)) } else null

    @Synchronized override fun write(bytes: ByteArray, expectedEtag: String?): Boolean {
        val cur = read()?.etag
        if (cur != expectedEtag) return false
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp"); tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return true
    }
}

/** [BlobStore] on a WebDAV URL: GET + PUT with `If-Match` / `If-None-Match: *` for compare-and-swap. */
class WebDavBlobStore(private val url: String, private val user: String? = null, private val password: String? = null) : BlobStore {
    private fun open(method: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
        if (user != null) setRequestProperty("Authorization",
            "Basic " + Base64.getEncoder().encodeToString("$user:${password.orEmpty()}".toByteArray()))
    }

    override fun read(): BlobStore.Blob? {
        val c = open("GET")
        return when (c.responseCode) {
            200 -> BlobStore.Blob(c.inputStream.readBytes(), c.getHeaderField("ETag") ?: "")
            404 -> null
            else -> error("WebDAV GET ${c.responseCode}")
        }
    }

    override fun write(bytes: ByteArray, expectedEtag: String?): Boolean {
        val c = open("PUT").apply { doOutput = true }
        if (expectedEtag == null) c.setRequestProperty("If-None-Match", "*") else c.setRequestProperty("If-Match", expectedEtag)
        c.outputStream.use { it.write(bytes) }
        return when (c.responseCode) { 200, 201, 204 -> true; 412 -> false; else -> error("WebDAV PUT ${c.responseCode}") }
    }
}
