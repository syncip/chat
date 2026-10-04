package chat.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val LABELS = mapOf(
    "registration" to "Registrierung", "user_invites" to "Nutzer dürfen Einladungen erzeugen", "registration_pow" to "Proof-of-Work-Bits (0–28)",
    "federation" to "Föderation", "federation_allow" to "Erlaubte Server (bei allowlist), eine Domain pro Zeile", "federation_block" to "Gesperrte Server, eine Domain pro Zeile",
    "max_file_size" to "Max. Dateigröße (Bytes)", "user_quota" to "Speicher je Nutzer (Bytes)", "max_message_attachments" to "Dateien je Nachricht",
    "max_message_total_size" to "Gesamtgröße je Nachricht (Bytes)", "max_message_text" to "Max. Textlänge", "max_envelope_size" to "Max. Postfach-Blob (Bytes)",
    "blob_retention_days" to "Dateien aufbewahren (Tage)", "message_retention_days" to "Nachrichten-Warteschlange aufbewahren (Tage)",
    "rate_per_minute" to "Anfragen pro Minute und IP", "max_devices" to "Geräte je Konto", "max_mailboxes" to "Postfächer je Konto",
    "channels" to "Öffentliche Kanäle erlauben", "max_channels" to "Kanäle je Nutzer", "max_channel_members" to "Mitglieder je Kanal",
    "max_post_size" to "Max. Beitragsgröße (Bytes)", "channel_retention_days" to "Kanal-Beiträge aufbewahren (Tage)", "max_hooks" to "Webhooks je Kanal",
)
private val CHOICES = mapOf("registration" to listOf("invite", "open", "closed"), "federation" to listOf("open", "allowlist", "closed"))

private val STAT_LABELS = listOf(
    "users" to "Nutzer", "admins" to "Administratoren", "new_users_24h" to "Neue Nutzer (24 h)", "devices" to "Geräte", "mailboxes" to "Postfächer",
    "queued_messages" to "Wartende Nachrichten", "queued_bytes" to "Größe der Warteschlange", "blobs" to "Dateien", "blob_bytes" to "Dateispeicher",
    "channels" to "Kanäle", "public_channels" to "davon öffentlich", "channel_members" to "Kanal-Mitglieder", "channel_posts" to "Kanal-Beiträge",
    "channel_posts_24h" to "Beiträge (24 h)", "webhooks" to "Webhooks", "open_invites" to "Offene Einladungen", "db_bytes" to "Datenbank",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: AppViewModel, onBack: () -> Unit) {
    var tab by remember { mutableStateOf("stats") }
    Scaffold(topBar = { TopAppBar(title = { Text("Server-Administration") }, navigationIcon = { TextButton(onClick = onBack) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("stats" to "Statistik", "settings" to "Einstellungen", "users" to "Nutzer").forEach { (t, l) -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(l) }) }
            }
            when (tab) {
                "stats" -> AdminStats(vm)
                "settings" -> AdminSettings(vm)
                else -> AdminUsers(vm)
            }
        }
    }
}

