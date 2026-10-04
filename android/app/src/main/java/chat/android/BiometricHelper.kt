package chat.android

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optionales Entsperren per Fingerabdruck/Gesicht **oder Geräte-PIN/-Muster/-Kennwort** (ab Android 11; davor nur Biometrie).
 * Die Passphrase wird mit einem Keystore-Schlüssel verschlüsselt, der **pro Verwendung** eine Authentifizierung verlangt.
 * Ohne erfolgreiche Authentifizierung bleibt die Passphrase unlesbar.
 */
object BiometricHelper {
    private const val ALIAS = "chat_bio_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** Ab Android 11 (API 30) gelten auch Geräte-PIN, -Muster und -Kennwort; davor nur starke Biometrie. */
    private val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_STRONG

    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun file(ctx: Context) = File(ctx.filesDir, "bio.bin")

    fun isEnrolled(ctx: Context): Boolean = file(ctx).exists()

    fun disable(ctx: Context) {
        file(ctx).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            b.setInvalidatedByBiometricEnrollment(true) // neuer Fingerabdruck macht den Schlüssel ungültig
            @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    private fun prompt(activity: FragmentActivity, title: String, cipher: Cipher, onResult: (Result<Cipher>) -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onResult(Result.success(c)) else onResult(Result.failure(IllegalStateException("kein Cipher")))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(Result.failure(IllegalStateException(errString.toString())))
            }
        }
        val b = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Abbrechen") // mit Geräte-Anmeldedaten ist kein Abbrechen-Knopf erlaubt
        val info = b.build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), cb).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Passphrase biometrisch geschützt ablegen. */
    fun enable(activity: FragmentActivity, passphrase: String, onResult: (Result<Unit>) -> Unit) {
        try {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            prompt(activity, "Biometrie aktivieren", c) { r ->
                onResult(r.mapCatching { ciph ->
                    val ct = ciph.doFinal(passphrase.toByteArray())
                    val out = ByteArray(1 + ciph.iv.size + ct.size)
                    out[0] = ciph.iv.size.toByte()
                    ciph.iv.copyInto(out, 1)
                    ct.copyInto(out, 1 + ciph.iv.size)
                    file(activity).writeBytes(out)
                })
            }
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    /** Passphrase nach erfolgreicher Biometrie zurückgeben. */
    fun unlock(activity: FragmentActivity, onResult: (Result<String>) -> Unit) {
        try {
            val raw = file(activity).readBytes()
            val ivLen = raw[0].toInt() and 0xff
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 1, ivLen)) }
            prompt(activity, "Chat entsperren", c) { r ->
                onResult(r.mapCatching { ciph -> String(ciph.doFinal(raw, 1 + ivLen, raw.size - 1 - ivLen)) })
            }
        } catch (e: Exception) {
            // z. B. KeyPermanentlyInvalidatedException nach neuem Fingerabdruck: Biometrie deaktivieren, Passphrase nötig.
            disable(activity)
            onResult(Result.failure(e))
        }
    }
}
