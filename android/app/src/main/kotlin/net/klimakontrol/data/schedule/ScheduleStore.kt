package net.klimakontrol.data.schedule

import android.content.Context
import org.json.JSONArray

/** Persistenza locale delle pianificazioni (non sono segreti: SharedPreferences in chiaro). */
class ScheduleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("klima_schedules", Context.MODE_PRIVATE)

    fun all(): List<Schedule> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Schedule.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun forUnit(unitId: String): List<Schedule> = all().filter { it.unitId == unitId }

    fun get(id: String): Schedule? = all().firstOrNull { it.id == id }

    fun upsert(s: Schedule) = save(all().filter { it.id != s.id } + s)

    fun delete(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<Schedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
