package chat.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitTest {
    @Test fun `baseUrl uses http for IPs and localhost`() {
        assertEquals("https://chat.example.org", baseUrl("chat.example.org"))
        assertEquals("http://192.168.1.10:8080", baseUrl("192.168.1.10:8080"))
        assertEquals("http://localhost:8080", baseUrl("localhost:8080"))
    }

    @Test fun `splitAddress`() {
        assertEquals("alice" to "192.168.1.10:8080", splitAddress("alice@192.168.1.10:8080"))
        assertNull(splitAddress("a@b"))
        assertNull(splitAddress("alice"))
    }

    @Test fun `card roundtrip`() {
        val c = ContactCard("bob@b.example", Cap("b.example", "mb", "tok", "a2V5"))
        val d = decodeCard("https://x.example/#/add/${encodeCard(c)}")
        assertEquals(c.address, d.address)
        assertEquals(true, d.cap.intro)
        assertFailsWith<ChatException> { decodeCard("nope!!") }
    }

    @Test fun `proof of work meets difficulty`() {
        val n = solvePow("alice", 1_700_000_000, 10)
        val h = sha256Bytes("alice:1700000000:$n".toByteArray())
        assertTrue(h[0].toInt() == 0 && (h[1].toInt() and 0xC0) == 0)
    }

    @Test fun `wire format matches core`() {
        // So serialisiert der Rust-Kern (serde); Kotlin muss es lesen und identisch schreiben können.
        val json = """{"v":1,"id":"1","ts":5,"content":{"kind":"message","parts":[{"type":"quote","reference":"r","snippet":"s"},{"type":"text","body":"b"}]}}"""
        val e = ChatJson.decodeFromString(Envelope.serializer(), json)
        assertEquals(json, ChatJson.encodeToString(Envelope.serializer(), e))
        val dir = """{"v":1,"id":"2","ts":1,"content":{"kind":"directory","entries":[{"address":"a@b.c","device":"0123456789abcdef","domain":"b.c","mailbox_id":"m","send_token":"t","key":"k"}]}}"""
        assertEquals(dir, ChatJson.encodeToString(Envelope.serializer(), ChatJson.decodeFromString(Envelope.serializer(), dir)))
    }

    @Test fun `ffi core roundtrip`() {
        val a = uniffi.chat_core.MlsClient.create("alice@a.example")
        val b = uniffi.chat_core.MlsClient.create("bob@b.example")
        val kp = b.keyPackages(1u, false)[0]
        val gid = a.createGroup()
        val add = a.addMembers(gid, listOf(kp))
        assertTrue(b.join(add.welcome).contentEquals(gid))
        val env = """{"v":1,"id":"1","ts":1,"content":{"kind":"message","parts":[{"type":"text","body":"hi"}]}}"""
        val res = ChatJson.parseToJsonElement(b.process(gid, a.encryptEnvelope(gid, env))).toString()
        assertTrue(res.contains("\"sender\":\"alice@a.example\""), res)
    }
}
