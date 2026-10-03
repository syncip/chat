package chat.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Hält den Prozess (und damit die WebSocket-Verbindung der Engine) am Leben, solange die App entsperrt ist.
 * Kein Google-Dienst, kein Inhalt in der Benachrichtigung. Beim Sperren wird der Dienst beendet.
 */
class ChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifications.service(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifications.ID_SERVICE, n)
        }
        return START_NOT_STICKY // nach Prozess-Tod ist die App gesperrt; ein Neustart ohne Passphrase wäre sinnlos
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ChatService::class.java)) }
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ChatService::class.java))
        }
    }
}
