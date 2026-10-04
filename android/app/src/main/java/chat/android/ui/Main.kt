package chat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.engine.Conversation
import chat.engine.Part
import chat.engine.Phase

sealed interface Screen {
    data object List : Screen
    data class Chat(val id: String) : Screen
    data class Channel(val id: String) : Screen
    data object Settings : Screen
    data class Files(val convId: String?) : Screen
    data object Admin : Screen
}

@Composable
fun AppRoot(activity: FragmentActivity, onSecureChanged: () -> Unit) {
    val vm: AppViewModel = viewModel()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when (phase) {
            Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Phase.NoAccount -> OnboardingScreen(vm)
            Phase.Locked -> UnlockScreen(vm, activity)
            Phase.Unlocked -> MainScreen(vm, activity, onSecureChanged)
        }
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { vm.clearError() }, confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, activity: FragmentActivity, onSecureChanged: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var dialog by remember { mutableStateOf<String?>(null) } // new | group
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(online) { if (online) isAdmin = vm.engine.isAdmin() }
    val s = state ?: return
    if (!s.backupDone) BackupGate(vm, s)

    BackHandler(enabled = screen != Screen.List) { screen = Screen.List }

    when (val sc = screen) {
        is Screen.Chat -> {
            val conv = s.conversations[sc.id]
            if (conv == null) screen = Screen.List else ChatScreen(vm, s, conv, onBack = { screen = Screen.List }, onFiles = { screen = Screen.Files(conv.id) })
        }
        is Screen.Channel -> {
            val ch = s.channels[sc.id]
            if (ch == null) screen = Screen.List else ChannelScreen(vm, s, ch, onBack = { screen = Screen.List })
        }
        Screen.Admin -> AdminScreen(vm, onBack = { screen = Screen.List })
        is Screen.Files -> FilesScreen(vm, s, sc.convId, onBack = { screen = if (sc.convId != null) Screen.Chat(sc.convId) else Screen.List })
        Screen.Settings -> SettingsScreen(vm, activity, s, onBack = { screen = Screen.List }, onSecureChanged = onSecureChanged)
        Screen.List -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text(s.me.name); Text("${if (online) "● verbunden" else "○ offline"} · ${s.me.domain}", style = MaterialTheme.typography.bodySmall) } },
                    actions = {
                        TextButton(onClick = { dialog = "new" }) { Text("＋") }
                        TextButton(onClick = { dialog = "group" }) { Text("👥") }
                        TextButton(onClick = { dialog = "channel" }) { Text("📢") }
                        TextButton(onClick = { dialog = "join" }) { Text("🔗") }
                        if (isAdmin) TextButton(onClick = { screen = Screen.Admin }) { Text("🛠") }
                        TextButton(onClick = { screen = Screen.Files(null) }) { Text("📁") }
                        val sec = securityReport(s, online, vm.engine.info?.client_hash).first
                        TextButton(onClick = { dialog = "security" }) { Text("🛡", color = levelColor(sec)) }
                        TextButton(onClick = { screen = Screen.Settings }) { Text("⚙") }
                    },
                )
            },
        ) { pad ->
            val convs = s.conversations.values.sortedByDescending { it.messages.lastOrNull()?.ts ?: it.createdAt }
            val requests = convs.filter { it.status == "request" }
            val list = convs.filter { it.status != "request" }
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (s.alerts.isNotEmpty()) item {
                    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "⚠ Sicherheitshinweis: ${s.alerts[0].text}" + (if (s.alerts.size > 1) " (+${s.alerts.size - 1} weitere)" else ""),
                                Modifier.weight(1f).clickable { dialog = "security" }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { val ids = s.alerts.map { it.id }; vm.run { ids.forEach { vm.engine.dismissAlert(it) } } }) { Text("✕") }
                        }
                    }
                }
                if (requests.isNotEmpty()) {
                    item { Text("Anfragen", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge) }
                    items(requests, key = { it.id }) { c -> ConvRow(c, badge = "neu") { screen = Screen.Chat(c.id) } }
                    item { HorizontalDivider() }
                }
                if (list.isEmpty() && s.channels.isEmpty()) item {
                    Text(
                        "Noch keine Chats. Teile deinen Kontaktlink (⚙) oder öffne den Link eines Kontakts (＋).",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(s.channels.values.toList(), key = { "ch-" + it.id }) { ch ->
                    Column(Modifier.fillMaxWidth().clickable { vm.run { vm.engine.markChannelRead(ch.id) }; screen = Screen.Channel(ch.id) }.padding(16.dp, 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("📢 ${ch.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            if (ch.unread > 0) Text(ch.unread.toString(), color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            when (ch.me.status) { "pending" -> "Wartet auf Freigabe"; "banned" -> "Gesperrt"; else -> "Öffentlicher Kanal" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    HorizontalDivider()
                }
                items(list, key = { it.id }) { c ->
                    ConvRow(c, badge = if (c.unread > 0) c.unread.toString() else null) { vm.run { vm.engine.markRead(c.id) }; screen = Screen.Chat(c.id) }
                }
            }
        }
    }

    when (dialog) {
        "new" -> StartChatDialog(vm, onClose = { dialog = null }, onStarted = { id -> dialog = null; screen = Screen.Chat(id) })
        "channel" -> CreateChannelDialog(vm, onClose = { dialog = null }, onCreated = { id -> dialog = null; screen = Screen.Channel(id) })
        "join" -> JoinChannelDialog(vm, onClose = { dialog = null }, onJoined = { id -> dialog = null; screen = Screen.Channel(id) })
        "security" -> SecurityDialog(vm, s, online, onClose = { dialog = null })
        "group" -> NewGroupDialog(vm, s, onClose = { dialog = null }, onCreated = { id -> dialog = null; screen = Screen.Chat(id) })
    }
    LaunchedEffect(Unit) { /* Zustand wird vom ViewModel gehalten */ }
}

@Composable
private fun ConvRow(c: Conversation, badge: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text((if (c.kind == "group") "👥 " else "") + c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (badge != null) Text(badge, color = MaterialTheme.colorScheme.primary)
        }
        Text(preview(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    HorizontalDivider()
}

private fun preview(c: Conversation): String {
    if (c.status == "left") return "Verlassen"
    val m = c.messages.lastOrNull() ?: return ""
    if (m.deleted == true) return "Nachricht gelöscht"
    val p = m.parts.firstOrNull { it is Part.Text || it is Part.Code || it is Part.File } ?: return ""
    return when (p) {
        is Part.Text -> p.body.take(60)
        is Part.Code -> p.body.take(60)
        is Part.File -> "📎 ${p.name}"
        else -> ""
    }
}
