package chat.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import chat.android.BiometricHelper
import chat.engine.AppState
import chat.engine.baseUrl

/** Kopiert in die Zwischenablage und markiert den Inhalt als sensibel (keine Vorschau, kein Verlauf in Tastaturen). */
fun copySensitive(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("chat", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    cm.setPrimaryClip(clip)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, activity: FragmentActivity, s: AppState, onBack: () -> Unit, onSecureChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    var msg by remember { mutableStateOf("") }
    var quota by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var invite by remember { mutableStateOf("") }
    var blockUser by remember { mutableStateOf("") }
    var blockServer by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(prefs.secureScreen) }
    var keep by remember { mutableStateOf(prefs.keepConnected) }
    var autolock by remember { mutableStateOf(prefs.autoLockMinutes) }
    var bio by remember { mutableStateOf(BiometricHelper.isEnrolled(ctx)) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = pendingBackup
        if (uri != null && d != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(d) }; msg = "Backup gespeichert. Bewahre Datei und Passphrase getrennt auf." }
        pendingBackup = null
    }
    val link = vm.engine.contactLink(baseUrl(s.me.domain))
    val lim = vm.engine.info?.limits

    LaunchedEffect(Unit) { runCatching { quota = vm.engine.quota() } }
    fun run(ok: String = "", f: suspend () -> Unit) { msg = ""; vm.run(onError = { msg = it }) { f(); if (ok.isNotEmpty()) msg = ok } }

    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { TextButton(onClick = onBack) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)

            Section("Dein Kontaktlink")
            if (s.intro != null) {
                Text("Wer diesen Link hat, kann dir eine Chat-Anfrage schicken. Du entscheidest, ob du sie annimmst.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = link, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySensitive(ctx, link); msg = "Link kopiert." }) { Text("Kopieren") }
                    OutlinedButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link)
                        ctx.startActivity(android.content.Intent.createChooser(i, "Kontaktlink teilen"))
                    }) { Text("Teilen") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { run("Kontaktlink deaktiviert.") { vm.engine.setIntroEnabled(false) } }) { Text("Deaktivieren") }
                    TextButton(onClick = { run("Neuer Link erzeugt, der alte ist ungültig.") { vm.engine.setIntroEnabled(false); vm.engine.setIntroEnabled(true) } }) { Text("Neu erzeugen") }
                }
            } else {
                Text("Der Kontaktlink ist aus: Niemand Neues kann dich erreichen.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run { vm.engine.setIntroEnabled(true) } }) { Text("Aktivieren") }
            }

            Section("Blockieren & Allowlist")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.filterMode == "off", onClick = { run { vm.engine.setFilterMode("off") } }, label = { Text("Offen") })
                FilterChip(selected = s.filterMode == "allow", onClick = { run { vm.engine.setFilterMode("allow") } }, label = { Text("Nur Erlaubte & Verifizierte") })
            }
            ListEditor("Blockierte Nutzer", s.blockedUsers, blockUser, { blockUser = it }, "name@server",
                onAdd = { run { vm.engine.blockUser(it.trim().lowercase()); blockUser = "" } }, onRemove = { run { vm.engine.unblockUser(it) } })
            ListEditor("Blockierte Server", s.blockedServers, blockServer, { blockServer = it }, "server.example",
                onAdd = { run { vm.engine.blockServer(it); blockServer = "" } }, onRemove = { run { vm.engine.unblockServer(it) } })
            ListEditor("Erlaubt (Nutzer oder Server)", s.allowUsers + s.allowServers, allow, { allow = it }, "name@server oder server.example",
                onAdd = { run { vm.engine.setAllow(if ("@" in it) "user" else "server", it, true); allow = "" } },
                onRemove = { run { vm.engine.setAllow(if ("@" in it) "user" else "server", it, false) } })
            SwitchRow("Server-Liste beim Home-Server hinterlegen (spart Bandbreite, verrät dem Server aber gehashte Domains)", s.serverSideFilter) { run { vm.engine.setServerSideFilter(it) } }
            Text("Blockierte Absender erfahren nichts davon.", style = MaterialTheme.typography.bodySmall)

            Section("Netzwerk")
            SwitchRow("Direkt an Empfänger-Server senden (der Ziel-Server sieht dann deine IP; ein VPN/Tor wird empfohlen)", s.directSend) { run { vm.engine.setDirectSend(it) } }

            Section("Sicherheit")
            SwitchRow("Screenshots und Bildschirmaufnahme verhindern", secure) { secure = it; prefs.secureScreen = it; onSecureChanged() }
            SwitchRow("Verbindung im Hintergrund halten, solange entsperrt", keep) {
                keep = it; prefs.keepConnected = it
                if (it) chat.android.ChatService.start(ctx) else chat.android.ChatService.stop(ctx)
            }
            Text("Automatisch sperren nach (Minuten im Hintergrund):", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 5, 15).forEach { m -> FilterChip(selected = autolock == m, onClick = { autolock = m; prefs.autoLockMinutes = m }, label = { Text(if (m == 0) "Sofort" else "$m") }) }
            }
            if (BiometricHelper.available(ctx)) {
                SwitchRow("Mit Biometrie entsperren (Passphrase unten eintragen)", bio) { on ->
                    if (on) {
                        if (backupPass.isEmpty()) { msg = "Bitte zuerst deine Passphrase unten eintragen."; return@SwitchRow }
                        run {
                            if (!vm.engine.checkPassphrase(backupPass)) throw IllegalStateException("Falsche Passphrase.")
                            BiometricHelper.enable(activity, backupPass) { r ->
                                bio = r.isSuccess; prefs.biometricEnabled = r.isSuccess
                                if (r.isFailure) msg = "Biometrie konnte nicht aktiviert werden."
                            }
                        }
                    } else { BiometricHelper.disable(ctx); bio = false; prefs.biometricEnabled = false }
                }
            }
            OutlinedTextField(
                value = backupPass, onValueChange = { backupPass = it }, label = { Text("Passphrase (für Backup / Biometrie)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )

            Section("Server & Speicher")
            Text("Home-Server: ${s.me.domain} · Föderation: ${vm.engine.info?.federation ?: "?"} · Registrierung: ${vm.engine.info?.registration ?: "?"}", style = MaterialTheme.typography.bodySmall)
            if (baseUrl(s.me.domain).startsWith("http://")) Text("⚠ Dieser Server nutzt kein TLS (http). Nur in vertrauenswürdigen Netzen verwenden.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            quota?.let { Text("${fmt(it.first)} von ${fmt(it.second)} belegt", style = MaterialTheme.typography.bodySmall) }
            lim?.let { Text("Limits: Datei ${fmt(it.max_file_size)}, ${it.max_message_attachments} Dateien / ${fmt(it.max_message_total_size)} pro Nachricht, Dateien ${it.blob_retention_days} Tage gespeichert.", style = MaterialTheme.typography.bodySmall) }
            vm.engine.info?.client_hash?.takeIf { it.isNotEmpty() }?.let { Text("Web-Client-Hash des Servers: ${it.take(32)}…", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(onClick = { run { invite = vm.engine.createInvite() } }) { Text("Einladungscode erzeugen") }
            if (invite.isNotEmpty()) Text(invite, style = MaterialTheme.typography.bodyMedium)

            Section("Backup & Sitzung")
            Text("Das Backup ist mit der Passphrase verschlüsselt und mit dem Web-Client kompatibel. Auf zwei Geräten gleichzeitig verwenden führt zu defekter Verschlüsselung (noch kein Multi-Device).", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (backupPass.length < 10) { msg = "Die Passphrase braucht mindestens 10 Zeichen."; return@OutlinedButton }
                    run { pendingBackup = vm.engine.exportBackup(backupPass); save.launch("chat-backup-${s.me.name}.bak") }
                }) { Text("Backup speichern") }
                OutlinedButton(onClick = { run { vm.engine.lock() } }) { Text("Sperren") }
            }
            TextButton(onClick = { run { vm.engine.deleteAccount(); wipeSecrets(activity) } }) { Text("Konto lokal löschen", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun fmt(n: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 10 || i == 0) "%.0f %s".format(v, u[i]) else "%.1f %s".format(v, u[i])
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ListEditor(title: String, items: List<String>, value: String, setValue: (String) -> Unit, placeholder: String, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        items.forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) { Text(i, Modifier.weight(1f)); TextButton(onClick = { onRemove(i) }) { Text("entfernen") } } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = value, onValueChange = setValue, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = value.isNotBlank(), onClick = { onAdd(value) }) { Text("Hinzufügen") }
        }
    }
}
