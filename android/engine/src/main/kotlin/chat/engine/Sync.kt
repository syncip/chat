package chat.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import uniffi.chat_core.MlsClient

/**
 * Konto-Sync zwischen den Geräten eines Kontos (identisch zum Web-Client, siehe web/src/lib/sync.ts und docs/SYNC.md):
 * ein mit einem aus dem Konto-Schlüssel abgeleiteten Schlüssel verschlüsselter Blob beim Home-Server, Zusammenführung je Eintrag
 * („last writer wins“, Löschungen als Grabsteine).
 */
@Serializable
data class SyncBase(val h: String, val ts: Long, val del: Boolean)

@Serializable
data class SyncState(var version: Long = 0, val base: MutableMap<String, SyncBase> = mutableMapOf())

@Serializable
private data class SyncItem(val ts: Long, val del: Boolean = false, @SerialName("val") val value: JsonElement? = null)

@Serializable
private data class SyncDoc(val v: Int = 1, val items: Map<String, SyncItem> = emptyMap())

private const val KIND_SYNC: UByte = 4u
private val SYNC_GID = "sync".toByteArray()
private const val TOMBSTONE_MS = 30L * 24 * 3600 * 1000

internal class AccountSync(private val h: Host) {
    interface Host {
        fun state(): AppState
        fun client(): MlsClient
        suspend fun call(method: String, uri: String, json: String?, ok: List<Int> = listOf(200, 201, 202, 204)): String
        fun applyChannel(id: String, server: String?, key: String, title: String, createdAt: Long)
        fun dirty()
        fun now(): Long
    }

