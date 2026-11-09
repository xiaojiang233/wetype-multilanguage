package top.xiaojiang233.wetypemulti.common

import com.sun.net.httpserver.HttpServer
import top.xiaojiang233.wetypemulti.common.dict.Dictionary
import top.xiaojiang233.wetypemulti.common.predict.Predictor
import top.xiaojiang233.wetypemulti.common.sync.*
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class SyncTest {
    private var now = 1000L
    private fun dev(id: String) = UserDataStore(id) { ++now }
    private fun provider(codec: SyncCodec = SyncCodec.Identity): Pair<BlobSyncProvider, java.io.File> {
        val f = Files.createTempDirectory("wtm").resolve("sync.tsv").toFile()
        return BlobSyncProvider(FileBlobStore(f), codec) to f
    }

    @Test fun twoDevicesConverge() {
        val (p, _) = provider()
        val a = dev("A"); val b = dev("B")
        val ea = SyncEngine(a, p); val eb = SyncEngine(b, p)
        a.upsertWord("ねこ", "猫", 10); a.setBigram("今日", "は", 3)
        assertEquals(2, ea.sync().pushed)
        assertEquals(2, eb.sync().pulled)
        assertEquals("10", b.get(DataType.USER_WORD, "ねこ${SEP}猫")?.value)

        // B edits, A deletes the other word: LWW + tombstones propagate.
        a.upsertWord("いぬ", "犬", 5); ea.sync(); eb.sync()
        b.removeWord("いぬ", "犬"); eb.sync(); ea.sync()
        assertNull(a.get(DataType.USER_WORD, "いぬ${SEP}犬"))
        assertNotNull(a.get(DataType.USER_WORD, "ねこ${SEP}猫"))
    }

    @Test fun countersKeepLargerValueAndLatestWinsOtherwise() {
        val (p, _) = provider()
        val a = dev("A"); val b = dev("B")
        val ea = SyncEngine(a, p); val eb = SyncEngine(b, p)
        a.setBigram("x", "y", 2); b.setBigram("x", "y", 7)
        ea.sync(); eb.sync(); ea.sync()
        assertEquals("7", a.get(DataType.BIGRAM, "x${SEP}y")?.value)
        assertEquals("7", b.get(DataType.BIGRAM, "x${SEP}y")?.value)
        a.pin("ねこ", "猫", 1); ea.sync(); b.pin("ねこ", "ネコ", 2); eb.sync(); ea.sync()
        assertEquals("ネコ${SEP}2", a.get(DataType.HARD_FIX, "ねこ")?.value) // B wrote last
    }

    @Test fun pushConflictIsRetried() {
        val (real, _) = provider()
        var conflicts = 1
        val flaky = object : SyncProvider by real {
            override fun push(items: List<SyncItem>, baseVersion: Long) =
                if (conflicts-- > 0) PushResult.Conflict else real.push(items, baseVersion)
        }
        val a = dev("A"); a.upsertWord("a", "b", 1)
        val rep = SyncEngine(a, flaky).sync()
        assertEquals(1, rep.retries); assertEquals(1, rep.pushed)
    }

    @Test fun backendResetTriggersFullReupload() {
        val (p, f) = provider()
        val a = dev("A"); val ea = SyncEngine(a, p)
        a.upsertWord("a", "b", 1); ea.sync()
        f.delete() // backend lost its history
        val rep = ea.sync()
        assertTrue(rep.fullMerge); assertEquals(1, rep.pushed)
        assertEquals(1, SyncEngine(dev("B"), p).sync().pulled)
    }

    @Test fun encryptedBlobRoundTripAndWrongKey() {
        val (p, f) = provider(AesGcmCodec("secret".toCharArray(), 1000))
        val a = dev("A"); a.upsertWord("ねこ", "猫", 1); SyncEngine(a, p).sync()
        assertFalse(String(f.readBytes(), Charsets.UTF_8).contains("猫"))
        assertEquals(1, SyncEngine(dev("B"), p).sync().pulled)
        val bad = BlobSyncProvider(FileBlobStore(f), AesGcmCodec("nope".toCharArray(), 1000))
        assertFails { bad.pull(0) }
    }

    @Test fun webDavBackend() {
        var body: ByteArray? = null; var etag = 0
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/sync.tsv") { ex ->
            when (ex.requestMethod) {
                "GET" -> if (body == null) ex.sendResponseHeaders(404, -1) else {
                    ex.responseHeaders.add("ETag", "\"$etag\""); ex.sendResponseHeaders(200, body!!.size.toLong()); ex.responseBody.write(body)
                }
                "PUT" -> {
                    val im = ex.requestHeaders.getFirst("If-Match"); val inm = ex.requestHeaders.getFirst("If-None-Match")
                    val ok = (im != null && body != null && im == "\"$etag\"") || (inm == "*" && body == null)
                    if (ok) { body = ex.requestBody.readBytes(); etag++; ex.sendResponseHeaders(204, -1) } else ex.sendResponseHeaders(412, -1)
                }
            }
            ex.close()
        }
        srv.start()
        try {
            val p = BlobSyncProvider(WebDavBlobStore("http://127.0.0.1:${srv.address.port}/sync.tsv"))
            val a = dev("A"); val b = dev("B")
            a.upsertWord("ねこ", "猫", 4); SyncEngine(a, p).sync()
            assertEquals(1, SyncEngine(b, p).sync().pulled)
        } finally { srv.stop(0) }
    }

    @Test fun applyToDictionaryAndPredictor() {
        val s = dev("A"); s.upsertWord("ねこ", "猫", 9); s.setBigram("今日", "は", 2); s.block("ねこ", "子")
        val d = Dictionary(); val pr = Predictor(d); val blocked = HashSet<Pair<String, String>>()
        s.applyTo(d, pr, blocked)
        assertEquals("猫", d.lookup("ねこ").single().word)
        assertEquals(listOf("は"), pr.suggest("今日"))
        assertEquals(setOf("ねこ" to "子"), blocked)
    }

    @Test fun storePersistenceKeepsDirtyAndTombstones() {
        val a = dev("A"); a.upsertWord("a", "b", 1); a.removeWord("c", "d")
        val b = dev("A"); b.load(a.serialize())
        assertEquals(2, b.pending().size)
        assertNull(b.get(DataType.USER_WORD, "c${SEP}d"))
        a.markPushed(); val c = dev("A"); c.load(a.serialize()); assertTrue(c.pending().isEmpty())
    }

    @Test fun concurrentDevicesConverge() {
        val (p, _) = provider()
        val devices = (1..4).map { dev("D$it") }
        val threads = devices.mapIndexed { n, d ->
            Thread {
                repeat(5) { k -> d.upsertWord("よみ$n", "語$n-$k", k); d.setBigram("前$n", "後$k", k + 1) }
                SyncEngine(d, p).sync()
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        // One more round each so everyone has seen everyone's pushes.
        val engines = devices.map { SyncEngine(it, p) }
        repeat(2) { engines.forEach { e -> e.sync() } }
        val sets = devices.map { d -> d.all().map { it.id to it.value }.toSet() }
        assertTrue(sets.all { it == sets[0] }); assertEquals(40, sets[0].size)
    }

    @Test fun restartKeepsUnsyncedChangesAndCursor() {
        val (p, _) = provider()
        val a = dev("A"); a.upsertWord("a", "b", 1)
        val saved = a.serialize() // app killed before the first sync
        val a2 = dev("A"); a2.load(saved)
        val e = SyncEngine(a2, p); assertEquals(1, e.sync().pushed)
        val v = e.version; val saved2 = a2.serialize()
        val a3 = dev("A"); a3.load(saved2); val e3 = SyncEngine(a3, p).apply { restore(v) }
        val rep = e3.sync(); assertEquals(0, rep.pushed); assertEquals(0, rep.pulled)
    }
}
