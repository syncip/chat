package chat.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodesTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    @Test fun `chat code starts a chat and QR link adds a device`() = runBlocking {
        TestServer().use { s ->
            val alice = newClient(s, "alice")
            val bob = newClient(s, "bob")
            assertEquals("martinistcool", alice.engine.setChatCode("MartinIstCool"))
            assertEquals("martinistcool", alice.engine.myChatCode())
            assertFailsWith<ChatException> { bob.engine.setChatCode("martinistcool") } // vergeben
            assertFailsWith<ChatException> { bob.engine.resolveChatCode("gibtsnicht") }
            val card = bob.engine.resolveChatCode("martinistcool")
            assertEquals(alice.address, card.address)
            val conv = bob.engine.startChat(card)
            eventually("Anfrage bei Alice") { alice.engine.snapshot()!!.conversations.values.firstOrNull { it.status == "request" } }
            assertEquals(true, conv.isNotEmpty())

            // QR-Anmeldung eines zweiten Geräts
            val link = alice.engine.createDeviceLink()
            val dev2 = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("qrdev").toFile()), scope = testScope)
            dev2.init()
            dev2.linkFromQr(link, "neue-passphrase-1")
            assertEquals(alice.address, dev2.snapshot()!!.me.address)
            assertFailsWith<ChatException> { dev2.linkFromQr(link, "neue-passphrase-1") } // nur einmal einlösbar
            assertFailsWith<ChatException> { dev2.linkFromQr("chatlink1:xx", "x") }
            Unit
        }
    }
}
