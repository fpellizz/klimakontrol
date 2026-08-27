package net.klimakontrol.data.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.klimakontrol.R

/** Notifica di esito quando una pianificazione scatta (best-effort: se manca il permesso, tace). */
object Notifier {

    private const val CHANNEL = "klima_timers"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        ctx.getString(R.string.timer_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }
    }

    fun fired(ctx: Context, s: Schedule, ok: Boolean) {
        ensureChannel(ctx)
        val action = ctx.getString(if (s.turnOn) R.string.timer_action_on else R.string.timer_action_off)
        val title = "${s.unitName}: $action"
        val text = ctx.getString(if (ok) R.string.timer_notify_ok else R.string.timer_notify_fail)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(s.requestCode(), n)
        } catch (e: SecurityException) {
            // permesso notifiche non concesso (Android 13+): il timer è comunque scattato
        }
    }
}
