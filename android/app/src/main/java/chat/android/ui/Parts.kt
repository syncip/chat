package chat.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
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

fun formatBytes(n: Long): String {
    val u = listOf("B", "KB", "MB", "GB")
    var v = n.toDouble(); var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 10 || i == 0) "%.0f %s".format(v, u[i]) else "%.1f %s".format(v, u[i])
}

/** Inhalte einer Nachricht (Text, einklappbarer Code, Zitat, Datei). */
@Composable
fun PartsView(vm: AppViewModel, parts: List<Part>) {
    parts.forEach { p ->
        when (p) {
            is Part.Quote -> Row(Modifier.padding(bottom = 4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(6.dp))) {
                Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                Text(p.snippet, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            is Part.Text -> Text(renderInline(p.body))
            is Part.Code -> CodeBlock(p)
            is Part.File -> FileCard(vm, p)
        }
    }
}

@Composable
private fun CodeBlock(p: Part.Code) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val lines = p.body.count { it == '\n' } + 1
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(
                (if (open) "▾ " else "▸ ") + "Code" + (if (p.lang.isNotEmpty()) " (${p.lang})" else "") + " · $lines Zeilen",
                Modifier.clickable { open = !open }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                Box(Modifier.horizontalScroll(rememberScrollState())) { Text(p.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { copySensitive(ctx, p.body) }) { Text("Kopieren") }
            }
        }
    }
}

private val IMAGE_RE = Regex("^image/(png|jpeg|gif|webp)$")
private val AUDIO_RE = Regex("^audio/(mpeg|mp3|ogg|wav|x-wav|webm|aac|flac|mp4|x-m4a)$")
private val VIDEO_RE = Regex("^video/(mp4|webm|ogg|quicktime)$")
private const val AUTO_IMAGE_MAX = 15L * 1024 * 1024

/** Dekodierte Bilder, nur im Arbeitsspeicher (kein Klartext auf dem Datenträger). */
private object BitmapCache {
    val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)
}

private fun fileIcon(name: String, mime: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("audio/") -> "🎵"
        mime.startsWith("video/") -> "🎬"
        ext == "pdf" -> "📕"
        ext in listOf("doc", "docx", "odt", "rtf", "txt", "md") -> "📄"
        ext in listOf("xls", "xlsx", "csv", "ods") -> "📊"
        ext in listOf("zip", "7z", "rar", "tar", "gz") -> "🗜️"
        ext in listOf("exe", "msi", "apk", "bat", "sh", "dll") -> "⚙️"
        else -> "📎"
    }
}

/** Datei in einer Nachricht: Bilder direkt angezeigt, Audio/Video per Klick abgespielt (nur im Speicher), sonst Datei-Karte. */
@Composable
fun FileCard(vm: AppViewModel, p: Part.File, compact: Boolean = false) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf(BitmapCache.cache.get(p.blob_id)) }
    var media by remember { mutableStateOf<ByteArray?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var zoom by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pending
        if (uri != null && data != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
        pending = null
    }
    val isImg = IMAGE_RE.matches(p.mime)
    val isAudio = AUDIO_RE.matches(p.mime)
    val isVideo = VIDEO_RE.matches(p.mime)

    fun load(then: (ByteArray) -> Unit) {
        busy = true
        vm.run(onError = { vm.showError(it); busy = false }) {
            val data = vm.engine.downloadFile(p)
            then(data)
            busy = false
        }
    }
    fun showImage(data: ByteArray) {
        BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()?.let { BitmapCache.cache.put(p.blob_id, it); image = it }
    }

    LaunchedEffect(p.blob_id) {
        if (isImg && image == null && p.size <= AUTO_IMAGE_MAX) load { showImage(it) }
    }

    Column(Modifier.padding(vertical = 4.dp)) {
        if (isImg) {
            val img = image
            if (img != null) Image(
                img, contentDescription = p.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.widthIn(max = 280.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).clickable { zoom = true },
            )
            else if (busy) Text("Lädt …", style = MaterialTheme.typography.labelMedium)
            else TextButton(onClick = { load { showImage(it) } }) { Text("Bild laden (${formatBytes(p.size)})") }
        }
        if (isAudio || isVideo) {
            val m = media
            if (m != null) MediaPlayerView(m, video = isVideo)
            else OutlinedButton(enabled = !busy, onClick = { load { media = it } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Lädt …" else if (isVideo) "▶ Video abspielen" else "▶ Audio abspielen")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fileIcon(p.name, p.mime))
            Text(p.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(formatBytes(p.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = {
                load { data -> pending = data; save.launch(p.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")) }
            }) { Text("Speichern") }
        }
        if (!compact) Text("Von ${p.blob_server} geladen und lokal entschlüsselt · wird nie ausgeführt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (zoom) image?.let { img ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { zoom = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            ZoomableImage(img, p.name, onClose = { zoom = false })
        }
    }
}

/** Liest ausgewählte Dateien (Größenlimit vorab per Metadaten prüfen). */
internal fun readAttachments(ctx: android.content.Context, uris: List<Uri>, limit: Long): List<Engine.Attachment> = uris.map { uri ->
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

/** Anzeigename einer ausgewählten Datei. */
internal fun displayName(ctx: android.content.Context, uri: Uri): String {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { return c.getString(it) }
    }
    return uri.lastPathSegment ?: "Datei"
}

/** Vollbildansicht eines Bildes mit Zoomen (zwei Finger) und Verschieben; Tippen schließt. */
@Composable
private fun ZoomableImage(img: androidx.compose.ui.graphics.ImageBitmap, name: String, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Image(
            img, contentDescription = name,
            modifier = Modifier.fillMaxWidth().transformable(state).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}
