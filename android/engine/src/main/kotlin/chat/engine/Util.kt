package chat.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal val rng = SecureRandom()

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun sha256Bytes(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
fun randomId(): String = randomBytes(12).hex()

private val nameRe = Regex("^([a-z0-9][a-z0-9._-]{1,31})@([a-z0-9.-]+(?::\\d{1,5})?)$")

/** `name@domain` zerlegen (Domain darf `IP:PORT` sein). */
fun splitAddress(a: String): Pair<String, String>? =
    nameRe.matchEntire(a.trim().lowercase())?.let { it.groupValues[1] to it.groupValues[2] }

private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

/** https für Domains; http für localhost und IP-Adressen (kein Zertifikat möglich). */
fun baseUrl(domain: String): String {
    val host = domain.substringBeforeLast(':', domain)
    val plain = host == "localhost" || ipv4.matches(host)
    return "${if (plain) "http" else "https"}://$domain"
}

/** Proof-of-Work für offene Registrierung: führende Null-Bits von sha256("name:ts:nonce"). */
fun solvePow(name: String, ts: Long, bits: Int): String {
    if (bits <= 0) return ""
    var i = 0L
    while (true) {
        val nonce = i.toString(36)
        val h = sha256Bytes("$name:$ts:$nonce".toByteArray())
        var n = 0
        for (b in h) {
            val v = b.toInt() and 0xff
            if (v == 0) { n += 8; continue }
            n += Integer.numberOfLeadingZeros(v) - 24
            break
        }
        if (n >= bits) return nonce
        i++
    }
}

fun encodeCard(c: ContactCard): String {
    val j = kotlinx.serialization.json.buildJsonObject {
        put("a", kotlinx.serialization.json.JsonPrimitive(c.address))
        put("d", kotlinx.serialization.json.JsonPrimitive(c.cap.domain))
        put("m", kotlinx.serialization.json.JsonPrimitive(c.cap.mailbox_id))
        put("t", kotlinx.serialization.json.JsonPrimitive(c.cap.send_token))
        put("k", kotlinx.serialization.json.JsonPrimitive(c.cap.key))
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray())
}

fun decodeCard(text: String): ContactCard {
    val m = Regex("#/add/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
        ?: Regex("^([A-Za-z0-9_-]+)$").matchEntire(text.trim())?.groupValues?.get(1)
        ?: throw ChatException("Ungültiger Kontaktlink")
    val j = try {
        ChatJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(m))) as kotlinx.serialization.json.JsonObject
    } catch (e: Exception) {
        throw ChatException("Ungültiger Kontaktlink")
    }
    fun s(k: String) = (j[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw ChatException("Ungültiger Kontaktlink")
    val addr = s("a")
    if (splitAddress(addr) == null) throw ChatException("Ungültiger Kontaktlink")
    return ContactCard(addr, Cap(s("d"), s("m"), s("t"), s("k"), intro = true))
}

/** Fehler mit nutzerlesbarer (deutscher) Meldung. */
open class ChatException(message: String) : Exception(message)

/** Nur kleine Bilder (data-URL) zulassen. */
fun validAvatar(a: String?): String? =
    a?.takeIf { it.length <= 16 * 1024 && Regex("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$").matches(it) }

/** „https://chat.example.org/pfad“ → „chat.example.org“ (Schema, Pfad und Leerzeichen entfernen, kleinschreiben). */
fun normalizeServer(input: String): String =
    input.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('#').trim()

/** Schnelle, speicherharte Ableitung für kurze Geheimnisse (App-PIN): Argon2id im nativen Kern (19 MiB, 2 Durchläufe). */
object PinVault {
    fun seal(pin: String, data: ByteArray): ByteArray = uniffi.chat_core.vaultSealWith(pin, data, 19u * 1024u, 2u)
    /** Wirft bei falscher PIN. */
    fun open(pin: String, blob: ByteArray): ByteArray = uniffi.chat_core.vaultOpenPlain(pin, blob)
}
