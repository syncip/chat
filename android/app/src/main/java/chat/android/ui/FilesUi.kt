package chat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import chat.engine.AppState
import chat.engine.Part

class FileEntry(val part: Part.File, val from: String, val ts: Long, val convTitle: String, val msgId: String)

/** Alle Dateien aus den Chats (oder nur einem Chat), neueste zuerst. Einmal-Nachrichten und gelöschte Nachrichten zählen nicht. */
fun collectFiles(s: AppState, convId: String?): List<FileEntry> {
    val out = mutableListOf<FileEntry>()
    for (c in s.conversations.values) {
        if (convId != null && c.id != convId) continue
        for (m in c.messages) {
            if (m.deleted == true || m.once == true) continue
            for (p in m.parts) if (p is Part.File) out.add(FileEntry(p, m.from, m.ts, c.title, m.id))
        }
    }
    return out.sortedByDescending { it.ts }
}

private fun kindOf(p: Part.File): String = when {
    p.mime.startsWith("image/") -> "images"
    p.mime.startsWith("audio/") || p.mime.startsWith("video/") -> "media"
    else -> "docs"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, s: AppState, convId: String?, onBack: () -> Unit) {
    val all = remember(s, convId) { collectFiles(s, convId) }
    var tab by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    val shown = all.filter { (tab == "all" || kindOf(it.part) == tab) && (q.isBlank() || it.part.name.contains(q, ignoreCase = true)) }
    val tabs = listOf("all" to "Alle", "images" to "Bilder", "media" to "Audio & Video", "docs" to "Dokumente")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (convId != null) "Dateien in diesem Chat" else "Alle Dateien") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { (t, label) ->
                    val n = all.count { t == "all" || kindOf(it.part) == t }
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text("$label $n") })
                }
            }
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Dateiname suchen …") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (shown.isEmpty()) Text("Keine Dateien.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.msgId + it.part.blob_id }) { f ->
                    Column {
                        FileCard(vm, f.part, compact = true)
                        Text(
                            f.from.substringBefore('@') + (if (convId == null) " · ${f.convTitle}" else "") + " · " +
                                java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(f.ts)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
