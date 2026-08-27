package net.klimakontrol.data.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Registra/annulla gli allarmi del sistema per le pianificazioni. */
object Scheduler {

    /** Su Android 12+ gli allarmi esatti richiedono un permesso concedibile dall'utente. */
    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    private fun pendingIntent(context: Context, s: Schedule): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_ID, s.id)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, s.requestCode(), intent, flags)
    }

    /** Programma il prossimo scatto della pianificazione (esatto se concesso, altrimenti inesatto). */
    fun arm(context: Context, s: Schedule) {
        if (!s.enabled) { cancel(context, s); return }
        val next = s.nextTrigger(System.currentTimeMillis()) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, s)
        try {
            if (canExact(context))
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            // il permesso può essere revocato tra il controllo e la chiamata: ripiega su inesatto
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    fun cancel(context: Context, s: Schedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, s))
    }

    /** Riprogramma tutte le pianificazioni (dopo un reboot o un aggiornamento dell'app). */
    fun rearmAll(context: Context) {
        ScheduleStore(context).all().forEach { arm(context, it) }
    }
}
