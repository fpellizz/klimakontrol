package net.klimakontrol.data.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.klimakontrol.data.cloud.CloudService

/**
 * Riceve gli allarmi delle pianificazioni (scatto) e gli eventi di sistema (reboot / app
 * aggiornata) per riprogrammarle. Allo scatto invia il comando via cloud in un'istanza a sé di
 * [CloudService] (il receiver non ha lo stato dell'app caricato), poi ri-arma il ricorrente o
 * consuma il one-shot, e mostra una notifica di esito.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Scheduler.rearmAll(app)
                return
            }
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val store = ScheduleStore(app)
        val s = store.get(id) ?: return
        if (!s.enabled) return

        val pending = goAsync()   // tiene vivo il receiver mentre parte la coroutine
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    CloudService(app).runScheduledAction(s.unitId, s.action)
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }
            try {
                Notifier.fired(app, s, ok)
                if (s.recurring) Scheduler.arm(app, s)   // programma la prossima occorrenza
                else store.delete(id)                    // one-shot: consumato
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "net.klimakontrol.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"
        private const val TIMEOUT_MS = 30_000L
    }
}
