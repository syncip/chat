package chat.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.ChPost
import chat.engine.ChannelMember
import chat.engine.ChannelPolicy
import chat.engine.ChannelPreview
import chat.engine.ChannelState
import chat.engine.NeedsCaptcha
import chat.engine.Part
import chat.engine.baseUrl
import java.util.Base64

private val ROLE = mapOf("owner" to "Besitzer", "mod" to "Moderator", "write" to "Schreiben", "member" to "Mitglied", "read" to "Nur lesen")

private fun fmtDur(s: Long): String = when {
    s <= 0 -> "keine"
    s < 3600 -> "${s / 60} Min"
    s < 86400 -> "${s / 3600} Std"
    else -> "${s / 86400} Tage"
}

/** Eingabe einer Dauer als Zahl in der gewählten Einheit (Minuten/Stunden/Tage); liefert Sekunden. */
@Composable
private fun DurationField(label: String, seconds: Long, onChange: (Long) -> Unit) {
    var unit by remember { mutableStateOf(if (seconds > 0 && seconds % 86400 == 0L) 86400L else if (seconds > 0 && seconds % 3600 == 0L) 3600L else 60L) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = (seconds / unit).toString(), onValueChange = { v -> onChange((v.filter { it.isDigit() }.take(5).toLongOrNull() ?: 0L) * unit) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            listOf(60L to "Min", 3600L to "Std", 86400L to "Tage").forEach { (u, n) ->
                FilterChip(selected = unit == u, onClick = { val v = seconds / unit; unit = u; onChange(v * u) }, label = { Text(n) })
            }
        }
    }
}

@Composable
private fun PolicyForm(p: ChannelPolicy, onChange: (ChannelPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Beitritt", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("open" to "Direkt", "approval" to "Freigabe", "pow" to "Proof-of-Work", "captcha" to "Captcha").forEach { (m, n) ->
                FilterChip(selected = p.join_mode == m, onClick = { onChange(p.copy(join_mode = m)) }, label = { Text(n) })
            }
        }
        if (p.join_mode == "pow") {
            Text("Schwierigkeit (Bits, 8–24)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = p.pow_bits.toString(), onValueChange = { v -> onChange(p.copy(pow_bits = (v.filter { it.isDigit() }.take(2).toIntOrNull() ?: 8).coerceIn(8, 24))) },
                singleLine = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = p.members_can_write, onCheckedChange = { onChange(p.copy(members_can_write = it)) })
            Text("Alle Mitglieder dürfen schreiben (sonst nur lesen; Schreibrechte einzeln vergeben)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        DurationField("Neue Mitglieder dürfen erst schreiben nach", p.probation_seconds) { onChange(p.copy(probation_seconds = it)) }
        DurationField("Mindestabstand zwischen Beiträgen (Slow-Mode)", p.slow_mode_seconds) { onChange(p.copy(slow_mode_seconds = it)) }
    }
}

