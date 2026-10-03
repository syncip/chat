package chat.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import chat.android.BiometricHelper
import chat.android.SecureBlobStore
import chat.engine.baseUrl

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, hint: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = hint?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun TransportWarning(server: String) {
    if (baseUrl(server).startsWith("http://") && !server.startsWith("localhost")) {
        Text(
            "⚠ Unverschlüsselte Verbindung (http): Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber der Verkehr ist im Netzwerk sichtbar. Nur in vertrauenswürdigen Netzen nutzen.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var restore by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var backupUri by remember { mutableStateOf<Uri?>(null) }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backupUri = it }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Chat", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Ende-zu-Ende-verschlüsselt. Dein Schlüssel wird auf diesem Gerät erzeugt und verlässt es nie.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!restore) {
            Field("Server", server, { server = it }, hint = "chat.example.org oder 192.168.1.10:8080")
            TransportWarning(server)
            Field("Benutzername", name, { name = it.lowercase() })
            Field("Einladungscode", invite, { invite = it }, hint = "nur bei Einladungs-Servern")
        } else {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(if (backupUri == null) "Backup-Datei wählen" else "Datei gewählt ✓") }
            Field("Passphrase des Backups", backupPass, { backupPass = it }, password = true)
        }
        Field(if (restore) "Neue Passphrase für dieses Gerät" else "Passphrase (schützt deine Schlüssel lokal)", pass, { pass = it }, password = true)
        Field("Passphrase wiederholen", pass2, { pass2 = it }, password = true)
        Text(
            "Es gibt kein „Passwort vergessen“. Ohne Passphrase und ohne Backup ist dein Konto verloren.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
        )
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
            onClick = {
                err = ""
                if (pass.length < 10) { err = "Die Passphrase braucht mindestens 10 Zeichen."; return@Button }
                if (pass != pass2) { err = "Die Passphrasen stimmen nicht überein."; return@Button }
                busy = true
                vm.run(onError = { err = it; busy = false }) {
                    if (restore) {
                        val uri = backupUri ?: throw IllegalStateException("Bitte Backup-Datei wählen.")
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Datei nicht lesbar.")
                        vm.engine.linkDevice(bytes, backupPass, pass)
                    } else {
                        vm.engine.createAccount(server, name, invite, pass)
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Bitte warten …" else if (restore) "Gerät anmelden" else "Konto erstellen") }
        TextButton(onClick = { restore = !restore }) { Text(if (restore) "Neues Konto erstellen" else "Mit Backup-Datei auf diesem Gerät anmelden") }
    }
}

@Composable
fun UnlockScreen(vm: AppViewModel, activity: FragmentActivity) {
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val bio = remember { BiometricHelper.isEnrolled(activity) && BiometricHelper.available(activity) }

    fun unlock(p: String) {
        busy = true
        err = ""
        vm.run(onError = { err = it; busy = false }) { vm.engine.unlock(p); busy = false }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(48.dp))
        Text("Entsperren", style = MaterialTheme.typography.headlineMedium)
        Text(vm.engine.knownAddress() ?: "", style = MaterialTheme.typography.bodyMedium)
        Field("Passphrase", pass, { pass = it }, password = true)
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = { unlock(pass) }) {
            Text(if (busy) "Entsperre …" else "Entsperren")
        }
        if (bio) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                BiometricHelper.unlock(activity) { r -> r.onSuccess { unlock(it) }.onFailure { err = "Biometrie fehlgeschlagen. Bitte Passphrase eingeben." } }
            }) { Text("Mit Biometrie entsperren") }
        }
    }
}

/** Beim Löschen des Kontos auch Keystore-Schlüssel und Biometrie entfernen. */
fun wipeSecrets(activity: FragmentActivity) {
    BiometricHelper.disable(activity)
    SecureBlobStore.destroyKey()
}
