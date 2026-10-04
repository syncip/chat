package chat.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.Conversation

/** Abschnitt als Karte mit Überschrift (Info- und Einstellungsseiten). */
@Composable
fun InfoCard(title: String?, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (title != null) Text(title, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvInfoScreen(vm: AppViewModel, s: AppState, conv: Conversation) {
    var err by remember { mutableStateOf("") }
    var name by remember(conv.id) { mutableStateOf(conv.title) }
    var timer by remember(conv.id) { mutableStateOf(conv.disappearSeconds) }
    var saved by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    val self = vm.engine.isSelfChat(s, conv)
    val group = conv.kind == "group" && !self
    val dirty = (conv.kind == "group" && name.trim() != conv.title) || timer != conv.disappearSeconds
    val others = conv.members.map { it.address }.distinct().filter { it != s.me.address }
    val candidates = s.conversations.values.filter { it.kind == "dm" && it.status == "active" }
        .mapNotNull { c -> c.members.firstOrNull { it.address != s.me.address }?.address }
        .filter { a -> conv.members.none { it.address == a } }.distinct()
    val safety = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(conv.members) { safety.value = others.mapNotNull { a -> vm.engine.safetyNumber(a)?.let { a to it } }.toMap() }
    fun run(f: suspend () -> Unit) { err = ""; vm.run(onError = { err = it }) { f() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (group) "Gruppeninfo" else if (self) "Notizen" else "Kontaktinfo") },
            navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarImage(conv.title, vm.engine.avatarOfConv(s, conv), 96.dp)
                Spacer(Modifier.height(10.dp))
                Text(conv.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                val sec = convSecurity(s, conv)
                Text(sec.second, color = levelColor(sec.first), style = MaterialTheme.typography.bodyMedium)
                if (group) AvatarPickerRow(conv.title, conv.avatar, label = "Gruppenbild ändern", onPick = { d -> run { vm.engine.setGroupAvatar(conv.id, d) } }, onError = { err = it })
            }
            if (err.isNotEmpty()) Text(err, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)

            if (conv.kind == "group" || conv.kind == "dm") InfoCard("Einstellungen") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (conv.kind == "group") OutlinedTextField(name, { name = it.take(80); saved = false }, label = { Text(if (self) "Name" else "Gruppenname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Verschwindende Nachrichten", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0L to "Aus", 3600L to "1 Std", 86400L to "1 Tag", 604800L to "1 Woche").forEach { (sec, label) ->
                            FilterChip(selected = timer == sec, onClick = { timer = sec; saved = false }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = dirty && (conv.kind != "group" || name.isNotBlank()), onClick = {
                            run {
                                if (conv.kind == "group" && name.trim() != conv.title) vm.engine.renameGroup(conv.id, name)
                                if (timer != conv.disappearSeconds) vm.engine.setDisappear(conv.id, timer)
                                saved = true
                            }
                        }) { Text("Speichern") }
                        if (dirty) TextButton(onClick = { name = conv.title; timer = conv.disappearSeconds }) { Text("Verwerfen") }
                        if (saved && !dirty) Text("✔ Gespeichert", color = levelColor(Level.Ok), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!self) InfoCard(if (group) "${others.size} Mitglieder" else "Sicherheit") {
                others.forEach { a ->
                    val c = s.contacts[a]
                    var m by remember { mutableStateOf(false) }
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        leadingContent = { AvatarImage(a, s.avatars[a], 42.dp) },
                        headlineContent = { Text(a) },
                        supportingContent = {
                            Column {
                                if (c?.verified == true) Text("✔ verifiziert", color = levelColor(Level.Ok), style = MaterialTheme.typography.labelMedium)
                                Text("Sicherheitsnummer: " + (safety.value[a] ?: "…"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { m = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Aktionen") }
                                DropdownMenu(expanded = m, onDismissRequest = { m = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (c?.verified == true) "Verifizierung entfernen" else "Als verifiziert markieren") },
                                        leadingIcon = { Icon(Icons.Filled.Verified, null) },
                                        onClick = { m = false; run { vm.engine.verifyContact(a, c?.verified != true) } },
                                    )
                                    DropdownMenuItem(text = { Text("Blockieren") }, leadingIcon = { Icon(Icons.Filled.Block, null) }, onClick = { m = false; run { vm.engine.blockUser(a) } })
                                    if (group) DropdownMenuItem(text = { Text("Aus Gruppe entfernen") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { m = false; run { vm.engine.removeMember(conv.id, a) } })
                                }
                            }
                        },
                    )
                }
                Text(
                    "Vergleicht die Sicherheitsnummer über einen anderen Weg (persönlich, Telefon). Stimmt sie überein, kann kein Server mitlesen oder sich dazwischenschalten.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (group && candidates.isNotEmpty()) Box(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { addOpen = true }) { Icon(Icons.Filled.PersonAdd, null); Spacer(Modifier.height(0.dp)); Text("  Mitglied hinzufügen") }
                    DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        candidates.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { addOpen = false; run { vm.engine.addMember(conv.id, a) } }) }
                    }
                }
            }

            InfoCard(null) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { Icon(Icons.Filled.Key, null) },
                    headlineContent = { Text("Schlüssel erneuern") },
                    supportingContent = { Text("Neue Gruppenschlüssel (Forward Secrecy)") },
                    modifier = Modifier.padding(0.dp).let { it },
                    trailingContent = { TextButton(onClick = { run { vm.engine.rotateKeys(conv.id) } }) { Text("Erneuern") } },
                )
                if (conv.status == "active") ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(if (group) "Gruppe verlassen" else "Chat verlassen", color = MaterialTheme.colorScheme.error) },
                    trailingContent = { TextButton(onClick = { run { vm.engine.leaveConversation(conv.id) } }) { Text("Verlassen") } },
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("Chat löschen", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Entfernt Verlauf und Schlüssel auf diesem Gerät") },
                    trailingContent = { OutlinedButton(onClick = { run { vm.engine.deleteConversation(conv.id); vm.home() } }) { Text("Löschen") } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
