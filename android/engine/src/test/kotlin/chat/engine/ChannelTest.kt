package chat.engine

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChannelTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    private suspend fun Client.chan(id: String) = snapshot().channels[id]!!
    private suspend fun Client.snapshot() = engine.snapshot()!!
    private fun ChannelState.hasPost(text: String) = posts.any { p -> !p.deleted && p.parts.any { it is Part.Text && it.body == text } }

    @Test fun `channel join rights moderation and approval`() = runBlocking {
        TestServer().use { s ->
            val owner = newClient(s, "owner"); val alice = newClient(s, "alice"); val bob = newClient(s, "bob")
            val id = owner.engine.createChannel("News", ChannelPolicy(join_mode = "open", members_can_write = false))
            val link = owner.engine.channelLink(id, "http://${s.domain}")
            val pv = alice.engine.previewChannel(link)
            assertEquals("News", pv.title)
            alice.engine.joinChannel(link)
            bob.engine.joinChannel(link)

            // Mitglieder dürfen nur lesen; Besitzer schreibt
            assertFailsWith<ChatException> { alice.engine.postToChannel(id, listOf(Part.Text("nope"))) }
            owner.engine.postToChannel(id, listOf(Part.Text("Hallo")))
            eventually("Beitrag bei Alice") { alice.chan(id).takeIf { it.hasPost("Hallo") } }

            // individuelles Schreibrecht
            val aliceIk = alice.chan(id).me.ik
            owner.engine.channelMod(id, "role", target = aliceIk, role = "write")
            eventually("Alice darf schreiben") { alice.chan(id).takeIf { it.me.can_write } }
            alice.engine.postToChannel(id, listOf(Part.Text("Danke")))
            eventually("Beitrag bei Bob") { bob.chan(id).takeIf { it.hasPost("Danke") } }

            // Dateianhang in Kanal: Alice lädt hoch, Bob lädt entschlüsselt herunter
            val bytes = ByteArray(70_000) { (it * 5).toByte() }
            alice.engine.postToChannel(id, "mit Bild", files = listOf(Engine.Attachment("bild.png", "image/png", bytes)))
            val got = eventually("Datei-Beitrag bei Bob") { bob.chan(id).posts.firstOrNull { p -> p.parts.any { it is Part.File } } }
            val fp = got.parts.filterIsInstance<Part.File>().first()
            assertEquals("bild.png", fp.name)
            assertTrue(bob.engine.downloadFile(fp).contentEquals(bytes), "Kanaldatei bitgenau")
            val post = bob.chan(id).posts.first { it.parts.any { p -> p is Part.Text && p.body == "Danke" } }
            assertTrue(!post.bad && post.from == alice.address)

            // Timeout, Löschen, Sperre
            owner.engine.channelMod(id, "timeout", target = aliceIk, seconds = 3600)
            eventually("Alice stumm") { alice.chan(id).takeIf { !it.me.can_write } }
            assertFailsWith<ChatException> { alice.engine.postToChannel(id, listOf(Part.Text("stumm"))) }
            owner.engine.channelMod(id, "delete", postId = post.id)
            eventually("Beitrag gelöscht bei Bob") { bob.chan(id).takeIf { c -> c.posts.first { it.id == post.id }.deleted } }
            owner.engine.channelMod(id, "ban", target = aliceIk)
            eventually("Alice gesperrt") { alice.chan(id).takeIf { it.me.status == "banned" } }
            assertFailsWith<ApiException> { alice.engine.syncChannel(id) }.also { assertEquals(403, it.status) }

            // Mitgliederliste nur für Moderation
            assertFailsWith<ApiException> { bob.engine.channelMembers(id) }
            assertTrue(owner.engine.channelMembers(id).any { it.address == bob.address })

            // Freigabe + Proof-of-Work
            val appr = owner.engine.createChannel("Intern", ChannelPolicy(join_mode = "approval"))
            val carol = newClient(s, "carol")
            carol.engine.joinChannel(owner.engine.channelLink(appr, "http://${s.domain}"))
            assertEquals("pending", carol.chan(appr).me.status)
            owner.engine.channelMod(appr, "approve", target = carol.chan(appr).me.ik)
            eventually("Carol freigegeben") { carol.chan(appr).takeIf { it.me.status == "active" } }
            val pow = owner.engine.createChannel("Pow", ChannelPolicy(join_mode = "pow", pow_bits = 10))
            bob.engine.joinChannel(owner.engine.channelLink(pow, "http://${s.domain}"))
            assertEquals("active", bob.chan(pow).me.status)

            // Captcha: wird angefordert
            val cap = owner.engine.createChannel("Captcha", ChannelPolicy(join_mode = "captcha"))
            val ex = assertFailsWith<NeedsCaptcha> { carol.engine.joinChannel(owner.engine.channelLink(cap, "http://${s.domain}")) }
            assertTrue(ex.imagePngBase64.length > 100)
            assertFailsWith<ApiException> { carol.engine.joinChannel(owner.engine.channelLink(cap, "http://${s.domain}"), ex.token, "00000x") }
            listOf(owner, alice, bob, carol).forEach { it.engine.close() }
        }
    }
}
