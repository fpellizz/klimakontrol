package net.klimakontrol.data.tasks

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Una pianificazione (timer) del modulo. MVP: timer ricorrente (settimanale) che applica
 * un'azione a un certo orario nei giorni scelti.
 *
 * Formato ricostruito dalla SDK JS ufficiale (broadlink-jssdk): comando `dev_taskadd` via
 * sdkcontrol, azione = normale comando di controllo `{params, vals}`, giorni = lista `repeat`
 * 1..7, orari in UTC+8 (il firmware vive in UTC+8). L'envelope esatto della direttiva task va
 * confermato su HW; qui il comando va nel campo `act` del payload, come in `klimakontrol/cloud.py`.
 */
data class Timer(
    val type: Int = TYPE_PERIOD,
    val hour: Int,
    val minute: Int,
    val weekday: List<Int> = emptyList(),        // 0 = lunedì .. 6 = domenica (ora locale)
    val enable: Boolean = true,
    val index: Int? = null,
    val action: Map<String, Int> = emptyMap(),   // chiavi sul filo (pwr/save_temp/tcl_mode…) → valori
) {
    companion object {
        const val TYPE_ONCE = 0
        const val TYPE_DELAY = 1
        const val TYPE_PERIOD = 2
        const val TYPE_CYCLE = 3
        const val TYPE_RANDOM = 4
        const val DEVICE_TZ = 8

        const val CMD_ADD = "dev_taskadd"
        const val CMD_LIST = "dev_tasklist"
        const val CMD_DELETE = "dev_taskdel"

        /** Spostamento locale → UTC+8 del firmware. */
        private fun deviceShift(): Duration {
            val off = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
            return Duration.ofSeconds((DEVICE_TZ * 3600L) - off)
        }

        private fun paramsVals(action: Map<String, Int>): JSONObject {
            val params = JSONArray()
            val vals = JSONArray()
            for ((k, v) in action) {
                params.put(k)
                vals.put(JSONArray().put(JSONObject().put("val", v).put("idx", 1)))
            }
            return JSONObject().put("params", params).put("vals", vals)
        }

        private fun flatten(data: JSONObject?): Map<String, Int> {
            val params = data?.optJSONArray("params") ?: return emptyMap()
            val vals = data.optJSONArray("vals") ?: return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (i in 0 until params.length()) {
                val name = params.optString(i)
                val entry = vals.optJSONArray(i)?.optJSONObject(0)
                if (name.isNotEmpty() && entry != null) out[name] = entry.optInt("val")
            }
            return out
        }

        /** Legge un timer restituito dal modulo (device/UTC+8) e lo riporta in ora locale. */
        fun fromWire(raw: JSONObject, type: Int): Timer {
            // orario device: "HH:mm:ss" (o "AAAA-MM-GG HH:mm:ss" per once/delay); prende ora/min
            val timeStr = raw.optString("time")
            val hm = Regex("(\\d{1,2}):(\\d{2})").find(timeStr)
            val devH = hm?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val devM = hm?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val dev = LocalDateTime.of(LocalDate.now(), LocalTime.of(devH, devM))
            val local = dev.minus(deviceShift())
            // repeat (1..7 device) → weekday (0..6 locale) con lo shift inverso
            val repeatArr = raw.optJSONArray("repeat")
            val delta = ChronoUnit.DAYS.between(local.toLocalDate(), dev.toLocalDate()).toInt()
            val weekday = mutableListOf<Int>()
            if (repeatArr != null) {
                for (i in 0 until repeatArr.length()) {
                    val d = repeatArr.optInt(i)
                    weekday.add((((d - 1 - delta) % 7) + 7) % 7)
                }
            }
            return Timer(
                type = type, hour = local.hour, minute = local.minute,
                weekday = weekday.sorted(), enable = raw.optInt("enable", 1) != 0,
                index = if (raw.has("index")) raw.optInt("index") else null,
                action = flatten(raw.optJSONObject("data")),
            )
        }
    }

    /** Costruisce il `ctrlData` per `dev_taskadd`, con orari convertiti in UTC+8. */
    fun toWire(): JSONObject {
        val base = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, minute))
        val dev = base.plus(deviceShift())
        val out = JSONObject()
            .put("type", type)
            .put("enable", if (enable) 1 else 0)
            .put("time", "%02d:%02d:00".format(dev.hour, dev.minute))
        index?.let { out.put("index", it) }
        if (weekday.isNotEmpty()) {
            val delta = ChronoUnit.DAYS.between(base.toLocalDate(), dev.toLocalDate()).toInt()
            val repeat = weekday.map { (((it + delta) % 7) + 7) % 7 + 1 }.sorted()   // 1=lun..7=dom
            out.put("repeat", JSONArray(repeat))
        }
        if (action.isNotEmpty()) out.put("data", paramsVals(action))
        return out
    }
}
