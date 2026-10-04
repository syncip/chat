package chat.engine

import java.io.File

/** Ablage für verschlüsselte Blobs. Die Android-App liefert eine Keystore-gestützte Implementierung. */
interface BlobStore {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, data: ByteArray)
    suspend fun delete(name: String)
}

/** Einfache Datei-Ablage (Tests; die Daten sind ohnehin bereits mit Passphrase verschlüsselt). */
class FileBlobStore(private val dir: File) : BlobStore {
    init { dir.mkdirs() }
    private fun f(name: String) = File(dir, name)
    override suspend fun read(name: String): ByteArray? = f(name).takeIf { it.exists() }?.readBytes()
    override suspend fun write(name: String, data: ByteArray) {
        val tmp = File(dir, "$name.${System.nanoTime()}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f(name))) { f(name).writeBytes(data); tmp.delete() } // atomar, wo möglich
    }
    override suspend fun delete(name: String) { f(name).delete() }
}