@Composable
private fun AdminStats(vm: AppViewModel) {
    var d by remember { mutableStateOf<JsonObject?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { d = vm.engine.adminStats() }.onFailure { err = it.message ?: "Fehler" }
            kotlinx.coroutines.delay(10_000)
        }
    }
    val cur = d
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cur == null) Text(err.ifEmpty { "Lade …" })
        else {
            val st = cur["stats"]!!.jsonObject
            STAT_LABELS.forEach { (k, l) ->
                val v = st[k]?.jsonPrimitive?.longOrNull ?: 0L
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(l); Text(if (k.endsWith("_bytes")) formatBytes(v) else v.toString(), style = MaterialTheme.typography.titleSmall) }
                HorizontalDivider()
            }
            val up = cur["uptime_seconds"]?.jsonPrimitive?.longOrNull ?: 0L
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Laufzeit"); Text("${up / 86400} d ${up % 86400 / 3600} h ${up % 3600 / 60} min") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Anfragen seit Start"); Text(cur["requests_total"]?.jsonPrimitive?.content ?: "0") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Live-Verbindungen"); Text(cur["ws_connections"]?.jsonPrimitive?.content ?: "0") }
            Text("Aktualisiert sich alle 10 Sekunden. Es werden keine Inhalte und keine IP-Adressen erfasst, nur Zähler.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdminSettings(vm: AppViewModel) {
    var orig by remember { mutableStateOf<JsonObject?>(null) }
    val edit = remember { androidx.compose.runtime.mutableStateMapOf<String, JsonElement>() }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { vm.engine.adminSettings() }.onSuccess { orig = it; edit.putAll(it) }.onFailure { err = it.message ?: "Fehler" } }
    val o = orig
    if (o == null) { Text(err.ifEmpty { "Lade …" }, Modifier.padding(16.dp)); return }
    val dirty = o.any { (k, v) -> edit[k] != v }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            o.keys.forEach { k ->
                val label = LABELS[k] ?: k
                val v = edit[k] ?: o[k]!!
                when {
                    k in CHOICES -> {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CHOICES[k]!!.forEach { c -> FilterChip(selected = v.jsonPrimitive.content == c, onClick = { edit[k] = JsonPrimitive(c); msg = "" }, label = { Text(c) }) } }
                    }
                    v is JsonArray -> OutlinedTextField(
                        value = v.jsonArray.joinToString("\n") { it.jsonPrimitive.content }, onValueChange = { t -> edit[k] = JsonArray(t.split("\n").map { JsonPrimitive(it) }); msg = "" },
                        label = { Text(label) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    v is JsonPrimitive && !v.isString && (v.content == "true" || v.content == "false") -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f)); Switch(checked = v.boolean, onCheckedChange = { edit[k] = JsonPrimitive(it); msg = "" })
                    }
                    else -> OutlinedTextField(
                        value = v.jsonPrimitive.content, onValueChange = { t -> t.filter { c -> c.isDigit() }.toLongOrNull()?.let { n -> edit[k] = JsonPrimitive(n); msg = "" } },
                        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text("Domain und Listen-Adresse lassen sich nur über Umgebungsvariablen ändern (Neustart nötig).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
        }
        // Speichern immer sichtbar unten
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = dirty, onClick = {
                err = ""
                vm.run(onError = { err = it }) {
                    val n = vm.engine.saveAdminSettings(JsonObject(edit.toMap()))
                    orig = n; edit.clear(); edit.putAll(n); msg = "✔ Gespeichert und aktiv"
                }
            }) { Text("Speichern") }
            OutlinedButton(enabled = dirty, onClick = { edit.clear(); edit.putAll(o); msg = "" }) { Text("Verwerfen") }
            if (dirty) Text("Ungespeichert", color = MaterialTheme.colorScheme.tertiary) else if (msg.isNotEmpty()) Text(msg, color = levelColor(Level.Ok))
        }
    }
}

