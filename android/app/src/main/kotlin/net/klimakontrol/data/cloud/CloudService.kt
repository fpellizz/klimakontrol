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

    /** Base URL della regione corrente (per il branding: /neutralapp/companyinfo). */
    fun baseUrl(): String = region.baseUrl

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

    // ---- onboarding: aggiungi un modulo nuovo (LAN discovery+auth → bind cloud) ----
    /**
     * Trova sulla WiFi di casa il modulo appena configurato (quello non ancora nell'account),
     * ne ricava la chiave AES via auth in LAN, e lo registra nell'account (bind). Ritorna il
     * nome assegnato. Va usato dopo la config SoftAP, con il telefono tornato sulla WiFi di casa.
     */
    suspend fun addNewModule(context: android.content.Context, name: String): String =
        withContext(Dispatchers.IO) {
            if (!client.loggedIn && !restore() && !autoLogin())
                throw CloudException("nessuna sessione salvata")
            if (devices.isEmpty()) runCatching { devices = client.devices() }
            val known = devices.map { normMac(it.mac) }.toSet()
            val candidate = net.klimakontrol.data.onboarding.LanProbe.discover(context)
                .firstOrNull { normMac(it.mac) !in known }
                ?: throw CloudException("nessun nuovo modulo trovato sulla rete WiFi")
            val authed = net.klimakontrol.data.onboarding.LanProbe.authenticate(context, candidate)
            val friendly = name.ifBlank { candidate.name }
            client.bindDevice(derivePid(authed.devtype), deriveDid(authed.mac),
                authed.mac, authed.key, friendly)
            friendly.ifBlank { authed.mac }
        }

    private fun normMac(m: String) = m.replace(":", "").replace("-", "").lowercase()

    /** `did` cloud = 10 byte zero + MAC (32 hex). Osservato sui device reali di questo account
     *  (`endpointId` = `00000000000000000000<mac>`); da confermare su un modulo vergine. */
    private fun deriveDid(mac: String): String = "0".repeat(20) + normMac(mac)

    /** `pid` cloud = 24 zeri + devtype little-endian (2 byte) + `0000`. Osservato: 0x4e2e → `…2e4e0000`. */
    private fun derivePid(devtype: Int): String =
        "0".repeat(24) + "%02x%02x".format(devtype and 0xFF, (devtype ushr 8) and 0xFF) + "0000"

    /**
     * Esegue un'azione pianificata dal telefono (timer): assicura la sessione (riusa quella
     * salvata o rientra con le credenziali), carica le unità e invia il comando. Usato dal
     * ricevitore d'allarme, che gira in un'istanza a sé senza stato caricato. Lancia se non
     * riesce (nessuna sessione o rete): il chiamante lo segnala come timer non riuscito.
     */
    suspend fun runScheduledAction(unitId: String, changes: Map<String, Int>) =
        withContext(Dispatchers.IO) {
            if (!restore() && !autoLogin()) throw CloudException("nessuna sessione salvata")
            try {
                loadUnits(); push(unitId, changes)
            } catch (e: Exception) {
                // sessione forse scaduta: un solo rientro con le credenziali salvate
                if (autoLogin()) { loadUnits(); push(unitId, changes) } else throw e
            }
            Unit
        }
}
