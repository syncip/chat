package chat.android.ui

import android.app.Application
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

    init {
        viewModelScope.launch { engine.version.debounce(60).collect { _state.value = engine.snapshot() } }
        viewModelScope.launch { engine.phase.collect { _state.value = engine.snapshot() } }
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
