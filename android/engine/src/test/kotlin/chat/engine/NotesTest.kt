package chat.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotesTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    @Test fun `notes to self syncs to a second device`() = runBlocking {
        TestServer().use { s ->
            val alice = newClient(s, "alice")
            val id = alice.engine.openSelfChat()
            assertEquals(id, alice.engine.openSelfChat(), "nur ein Notiz-Chat")
            alice.engine.sendMessage(id, text = "Merkzettel")
            assertTrue(alice.engine.snapshot()!!.conversations[id]!!.messages.any { m -> m.parts.any { it is Part.Text && it.body == "Merkzettel" } })

            val backup = alice.engine.exportBackup("backup-passphrase-1")
            val e2 = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("notes2").toFile()), scope = testScope)
            e2.init()
            e2.linkDevice(backup, "backup-passphrase-1", "neue-passphrase-1")
            val dev2 = Client(e2, java.io.File("."), s, "alice")
            eventually("Notizen auf Gerät 2") { e2.snapshot()!!.conversations.values.firstOrNull { it.kind == "group" } }
            val c2 = e2.snapshot()!!.conversations.values.first { it.kind == "group" }
            e2.sendMessage(c2.id, text = "Vom zweiten Gerät")
            eventually("Nachricht von Gerät 2 bei Gerät 1") { alice.engine.snapshot()!!.conversations[id]?.takeIf { c -> c.messages.any { m -> m.parts.any { it is Part.Text && it.body == "Vom zweiten Gerät" } } } }
            assertTrue(dev2.address.isNotEmpty())
            Unit
        }
    }
}
