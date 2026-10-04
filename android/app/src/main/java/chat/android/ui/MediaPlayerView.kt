package chat.android.ui

import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Spielt entschlüsselte Bytes direkt aus dem Speicher ab (es wird keine Klartextdatei angelegt). */
private class BytesDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = data.size.toLong()
    override fun close() {}
}

@Composable
fun MediaPlayerView(data: ByteArray, video: Boolean) {
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val player = remember(data) {
        MediaPlayer().apply {
            try {
                setDataSource(BytesDataSource(data))
                setOnPreparedListener { it.start(); playing = true }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ -> failed = true; true }
            } catch (e: Exception) {
                failed = true
            }
        }
    }
    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    if (!video) LaunchedEffect(player) { if (!failed) runCatching { player.prepareAsync() }.onFailure { failed = true } }

    if (video) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            if (failed) return
                            runCatching { player.setSurface(Surface(st)); player.prepareAsync() }.onFailure { failed = true }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }
    if (failed) {
        Text("Wiedergabe nicht möglich (Format nicht unterstützt).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                runCatching { if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } }
            }) { Text(if (playing) "⏸ Pause" else "▶ Abspielen") }
        }
    }
}
