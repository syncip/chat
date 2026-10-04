package chat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.ChannelState
import chat.engine.Conversation
import chat.engine.Part
import chat.engine.decodeCard

private fun convPreview(s: AppState, c: Conversation): String {
    if (c.status == "request") return "Möchte mit dir chatten"
    if (c.status == "left") return "Verlassen"
    val m = c.messages.lastOrNull() ?: return if (c.kind == "group") "Gruppe erstellt" else ""
    if (m.deleted == true) return "🚫 Nachricht gelöscht"
    val who = if (c.kind == "group" && m.from != s.me.address) m.from.substringBefore('@') + ": " else ""
    if (m.once == true) return who + "🔒 Einmal-Nachricht"
    val p = m.parts.firstOrNull { it is Part.Text || it is Part.Code || it is Part.File }
    return who + when (p) {
        is Part.Text -> p.body.replace('\n', ' ')
        is Part.Code -> "</> " + p.body.lineSequence().first()
        is Part.File -> (if (p.mime.startsWith("image/")) "📷 " else if (p.mime.startsWith("video/")) "🎬 " else if (p.mime.startsWith("audio/")) "🎵 " else "📎 ") + p.name
        else -> ""
    }
}

private fun chanPreview(c: ChannelState): String = when (c.me.status) {
    "pending" -> "Wartet auf Freigabe"
    "banned" -> "Gesperrt"
    else -> c.posts.lastOrNull { !it.deleted }?.let { p ->
        val who = if (p.hook != null) "🔔 ${p.hook}: " else p.from.substringBefore('@') + ": "
        who + when (val x = p.parts.firstOrNull { it is Part.Text || it is Part.File || it is Part.Code }) {
            is Part.Text -> x.body.replace('\n', ' ')
            is Part.File -> "📎 " + x.name
            is Part.Code -> "</> Code"
            else -> ""
        }
    } ?: if (c.policy.isPublic) "Öffentlicher Kanal" else "Kanal"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, s: AppState, online: Boolean, isAdmin: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var joinLink by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val sec = securityReport(s, online, vm.engine.info?.client_hash).first
    val share = vm.share

    val convs = s.conversations.values.sortedByDescending { it.messages.lastOrNull()?.ts ?: it.createdAt }
        .filter { q.isBlank() || it.title.contains(q, true) || convPreview(s, it).contains(q, true) }
    val chans = s.channels.values.sortedByDescending { it.posts.lastOrNull()?.ts ?: it.createdAt }
        .filter { q.isBlank() || it.title.contains(q, true) }
    val unreadChats = s.conversations.values.sumOf { it.unread }
    val unreadChans = s.channels.values.sumOf { it.unread }

    fun open(c: Conversation) {
        val sh = vm.share
        if (sh != null) { vm.pendingDraft = c.id to sh; vm.share = null }
        vm.run { vm.engine.markRead(c.id) }
        vm.go(Route.Chat(c.id))
    }

    Scaffold(
        topBar = {
            if (searching) TopAppBar(
                navigationIcon = { IconButton(onClick = { searching = false; q = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Suche schließen") } },
                title = {
                    TextField(q, { q = it }, placeholder = { Text("Suchen …") }, singleLine = true, colors = transparentFieldColors(), modifier = Modifier.fillMaxWidth().testTag("search_input"))
                },
            ) else TopAppBar(
                title = {
                    Column {
                        Text("Chat", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (online) s.me.address else "Verbinde mit ${s.me.domain} …",
                            style = MaterialTheme.typography.labelSmall, color = if (online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, contentDescription = "Suchen") }
                    IconButton(onClick = { vm.lockNow() }, modifier = Modifier.testTag("btn_lock")) { Icon(Icons.Filled.Lock, contentDescription = "Sperren") }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("btn_menu")) {
                            BadgedBox(badge = { if (sec == Level.Bad || sec == Level.Warn) Badge(containerColor = levelColor(sec)) }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menü") }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Einstellungen") }, leadingIcon = { Icon(Icons.Filled.Settings, null) }, onClick = { menu = false; vm.go(Route.Settings) }, modifier = Modifier.testTag("menu_settings"))
                            DropdownMenuItem(text = { Text("Alle Dateien") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { menu = false; vm.go(Route.Files(null)) })
                            DropdownMenuItem(text = { Text("Sicherheit") }, leadingIcon = { Icon(Icons.Filled.Shield, null, tint = levelColor(sec)) }, onClick = { menu = false; dialog = "security" })
                            if (isAdmin) DropdownMenuItem(text = { Text("Administration") }, leadingIcon = { Icon(Icons.Filled.AdminPanelSettings, null) }, onClick = { menu = false; vm.go(Route.Admin) })
                            DropdownMenuItem(text = { Text("Sperren") }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, onClick = { menu = false; vm.lockNow() })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { add = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.testTag("fab_add")) {
                Icon(Icons.Filled.Add, contentDescription = "Neu: Chat, Gruppe, Kanal")
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (share != null) Banner(
                "Teilen: Wähle einen Chat für " + (if (share.uris.isNotEmpty()) "${share.uris.size} Datei(en)" else "den Text"),
                MaterialTheme.colorScheme.secondaryContainer,
            ) { TextButton(onClick = { vm.share = null }) { Text("Abbrechen") } }
            if (s.alerts.isNotEmpty()) Banner(
                "⚠ ${s.alerts[0].text}" + (if (s.alerts.size > 1) " (+${s.alerts.size - 1})" else ""),
                MaterialTheme.colorScheme.errorContainer, onClick = { dialog = "security" },
            ) { TextButton(onClick = { val ids = s.alerts.map { it.id }; vm.run { ids.forEach { vm.engine.dismissAlert(it) } } }) { Text("OK") } }
            if (!s.backupDone) Banner("Kein Backup gespeichert – ohne Backup ist dein Konto bei Geräteverlust weg.", MaterialTheme.colorScheme.errorContainer)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.testTag("tab_chats"), text = { TabLabel("Chats", unreadChats) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.testTag("tab_channels"), text = { TabLabel("Kanäle", unreadChans) })
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; vm.run(onError = { refreshing = false; vm.showError(it) }) { vm.engine.refreshNow(); refreshing = false } },
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag(if (tab == 0) "list_chats" else "list_channels"), contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (tab == 0) {
                        if (convs.isEmpty()) item { EmptyHint("Noch keine Chats", "Tippe auf ＋ und füge den Link oder Chat-Code eines Kontakts ein – oder starte „Notizen an mich“.") }
                        items(convs, key = { "c-" + it.id }) { c ->
                            ChatListRow(
                                title = c.title,
                                avatar = vm.engine.avatarOfConv(s, c), square = false,
                                preview = convPreview(s, c), time = c.messages.lastOrNull()?.ts ?: c.createdAt, unread = c.unread,
                                request = c.status == "request",
                                ticks = c.messages.lastOrNull()?.takeIf { it.from == s.me.address && c.kind == "dm" }?.status,
                                showDelivered = s.sendDelivered, showRead = s.sendRead,
                                onClick = { open(c) },
                            )
                        }
                    } else {
                        if (chans.isEmpty()) item { EmptyHint("Noch keine Kanäle", "Tippe auf ＋, um einen Kanal zu erstellen oder einem Kanal per Link beizutreten.") }
                        items(chans, key = { "k-" + it.id }) { c ->
                            ChatListRow(
                                title = c.title, avatar = c.avatar, square = true, preview = chanPreview(c),
                                time = c.posts.lastOrNull()?.ts ?: c.createdAt, unread = c.unread, request = false, ticks = null,
                                showDelivered = false, showRead = false,
                                onClick = { vm.run { vm.engine.markChannelRead(c.id) }; vm.go(Route.Channel(c.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (add) AddSheet(vm, onDismiss = { add = false }, onAction = { what, arg -> add = false; if (what == "join") joinLink = arg; dialog = what })
    when (dialog) {
        "group" -> NewGroupDialog(vm, s, onClose = { dialog = null }, onCreated = { id -> dialog = null; vm.go(Route.Chat(id)) })
        "channel" -> CreateChannelDialog(vm, onClose = { dialog = null }, onCreated = { id -> dialog = null; tab = 1; vm.go(Route.Channel(id)) })
        "join" -> JoinChannelDialog(vm, onClose = { dialog = null }, onJoined = { id -> dialog = null; tab = 1; vm.go(Route.Channel(id)) }, initial = joinLink)
        "security" -> SecurityDialog(vm, s, online, onClose = { dialog = null })
        "mycode" -> MyCodeDialog(vm, s, onClose = { dialog = null })
    }
}

@Composable
private fun TabLabel(text: String, unread: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text)
        if (unread > 0) { Spacer(Modifier.width(6.dp)); Badge(containerColor = LocalChatColors.current.unread) { Text(if (unread > 99) "99+" else unread.toString()) } }
    }
}

@Composable
private fun EmptyHint(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Eine Zeile der Chatliste im Messenger-Stil: Bild, Name, letzte Nachricht, Zeit, ungelesene Nachrichten. */
@Composable
private fun ChatListRow(
    title: String, avatar: String?, square: Boolean, preview: String, time: Long, unread: Int, request: Boolean,
    ticks: String?, showDelivered: Boolean, showRead: Boolean, onClick: () -> Unit,
) {
    val cc = LocalChatColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp).testTag("row_$title"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(title, avatar, 52.dp, square)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listTime(time), style = MaterialTheme.typography.labelSmall, color = if (unread > 0) cc.unread else cc.meta)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ticks != null) { StatusTicks(ticks, showDelivered, showRead); Spacer(Modifier.width(3.dp)) }
                Text(
                    preview, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (request) MaterialTheme.colorScheme.primary else cc.meta,
                )
                if (unread > 0 || request) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = CircleShape, color = cc.unread) {
                        Text(if (request) "neu" else if (unread > 99) "99+" else unread.toString(), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }
        }
    }
}

/**
 * „＋“: Link, Einladung oder Chat-Code einfügen/scannen – die App erkennt selbst, ob es ein Kontakt, ein Chat-Code oder ein Kanal ist –
 * oder etwas Neues erstellen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(vm: AppViewModel, onDismiss: () -> Unit, onAction: (String, String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    fun go(input: String) {
        val t = input.trim()
        Regex("#/join/[A-Za-z0-9_-]+").find(t)?.value?.let { onAction("join", it); return }
        busy = true; err = ""
        vm.run(onError = { err = it; busy = false }) {
            val isCode = t.length <= 80 && Regex("^[A-Za-z0-9][A-Za-z0-9_-]{2,39}(@[A-Za-z0-9.:-]+)?$").matches(t)
            val id = vm.engine.startChat(if (isCode) vm.engine.resolveChatCode(t) else decodeCard(t))
            busy = false
            onDismiss()
            vm.go(Route.Chat(id))
        }
    }
    val scan = rememberQrScanner("QR-Code scannen (Kontakt oder Kanal)") { text -> link = text; go(text) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Chat starten oder beitreten", style = MaterialTheme.typography.titleLarge)
            Text(
                "Füge einen Kontaktlink, Chat-Code (z. B. martinistcool, bei anderen Servern code@server) oder Kanal-Link ein – die App erkennt selbst, worum es sich handelt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(link, { link = it; err = "" }, placeholder = { Text("Link, Einladung oder Chat-Code") }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("add_input"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = scan) { Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scannen") }
                OutlinedButton(onClick = { clip.getText()?.text?.let { link = it } }) { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Einfügen") }
                Spacer(Modifier.weight(1f))
                Button(enabled = !busy && link.isNotBlank(), onClick = { go(link) }, modifier = Modifier.testTag("add_continue")) { Text(if (busy) "…" else "Weiter") }
            }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Neu erstellen", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SheetRow(Icons.Filled.Groups, "Neue Gruppe", "Mit bestehenden Kontakten", "add_group") { onAction("group", "") }
            SheetRow(Icons.Filled.Campaign, "Neuer Kanal", "Privat (verschlüsselt) oder öffentlich", "add_channel") { onAction("channel", "") }
            SheetRow(Icons.Filled.EditNote, "Notizen an mich", "Ablage für dich, auf allen deinen Geräten", "add_notes") {
                vm.run { val id = vm.engine.openSelfChat(); onDismiss(); vm.go(Route.Chat(id)) }
            }
            SheetRow(Icons.Filled.QrCodeScanner, "Mein Kontakt-QR-Code", "Andere scannen ihn, um dir zu schreiben", "add_mycode") { onAction("mycode", "") }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(sub) },
        leadingContent = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

/** Eigener Kontakt-QR-Code und Link zum Teilen. */
@Composable
private fun MyCodeDialog(vm: AppViewModel, s: AppState, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val link = vm.engine.contactLink(chat.engine.baseUrl(s.me.domain))
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mein Kontakt") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (link.isEmpty()) Text("Der Kontaktlink ist ausgeschaltet (Einstellungen → Profil & Kontakt).")
                else {
                    QrImage(link, 240.dp)
                    Text(s.me.address, style = MaterialTheme.typography.bodyMedium)
                    Text("Wer den Code scannt oder den Link öffnet, kann dir eine Chat-Anfrage senden. Du entscheidest, ob du annimmst.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (link.isNotEmpty()) TextButton(onClick = {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link)
                ctx.startActivity(android.content.Intent.createChooser(i, "Kontaktlink teilen"))
            }) { Text("Link teilen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Schließen") } },
    )
}

@Suppress("unused")
private val keepIcons = listOf(Icons.Filled.Done, Icons.Filled.DoneAll)
