package chat.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.Conversation
import chat.engine.Msg
import chat.engine.Part
import chat.engine.snippetOf
import kotlinx.coroutines.launch

/** Elemente des Verlaufs (neueste zuerst, die Liste ist umgekehrt): Nachrichten und Tagestrenner. */
private sealed interface Row0 {
    data class M(val m: Msg, val first: Boolean) : Row0
    data class Day(val ts: Long) : Row0
}

private fun rows(messages: List<Msg>): List<Row0> {
    val out = mutableListOf<Row0>()
    for ((i, m) in messages.withIndex()) {
        val prev = messages.getOrNull(i - 1)
        if (prev == null || !sameDay(prev.ts, m.ts)) out.add(Row0.Day(m.ts))
        out.add(Row0.M(m, first = prev == null || prev.from != m.from || !sameDay(prev.ts, m.ts)))
    }
    return out.asReversed()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, s: AppState, conv: Conversation) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember(conv.id) { mutableStateOf("") }
    var codeMode by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    var once by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var files by remember(conv.id) { mutableStateOf(listOf<Uri>()) }
    var busy by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Msg?>(null) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    val me = s.me.address
    val self = vm.engine.isSelfChat(s, conv)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { files = files + it }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { files = files + it }

    // Aus einer anderen App geteilte Inhalte übernehmen
    LaunchedEffect(conv.id) {
        vm.pendingDraft?.takeIf { it.first == conv.id }?.let { (_, d) ->
            d.text?.let { text = it }
            files = files + d.uris
            vm.pendingDraft = null
        }
    }
    val rowList = remember(conv.messages) { rows(conv.messages) }
    LaunchedEffect(conv.messages.size) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
        vm.engine.markRead(conv.id)
    }
    val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }

    fun send() {
        busy = true
        vm.run(onError = { vm.showError(it); busy = false }) {
            val ed = editing
            if (ed != null) {
                vm.engine.editMessage(conv.id, ed.id, text)
                editing = null
            } else {
                val atts = readAttachments(ctx, files, vm.engine.info?.limits?.max_file_size ?: Long.MAX_VALUE)
                vm.engine.sendMessage(
                    conv.id, text = if (codeMode) null else text, code = if (codeMode) lang to text else null,
                    quote = reply, files = atts, once = once && conv.kind == "dm",
                )
            }
            text = ""; files = emptyList(); reply = null; codeMode = false; once = false
            busy = false
            // Animationen brauchen den Frame-Takt der Oberfläche: im Composition-Scope starten, nicht im ViewModel-Scope.
            uiScope.launch { listState.animateScrollToItem(0) }
        }
    }

    val sec = convSecurity(s, conv)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") } },
                title = {
                    Row(Modifier.clickable { vm.go(Route.ConvInfo(conv.id)) }.testTag("chat_header"), verticalAlignment = Alignment.CenterVertically) {
                        AvatarImage(conv.title, vm.engine.avatarOfConv(s, conv), 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(conv.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    self -> "Nur du · Notizen & Dateiablage"
                                    conv.kind == "group" -> "${conv.members.map { it.address }.distinct().size} Mitglieder · ${sec.second}"
                                    else -> sec.second
                                } + (if (conv.disappearSeconds > 0) " · ⏱" else ""),
                                style = MaterialTheme.typography.bodySmall, color = levelColor(sec.first), maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.go(Route.Files(conv.id)) }) { Icon(Icons.Filled.Folder, contentDescription = "Dateien") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Info & Einstellungen") }, onClick = { menu = false; vm.go(Route.ConvInfo(conv.id)) })
                            DropdownMenuItem(text = { Text("Dateien") }, onClick = { menu = false; vm.go(Route.Files(conv.id)) })
                        }
                    }
                },
            )
        },
        modifier = Modifier.imePadding(),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(LocalChatColors.current.chatBg)) {
            conv.warning?.let { Banner("⚠ $it", MaterialTheme.colorScheme.errorContainer, onClick = { vm.go(Route.ConvInfo(conv.id)) }) }
            if (conv.status == "request") RequestBar(vm, conv)
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("messages"), state = listState, reverseLayout = true,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(rowList, key = { r -> when (r) { is Row0.M -> r.m.id; is Row0.Day -> "d" + r.ts } }) { r ->
                        when (r) {
                            is Row0.Day -> DaySeparator(dayLabel(r.ts))
                            is Row0.M -> MessageRow(
                                vm, conv, r.m, mine = r.m.from == me, first = r.first, group = conv.kind == "group" && !self,
                                showDelivered = s.sendDelivered, showRead = s.sendRead, onLong = { selected = r.m },
                            )
                        }
                    }
                    if (rowList.isEmpty()) item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text(
                                    if (self) "📝 Hier legst du Notizen und Dateien für dich ab. Sie sind Ende-zu-Ende verschlüsselt und auf allen deinen Geräten verfügbar."
                                    else "🔒 Nachrichten sind Ende-zu-Ende verschlüsselt. Niemand außerhalb dieses Chats, auch kein Server, kann sie lesen.",
                                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (showJump) SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Zum Ende") }
            }
            if (conv.status == "active") {
                ComposerBar(
                    text = text, onText = { text = it }, placeholder = if (codeMode) "Code …" else "Nachricht",
                    files = files, fileName = { displayName(ctx, it) }, onRemoveFile = { files = files - it },
                    codeMode = codeMode, lang = lang, onLang = { lang = it }, busy = busy,
                    onAttach = { attach = true }, onSend = { send() },
                    flags = listOfNotNull(if (once) "🔒 Einmal-Nachricht" else null, if (codeMode) "</> Codeblock" else null).joinToString(" · "),
                    replyTitle = editing?.let { "Nachricht bearbeiten" } ?: reply?.let { if (it.from == me) "Du" else it.from.substringBefore('@') },
                    replySnippet = (editing ?: reply)?.let { snippetOf(it) } ?: "",
                    onCancelReply = { if (editing != null) { editing = null; text = "" } else reply = null },
                )
            } else if (conv.status == "left") {
                Banner("Du bist in dieser Unterhaltung nicht mehr Mitglied.", MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    if (attach) ActionSheet("Anhängen", attachActions(
        onMedia = { attach = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        onFiles = { attach = false; pickFiles.launch("*/*") },
        codeMode = codeMode, onCode = { attach = false; codeMode = !codeMode },
        once = if (conv.kind == "dm" && !self) once else null, onOnce = { attach = false; once = !once },
    ), onDismiss = { attach = false })

    selected?.let { m ->
        val mine = m.from == me
        val actions = buildList {
            if (m.deleted != true) add(SheetAction(Icons.AutoMirrored.Filled.Reply, "Antworten", "msg_reply") { selected = null; reply = m; editing = null })
            val txt = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.body } + m.parts.filterIsInstance<Part.Code>().joinToString("\n") { it.body }
            if (txt.isNotEmpty() && m.once != true) add(SheetAction(Icons.Filled.ContentCopy, "Kopieren", "msg_copy") { selected = null; copySensitive(ctx, txt) })
            if (mine && m.deleted != true && m.parts.any { it is Part.Text }) add(SheetAction(Icons.Filled.Edit, "Bearbeiten", "msg_edit") {
                selected = null; editing = m; reply = null; text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.body }
            })
            if (mine && m.deleted != true) add(SheetAction(Icons.Filled.Delete, "Für alle löschen", "msg_delete") {
                selected = null; vm.run { if (m.parts.any { it is Part.File }) vm.engine.deleteOwnFiles(m.id, convId = conv.id) else vm.engine.deleteMessage(conv.id, m.id) }
            })
        }
        ActionSheet(null, actions, onDismiss = { selected = null }, header = {
            if (m.deleted != true) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(46.dp).clickable {
                        selected = null; vm.run { vm.engine.react(conv.id, m.id, e) }
                    }) { Box(contentAlignment = Alignment.Center) { Text(e, style = MaterialTheme.typography.titleLarge) } }
                }
            }
        })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(vm: AppViewModel, conv: Conversation, m: Msg, mine: Boolean, first: Boolean, group: Boolean, showDelivered: Boolean, showRead: Boolean, onLong: () -> Unit) {
    val cc = LocalChatColors.current
    var shown by remember { mutableStateOf<List<Part>?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 1.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Bubble(mine, first, Modifier.widthIn(max = 310.dp).combinedClickable(onClick = {}, onLongClick = onLong)) {
            Column(Modifier.padding(start = 9.dp, end = 9.dp, top = 5.dp, bottom = 4.dp)) {
                if (group && !mine && first) Text(m.from.substringBefore('@'), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = senderColor(m.from))
                when {
                    m.deleted == true -> Text("🚫 Nachricht gelöscht", style = MaterialTheme.typography.bodyMedium, color = cc.meta)
                    m.once == true && !mine -> {
                        if (m.consumed == true && shown == null) Text("🔒 Einmal-Nachricht gelesen", color = cc.meta)
                        else {
                            TextButton(onClick = { vm.run { shown = vm.engine.revealOnce(conv.id, m.id) } }) { Text("🔒 Einmal-Nachricht anzeigen") }
                            Text("Wird nach dem Anzeigen gelöscht.", style = MaterialTheme.typography.labelSmall, color = cc.meta)
                        }
                    }
                    else -> {
                        if (m.once == true) Text("🔒 Einmal-Nachricht" + (if (m.consumed == true) " (eigene Kopie entfernt)" else ""), style = MaterialTheme.typography.labelSmall, color = cc.meta)
                        PartsView(vm, m.parts)
                    }
                }
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (m.expiresAt != null) Icon(Icons.Filled.Timer, contentDescription = "verschwindet", tint = cc.meta, modifier = Modifier.size(12.dp))
                    if (m.edited == true) Text("bearbeitet", style = MaterialTheme.typography.labelSmall, color = cc.meta)
                    Text(timeOfDay(m.ts), style = MaterialTheme.typography.labelSmall, color = cc.meta)
                    if (mine) StatusTicks(m.status, showDelivered, showRead)
                }
            }
        }
        if (m.reactions.isNotEmpty()) Row(Modifier.padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            m.reactions.forEach { (e, who) ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.clickable { vm.run { vm.engine.react(conv.id, m.id, e) } }) {
                    Text("$e ${who.size}", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    shown?.let { parts ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text("Einmal-Nachricht") },
            text = {
                Column {
                    PartsView(vm, parts)
                    Text("Diese Nachricht ist nur jetzt sichtbar. Beim Schließen ist sie unwiderruflich gelöscht.", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { shown = null }) { Text("Schließen und löschen") } },
        )
    }
}

@Composable
private fun RequestBar(vm: AppViewModel, conv: Conversation) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${conv.title} möchte mit dir chatten", style = MaterialTheme.typography.titleSmall)
            Text(
                "Erst nach dem Annehmen erfährt dein Gegenüber, wo es dich erreicht. Ablehnen verwirft alles, ohne dass es benachrichtigt wird.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.run { vm.engine.acceptRequest(conv.id) } }, modifier = Modifier.testTag("request_accept")) { Text("Annehmen") }
                OutlinedButton(onClick = { vm.run { vm.engine.declineRequest(conv.id); vm.back() } }) { Text("Ablehnen") }
            }
        }
    }
}

