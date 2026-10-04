package chat.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import chat.engine.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ablage der Engine-Daten. Die Engine liefert bereits mit der Passphrase (Argon2id) verschlüsselte Blobs;
 * hier kommt eine **zweite Schicht** mit einem nicht exportierbaren Android-Keystore-Schlüssel (StrongBox, falls vorhanden) dazu.
 * Wer die Dateien ohne das Gerät erbeutet, kann die Passphrase deshalb nicht offline durchprobieren.
 */
class SecureBlobStore(ctx: Context) : BlobStore {
    private val dir = File(ctx.filesDir, "store").apply { mkdirs() }

    private fun file(name: String) = File(dir, name)

    /** Schreib- und Lesezugriffe nacheinander (eine Datei wird nie von zwei Vorgängen gleichzeitig geschrieben). */
    private val lock = Mutex()

    override suspend fun read(name: String): ByteArray? = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = file(name)
            if (!f.exists()) return@withContext null
            try {
                decrypt(name, f.readBytes())
            } catch (e: Exception) {
                // Letzte gute Fassung als Rückfall (falls die aktuelle Datei beschädigt ist).
                val bak = File(dir, "$name.bak")
                if (!bak.exists()) throw e
                android.util.Log.w("chat", "$name nicht lesbar, nutze Sicherungskopie", e)
                decrypt(name, bak.readBytes())
            }
        }
    }

    private fun decrypt(name: String, raw: ByteArray): ByteArray {
        val ivLen = raw[0].toInt() and 0xff
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.updateAAD(name.toByteArray())
        return c.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)
    }

    override suspend fun write(name: String, data: ByteArray) = lock.withLock {
        withContext(Dispatchers.IO) {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key()) // IV erzeugt der Keystore
            c.updateAAD(name.toByteArray())
            val ct = c.doFinal(data)
            val out = ByteArray(1 + c.iv.size + ct.size)
            out[0] = c.iv.size.toByte()
            c.iv.copyInto(out, 1)
            ct.copyInto(out, 1 + c.iv.size)
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(out)
            // Vorherige (lesbare) Fassung als Sicherungskopie behalten, dann atomar ersetzen.
            val cur = file(name)
            if (cur.exists()) runCatching { decrypt(name, cur.readBytes()) }.onSuccess { cur.copyTo(File(dir, "$name.bak"), overwrite = true) }
            if (!tmp.renameTo(cur)) { cur.writeBytes(out); tmp.delete() }
        }
    }

    override suspend fun delete(name: String) = lock.withLock {
        withContext(Dispatchers.IO) { file(name).delete(); File(dir, "$name.bak").delete(); Unit }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return generate(strongBox = Build.VERSION.SDK_INT >= 28)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        } catch (e: java.security.ProviderException) { // u. a. StrongBoxUnavailableException (erst ab API 28 als eigene Klasse)
            if (!strongBox) throw e
            generate(strongBox = false)
        }
    }

    companion object {
        private const val ALIAS = "chat_store_key_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Beim Löschen des Kontos auch den Schlüssel vernichten. */
        fun destroyKey() {
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        }
    }
}
