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

    private suspend fun <T> op(f: suspend () -> T): T = withContext(dispatcher) { f() }

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

    suspend fun createAccount(server: String, name0: String, invite: String, passphrase: String) = op {
        val name = name0.trim().lowercase()
        val info = Api(server.trim().lowercase(), null, http).serverInfo()
        val domain = info.domain
        if (info.registration == "closed") throw ChatException("Dieser Server nimmt keine Registrierungen an.")
        val c = MlsClient.create("$name@$domain")
        val ts = clock() / 1000
        val sig = c.sign("CHAT-REGISTER-V1\n$domain\n$name\n$ts".toByteArray()).b64()
        val pow = solvePow(name, ts, info.pow_bits)
        val kps = c.keyPackages(KP_BATCH.toUInt(), false).map { it.b64() }
        val last = c.keyPackages(1u, true)[0].b64()
        val reg = Api(domain, null, http).register(
            buildJsonObject {
                put("invite", invite.trim()); put("name", name); put("ik", c.identityPublic().b64())
                put("ts", ts); put("sig", sig); put("pow", pow)
                put("keypackages", JsonArray(kps.map { JsonPrimitive(it) })); put("last_resort", last)
            }.toString(),
        )
        val intro = ChatJson.parseToJsonElement(reg).jsonObject["intro"]!!.jsonObject
        val introKey = uniffi.chat_core.envelopeKey().b64()
        val mb = intro["mailbox_id"]!!.jsonPrimitive.content
        client = c
        this.info = info
        state = AppState(
            me = Me("$name@$domain", domain, name),
            intro = IntroBox(mb, intro["send_token"]!!.jsonPrimitive.content, introKey),
            mailboxes = mutableMapOf(mb to introKey),
        )
        vault = VaultSession.create(passphrase)
        persist()
        store.write("meta", "$name@$domain".toByteArray())
        knownAddress = "$name@$domain"
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

    /** Backup als verschlüsselte Datei (Format identisch zum Web-Client). */
    suspend fun exportBackup(passphrase: String): ByteArray = op { uniffi.chat_core.vaultSeal(passphrase, serialize()) }

    suspend fun restoreBackup(file: ByteArray, backupPass: String, newPass: String) = op {
        openVault(backupPass, file)
        vault = VaultSession.create(newPass)
        persist()
        knownAddress = state!!.me.address
        store.write("meta", knownAddress!!.toByteArray())
        start()
    }

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
            override fun sign(data: ByteArray) = c.sign(data)
        }, http)
        info = runCatching { api!!.serverInfo() }.getOrNull() ?: info
        _phase.value = Phase.Unlocked
        emit()
        pump?.cancel()
        pump = scope.launch(dispatcher) { for (job in incoming) runCatching { job() }.onFailure { System.err.println("queue: $it") } }
        connect()
        tick = scope.launch(dispatcher) {
            while (true) { delay(30_000); purgeExpired(); retryOutbox() }
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
                            incoming.trySend { retryOutbox() }
                            incoming.trySend { replenishKeyPackages() }
                        }
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
            for (e in arr) {
                val o = e.jsonObject
                handleRaw(o["seq"]!!.jsonPrimitive.content.toLong(), o["mailbox_id"]!!.jsonPrimitive.content, o["data"]!!.jsonPrimitive.content.unb64())
            }
        }
    }

    private suspend fun handleRaw(seq: Long, mailboxId: String, data: ByteArray) {
        val s = state ?: return
        if (seq <= s.cursor) return
        try { handleIncoming(mailboxId, data) } catch (e: Exception) { System.err.println("message dropped: $e") }
        s.cursor = seq
        flush()
        emit()
        // Erst nach dem Speichern beim Server löschen.
        runCatching { api?.call("DELETE", "/v1/messages?upto=$seq") }
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
        when (o.kind) {
            KIND_WELCOME -> handleWelcome(o.payload)
            KIND_MLS -> handleMls(o.groupId.hex(), o.groupId, o.payload)
        }
    }

    private fun readMembers(gid: ByteArray): List<Member> =
        ChatJson.parseToJsonElement(client!!.members(gid)).jsonArray.map {
            val o = it.jsonObject
            Member(o["address"]!!.jsonPrimitive.content, o["identity"]!!.jsonPrimitive.content)
        }

    private fun pinMembers(conv: Conversation) {
        val s = state!!
        for (m in conv.members) {
            if (m.address == s.me.address) continue
            val c = s.contacts[m.address]
            if (c == null) s.contacts[m.address] = Contact(m.address, m.ik)
            else if (c.ik != m.ik) {
                conv.warning = "Der Schlüssel von ${m.address} hat sich geändert. Bitte neu verifizieren."
                c.verified = false
            }
        }
    }

    private suspend fun handleWelcome(welcome: ByteArray) {
        val s = state!!
        val gid = client!!.join(welcome)
        val id = gid.hex()
        if (s.conversations.containsKey(id)) return
        val members = readMembers(gid)
        val others = members.filter { it.address != s.me.address }
        // Blockierte oder nicht erlaubte Absender: stillschweigend verwerfen (der Absender erfährt nichts).
        val blocked = others.any { it.address in s.blockedUsers || (splitAddress(it.address)?.second ?: "") in s.blockedServers } ||
            (s.filterMode == "allow" && others.none { !isBlocked(it.address) })
        if (blocked) { client!!.deleteGroup(gid); return }
        val kind = if (members.size == 2) "dm" else "group"
        val conv = Conversation(
            id = id, kind = kind, title = if (kind == "dm") others.firstOrNull()?.address ?: "?" else "Gruppe",
            status = "request", members = members, unread = 1, createdAt = clock(),
        )
        s.conversations[id] = conv
        pinMembers(conv)
        if (kind == "dm" && s.contacts[others.firstOrNull()?.address]?.verified == true) acceptRequestLocked(id)
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
                    if (conv.kind == "group") rebuildCaps(conv)
                }
            }
            "application" -> {
                val sender = res["sender"]!!.jsonPrimitive.content
                // Zustand ist fortgeschrieben; blockierte Absender werden erst jetzt verworfen.
                if (isBlocked(sender)) return
                val env = ChatJson.decodeFromJsonElement(Envelope.serializer(), res["envelope"]!!)
                applyContent(conv, sender, env)
                flushRelays()
            }
        }
    }

    private fun rebuildCaps(conv: Conversation) {
        val addrs = conv.members.map { it.address }.toSet()
        conv.caps.keys.retainAll(addrs)
    }

    private fun applyContent(conv: Conversation, sender: String, env: Envelope) {
        if (conv.members.none { it.address == sender }) return
        when (val c = env.content) {
            is Content.Message -> {
                if (conv.messages.any { it.id == env.id }) return
                val msg = Msg(
                    id = env.id, from = sender, ts = minOf(env.ts, clock() + 5 * 60_000), parts = c.parts, status = "received",
                    expiresAt = if (conv.disappearSeconds > 0) clock() + conv.disappearSeconds * 1000 else null,
                )
                conv.messages.add(msg)
                conv.messages.sortBy { it.ts }
                conv.unread++
                _newMessages.tryEmit(conv.id)
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
            is Content.Directory -> for (e in c.entries) {
                val before = conv.caps[e.address]?.mailbox_id
                mergeCap(conv, sender, e)
                val after = conv.caps[e.address]?.mailbox_id
                // Neue Selbst-Ankündigung: an die übrigen Mitglieder weiterreichen.
                if (e.address == sender && after != null && after != before && conv.status == "active" && conv.kind == "group") {
                    relays.add(Triple(conv.id, e, sender))
                }
            }
            is Content.Read -> {}
        }
    }

    /** Caps nur vom Besitzer selbst überschreibbar; für andere gilt „first write wins“. */
    private fun mergeCap(conv: Conversation, sender: String, e: CapEntry) {
        if (splitAddress(e.address) == null || e.domain.isEmpty() || e.mailbox_id.isEmpty() || e.send_token.isEmpty() || e.key.isEmpty()) return
        val cur = conv.caps[e.address]
        if (e.address == sender || cur == null || cur.intro == true) {
            if (e.address != sender && conv.members.none { it.address == e.address }) return
            conv.caps[e.address] = Cap(e.domain, e.mailbox_id, e.send_token, e.key)
        }
    }

    private suspend fun flushRelays() {
        val s = state!!
        val list = relays.toList()
        relays.clear()
        for ((convId, entry, sender) in list) {
            val conv = s.conversations[convId] ?: continue
            if (conv.status != "active") continue
            val others = conv.members.map { it.address }.filter { it != s.me.address && it != sender }
            if (others.isNotEmpty()) sendContent(conv, Content.Directory(listOf(entry)), others)
            val known = conv.caps.filter { (a, k) -> a != sender && k.intro != true }
                .map { (a, k) -> CapEntry(a, k.domain, k.mailbox_id, k.send_token, k.key) }
            if (known.isNotEmpty()) sendContent(conv, Content.Directory(known), listOf(sender))
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

    private suspend fun sendCt(conv: Conversation, gid: ByteArray, kind: UByte, ct: ByteArray, only: List<String>?): Int {
        val me = state!!.me.address
        var sent = 0
        for (m in conv.members) {
            if (m.address == me || (only != null && m.address !in only)) continue
            val cap = conv.caps[m.address] ?: continue
            deliver(cap, uniffi.chat_core.envelopeSeal(cap.key.unb64(), kind, gid, ct))
            sent++
        }
        return sent
    }

    private suspend fun newMailbox(): MyMailbox {
        val r = ChatJson.parseToJsonElement(api!!.call("POST", "/v1/mailboxes")).jsonObject
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
        val me = CapEntry(s.me.address, s.me.domain, mb.id, mb.token, mb.key)
        val gid = conv.id.unhex()
        sendCt(conv, gid, KIND_MLS, encryptEnvelope(gid, newEnvelope(Content.Directory(listOf(me) + extra))), only)
    }

    // ---------- Öffentliche Aktionen ----------

    fun myCard(): ContactCard? {
        val s = state ?: return null
        val i = s.intro ?: return null
        return ContactCard(s.me.address, Cap(s.me.domain, i.mailbox_id, i.send_token, i.key))
    }

    fun contactLink(host: String): String = myCard()?.let { "$host/#/add/${encodeCard(it)}" } ?: ""

    suspend fun setIntroEnabled(on: Boolean) = op {
        val s = state!!
        if (!on && s.intro != null) {
            api!!.call("DELETE", "/v1/mailboxes/${s.intro!!.mailbox_id}")
            s.mailboxes.remove(s.intro!!.mailbox_id)
            s.intro = null
        } else if (on && s.intro == null) {
            val mb = newMailbox()
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
        val (kp, ik) = fetchKeyPackage(addr)
        val contact = s.contacts.getOrPut(addr) { Contact(addr, ik) }
        if (contact.ik != ik) throw ChatException("Der Schlüssel dieses Kontakts hat sich geändert. Bitte neu verifizieren.")
        contact.intro = card.cap
        val gid = client!!.createGroup()
        val add = client!!.addMembers(gid, listOf(kp))
        val id = gid.hex()
        val conv = Conversation(
            id = id, kind = "dm", title = addr, status = "active", members = readMembers(gid),
            caps = mutableMapOf(addr to card.cap), createdAt = clock(),
        )
        s.conversations[id] = conv
        ensureMailbox(conv)
        flush()
        deliver(card.cap, uniffi.chat_core.envelopeSeal(card.cap.key.unb64(), KIND_WELCOME, ByteArray(0), add.welcome))
        announce(conv)
        dirty()
        id
    }

    private suspend fun fetchKeyPackage(addr: String): Pair<ByteArray, String> {
        val enc = java.net.URLEncoder.encode(addr, "UTF-8")
        val r = ChatJson.parseToJsonElement(api!!.call("GET", "/v1/resolve/$enc/keypackage")).jsonObject
        val kp = r["keypackage"]!!.jsonPrimitive.content.unb64()
        val id = ChatJson.parseToJsonElement(client!!.keyPackageIdentity(kp)).jsonObject
        if (id["address"]!!.jsonPrimitive.content != addr) throw ChatException("Der Server hat ein KeyPackage für eine andere Adresse geliefert.")
        val ik = id["identity"]!!.jsonPrimitive.content
        if (ik != r["ik"]!!.jsonPrimitive.content.unb64().hex()) throw ChatException("Schlüssel stimmt nicht überein (möglicher Manipulationsversuch).")
        return kp to ik
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

    suspend fun createGroup(title: String, addresses: List<String>): String = op {
        val s = state!!
        if (addresses.isEmpty()) throw ChatException("Mindestens ein Mitglied wählen.")
        val kps = mutableListOf<ByteArray>()
        val caps = mutableMapOf<String, Cap>()
        for (a in addresses) {
            val cap = knownCap(a) ?: throw ChatException("$a ist kein bekannter Kontakt.")
            val (kp, ik) = fetchKeyPackage(a)
            val c = s.contacts.getOrPut(a) { Contact(a, ik) }
            if (c.ik != ik) throw ChatException("Schlüssel von $a hat sich geändert.")
            kps.add(kp); caps[a] = cap
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
        sendCt(conv, gid, KIND_WELCOME, add.welcome, null)
        announce(conv)
        broadcast(conv, newEnvelope(Content.GroupName(conv.title)))
        dirty()
        id
    }

    private fun knownCap(addr: String): Cap? {
        val s = state!!
        for (c in s.conversations.values) {
            val cap = c.caps[addr]
            if (c.kind == "dm" && c.status == "active" && cap != null && cap.intro != true) return cap
        }
        return s.contacts[addr]?.intro ?: s.conversations.values.firstNotNullOfOrNull { it.caps[addr] }
    }

    suspend fun addMember(id: String, address: String) = op {
        val s = state!!
        val conv = s.conversations[id]
        if (conv == null || conv.kind != "group") throw ChatException("Keine Gruppe.")
        if (conv.members.any { it.address == address }) throw ChatException("Bereits Mitglied.")
        val cap = knownCap(address) ?: throw ChatException("$address ist kein bekannter Kontakt.")
        val (kp, ik) = fetchKeyPackage(address)
        val c = s.contacts.getOrPut(address) { Contact(address, ik) }
        if (c.ik != ik) throw ChatException("Schlüssel hat sich geändert.")
        val gid = id.unhex()
        val add = client!!.addMembers(gid, listOf(kp))
        val others = conv.members.map { it.address }.filter { it != s.me.address }
        conv.members = readMembers(gid)
        sendCt(conv, gid, KIND_MLS, add.commit, others)
        conv.caps[address] = cap
        sendCt(conv, gid, KIND_WELCOME, add.welcome, listOf(address))
        val dir = conv.caps.filter { (a, _) -> a != address }.map { (a, k) -> CapEntry(a, k.domain, k.mailbox_id, k.send_token, k.key) }
        announce(conv, dir, listOf(address))
        broadcast(conv, newEnvelope(Content.GroupName(conv.title)))
        dirty()
    }

    suspend fun removeMember(id: String, address: String) = op {
        val s = state!!
        val conv = s.conversations[id] ?: return@op
        if (conv.kind != "group") return@op
        val gid = id.unhex()
        val commit = client!!.removeMembers(gid, listOf(address))
        val targets = conv.members.map { it.address }.filter { it != s.me.address }
        sendCt(conv, gid, KIND_MLS, commit, targets)
        conv.members = readMembers(gid)
        conv.caps.remove(address)
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

    suspend fun sendMessage(id: String, text: String? = null, code: Pair<String, String>? = null, quote: Msg? = null, files: List<Attachment> = emptyList()) = op {
        val s = state!!
        val conv = s.conversations[id]
        if (conv == null || conv.status != "active") throw ChatException("Unterhaltung nicht aktiv.")
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
        if (parts.isEmpty()) return@op
        val env = newEnvelope(Content.Message(parts))
        val msg = Msg(
            id = env.id, from = s.me.address, ts = env.ts, parts = parts, status = "sending",
            expiresAt = if (conv.disappearSeconds > 0) clock() + conv.disappearSeconds * 1000 else null,
        )
        conv.messages.add(msg)
        dirty()
        val n = broadcast(conv, env)
        msg.status = if (n > 0) "sent" else "failed"
        dirty()
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
        applyContent(conv, state!!.me.address, env)
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

    suspend fun setDisappear(id: String, seconds: Long) = op {
        val conv = state!!.conversations[id] ?: return@op
        conv.disappearSeconds = seconds
        broadcast(conv, newEnvelope(Content.Disappear(seconds)))
        dirty()
    }

    suspend fun markRead(id: String) = op {
        val conv = state?.conversations?.get(id) ?: return@op
        if (conv.unread != 0) { conv.unread = 0; dirty() }
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
    for (p in m.parts) when (p) {
        is Part.Text -> return p.body
        is Part.Code -> return p.body
        is Part.File -> return "📎 ${p.name}"
        else -> {}
    }
    return if (m.deleted == true) "Nachricht gelöscht" else ""
}
