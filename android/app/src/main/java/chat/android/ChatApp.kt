package chat.android

import android.app.Application
import chat.engine.Engine
import chat.engine.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
        private set
    lateinit var engine: Engine
        private set

    /** Wird von der Activity gesetzt (sichtbar = Vordergrund). */
    @Volatile var visible = false
    private var lockJob: Job? = null

    /**
     * Bis zu diesem Zeitpunkt ist ein Wechsel in eine System-Oberfläche erwartet (Fingerabdruck/Geräte-PIN, Dateiauswahl, Kamera).
     * Dann sperrt die App nicht sofort beim Verlassen, sondern frühestens danach – sonst würde z. B. das Einrichten des
     * Fingerabdrucks die App mitten im Vorgang sperren.
     */
    @Volatile private var externalUntil = 0L
    fun expectSystemUi(ms: Long = 120_000) { externalUntil = System.currentTimeMillis() + ms }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.createChannels(this)
        engine = Engine(SecureBlobStore(this), scope = appScope)
        appScope.launch { engine.init() }
        // Benachrichtigung ohne Inhalt, wenn die App nicht sichtbar ist.
        appScope.launch { engine.newMessages.collect { if (!visible) Notifications.newMessage(this@ChatApp) else if (prefs.inAppSound) Notifications.playInApp(this@ChatApp) } }
        // Neues Netz (WLAN ↔ Mobilfunk, Verbindung wieder da): sofort neu verbinden und Nachrichten nachholen.
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { engine.nudge() }
            })
        }
        // Hintergrunddienst folgt dem Entsperr-Zustand.
        appScope.launch {
            engine.phase.collect { p ->
                if (p == Phase.Unlocked && prefs.keepConnected) ChatService.start(this@ChatApp) else ChatService.stop(this@ChatApp)
            }
        }
    }

    fun onForeground() {
        visible = true
        externalUntil = 0
        engine.nudge()
        lockJob?.cancel()
        Notifications.clearMessages(this)
    }

    /** App geht in den Hintergrund: nach der eingestellten Zeit sperren (0 = sofort). */
    fun onBackground() {
        visible = false
        lockJob?.cancel()
        val grace = (externalUntil - System.currentTimeMillis()).coerceAtLeast(0)
        lockJob = appScope.launch {
            delay(maxOf(prefs.autoLockMinutes * 60_000L, grace))
            if (engine.phase.value == Phase.Unlocked) engine.lock()
        }
    }
}
