package chat.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream

/** Rundes (bzw. quadratisches) Bild aus einer data-URL, sonst farbiger Kreis mit Initialen. */
@Composable
fun AvatarImage(name: String, dataUrl: String?, size: Dp = 40.dp, square: Boolean = false) {
    val shape = if (square) RoundedCornerShape(28) else CircleShape
    val bmp = remember(dataUrl) {
        dataUrl?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }?.let {
            runCatching { val b = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull()
        }
    }
    if (bmp != null) {
        Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
        return
    }
    var h = 0
    for (c in name) h = h * 31 + c.code
    val hue = (h.toUInt() % 360u).toFloat()
    val c1 = Color(AColor.HSVToColor(floatArrayOf(hue, 0.6f, 0.8f)))
    val c2 = Color(AColor.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.6f, 0.65f)))
    val initials = name.substringBefore('@').split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.ifEmpty { "?" }.uppercase()
    Box(Modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(c1, c2))), contentAlignment = Alignment.Center) {
        Text(initials, color = Color.White, fontSize = (size.value * 0.4f).sp)
    }
}

/** Bild auf ein kleines quadratisches JPEG (data-URL, ≤ ~14 KB) verkleinern. */
fun avatarFromUri(ctx: android.content.Context, uri: Uri, size: Int = 128): String {
    val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw IllegalStateException("Bild nicht lesbar.")
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, size, size, true)
    for (q in intArrayOf(85, 70, 55, 40)) {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (url.length <= 14 * 1024) return url
    }
    throw IllegalStateException("Bild ist zu detailreich.")
}

/** Zeile mit Vorschau, „Bild wählen“ und „Entfernen“. [onPick] erhält die data-URL bzw. null (entfernen). */
@Composable
fun AvatarPickerRow(name: String, current: String?, square: Boolean = false, label: String, onPick: (String?) -> Unit, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching { avatarFromUri(ctx, uri) }.onSuccess(onPick).onFailure { onError(it.message ?: "Bild ungültig.") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AvatarImage(name, current, 56.dp, square)
        TextButton(onClick = { pick.launch("image/*") }) { Text(label) }
        if (current != null) TextButton(onClick = { onPick(null) }) { Text("Entfernen") }
    }
}

/** QR-Code als Bild (schwarz auf weiß, damit er auch im Dunkelmodus scanbar bleibt). */
@Composable
fun QrImage(text: String, size: Dp = 240.dp) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
        b
    }
    Image(bmp.asImageBitmap(), contentDescription = "QR-Code", modifier = Modifier.size(size))
}

/** Startet den QR-Scanner (Kamera-Berechtigung fragt die Bibliothek selbst ab). [onResult] erhält den gelesenen Text. */
@Composable
fun rememberQrScanner(prompt: String = "QR-Code scannen", onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let(onResult) }
    return {
        launcher.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false),
        )
    }
}
