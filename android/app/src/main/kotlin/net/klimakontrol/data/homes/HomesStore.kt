package net.klimakontrol.data.homes

import android.content.Context
import net.klimakontrol.data.Home
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistenza LOCALE delle "case" definite dall'utente e dell'assegnazione dispositivo→casa.
 * Nessun dato dal cloud: sono gruppi/filtri gestiti in app, in un semplice SharedPreferences.
 */
class HomesStore(context: Context) {
    private val sp = context.getSharedPreferences("klima_homes", Context.MODE_PRIVATE)

    fun homes(): List<Home> {
        val arr = JSONArray(sp.getString("homes", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); Home(o.getString("id"), o.getString("name"))
        }
    }

    fun saveHomes(list: List<Home>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        sp.edit().putString("homes", arr.toString()).apply()
    }

    /** Preferenza locale: aggiungere `beep=1` a ogni comando (bip del climatizzatore). */
    fun beepOnCommand(): Boolean = sp.getBoolean("beep", false)
    fun setBeepOnCommand(on: Boolean) = sp.edit().putBoolean("beep", on).apply()

    /** did del dispositivo → id della casa. */
    fun assignments(): Map<String, String> {
        val o = JSONObject(sp.getString("assign", "{}"))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun saveAssignments(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (did, homeId) -> o.put(did, homeId) }
        sp.edit().putString("assign", o.toString()).apply()
    }

    /** Configurazione (case + assegnazioni) come JSON leggibile, per l'esportazione. */
    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("homes", JSONArray(sp.getString("homes", "[]")))
        .put("assign", JSONObject(sp.getString("assign", "{}")))
        .toString(2)

    /** Reimporta una configurazione esportata. Lancia se il JSON non è valido. */
    fun importJson(json: String) {
        val o = JSONObject(json)
        sp.edit()
            .putString("homes", (o.optJSONArray("homes") ?: JSONArray()).toString())
            .putString("assign", (o.optJSONObject("assign") ?: JSONObject()).toString())
            .apply()
    }
}
