package chat.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    private suspend fun Client.state2() = engine.snapshot()!!

    @Test fun `channels and settings sync between devices, hooks, public channels and admin`() = runBlocking {
        TestServer().use { s ->
            val alice = newClient(s, "alice")
            val bob = newClient(s, "bob")
            assertTrue(alice.engine.isAdmin(), "erster Nutzer ist Admin")
            assertFalse(bob.engine.isAdmin())
            assertTrue(alice.engine.adminStats()["stats"] != null)

            val priv = alice.engine.createChannel("Intern", ChannelPolicy(join_mode = "open"))
            val pub = alice.engine.createChannel("Wetter", ChannelPolicy(join_mode = "open"), isPublic = true)
            alice.engine.setReceiptSettings(sendRead = true)
            kotlinx.coroutines.delay(2500)

            // zweites Gerät über das Backup
            val backup = alice.engine.exportBackup("backup-passphrase-1")
            val e2 = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("device2").toFile()), scope = testScope)
            e2.init()
            e2.linkDevice(backup, "backup-passphrase-1", "neue-passphrase-1")
            val dev2 = Client(e2, java.io.File("."), s, "alice")
            eventually("Kanäle auf Gerät 2") { dev2.state2().channels.takeIf { it.containsKey(priv) && it.containsKey(pub) } }
            eventually("Einstellung auf Gerät 2") { if (dev2.state2().sendRead) true else null }
            assertEquals("Intern", dev2.state2().channels[priv]!!.title)
            assertTrue(dev2.state2().channels[pub]!!.policy.isPublic)

            // Gerät 2 erstellt einen Kanal, löscht später den anderen
            val neu = e2.createChannel("Neu", ChannelPolicy(join_mode = "open"))
            eventually("Kanal von Gerät 2 auf Gerät 1") { alice.state().channels.takeIf { it.containsKey(neu) } }
            alice.engine.deleteChannel(neu)
            eventually("Löschung auf Gerät 2") { if (!dev2.state2().channels.containsKey(neu)) true else null }

            // Webhooks (ntfy-kompatibel): öffentlich und verschlüsselt
            val http = OkHttpClient()
            fun publish(url: String, text: String, vararg h: Pair<String, String>) {
                val b = Request.Builder().url(url).post(text.toRequestBody())
                h.forEach { (k, v) -> b.header(k, v) }
                http.newCall(b.build()).execute().use { assertEquals(200, it.code) }
            }
            val hookPub = alice.engine.createChannelHook(pub, "Monitoring")
            publish(hookPub, "Backup fertig", "Title" to "Nachtlauf", "Tags" to "white_check_mark")
            val hookPriv = alice.engine.createChannelHook(priv, "Router")
            publish(hookPriv, "Link down", "Priority" to "5")
            eventually("Webhook-Beitrag (öffentlich)") {
                alice.chan(pub).posts.firstOrNull { it.hook == "Monitoring" && it.parts.any { p -> p is Part.Text && p.body.contains("Nachtlauf") } }
            }
            eventually("Webhook-Beitrag (verschlüsselt)") {
                alice.chan(priv).posts.firstOrNull { it.hook == "Router" && it.parts.any { p -> p is Part.Text && p.body.contains("Link down") } }
            }
            val hooks = alice.engine.channelHooks(priv)
            assertEquals(1, hooks.size)
            alice.engine.deleteChannelHook(priv, hooks[0].id)
            http.newCall(Request.Builder().url(hookPriv).post("x".toRequestBody()).build()).execute().use { assertEquals(404, it.code) }

            // Öffentlicher Kanal ohne Konto lesbar
            http.newCall(Request.Builder().url("http://${s.domain}/v1/channels/$pub/public/log").build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().contains("\"public\":true"))
            }
            listOf(alice, bob, dev2).forEach { it.engine.close() }
        }
    }

    private suspend fun Client.chan(id: String) = engine.snapshot()!!.channels[id]!!
}
