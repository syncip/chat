package chat.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/** Startet einen echten `chatd`-Prozess (Pfad: -Pchatd=… oder Umgebungsvariable CHATD). */
class TestServer(val port: Int = freePort()) : AutoCloseable {
    val domain = "127.0.0.1:$port"
    private val dir: File = Files.createTempDirectory("chatd-test").toFile()
    private val http = OkHttpClient()
    private val proc: Process = run {
        val bin = System.getProperty("chatd.path") ?: System.getenv("CHATD") ?: error("chatd-Binary fehlt (-Pchatd=… oder CHATD)")
        val pb = ProcessBuilder(bin).redirectErrorStream(true).redirectOutput(File(dir, "log.txt"))
        pb.environment().putAll(
            mapOf(
                "CHAT_DOMAIN" to domain, "CHAT_LISTEN" to "127.0.0.1:$port", "CHAT_DATA_DIR" to dir.absolutePath,
                "CHAT_ADMIN_KEY" to "test-admin-key", "CHAT_FEDERATION_ALLOW_PRIVATE" to "true",
                "CHAT_RATE_PER_MINUTE" to "100000", "CHAT_USER_INVITES" to "true",
            ),
        )
        pb.start()
    }

    init {
        if (!waitUp()) error("chatd startet nicht: ${File(dir, "log.txt").readText()}")
    }

    private fun waitUp(): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching { http.newCall(Request.Builder().url("http://$domain/v1/server-info").build()).execute().use { it.isSuccessful } }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(100)
        }
        return false
    }

    fun invite(): String {
        val req = Request.Builder().url("http://$domain/v1/admin/invites").header("X-Admin-Key", "test-admin-key")
            .post(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { r ->
            check(r.code == 201) { "invite: ${r.code}" }
            return ChatJson.parseToJsonElement(r.body!!.string()).let { (it as kotlinx.serialization.json.JsonObject)["invite"]!!.toString().trim('"') }
        }
    }

    override fun close() {
        proc.destroy()
        dir.deleteRecursively()
    }

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
        fun available(): Boolean = (System.getProperty("chatd.path") ?: System.getenv("CHATD"))?.let { File(it).canExecute() } == true
    }
}

val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class Client(val engine: Engine, val dir: File, val server: TestServer, val name: String) {
    val address get() = "$name@${server.domain}"
    suspend fun state(): AppState = engine.snapshot()!!
    suspend fun conv(pred: (Conversation) -> Boolean = { true }): Conversation? = state().conversations.values.firstOrNull(pred)
}

const val PASS = "correct horse battery"

suspend fun newClient(server: TestServer, name: String, dir: File = Files.createTempDirectory("chat-client-$name").toFile()): Client {
    val e = Engine(FileBlobStore(dir), scope = testScope)
    e.init()
    e.createAccount(server.domain, name, server.invite(), PASS)
    return Client(e, dir, server, name)
}

/** Wartet (max. 20 s) bis die Bedingung erfüllt ist. */
suspend fun <T : Any> eventually(what: String, timeoutMs: Long = 20_000, f: suspend () -> T?): T {
    var last: Throwable? = null
    try {
        return withTimeout(timeoutMs) {
            while (true) {
                try { f()?.let { return@withTimeout it } } catch (e: Exception) { last = e }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Zeitüberschreitung: $what (letzter Fehler: $last)")
    }
}

fun Conversation.hasText(t: String) = messages.any { m -> m.parts.any { it is Part.Text && it.body.contains(t) } }
