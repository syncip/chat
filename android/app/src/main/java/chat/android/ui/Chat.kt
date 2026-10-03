package chat.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.Conversation
import chat.engine.Engine
import chat.engine.Msg
import chat.engine.Part
import chat.engine.snippetOf

private fun formatBytes(n: Long): String {
    val u = listOf("B", "KB", "MB", "GB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 10 || i == 0) "%.0f %s".format(v, u[i]) else "%.1f %s".format(v, u[i])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, s: AppState, conv: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var codeMode by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var files by remember { mutableStateOf(listOf<Uri>()) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val me = s.me.address
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { files = files + it }

    LaunchedEffect(conv.messages.size) {
        if (conv.messages.isNotEmpty()) listState.animateScrollToItem(conv.messages.size - 1)
        vm.engine.markRead(conv.id)
    }

    fun send() {
        busy = true
        vm.run(onError = { vm.showError(it); busy = false }) {
            val ed = editing
            if (ed != null) {
                vm.engine.editMessage(conv.id, ed.id, text)
                editing = null
            } else {
                val limit = vm.engine.info?.limits?.max_file_size ?: Long.MAX_VALUE
                val atts = files.map { uri ->
                    var name = "datei"; var size = -1L
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                        }
                    }
                    if (size > limit) throw IllegalStateException("$name: Datei zu groß (max. ${formatBytes(limit)}).")
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("$name nicht lesbar.")
                    Engine.Attachment(name, ctx.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
                }
                vm.engine.sendMessage(
                    conv.id,
                    text = if (codeMode) null else text,
                    code = if (codeMode) lang to text else null,
                    quote = reply,
                    files = atts,
                )
            }
            text = ""; files = emptyList(); reply = null; codeMode = false
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(conv.title, maxLines = 1)
                        Text(
                            if (conv.kind == "group") "${conv.members.size} Mitglieder" else "Ende-zu-Ende-verschlüsselt",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = { info = true }) { Text("ⓘ") } },
            )
        },
        modifier = Modifier.imePadding(),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            conv.warning?.let { Text("⚠ $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            if (conv.status == "request") {
                RequestBar(vm, conv, onGone = onBack)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(conv.messages, key = { it.id }) { m ->
                    MessageBubble(vm, conv, m, mine = m.from == me, onReply = { reply = m }, onEdit = {
                        editing = m; text = m.parts.filterIsInstance<Part.Text>().firstOrNull()?.body ?: ""; codeMode = false
                    })
                }
            }
            if (conv.status == "active") {
                HorizontalDivider()
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    reply?.let {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
                            Text("Antwort auf: ${snippetOf(it).take(80)}", Modifier.weight(1f).padding(6.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { reply = null }) { Text("✕") }
                        }
                    }
                    editing?.let { TextButton(onClick = { editing = null; text = "" }) { Text("Bearbeiten abbrechen") } }
                    if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        files.forEach { u -> FilterChip(selected = true, onClick = { files = files - u }, label = { Text(u.lastPathSegment ?: "Datei") }) }
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = editing == null, onClick = { pick.launch("*/*") }) { Text("📎") }
                        TextButton(enabled = editing == null, onClick = { codeMode = !codeMode }) { Text(if (codeMode) "</> ✓" else "</>") }
                        Column(Modifier.weight(1f)) {
                            if (codeMode) OutlinedTextField(value = lang, onValueChange = { lang = it }, placeholder = { Text("Sprache") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(
                                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                                placeholder = { Text(if (codeMode) "Code …" else "Nachricht …") },
                                textStyle = if (codeMode) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(enabled = !busy && (text.isNotBlank() || files.isNotEmpty()), onClick = { send() }) { Text(if (busy) "…" else "Senden") }
                    }
                }
            } else if (conv.status == "left") {
                Text("Du bist in dieser Unterhaltung nicht mehr Mitglied.", Modifier.padding(12.dp))
            }
        }
    }
    if (info) ConvInfoDialog(vm, s, conv, onClose = { info = false }, onGone = { info = false; onBack() })
}

@Composable
private fun RequestBar(vm: AppViewModel, conv: Conversation, onGone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${conv.title} möchte mit dir chatten.", style = MaterialTheme.typography.titleSmall)
            Text(
                "Erst nach dem Annehmen erfährt dein Gegenüber, wo es dich erreicht. Ablehnen verwirft alles, ohne dass es benachrichtigt wird.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.run { vm.engine.acceptRequest(conv.id) } }) { Text("Annehmen") }
                OutlinedButton(onClick = { vm.run { vm.engine.declineRequest(conv.id); onGone() } }) { Text("Ablehnen") }
            }
        }
    }
}

