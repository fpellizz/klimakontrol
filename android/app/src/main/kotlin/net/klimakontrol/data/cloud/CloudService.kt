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
    fun savedRegion(): String = store.region().takeIf { it in REGIONS } ?: "eu"

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

    /** Registrazione passo 1: fa inviare il codice di verifica al nuovo account. */
    suspend fun sendRegisterCode(account: String, regionCode: String) = withContext(Dispatchers.IO) {
        region = REGIONS.getValue(regionCode)
        client = CloudClient(region)
        client.sendRegisterCode(account.trim())
    }

    /** Registrazione passo 2: crea l'account e salva la sessione (la register è anche login). */
    suspend fun register(account: String, password: String, code: String,
                         regionCode: String, nickname: String) = withContext(Dispatchers.IO) {
        region = REGIONS.getValue(regionCode)
        client = CloudClient(region)
        client.register(account.trim(), password, code, nickname = nickname.trim())
        store.saveSession(client.userid!!, client.loginSession!!)
        store.saveMeta(account.trim(), regionCode)
    }

    /** Cambia la password dell'account (a sessione aperta). */
    suspend fun changePassword(oldPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        client.changePassword(oldPassword, newPassword)
        // se le credenziali erano salvate, aggiorna la password memorizzata (email invariata)
        store.creds()?.let { store.saveCreds(it.email, newPassword, it.region) }
    }

    /** Cambia il soprannome dell'account (a sessione aperta). */
    suspend fun changeNickname(nickname: String) = withContext(Dispatchers.IO) {
        client.changeNickname(nickname)
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
