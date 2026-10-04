package chat.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

/** Benachrichtigungen enthalten nie Absender oder Inhalt, auf dem Sperrbildschirm sind sie verborgen. */
object Notifications {
    const val CH_MESSAGES = "messages_v2" // v2: Kanal mit ausdrücklichem Ton und hoher Wichtigkeit (bestehende Kanäle lassen sich nicht nachträglich ändern)
    const val CH_SERVICE = "connection"
    const val ID_MESSAGE = 1
    const val ID_SERVICE = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("messages")
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Nachrichten", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                // Ton ausdrücklich setzen (der Nutzer kann ihn in den Android-Einstellungen des Kanals ändern)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Verbindung", NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun newMessage(ctx: Context) {
        val n = Notification.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Neue Nachricht")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_MESSAGE, n) }
    }

    /** Kurzer Ton bei neuer Nachricht, während die App im Vordergrund ist. */
    fun playInApp(ctx: Context) {
        runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
    }

    fun clearMessages(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_MESSAGE)
    }

    fun service(ctx: Context): Notification = Notification.Builder(ctx, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Chat verbunden")
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setContentIntent(openApp(ctx))
        .setOngoing(true)
        .build()
}
