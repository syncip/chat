package chat.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Farben wie im Webinterface (Grün-Akzent, helle/dunkle Chat-Fläche, eigene/fremde Sprechblasen). */
@Immutable
data class ChatColors(
    val chatBg: Color,
    val mine: Color,
    val onMine: Color,
    val theirs: Color,
    val onTheirs: Color,
    val meta: Color,
    val ok: Color,
    val warn: Color,
    val unread: Color,
    val ticksRead: Color,
)

private val LightChat = ChatColors(
    chatBg = Color(0xFFEFEAE2), mine = Color(0xFFD9FDD3), onMine = Color(0xFF111B21), theirs = Color.White, onTheirs = Color(0xFF111B21),
    meta = Color(0xFF667781), ok = Color(0xFF1A8F5A), warn = Color(0xFFA56A00), unread = Color(0xFF25D366), ticksRead = Color(0xFF53BDEB),
)
private val DarkChat = ChatColors(
    chatBg = Color(0xFF0B141A), mine = Color(0xFF005C4B), onMine = Color(0xFFE9EDEF), theirs = Color(0xFF202C33), onTheirs = Color(0xFFE9EDEF),
    meta = Color(0xFF8696A0), ok = Color(0xFF4CC38A), warn = Color(0xFFF0B34A), unread = Color(0xFF00A884), ticksRead = Color(0xFF53BDEB),
)

val LocalChatColors = staticCompositionLocalOf { LightChat }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF008069), onPrimary = Color.White, primaryContainer = Color(0xFFD9FDD3), onPrimaryContainer = Color(0xFF00382D),
    secondary = Color(0xFF00A884), onSecondary = Color.White, secondaryContainer = Color(0xFFE7F5F1), onSecondaryContainer = Color(0xFF0A3D33),
    tertiary = Color(0xFFA56A00), tertiaryContainer = Color(0xFFFFF1D6), onTertiaryContainer = Color(0xFF3D2700),
    background = Color.White, surface = Color.White, onSurface = Color(0xFF111B21), surfaceVariant = Color(0xFFF0F2F5), onSurfaceVariant = Color(0xFF54656F),
    surfaceContainer = Color(0xFFF7F8FA), surfaceContainerHigh = Color(0xFFF0F2F5), surfaceContainerHighest = Color(0xFFE9EDEF),
    outline = Color(0xFFD1D7DB), outlineVariant = Color(0xFFE9EDEF), error = Color(0xFFD93025), errorContainer = Color(0xFFFDE7E7), onErrorContainer = Color(0xFF5C0B07),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF00A884), onPrimary = Color(0xFF00211B), primaryContainer = Color(0xFF005C4B), onPrimaryContainer = Color(0xFFD9FDD3),
    secondary = Color(0xFF25D366), onSecondary = Color(0xFF002110), secondaryContainer = Color(0xFF1F3A33), onSecondaryContainer = Color(0xFFC9EFE4),
    tertiary = Color(0xFFF0B34A), tertiaryContainer = Color(0xFF4A3300), onTertiaryContainer = Color(0xFFFFE1A8),
    background = Color(0xFF111B21), surface = Color(0xFF111B21), onSurface = Color(0xFFE9EDEF), surfaceVariant = Color(0xFF202C33), onSurfaceVariant = Color(0xFF8696A0),
    surfaceContainer = Color(0xFF16232A), surfaceContainerHigh = Color(0xFF202C33), surfaceContainerHighest = Color(0xFF2A3942),
    outline = Color(0xFF3B4A54), outlineVariant = Color(0xFF222D34), error = Color(0xFFFF8A80), errorContainer = Color(0xFF4A1512), onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalChatColors provides if (dark) DarkChat else LightChat) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
