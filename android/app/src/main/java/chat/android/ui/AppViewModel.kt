package chat.android.ui

import chat.engine.Phase
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.android.ChatApp
import chat.engine.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Ziele der App-Navigation (eigener, einfacher Back-Stack). */
sealed interface Route {
    data object Home : Route
    data class Chat(val id: String) : Route
    data class Channel(val id: String) : Route
    data class ConvInfo(val id: String) : Route
    data class ChannelInfo(val id: String) : Route
    data class Files(val convId: String?) : Route
    data object Settings : Route
    data object Admin : Route
}

/** Von einer anderen App geteilte Inhalte (Text oder Dateien), die in einen Chat gesendet werden sollen. */
data class ShareData(val text: String?, val uris: List<Uri>)

@OptIn(FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val chat = app as ChatApp
    val engine = chat.engine
    val prefs = chat.prefs
    val phase = engine.phase
    val online = engine.online

    private val _state = MutableStateFlow<AppState?>(null)
    /** Unveränderlicher Schnappschuss des Engine-Zustands (null, solange gesperrt). */
    val state: StateFlow<AppState?> = _state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // ---- Navigation ----
    val stack = mutableStateListOf<Route>(Route.Home)
    val route: Route get() = stack.last()
    fun go(r: Route) { if (stack.last() != r) stack.add(r) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** Ersetzt das oberste Ziel (z. B. Chat nach dem Erstellen öffnen). */
    fun replace(r: Route) { if (stack.size > 1) stack[stack.lastIndex] = r else stack.add(r) }

    /** Einstellungen direkt auf einer Unterseite öffnen (z. B. „security“). */
    var settingsPage by mutableStateOf<String?>(null)

    // ---- Teilen aus anderen Apps ----
    var share by mutableStateOf<ShareData?>(null)
    /** Entwürfe (Text + Anhänge), die beim Öffnen eines Chats ins Eingabefeld übernommen werden. */
    var pendingDraft by mutableStateOf<Pair<String, ShareData>?>(null)

    init {
        viewModelScope.launch { engine.version.debounce(80).collect { _state.value = engine.snapshot() } }
        viewModelScope.launch {
            engine.phase.collect {
                _state.value = engine.snapshot()
                if (it != Phase.Unlocked) home()
            }
        }
    }

    fun clearError() { _error.value = null }
    fun showError(msg: String) { _error.value = msg }

    /** Führt eine Aktion aus und zeigt Fehler als Meldung an. */
    fun run(onError: ((String) -> Unit)? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val m = e.message ?: "Fehler"
            if (onError != null) onError(m) else _error.value = m
        }
    }
}
