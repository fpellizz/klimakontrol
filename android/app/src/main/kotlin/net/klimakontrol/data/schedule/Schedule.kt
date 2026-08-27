package net.klimakontrol.data.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Una pianificazione tenuta e fatta scattare **dal telefono**.
 *
 * Questi moduli (`0x4e2e`) non hanno uno scheduler nativo: né i task device-side (`dev_taskadd`,
 * lo script Lua del modello ha i timer rimossi), né la "Reservation" a parametri (il profilo non
 * espone `sub_*`), né il timer cloud (codice morto). Vedi `docs/open-questions.md`. Quindi la
 * "sveglia" la tiene l'app: [AlarmManager][android.app.AlarmManager] fa scattare
 * [AlarmReceiver] all'ora giusta, che manda accensione/spegnimento via cloud (lo stesso
 * `sdkcontrol` del controllo manuale). Limite onesto: a telefono spento/app rimossa non scatta.
 */
data class Schedule(
    val id: String,
    val unitId: String,
    val unitName: String,
    val recurring: Boolean,                     // true = settimanale; false = una volta ("tra X")
    val hour: Int = 0,                          // ora locale del giorno (solo ricorrente)
    val minute: Int = 0,
    val weekday: List<Int> = emptyList(),       // 0=lun..6=dom (ricorrente; vuoto = ogni giorno)
    val fireAtMillis: Long = 0L,                // one-shot: istante assoluto di scatto (epoch ms)
    val action: Map<String, Int> = emptyMap(),  // pwr/save_temp/tcl_mode… → valore sul filo
    val enabled: Boolean = true,
) {
    val turnOn: Boolean get() = (action["pwr"] ?: 0) != 0
    val targetTemp: Int? get() = action["save_temp"]

    /** requestCode stabile per il PendingIntent dell'allarme. */
    fun requestCode(): Int = id.hashCode()

    /**
     * Prossimo istante di scatto in epoch-ms, o `null` se non ce n'è (one-shot già passato).
     * Per il ricorrente cerca il primo giorno-abilitato con orario ancora futuro (entro 8 giorni).
     */
    fun nextTrigger(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!recurring) return if (fireAtMillis > nowMillis) fireAtMillis else null
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val time = LocalTime.of(hour, minute)
        val days = if (weekday.isEmpty()) (0..6).toList() else weekday
        for (add in 0..7) {
            val date = now.toLocalDate().plusDays(add.toLong())
            val dow = (date.dayOfWeek.value + 6) % 7   // java: Mon=1..Sun=7 → Mon=0..Sun=6
            if (dow !in days) continue
            val ms = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            if (ms > nowMillis) return ms
        }
        return null
    }

    fun toJson(): JSONObject {
        val a = JSONObject()
        action.forEach { (k, v) -> a.put(k, v) }
        return JSONObject()
            .put("id", id).put("unitId", unitId).put("unitName", unitName)
            .put("recurring", recurring).put("hour", hour).put("minute", minute)
            .put("weekday", JSONArray(weekday)).put("fireAt", fireAtMillis)
            .put("action", a).put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val wd = mutableListOf<Int>()
            o.optJSONArray("weekday")?.let { for (i in 0 until it.length()) wd.add(it.getInt(i)) }
            val action = LinkedHashMap<String, Int>()
            o.optJSONObject("action")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) { val k = keys.next(); action[k] = obj.getInt(k) }
            }
            return Schedule(
                id = o.getString("id"),
                unitId = o.getString("unitId"),
                unitName = o.optString("unitName"),
                recurring = o.optBoolean("recurring"),
                hour = o.optInt("hour"),
                minute = o.optInt("minute"),
                weekday = wd,
                fireAtMillis = o.optLong("fireAt"),
                action = action,
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}
