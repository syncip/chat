package chat.engine

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String) : ChatException(message)

interface Signer {
    val name: String
    /** Geräte-ID (Anfragen werden mit dem Geräteschlüssel signiert). */
    val deviceId: String
    fun sign(data: ByteArray): ByteArray
}

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    // WebSocket-Ping: erkennt tote Verbindungen (Mobilfunk-/NAT-Abbrüche, Doze) und löst das Neuverbinden samt Nachholen aus.
    .pingInterval(20, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private val JSON = "application/json".toMediaType()
private val OCTET = "application/octet-stream".toMediaType()

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { cancel() }
}

/** Signierter API-Client für den Home-Server (siehe docs/PROTOCOL.md §2 und §11). */
class Api(val domain: String, private val signer: Signer? = null, val http: OkHttpClient = defaultHttpClient()) {
    val base: String = baseUrl(domain)

    private fun authHeader(method: String, uri: String, bodyHash: String): String {
        val s = signer ?: throw ChatException("Nicht angemeldet")
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = randomBytes(12).b64()
        val msg = "CHAT-REQ-V1\n$domain\n$method\n$uri\n$ts\n$nonce\n$bodyHash"
        val sig = s.sign(msg.toByteArray()).b64()
        return "Chat-Sig name=${s.name},dev=${s.deviceId},ts=$ts,nonce=$nonce,sig=$sig"
    }

    /** WebSocket-Auth-Nachricht (Body-Hash "WS"). */
    fun wsAuth(): String {
        val h = authHeader("GET", "/v1/stream", "WS").removePrefix("Chat-Sig ")
        val m = h.split(',').associate { kv -> kv.substringBefore('=') to kv.substringAfter('=') }
        return kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in m) put(k, kotlinx.serialization.json.JsonPrimitive(v))
        }.toString()
    }

    fun wsUrl(): String = base.replaceFirst("http", "ws") + "/v1/stream"

    internal suspend fun exec(url: String, build: Request.Builder.() -> Unit): Response {
        val req = Request.Builder().url(url).apply(build).build()
        try {
            return http.newCall(req).await()
        } catch (e: IOException) {
            val origin = "${req.url.scheme}://${req.url.host}:${req.url.port}"
            throw ApiException(0, "Verbindung zu $origin fehlgeschlagen. Server nicht erreichbar, Firewall/Port blockiert oder Adresse falsch.")
        }
    }

    internal fun check(res: Response, ok: List<Int>): String {
        res.use {
            val text = it.body?.string() ?: ""
            if (it.code !in ok) {
                val msg = runCatching {
                    (ChatJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["error"]
                        ?.let { e -> (e as kotlinx.serialization.json.JsonPrimitive).content }
                }.getOrNull() ?: it.message
                throw ApiException(it.code, msg)
            }
            return text
        }
    }

    /** Signierter Aufruf mit optionalem JSON-Body; gibt den Antworttext zurück (leer bei 202/204). */
    suspend fun call(method: String, uri: String, json: String? = null, ok: List<Int> = listOf(200, 201, 202, 204)): String {
        val raw = json?.toByteArray() ?: ByteArray(0)
        val auth = authHeader(method, uri, sha256Bytes(raw).hex())
        val res = exec(base + uri) {
            header("Authorization", auth)
            val body: RequestBody? = if (json == null) (if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(JSON)) else raw.toRequestBody(JSON)
            method(method, body)
        }
        return check(res, ok)
    }

    /** Unsignierter/anders signierter JSON-Aufruf (z. B. Kanal-Endpunkte mit `Chan-Sig`). */
    suspend fun rawJson(method: String, uri: String, headers: Map<String, String>, json: String?, ok: List<Int> = listOf(200, 201)): String {
        val res = exec(base + uri) {
            headers.forEach { (k, v) -> header(k, v) }
            val body: RequestBody? = if (json == null) (if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(JSON)) else json.toByteArray().toRequestBody(JSON)
            method(method, body)
        }
        return check(res, ok)
    }

    suspend fun publicGet(path: String): String = check(exec(base + path) { get() }, listOf(200))

    suspend fun serverInfo(): ServerInfo = ChatJson.decodeFromString(ServerInfo.serializer(), publicGet("/v1/server-info"))

    suspend fun register(json: String): String {
        val res = exec("$base/v1/register") { post(json.toRequestBody(JSON)) }
        return check(res, listOf(201))
    }

    /** Neues Gerät eines bestehenden Kontos (vom Konto-Schlüssel beglaubigt, keine Anmeldung nötig). */
    suspend fun addDevice(json: String) {
        check(exec("$base/v1/devices") { post(json.toRequestBody(JSON)) }, listOf(201))
    }

    /** Verschlüsselte Datei hochladen (Body nicht signiert, siehe PROTOCOL §11). */
    suspend fun uploadBlob(data: ByteArray): String {
        val auth = authHeader("POST", "/v1/blobs", "UNSIGNED")
        val res = exec("$base/v1/blobs") {
            header("Authorization", auth)
            header("X-Body-Hash", "UNSIGNED")
            post(data.toRequestBody(OCTET))
        }
        val o = ChatJson.parseToJsonElement(check(res, listOf(201))) as kotlinx.serialization.json.JsonObject
        return (o["blob_id"] as kotlinx.serialization.json.JsonPrimitive).content
    }

    /** Anonymer Einwurf direkt beim Ziel-Server; gibt den HTTP-Status zurück. */
    suspend fun putDirect(domain: String, mailboxId: String, token: String, blob: ByteArray): Int {
        val u = "${baseUrl(domain)}/v1/mailboxes/${java.net.URLEncoder.encode(mailboxId, "UTF-8")}/messages"
        val res = exec(u) {
            header("X-Send-Token", token)
            put(blob.toRequestBody(OCTET))
        }
        return res.use { it.code }
    }

    fun openWebSocket(listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(wsUrl()).build(), listener)

    companion object {
        suspend fun downloadBlob(http: OkHttpClient, server: String, id: String): ByteArray {
            val url = "${baseUrl(server)}/v1/blobs/${java.net.URLEncoder.encode(id, "UTF-8")}"
            val res = try {
                http.newCall(Request.Builder().url(url).build()).await()
            } catch (e: IOException) {
                throw ApiException(0, "Download von $server fehlgeschlagen.")
            }
            res.use {
                if (!it.isSuccessful) throw ApiException(it.code, "Download fehlgeschlagen")
                return it.body!!.bytes()
            }
        }
    }
}
