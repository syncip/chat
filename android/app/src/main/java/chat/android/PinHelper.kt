package chat.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optionales Entsperren mit einer **App-PIN** (4–8 Ziffern), unabhängig von der Geräte-Sperre.
 * Die Passphrase wird doppelt verschlüsselt: innen mit einem aus der PIN abgeleiteten Schlüssel (PBKDF2, 310 000 Runden), außen mit einem
 * Keystore-Schlüssel (hardwaregestützt, verlässt das Gerät nicht). Nach 5 falschen Eingaben wird die PIN gelöscht; dann ist die Passphrase nötig.
 */
object PinHelper {
    private const val ALIAS = "chat_pin_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val ITER = 310_000
    const val MAX_ATTEMPTS = 5
    const val MIN_LEN = 4
    const val MAX_LEN = 8

    private fun file(ctx: Context) = File(ctx.filesDir, "pin.bin")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chat_pin", Context.MODE_PRIVATE)

    fun isEnrolled(ctx: Context): Boolean = file(ctx).exists()
    fun attemptsLeft(ctx: Context): Int = MAX_ATTEMPTS - prefs(ctx).getInt("fails", 0)

    fun disable(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun outerKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKey {
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun open(key: SecretKey, blob: ByteArray): ByteArray {
        val n = blob[0].toInt() and 0xff
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 1, n)) }
        return c.doFinal(blob, 1 + n, blob.size - 1 - n)
    }

    fun validPin(pin: String) = pin.length in MIN_LEN..MAX_LEN && pin.all { it.isDigit() }

    /** Legt die PIN fest (die Passphrase muss vorher geprüft sein). */
    fun enable(ctx: Context, passphrase: String, pin: String) {
        require(validPin(pin)) { "Die PIN braucht $MIN_LEN–$MAX_LEN Ziffern." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val inner = seal(pinKey(pin, salt), passphrase.toByteArray())
        file(ctx).writeBytes(seal(outerKey(), salt + inner))
        prefs(ctx).edit().putInt("fails", 0).apply()
    }

    /** Liefert die Passphrase zur richtigen PIN; zählt Fehlversuche. */
    fun unlock(ctx: Context, pin: String): Result<String> {
        if (!isEnrolled(ctx)) return Result.failure(IllegalStateException("Keine PIN eingerichtet."))
        return try {
            val plain = open(outerKey(), file(ctx).readBytes())
            val salt = plain.copyOfRange(0, 16)
            val pass = String(open(pinKey(pin, salt), plain.copyOfRange(16, plain.size)))
            prefs(ctx).edit().putInt("fails", 0).apply()
            Result.success(pass)
        } catch (e: java.security.GeneralSecurityException) {
            val fails = prefs(ctx).getInt("fails", 0) + 1
            prefs(ctx).edit().putInt("fails", fails).apply()
            if (fails >= MAX_ATTEMPTS) {
                disable(ctx)
                Result.failure(IllegalStateException("Zu viele Fehlversuche: Die PIN wurde gelöscht. Bitte mit der Passphrase entsperren."))
            } else Result.failure(IllegalStateException("Falsche PIN (noch ${MAX_ATTEMPTS - fails} Versuche)."))
        } catch (e: Exception) {
            disable(ctx)
            Result.failure(IllegalStateException("PIN nicht lesbar. Bitte mit der Passphrase entsperren und neu einrichten."))
        }
    }
}