@Composable
fun CreateChannelDialog(vm: AppViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf(ChannelPolicy()) }
    var isPublic by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Neuer Kanal") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Jeder mit dem Link kann beitreten. Der Kanalschlüssel steht nur im Link (hinter dem #) und wird nie an den Server gesendet. Der Server sieht keine Inhalte, erzwingt aber Rechte und Sperren.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPublic, onCheckedChange = { isPublic = it })
                    Text("Öffentlich sichtbar – ohne Konto lesbar und verfolgbar", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                if (isPublic) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "⚠ Öffentlicher Kanal: Alle Beiträge sind unverschlüsselt und für jeden im Internet lesbar (auch ohne Konto), der Server kann alles mitlesen. Das lässt sich später nicht ändern. Schreiben dürfen weiterhin nur Mitglieder mit Konto.",
                        Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
                PolicyForm(policy) { policy = it }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && title.isNotBlank(), onClick = {
                busy = true; err = ""
                vm.run(onError = { err = it; busy = false }) { val id = vm.engine.createChannel(title, policy, isPublic); busy = false; onCreated(id) }
            }) { Text(if (busy) "…" else "Kanal erstellen") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@Composable
fun JoinChannelDialog(vm: AppViewModel, onClose: () -> Unit, onJoined: (String) -> Unit, initial: String = "") {
    var link by remember { mutableStateOf(initial) }
    var info by remember { mutableStateOf<ChannelPreview?>(null) }
    var captcha by remember { mutableStateOf<NeedsCaptcha?>(null) }
    var answer by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    LaunchedEffect(Unit) { if (initial.isNotBlank()) vm.run(onError = { err = it }) { info = vm.engine.previewChannel(initial) } }
    val modeText = mapOf(
        "open" to "Direkter Zugriff.", "approval" to "Die Moderation muss dich freigeben.",
        "pow" to "Dein Gerät löst beim Beitritt eine kleine Rechenaufgabe (kann einige Sekunden dauern).", "captcha" to "Du musst ein Captcha lösen.",
    )
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Kanal beitreten") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = link, onValueChange = { link = it; info = null; captcha = null }, minLines = 2, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://…/#/join/…") })
                Row {
                    TextButton(onClick = { clip.getText()?.text?.let { link = it; info = null; captcha = null } }) { Text("Einfügen") }
                    TextButton(enabled = link.isNotBlank() && !busy, onClick = {
                        err = ""
                        vm.run(onError = { err = it }) { info = vm.engine.previewChannel(link) }
                    }) { Text("Prüfen") }
                }
                info?.let { i ->
                    Text("${i.title} · ${i.members} Mitglieder · Server ${i.server}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        (modeText[i.policy.join_mode] ?: "") +
                            (if (i.policy.probation_seconds > 0) " Neue Mitglieder dürfen erst nach ${fmtDur(i.policy.probation_seconds)} schreiben." else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Mit dem Beitritt sieht der Kanal deine Adresse.", style = MaterialTheme.typography.bodySmall)
                }
                captcha?.let { c ->
                    val bytes = Base64.getDecoder().decode(c.imagePngBase64)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Image(it.asImageBitmap(), contentDescription = "Captcha") }
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("Zahl eingeben") }, singleLine = true)
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = info != null && !busy && (captcha == null || answer.isNotBlank()), onClick = {
                busy = true; err = ""
                vm.run(onError = { err = it; busy = false; captcha = null }) {
                    try {
                        val id = vm.engine.joinChannel(link, captcha?.token, captcha?.let { answer })
                        busy = false
                        onJoined(id)
                    } catch (c: NeedsCaptcha) {
                        captcha = c; answer = ""; busy = false
                    }
                }
            }) { Text(if (busy) "Bitte warten …" else if (captcha != null) "Absenden" else "Beitreten") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(vm: AppViewModel, s: AppState, ch: ChannelState, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var codeMode by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(listOf<android.net.Uri>()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { files = files + it }
    val listState = rememberLazyListState()
    val isMod = ch.me.status == "active" && (ch.me.role == "owner" || ch.me.role == "mod")

    LaunchedEffect(ch.posts.size) {
        if (ch.posts.isNotEmpty()) listState.animateScrollToItem(ch.posts.size - 1)
        vm.engine.markChannelRead(ch.id)
    }

    val blocker = when {
        ch.me.status == "banned" -> "Du wurdest aus diesem Kanal gesperrt."
        ch.me.status == "pending" -> "Dein Beitritt muss noch von der Moderation freigegeben werden."
        ch.me.can_write -> ""
        ch.me.role == "read" -> "In diesem Kanal darfst du nur lesen."
        ch.me.muted_until * 1000 > System.currentTimeMillis() -> "Du bist stummgeschaltet bis ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ch.me.muted_until * 1000))}."
        !ch.policy.members_can_write && ch.me.role != "write" -> "In diesem Kanal darfst du nur lesen."
        else -> "Neue Mitglieder dürfen erst nach ${fmtDur(ch.policy.probation_seconds)} schreiben."
    }

    fun send() {
        busy = true
        vm.run(onError = { vm.showError(it); busy = false }) {
            val atts = readAttachments(ctx, files, vm.engine.info?.limits?.max_file_size ?: Long.MAX_VALUE)
            vm.engine.postToChannel(ch.id, if (codeMode) null else text, if (codeMode) lang to text else null, atts)
            text = ""; files = emptyList(); codeMode = false; busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AvatarImage(ch.title, ch.avatar, 36.dp, square = true)
                        Column { Text("📢 ${ch.title}", maxLines = 1); Text("${ROLE[ch.me.role] ?: ch.me.role} · ${ch.server}", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { TextButton(onClick = { info = true }) { Text("ⓘ") } },
            )
        },
        modifier = Modifier.imePadding(),
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (ch.policy.isPublic) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text("🌐 Öffentlicher Kanal: Inhalte sind unverschlüsselt und für jeden ohne Konto lesbar.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
            var pendingN by remember { mutableStateOf(0) }
            var dismissedN by remember { mutableStateOf(0) }
            LaunchedEffect(ch.id, isMod, ch.policy.join_mode, ch.events.size) {
                if (!isMod || ch.policy.join_mode == "open") { pendingN = 0; return@LaunchedEffect }
                while (true) {
                    runCatching { pendingN = vm.engine.channelMembers(ch.id, "pending").size }
                    kotlinx.coroutines.delay(20_000)
                }
            }
            if (pendingN > dismissedN) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "👋 " + (if (pendingN == 1) "1 Person wartet" else "$pendingN Personen warten") + " auf Freigabe für diesen Kanal.",
                        Modifier.weight(1f).clickable { info = true }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { info = true }) { Text("Prüfen") }
                    TextButton(onClick = { dismissedN = pendingN }) { Text("✕") }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ch.posts, key = { it.id }) { p -> PostRow(vm, ch, p, mine = p.from == s.me.address, isMod = isMod) }
            }
            HorizontalDivider()
            if (ch.needsKey) {
                var newLink by remember { mutableStateOf("") }
                var keyErr by remember { mutableStateOf("") }
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Dieser Kanal ist jetzt privat. Füge den neuen Einladungslink des Besitzers ein, um weiterzulesen.", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(newLink, { newLink = it }, label = { Text("Neuer Einladungslink") }, modifier = Modifier.fillMaxWidth())
                    if (keyErr.isNotEmpty()) Text(keyErr, color = MaterialTheme.colorScheme.error)
                    Button(enabled = newLink.isNotBlank(), onClick = { vm.run(onError = { keyErr = it }) { vm.engine.rekeyChannel(ch.id, newLink); newLink = "" } }) { Text("Schlüssel übernehmen") }
                }
            } else if (blocker.isNotEmpty()) {
                Text(blocker, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { attachMenu = true }, modifier = Modifier.height(52.dp)) { Text("＋", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("📎 Datei / Foto anhängen") }, onClick = { attachMenu = false; pick.launch("*/*") })
                            DropdownMenuItem(text = { Text(if (codeMode) "</> Codeblock ausschalten" else "</> Codeblock") }, onClick = { attachMenu = false; codeMode = !codeMode })
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            files.forEach { u -> androidx.compose.material3.FilterChip(selected = true, onClick = { files = files - u }, label = { Text(u.lastPathSegment ?: "Datei") }) }
                        }
                        if (codeMode) OutlinedTextField(value = lang, onValueChange = { lang = it }, placeholder = { Text("Sprache") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            minLines = 1, maxLines = 6, shape = RoundedCornerShape(26.dp),
                            placeholder = { Text(if (codeMode) "Code …" else "Beitrag …") },
                            textStyle = if (codeMode) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Button(
                        enabled = !busy && (text.isNotBlank() || files.isNotEmpty()), onClick = { send() },
                        shape = androidx.compose.foundation.shape.CircleShape, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.size(52.dp),
                    ) { Text(if (busy) "…" else "➤", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
    if (info) ChannelInfoDialog(vm, ch, onClose = { info = false }, onGone = { info = false; onBack() })
}

@Composable
private fun PostRow(vm: AppViewModel, ch: ChannelState, p: ChPost, mine: Boolean, isMod: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
            onClick = { if ((isMod || (mine && p.hook == null)) && !p.deleted) menu = true },
        ) {
            Column(Modifier.padding(10.dp, 6.dp)) {
                Text(if (p.hook != null) "🔔 ${p.hook} (Webhook)" else p.from, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                when {
                    p.deleted -> Text("Beitrag entfernt", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.bad -> Text("Beitrag konnte nicht verifiziert werden", color = MaterialTheme.colorScheme.error)
                    else -> PartsView(vm, p.parts)
                }
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(p.ts)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Beitrag löschen") }, onClick = { menu = false; vm.run { if (mine) vm.engine.deleteOwnFiles(p.id, chanId = ch.id) else vm.engine.channelMod(ch.id, "delete", postId = p.id) } })
            if (isMod && !mine && p.hook == null) {
                DropdownMenuItem(text = { Text("Autor sperren") }, onClick = { menu = false; vm.run { vm.engine.channelMod(ch.id, "ban", target = p.ik) } })
                DropdownMenuItem(text = { Text("Autor 1 Std stummschalten") }, onClick = { menu = false; vm.run { vm.engine.channelMod(ch.id, "timeout", target = p.ik, seconds = 3600) } })
            }
        }
    }
}

@Composable
private fun ChannelInfoDialog(vm: AppViewModel, ch: ChannelState, onClose: () -> Unit, onGone: () -> Unit) {
    val ctx = LocalContext.current
    val isOwner = ch.me.role == "owner"
    val isMod = isOwner || ch.me.role == "mod"
    var members by remember { mutableStateOf<List<ChannelMember>>(emptyList()) }
    var policy by remember { mutableStateOf(ch.policy) }
    var title by remember { mutableStateOf(ch.title) }
    var err by remember { mutableStateOf("") }
    var linkText by remember { mutableStateOf("") }
    var publicLink by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var pub by remember { mutableStateOf(ch.policy.isPublic) }
    val dirty = isOwner && (title != ch.title || pub != ch.policy.isPublic || policy.copy(isPublic = false) != ch.policy.copy(isPublic = false))
    LaunchedEffect(ch.id, ch.key) {
        runCatching { linkText = vm.engine.channelLink(ch.id, baseUrl(ch.server)) }
        if (ch.policy.isPublic) runCatching { publicLink = vm.engine.channelPublicLink(ch.id, baseUrl(ch.server)) }
    }
    LaunchedEffect(ch.id, isMod) { if (isMod) runCatching { members = vm.engine.channelMembers(ch.id) } }
    fun run(f: suspend () -> Unit) {
        err = ""
        vm.run(onError = { err = it }) { f(); if (isMod) members = vm.engine.channelMembers(ch.id) }
    }
    fun act(m: ChannelMember, action: String, role: String? = null, seconds: Long? = null) = run { vm.engine.channelMod(ch.id, action, target = m.ik, role = role, seconds = seconds) }

    AlertDialog(
        onDismissRequest = onClose,
        // Speichern steht immer sichtbar unten (nicht im scrollbaren Bereich)
        confirmButton = {
            if (dirty) Button(onClick = { run { vm.engine.updateChannel(ch.id, title, policy, makePublic = pub); saved = true } }) { Text("Speichern") }
            else TextButton(onClick = onClose) { Text("Schließen") }
        },
        dismissButton = { if (dirty) TextButton(onClick = { title = ch.title; policy = ch.policy; pub = ch.policy.isPublic }) { Text("Verwerfen") } },
        title = { Text(ch.title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isOwner) AvatarPickerRow(ch.title, ch.avatar, square = true, label = "Kanalbild wählen", onPick = { d -> run { vm.engine.updateChannel(ch.id, null, ch.policy, avatar = d ?: "") } }, onError = { err = it })
                if (dirty) Text("Ungespeicherte Änderungen – unten „Speichern“ tippen.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                else if (saved) Text("✔ Gespeichert", color = levelColor(Level.Ok), style = MaterialTheme.typography.bodySmall)
                if (ch.policy.isPublic) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text("🌐 Dieser Kanal ist öffentlich: Beiträge sind unverschlüsselt und für jeden ohne Konto lesbar.", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Öffentlicher Lese-Link (ohne Konto)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = publicLink, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { copySensitive(ctx, publicLink) }) { Text("Lese-Link kopieren") }
                }
                Text("Einladungslink", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Der Link enthält den Kanalschlüssel. Nach einer Sperre kennt die Person den Schlüssel weiterhin, der Server verweigert ihr aber Lesen und Schreiben.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = linkText, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copySensitive(ctx, linkText) }) { Text("Kopieren") }
                    OutlinedButton(onClick = {
                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, linkText)
                        ctx.startActivity(android.content.Intent.createChooser(i, "Kanal-Link teilen"))
                    }) { Text("Teilen") }
                }
                if (isOwner) {
                    Text("Einstellungen (global)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Sichtbarkeit", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !pub, onClick = { pub = false; saved = false }, label = { Text("Privat (verschlüsselt)") })
                        FilterChip(selected = pub, onClick = { pub = true; saved = false }, label = { Text("Öffentlich") })
                    }
                    if (pub != ch.policy.isPublic) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                if (pub) "🌐 Nach dem Speichern sind neue Beiträge für jeden im Internet lesbar. Ältere verschlüsselte Beiträge bleiben verschlüsselt."
                                else "🔒 Nach dem Speichern gibt es einen neuen Schlüssel: Der öffentliche Link funktioniert nicht mehr, Mitglieder brauchen den neuen Einladungslink (siehe oben nach dem Speichern). Ältere öffentliche Beiträge bleiben auf dem Server im Klartext.",
                                Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    PolicyForm(policy) { policy = it; saved = false }
                }
                if (isMod) HooksSection(vm, ch)
                if (isMod) {
                    Text("Mitglieder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    members.forEach { m ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            val muted = m.muted_until * 1000 > System.currentTimeMillis()
                            Text(m.address + " · " + (ROLE[m.role] ?: m.role) + (if (m.status == "pending") " · wartet" else if (m.status == "banned") " · gesperrt" else "") + (if (muted) " · stumm" else ""))
                            if (m.role != "owner" && (isOwner || m.role != "mod")) {
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    when (m.status) {
                                        "pending" -> {
                                            TextButton(onClick = { act(m, "approve") }) { Text("Freigeben") }
                                            TextButton(onClick = { act(m, "reject") }) { Text("Ablehnen") }
                                        }
                                        "banned" -> TextButton(onClick = { act(m, "unban") }) { Text("Entsperren") }
                                        else -> {
                                            TextButton(onClick = { act(m, "role", role = "write") }) { Text("Schreiben") }
                                            TextButton(onClick = { act(m, "role", role = "read") }) { Text("Nur lesen") }
                                            TextButton(onClick = { act(m, "role", role = "member") }) { Text("Global") }
                                            if (isOwner) TextButton(onClick = { act(m, "role", role = "mod") }) { Text("Moderator") }
                                            TextButton(onClick = { act(m, "timeout", seconds = if (muted) 0L else 3600L) }) { Text(if (muted) "Stumm aufheben" else "1 Std stumm") }
                                            TextButton(onClick = { act(m, "kick") }) { Text("Entfernen") }
                                            TextButton(onClick = { act(m, "ban") }) { Text("Sperren") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Protokoll", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                ch.events.takeLast(20).reversed().forEach { ev ->
                    Text(
                        "${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(ev.ts))} · ${ev.actor} · ${ev.kind}" +
                            (if (ev.targetAddress.isNotEmpty()) " → ${ev.targetAddress}" else ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isOwner) TextButton(onClick = { run { vm.engine.leaveChannel(ch.id) }; onGone() }) { Text("Kanal verlassen") }
                    if (isOwner) TextButton(onClick = { run { vm.engine.deleteChannel(ch.id) }; onGone() }) { Text("Kanal löschen", color = MaterialTheme.colorScheme.error) }
                }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

/** Webhooks: Andere Apps senden per HTTP Nachrichten in den Kanal (ntfy-kompatibel, siehe docs/NTFY.md). */
@Composable
private fun HooksSection(vm: AppViewModel, ch: ChannelState) {
    val ctx = LocalContext.current
    var hooks by remember { mutableStateOf<List<chat.engine.ChHook>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var created by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(ch.id) { runCatching { hooks = vm.engine.channelHooks(ch.id) } }
    val base = baseUrl(ch.server)
    Text("Webhooks (ntfy-kompatibel)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    TextButton(onClick = { help = !help }) { Text(if (help) "▾ Erklärung ausblenden" else "▸ Was ist das? So funktioniert es") }
    if (help) {
        Text(
            "Mit einem Webhook können andere Programme (Skripte, Monitoring, Router, Home Assistant, CI …) per HTTP eine Benachrichtigung in diesen Kanal schicken – genau wie bei ntfy. " +
                "Jeder Webhook hat eine eigene geheime Adresse; wer sie kennt, kann in den Kanal schreiben. Löschst du den Webhook, ist die Adresse sofort wertlos.\n\n" +
                "curl -d \"Backup fertig\" $base/h/<TOKEN>\n" +
                "curl -H \"Title: Nachtlauf\" -H \"Priority: high\" -H \"Tags: white_check_mark\" -d \"Alles gesichert\" $base/h/<TOKEN>\n\n" +
                "Unterstützt: Nachricht, Title/t, Priority/p (1–5), Tags/ta, Click, als Header oder URL-Parameter, JSON-Body sowie GET …/publish, /send, /trigger. " +
                "Nicht unterstützt: Anhänge, Aktionsknöpfe, Zeitplanung, Abonnieren über ntfy-Clients.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (ch.policy.isPublic) Text("Dieser Kanal ist öffentlich, die Nachrichten sind ohnehin lesbar.", style = MaterialTheme.typography.bodySmall)
        else Text(
            "⚠ Nicht öffentliche Kanäle sind Ende-zu-Ende-verschlüsselt. Damit der Server Webhook-Nachrichten verschlüsseln kann, bekommt er beim Anlegen den Kanalschlüssel. " +
                "Der Server (und wer den Webhook-Absender kontrolliert) sieht diese Nachrichten dann im Klartext, und der Server könnte den Kanal mitlesen.",
            color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall,
        )
    }
    hooks.forEach { h ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔔 ${h.name}", Modifier.weight(1f))
            TextButton(onClick = { vm.run(onError = { err = it }) { vm.engine.deleteChannelHook(ch.id, h.id); created = null; hooks = vm.engine.channelHooks(ch.id) } }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (hooks.isEmpty()) Text("Noch keine Webhooks.", style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, placeholder = { Text("Name, z. B. Monitoring") }, singleLine = true, modifier = Modifier.weight(1f))
        Button(enabled = name.isNotBlank(), onClick = { vm.run(onError = { err = it }) { created = vm.engine.createChannelHook(ch.id, name.trim()); name = ""; hooks = vm.engine.channelHooks(ch.id) } }) { Text("Anlegen") }
    }
    created?.let { url ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(8.dp)) {
                Text("Adresse jetzt kopieren – sie wird nicht wieder angezeigt:", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = url, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { copySensitive(ctx, url) }) { Text("Kopieren") }
            }
        }
    }
    if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
}
