package net.klimakontrol.data.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.klimakontrol.data.AcUnit

/**
 * Facciata cloud usata dalla UI: login, ripristino sessione, caricamento unità e invio comandi.
 * Tutte le chiamate di rete girano su Dispatchers.IO. La logica sta in [CloudClient].
 */
class CloudService(context: Context) {

    private val store = SessionStore(context.applicationContext)
    private var region: Region = REGIONS.getValue("eu")
    private var client = CloudClient(region)
    private var devices: List<CloudDevice> = emptyList()

    fun hasSession(): Boolean = store.load() != null
    fun savedEmail(): String = store.load()?.email ?: ""

    /** Login con credenziali; salva la sessione. Lancia CloudException in caso di errore. */
    suspend fun login(email: String, password: String, regionCode: String = "eu") =
        withContext(Dispatchers.IO) {
            region = REGIONS.getValue(regionCode)
            client = CloudClient(region)
            client.login(email.trim(), password)
            store.save(client.userid!!, client.loginSession!!, email.trim(), regionCode)
        }

    /** Riusa la sessione salvata, se c'è. Restituisce true se pronta. */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val s = store.load() ?: return@withContext false
        region = REGIONS[s.region] ?: REGIONS.getValue("eu")
        client = CloudClient(region)
        client.restoreSession(s.userid, s.loginSession)
        true
    }

    fun logout() {
        store.clear()
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