@Composable
private fun AdminUsers(vm: AppViewModel) {
    var users by remember { mutableStateOf<JsonArray?>(null) }
    var err by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    fun load() = vm.run(onError = { err = it }) { users = vm.engine.adminUsers(query.trim()) }
    LaunchedEffect(Unit) { load() }
    val now = System.currentTimeMillis() / 1000
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Nutzer suchen") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { load() }) { Text("Suchen") }
        }
        users?.forEach { e ->
            val u = e.jsonObject
            val name = u["name"]!!.jsonPrimitive.content
            val admin = u["admin"]!!.jsonPrimitive.boolean
            val until = u["banned_until"]?.jsonPrimitive?.longOrNull ?: 0L
            val rate = u["rate_limit"]?.jsonPrimitive?.longOrNull ?: 0L
            val rateUntil = u["rate_until"]?.jsonPrimitive?.longOrNull ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name + if (admin) "  (Admin)" else "", style = MaterialTheme.typography.titleSmall)
                    Text("${u["devices"]?.jsonPrimitive?.content} Geräte · ${formatBytes(u["blob_bytes"]?.jsonPrimitive?.longOrNull ?: 0L)} Dateien · ${u["channels"]?.jsonPrimitive?.content} Kanäle", style = MaterialTheme.typography.bodySmall)
                    if (until == -1L) Text("Dauerhaft gesperrt", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else if (until > now) Text("Gesperrt bis " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(until * 1000)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (rate > 0 && (rateUntil == 0L || rateUntil > now)) Text("Limit $rate/Min", style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    TextButton(onClick = { vm.run(onError = { err = it }) { vm.engine.setAdmin(name, !admin); users = vm.engine.adminUsers(query.trim()) } }) { Text(if (admin) "Admin entziehen" else "Zum Admin machen") }
                    if (!admin) TextButton(onClick = { target = u }) { Text("Sperren / Limit …") }
                }
            }
            HorizontalDivider()
        }
        if (users == null && err.isEmpty()) Text("Lade …")
        if (users?.isEmpty() == true) Text("Keine Nutzer gefunden.")
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
    }
    target?.let { u -> RestrictDialog(vm, u, onClose = { target = null }, onDone = { target = null; load() }) }
}

@Composable
private fun RestrictDialog(vm: AppViewModel, u: kotlinx.serialization.json.JsonObject, onClose: () -> Unit, onDone: () -> Unit) {
    val name = u["name"]!!.jsonPrimitive.content
    val now = System.currentTimeMillis() / 1000
    val until = u["banned_until"]?.jsonPrimitive?.longOrNull ?: 0L
    var ban by remember { mutableStateOf(if (until == -1L) "perm" else if (until > now) "temp" else "") }
    var dur by remember { mutableStateOf("1") }
    var unit by remember { mutableStateOf(60L) }
    var reason by remember { mutableStateOf(u["ban_reason"]?.jsonPrimitive?.content ?: "") }
    var rate by remember { mutableStateOf((u["rate_limit"]?.jsonPrimitive?.longOrNull ?: 0L).let { if (it > 0) it.toString() else "" }) }
    var rdur by remember { mutableStateOf("0") }
    var runit by remember { mutableStateOf(60L) }
    var err by remember { mutableStateOf("") }
    val units = listOf("Minuten" to 1L, "Stunden" to 60L, "Tage" to 1440L)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$name: Sperre / Limit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Keine", "temp" to "Zeitweise", "perm" to "Dauerhaft").forEach { (v, l) -> FilterChip(selected = ban == v, onClick = { ban = v }, label = { Text(l) }) }
                }
                if (ban == "temp") {
                    OutlinedTextField(dur, { dur = it.filter(Char::isDigit) }, label = { Text("Dauer") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { units.forEach { (l, v) -> FilterChip(selected = unit == v, onClick = { unit = v }, label = { Text(l) }) } }
                }
                if (ban != "") OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Grund (sieht der Nutzer)") })
                OutlinedTextField(rate, { rate = it.filter(Char::isDigit) }, label = { Text("Limit pro Minute (leer = Standard)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                if ((rate.toIntOrNull() ?: 0) > 0) {
                    OutlinedTextField(rdur, { rdur = it.filter(Char::isDigit) }, label = { Text("Limit-Dauer (0 = unbefristet)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { units.forEach { (l, v) -> FilterChip(selected = runit == v, onClick = { runit = v }, label = { Text(l) }) } }
                }
                Text("Eine Sperre beendet sofort jeden Zugriff des Kontos auf diesen Server. Das Limit drosselt alle Anfragen des Kontos.", style = MaterialTheme.typography.bodySmall)
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.run(onError = { err = it }) {
                    vm.engine.restrictUser(name, ban, (dur.toLongOrNull() ?: 1L) * unit, reason, rate.toIntOrNull() ?: 0, (rdur.toLongOrNull() ?: 0L) * runit)
                    onDone()
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}
