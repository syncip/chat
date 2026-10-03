package chat.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import uniffi.chat_core.MlsClient
import java.util.Base64

/**
 * Öffentliche Kanäle (docs/CHANNELS.md): serverunterstützt, Kanalschlüssel nur im Link-Fragment.
 * Beiträge sind mit dem Kanalschlüssel verschlüsselt und mit dem Konto-Schlüssel (AIK) des Absenders signiert.
 */
private const val KIND_CHANNEL: UByte = 3u
private val TITLE_GID = "title".toByteArray()

@Serializable
data class ChannelPolicy(
    val join_mode: String = "open", // open | approval | pow | captcha
    val pow_bits: Int = 16,
    val probation_seconds: Long = 0,
    val members_can_write: Boolean = false,
    val slow_mode_seconds: Long = 0,
)

@Serializable
data class ChannelMember(
    val ik: String = "",
    val address: String = "",
    val role: String = "member", // owner | mod | write | member | read
    val status: String = "pending", // active | pending | banned
    val joined_at: Long = 0,
    val muted_until: Long = 0,
    val can_write: Boolean = false,
)

@Serializable
data class ChPost(
    val id: String,
    val seq: Long,
    val ts: Long,
    val from: String,
    val ik: String,
    var parts: List<Part> = emptyList(),
    var deleted: Boolean = false,
    /** Signatur oder Entschlüsselung fehlgeschlagen. */
    val bad: Boolean = false,
)

@Serializable
data class ChEvent(val seq: Long, val ts: Long, val kind: String, val actor: String, val targetAddress: String = "", val meta: String = "")

@Serializable
data class ChannelState(
    val id: String,
    val server: String,
    /** Kanalschlüssel (base64); nur im Link und lokal. */
    val key: String,
    var title: String,
    var policy: ChannelPolicy,
    var me: ChannelMember,
    val posts: MutableList<ChPost> = mutableListOf(),
    val events: MutableList<ChEvent> = mutableListOf(),
    var cursor: Long = 0,
    var unread: Int = 0,
    val createdAt: Long,
)

data class ChannelPreview(val id: String, val server: String, val title: String, val policy: ChannelPolicy, val members: Int)

class NeedsCaptcha(val token: String, val imagePngBase64: String) : ChatException("Captcha erforderlich")

data class ChannelLink(val s: String, val c: String, val k: String)

fun encodeChannelLink(l: ChannelLink): String {
    val j = buildJsonObject { put("s", l.s); put("c", l.c); put("k", l.k) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(j.toString().toByteArray())
}

fun decodeChannelLink(text: String): ChannelLink {
    val m = Regex("#/join/([A-Za-z0-9_-]+)").find(text)?.groupValues?.get(1)
        ?: Regex("^([A-Za-z0-9_-]+)$").matchEntire(text.trim())?.groupValues?.get(1)
        ?: throw ChatException("Ungültiger Kanal-Link")
    return try {
        val j = ChatJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(m))).jsonObject
        ChannelLink(j["s"]!!.jsonPrimitive.content, j["c"]!!.jsonPrimitive.content, j["k"]!!.jsonPrimitive.content)
    } catch (e: Exception) {
        throw ChatException("Ungültiger Kanal-Link")
    }
}

