package chat.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatFlowTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    private suspend fun connect(from: Client, to: Client) {
        val card = to.engine.myCard()!!
        from.engine.startChat(card)
        val req = eventually("Anfrage bei ${to.name}") { to.conv { it.status == "request" } }
        to.engine.acceptRequest(req.id)
        eventually("Chat aktiv bei ${from.name}") { from.conv { c -> c.caps.keys.any { it.startsWith(to.address + "#") } } }
    }

    @Test fun `registration validation and invite rules`() = runBlocking {
        TestServer().use { s ->
            val e = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("x").toFile()), scope = testScope)
            e.init()
            val ex = assertFailsWith<ApiException> { e.createAccount(s.domain, "alice", "", PASS) }
            assertTrue(ex.message!!.contains("invite"), ex.message)
            e.createAccount(s.domain, "alice", s.invite(), PASS)
            val e2 = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("y").toFile()), scope = testScope)
            e2.init()
            assertFailsWith<ApiException> { e2.createAccount(s.domain, "alice", s.invite(), PASS) } // Name vergeben
            e.close(); e2.close()
        }
    }

    @Test fun `federated 1to1 text code quote file and block`() = runBlocking {
        TestServer().use { a -> TestServer().use { b ->
            val alice = newClient(a, "alice")
            val bob = newClient(b, "bob") // anderer Server → Föderation
            connect(from = bob, to = alice)

            bob.engine.sendMessage(bob.conv()!!.id, text = "Hallo Alice, **verschlüsselt**!")
            eventually("Text bei Alice") { alice.conv { it.hasText("verschlüsselt") } }
            val aliceConv = alice.conv()!!
            alice.engine.sendMessage(aliceConv.id, text = "Hi Bob")
            val bobMsg = eventually("Text bei Bob") { bob.conv { it.hasText("Hi Bob") } }

            // Code + Zitat
            alice.engine.sendMessage(aliceConv.id, code = "kotlin" to "println(\"<b>x</b>\")")
            val withCode = eventually("Code bei Bob") { bob.conv { c -> c.messages.any { m -> m.parts.any { it is Part.Code } } } }
            val code = withCode.messages.flatMap { it.parts }.filterIsInstance<Part.Code>().first()
            assertEquals("kotlin", code.lang); assertEquals("println(\"<b>x</b>\")", code.body)
            val quoted = bobMsg.messages.first { m -> m.parts.any { it is Part.Text && it.body == "Hi Bob" } }
            bob.engine.sendMessage(bobMsg.id, text = "Zitat-Antwort", quote = quoted)
            eventually("Zitat bei Alice") { alice.conv { c -> c.messages.any { m -> m.parts.any { it is Part.Quote && it.snippet == "Hi Bob" } } } }

            // Binärdatei (.exe) Ende-zu-Ende
            val bytes = ByteArray(150_000) { (it * 7).toByte() }
            bob.engine.sendMessage(bobMsg.id, files = listOf(Engine.Attachment("setup.exe", "application/x-msdownload", bytes)))
            val withFile = eventually("Datei bei Alice") { alice.conv { c -> c.messages.any { m -> m.parts.any { it is Part.File } } } }
            val fp = withFile.messages.flatMap { it.parts }.filterIsInstance<Part.File>().first()
            assertEquals("setup.exe", fp.name)
            assertTrue(alice.engine.downloadFile(fp).contentEquals(bytes), "Datei bitgenau")

            // Reaktion, Bearbeiten, Löschen
            val hi = alice.conv()!!.messages.first { m -> m.from == alice.address && m.parts.any { it is Part.Text } }
            alice.engine.react(aliceConv.id, hi.id, "👍")
            eventually("Reaktion bei Bob") { bob.conv { c -> c.messages.any { it.id == hi.id && it.reactions["👍"]?.contains(alice.address) == true } } }
            alice.engine.editMessage(aliceConv.id, hi.id, "Hi Bob (bearbeitet)")
            eventually("Edit bei Bob") { bob.conv { c -> c.messages.any { it.id == hi.id && it.edited == true && it.parts.any { p -> p is Part.Text && p.body.contains("bearbeitet") } } } }
            alice.engine.deleteMessage(aliceConv.id, hi.id)
            eventually("Delete bei Bob") { bob.conv { c -> c.messages.any { it.id == hi.id && it.deleted == true } } }

            // Blockieren: Nachrichten von Bob erscheinen nicht mehr
            alice.engine.blockUser(bob.address)
            bob.engine.sendMessage(bobMsg.id, text = "nach dem Blockieren")
            kotlinx.coroutines.delay(3000)
            assertTrue(alice.conv()!!.messages.none { m -> m.parts.any { it is Part.Text && it.body.contains("nach dem Blockieren") } })
            listOf(alice, bob).forEach { it.engine.close() }
        } }
    }

    @Test fun `receipts follow settings and once messages are burned after reading`() = runBlocking {
        TestServer().use { a ->
            val alice = newClient(a, "alice"); val bob = newClient(a, "bob")
            connect(bob, alice)
            val bconv = bob.conv()!!.id; val aconv = alice.conv()!!.id
            suspend fun sentStatus(text: String) = bob.conv()!!.messages.first { m -> m.parts.any { it is Part.Text && it.body == text } }.status

            // Standard: nur „gesendet“
            bob.engine.sendMessage(bconv, text = "erste")
            eventually("bei Alice") { alice.conv { it.hasText("erste") } }
            alice.engine.markRead(aconv)
            kotlinx.coroutines.delay(1500)
            assertEquals("sent", sentStatus("erste"))

            // Alice sendet „empfangen“ und „gelesen“
            alice.engine.setReceiptSettings(sendDelivered = true, sendRead = true)
            bob.engine.sendMessage(bconv, text = "zweite")
            eventually("delivered") { if (sentStatus("zweite") == "delivered" || sentStatus("zweite") == "read") true else null }
            alice.engine.markRead(aconv)
            eventually("read") { if (sentStatus("zweite") == "read") true else null }

            // Einmal-Nachricht
            bob.engine.sendMessage(bconv, text = "Kennwort: hunter2", once = true)
            val msg = eventually("Einmal-Nachricht bei Alice") { alice.conv()!!.messages.firstOrNull { it.once == true } }
            assertEquals("🔒 Einmal-Nachricht", snippetOf(msg))
            val parts = alice.engine.revealOnce(aconv, msg.id)
            assertTrue(parts!!.any { it is Part.Text && it.body.contains("hunter2") })
            assertEquals(null, alice.engine.revealOnce(aconv, msg.id), "nur einmal")
            assertTrue(alice.conv()!!.messages.first { it.id == msg.id }.parts.isEmpty())
            eventually("Absender sieht gelesen") { if (bob.conv()!!.messages.first { it.once == true }.status == "read") true else null }
            listOf(alice, bob).forEach { it.engine.close() }
        }
    }

    @Test fun `group across two servers and member removal`() = runBlocking {
        TestServer().use { a -> TestServer().use { b ->
            val alice = newClient(a, "alice"); val carol = newClient(a, "carol"); val bob = newClient(b, "bob")
            connect(bob, alice); connect(carol, alice)
            val gid = alice.engine.createGroup("Projekt X", listOf(bob.address, carol.address))
            for (c in listOf(bob, carol)) {
                val req = eventually("Gruppen-Anfrage bei ${c.name}") { c.conv { it.status == "request" && it.kind == "group" } }
                c.engine.acceptRequest(req.id)
            }
            alice.engine.sendMessage(gid, text = "hallo gruppe")
            eventually("bei Bob") { bob.conv { it.kind == "group" && it.hasText("hallo gruppe") } }
            eventually("bei Carol") { carol.conv { it.kind == "group" && it.hasText("hallo gruppe") } }
            eventually("Gruppenname bei Bob") { bob.conv { it.kind == "group" && it.title == "Projekt X" } }
            // Postfach-Verzeichnis: Bob und Carol kennen sich nur über Alices Weiterleitung
            eventually("Verzeichnis vollständig") {
                val bg = bob.conv { it.kind == "group" }!!; val cg = carol.conv { it.kind == "group" }!!
                if (bg.caps.keys.any { it.startsWith(carol.address + "#") } && cg.caps.keys.any { it.startsWith(bob.address + "#") }) true else null
            }
            val bg = bob.conv { it.kind == "group" }!!.id; val cg = carol.conv { it.kind == "group" }!!.id
            bob.engine.sendMessage(bg, text = "bob an alle")
            eventually("Bob→Alice") { alice.conv { it.kind == "group" && it.hasText("bob an alle") } }
            eventually("Bob→Carol") { carol.conv { it.kind == "group" && it.hasText("bob an alle") } }
            carol.engine.sendMessage(cg, text = "carol an alle")
            eventually("Carol→Bob") { bob.conv { it.kind == "group" && it.hasText("carol an alle") } }

            alice.engine.removeMember(gid, carol.address)
            eventually("Carol entfernt") { carol.conv { it.kind == "group" && it.status == "left" } }
            alice.engine.sendMessage(gid, text = "geheim nach Entfernung")
            eventually("Bob liest") { bob.conv { it.kind == "group" && it.hasText("geheim nach Entfernung") } }
            kotlinx.coroutines.delay(2500)
            assertTrue(carol.conv { it.kind == "group" }!!.messages.none { m -> m.parts.any { it is Part.Text && it.body.contains("geheim") } })
            listOf(alice, bob, carol).forEach { it.engine.close() }
        } }
    }

    @Test fun `lock unlock persistence`() = runBlocking {
        TestServer().use { a ->
            val alice = newClient(a, "alice"); val bob = newClient(a, "bob")
            connect(bob, alice)
            bob.engine.sendMessage(bob.conv()!!.id, text = "bleibt erhalten")
            eventually("Nachricht") { alice.conv { it.hasText("bleibt erhalten") } }
            alice.engine.lock()
            assertEquals(Phase.Locked, alice.engine.phase.value)
            assertFailsWith<ChatException> { alice.engine.unlock("falsche passphrase!!") }
            alice.engine.unlock(PASS)
            assertTrue(alice.conv { it.hasText("bleibt erhalten") } != null, "Verlauf nach Entsperren")
            listOf(alice.engine, bob.engine).forEach { it.close() }
        }
    }

    @Test fun `second device via backup joins chats and reads writes and can be revoked`() = runBlocking {
        TestServer().use { a ->
            val alice = newClient(a, "alice"); val bob = newClient(a, "bob")
            connect(bob, alice)
            bob.engine.sendMessage(bob.conv()!!.id, text = "vor dem zweiten geraet")
            eventually("Nachricht bei Alice") { alice.conv { it.hasText("vor dem zweiten geraet") } }

            val backup = alice.engine.exportBackup("backup-passphrase-1")
            // falsche Passphrase / kaputte Datei
            val e2 = Engine(FileBlobStore(java.nio.file.Files.createTempDirectory("device2").toFile()), scope = testScope)
            e2.init()
            assertFailsWith<ChatException> { e2.linkDevice(backup, "falsch-falsch-falsch", "neue-passphrase-1") }
            assertFailsWith<ChatException> { e2.linkDevice(backup.copyOf(backup.size / 2), "backup-passphrase-1", "neue-passphrase-1") }
            e2.linkDevice(backup, "backup-passphrase-1", "neue-passphrase-1")
            val dev2 = Client(e2, java.io.File("."), a, "alice")
            eventually("Hinweis auf neues Gerät beim ersten Gerät") { alice.state().alerts.firstOrNull { it.kind == "device" } }
            assertEquals(2, e2.listDevices().size)

            // Gerät 1 nimmt Gerät 2 automatisch in die Unterhaltung auf; der alte Verlauf bleibt unlesbar
            val conv2 = eventually("Unterhaltung auf Gerät 2") { dev2.conv { it.kind == "dm" && it.status == "active" } }
            assertTrue(conv2.messages.none { m -> m.parts.any { it is Part.Text && it.body.contains("vor dem") } }, "kein Verlauf vor dem Beitritt")
            eventually("Gerät 2 hat Postfächer von Bob und Gerät 1") {
                dev2.conv { c -> c.caps.keys.any { it.startsWith(bob.address + "#") } }
            }
            eventually("Bob kennt das Postfach von Gerät 2") {
                bob.conv { c -> c.caps.keys.count { it.startsWith(alice.address + "#") } == 2 }
            }

            // Bob → beide Geräte
            bob.engine.sendMessage(bob.conv()!!.id, text = "an beide geraete")
            eventually("Gerät 1") { alice.conv { it.hasText("an beide geraete") } }
            eventually("Gerät 2") { dev2.conv { it.hasText("an beide geraete") } }
            // Gerät 2 → Bob und Gerät 1
            e2.sendMessage(conv2.id, text = "von geraet 2")
            eventually("Bob liest Gerät 2") { bob.conv { it.hasText("von geraet 2") } }
            eventually("Gerät 1 sieht Nachricht von Gerät 2") { alice.conv { c -> c.messages.any { it.from == alice.address && it.parts.any { p -> p is Part.Text && p.body == "von geraet 2" } } } }

            // Widerruf
            val d2 = e2.listDevices().first { it.current }.id
            alice.engine.revokeDevice(d2)
            eventually("Bob entfernt Gerät 2 aus den Postfächern") { bob.conv { c -> c.members.count { it.address == alice.address } == 1 } }
            bob.engine.sendMessage(bob.conv()!!.id, text = "nur noch geraet 1")
            eventually("Gerät 1 liest weiter") { alice.conv { it.hasText("nur noch geraet 1") } }
            kotlinx.coroutines.delay(2500)
            assertTrue(dev2.conv { it.hasText("nur noch geraet 1") } == null, "widerrufenes Gerät erhält nichts mehr")
            listOf(alice.engine, bob.engine, e2).forEach { it.close() }
        }
    }
}
