package chat.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import chat.android.ui.AppRoot
import chat.android.ui.AppViewModel
import chat.android.ui.ShareData
import chat.android.ui.ChatTheme

class MainActivity : FragmentActivity() {
    private val app get() = application as ChatApp

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleShare(intent)
        setContent { ChatTheme { AppRoot(activity = this, onSecureChanged = { applySecureFlag() }, vm = vm) } }
    }

    private val vm: AppViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Aus einer anderen App geteilte Inhalte merken; nach dem Entsperren wählt man den Ziel-Chat. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> return
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrBlank()) return
        vm.share = ShareData(text, uris)
        vm.home()
    }

    /** FLAG_SECURE: keine Screenshots, keine Bildschirmaufnahme, leere Vorschau im App-Wechsler. */
    fun applySecureFlag() {
        if (app.prefs.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // Alle Activity-Result-Aufrufe (Dateiauswahl, Speichern, Kamera, Geräte-PIN) laufen hierüber: kurz nicht automatisch sperren.
    @Deprecated("Activity Result API nutzt das intern")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        app.expectSystemUi()
        @Suppress("DEPRECATION") super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStart() {
        super.onStart()
        app.onForeground()
    }

    override fun onStop() {
        super.onStop()
        app.onBackground()
    }
}
