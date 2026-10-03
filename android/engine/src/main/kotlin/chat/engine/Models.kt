package chat.engine

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** Wire-/Speicherformat; identisch zum Web-Client (`web/src/lib/types.ts`) und zum Rust-Kern (`core/src/message.rs`). */
@OptIn(ExperimentalSerializationApi::class)
val ChatJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Cap(
    val domain: String,
    val mailbox_id: String,
    val send_token: String,
    val key: String,
    /** true: nur Intro-Postfach (Platzhalter bis zur Selbst-Ankündigung des Kontakts). */
    val intro: Boolean? = null,
    /** Gerät, dem das Postfach gehört (jedes Gerät hat eigene Unterhaltungs-Postfächer). */
    val device: String? = null,
)

@Serializable
data class CapEntry(
    val address: String,
    val device: String = "",
    val domain: String,
    val mailbox_id: String,
    val send_token: String,
    val key: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class Part {
    @Serializable @SerialName("text")
    data class Text(val body: String) : Part()

    @Serializable @SerialName("code")
    data class Code(val lang: String, val body: String) : Part()

    @Serializable @SerialName("quote")
    data class Quote(val reference: String, val snippet: String) : Part()

    @Serializable @SerialName("file")
    data class File(
        val blob_id: String,
        val blob_server: String,
        val key: String,
        val nonce: String,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String,
    ) : Part()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class Content {
    @Serializable @SerialName("message")
    data class Message(val parts: List<Part>, val once: Boolean? = null) : Content()

    @Serializable @SerialName("reaction")
    data class Reaction(val reference: String, val emoji: String) : Content()

    @Serializable @SerialName("edit")
    data class Edit(val reference: String, val parts: List<Part>) : Content()

    @Serializable @SerialName("delete")
    data class Delete(val reference: String) : Content()

    /** Zustell-/Lesebestätigung (nur 1:1-Chats): receipt = "delivered" | "read". */
    @Serializable @SerialName("receipt")
    data class Receipt(val receipt: String, val references: List<String>) : Content()

    @Serializable @SerialName("disappear")
    data class Disappear(val seconds: Long) : Content()

    @Serializable @SerialName("directory")
    data class Directory(val entries: List<CapEntry>) : Content()

    @Serializable @SerialName("group_name")
    data class GroupName(val name: String) : Content()
}

@Serializable
data class Envelope(
    val v: Int = 1,
    val id: String,
    val ts: Long,
    val content: Content,
)

@Serializable
data class Msg(
    val id: String,
    val from: String,
    val ts: Long,
    var parts: List<Part>,
    var status: String, // sending | sent | delivered | read | failed | received
    var edited: Boolean? = null,
    var deleted: Boolean? = null,
    val reactions: MutableMap<String, MutableList<String>> = mutableMapOf(),
    val expiresAt: Long? = null,
    /** Einmal-Nachricht: nach dem ersten Anzeigen gelöscht. */
    val once: Boolean? = null,
    var consumed: Boolean? = null,
    /** Eingehend: Lesebestätigung wurde bereits gesendet. */
    var readAck: Boolean? = null,
)

/** Ein MLS-Blatt (Gerät); `ik` = Konto-Schlüssel (AIK). */
@Serializable
data class Member(val address: String, val ik: String, val device: String)

@Serializable
data class MyMailbox(val id: String, val key: String, val token: String)

@Serializable
data class Conversation(
    val id: String, // hex der MLS-Gruppen-ID
    val kind: String, // dm | group
    var title: String,
    var status: String, // active | request | left
    var members: List<Member>,
    val caps: MutableMap<String, Cap> = mutableMapOf(),
    var myMailbox: MyMailbox? = null,
    val messages: MutableList<Msg> = mutableListOf(),
    var unread: Int = 0,
    var disappearSeconds: Long = 0,
    var warning: String? = null,
    /** Gerät wurde per Welcome aus dem eigenen Konto aufgenommen: Postfach erst ankündigen, wenn die Verzeichnisse da sind. */
    var pendingAnnounce: Boolean? = null,
    val createdAt: Long,
)

@Serializable
data class Contact(
    val address: String,
    var ik: String, // hex, angepinnt (TOFU)
    var verified: Boolean = false,
    var intro: Cap? = null,
)

@Serializable
data class Me(val address: String, val domain: String, val name: String, val deviceId: String, val inboxId: String)

@Serializable
data class IntroBox(val mailbox_id: String, val send_token: String, val key: String)

@Serializable
data class OutboxItem(val id: String, val cap: Cap, val blob: String, var tries: Int = 0)

@Serializable
data class AppState(
    val v: Int = 1,
    val me: Me,
    /** Backup-Datei wurde gespeichert (Pflicht nach der Registrierung). */
    var backupDone: Boolean = false,
    var intro: IntroBox?,
    val conversations: MutableMap<String, Conversation> = mutableMapOf(),
    val contacts: MutableMap<String, Contact> = mutableMapOf(),
    /** Postfach-ID → Umschlag-Schlüssel (base64) */
    val mailboxes: MutableMap<String, String> = mutableMapOf(),
    var blockedUsers: MutableList<String> = mutableListOf(),
    var blockedServers: MutableList<String> = mutableListOf(),
    var allowUsers: MutableList<String> = mutableListOf(),
    var allowServers: MutableList<String> = mutableListOf(),
    var filterMode: String = "off", // off | block | allow
    var serverSideFilter: Boolean = false,
    var directSend: Boolean = false,
    var cursor: Long = 0,
    val outbox: MutableList<OutboxItem> = mutableListOf(),
    /** Bestätigungen senden (Standard: aus; wer sie ausschaltet, sieht die der anderen auch nicht). */
    var sendDelivered: Boolean = false,
    var sendRead: Boolean = false,
    /** Einmal-Nachrichten: eigene Kopie sofort entfernen. */
    var onceDropOwnCopy: Boolean = false,
    /** Öffentliche Kanäle (Schlüssel stehen auch in der Backup-Datei, damit neue Geräte sie bekommen). */
    val channels: MutableMap<String, ChannelState> = mutableMapOf(),
)

@Serializable
data class Limits(
    val max_file_size: Long,
    val max_message_attachments: Int,
    val max_message_total_size: Long,
    val max_message_text: Int,
    val max_envelope_size: Long,
    val user_quota: Long,
    val blob_retention_days: Int,
    val message_retention_days: Int,
)

@Serializable
data class ServerInfo(
    val domain: String,
    val version: Int,
    val registration: String,
    val federation: String,
    val client_hash: String = "",
    val pow_bits: Int = 0,
    val limits: Limits,
)

/** Kontaktkarte (Link `…/#/add/<base64url>`). */
data class ContactCard(val address: String, val cap: Cap)

@Serializable
data class DeviceInfo(val id: String, val created_at: Long, val current: Boolean)