    /** Aktueller lokaler Zustand als Einträge (Reihenfolge der Schlüssel wie im Web-Client). */
    fun collect(s: AppState): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        for (c in s.channels.values) out["chan:${c.id}"] = buildJsonObject { put("server", c.server); put("key", c.key); put("title", c.title); put("createdAt", c.createdAt) }
        out["set:filterMode"] = JsonPrimitive(s.filterMode)
        out["set:serverSideFilter"] = JsonPrimitive(s.serverSideFilter)
        out["set:directSend"] = JsonPrimitive(s.directSend)
        out["set:sendDelivered"] = JsonPrimitive(s.sendDelivered)
        out["set:sendRead"] = JsonPrimitive(s.sendRead)
        out["set:onceDropOwnCopy"] = JsonPrimitive(s.onceDropOwnCopy)
        s.blockedUsers.forEach { out["blockU:$it"] = JsonPrimitive(true) }
        s.blockedServers.forEach { out["blockS:$it"] = JsonPrimitive(true) }
        s.allowUsers.forEach { out["allowU:$it"] = JsonPrimitive(true) }
        s.allowServers.forEach { out["allowS:$it"] = JsonPrimitive(true) }
        for (c in s.contacts.values) out["contact:${c.address}"] = buildJsonObject { put("ik", c.ik); put("verified", c.verified) }
        return out
    }

    fun signature(): String = collect(h.state()).entries.joinToString("|") { "${it.key}=${it.value}" }

    private fun seal(doc: SyncDoc): String =
        uniffi.chat_core.envelopeSeal(h.client().syncKey(), KIND_SYNC, SYNC_GID, ByteArray(0), ChatJson.encodeToString(SyncDoc.serializer(), doc).toByteArray()).b64()

    private fun open(data: String): SyncDoc? {
        if (data.isEmpty()) return null
        return try {
            val o = uniffi.chat_core.envelopeOpen(h.client().syncKey(), data.unb64())
            if (o.kind != KIND_SYNC) null else ChatJson.decodeFromString(SyncDoc.serializer(), String(o.payload))
        } catch (e: Exception) { null }
    }

    suspend fun run() {
        val s = h.state()
        val first = s.sync == null
        val st = s.sync ?: SyncState().also { s.sync = it }
        val base = st.base
        // Erster Abgleich dieses Geräts: eigene Vorgabewerte dürfen Einträge anderer Geräte nicht überschreiben (ts = 0).
        val now = if (first) 0L else h.now()
        val cur = collect(s)
        for ((k, v) in cur) {
            val hv = v.toString()
            val b = base[k]
            if (b == null || b.h != hv || b.del) base[k] = SyncBase(hv, now, false)
        }
        for (k in base.keys.toList()) if (k !in cur && base[k]!!.del.not()) base[k] = SyncBase("", now, true)

        for (attempt in 0 until 5) {
            val r = ChatJson.parseToJsonElement(h.call("GET", "/v1/sync", null)).jsonObject
            val version = r["version"]!!.jsonPrimitive.longOrNull ?: 0L
            val doc = open(r["data"]?.jsonPrimitive?.content ?: "") ?: SyncDoc()
            val merged = doc.items.toMutableMap()
            var push = false
            val applied = mutableListOf<String>()
            for (k in (doc.items.keys + base.keys)) {
                val rem = doc.items[k]
                val loc = base[k]
                if (loc != null && (rem == null || loc.ts > rem.ts)) {
                    merged[k] = if (loc.del) SyncItem(loc.ts, true) else SyncItem(loc.ts, false, cur[k])
                    push = true
                } else if (rem != null && (loc == null || loc.ts != rem.ts || loc.del != rem.del)) {
                    apply(k, rem)
                    base[k] = SyncBase("", rem.ts, rem.del)
                    applied.add(k)
                }
            }
            if (applied.isNotEmpty()) {
                val after = collect(h.state())
                for (k in applied) if (base[k]!!.del.not()) after[k]?.let { base[k] = SyncBase(it.toString(), base[k]!!.ts, false) }
            }
            merged.entries.removeAll { it.value.del && h.now() - it.value.ts > TOMBSTONE_MS }
            if (!push) { st.version = version; return }
            val body = buildJsonObject { put("base_version", version); put("data", seal(SyncDoc(1, merged))) }.toString()
            try {
                val out = ChatJson.parseToJsonElement(h.call("PUT", "/v1/sync", body)).jsonObject
                st.version = out["version"]!!.jsonPrimitive.longOrNull ?: version
                return
            } catch (e: ApiException) {
                if (e.status == 409) continue // jemand war schneller: neu abgleichen
                throw e
            }
        }
    }

    private fun apply(k: String, it: SyncItem) {
        val s = h.state()
        val i = k.indexOf(':')
        val kind = k.substring(0, i)
        val name = k.substring(i + 1)
        val on = !it.del
        fun list(arr: MutableList<String>) { val j = arr.indexOf(name); if (on && j < 0) arr.add(name); if (!on && j >= 0) arr.removeAt(j) }
        when (kind) {
            "chan" -> {
                if (on) {
                    val v = it.value!!.jsonObject
                    h.applyChannel(name, v["server"]!!.jsonPrimitive.content, v["key"]!!.jsonPrimitive.content, v["title"]!!.jsonPrimitive.content, v["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L)
                } else h.applyChannel(name, null, "", "", 0)
            }
            "set" -> if (on) {
                val p = it.value as? JsonPrimitive
                if (p != null && p !is JsonNull) when (name) {
                    "filterMode" -> s.filterMode = p.content
                    "serverSideFilter" -> s.serverSideFilter = p.content == "true"
                    "directSend" -> s.directSend = p.content == "true"
                    "sendDelivered" -> s.sendDelivered = p.content == "true"
                    "sendRead" -> s.sendRead = p.content == "true"
                    "onceDropOwnCopy" -> s.onceDropOwnCopy = p.content == "true"
                }
            }
            "blockU" -> list(s.blockedUsers)
            "blockS" -> list(s.blockedServers)
            "allowU" -> list(s.allowUsers)
            "allowS" -> list(s.allowServers)
            "contact" -> if (on) {
                val v = it.value!!.jsonObject
                val ik = v["ik"]!!.jsonPrimitive.content
                val verified = v["verified"]!!.jsonPrimitive.content == "true"
                val c = s.contacts[name]
                if (c != null) { c.ik = ik; c.verified = verified } else s.contacts[name] = Contact(name, ik, verified)
            } else s.contacts.remove(name)
        }
        h.dirty()
    }
}
