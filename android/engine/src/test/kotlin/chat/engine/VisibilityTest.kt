package chat.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisibilityTest {
    @BeforeAll fun check() = assumeTrue(TestServer.available(), "chatd-Binary nicht gesetzt (-Pchatd=…)")

    private fun ChannelState.has(text: String) = posts.any { p -> !p.deleted && !p.bad && p.parts.any { it is Part.Text && it.body == text } }

    @Test fun `channel can switch between private and public`() = runBlocking {
        TestServer().use { s ->
            val owner = newClient(s, "owner"); val bob = newClient(s, "bob")
            val pol = ChannelPolicy(join_mode = "open", members_can_write = true)
            val id = owner.engine.createChannel("Wechsel", pol)
            bob.engine.joinChannel(owner.engine.channelLink(id, "http://${s.domain}"))
            owner.engine.postToChannel(id, listOf(Part.Text("alt")))
            eventually("alt bei Bob") { bob.engine.snapshot()!!.channels[id]?.takeIf { it.has("alt") } }

            owner.engine.updateChannel(id, null, pol, makePublic = true)
            owner.engine.postToChannel(id, listOf(Part.Text("neu-oeffentlich")))
            eventually("neu bei Bob") { bob.engine.snapshot()!!.channels[id]?.takeIf { it.has("neu-oeffentlich") && it.policy.isPublic } }
            assertTrue(bob.engine.snapshot()!!.channels[id]!!.has("alt"))

            owner.engine.updateChannel(id, null, pol, makePublic = false)
            eventually("Bob braucht Schlüssel") { bob.engine.snapshot()!!.channels[id]?.takeIf { it.needsKey } }
            bob.engine.rekeyChannel(id, owner.engine.channelLink(id, "http://${s.domain}"))
            owner.engine.postToChannel(id, listOf(Part.Text("geheim")))
            eventually("geheim bei Bob") { bob.engine.snapshot()!!.channels[id]?.takeIf { it.has("geheim") } }
            assertTrue(bob.engine.snapshot()!!.channels[id]!!.has("neu-oeffentlich"))
            Unit
        }
    }
}