@Composable
private fun MessageBubble(vm: AppViewModel, conv: Conversation, m: Msg, mine: Boolean, onReply: () -> Unit, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
            onClick = { menu = true },
        ) {
            Column(Modifier.padding(10.dp, 6.dp)) {
                if (m.deleted == true) {
                    Text("Nachricht gelöscht", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (!mine) Text(m.from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    m.parts.forEach { p ->
                        when (p) {
                            is Part.Quote -> Text(p.snippet, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            is Part.Text -> Text(renderInline(p.body))
                            is Part.Code -> CodeBlock(p)
                            is Part.File -> FileCard(vm, p)
                        }
                    }
                    if (m.reactions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.reactions.forEach { (e, who) -> Text("$e ${who.size}", style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Text(
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.ts)) +
                        (if (m.edited == true) " · bearbeitet" else "") + (if (m.expiresAt != null) " · ⏱" else "") +
                        (if (mine) when (m.status) { "sending" -> " · sendet …"; "failed" -> " · fehlgeschlagen"; else -> " ✓" } else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Antworten") }, onClick = { menu = false; onReply() })
            if (m.deleted != true) {
                listOf("👍", "❤️", "😂", "🎉").forEach { e -> DropdownMenuItem(text = { Text(e) }, onClick = { menu = false; vm.run { vm.engine.react(conv.id, m.id, e) } }) }
                if (mine) {
                    DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; vm.run { vm.engine.deleteMessage(conv.id, m.id) } })
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(p: Part.Code) {
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            if (p.lang.isNotEmpty()) Text(p.lang, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.horizontalScroll(rememberScrollState())) { Text(p.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { copySensitive(ctx, p.body) }) { Text("Kopieren") }
        }
    }
}

@Composable
private fun FileCard(vm: AppViewModel, p: Part.File) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pending
        if (uri != null && data != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
        pending = null
    }
    val isImg = Regex("^image/(png|jpeg|gif|webp)$").matches(p.mime)

    fun load(toDisk: Boolean) {
        busy = true
        vm.run(onError = { vm.showError(it); busy = false }) {
            val data = vm.engine.downloadFile(p)
            if (toDisk) { pending = data; save.launch(p.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")) }
            else preview = BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
            busy = false
        }
    }

    Column(Modifier.padding(vertical = 4.dp)) {
        preview?.let { Image(it, contentDescription = p.name, modifier = Modifier.heightIn(max = 280.dp)) }
        Text("📎 ${p.name}", style = MaterialTheme.typography.bodyMedium)
        Text("${formatBytes(p.size)} · von ${p.blob_server} · wird nie ausgeführt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            if (isImg && preview == null) TextButton(enabled = !busy, onClick = { load(false) }) { Text("Vorschau laden") }
            TextButton(enabled = !busy, onClick = { load(true) }) { Text(if (busy) "…" else "Speichern") }
        }
    }
}

@Composable
fun ConvInfoDialog(vm: AppViewModel, s: AppState, conv: Conversation, onClose: () -> Unit, onGone: () -> Unit) {
    var add by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val others = conv.members.filter { it.address != s.me.address }
    val candidates = s.conversations.values.filter { it.kind == "dm" && it.status == "active" }
        .mapNotNull { c -> c.members.firstOrNull { it.address != s.me.address }?.address }
        .filter { a -> conv.members.none { it.address == a } }
    val safety = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(conv.members) {
        safety.value = others.mapNotNull { m -> vm.engine.safetyNumber(m.address)?.let { m.address to it } }.toMap()
    }
    fun run(f: suspend () -> Unit) { err = ""; vm.run(onError = { err = it }) { f() } }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
        title = { Text(conv.title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Mitglieder", style = MaterialTheme.typography.titleSmall)
                others.forEach { m ->
                    val c = s.contacts[m.address]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(m.address + if (c?.verified == true) "  ✔ verifiziert" else "")
                        Text(safety.value[m.address] ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(onClick = { run { vm.engine.verifyContact(m.address, c?.verified != true) } }) { Text(if (c?.verified == true) "Verifizierung entfernen" else "Verifiziert") }
                            TextButton(onClick = { run { vm.engine.blockUser(m.address) } }) { Text("Blockieren") }
                            if (conv.kind == "group") TextButton(onClick = { run { vm.engine.removeMember(conv.id, m.address) } }) { Text("Entfernen") }
                        }
                    }
                }
                Text("Vergleiche die Sicherheitsnummer über einen anderen Kanal (persönlich, Telefon), um Manipulation durch Server auszuschließen.", style = MaterialTheme.typography.bodySmall)
                if (conv.kind == "group" && candidates.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { addMenu = true }) { Text(if (add.isEmpty()) "Mitglied hinzufügen …" else add) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            candidates.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { add = a; addMenu = false }) }
                        }
                        TextButton(enabled = add.isNotEmpty(), onClick = { run { vm.engine.addMember(conv.id, add); add = "" } }) { Text("Hinzufügen") }
                    }
                }
                Text("Verschwindende Nachrichten", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0L to "Aus", 3600L to "1 Std", 86400L to "1 Tag", 604800L to "1 Woche").forEach { (sec, label) ->
                        FilterChip(selected = conv.disappearSeconds == sec, onClick = { run { vm.engine.setDisappear(conv.id, sec) } }, label = { Text(label) })
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { run { vm.engine.rotateKeys(conv.id) } }) { Text("Schlüssel erneuern") }
                    if (conv.status == "active") TextButton(onClick = { run { vm.engine.leaveConversation(conv.id) }; onClose() }) { Text("Verlassen") }
                    TextButton(onClick = { run { vm.engine.deleteConversation(conv.id) }; onGone() }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
