package chat.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// ---------- Zeitangaben ----------

private fun dayStart(ts: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun timeOfDay(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

/** Zeit in der Chatliste: heute → Uhrzeit, gestern → „Gestern“, diese Woche → Wochentag, sonst Datum. */
fun listTime(ts: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val d = dayStart(ts)
    return when {
        d == today -> timeOfDay(ts)
        today - d <= 86_400_000L -> "Gestern"
        today - d < 6 * 86_400_000L -> java.text.SimpleDateFormat("EEEE", java.util.Locale.GERMAN).format(Date(ts))
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
    }
}

/** Tagestrenner im Verlauf: „Heute“, „Gestern“ oder Datum. */
fun dayLabel(ts: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val d = dayStart(ts)
    return when {
        d == today -> "Heute"
        today - d <= 86_400_000L -> "Gestern"
        else -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(ts))
    }
}

fun sameDay(a: Long, b: Long) = dayStart(a) == dayStart(b)

@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 1.dp) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Sprechblase ----------

/** Stabile Farbe je Absender (Gruppen, Kanäle). */
fun senderColor(name: String): Color {
    val palette = listOf(0xFF1F7AEC, 0xFFD3396D, 0xFF2A9D8F, 0xFFE76F51, 0xFF7E57C2, 0xFF00897B, 0xFFC0632B, 0xFF5C6BC0)
    var h = 0
    for (c in name) h = h * 31 + c.code
    return Color(palette[Math.floorMod(h, palette.size)])
}

/** Sprechblase mit „Ecke“ zur Seite des Absenders (erste Blase einer Folge). */
@Composable
fun Bubble(mine: Boolean, first: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cc = LocalChatColors.current
    val r = 14.dp
    val shape = if (mine) RoundedCornerShape(topStart = r, topEnd = if (first) 3.dp else r, bottomEnd = r, bottomStart = r)
    else RoundedCornerShape(topStart = if (first) 3.dp else r, topEnd = r, bottomEnd = r, bottomStart = r)
    Surface(shape = shape, color = if (mine) cc.mine else cc.theirs, contentColor = if (mine) cc.onMine else cc.onTheirs, shadowElevation = 0.5.dp, modifier = modifier) {
        content()
    }
}

/** Status eigener Nachrichten als Häkchen (✓ gesendet, ✓✓ empfangen, blaue ✓✓ gelesen); nur wer selbst bestätigt, sieht sie. */
@Composable
fun StatusTicks(status: String, showDelivered: Boolean, showRead: Boolean) {
    val cc = LocalChatColors.current
    var st = status
    if (st == "read" && !showRead) st = if (showDelivered) "delivered" else "sent"
    if (st == "delivered" && !showDelivered) st = "sent"
    val (icon, tint, desc) = when (st) {
        "sending" -> Triple(Icons.Filled.Schedule, cc.meta, "sendet")
        "failed" -> Triple(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.error, "fehlgeschlagen")
        "read" -> Triple(Icons.Filled.DoneAll, cc.ticksRead, "gelesen")
        "delivered" -> Triple(Icons.Filled.DoneAll, cc.meta, "empfangen")
        else -> Triple(Icons.Filled.Done, cc.meta, "gesendet")
    }
    Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(15.dp))
}

/** Hinweisleiste (Warnung, Info) über dem Verlauf. */
@Composable
fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}

// ---------- Eingabe ----------

/** Eingabezeile im Messenger-Stil: abgerundetes Feld mit Anhängen-Knopf, runder Sendeknopf, optionale Antwort-/Bearbeiten-Leiste. */
@Composable
fun ComposerBar(
    text: String, onText: (String) -> Unit, placeholder: String,
    files: List<Uri>, fileName: (Uri) -> String, onRemoveFile: (Uri) -> Unit,
    codeMode: Boolean, lang: String, onLang: (String) -> Unit,
    busy: Boolean, onAttach: () -> Unit, onSend: () -> Unit,
    replyTitle: String? = null, replySnippet: String = "", onCancelReply: () -> Unit = {}, flags: String = "",
) {
    val canSend = !busy && (text.isNotBlank() || files.isNotEmpty())
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 1.dp, modifier = Modifier.weight(1f)) {
                Column {
                    if (replyTitle != null) ReplyStrip(replyTitle, replySnippet, onCancelReply)
                    if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        files.forEach { u ->
                            InputChip(selected = false, onClick = { onRemoveFile(u) }, label = { Text(fileName(u), maxLines = 1) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Entfernen", Modifier.size(16.dp)) })
                        }
                    }
                    if (flags.isNotEmpty()) Text(flags, Modifier.padding(start = 16.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (codeMode) TextField(
                        value = lang, onValueChange = onLang, placeholder = { Text("Sprache (optional)") }, singleLine = true,
                        colors = transparentFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        TextField(
                            value = text, onValueChange = onText, placeholder = { Text(placeholder) },
                            minLines = 1, maxLines = 6, colors = transparentFieldColors(),
                            textStyle = if (codeMode) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("composer_input"),
                        )
                        IconButton(onClick = onAttach, modifier = Modifier.testTag("composer_attach")) { Icon(Icons.Filled.AttachFile, contentDescription = "Anhängen") }
                    }
                }
            }
            FilledIconButton(
                onClick = onSend, enabled = canSend, modifier = Modifier.size(52.dp).testTag("composer_send"),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Senden") }
        }
    }
}

@Composable
fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
)

/** Leiste über dem Eingabefeld: worauf geantwortet bzw. was bearbeitet wird. */
@Composable
fun ReplyStrip(title: String, snippet: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(snippet, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Abbrechen") }
    }
}

class SheetAction(val icon: ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

/** Auswahl im unteren Bereich (Anhängen, Aktionen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit, header: (@Composable () -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            if (title != null) Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            header?.invoke()
            actions.forEach { a ->
                ListItem(
                    headlineContent = { Text(a.label) },
                    leadingContent = {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Icon(a.icon, contentDescription = null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    modifier = Modifier.clickable { a.onClick() }.testTag(a.tag),
                )
            }
        }
    }
}

/** Standard-Anhänge: Fotos/Videos, Dateien, Codeblock, (nur 1:1) Einmal-Nachricht. */
fun attachActions(onMedia: () -> Unit, onFiles: () -> Unit, codeMode: Boolean, onCode: () -> Unit, once: Boolean?, onOnce: () -> Unit): List<SheetAction> = buildList {
    add(SheetAction(Icons.Filled.PhotoLibrary, "Fotos & Videos", "attach_media", onMedia))
    add(SheetAction(Icons.Filled.Description, "Datei", "attach_file", onFiles))
    add(SheetAction(Icons.Filled.Code, if (codeMode) "Codeblock ausschalten" else "Codeblock", "attach_code", onCode))
    if (once != null) add(SheetAction(Icons.Filled.Lock, if (once) "Einmal-Nachricht ausschalten" else "Einmal-Nachricht (nach dem Lesen gelöscht)", "attach_once", onOnce))
}
