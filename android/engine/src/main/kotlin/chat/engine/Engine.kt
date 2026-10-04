package chat.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import uniffi.chat_core.MlsClient
import uniffi.chat_core.VaultSession
import java.util.Base64
import java.util.concurrent.Executors

private const val KIND_WELCOME: UByte = 1u
private const val KIND_MLS: UByte = 2u
private const val KP_BATCH = 40
private const val KP_LOW = 15

/** Zustand der Verbindung/App für die UI. */
enum class Phase { Loading, NoAccount, Locked, Unlocked }

/**
 * Engine: verbindet Krypto-Kern (Rust über UniFFI), API und lokalen verschlüsselten Zustand.
 * Alle Operationen laufen auf einem einzelnen Thread (wie im Web-Client), Netzwerk-Aufrufe suspendieren nur.
 */
class Engine(
    private val store: BlobStore,
    private val http: OkHttpClient = defaultHttpClient(),
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "chat-engine").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var client: MlsClient? = null
    private var vault: VaultSession? = null
    private var api: Api? = null
    private var state: AppState? = null
    var info: ServerInfo? = null
        private set
    private var knownAddress: String? = null

    private val _phase = MutableStateFlow(Phase.Loading)
    val phase: StateFlow<Phase> = _phase

    private val _version = MutableStateFlow(0)
    /** Zählt Zustandsänderungen; die UI holt dann `snapshot()`. */
    val version: StateFlow<Int> = _version

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online

    private val _newMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Konversations-ID bei jeder neuen eingehenden Nachricht (für Benachrichtigungen, ohne Inhalt). */
    val newMessages: SharedFlow<String> = _newMessages

    private val incoming = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var pump: Job? = null
    private var ws: WebSocket? = null
    private var wsJob: Job? = null
    private var tick: Job? = null
    private var closed = true
    private var backoff = 1000L
    private var saveJob: Job? = null
    private val relays = mutableListOf<Triple<String, CapEntry, String>>() // convId, entry, sender
    private class PendingReceipt(val convId: String, val kind: String, val ids: List<String>)
    private val receipts = mutableListOf<PendingReceipt>()

    private suspend fun <T> op(f: suspend () -> T): T = withContext(dispatcher) { f() }

    private val channels = ChannelManager(http, scope, dispatcher, object : ChannelManager.Host {
        override fun state() = state!!
        override fun client() = client!!
        override fun dirty() = this@Engine.dirty()
        override suspend fun home(method: String, uri: String, json: String?) = api!!.call(method, uri, json)
        override fun closed() = closed
        override fun incoming() { _newMessages.tryEmit("channel") }
    })

    private val accountSync = AccountSync(object : AccountSync.Host {
        override fun state() = state!!
        override fun client() = client!!
        override suspend fun call(method: String, uri: String, json: String?, ok: List<Int>) = api!!.call(method, uri, json, ok)
        override fun applyChannel(id: String, server: String?, key: String, title: String, createdAt: Long) {
            if (server != null) channels.adopt(id, server, key, title, createdAt) else channels.drop(id)
        }
        override fun dirty() = this@Engine.dirty()
        override fun now() = clock()
    })
    private var lastSyncSig = ""
    private var syncTimer: Job? = null

    /** Änderungen an synchronisierten Daten entprellt an die anderen Geräte weitergeben. */
    private fun scheduleSync() {
        if (syncTimer?.isActive == true || state == null || api == null || closed) return
        syncTimer = scope.launch(dispatcher) {
            delay(1500)
            if (state != null && accountSync.signature() != lastSyncSig) incoming.trySend { syncNow() }
        }
    }

    private suspend fun syncNow() {
        if (state == null || api == null) return
        // Stand VOR dem Abgleich merken: Änderungen, die währenddessen passieren, müssen beim nächsten Durchlauf noch auffallen.
        val before = accountSync.signature()
        try {
            // Wurde etwas von einem anderen Gerät übernommen, ist `before` veraltet: einen weiteren Lauf erzwingen (der dann nichts mehr ändert).
            lastSyncSig = if (accountSync.run()) "" else before
        } catch (e: Exception) { System.err.println("sync: $e") }
    }

    private fun emit() { _version.value = _version.value + 1 }

    fun close() {
        closed = true
        ws?.cancel()
        executor.shutdown()
    }

    // ---------- Lebenszyklus ----------

    suspend fun init() = op {
        knownAddress = store.read("meta")?.toString(Charsets.UTF_8)
        _phase.value = if (knownAddress != null) Phase.Locked else Phase.NoAccount
    }

    fun knownAddress(): String? = knownAddress

    /** Geräte-Registrierung: Zertifikat des Konto-Schlüssels, Geräte-Inbox (aus dem AIK abgeleitet), KeyPackages. */
    private class DeviceReg(
        val deviceId: String, val device: JsonObject, val inbox: JsonObject, val inboxId: String, val inboxKey: String,
        val keypackages: List<String>, val lastResort: String,
    )

    private fun deviceRegistration(c: MlsClient): DeviceReg {
        val deviceId = c.deviceId()
        val inbox = ChatJson.parseToJsonElement(c.deviceInbox(deviceId)).jsonObject
        val token = inbox["token"]!!.jsonPrimitive.content
        val mb = inbox["mailbox_id"]!!.jsonPrimitive.content
        return DeviceReg(
            deviceId = deviceId,
            device = buildJsonObject {
                put("id", deviceId); put("dpk", c.devicePublic().b64()); put("cert", c.deviceCert().b64())
            },
            inbox = buildJsonObject { put("mailbox_id", mb); put("token_hash", sha256Bytes(token.toByteArray()).b64()) },
            inboxId = mb,
            inboxKey = inbox["key"]!!.jsonPrimitive.content.unhex().b64(),
            keypackages = c.keyPackages(KP_BATCH.toUInt(), false).map { it.b64() },
            lastResort = c.keyPackages(1u, true)[0].b64(),
        )
    }

    /** Server vor der Registrierung prüfen (erreichbar? Registrierungsmodus?). Liefert die Server-Informationen. */
    suspend fun probeServer(server: String): ServerInfo = op {
        try { Api(normalizeServer(server), null, http).serverInfo() } catch (e: ChatException) { throw e } catch (e: Exception) {
            throw ChatException("Server nicht erreichbar oder kein Chat-Server (${e.message ?: e.javaClass.simpleName}).")
        }
    }

    suspend fun createAccount(server: String, name0: String, invite: String, passphrase: String) = op {
        val name = name0.trim().lowercase()
        val info = try { Api(normalizeServer(server), null, http).serverInfo() } catch (e: ChatException) { throw e } catch (e: Exception) {
            throw ChatException("Server nicht erreichbar oder kein Chat-Server (${e.message ?: e.javaClass.simpleName}).")
        }
        val domain = info.domain
        if (info.registration == "closed") throw ChatException("Dieser Server nimmt keine Registrierungen an.")
        val c = MlsClient.create("$name@$domain")
        val ts = clock() / 1000
        // Die Registrierung beweist den Besitz des Konto-Schlüssels (AIK).
        val sig = c.signAccount("CHAT-REGISTER-V1\n$domain\n$name\n$ts".toByteArray()).b64()
        val pow = solvePow(name, ts, info.pow_bits)
        val reg = deviceRegistration(c)
        val res = Api(domain, null, http).register(
            buildJsonObject {
                put("invite", invite.trim()); put("name", name); put("ik", c.identityPublic().b64())
                put("ts", ts); put("sig", sig); put("pow", pow)
                put("keypackages", JsonArray(reg.keypackages.map { JsonPrimitive(it) })); put("last_resort", reg.lastResort)
                put("device", reg.device); put("inbox", reg.inbox)
            }.toString(),
        )
        val intro = ChatJson.parseToJsonElement(res).jsonObject["intro"]!!.jsonObject
        val introKey = uniffi.chat_core.envelopeKey().b64()
        val mb = intro["mailbox_id"]!!.jsonPrimitive.content
        client = c
        this.info = info
        state = AppState(
            me = Me("$name@$domain", domain, name, reg.deviceId, reg.inboxId),
            intro = IntroBox(mb, intro["send_token"]!!.jsonPrimitive.content, introKey),
            mailboxes = mutableMapOf(mb to introKey, reg.inboxId to reg.inboxKey),
        )
        state!!.knownDevices = mutableListOf(reg.deviceId) // jedes später hinzukommende Gerät löst einen Hinweis aus
        vault = VaultSession.create(passphrase)
        persist()
        store.write("meta", "$name@$domain".toByteArray())
        knownAddress = "$name@$domain"
        start()
    }

    /**
     * Neues Gerät für ein bestehendes Konto: Backup-Datei (enthält den Konto-Schlüssel) → neuer Geräteschlüssel → Registrierung am Server.
     * Das Gerät startet ohne Gespräche; ein aktives Gerät des Kontos nimmt es in die Gruppen auf (siehe docs/MULTIDEVICE.md).
     */
    suspend fun linkDevice(file: ByteArray, backupPass: String, newPass: String) = op {
        val plain = try { uniffi.chat_core.vaultOpenPlain(backupPass, file) } catch (e: Exception) {
            throw ChatException("Falsche Passphrase oder beschädigte Backup-Datei.")
        }
        linkFromBackupJson(plain, newPass)
    }

    /**
     * Gerät per QR-Code aus dem Webinterface anmelden (`chatlink1:<base64url(json {s,i,k})>`): der verschlüsselte Backup-Inhalt wird einmalig
     * vom Home-Server abgeholt und mit dem Schlüssel aus dem QR-Code entschlüsselt (siehe web `createDeviceLink`).
     */
    suspend fun linkFromQr(link: String, newPass: String) = op {
        val l = link.trim()
        if (!l.startsWith("chatlink1:")) throw ChatException("Das ist kein Anmelde-QR-Code dieser App.")
        val j = try {
            ChatJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(l.removePrefix("chatlink1:")))).jsonObject
        } catch (e: Exception) { throw ChatException("QR-Code unlesbar.") }
        val server = j["s"]?.jsonPrimitive?.content ?: throw ChatException("QR-Code unlesbar.")
        val id = j["i"]?.jsonPrimitive?.content ?: throw ChatException("QR-Code unlesbar.")
        val key = j["k"]?.jsonPrimitive?.content?.unb64() ?: throw ChatException("QR-Code unlesbar.")
        val res = try {
            Api(server, null, http).publicGet("/v1/transfer/" + java.net.URLEncoder.encode(id, "UTF-8"))
        } catch (e: ApiException) {
            if (e.status == 404) throw ChatException("Der QR-Code ist abgelaufen oder wurde schon benutzt. Zeige im Webinterface einen neuen an.")
            throw e
        }
        val data = ChatJson.parseToJsonElement(res).jsonObject["data"]!!.jsonPrimitive.content.unb64()
        val o = try { uniffi.chat_core.envelopeOpen(key, data) } catch (e: Exception) { throw ChatException("QR-Code ungültig.") }
        if (o.kind != 4.toUByte()) throw ChatException("QR-Code ungültig.")
        linkFromBackupJson(o.payload, newPass)
    }

    private suspend fun linkFromBackupJson(plain: ByteArray, newPass: String) {
        val j = ChatJson.parseToJsonElement(String(plain)).jsonObject
        if (j["v"]?.jsonPrimitive?.content != "2") throw ChatException("Dieses Backup hat ein altes Format und kann nicht verwendet werden.")
        val address = j["address"]!!.jsonPrimitive.content
        val (name, domain) = splitAddress(address) ?: throw ChatException("Ungültiges Backup.")
        val c = MlsClient.linkDevice(address, j["aik"]!!.jsonPrimitive.content.unb64())
        val info = Api(domain, null, http).serverInfo()
        if (info.domain != domain) throw ChatException("Der Server meldet einen anderen Namen als im Backup.")
        val reg = deviceRegistration(c)
        val ts = clock() / 1000
        val sig = c.signAccount("CHAT-ADD-DEVICE-V1\n$domain\n$name\n$ts\n${reg.deviceId}".toByteArray()).b64()
        Api(domain, null, http).addDevice(
            buildJsonObject {
                put("name", name); put("ts", ts); put("sig", sig); put("device", reg.device); put("inbox", reg.inbox)
                put("keypackages", JsonArray(reg.keypackages.map { JsonPrimitive(it) })); put("last_resort", reg.lastResort)
            }.toString(),
        )
        val st = AppState(me = Me(address, domain, name, reg.deviceId, reg.inboxId), intro = null)
        val sync = j["sync"]?.jsonObject
        if (sync != null) {
            fun <T> opt(k: String, ser: kotlinx.serialization.KSerializer<T>): T? = sync[k]?.let { ChatJson.decodeFromJsonElement(ser, it) }
            opt("contacts", kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), Contact.serializer()))?.let { st.contacts.putAll(it) }
            opt("blockedUsers", kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))?.let { st.blockedUsers = it.toMutableList() }
            opt("blockedServers", kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))?.let { st.blockedServers = it.toMutableList() }
            opt("allowUsers", kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))?.let { st.allowUsers = it.toMutableList() }
            opt("allowServers", kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))?.let { st.allowServers = it.toMutableList() }
            sync["filterMode"]?.jsonPrimitive?.content?.let { st.filterMode = it }
            sync["serverSideFilter"]?.jsonPrimitive?.boolean?.let { st.serverSideFilter = it }
            sync["directSend"]?.jsonPrimitive?.boolean?.let { st.directSend = it }
            sync["sendDelivered"]?.jsonPrimitive?.boolean?.let { st.sendDelivered = it }
            sync["sendRead"]?.jsonPrimitive?.boolean?.let { st.sendRead = it }
            sync["onceDropOwnCopy"]?.jsonPrimitive?.boolean?.let { st.onceDropOwnCopy = it }
            opt("channels", kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), ChannelState.serializer()))?.let { st.channels.putAll(it) }
            sync["intro"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { st.intro = ChatJson.decodeFromJsonElement(IntroBox.serializer(), it) }
        }
        st.backupDone = true // das Backup existiert ja bereits
        st.mailboxes[reg.inboxId] = reg.inboxKey
        st.intro?.let { st.mailboxes[it.mailbox_id] = it.key }
        client = c
        this.info = info
        state = st
        vault = VaultSession.create(newPass)
        persist()
        store.write("meta", address.toByteArray())
        knownAddress = address
        start()
    }

    suspend fun unlock(passphrase: String) = op {
        val blob = store.read("vault") ?: throw ChatException("Kein Konto vorhanden.")
        openVault(passphrase, blob)
        start()
    }

    private fun openVault(passphrase: String, blob: ByteArray) {
        val opened = try {
            uniffi.chat_core.vaultOpen(passphrase, blob)
        } catch (e: Exception) {
            throw ChatException("Falsche Passphrase oder beschädigte Daten.")
        }
        val j = ChatJson.parseToJsonElement(String(opened.plaintext)).jsonObject
        vault = opened.vault
        client = MlsClient.importState(j["mls"]!!.jsonPrimitive.content.unb64())
        state = ChatJson.decodeFromJsonElement(AppState.serializer(), j["app"]!!)
    }

    /** Prüft die Passphrase gegen den gespeicherten Vault (z. B. vor dem Aktivieren der Biometrie). */
    suspend fun checkPassphrase(passphrase: String): Boolean = op {
        val blob = store.read("vault") ?: return@op false
        try { uniffi.chat_core.vaultOpenPlain(passphrase, blob); true } catch (e: Exception) { false }
    }

    /**
     * Backup-Datei (Format identisch zum Web-Client): Konto-Schlüssel (AIK), Einstellungen und Kontakte, **kein MLS-Zustand**
     * (deshalb kein Zustandsfork); Verlauf und Gruppen kommen auf neuen Geräten per Aufnahme durch ein aktives Gerät.
     */
    suspend fun exportBackup(passphrase: String): ByteArray = op { uniffi.chat_core.vaultSeal(passphrase, backupJson().toByteArray()) }

    /**
     * QR-Code zum Anmelden eines weiteren Geräts (gleiches Format wie im Webinterface): Backup-Inhalt, mit einem Einmalschlüssel verschlüsselt,
     * 5 Minuten einmalig beim Home-Server abrufbar; der Schlüssel steht nur im QR-Code. Liefert `chatlink1:…`.
     */
    suspend fun createDeviceLink(): String = op {
        val key = uniffi.chat_core.envelopeKey()
        val blob = uniffi.chat_core.envelopeSeal(key, 4u, "transfer".toByteArray(), ByteArray(0), backupJson().toByteArray())
        val id = ChatJson.parseToJsonElement(api!!.call("POST", "/v1/transfer", buildJsonObject { put("data", blob.b64()) }.toString())).jsonObject["id"]!!.jsonPrimitive.content
        val j = buildJsonObject { put("s", state!!.me.domain); put("i", id); put("k", key.b64()) }.toString()
        "chatlink1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(j.toByteArray())
    }

    private fun backupJson(): String {
        val s = state!!
        val sync = buildJsonObject {
            put("contacts", ChatJson.encodeToJsonElement(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), Contact.serializer()), s.contacts))
            put("blockedUsers", ChatJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), s.blockedUsers))
            put("blockedServers", ChatJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), s.blockedServers))
            put("allowUsers", ChatJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), s.allowUsers))
            put("allowServers", ChatJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), s.allowServers))
            put("filterMode", s.filterMode); put("serverSideFilter", s.serverSideFilter); put("directSend", s.directSend)
            put("sendDelivered", s.sendDelivered); put("sendRead", s.sendRead); put("onceDropOwnCopy", s.onceDropOwnCopy)
            s.intro?.let { put("intro", ChatJson.encodeToJsonElement(IntroBox.serializer(), it)) }
            put("channels", ChatJson.encodeToJsonElement(
                kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), ChannelState.serializer()),
                s.channels.mapValues { (_, c) -> c.copy(posts = mutableListOf(), events = mutableListOf(), cursor = 0, unread = 0) },
            ))
        }
        return buildJsonObject {
            put("v", 2); put("address", s.me.address); put("aik", client!!.exportIdentity().b64()); put("sync", sync)
        }.toString()
    }

    /** Nach dem Speichern der Backup-Datei aufrufen (hebt die Pflicht nach der Registrierung auf). */
    // ---------- Öffentliche Kanäle ----------

    suspend fun createChannel(title: String, policy: ChannelPolicy, isPublic: Boolean = false): String = op { channels.create(title, policy, isPublic) }
    suspend fun channelPublicLink(id: String, host: String): String = op { "$host/${channels.publicLink(id)}" }
    suspend fun channelHooks(id: String): List<ChHook> = op { channels.hooks(id) }
    suspend fun createChannelHook(id: String, name: String): String = op { channels.createHook(id, name) }
    suspend fun deleteChannelHook(id: String, hookId: String) = op { channels.deleteHook(id, hookId) }

    // ---------- Administration (nur für Administratoren) ----------

    /** true, wenn dieses Konto Administrator des Servers ist (der erste registrierte Nutzer). */
    suspend fun isAdmin(): Boolean = op {
        runCatching { ChatJson.parseToJsonElement(api!!.call("GET", "/v1/me")).jsonObject["admin"]!!.jsonPrimitive.boolean }.getOrDefault(false)
    }
    suspend fun adminStats(): JsonObject = op { ChatJson.parseToJsonElement(api!!.call("GET", "/v1/admin/stats")).jsonObject }
    suspend fun adminSettings(): JsonObject = op { ChatJson.parseToJsonElement(api!!.call("GET", "/v1/admin/settings")).jsonObject }
    suspend fun saveAdminSettings(s: JsonObject): JsonObject = op { ChatJson.parseToJsonElement(api!!.call("PUT", "/v1/admin/settings", s.toString())).jsonObject }
    suspend fun adminUsers(q: String = ""): JsonArray = op {
        val qs = if (q.isEmpty()) "" else "?q=" + java.net.URLEncoder.encode(q, "UTF-8")
        ChatJson.parseToJsonElement(api!!.call("GET", "/v1/admin/users$qs")).jsonObject["users"]!!.jsonArray
    }

    /** ban: "" | "perm" | "temp" (mit banMinutes); rateLimit: Anfragen/Minute (0 = Standard), rateMinutes 0 = unbefristet. */
    suspend fun restrictUser(name: String, ban: String, banMinutes: Long, reason: String, rateLimit: Int, rateMinutes: Long) = op {
        api!!.call("PUT", "/v1/admin/users/$name/restrict", buildJsonObject {
            put("ban", ban); put("ban_minutes", banMinutes); put("reason", reason); put("rate_limit", rateLimit); put("rate_minutes", rateMinutes)
        }.toString()); Unit
    }
    suspend fun setAdmin(name: String, admin: Boolean) = op { api!!.call("PUT", "/v1/admin/users/$name/admin", buildJsonObject { put("admin", admin) }.toString()); Unit }
    suspend fun previewChannel(link: String): ChannelPreview = op { channels.preview(link) }
    suspend fun joinChannel(link: String, captchaToken: String? = null, captchaAnswer: String? = null): String = op { channels.join(link, captchaToken, captchaAnswer) }
    suspend fun leaveChannel(id: String) = op { channels.leave(id) }
    suspend fun deleteChannel(id: String) = op { channels.remove(id) }
    suspend fun updateChannel(id: String, title: String?, policy: ChannelPolicy, avatar: String? = KEEP_AVATAR, makePublic: Boolean? = null) = op { channels.update(id, title, policy, avatar, makePublic) }
    suspend fun rekeyChannel(id: String, link: String) = op { channels.rekey(id, link) }
    suspend fun syncChannel(id: String) = op { channels.sync(id) }
    suspend fun postToChannel(id: String, parts: List<Part>) = op { channels.post(id, parts) }
    suspend fun channelMod(id: String, action: String, target: String? = null, postId: String? = null, role: String? = null, seconds: Long? = null) =
        op { channels.mod(id, action, target, postId, role, seconds) }
    suspend fun channelMembers(id: String, status: String? = null): List<ChannelMember> = op { channels.members(id, status) }
    suspend fun channelLink(id: String, host: String): String = op { "$host/${channels.link(id)}" }
    suspend fun markChannelRead(id: String) = op { channels.markRead(id) }

    suspend fun markBackupDone() = op { state!!.backupDone = true; dirty() }

    private fun serialize(): ByteArray = buildJsonObject {
        put("mls", client!!.exportState().b64())
        put("app", ChatJson.encodeToJsonElement(AppState.serializer(), state!!))
    }.toString().toByteArray()

    private suspend fun persist() {
        val v = vault ?: return
        if (client == null || state == null) return
        store.write("vault", v.seal(serialize()))
    }

    private fun dirty() {
        emit()
        scheduleSync()
        if (saveJob?.isActive == true) return
        saveJob = scope.launch(dispatcher) { delay(250); persist() }
    }

    private suspend fun flush() {
        saveJob?.cancel()
        persist()
    }

    suspend fun lock() = op {
        flush()
        closed = true
        wsJob?.cancel(); tick?.cancel(); pump?.cancel()
        channels.stop()
        ws?.cancel(); ws = null
        client?.destroy(); client = null
        vault?.destroy(); vault = null
        state = null; api = null
        _online.value = false
        _phase.value = if (knownAddress != null) Phase.Locked else Phase.NoAccount
        emit()
    }

    suspend fun deleteAccount() {
        lock()
        op { store.delete("vault"); store.delete("meta"); knownAddress = null; _phase.value = Phase.NoAccount }
    }

    /** Unveränderliche Kopie des Zustands für die UI. */
    suspend fun snapshot(): AppState? = op {
        state?.let { ChatJson.decodeFromString(AppState.serializer(), ChatJson.encodeToString(AppState.serializer(), it)) }
    }

    // ---------- Start / Netzwerk ----------

    private suspend fun start() {
        val s = state!!
        closed = false
        val c = client!!
        api = Api(s.me.domain, object : Signer {
            override val name = s.me.name
            override val deviceId = s.me.deviceId
            override fun sign(data: ByteArray) = c.sign(data)
        }, http)
        _phase.value = Phase.Unlocked
        emit()
        pump?.cancel()
        pump = scope.launch(dispatcher) { for (job in incoming) runCatching { job() }.onFailure { System.err.println("queue: $it") } }
        // Server-Infos (Limits) im Hintergrund holen: ein langsamer oder nicht erreichbarer Server darf das Entsperren nicht aufhalten.
        val a0 = api
        scope.launch(dispatcher) { runCatching { a0?.serverInfo() }.getOrNull()?.let { info = it; emit() } }
        connect()
        channels.start()
        tick = scope.launch(dispatcher) {
            var n = 0
            while (true) { delay(30_000); purgeExpired(); retryOutbox(); if (++n % 2 == 0) incoming.trySend { syncNow() } } // Sicherheitsnetz für verpasste Sync-Ereignisse
        }
    }

    private fun connect() {
        if (closed) return
        val a = api ?: return
        wsJob?.cancel()
        wsJob = scope.launch(dispatcher) {
            val authMsg = a.wsAuth()
            ws = a.openWebSocket(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(authMsg) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = runCatching { ChatJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    when (m["type"]?.jsonPrimitive?.content) {
                        "ready" -> {
                            backoff = 1000
                            _online.value = true
                            incoming.trySend { catchUp() }
                            incoming.trySend { reconcileDevices() }
                            incoming.trySend { syncNow() }
                            incoming.trySend { retryOutbox() }
                            incoming.trySend { replenishKeyPackages() }
                        }
                        "devices" -> incoming.trySend { reconcileDevices() }
                        "sync" -> incoming.trySend { syncNow() }
                        "message" -> {
                            val seq = m["seq"]!!.jsonPrimitive.content.toLong()
                            val mb = m["mailbox_id"]!!.jsonPrimitive.content
                            val data = m["data"]!!.jsonPrimitive.content.unb64()
                            incoming.trySend { handleRaw(seq, mb, data) }
                        }
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost()
                private fun lost() {
                    _online.value = false
                    if (closed) return
                    backoff = minOf(backoff * 2, 30_000)
                    wsJob = scope.launch(dispatcher) { delay(backoff); connect() }
                }
            })
        }
    }

    private suspend fun catchUp() {
        val a = api ?: return
        val s = state ?: return
        while (true) {
            val arr = ChatJson.parseToJsonElement(a.call("GET", "/v1/messages?after=${s.cursor}&limit=100")).jsonArray
            if (arr.isEmpty()) return
            // Ein Speichern/Neuzeichnen und ein Löschen beim Server je Stapel statt je Nachricht (nach dem Entsperren oft viele Nachrichten).
            var last = -1L
            for (e in arr) {
                val o = e.jsonObject
                val seq = o["seq"]!!.jsonPrimitive.content.toLong()
                if (handleRaw(seq, o["mailbox_id"]!!.jsonPrimitive.content, o["data"]!!.jsonPrimitive.content.unb64(), batch = true)) last = seq
            }
            if (last >= 0) {
                flush()
                emit()
                runCatching { api?.call("DELETE", "/v1/messages?upto=$last") }
            }
        }
    }

    /** Verarbeitet eine Nachricht; mit [batch] bleibt Speichern/Neuzeichnen/Löschen dem Aufrufer überlassen. Liefert true, wenn sie neu war. */
    private suspend fun handleRaw(seq: Long, mailboxId: String, data: ByteArray, batch: Boolean = false): Boolean {
        val s = state ?: return false
        if (seq <= s.cursor) return false
        try { handleIncoming(mailboxId, data) } catch (e: Exception) { System.err.println("message dropped: $e") }
        s.cursor = seq
        if (batch) return true
        flush()
        emit()
        // Erst nach dem Speichern beim Server löschen.
        runCatching { api?.call("DELETE", "/v1/messages?upto=$seq") }
        return true
    }

    private suspend fun replenishKeyPackages() {
        val a = api ?: return
        val c = client ?: return
        runCatching {
            val count = ChatJson.parseToJsonElement(a.call("GET", "/v1/keypackages/count")).jsonObject["count"]!!.jsonPrimitive.content.toInt()
            if (count >= KP_LOW) return
            val kps = c.keyPackages(KP_BATCH.toUInt(), false).map { it.b64() }
            val last = c.keyPackages(1u, true)[0].b64()
            a.call("PUT", "/v1/keypackages", buildJsonObject {
                put("keypackages", JsonArray(kps.map { JsonPrimitive(it) })); put("last_resort", last)
            }.toString())
            flush()
        }
    }

    // ---------- Filter ----------

    private fun isBlocked(address: String): Boolean {
        val s = state!!
        val (_, domain) = splitAddress(address) ?: return true
        if (address in s.blockedUsers || domain in s.blockedServers) return true
        if (s.filterMode == "allow") {
            return !(address in s.allowUsers || domain in s.allowServers || s.contacts[address]?.verified == true)
        }
        return false
    }

    // ---------- Eingehend ----------

    private suspend fun handleIncoming(mailboxId: String, data: ByteArray) {
        val s = state!!
        val key = s.mailboxes[mailboxId] ?: return
        val o = try { uniffi.chat_core.envelopeOpen(key.unb64(), data) } catch (e: Exception) { return }
        if (String(o.senderDevice) == s.me.deviceId) return // eigene Kopie (Zustellung an alle Geräte des Kontos)
        when (o.kind) {
            KIND_WELCOME -> handleWelcome(o.payload, mailboxId == s.me.inboxId)
            KIND_MLS -> handleMls(o.groupId.hex(), o.groupId, o.payload)
        }
    }

    /** Ein Eintrag je Gerät (MLS-Blatt). */
    private fun readMembers(gid: ByteArray): List<Member> =
        ChatJson.parseToJsonElement(client!!.members(gid)).jsonArray.map {
            val o = it.jsonObject
            Member(o["address"]!!.jsonPrimitive.content, o["identity"]!!.jsonPrimitive.content, o["device"]!!.jsonPrimitive.content)
        }

    private fun memberAddresses(conv: Conversation): List<String> = conv.members.map { it.address }.distinct()

    private fun pinMembers(conv: Conversation) {
        val s = state!!
        for (m in conv.members) {
            if (m.address == s.me.address) continue
            val c = s.contacts[m.address]
            if (c == null) s.contacts[m.address] = Contact(m.address, m.ik)
            else if (c.ik != m.ik) {
                conv.warning = "Der Schlüssel von ${m.address} hat sich geändert. Bitte neu verifizieren."
                addAlert("key-${m.address}-${m.ik.take(8)}", "key", conv.warning!!)
                c.verified = false
            }
        }
    }

    /**
     * `viaInbox`: das Welcome kam in der Geräte-Inbox an, also von einem anderen Gerät des eigenen Kontos
     * (Gerät wurde hinzugefügt): kein Anfrage-Dialog, sofort aktiv.
     */
    private suspend fun handleWelcome(welcome: ByteArray, viaInbox: Boolean) {
        val s = state!!
        val gid = client!!.join(welcome)
        val id = gid.hex()
        if (s.conversations.containsKey(id)) return
        val members = readMembers(gid)
        val addrs = members.map { it.address }.distinct()
        val others = addrs.filter { it != s.me.address }
        // Blockierte oder nicht erlaubte Absender: stillschweigend verwerfen (der Absender erfährt nichts).
        val blocked = !viaInbox && (others.any { it in s.blockedUsers || (splitAddress(it)?.second ?: "") in s.blockedServers } ||
            (s.filterMode == "allow" && others.none { !isBlocked(it) }))
        if (blocked || others.isEmpty()) { client!!.deleteGroup(gid); return }
        val kind = if (addrs.size == 2) "dm" else "group"
        val conv = Conversation(
            id = id, kind = kind, title = if (kind == "dm") others[0] else "Gruppe",
            status = if (viaInbox) "active" else "request", members = members, unread = if (viaInbox) 0 else 1, createdAt = clock(),
        )
        s.conversations[id] = conv
        pinMembers(conv)
        if (viaInbox) { conv.pendingAnnounce = true; return } // erst ankündigen, wenn die Verzeichnisse da sind
        if (kind == "dm" && s.contacts[others[0]]?.verified == true) acceptRequestLocked(id)
    }

    private suspend fun handleMls(id: String, gid: ByteArray, payload: ByteArray) {
        val s = state!!
        val conv = s.conversations[id] ?: return
        if (conv.status == "left") return
        val res = ChatJson.parseToJsonElement(client!!.process(gid, payload)).jsonObject
        when (res["kind"]?.jsonPrimitive?.content) {
            "commit" -> {
                if (res["removedSelf"]?.jsonPrimitive?.boolean == true) {
                    conv.status = "left"
                    client!!.deleteGroup(gid)
                } else {
                    conv.members = readMembers(gid)
                    pinMembers(conv)
                    rebuildCaps(conv)
                }
            }
            "application" -> {
                val sender = res["sender"]!!.jsonPrimitive.content
                val senderDevice = res["senderDevice"]?.jsonPrimitive?.content ?: ""
                // Zustand ist fortgeschrieben; blockierte Absender werden erst jetzt verworfen (eigene Geräte nie).
                if (sender != s.me.address && isBlocked(sender)) return
                val env = ChatJson.decodeFromJsonElement(Envelope.serializer(), res["envelope"]!!)
                applyContent(conv, sender, senderDevice, env)
                flushRelays()
                flushReceipts()
                if (conv.pendingAnnounce == true && conv.status == "active" && conv.caps.isNotEmpty()) {
                    conv.pendingAnnounce = null
                    ensureMailbox(conv)
                    announce(conv)
                }
            }
        }
    }

    /** Postfächer von Geräten entfernen, die nicht mehr Mitglied sind. */
    private fun rebuildCaps(conv: Conversation) {
        val leaves = conv.members.map { "${it.address}#${it.device}" }.toSet()
        val addrs = conv.members.map { it.address }.toSet()
        conv.caps.keys.retainAll { k -> if ('#' in k) k in leaves else k in addrs }
    }

    private fun applyContent(conv: Conversation, sender: String, senderDevice: String, env: Envelope) {
        if (conv.members.none { it.address == sender }) return
        when (val c = env.content) {
            is Content.Message -> {
                if (conv.messages.any { it.id == env.id }) return
                val once = c.once == true && conv.kind == "dm" // Einmal-Nachrichten gibt es nur in 1:1-Chats
                val own = sender == state!!.me.address // von einem anderen eigenen Gerät
                val msg = Msg(
                    id = env.id, from = sender, ts = minOf(env.ts, clock() + 5 * 60_000), parts = c.parts, status = if (own) "sent" else "received",
                    expiresAt = if (conv.disappearSeconds > 0) clock() + conv.disappearSeconds * 1000 else null,
                    once = if (once) true else null,
                )
                conv.messages.add(msg)
                conv.messages.sortBy { it.ts }
                if (!own) {
                    conv.unread++
                    _newMessages.tryEmit(conv.id)
                    if (conv.kind == "dm" && state!!.sendDelivered) receipts.add(PendingReceipt(conv.id, "delivered", listOf(env.id)))
                }
            }
            is Content.Receipt -> {
                if (conv.kind != "dm" || sender == state!!.me.address) return
                val rank = mapOf("sending" to 0, "failed" to 0, "received" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
                for (ref in c.references.take(200)) {
                    val m = conv.messages.find { it.id == ref && it.from == state!!.me.address } ?: continue
                    if ((rank[c.receipt] ?: 0) > (rank[m.status] ?: 0)) m.status = c.receipt
                }
            }
            is Content.Reaction -> {
                val m = conv.messages.find { it.id == c.reference } ?: return
                if (c.emoji.length > 16) return
                val list = m.reactions.getOrPut(c.emoji) { mutableListOf() }
                if (!list.remove(sender)) list.add(sender)
                if (list.isEmpty()) m.reactions.remove(c.emoji)
            }
            is Content.Edit -> {
                val m = conv.messages.find { it.id == c.reference }
                if (m != null && m.from == sender && m.deleted != true) { m.parts = c.parts; m.edited = true }
            }
            is Content.Delete -> {
                val m = conv.messages.find { it.id == c.reference }
                if (m != null && m.from == sender) { m.deleted = true; m.parts = emptyList() }
            }
            is Content.Disappear -> conv.disappearSeconds = c.seconds.coerceIn(0, 365L * 86400)
            is Content.GroupName -> if (conv.kind == "group") conv.title = c.name.take(80)
            is Content.GroupAvatar -> if (conv.kind == "group") conv.avatar = validAvatar(c.avatar)
            is Content.Profile -> { // nur das eigene Profilbild des (MLS-authentifizierten) Absenders
                val av = validAvatar(c.avatar)
                if (av != null) state!!.avatars[sender] = av else state!!.avatars.remove(sender)
            }
            is Content.Directory -> for (e in c.entries) {
                val key = "${e.address}#${e.device}"
                val before = conv.caps[key]?.mailbox_id
                mergeCap(conv, sender, senderDevice, e)
                val after = conv.caps[key]?.mailbox_id
                // Neue Selbst-Ankündigung eines Geräts: an die übrigen Mitglieder weiterreichen.
                if (e.address == sender && e.device == senderDevice && after != null && after != before && conv.status == "active") {
                    relays.add(Triple(conv.id, e, sender))
                }
            }
        }
    }

    /** Ein Gerät darf nur sein eigenes Postfach überschreiben; für andere Geräte gilt „first write wins“. */
    private fun mergeCap(conv: Conversation, sender: String, senderDevice: String, e: CapEntry) {
        val s = state!!
        if (splitAddress(e.address) == null || e.device.isEmpty() || e.domain.isEmpty() || e.mailbox_id.isEmpty() || e.send_token.isEmpty() || e.key.isEmpty()) return
        if (e.address == s.me.address && e.device == s.me.deviceId) return // wir selbst
        val key = "${e.address}#${e.device}"
        val isSelf = e.address == sender && e.device == senderDevice
        if (!isSelf && conv.caps.containsKey(key)) return
        if (!isSelf && conv.members.none { it.address == e.address && it.device == e.device }) return
        conv.caps[key] = Cap(e.domain, e.mailbox_id, e.send_token, e.key, device = e.device)
    }

    /** Alle bekannten Geräte-Postfächer der Unterhaltung (ohne Intro-Platzhalter und ohne `exceptKey`). */
    private fun knownEntries(conv: Conversation, exceptKey: String? = null): List<CapEntry> =
        conv.caps.filter { (k, c) -> k != exceptKey && c.intro != true && c.device != null }
            .map { (k, c) -> CapEntry(k.substringBefore('#'), c.device!!, c.domain, c.mailbox_id, c.send_token, c.key) }

    private suspend fun flushReceipts() {
        val s = state!!
        val list = receipts.toList()
        receipts.clear()
        for (r in list) {
            val conv = s.conversations[r.convId] ?: continue
            if (conv.status != "active" || conv.kind != "dm") continue
            broadcast(conv, newEnvelope(Content.Receipt(r.kind, r.ids)))
        }
    }

    private suspend fun flushRelays() {
        val s = state!!
        val list = relays.toList()
        relays.clear()
        for ((convId, entry, sender) in list) {
            val conv = s.conversations[convId] ?: continue
            if (conv.status != "active") continue
            val senderKey = "$sender#${entry.device}"
            val others = conv.members
                .filter { !(it.address == s.me.address && it.device == s.me.deviceId) && "${it.address}#${it.device}" != senderKey }
                .map { "${it.address}#${it.device}" }
            if (others.isNotEmpty()) sendContent(conv, Content.Directory(listOf(entry)), others)
            sendContent(conv, Content.Directory(knownEntries(conv, senderKey)), listOf(senderKey))
        }
    }

    // ---------- Ausgehend ----------

    private suspend fun deliver(cap: Cap, blob: ByteArray): Boolean {
        val s = state!!
        try {
            if (s.directSend) {
                val st = api!!.putDirect(cap.domain, cap.mailbox_id, cap.send_token, blob)
                if (st == 404) return true // Postfach widerrufen
                if (st in 200..299) return true
                throw ApiException(st, "status $st")
            }
            api!!.call("POST", "/v1/relay", buildJsonObject {
                put("domain", cap.domain); put("mailbox_id", cap.mailbox_id); put("send_token", cap.send_token); put("data", blob.b64())
            }.toString())
            return true
        } catch (e: ApiException) {
            if (e.status in listOf(404, 403, 413)) return true // dauerhaft abgelehnt
            s.outbox.add(OutboxItem(randomId(), cap, blob.b64()))
            return false
        }
    }

    private suspend fun retryOutbox() {
        val s = state ?: return
        if (api == null || s.outbox.isEmpty()) return
        val items = s.outbox.toList()
        s.outbox.clear()
        for (it in items) {
            val before = s.outbox.size
            if (!deliver(it.cap, it.blob.unb64())) {
                s.outbox.getOrNull(before)?.tries = it.tries + 1
                if (it.tries + 1 >= 200) s.outbox.removeAt(s.outbox.size - 1)
            }
        }
        dirty()
    }

    private fun newEnvelope(content: Content) = Envelope(1, randomId(), clock(), content)

    private fun encryptEnvelope(gid: ByteArray, env: Envelope): ByteArray =
        client!!.encryptEnvelope(gid, ChatJson.encodeToString(Envelope.serializer(), env))

    private suspend fun broadcast(conv: Conversation, env: Envelope): Int {
        val gid = conv.id.unhex()
        return sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, env), null)
    }

    private suspend fun sendContent(conv: Conversation, content: Content, only: List<String>) {
        val gid = conv.id.unhex()
        sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, newEnvelope(content)), only)
    }

    /**
     * Postfächer für den Versand: je Mitglieds-Gerät das angekündigte Postfach; kennen wir von einem Konto noch kein Geräte-Postfach,
     * dient das Intro-Postfach (kontoweit) als Ersatz. `only`: Einträge `adresse` (alle Geräte) oder `adresse#gerät`.
     */
    private fun capsForSend(conv: Conversation, only: List<String>?): List<Cap> {
        val s = state!!
        val out = mutableListOf<Cap>()
        for (addr in memberAddresses(conv)) {
            val leaves = conv.members.filter { it.address == addr && !(addr == s.me.address && it.device == s.me.deviceId) }
            if (leaves.isEmpty()) continue
            val caps = mutableListOf<Cap>()
            for (l in leaves) {
                val key = "$addr#${l.device}"
                if (only != null && addr !in only && key !in only) continue
                conv.caps[key]?.let { caps.add(it) }
            }
            if (caps.isEmpty() && addr != s.me.address && (only == null || addr in only)) conv.caps[addr]?.let { caps.add(it) }
            out.addAll(caps)
        }
        return out
    }

    private suspend fun sendCt(conv: Conversation, gid: ByteArray, kind: UByte, ct: ByteArray, only: List<String>?): Int {
        val dev = state!!.me.deviceId.toByteArray()
        var sent = 0
        for (cap in capsForSend(conv, only)) {
            deliver(cap, uniffi.chat_core.envelopeSeal(cap.key.unb64(), kind, gid, dev, ct))
            sent++
        }
        return sent
    }

    /** `account`: kontoweit (Zustellung an alle Geräte), sonst nur für dieses Gerät. */
    private suspend fun newMailbox(account: Boolean = false): MyMailbox {
        val r = ChatJson.parseToJsonElement(api!!.call("POST", "/v1/mailboxes", if (account) "{\"scope\":\"account\"}" else null)).jsonObject
        val key = uniffi.chat_core.envelopeKey().b64()
        val id = r["mailbox_id"]!!.jsonPrimitive.content
        state!!.mailboxes[id] = key
        return MyMailbox(id, key, r["send_token"]!!.jsonPrimitive.content)
    }

    private suspend fun ensureMailbox(conv: Conversation) {
        if (conv.myMailbox == null) conv.myMailbox = newMailbox()
    }

    private suspend fun announce(conv: Conversation, extra: List<CapEntry> = emptyList(), only: List<String>? = null) {
        val s = state!!
        ensureMailbox(conv)
        val mb = conv.myMailbox!!
        val me = CapEntry(s.me.address, s.me.deviceId, s.me.domain, mb.id, mb.token, mb.key)
        val gid = conv.id.unhex()
        sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, newEnvelope(Content.Directory(listOf(me) + extra))), only)
        // Bilder gleich mitgeben (Profilbild, Gruppenbild), damit neue Kontakte sie sofort sehen.
        s.me.avatar?.let { sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, newEnvelope(Content.Profile(it))), only) }
        if (conv.kind == "group") conv.avatar?.let { sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, newEnvelope(Content.GroupAvatar(it))), only) }
    }

    /** Eigenes Profilbild setzen (data-URL, null = entfernen): wird allen aktiven Chats mitgeteilt und mit den eigenen Geräten synchronisiert. */
    suspend fun setMyAvatar(avatar: String?) = op {
        val s = state!!
        val av = if (avatar == null) null else validAvatar(avatar) ?: throw ChatException("Bild ungültig oder zu groß.")
        s.me.avatar = av
        dirty()
        for (conv in s.conversations.values.toList()) if (conv.status == "active") runCatching { broadcast(conv, newEnvelope(Content.Profile(av))) }
    }

    /** Gruppenbild setzen (alle Mitglieder erhalten es). */
    suspend fun setGroupAvatar(id: String, avatar: String?) = op {
        val conv = state!!.conversations[id]
        if (conv == null || conv.kind != "group" || conv.status != "active") throw ChatException("Nur aktive Gruppen haben ein Bild.")
        val av = if (avatar == null) null else validAvatar(avatar) ?: throw ChatException("Bild ungültig oder zu groß.")
        conv.avatar = av
        broadcast(conv, newEnvelope(Content.GroupAvatar(av)))
        dirty()
    }

    /** Bild zu einem Chat: Gruppenbild bzw. Profilbild des Gegenübers (1:1). */
    fun avatarOfConv(s: AppState, conv: Conversation): String? {
        if (conv.kind == "group") return conv.avatar
        val other = conv.members.firstOrNull { it.address != s.me.address }?.address ?: return null
        return s.avatars[other]
    }

    // ---------- Öffentliche Aktionen ----------

    fun myCard(): ContactCard? {
        val s = state ?: return null
        val i = s.intro ?: return null
        return ContactCard(s.me.address, Cap(s.me.domain, i.mailbox_id, i.send_token, i.key))
    }

    fun contactLink(host: String): String = myCard()?.let { "$host/#/add/${encodeCard(it)}" } ?: ""

    // ---------- Chat-Code (frei wählbarer Kurzcode für die eigene Kontaktkarte) ----------

    suspend fun myChatCode(): String = op {
        ChatJson.parseToJsonElement(api!!.call("GET", "/v1/code")).jsonObject["code"]?.jsonPrimitive?.content ?: ""
    }

    suspend fun setChatCode(code: String): String = op {
        val card = myCard() ?: throw ChatException("Kontaktaufnahme ist deaktiviert (Einstellungen → Kontaktlink).")
        val body = buildJsonObject { put("code", code.trim()); put("card", encodeCard(card)) }.toString()
        ChatJson.parseToJsonElement(api!!.call("PUT", "/v1/code", body)).jsonObject["code"]!!.jsonPrimitive.content
    }

    suspend fun removeChatCode() = op { api!!.call("DELETE", "/v1/code"); Unit }

    /** Löst einen Chat-Code (`code` oder `code@server`) in eine Kontaktkarte auf. */
    suspend fun resolveChatCode(input: String): ContactCard = op {
        val t = input.trim().lowercase()
        val code = t.substringBefore('@')
        val dom = if (t.contains('@')) t.substringAfter('@') else ""
        if (!Regex("^[a-z0-9][a-z0-9_-]{2,39}$").matches(code)) throw ChatException("Ungültiger Code.")
        val res = try {
            api!!.call("GET", "/v1/codes/$code" + if (dom.isNotEmpty()) "?domain=" + java.net.URLEncoder.encode(dom, "UTF-8") else "")
        } catch (e: ApiException) {
            if (e.status == 404) throw ChatException("Diesen Code gibt es nicht.")
            throw e
        }
        decodeCard(ChatJson.parseToJsonElement(res).jsonObject["card"]!!.jsonPrimitive.content)
    }

    suspend fun setIntroEnabled(on: Boolean) = op {
        val s = state!!
        if (!on && s.intro != null) {
            runCatching { api!!.call("DELETE", "/v1/code") } // ohne Intro-Postfach ist ein Code wirkungslos
            api!!.call("DELETE", "/v1/mailboxes/${s.intro!!.mailbox_id}")
            s.mailboxes.remove(s.intro!!.mailbox_id)
            s.intro = null
        } else if (on && s.intro == null) {
            val mb = newMailbox(account = true)
            s.intro = IntroBox(mb.id, mb.token, mb.key)
        }
        dirty()
    }

    suspend fun startChat(card: ContactCard): String = op {
        val s = state!!
        val addr = card.address
        if (addr == s.me.address) throw ChatException("Das bist du selbst.")
        if (addr in s.blockedUsers) throw ChatException("Dieser Nutzer ist blockiert.")
        s.conversations.values.firstOrNull { it.kind == "dm" && it.status != "left" && it.members.any { m -> m.address == addr } }?.let { return@op it.id }
        val (kps, ik) = fetchKeyPackages(addr)
        val contact = s.contacts.getOrPut(addr) { Contact(addr, ik) }
        if (contact.ik != ik) throw ChatException("Der Schlüssel dieses Kontos hat sich geändert. Bitte neu verifizieren.")
        contact.intro = card.cap
        val gid = client!!.createGroup()
        // Alle Geräte des Kontakts werden aufgenommen; das Welcome geht an das kontoweite Intro-Postfach (alle Geräte erhalten es).
        val add = client!!.addMembers(gid, kps.map { it.second })
        val id = gid.hex()
        val conv = Conversation(
            id = id, kind = "dm", title = addr, status = "active", members = readMembers(gid),
            caps = mutableMapOf(addr to card.cap), createdAt = clock(),
        )
        s.conversations[id] = conv
        ensureMailbox(conv)
        flush()
        deliver(card.cap, uniffi.chat_core.envelopeSeal(card.cap.key.unb64(), KIND_WELCOME, ByteArray(0), s.me.deviceId.toByteArray(), add.welcome))
        announce(conv)
        dirty()
        id
    }

    /** KeyPackages aller (oder der genannten) Geräte eines Kontos; prüft Adresse, Konto-Schlüssel und Gerätezertifikat. */
    private suspend fun fetchKeyPackages(addr: String, devices: List<String> = emptyList()): Pair<List<Pair<String, ByteArray>>, String> {
        val enc = java.net.URLEncoder.encode(addr, "UTF-8")
        val q = if (devices.isEmpty()) "" else "?" + devices.joinToString("&") { "device=$it" }
        val r = ChatJson.parseToJsonElement(api!!.call("GET", "/v1/resolve/$enc/keypackages$q")).jsonObject
        val ik = r["ik"]!!.jsonPrimitive.content.unb64().hex()
        val kps = r["devices"]!!.jsonArray.map { d ->
            val o = d.jsonObject
            val kp = o["keypackage"]!!.jsonPrimitive.content.unb64()
            val id = try { ChatJson.parseToJsonElement(client!!.keyPackageIdentity(kp)).jsonObject } catch (e: Exception) {
                throw ChatException("Ungültiges Gerätezertifikat (möglicher Manipulationsversuch).")
            }
            if (id["address"]!!.jsonPrimitive.content != addr) throw ChatException("Der Server hat ein KeyPackage für eine andere Adresse geliefert.")
            if (id["identity"]!!.jsonPrimitive.content != ik) throw ChatException("Schlüssel stimmt nicht überein (möglicher Manipulationsversuch).")
            val dev = o["device"]!!.jsonPrimitive.content
            if (id["device"]!!.jsonPrimitive.content != dev) throw ChatException("Gerätekennung stimmt nicht überein.")
            dev to kp
        }
        if (kps.isEmpty()) throw ChatException("Kein Gerät dieses Kontos ist erreichbar.")
        return kps to ik
    }

    private suspend fun acceptRequestLocked(id: String) {
        val conv = state!!.conversations[id] ?: return
        if (conv.status != "request") return
        conv.status = "active"
        ensureMailbox(conv)
        announce(conv)
        dirty()
    }

    suspend fun acceptRequest(id: String) = op { acceptRequestLocked(id) }

    suspend fun declineRequest(id: String) = op {
        val s = state!!
        if (s.conversations.remove(id) != null) runCatching { client!!.deleteGroup(id.unhex()) }
        dirty()
    }

    /** Bekannte Postfächer eines Kontos: Geräte-Postfächer aus aktiven 1:1-Chats, sonst das Intro-Postfach. */
    private fun knownCaps(addr: String): Map<String, Cap> {
        val s = state!!
        val out = mutableMapOf<String, Cap>()
        for (c in s.conversations.values) {
            if (c.kind != "dm" || c.status != "active") continue
            for ((k, cap) in c.caps) if (k.startsWith("$addr#") && cap.intro != true) out[k] = cap
        }
        if (out.isEmpty()) {
            (s.contacts[addr]?.intro ?: s.conversations.values.firstNotNullOfOrNull { it.caps[addr] })?.let { out[addr] = it }
        }
        return out
    }

    /** Welcome an alle Geräte eines Mitglieds: Geräte-Postfächer plus (falls nicht jedes Gerät eines hat) das kontoweite Intro-Postfach. */
    private suspend fun sendWelcomeTo(conv: Conversation, gid: ByteArray, welcome: ByteArray, addr: String) {
        val s = state!!
        val dev = s.me.deviceId.toByteArray()
        val leaves = conv.members.filter { it.address == addr }
        val caps = leaves.mapNotNull { conv.caps["$addr#${it.device}"] }.toMutableList()
        val intro = s.contacts[addr]?.intro ?: conv.caps[addr]
        if (intro != null && (caps.size < leaves.size || caps.isEmpty())) caps.add(intro)
        for (cap in caps) deliver(cap, uniffi.chat_core.envelopeSeal(cap.key.unb64(), KIND_WELCOME, gid, dev, welcome))
    }

    suspend fun createGroup(title: String, addresses: List<String>): String = op {
        val s = state!!
        if (addresses.isEmpty()) throw ChatException("Mindestens ein Mitglied wählen.")
        val kps = mutableListOf<ByteArray>()
        val caps = mutableMapOf<String, Cap>()
        for (a in addresses) {
            val known = knownCaps(a)
            if (known.isEmpty()) throw ChatException("$a ist kein bekannter Kontakt.")
            val (list, ik) = fetchKeyPackages(a)
            val c = s.contacts.getOrPut(a) { Contact(a, ik) }
            if (c.ik != ik) throw ChatException("Schlüssel von $a hat sich geändert.")
            kps.addAll(list.map { it.second }); caps.putAll(known)
        }
        val gid = client!!.createGroup()
        val add = client!!.addMembers(gid, kps)
        val id = gid.hex()
        val conv = Conversation(
            id = id, kind = "group", title = title.trim().ifEmpty { "Gruppe" }, status = "active", members = readMembers(gid),
            caps = caps, createdAt = clock(),
        )
        s.conversations[id] = conv
        ensureMailbox(conv)
        flush()
        for (a in addresses) sendWelcomeTo(conv, gid, add.welcome, a)
        announce(conv)
        broadcast(conv, newEnvelope(Content.GroupName(conv.title)))
        dirty()
        id
    }

    suspend fun addMember(id: String, address: String) = op {
        val s = state!!
        val conv = s.conversations[id]
        if (conv == null || conv.kind != "group") throw ChatException("Keine Gruppe.")
        if (conv.members.any { it.address == address }) throw ChatException("Bereits Mitglied.")
        val known = knownCaps(address)
        if (known.isEmpty()) throw ChatException("$address ist kein bekannter Kontakt.")
        val (list, ik) = fetchKeyPackages(address)
        val c = s.contacts.getOrPut(address) { Contact(address, ik) }
        if (c.ik != ik) throw ChatException("Schlüssel hat sich geändert.")
        val gid = id.unhex()
        val before = memberAddresses(conv)
        val add = client!!.addMembers(gid, list.map { it.second })
        conv.members = readMembers(gid)
        sendCt(conv, gid, KIND_MLS, add.commit, before) // Commit an bisherige Mitglieder
        conv.caps.putAll(known)
        sendWelcomeTo(conv, gid, add.welcome, address)
        announce(conv, knownEntries(conv), listOf(address))
        broadcast(conv, newEnvelope(Content.GroupName(conv.title)))
        dirty()
    }

    suspend fun removeMember(id: String, address: String) = op {
        val conv = state!!.conversations[id] ?: return@op
        if (conv.kind != "group") return@op
        val gid = id.unhex()
        val commit = client!!.removeMembers(gid, listOf(address))
        // Auch an die entfernten Geräte senden (sie erfahren so von der Entfernung); erst danach Mitglieder/Postfächer aktualisieren.
        sendCt(conv, gid, KIND_MLS, commit, null)
        conv.members = readMembers(gid)
        rebuildCaps(conv)
        dirty()
    }

    // ---------- Geräte (Multi-Device) ----------

    suspend fun listDevices(): List<DeviceInfo> = op {
        ChatJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DeviceInfo.serializer()), api!!.call("GET", "/v1/devices"))
    }

    /** Gerät widerrufen: es verliert Anmeldung und Postfach; ein aktives Gerät entfernt es aus allen Gruppen. */
    suspend fun revokeDevice(id: String) = op {
        api!!.call("DELETE", "/v1/devices/$id")
        incoming.trySend { reconcileDevices() }
    }

    private fun inboxCap(deviceId: String): Cap {
        val s = state!!
        val i = ChatJson.parseToJsonElement(client!!.deviceInbox(deviceId)).jsonObject
        return Cap(s.me.domain, i["mailbox_id"]!!.jsonPrimitive.content, i["token"]!!.jsonPrimitive.content,
            i["key"]!!.jsonPrimitive.content.unhex().b64(), device = deviceId)
    }

    private var reconciling = false

    /**
     * Abgleich der Geräte des eigenen Kontos mit den Blättern in jeder Gruppe: Das Gerät mit der kleinsten ID unter den bereits
     * beteiligten nimmt neue Geräte auf und entfernt widerrufene (siehe docs/MULTIDEVICE.md).
     */
    private fun addAlert(id: String, kind: String, text: String) {
        val s = state ?: return
        if (s.alerts.any { it.id == id }) return
        s.alerts.add(SecurityAlert(id, kind, text, clock()))
    }

    /** Erkennt Geräte, die seit dem letzten Abgleich neu zum Konto hinzugekommen sind. */
    private fun trackDevices(devs: List<DeviceInfo>) {
        val s = state ?: return
        val ids = devs.map { it.id }
        val known = s.knownDevices
        if (known == null) { s.knownDevices = ids.toMutableList(); return }
        for (id in ids) {
            if (id in known) continue
            known.add(id)
            addAlert("dev-$id", "device", "Neues Gerät $id wurde deinem Konto hinzugefügt. Warst du das nicht, widerrufe es sofort (Einstellungen → Geräte).")
        }
        known.retainAll(ids.toSet())
    }

    suspend fun dismissAlert(id: String) = op { state!!.alerts.removeAll { it.id == id }; dirty() }

    private suspend fun reconcileDevices() {
        val s = state ?: return
        if (api == null || reconciling) return
        reconciling = true
        try {
            val devs = ChatJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DeviceInfo.serializer()), api!!.call("GET", "/v1/devices"))
            trackDevices(devs)
            val active = devs.map { it.id }.toSet()
            for (conv in s.conversations.values.toList()) {
                if (conv.status != "active") continue
                val own = conv.members.filter { it.address == s.me.address }
                val present = own.filter { it.device in active }.map { it.device }.sorted()
                if (present.firstOrNull() != s.me.deviceId) continue // nicht unser Zug
                val stale = own.filter { it.device !in active }.map { it.device }
                val missing = devs.map { it.id }.filter { id -> own.none { it.device == id } }
                try {
                    if (stale.isNotEmpty()) removeOwnDevices(conv, stale)
                    if (missing.isNotEmpty()) addOwnDevices(conv, missing)
                } catch (e: Exception) { System.err.println("reconcile ${conv.id}: $e") }
            }
            flush()
        } finally { reconciling = false }
    }

    private suspend fun removeOwnDevices(conv: Conversation, stale: List<String>) {
        val s = state!!
        val gid = conv.id.unhex()
        val json = ChatJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonObject.serializer()),
            stale.map { d -> buildJsonObject { put("address", s.me.address); put("device", d) } },
        )
        val commit = client!!.removeDevices(gid, json)
        conv.members = readMembers(gid)
        rebuildCaps(conv)
        sendCt(conv, gid, KIND_MLS, commit, null)
        dirty()
    }

    private suspend fun addOwnDevices(conv: Conversation, deviceIds: List<String>) {
        val s = state!!
        val (kps, ik) = fetchKeyPackages(s.me.address, deviceIds)
        if (ik != client!!.identityPublic().hex()) throw ChatException("Konto-Schlüssel stimmt nicht überein.")
        val gid = conv.id.unhex()
        val add = client!!.addMembers(gid, kps.map { it.second })
        conv.members = readMembers(gid)
        ensureMailbox(conv)
        sendCt(conv, gid, KIND_MLS, add.commit, null) // an alle Geräte mit bekanntem Postfach (die neuen noch nicht)
        val me = s.me.deviceId.toByteArray()
        val mb = conv.myMailbox!!
        val entries = listOf(CapEntry(s.me.address, s.me.deviceId, s.me.domain, mb.id, mb.token, mb.key)) + knownEntries(conv)
        for ((device, _) in kps) {
            // Welcome und Verzeichnis gehen an die Geräte-Inbox (aus dem Konto-Schlüssel abgeleitet, der Server sieht keine Geheimnisse).
            val cap = inboxCap(device)
            val key = cap.key.unb64()
            deliver(cap, uniffi.chat_core.envelopeSeal(key, KIND_WELCOME, ByteArray(0), me, add.welcome))
            suspend fun send(content: Content) {
                val ct = encryptEnvelope(gid, newEnvelope(content))
                deliver(cap, uniffi.chat_core.envelopeSeal(key, KIND_MLS, gid, me, ct))
            }
            send(Content.Directory(entries))
            if (conv.kind == "group") send(Content.GroupName(conv.title))
        }
        dirty()
    }

    suspend fun leaveConversation(id: String) = op { leaveLocked(id) }

    private fun leaveLocked(id: String) {
        val conv = state!!.conversations[id] ?: return
        runCatching { client!!.deleteGroup(id.unhex()) }
        conv.status = "left"
        dirty()
    }

    suspend fun deleteConversation(id: String) = op {
        val s = state!!
        val conv = s.conversations[id] ?: return@op
        if (conv.status != "left") leaveLocked(id)
        conv.myMailbox?.let { mb ->
            runCatching { api?.call("DELETE", "/v1/mailboxes/${mb.id}") }
            s.mailboxes.remove(mb.id)
        }
        s.conversations.remove(id)
        dirty()
    }

    suspend fun rotateKeys(id: String) = op {
        val conv = state!!.conversations[id] ?: return@op
        val gid = id.unhex()
        val commit = client!!.updateKeys(gid)
        sendCt(conv, gid, KIND_MLS, commit, null)
        dirty()
    }

    class Attachment(val name: String, val mime: String, val data: ByteArray)

    suspend fun sendMessage(id: String, text: String? = null, code: Pair<String, String>? = null, quote: Msg? = null, files: List<Attachment> = emptyList(), once: Boolean = false) = op {
        val s = state!!
        val conv = s.conversations[id]
        if (conv == null || conv.status != "active") throw ChatException("Unterhaltung nicht aktiv.")
        if (once && conv.kind != "dm") throw ChatException("Einmal-Nachrichten gibt es nur in privaten 1:1-Chats.")
        val parts = buildParts(text, code, quote, files)
        if (parts.isEmpty()) return@op
        val env = newEnvelope(Content.Message(parts, if (once) true else null))
        val msg = Msg(
            id = env.id, from = s.me.address, ts = env.ts, parts = parts, status = "sending",
            expiresAt = if (conv.disappearSeconds > 0) clock() + conv.disappearSeconds * 1000 else null,
            once = if (once) true else null,
        )
        conv.messages.add(msg)
        dirty()
        val n = broadcast(conv, env)
        // Ein Empfangsstatus kann bereits eingetroffen sein, während wir noch auf den Server warteten.
        if (msg.status == "sending") msg.status = if (n > 0) "sent" else "failed"
        if (once && s.onceDropOwnCopy) { msg.parts = emptyList(); msg.consumed = true }
        dirty()
    }

    /** Baut die Teile einer Nachricht/eines Beitrags (Limits prüfen, Dateien verschlüsselt hochladen). */
    private suspend fun buildParts(text: String?, code: Pair<String, String>?, quote: Msg?, files: List<Attachment>): MutableList<Part> {
        val lim = info?.limits
        val parts = mutableListOf<Part>()
        if (quote != null) parts.add(Part.Quote(quote.id, snippetOf(quote).take(200)))
        if (!text.isNullOrBlank()) {
            if (lim != null && text.toByteArray().size > lim.max_message_text) throw ChatException("Text zu lang.")
            parts.add(Part.Text(text))
        }
        if (code != null && code.second.isNotBlank()) {
            if (lim != null && code.second.toByteArray().size > lim.max_message_text) throw ChatException("Codeblock zu lang.")
            parts.add(Part.Code(code.first.trim().take(30), code.second))
        }
        if (lim != null) {
            if (files.size > lim.max_message_attachments) throw ChatException("Maximal ${lim.max_message_attachments} Dateien pro Nachricht.")
            if (files.sumOf { it.data.size.toLong() } > lim.max_message_total_size) throw ChatException("Nachricht überschreitet die Gesamtgröße.")
            files.firstOrNull { it.data.size > lim.max_file_size }?.let { throw ChatException("${it.name}: Datei zu groß.") }
        }
        for (f in files) parts.add(uploadFile(f))
        return parts
    }

    /** Beitrag mit Text/Code/Dateien in einen Kanal (Dateien liegen verschlüsselt auf dem eigenen Heimserver). */
    suspend fun postToChannel(id: String, text: String?, code: Pair<String, String>? = null, files: List<Attachment> = emptyList()) = op {
        val parts = buildParts(text, code, null, files)
        if (parts.isNotEmpty()) channels.post(id, parts)
    }

    private suspend fun uploadFile(f: Attachment): Part.File {
        val kn = uniffi.chat_core.fileRandomKey()
        val key = kn.copyOfRange(0, 32)
        val nonce = kn.copyOfRange(32, kn.size)
        val ct = uniffi.chat_core.fileEncrypt(key, nonce, f.data)
        val blobId = api!!.uploadBlob(ct)
        return Part.File(
            blob_id = blobId, blob_server = state!!.me.domain, key = key.b64(), nonce = nonce.b64(),
            name = f.name.take(200), mime = f.mime.ifEmpty { "application/octet-stream" }, size = f.data.size.toLong(),
            sha256 = sha256Bytes(f.data).hex(),
        )
    }

    /** Datei laden und entschlüsseln. Nur auf ausdrückliche Nutzeraktion (verhindert IP-Leaks durch fremde Server). */
    suspend fun downloadFile(p: Part.File): ByteArray = op {
        if (splitAddress("x1@${p.blob_server}") == null) throw ChatException("Ungültiger Server.")
        val ct = Api.downloadBlob(http, p.blob_server, p.blob_id)
        val data = try { uniffi.chat_core.fileDecrypt(p.key.unb64(), p.nonce.unb64(), ct) } catch (e: Exception) { throw ChatException("Datei beschädigt oder manipuliert.") }
        if (data.size.toLong() != p.size || sha256Bytes(data).hex() != p.sha256) throw ChatException("Datei beschädigt oder manipuliert.")
        data
    }

    suspend fun react(id: String, ref: String, emoji: String) = op {
        val conv = state!!.conversations[id] ?: return@op
        if (conv.messages.none { it.id == ref }) return@op
        val env = newEnvelope(Content.Reaction(ref, emoji))
        applyContent(conv, state!!.me.address, state!!.me.deviceId, env)
        broadcast(conv, env)
        dirty()
    }

    suspend fun editMessage(id: String, ref: String, text: String) = op {
        val s = state!!
        val conv = s.conversations[id] ?: return@op
        val m = conv.messages.find { it.id == ref } ?: return@op
        if (m.from != s.me.address || m.deleted == true) return@op
        val parts = m.parts.map { if (it is Part.Text) Part.Text(text) else it }.toMutableList()
        if (parts.none { it is Part.Text }) parts.add(Part.Text(text))
        m.parts = parts
        m.edited = true
        broadcast(conv, newEnvelope(Content.Edit(ref, parts)))
        dirty()
    }

    /**
     * Eigene Datei löschen: entfernt die Nachricht bzw. den Kanal-Beitrag (für alle) und löscht alle Dateien daraus vom Heimserver
     * (der Speicher wird frei). Genau eines von [convId] / [chanId] angeben.
     */
    suspend fun deleteOwnFiles(msgId: String, convId: String? = null, chanId: String? = null) = op {
        val s = state!!
        val parts: List<Part>
        if (chanId != null) {
            val p = s.channels[chanId]?.posts?.find { it.id == msgId }
            if (p == null || p.from != s.me.address) return@op
            parts = p.parts
            channels.mod(chanId, "delete", postId = msgId)
        } else if (convId != null) {
            val m = s.conversations[convId]?.messages?.find { it.id == msgId }
            if (m == null || m.from != s.me.address) return@op
            parts = m.parts
            deleteMessage(convId, msgId)
        } else return@op
        for (p in parts) if (p is Part.File && p.blob_server == s.me.domain) runCatching { api!!.call("DELETE", "/v1/blobs/" + java.net.URLEncoder.encode(p.blob_id, "UTF-8")) }
        dirty()
    }

    suspend fun deleteMessage(id: String, ref: String) = op {
        val s = state!!
        val conv = s.conversations[id] ?: return@op
        val m = conv.messages.find { it.id == ref } ?: return@op
        if (m.from != s.me.address) return@op
        m.deleted = true
        m.parts = emptyList()
        broadcast(conv, newEnvelope(Content.Delete(ref)))
        dirty()
    }

    /** Gruppe umbenennen (alle Mitglieder erhalten den neuen Namen). */
    suspend fun renameGroup(id: String, name: String) = op {
        val conv = state!!.conversations[id] ?: return@op
        if (conv.kind != "group" || conv.status != "active") throw ChatException("Nur aktive Gruppen lassen sich umbenennen.")
        val n = name.trim().take(80)
        if (n.isEmpty()) throw ChatException("Der Name darf nicht leer sein.")
        conv.title = n
        broadcast(conv, newEnvelope(Content.GroupName(n)))
        dirty()
    }

    suspend fun setDisappear(id: String, seconds: Long) = op {
        val conv = state!!.conversations[id] ?: return@op
        conv.disappearSeconds = seconds
        broadcast(conv, newEnvelope(Content.Disappear(seconds)))
        dirty()
    }

    suspend fun markRead(id: String) = op {
        val s = state ?: return@op
        val conv = s.conversations[id] ?: return@op
        var changed = false
        if (conv.unread != 0) { conv.unread = 0; changed = true }
        // Lesebestätigung (nur 1:1, nur wenn eingeschaltet). Einmal-Nachrichten bestätigen erst beim Anzeigen.
        if (s.sendRead && conv.kind == "dm" && conv.status == "active") {
            val ids = conv.messages.filter { it.from != s.me.address && it.once != true && it.readAck != true && it.deleted != true }.map { it.id }
            if (ids.isNotEmpty()) {
                conv.messages.filter { it.id in ids }.forEach { it.readAck = true }
                receipts.add(PendingReceipt(id, "read", ids))
                flushReceipts()
                changed = true
            }
        }
        if (changed) dirty()
    }

    /**
     * Einmal-Nachricht anzeigen: liefert den Inhalt genau einmal zurück, löscht ihn lokal sofort und meldet dem Absender „gelesen“
     * (auch wenn Lesebestätigungen sonst aus sind: das Anzeigen ist hier der Zweck der Nachricht).
     */
    suspend fun revealOnce(convId: String, msgId: String): List<Part>? = op {
        val s = state!!
        val conv = s.conversations[convId] ?: return@op null
        val m = conv.messages.find { it.id == msgId } ?: return@op null
        if (m.once != true || m.consumed == true || m.from == s.me.address) return@op null
        val parts = m.parts
        m.parts = emptyList()
        m.consumed = true
        m.readAck = true
        receipts.add(PendingReceipt(convId, "read", listOf(msgId)))
        flushReceipts()
        dirty()
        parts
    }

    suspend fun setReceiptSettings(sendDelivered: Boolean? = null, sendRead: Boolean? = null, onceDropOwnCopy: Boolean? = null) = op {
        val s = state!!
        sendDelivered?.let { s.sendDelivered = it }
        sendRead?.let { s.sendRead = it }
        onceDropOwnCopy?.let { s.onceDropOwnCopy = it }
        dirty()
    }

    private fun purgeExpired() {
        val s = state ?: return
        val now = clock()
        var changed = false
        for (c in s.conversations.values) if (c.messages.removeAll { it.expiresAt != null && it.expiresAt <= now }) changed = true
        if (changed) dirty()
    }

    // ---------- Kontakte, Blockieren, Filter ----------

    suspend fun verifyContact(address: String, verified: Boolean) = op {
        val s = state!!
        val c = s.contacts[address] ?: return@op
        c.verified = verified
        if (verified) for (conv in s.conversations.values) if (conv.members.any { it.address == address }) conv.warning = null
        dirty()
    }

    suspend fun safetyNumber(address: String): String? = op {
        val c = state!!.contacts[address] ?: return@op null
        uniffi.chat_core.pairSafetyNumber(client!!.identityPublic(), c.ik.unhex())
    }

    suspend fun blockUser(address: String) = op {
        val s = state!!
        if (address !in s.blockedUsers) s.blockedUsers.add(address)
        // DM-Postfach widerrufen: der Server wirft weitere Einwürfe des Kontakts ab.
        for (c in s.conversations.values) {
            val mb = c.myMailbox
            if (c.kind == "dm" && c.members.any { it.address == address } && mb != null) {
                runCatching { api!!.call("DELETE", "/v1/mailboxes/${mb.id}") }
                s.mailboxes.remove(mb.id)
                c.myMailbox = null
            }
        }
        dirty()
    }

    suspend fun unblockUser(address: String) = op { state!!.blockedUsers.remove(address); dirty() }

    suspend fun blockServer(domain: String) = op {
        val d = domain.trim().lowercase()
        if (d.isNotEmpty() && d !in state!!.blockedServers) state!!.blockedServers.add(d)
        dirty(); syncServerFilter()
    }

    suspend fun unblockServer(domain: String) = op { state!!.blockedServers.remove(domain); dirty(); syncServerFilter() }

    suspend fun setFilterMode(mode: String) = op { state!!.filterMode = mode; dirty(); syncServerFilter() }

    suspend fun setAllow(kind: String, value: String, on: Boolean) = op {
        val s = state!!
        val v = value.trim().lowercase()
        val list = if (kind == "user") s.allowUsers else s.allowServers
        if (on) { if (v !in list) list.add(v) } else list.remove(v)
        dirty(); syncServerFilter()
    }

    suspend fun setServerSideFilter(on: Boolean) = op { state!!.serverSideFilter = on; dirty(); syncServerFilter() }

    suspend fun setDirectSend(on: Boolean) = op { state!!.directSend = on; dirty() }

    /** Optional: gehashte Domain-Liste beim Home-Server hinterlegen (spart Bandbreite, verrät aber die Liste). */
    private suspend fun syncServerFilter() {
        val s = state!!
        val a = api ?: return
        val mode = when {
            !s.serverSideFilter -> "off"
            s.filterMode == "allow" -> "allow"
            s.blockedServers.isNotEmpty() -> "block"
            else -> "off"
        }
        val list = if (mode == "allow") s.allowServers else s.blockedServers
        runCatching {
            a.call("PUT", "/v1/filters", buildJsonObject {
                put("mode", mode)
                put("domains", JsonArray(list.map { JsonPrimitive(sha256Bytes(it.toByteArray()).b64()) }))
            }.toString())
        }
    }

    suspend fun quota(): Pair<Long, Long> = op {
        val o = ChatJson.parseToJsonElement(api!!.call("GET", "/v1/quota")).jsonObject
        o["used"]!!.jsonPrimitive.content.toLong() to o["quota"]!!.jsonPrimitive.content.toLong()
    }

    suspend fun createInvite(): String = op {
        ChatJson.parseToJsonElement(api!!.call("POST", "/v1/invites", "{}")).jsonObject["invite"]!!.jsonPrimitive.content
    }
}

fun snippetOf(m: Msg): String {
    if (m.once == true) return "🔒 Einmal-Nachricht" // nie Inhalt einer Einmal-Nachricht in Vorschau/Zitat
    for (p in m.parts) when (p) {
        is Part.Text -> return p.body
        is Part.Code -> return p.body
        is Part.File -> return "📎 ${p.name}"
        else -> {}
    }
    return if (m.deleted == true) "Nachricht gelöscht" else ""
}
