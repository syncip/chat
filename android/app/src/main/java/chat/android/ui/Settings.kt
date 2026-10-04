package chat.android.ui

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.collectAsState
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import chat.android.PinHelper
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
    var page by remember { mutableStateOf(vm.settingsPage.also { vm.settingsPage = null }) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val titles = mapOf("profile" to "Profil & Kontakt", "privacy" to "Datenschutz", "security" to "Sicherheit & Entsperren", "notify" to "Benachrichtigungen & Verbindung", "devices" to "Geräte", "server" to "Server, Speicher & Backup", "diag" to "Verbindung & Diagnose")
    var quota by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var invite by remember { mutableStateOf("") }
    var blockUser by remember { mutableStateOf("") }
    var blockServer by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(prefs.secureScreen) }
    var keep by remember { mutableStateOf(prefs.keepConnected) }
    var autolock by remember { mutableStateOf(prefs.autoLockMinutes) }
    var pendingBackup by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val d = pendingBackup
        if (uri != null && d != null) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(d) }; msg = "Backup gespeichert. Bewahre Datei und Passphrase getrennt auf." }
        pendingBackup = null
    }
    val link = vm.engine.contactLink(baseUrl(s.me.domain))
    val lim = vm.engine.info?.limits

    var devices by remember { mutableStateOf<List<chat.engine.DeviceInfo>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { quota = vm.engine.quota() }; runCatching { devices = vm.engine.listDevices() } }
    fun run(ok: String = "", f: suspend () -> Unit) { msg = ""; vm.run(onError = { msg = it }) { f(); if (ok.isNotEmpty()) msg = ok } }

    Scaffold(topBar = { TopAppBar(title = { Text(titles[page] ?: "Einstellungen") }, navigationIcon = { androidx.compose.material3.IconButton(onClick = { if (page != null) page = null else onBack() }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.primary)
            if (page == null) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(s.me.address, s.me.avatar, 64.dp)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(s.me.name, style = MaterialTheme.typography.titleLarge)
                        Text(s.me.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { vm.lockNow() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Lock, null); Text("  Jetzt sperren") }
                SettingsRow(Icons.Filled.Person, "Profil & Kontakt", "Profilbild, Kontaktlink, Chat-Code, QR", "set_profile") { page = "profile" }
                SettingsRow(Icons.Filled.Security, "Sicherheit & Entsperren", "App-PIN, Fingerabdruck, automatische Sperre", "set_security") { page = "security" }
                SettingsRow(Icons.Filled.PrivacyTip, "Datenschutz", "Blockieren, Netzwerk, Bestätigungen", "set_privacy") { page = "privacy" }
                SettingsRow(Icons.Filled.Notifications, "Benachrichtigungen & Verbindung", "Ton, Verbindung im Hintergrund", "set_notify") { page = "notify" }
                SettingsRow(Icons.Filled.Devices, "Geräte", "Weitere Geräte, QR-Anmeldung", "set_devices") { page = "devices" }
                SettingsRow(Icons.Filled.Cloud, "Server, Speicher & Backup", "Speicher, Einladung, Backup, Konto", "set_server") { page = "server" }
                SettingsRow(Icons.Filled.NetworkCheck, "Verbindung & Diagnose", "Status, letzter Abgleich, Fehler", "set_diag") { page = "diag" }
                val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
                Text("Chat Android $version" + (vm.engine.info?.app_version?.let { " · Server $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (page == "profile") {
            Text("Profilbild", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Dein Bild sehen deine Chat-Partner (Ende-zu-Ende-verschlüsselt mitgeteilt) und deine anderen Geräte.", style = MaterialTheme.typography.bodySmall)
            AvatarPickerRow(s.me.address, s.me.avatar, label = "Profilbild wählen", onPick = { d -> run(if (d != null) "Profilbild gesetzt." else "Profilbild entfernt.") { vm.engine.setMyAvatar(d) } }, onError = { msg = it })

            Text("Dein Kontaktlink", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
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
                ChatCodeSection(vm, link, onMsg = { msg = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { run("Kontaktlink deaktiviert.") { vm.engine.setIntroEnabled(false) } }) { Text("Deaktivieren") }
                    TextButton(onClick = { run("Neuer Link erzeugt, der alte ist ungültig.") { vm.engine.setIntroEnabled(false); vm.engine.setIntroEnabled(true) } }) { Text("Neu erzeugen") }
                }
            } else {
                Text("Der Kontaktlink ist aus: Niemand Neues kann dich erreichen.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { run { vm.engine.setIntroEnabled(true) } }) { Text("Aktivieren") }
            }

            }
            if (page == "privacy") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.filterMode == "off", onClick = { run { vm.engine.setFilterMode("off") } }, label = { Text("Offen") })
                FilterChip(selected = s.filterMode == "allow", onClick = { run { vm.engine.setFilterMode("allow") } }, label = { Text("Nur Erlaubte & Verifizierte") })
            }
            Text("Blockieren & Allowlist", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            ListEditor("Blockierte Nutzer", s.blockedUsers, blockUser, { blockUser = it }, "name@server",
                onAdd = { run { vm.engine.blockUser(it.trim().lowercase()); blockUser = "" } }, onRemove = { run { vm.engine.unblockUser(it) } })
            ListEditor("Blockierte Server", s.blockedServers, blockServer, { blockServer = it }, "server.example",
                onAdd = { run { vm.engine.blockServer(it); blockServer = "" } }, onRemove = { run { vm.engine.unblockServer(it) } })
            ListEditor("Erlaubt (Nutzer oder Server)", s.allowUsers + s.allowServers, allow, { allow = it }, "name@server oder server.example",
                onAdd = { run { vm.engine.setAllow(if ("@" in it) "user" else "server", it, true); allow = "" } },
                onRemove = { run { vm.engine.setAllow(if ("@" in it) "user" else "server", it, false) } })
            SwitchRow("Server-Liste beim Home-Server hinterlegen (spart Bandbreite, verrät dem Server aber gehashte Domains)", s.serverSideFilter) { run { vm.engine.setServerSideFilter(it) } }
            Text("Blockierte Absender erfahren nichts davon.", style = MaterialTheme.typography.bodySmall)

            SwitchRow("Direkt an Empfänger-Server senden (der Ziel-Server sieht dann deine IP; ein VPN/Tor wird empfohlen)", s.directSend) { run { vm.engine.setDirectSend(it) } }

            Text("Nur in privaten Chats. Wer „Empfangen“/„Gelesen“ ausschaltet, sieht die der anderen auch nicht (gegenseitig).", style = MaterialTheme.typography.bodySmall)
            SwitchRow("„Empfangen“ senden", s.sendDelivered) { run { vm.engine.setReceiptSettings(sendDelivered = it) } }
            SwitchRow("„Gelesen“ senden", s.sendRead) { run { vm.engine.setReceiptSettings(sendRead = it) } }
            SwitchRow("Einmal-Nachrichten: eigene Kopie sofort entfernen", s.onceDropOwnCopy) { run { vm.engine.setReceiptSettings(onceDropOwnCopy = it) } }

            }
            if (page == "devices") {
            Text("Dein Konto kann auf mehreren Geräten gleichzeitig aktiv sein. Neue Geräte meldest du mit der Backup-Datei oder per QR-Code an.", style = MaterialTheme.typography.bodySmall)
            DeviceLinkSection(vm, onMsg = { msg = it })
            devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.id + if (d.current) " (dieses Gerät)" else "", Modifier.weight(1f), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    if (!d.current) TextButton(onClick = { run("Gerät widerrufen.") { vm.engine.revokeDevice(d.id); devices = vm.engine.listDevices() } }) { Text("Widerrufen") }
                }
            }

            }
            if (page == "notify") {
            var inApp by remember { mutableStateOf(prefs.inAppSound) }
            SwitchRow("Benachrichtigungston bei neuen Nachrichten (App geöffnet)", inApp) { inApp = it; prefs.inAppSound = it }
            SwitchRow("Verbindung im Hintergrund halten, solange entsperrt", keep) {
                keep = it; prefs.keepConnected = it
                if (it) chat.android.ChatService.start(ctx) else chat.android.ChatService.stop(ctx)
            }
            Text("Tipp: Nimm die App in den Android-Einstellungen aus der Akku-Optimierung (Akku → „Nicht optimieren“), damit die Verbindung im Hintergrund nicht beendet wird.", style = MaterialTheme.typography.bodySmall)
            }
            if (page == "security") {
            QuickUnlockSection(vm, activity, onMsg = { msg = it })
            SwitchRow("Screenshots und Bildschirmaufnahme verhindern", secure) { secure = it; prefs.secureScreen = it; onSecureChanged() }
            Text("Automatisch sperren nach (Minuten im Hintergrund):", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 1, 5, 15).forEach { m -> FilterChip(selected = autolock == m, onClick = { autolock = m; prefs.autoLockMinutes = m }, label = { Text(if (m == 0) "Sofort" else "$m") }) }
            }
            Text("Mindestlänge für Passphrasen: ${prefs.minPassphrase} Zeichen (vom Server-Admin festgelegt).", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.lockNow() }) { Text("🔒 Jetzt sperren") }
            }
            if (page == "diag") {
                DiagnosticsSection(vm)
            }
            if (page == "server") {
            Text("Server & Speicher", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Home-Server: ${s.me.domain} · Föderation: ${vm.engine.info?.federation ?: "?"} · Registrierung: ${vm.engine.info?.registration ?: "?"}", style = MaterialTheme.typography.bodySmall)
            if (baseUrl(s.me.domain).startsWith("http://")) Text("⚠ Dieser Server nutzt kein TLS (http). Nur in vertrauenswürdigen Netzen verwenden.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            quota?.let { Text("${fmt(it.first)} von ${fmt(it.second)} belegt", style = MaterialTheme.typography.bodySmall) }
            lim?.let { Text("Limits: Datei ${fmt(it.max_file_size)}, ${it.max_message_attachments} Dateien / ${fmt(it.max_message_total_size)} pro Nachricht, Dateien ${it.blob_retention_days} Tage gespeichert.", style = MaterialTheme.typography.bodySmall) }
            vm.engine.info?.client_hash?.takeIf { it.isNotEmpty() }?.let { Text("Web-Client-Hash des Servers: ${it.take(32)}…", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(onClick = { run { invite = vm.engine.createInvite() } }) { Text("Einladungscode erzeugen") }
            if (invite.isNotEmpty()) Text(invite, style = MaterialTheme.typography.bodyMedium)

            Text("Backup", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Das Backup ist mit der Passphrase verschlüsselt und mit dem Web-Client kompatibel. Mit der Backup-Datei meldest du dich auf einem weiteren Gerät an (Multi-Device); sie enthält deinen Konto-Schlüssel und gehört nur in deine Hände.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = backupPass, onValueChange = { backupPass = it }, label = { Text("Passphrase für das Backup") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (backupPass.length < prefs.minPassphrase) { msg = "Die Passphrase braucht mindestens ${prefs.minPassphrase} Zeichen."; return@OutlinedButton }
                    run { pendingBackup = vm.engine.exportBackup(backupPass); save.launch("chat-backup-${s.me.name}.bak") }
                }) { Text("Backup speichern") }
                OutlinedButton(onClick = { vm.lockNow() }) { Text("Sperren") }
            }
            TextButton(onClick = { run { vm.engine.deleteAccount(); wipeSecrets(activity) } }) { Text("Konto lokal löschen", color = MaterialTheme.colorScheme.error) }
            }
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

@Composable
private fun ChatCodeSection(vm: AppViewModel, link: String, onMsg: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { vm.engine.myChatCode() }.onSuccess { saved = it; code = it } }
    Text("Chat-Code", style = MaterialTheme.typography.labelLarge)
    Text(
        "Wähle einen Code (3–40 Zeichen: a–z, 0–9, _ und -). Wer ihn bei „Neuer Chat“ eingibt, landet bei dir. Der Code ist öffentlich auflösbar: wer ihn errät, kann dir eine Anfrage schicken (du entscheidest, ob du sie annimmst).",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(code, { code = it.lowercase() }, label = { Text("z. B. martinistcool") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = code.isNotBlank() && code != saved, onClick = {
            vm.run(onError = onMsg) { val c = vm.engine.setChatCode(code); saved = c; code = c; onMsg("Chat-Code „$c“ gespeichert.") }
        }) { Text("Code speichern") }
        if (saved.isNotEmpty()) TextButton(onClick = { vm.run(onError = onMsg) { vm.engine.removeChatCode(); saved = ""; code = ""; onMsg("Chat-Code entfernt.") } }) { Text("Entfernen") }
    }
    OutlinedButton(onClick = { qr = !qr }) { Text(if (qr) "QR-Code ausblenden" else "Meinen Kontakt-QR-Code anzeigen") }
    if (qr) QrImage(link, 240.dp)
}

@Composable
private fun DeviceLinkSection(vm: AppViewModel, onMsg: (String) -> Unit) {
    var link by remember { mutableStateOf<String?>(null) }
    Text(
        "Weiteres Gerät per QR-Code anmelden: Der Code enthält einen Einmalschlüssel, gilt 5 Minuten und nur einmal. Zeige ihn niemandem und mache kein Foto davon.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (link == null) {
        OutlinedButton(onClick = { vm.run(onError = onMsg) { link = vm.engine.createDeviceLink() } }) { Text("QR-Code anzeigen") }
    } else {
        QrImage(link!!, 260.dp)
        TextButton(onClick = { link = null }) { Text("Code ausblenden") }
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

/** Verbindungsstatus und letzte Abgleiche – hilft bei „Nachrichten/Kanäle kommen nicht an“. */
@Composable
private fun DiagnosticsSection(vm: AppViewModel) {
    val online by vm.online.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val d = remember(tick) { vm.engine.diag }
    fun ago(ts: Long) = if (ts <= 0) "noch nie" else "vor ${(System.currentTimeMillis() - ts) / 1000} s (" + timeOfDay(ts) + ")"
    Text("Echtzeit-Verbindung: " + if (online) "✓ verbunden" else "✗ getrennt (versucht es automatisch erneut)", color = if (online) levelColor(Level.Ok) else MaterialTheme.colorScheme.error)
    Text("Verbindungsaufbauten: ${d.wsConnects} · zuletzt verbunden: ${ago(d.lastWsReadyAt)}", style = MaterialTheme.typography.bodySmall)
    d.lastWsError?.let { Text("Letzter Verbindungsfehler: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Text("Nachrichten zuletzt abgeholt: ${ago(d.lastCatchUpAt)}", style = MaterialTheme.typography.bodySmall)
    d.lastCatchUpError?.let { Text("Fehler beim Abholen: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Text("Konto-Abgleich (Kanäle, Einstellungen) zuletzt: ${ago(d.lastSyncAt)}", style = MaterialTheme.typography.bodySmall)
    d.lastSyncError?.let { Text("Fehler beim Abgleich: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    var busy by remember { mutableStateOf(false) }
    Button(enabled = !busy, onClick = { busy = true; vm.run(onError = { busy = false; vm.showError(it) }) { vm.engine.refreshNow(); busy = false; tick++ } }) { Text(if (busy) "Gleiche ab …" else "Jetzt alles abgleichen") }
    Text(
        "Die App hält die Verbindung, solange sie entsperrt ist und „Verbindung im Hintergrund halten“ an ist. Android kann Apps im Hintergrund trotzdem beenden – nimm die App dafür aus der Akku-Optimierung.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Schnell-Entsperren: Fingerabdruck/Geräte-Sperre oder eigene App-PIN, jeweils mit ausdrücklichem „Speichern“. */
@Composable
private fun QuickUnlockSection(vm: AppViewModel, activity: FragmentActivity, onMsg: (String) -> Unit) {
    val ctx = LocalContext.current
    var bio by remember { mutableStateOf(BiometricHelper.isEnrolled(ctx)) }
    var pinOn by remember { mutableStateOf(PinHelper.isEnrolled(ctx)) }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val bioAvailable = BiometricHelper.available(ctx)
    Text("Schnell-Entsperren", style = MaterialTheme.typography.titleMedium)
    Text(
        "Statt jedes Mal die lange Passphrase einzugeben, kannst du die App per Fingerabdruck/Gerätesperre oder mit einer eigenen PIN öffnen. " +
            "Die Passphrase bleibt der Hauptschlüssel (Backup, neue Geräte). Zur Einrichtung bestätigst du sie einmal.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Aktiv: " + listOfNotNull(if (bio) "Fingerabdruck/Gerätesperre" else null, if (pinOn) "App-PIN" else null).ifEmpty { listOf("nichts (nur Passphrase)") }.joinToString(" + "),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
    )
    if (!bio || !pinOn) {
        OutlinedTextField(
            value = pass, onValueChange = { pass = it; err = "" }, label = { Text("Deine Passphrase (zur Bestätigung)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("qu_pass"),
        )
    }
    // Fingerabdruck / Geräte-Sperre
    if (bio) {
        OutlinedButton(onClick = { BiometricHelper.disable(ctx); bio = false; vm.prefs.biometricEnabled = false; onMsg("Fingerabdruck-Entsperren ausgeschaltet.") }) { Text("Fingerabdruck/Gerätesperre ausschalten") }
    } else if (bioAvailable) {
        Button(enabled = pass.isNotEmpty(), onClick = {
            vm.run(onError = { err = it }) {
                if (!vm.engine.checkPassphrase(pass)) throw IllegalStateException("Falsche Passphrase.")
                BiometricHelper.enable(activity, pass) { r ->
                    bio = r.isSuccess; vm.prefs.biometricEnabled = r.isSuccess
                    if (r.isSuccess) { pass = ""; onMsg("Fingerabdruck-Entsperren gespeichert.") } else err = "Konnte nicht aktiviert werden: " + (r.exceptionOrNull()?.message ?: "abgebrochen")
                }
            }
        }) { Text("Fingerabdruck/Gerätesperre aktivieren und speichern") }
    } else {
        Text("Dafür muss in Android eine Bildschirmsperre (PIN, Muster oder Passwort) bzw. ein Fingerabdruck eingerichtet sein.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = {
            val i = if (android.os.Build.VERSION.SDK_INT >= 30) android.content.Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL) else android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } }
        }) { Text("Android-Sicherheitseinstellungen öffnen") }
    }
    // App-PIN
    Text("App-PIN", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (pinOn) {
        OutlinedButton(onClick = { PinHelper.disable(ctx); pinOn = false; onMsg("App-PIN gelöscht.") }) { Text("App-PIN löschen") }
    } else {
        val kb = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(PinHelper.MAX_LEN); err = "" }, label = { Text("Neue PIN (${PinHelper.MIN_LEN}–${PinHelper.MAX_LEN} Ziffern)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb, modifier = Modifier.fillMaxWidth().testTag("qu_pin"))
        OutlinedTextField(pin2, { pin2 = it.filter(Char::isDigit).take(PinHelper.MAX_LEN); err = "" }, label = { Text("PIN wiederholen") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb, modifier = Modifier.fillMaxWidth().testTag("qu_pin2"))
        Button(enabled = pass.isNotEmpty() && pin.isNotEmpty() && pin2.isNotEmpty(), onClick = {
            err = ""
            if (!PinHelper.validPin(pin)) { err = "Die PIN braucht ${PinHelper.MIN_LEN}–${PinHelper.MAX_LEN} Ziffern."; return@Button }
            if (pin != pin2) { err = "Die PINs stimmen nicht überein."; return@Button }
            vm.run(onError = { err = it }) {
                if (!vm.engine.checkPassphrase(pass)) throw IllegalStateException("Falsche Passphrase.")
                val p = pass; val n = pin
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinHelper.enable(ctx, p, n) }
                pinOn = true; pin = ""; pin2 = ""; pass = ""
                onMsg("App-PIN gespeichert. Nach 5 falschen Eingaben wird sie gelöscht.")
            }
        }, modifier = Modifier.testTag("qu_pin_save")) { Text("App-PIN speichern") }
    }
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
}
