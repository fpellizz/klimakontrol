package net.klimakontrol.data.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistenza cifrata (Android Keystore via EncryptedSharedPreferences) di:
 *  - sessione: userid + loginsession (per l'auto-accesso quando la sessione è ancora valida);
 *  - credenziali: email + password + regione, salvate SOLO se l'utente sceglie "ricorda"
 *    (per rifare il login automaticamente quando la sessione scade).
 * La password è cifrata a riposo; non compare mai in chiaro né nei log.
 */
class SessionStore(context: Context) {

    private val sp: SharedPreferences = run {
        val ctx = context.applicationContext
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "klima_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Session(val userid: String, val loginSession: String)
    data class Creds(val email: String, val password: String, val region: String)

    // ---- sessione ----
    fun saveSession(userid: String, loginSession: String) =
        sp.edit().putString("userid", userid).putString("loginsession", loginSession).apply()

    fun session(): Session? {
        val uid = sp.getString("userid", null) ?: return null
        val ls = sp.getString("loginsession", null) ?: return null
        return Session(uid, ls)
    }

    // ---- meta (per il prefill), sempre salvate ----
    fun saveMeta(email: String, region: String) =
        sp.edit().putString("email", email).putString("region", region).apply()

    fun email(): String = sp.getString("email", "") ?: ""
    fun region(): String = sp.getString("region", "eu") ?: "eu"

    // ---- credenziali (solo se "ricorda") ----
    fun saveCreds(email: String, password: String, region: String) = sp.edit()
        .putString("email", email).putString("password", password)
        .putString("region", region).putBoolean("remember", true).apply()

    fun creds(): Creds? {
        if (!sp.getBoolean("remember", false)) return null
        val pw = sp.getString("password", null) ?: return null
        return Creds(email(), pw, region())
    }

    fun clearCreds() = sp.edit().remove("password").putBoolean("remember", false).apply()

    /** Rimuove solo la sessione (userid/loginsession); email e credenziali salvate restano. */
    fun clearSession() = sp.edit().remove("userid").remove("loginsession").apply()

    fun clearAll() = sp.edit().clear().apply()
}
