package chat.engine.tools

import chat.engine.Engine
import chat.engine.FileBlobStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Interop-Gegenstelle für den Konto-Sync (kein Teil der App): meldet sich mit einer Backup-Datei als weiteres Gerät an und
 * schreibt bei jeder Änderung die Liste der Kanal-Titel in eine Datei („CHANNELS: a,b“).
 * Aufruf: SyncPeer <backupFile> <backupPassphrase> <statusFile> <laufzeitSekunden>
 */
fun main(args: Array<String>) = runBlocking {
    val (backup, bpass, statusFile, secs) = args.toList() + List(4) { "" }
    val dir = File(System.getProperty("java.io.tmpdir"), "syncpeer-${System.nanoTime()}")
    val e = Engine(FileBlobStore(dir), scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
    e.init()
    e.linkDevice(File(backup).readBytes(), if (bpass == "-") System.getenv("PEER_BACKUP_PASS") ?: "" else bpass, "interop-passphrase")
    println("PEER READY")
    var last = ""
    val deadline = System.currentTimeMillis() + secs.toLong() * 1000
    while (System.currentTimeMillis() < deadline) {
        val s = e.snapshot()!!
        val line = "CHANNELS: " + s.channels.values.map { it.title }.sorted().joinToString(",") + " | PUBLIC: " + s.channels.values.filter { it.policy.isPublic }.map { it.title }.sorted().joinToString(",")
        if (line != last) { File(statusFile).writeText(line); last = line }
        delay(200)
    }
    e.close()
}
