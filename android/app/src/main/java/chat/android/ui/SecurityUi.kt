package chat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.Conversation
import chat.engine.baseUrl

enum class Level { Ok, Info, Warn, Bad }

class SecItem(val level: Level, val title: String, val detail: String)

private fun worst(levels: List<Level>): Level = levels.maxByOrNull { if (it == Level.Bad) 2 else if (it == Level.Warn) 1 else 0 } ?: Level.Ok

/** Sicherheitsstatus eines Chats: Schlüsseländerung (rot), unverifiziert (grau), verifiziert (grün). */
fun convSecurity(s: AppState, c: Conversation): Pair<Level, String> {
    if (c.warning != null) return Level.Bad to "Schlüssel geändert"
    val others = c.members.map { it.address }.distinct().filter { it != s.me.address }
    if (others.isEmpty()) return Level.Info to "Ende-zu-Ende verschlüsselt"
    val unverified = others.filter { s.contacts[it]?.verified != true }
    if (unverified.isEmpty()) return Level.Ok to "Verifiziert"
    return Level.Info to (if (c.kind == "dm") "Nicht verifiziert" else "${unverified.size} nicht verifiziert")
}

fun securityReport(s: AppState, online: Boolean, clientHash: String?): Pair<Level, List<SecItem>> {
    val items = mutableListOf<SecItem>()
    val http = baseUrl(s.me.domain).startsWith("http://")
    items += if (http) SecItem(Level.Warn, "Verbindung ohne TLS (http)", "Nachrichten bleiben Ende-zu-Ende verschlüsselt, aber der Verkehr ist im Netzwerk sichtbar. Nur in vertrauenswürdigen Netzen verwenden.")
    else SecItem(Level.Ok, "Verbindung verschlüsselt (TLS)", "Die Verbindung zu ${s.me.domain} ist verschlüsselt.")
    items += if (online) SecItem(Level.Ok, "Mit ${s.me.domain} verbunden", "Echtzeit-Verbindung steht.")
    else SecItem(Level.Warn, "Offline", "Nachrichten werden gesendet, sobald die Verbindung wieder da ist.")
    items += SecItem(Level.Ok, "Ende-zu-Ende-Verschlüsselung (MLS, RFC 9420)", "Server sehen weder Texte noch Dateien, Dateinamen, Gruppen oder Reaktionen.")
    items += SecItem(Level.Ok, "Lokaler Speicher doppelt verschlüsselt", "Passphrase (Argon2id) plus nicht exportierbarer Android-Keystore-Schlüssel. Kein Cloud-Backup, nur System-Zertifikate.")
    items += if (s.backupDone) SecItem(Level.Ok, "Backup-Datei gespeichert", "Du kannst dich auf weiteren Geräten anmelden und dein Konto wiederherstellen.")
    else SecItem(Level.Bad, "Kein Backup gespeichert", "Ohne Backup-Datei ist dein Konto bei Geräteverlust weg.")
    for (a in s.alerts) items += SecItem(if (a.kind == "key") Level.Bad else Level.Warn, if (a.kind == "key") "Schlüssel eines Kontakts geändert" else "Neues Gerät im Konto", a.text)
    val devs = s.knownDevices?.size ?: 1
    items += SecItem(Level.Info, "$devs ${if (devs == 1) "Gerät" else "Geräte"} aktiv", "Unbekannte Geräte kannst du unter Einstellungen → Geräte widerrufen.")
    val people = s.contacts.values
    items += SecItem(Level.Info, "${people.count { it.verified }} von ${people.size} Kontakten verifiziert",
        if (people.isEmpty()) "Noch keine Kontakte." else "Vergleiche die Sicherheitsnummer über einen anderen Kanal, um Manipulation durch Server auszuschließen.")
    for (c in s.conversations.values.filter { it.warning != null }) items += SecItem(Level.Bad, "Warnung in „${c.title}“", c.warning!!)
    val timers = s.conversations.values.count { it.disappearSeconds > 0 }
    if (timers > 0) items += SecItem(Level.Info, "Verschwindende Nachrichten in $timers Chats", "Nachrichten werden nach der eingestellten Zeit lokal gelöscht.")
    items += SecItem(Level.Info, "Bestätigungen: Empfangen ${if (s.sendDelivered) "an" else "aus"}, Gelesen ${if (s.sendRead) "an" else "aus"}", "Standardmäßig aus (weniger Metadaten). Gegenseitig.")
    if (!clientHash.isNullOrEmpty()) items += SecItem(Level.Info, "Web-Client-Prüfsumme des Servers", "${clientHash.take(32)}… (betrifft nur den Web-Client; diese App ist davon unabhängig)")
    return worst(items.map { it.level }) to items
}

@Composable
fun levelColor(l: Level): Color = when (l) {
    Level.Ok -> Color(0xFF1A8F5A)
    Level.Warn -> Color(0xFFA56A00)
    Level.Bad -> MaterialTheme.colorScheme.error
    Level.Info -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun SecurityDialog(vm: AppViewModel, s: AppState, online: Boolean, onClose: () -> Unit) {
    val (level, items) = securityReport(s, online, vm.engine.info?.client_hash)
    val headline = when (level) { Level.Bad -> "Handlungsbedarf"; Level.Warn -> "Es gibt Hinweise"; else -> "Alles in Ordnung" }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        title = { Text("🛡 $headline", color = levelColor(level)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in s.alerts) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.run { vm.engine.dismissAlert(a.id) } }) { Text("Gesehen") }
                        }
                    }
                }
                items.forEach { i ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (i.level) { Level.Ok -> "✔"; Level.Info -> "ℹ"; Level.Warn -> "⚠"; Level.Bad -> "✖" }, color = levelColor(i.level))
                        Column {
                            Text(i.title, style = MaterialTheme.typography.titleSmall)
                            Text(i.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
    )
}
