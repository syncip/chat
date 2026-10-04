package chat.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.decodeCard

@Composable
fun StartChatDialog(vm: AppViewModel, onClose: () -> Unit, onStarted: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    fun start(input: String) {
        busy = true; err = ""
        vm.run(onError = { err = it; busy = false }) {
            val t = input.trim()
            val isCode = t.length <= 80 && Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,39}(@[A-Za-z0-9.:-]+)?$").matches(t)
            val id = vm.engine.startChat(if (isCode) vm.engine.resolveChatCode(t) else decodeCard(input))
            busy = false
            onStarted(id)
        }
    }
    val scan = rememberQrScanner("QR-Code eines Kontakts scannen") { text -> link = text; start(text) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Neuer Chat") },
        text = {
            Column {
                Text("Gib den Chat-Code deines Gegenübers ein (z. B. martinistcool, bei anderen Servern code@server), füge seinen Kontaktlink ein oder scanne seinen QR-Code. Nur wer dir Code oder Link gibt, kann angeschrieben werden.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = link, onValueChange = { link = it }, minLines = 2, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Chat-Code oder https://…/#/add/…") })
                Row {
                    TextButton(onClick = scan) { Text("QR-Code scannen") }
                    TextButton(onClick = { clip.getText()?.text?.let { link = it } }) { Text("Einfügen") }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && link.isNotBlank(), onClick = { start(link) }) { Text(if (busy) "…" else "Chat starten") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@Composable
fun NewGroupDialog(vm: AppViewModel, s: AppState, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val contacts = s.conversations.values
        .filter { it.kind == "dm" && it.status == "active" }
        .mapNotNull { c -> c.members.firstOrNull { it.address != s.me.address }?.address }
        .filter { it !in s.blockedUsers }
    var title by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Neue Gruppe") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Mitglieder (nur bestehende Kontakte)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    if (contacts.isEmpty()) item { Text("Noch keine Kontakte.") }
                    items(contacts) { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = a in sel, onCheckedChange = { if (it) sel.add(a) else sel.remove(a) })
                            Text(a)
                        }
                    }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && sel.isNotEmpty(), onClick = {
                busy = true; err = ""
                vm.run(onError = { err = it; busy = false }) {
                    val id = vm.engine.createGroup(title, sel.toList())
                    busy = false
                    onCreated(id)
                }
            }) { Text(if (busy) "…" else "Gruppe erstellen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}
