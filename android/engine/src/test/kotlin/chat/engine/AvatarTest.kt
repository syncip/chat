package chat.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvatarTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    private val img = "data:image/png;base64,iVBORw0KGgo="

    @Test fun `profile and channel pictures, own channel files deletable`() = runBlocking {
        TestServer().use { s ->
            val alice = newClient(s, "alice")
            val bob = newClient(s, "bob")
            assertFailsWith<ChatException> { alice.engine.setMyAvatar("javascript:alert(1)") }
            val link = alice.engine.contactLink("http://${s.domain}")
            bob.engine.startChat(decodeCard(link))
            eventually("Anfrage bei Alice") { alice.engine.snapshot()!!.conversations.values.firstOrNull { it.status == "request" } }
            val req = alice.engine.snapshot()!!.conversations.values.first { it.status == "request" }
            alice.engine.acceptRequest(req.id)
            alice.engine.setMyAvatar(img)
            eventually("Profilbild bei Bob") { bob.engine.snapshot()!!.avatars[alice.address].takeIf { it == img } }
            alice.engine.setMyAvatar(null)
            eventually("Profilbild entfernt") { if (bob.engine.snapshot()!!.avatars[alice.address] == null) true else null }

            // Kanalbild
            val ch = alice.engine.createChannel("Fotos", ChannelPolicy(join_mode = "open", members_can_write = true))
            alice.engine.updateChannel(ch, null, ChannelPolicy(join_mode = "open", members_can_write = true), avatar = img)
            eventually("Kanalbild bei Alice") { alice.engine.snapshot()!!.channels[ch]?.avatar.takeIf { it == img } }
            bob.engine.joinChannel(alice.engine.channelLink(ch, "http://${s.domain}"))
            eventually("Kanalbild bei Bob") { bob.engine.snapshot()!!.channels[ch]?.avatar.takeIf { it == img } }
            assertEquals("Fotos", bob.engine.snapshot()!!.channels[ch]!!.title)

            // Datei in Kanal posten und als Autor löschen (Beitrag + Datei)
            val bytes = ByteArray(5000) { it.toByte() }
            alice.engine.postToChannel(ch, "Bild", files = listOf(Engine.Attachment("a.png", "image/png", bytes)))
            val post = eventually("Datei-Beitrag") { alice.engine.snapshot()!!.channels[ch]!!.posts.firstOrNull { p -> p.parts.any { it is Part.File } } }
            val blobId = post.parts.filterIsInstance<Part.File>().first().blob_id
            alice.engine.deleteOwnFiles(post.id, chanId = ch)
            eventually("Beitrag gelöscht") { alice.engine.snapshot()!!.channels[ch]!!.posts.firstOrNull { it.id == post.id }?.takeIf { it.deleted } }
            assertTrue(blobId.isNotEmpty())
            Unit
        }
    }
}
