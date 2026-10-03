package chat.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import chat.engine.AppState

/** Pflicht nach der Registrierung: Backup-Datei speichern (damit man sich auf anderen Geräten anmelden kann). */
@Composable
fun BackupGate(vm: AppViewModel, s: AppState) {
    val ctx = LocalContext.current
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = pending
        if (uri != null && d != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(d) }
            saved = true
        }
        pending = null
    }
    AlertDialog(
        onDismissRequest = {}, // nicht schließbar: das Backup ist Pflicht
        title = { Text("Backup speichern") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ohne diese Datei kannst du dich nicht auf einem anderen Gerät anmelden und verlierst bei Geräteverlust dein Konto. " +
                        "Sie enthält deinen Konto-Schlüssel und ist mit einer Passphrase verschlüsselt. Bewahre Datei und Passphrase getrennt auf.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Field("Backup-Passphrase (mind. 10 Zeichen)", pass, { pass = it }, password = true)
                Field("Wiederholen", pass2, { pass2 = it }, password = true)
                if (saved) Text("Datei gespeichert ✓", color = MaterialTheme.colorScheme.primary)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = {
                    err = ""
                    if (pass.length < 10) { err = "Die Passphrase braucht mindestens 10 Zeichen."; return@Button }
                    if (pass != pass2) { err = "Die Passphrasen stimmen nicht überein."; return@Button }
                    vm.run(onError = { err = it }) {
                        pending = vm.engine.exportBackup(pass)
                        save.launch("chat-backup-${s.me.name}.bak")
                    }
                }) { Text(if (saved) "Erneut speichern" else "Backup-Datei speichern") }
                if (saved) Button(onClick = { vm.run { vm.engine.markBackupDone() } }) { Text("Ich habe das Backup sicher abgelegt") }
            }
        },
    )
}