/** Proof-of-Work für den Kanal-Beitritt: sha256("id:ik:nonce") mit `bits` führenden Null-Bits. */
fun solveChannelPow(id: String, ik: ByteArray, bits: Int): String {
    val ikb = ik.b64()
    var i = 0L
    while (true) {
        val nonce = i.toString(36)
        val h = sha256Bytes("$id:$ikb:$nonce".toByteArray())
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

/** Signierter Zugriff auf einen Kanal (`Authorization: Chan-Sig`, Signatur mit dem Konto-Schlüssel). */
internal class ChannelApi(http: OkHttpClient, private val server: String, private val id: String, private val client: MlsClient) {
    private val api = Api(server, null, http)
    private val ik = client.identityPublic()

    private fun header(method: String, uri: String, bodyHash: String): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = randomBytes(12).b64()
        val sig = client.signAccount("CHAT-CHAN-V1\n$server\n$method\n$uri\n$ts\n$nonce\n$bodyHash".toByteArray()).b64()
        return mapOf("ik" to ik.b64(), "ts" to ts, "nonce" to nonce, "sig" to sig)
    }

    suspend fun call(method: String, path: String, json: String? = null): String {
        val uri = "/v1/channels/$id$path"
        val h = header(method, uri, sha256Bytes(json?.toByteArray() ?: ByteArray(0)).hex())
        return api.rawJson(method, uri, mapOf("Authorization" to "Chan-Sig ik=${h["ik"]},ts=${h["ts"]},nonce=${h["nonce"]},sig=${h["sig"]}"), json)
    }

    fun wsAuth(): String {
        val h = header("GET", "/v1/channels/$id/stream", "WS")
        return buildJsonObject { h.forEach { (k, v) -> put(k, v) } }.toString()
    }

    fun wsUrl(): String = api.base.replaceFirst("http", "ws") + "/v1/channels/$id/stream"
}

/** Kanal-Logik: Anlegen, Beitreten, Synchronisieren, Posten, Moderation. Läuft auf dem Engine-Thread. */
internal class ChannelManager(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val host: Host,
) {
    interface Host {
        fun state(): AppState
        fun client(): MlsClient
        fun dirty()
        suspend fun home(method: String, uri: String, json: String?): String
        fun closed(): Boolean
    }

    private val sockets = mutableMapOf<String, WebSocket>()
    private val retry = mutableMapOf<String, Job>()
    private val syncing = mutableSetOf<String>()

    private val channels get() = host.state().channels
    private fun api(c: ChannelState) = ChannelApi(http, c.server, c.id, host.client())
    private fun seal(key: String, gid: ByteArray, payload: ByteArray) = uniffi.chat_core.envelopeSeal(key.unb64(), KIND_CHANNEL, gid, ByteArray(0), payload)

    private fun open(key: String, gid: ByteArray, blob: ByteArray): ByteArray? = try {
        val o = uniffi.chat_core.envelopeOpen(key.unb64(), blob)
        if (o.kind == KIND_CHANNEL && o.groupId.contentEquals(gid)) o.payload else null
    } catch (e: Exception) { null }

    private fun title(key: String, enc64: String): String = open(key, TITLE_GID, enc64.unb64())?.let { String(it) } ?: "(unbekannt)"

    private fun policyOf(o: JsonObject) = ChatJson.decodeFromJsonElement(ChannelPolicy.serializer(), o)

    private fun policyJson(p: ChannelPolicy) = ChatJson.encodeToJsonElement(ChannelPolicy.serializer(), p)

    fun link(id: String): String = channels[id]!!.let { "#/join/${encodeChannelLink(ChannelLink(it.server, it.id, it.key))}" }

    // ---------- Verwaltung ----------

    suspend fun create(title: String, policy: ChannelPolicy): String {
        val key = uniffi.chat_core.envelopeKey().b64()
        val name = title.trim().ifEmpty { "Kanal" }
        val body = buildJsonObject { put("title_enc", seal(key, TITLE_GID, name.toByteArray()).b64()); put("policy", policyJson(policy)) }.toString()
        val id = ChatJson.parseToJsonElement(host.home("POST", "/v1/channels", body)).jsonObject["id"]!!.jsonPrimitive.content
        val s = host.state()
        val c = ChannelState(id, s.me.domain, key, name, policy, ChannelMember(address = s.me.address, role = "owner", status = "active", can_write = true), createdAt = System.currentTimeMillis())
        channels[id] = c
        host.dirty()
        sync(id)
        watch(id)
        return id
    }

    private suspend fun publicInfo(server: String, id: String): JsonObject =
        ChatJson.parseToJsonElement(Api(server, null, http).publicGet("/v1/channels/$id")).jsonObject

    suspend fun preview(linkText: String): ChannelPreview {
        val l = decodeChannelLink(linkText)
        val i = publicInfo(l.s, l.c)
        return ChannelPreview(l.c, l.s, title(l.k, i["title_enc"]!!.jsonPrimitive.content), policyOf(i["policy"]!!.jsonObject), i["members"]!!.jsonPrimitive.content.toInt())
    }

    /** Beitreten. Bei Captcha-Kanälen wirft die Methode [NeedsCaptcha]; danach erneut mit Token und Antwort aufrufen. */
    suspend fun join(linkText: String, captchaToken: String? = null, captchaAnswer: String? = null): String {
        val l = decodeChannelLink(linkText)
        channels[l.c]?.takeIf { it.me.status != "banned" }?.let { return l.c }
        val info = publicInfo(l.s, l.c)
        val policy = policyOf(info["policy"]!!.jsonObject)
        val client = host.client()
        val body = buildJsonObject {
            put("address", host.state().me.address)
            when (policy.join_mode) {
                "pow" -> put("pow_nonce", solveChannelPow(l.c, client.identityPublic(), policy.pow_bits))
                "captcha" -> {
                    if (captchaToken == null || captchaAnswer == null) {
                        val cap = ChatJson.parseToJsonElement(
                            Api(l.s, null, http).publicGet("/v1/channels/${l.c}/captcha?ik=" + java.net.URLEncoder.encode(client.identityPublic().b64(), "UTF-8")),
                        ).jsonObject
                        throw NeedsCaptcha(cap["token"]!!.jsonPrimitive.content, cap["image"]!!.jsonPrimitive.content)
                    }
                    put("captcha_token", captchaToken); put("captcha_answer", captchaAnswer)
                }
            }
        }.toString()
        val r = ChannelApi(http, l.s, l.c, client).call("POST", "/join", body)
        val me = ChatJson.decodeFromJsonElement(ChannelMember.serializer(), ChatJson.parseToJsonElement(r).jsonObject["me"]!!)
        val c = ChannelState(l.c, l.s, l.k, title(l.k, info["title_enc"]!!.jsonPrimitive.content), policy, me, createdAt = System.currentTimeMillis())
        channels[l.c] = c
        host.dirty()
        sync(l.c)
        watch(l.c)
        return l.c
    }

    suspend fun leave(id: String) {
        val c = channels[id] ?: return
        if (c.me.role != "owner" && c.me.status != "banned") runCatching { api(c).call("POST", "/leave") }
        unwatch(id)
        channels.remove(id)
        host.dirty()
    }

    suspend fun remove(id: String) {
        val c = channels[id] ?: return
        api(c).call("DELETE", "")
        unwatch(id)
        channels.remove(id)
        host.dirty()
    }

    suspend fun update(id: String, title: String?, policy: ChannelPolicy) {
        val c = channels[id]!!
        val body = buildJsonObject {
            put("policy", policyJson(policy))
            if (!title.isNullOrBlank() && title != c.title) put("title_enc", seal(c.key, TITLE_GID, title.toByteArray()).b64())
        }.toString()
        api(c).call("PUT", "/settings", body)
        sync(id)
    }

    suspend fun mod(id: String, action: String, target: String? = null, postId: String? = null, role: String? = null, seconds: Long? = null) {
        val body = buildJsonObject {
            put("action", action)
            target?.let { put("target", it) }; postId?.let { put("post_id", it) }; role?.let { put("role", it) }; seconds?.let { put("seconds", it) }
        }.toString()
        api(channels[id]!!).call("POST", "/mod", body)
        sync(id)
    }

    suspend fun members(id: String, status: String? = null): List<ChannelMember> {
        val r = api(channels[id]!!).call("GET", "/members" + (status?.let { "?status=$it" } ?: ""))
        return ChatJson.decodeFromJsonElement(ListSerializer(ChannelMember.serializer()), ChatJson.parseToJsonElement(r).jsonObject["members"]!!)
    }

    // ---------- Beiträge ----------

    suspend fun post(id: String, parts: List<Part>) {
        val c = channels[id]!!
        if (!c.me.can_write) throw ChatException("Du darfst in diesem Kanal nicht schreiben.")
        val postId = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(12))
        val ts = System.currentTimeMillis()
        val payload = ChatJson.encodeToString(PostPayload.serializer(), PostPayload(1, parts)).toByteArray()
        val data = seal(c.key, id.toByteArray(), payload)
        val msg = "CHAT-POST-V1\n$id\n$postId\n$ts\n0\n${sha256Bytes(data).hex()}"
        val sig = host.client().signAccount(msg.toByteArray())
        val body = buildJsonObject {
            put("post_id", postId); put("ts", ts); put("epoch", 0); put("data", data.b64()); put("sig", sig.b64())
        }.toString()
        api(c).call("POST", "/posts", body)
        sync(id)
    }

    @Serializable
    private data class PostPayload(val v: Int, val parts: List<Part>)

    private fun ingest(c: ChannelState, e: JsonObject) {
        val seq = e["seq"]!!.jsonPrimitive.content.toLong()
        val ts = e["ts"]!!.jsonPrimitive.content.toLong()
        val address = e["address"]?.jsonPrimitive?.content ?: ""
        val ik = e["ik"]?.jsonPrimitive?.content ?: ""
        if (e["type"]!!.jsonPrimitive.content == "post") {
            val pid = e["post_id"]!!.jsonPrimitive.content
            if (c.posts.any { it.id == pid }) return
            val deleted = e["deleted"]?.jsonPrimitive?.content == "true"
            var parts: List<Part> = emptyList()
            var bad = false
            if (!deleted && e["data"] != null && e["sig"] != null) {
                val data = e["data"]!!.jsonPrimitive.content.unb64()
                val epoch = e["epoch"]?.jsonPrimitive?.content ?: "0"
                val msg = "CHAT-POST-V1\n${c.id}\n$pid\n$ts\n$epoch\n${sha256Bytes(data).hex()}"
                val okSig = uniffi.chat_core.ed25519Verify(ik.unb64(), msg.toByteArray(), e["sig"]!!.jsonPrimitive.content.unb64())
                val pt = open(c.key, c.id.toByteArray(), data)
                if (!okSig || pt == null) bad = true else {
                    try { parts = ChatJson.decodeFromString(PostPayload.serializer(), String(pt)).parts } catch (x: Exception) { bad = true }
                }
            }
            c.posts.add(ChPost(pid, seq, ts, address, ik, parts, deleted, bad))
            if (address != host.state().me.address) c.unread++
        } else {
            val kind = e["kind"]?.jsonPrimitive?.content ?: ""
            val meta = e["meta"]?.jsonPrimitive?.content ?: ""
            val metaObj = runCatching { ChatJson.parseToJsonElement(meta).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
            if (kind == "delete") c.posts.find { it.id == metaObj["post_id"]?.jsonPrimitive?.content }?.let { it.deleted = true; it.parts = emptyList() }
            if (kind != "created") c.events.add(ChEvent(seq, ts, kind, address, metaObj["target_address"]?.jsonPrimitive?.content ?: "", meta))
            while (c.events.size > 200) c.events.removeAt(0)
        }
    }

    suspend fun sync(id: String) {
        val c = channels[id] ?: return
        if (!syncing.add(id)) return
        try {
            for (i in 0 until 50) {
                val r = try {
                    ChatJson.parseToJsonElement(api(c).call("GET", "/log?after=${c.cursor}&limit=100")).jsonObject
                } catch (x: ApiException) {
                    if (x.status == 403) { c.me = c.me.copy(status = "banned", can_write = false); host.dirty() }
                    throw x
                }
                c.me = ChatJson.decodeFromJsonElement(ChannelMember.serializer(), r["me"]!!)
                c.policy = policyOf(r["policy"]!!.jsonObject)
                c.title = title(c.key, r["title_enc"]!!.jsonPrimitive.content)
                val entries = r["entries"]!!.jsonArray
                for (e in entries) { ingest(c, e.jsonObject); c.cursor = e.jsonObject["seq"]!!.jsonPrimitive.content.toLong() }
                while (c.posts.size > 1000) c.posts.removeAt(0)
                host.dirty()
                if (entries.size < 100) break
            }
        } finally { syncing.remove(id) }
    }

    fun markRead(id: String) {
        val c = channels[id] ?: return
        if (c.unread != 0) { c.unread = 0; host.dirty() }
    }

    // ---------- Verbindung ----------

    fun start() {
        for (id in channels.keys.toList()) {
            scope.launch(dispatcher) { runCatching { sync(id) } }
            watch(id)
        }
    }

    fun stop() {
        for (id in sockets.keys.toList()) unwatch(id)
        retry.values.forEach { it.cancel() }
        retry.clear()
    }

    /** Echtzeit-Verbindung zu einem Kanal; bei Abbruch Wiederaufbau mit Backoff. */
    fun watch(id: String, backoff: Long = 2000) {
        val c = channels[id] ?: return
        if (host.closed() || sockets.containsKey(id)) return
        val api = api(c)
        val authMsg = api.wsAuth()
        val ws = http.newWebSocket(Request.Builder().url(api.wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(authMsg) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch(dispatcher) { runCatching { sync(id) } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
            private fun lost(webSocket: WebSocket) {
                scope.launch(dispatcher) {
                    if (sockets[id] === webSocket) sockets.remove(id)
                    val ch = channels[id]
                    if (host.closed() || ch == null || ch.me.status == "banned") return@launch
                    retry[id] = scope.launch(dispatcher) { delay(backoff); watch(id, minOf(backoff * 2, 60_000)) }
                }
            }
        })
        sockets[id] = ws
    }

    private fun unwatch(id: String) {
        retry.remove(id)?.cancel()
        sockets.remove(id)?.cancel()
    }
}
