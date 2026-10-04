package chat.engine

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sperren mitten in laufenden Änderungen darf die gespeicherten Daten nie beschädigen; Entsperren muss danach zügig klappen. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LockTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    @Test fun `lock while saving, then unlock repeatedly`() = runBlocking {
        TestServer().use { s ->
            val alice = newClient(s, "alice")
            val id = alice.engine.openSelfChat()
            repeat(5) { round ->
                // viele Änderungen (lösen Hintergrund-Speichern aus), währenddessen sperren
                val jobs = (1..5).map { i -> testScope.launch { runCatching { alice.engine.sendMessage(id, text = "r$round-$i") } } }
                kotlinx.coroutines.delay(30L * round)
                alice.engine.lock()
                jobs.forEach { it.join() }
                assertEquals(Phase.Locked, alice.engine.phase.value)
                val t = System.currentTimeMillis()
                alice.engine.unlock(PASS)
                val ms = System.currentTimeMillis() - t
                println("Entsperren Runde $round: $ms ms – ${alice.engine.diag.lastUnlock}")
                assertEquals(Phase.Unlocked, alice.engine.phase.value)
                assertTrue(ms < 20_000, "Entsperren dauert zu lange: $ms ms")
            }
        }
    }

    @Test fun `pin vault is fast and rejects wrong pin`() {
        val t = System.currentTimeMillis()
        val blob = PinVault.seal("2468", "geheime passphrase".toByteArray())
        assertEquals("geheime passphrase", String(PinVault.open("2468", blob)))
        assertTrue(runCatching { PinVault.open("1357", blob) }.isFailure)
        println("PIN-Ableitung (3x): ${System.currentTimeMillis() - t} ms")
    }
}
