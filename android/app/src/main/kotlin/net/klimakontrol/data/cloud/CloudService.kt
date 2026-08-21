package net.klimakontrol.data.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.klimakontrol.data.AcUnit

/**
 * Facciata cloud usata dalla UI: login, ripristino sessione, auto-login con credenziali salvate,
 * caricamento unità e invio comandi. Rete su Dispatchers.IO; logica in [CloudClient].
 */
class CloudService(context: Context) {

    private val store = SessionStore(context.applicationContext)
    private var region: Region = REGIONS.getValue("eu")
    private var client = CloudClient(region)
    private var devices: List<CloudDevice> = emptyList()

    fun hasSession(): Boolean = store.session() != null
    fun hasCreds(): Boolean = store.creds() != null
    fun savedEmail(): String = store.email()

    /** Login con credenziali; salva la sessione e (se richiesto) le credenziali cifrate. */
    suspend fun login(email: String, password: String, remember: Boolean, regionCode: String = "eu") =
        withContext(Dispatchers.IO) {
            region = REGIONS.getValue(regionCode)
            client = CloudClient(region)
            client.login(email.trim(), password)
            store.saveSession(client.userid!!, client.loginSession!!)
            store.saveMeta(email.trim(), regionCode)
            if (remember) store.saveCreds(email.trim(), password, regionCode) else store.clearCreds()
        }

    /** Riusa la sessione salvata, se c'è. */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val s = store.session() ?: return@withContext false
        region = REGIONS[store.region()] ?: REGIONS.getValue("eu")
        client = CloudClient(region)
        client.restoreSession(s.userid, s.loginSession)
        true
    }

    /** Rifà il login con le credenziali salvate (quando la sessione è scaduta). */
    suspend fun autoLogin(): Boolean = withContext(Dispatchers.IO) {
        val c = store.creds() ?: return@withContext false
        region = REGIONS[c.region] ?: REGIONS.getValue("eu")
        client = CloudClient(region)
        client.login(c.email, c.password)
        store.saveSession(client.userid!!, client.loginSession!!)
        true
    }

    /** Esci: chiude la sessione ma MANTIENE le credenziali salvate (se "ricorda" era attivo),
     *  così al prossimo avvio l'app rientra da sola. Per dimenticarle del tutto usa [forget]. */
    fun logout() {
        store.clearSession()
        devices = emptyList()
        client = CloudClient(region)
    }

    /** Dimentica tutto: sessione e credenziali salvate. */
    fun forget() {
        store.clearAll()
        devices = emptyList()
        client = CloudClient(region)
    }

    /** Carica l'elenco unità con il loro stato (una lettura per unità). */
    suspend fun loadUnits(): List<AcUnit> = withContext(Dispatchers.IO) {
        devices = client.devices()
        devices.map { d ->
            try {
                cloudUnit(d, client.getState(d, READ_PARAMS), online = true)
            } catch (e: Exception) {
                cloudUnit(d, emptyMap(), online = false)
            }
        }
    }

    private fun deviceFor(id: String): CloudDevice? = devices.firstOrNull { it.did == id }

    /** Invia dei parametri sul filo per l'unità. Lancia in caso di errore (per il roll-back). */
    suspend fun push(unitId: String, changes: Map<String, Int>) = withContext(Dispatchers.IO) {
        val d = deviceFor(unitId) ?: throw CloudException("unità sconosciuta: $unitId")
        client.setState(d, changes)
        Unit
    }
}
