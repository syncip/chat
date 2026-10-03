package chat.engine.tools

import chat.engine.Engine
import chat.engine.FileBlobStore
import chat.engine.Part
import chat.engine.hex
import chat.engine.sha256Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Interop-Gegenstelle für Tests (kein Teil der App): registriert sich, schreibt den Kontaktlink in eine Datei,
 * nimmt Anfragen an und antwortet auf Text mit „echo: …“, auf Dateien mit „file ok <sha256>“.
 * Aufruf: Peer <server> <invite> <name> <linkFile> <laufzeitSekunden>
 */
fun main(args: Array<String>) = runBlocking {
    val (server, invite, name, linkFile, secs) = args.toList() + List(5) { "" }
    val dir = File(System.getProperty("java.io.tmpdir"), "peer-$name-${System.nanoTime()}")
    val e = Engine(FileBlobStore(dir), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    e.init()
    e.createAccount(server, name, invite, "interop-passphrase")
    File(linkFile).writeText(e.contactLink("http://$server"))
    println("PEER READY")
    val handled = mutableSetOf<String>()
    val deadline = System.currentTimeMillis() + secs.toLong() * 1000
    while (System.currentTimeMillis() < deadline) {
        val s = e.snapshot()!!
        for (c in s.conversations.values) {
            if (c.status == "request") e.acceptRequest(c.id)
            if (c.status != "active") continue
            for (m in c.messages) {
                if (m.from == s.me.address || !handled.add(m.id)) continue
                for (p in m.parts) when (p) {
                    is Part.Text -> e.sendMessage(c.id, text = "echo: ${p.body}")
                    is Part.Code -> e.sendMessage(c.id, text = "code ${p.lang}: ${p.body}")
                    is Part.File -> {
                        val data = e.downloadFile(p)
                        e.sendMessage(c.id, text = "file ok ${sha256Bytes(data).hex()} ${data.size}")
                    }
                    else -> {}
                }
            }
        }
        delay(200)
    }
    e.close()
}
