package net.klimakontrol.data.cloud

import android.content.Context

/**
 * Persistenza della sessione cloud. Come `session.py`: salva userid/loginsession (segreti di
 * sessione, non la password) e l'email/regione per il ripristino. File privato dell'app.
 */
class SessionStore(context: Context) {
    private val sp = context.getSharedPreferences("klima_session", Context.MODE_PRIVATE)

    data class Saved(val userid: String, val loginSession: String, val email: String, val region: String)

    fun load(): Saved? {
        val uid = sp.getString("userid", null) ?: return null
        val ls = sp.getString("loginsession", null) ?: return null
        return Saved(uid, ls, sp.getString("email", "") ?: "", sp.getString("region", "eu") ?: "eu")
    }

    fun save(userid: String, loginSession: String, email: String, region: String) {
        sp.edit()
            .putString("userid", userid)
            .putString("loginsession", loginSession)
            .putString("email", email)
            .putString("region", region)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()
}
