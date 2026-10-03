package chat.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import chat.android.ui.AppRoot
import chat.android.ui.ChatTheme

class MainActivity : FragmentActivity() {
    private val app get() = application as ChatApp

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { ChatTheme { AppRoot(activity = this, onSecureChanged = { applySecureFlag() }) } }
    }

    /** FLAG_SECURE: keine Screenshots, keine Bildschirmaufnahme, leere Vorschau im App-Wechsler. */
    fun applySecureFlag() {
        if (app.prefs.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
