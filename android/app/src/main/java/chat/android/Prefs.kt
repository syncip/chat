package chat.android

import android.content.Context

/** Nicht-sensitive Einstellungen (Schalter). Alles Vertrauliche liegt im verschlüsselten Vault. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Keine Screenshots/Bildschirmaufnahmen, keine Vorschau im App-Wechsler. */
    var secureScreen: Boolean
        get() = p.getBoolean("secure_screen", true)
        set(v) = p.edit().putBoolean("secure_screen", v).apply()

    /** Minuten im Hintergrund, bevor die App sich sperrt (0 = sofort). */
    var autoLockMinutes: Int
        get() = p.getInt("autolock_min", 1)
        set(v) = p.edit().putInt("autolock_min", v.coerceIn(0, 60)).apply()

    var biometricEnabled: Boolean
        get() = p.getBoolean("biometric", false)
        set(v) = p.edit().putBoolean("biometric", v).apply()

    /** Verbindung im Hintergrund halten (Foreground-Service), solange die App entsperrt ist. */
    var keepConnected: Boolean
        get() = p.getBoolean("keep_connected", true)
        set(v) = p.edit().putBoolean("keep_connected", v).apply()

    /** Mindestlänge der Passphrase (Standard 10, einstellbar 1–128). */
    var minPassphrase: Int
        get() = p.getInt("min_pass", 10)
        set(v) = p.edit().putInt("min_pass", v.coerceIn(1, 128)).apply()

    /** Ton bei neuen Nachrichten, solange die App im Vordergrund ist (im Hintergrund entscheidet der Benachrichtigungskanal). */
    var inAppSound: Boolean
        get() = p.getBoolean("in_app_sound", true)
        set(v) = p.edit().putBoolean("in_app_sound", v).apply()
}
