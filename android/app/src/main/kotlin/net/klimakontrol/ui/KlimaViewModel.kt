package net.klimakontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.klimakontrol.BuildConfig
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.update.UpdateChecker
import net.klimakontrol.data.update.UpdateStatus
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.data.cloud.CloudService
import net.klimakontrol.data.cloud.ecoWire
import net.klimakontrol.data.cloud.fanChangeWire
import net.klimakontrol.data.cloud.modeChangeWire
import net.klimakontrol.data.cloud.nightWire
import net.klimakontrol.data.cloud.powerWire
import net.klimakontrol.data.cloud.swingHWire
import net.klimakontrol.data.cloud.swingVWire
import net.klimakontrol.data.cloud.targetWire
import net.klimakontrol.data.cloud.turboWire

sealed interface Phase {
    data object Loading : Phase
    data class Login(
        val email: String = "",
        val region: String = "eu",   // regione/vendor scelto (determina il lid)
        val error: String? = null,
        val busy: Boolean = false,
    ) : Phase
    data class Register(
        val email: String = "",
        val region: String = "eu",
        val codeSent: Boolean = false,   // passo 1 fatto: mostra il campo codice
        val error: String? = null,
        val busy: Boolean = false,
    ) : Phase
    data object Connected : Phase
}

/** Esito visibile di un comando per una singola unità. */
enum class SendState { Idle, Sending, Ok, Error }

private const val DEBOUNCE_MS = 400L
private const val OK_HOLD_MS = 900L
private const val ERR_HOLD_MS = 1600L
private const val POLL_MS = 15_000L   // aggiornamento periodico dello stato reale (uso promiscuo)

class KlimaViewModel(app: Application) : AndroidViewModel(app) {

    private val service = CloudService(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase = _phase.asStateFlow()

    private val _units = MutableStateFlow<List<AcUnit>>(emptyList())
    val units = _units.asStateFlow()

    private val _send = MutableStateFlow<Map<String, SendState>>(emptyMap())
    val send = _send.asStateFlow()

    private val _update = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val update = _update.asStateFlow()

    val appVersion: String = BuildConfig.VERSION_NAME

    // ---- impostazioni: feedback delle operazioni account ----
    private val _settingsMsg = MutableStateFlow<String?>(null)
    val settingsMsg = _settingsMsg.asStateFlow()
    private val _settingsBusy = MutableStateFlow(false)
    val settingsBusy = _settingsBusy.asStateFlow()

    fun accountEmail(): String = service.savedEmail()
    fun clearSettingsMsg() { _settingsMsg.value = null }

    private fun settingsOp(okMsg: String, block: suspend () -> Unit) = viewModelScope.launch {
        _settingsBusy.value = true; _settingsMsg.value = null
        try {
            block(); _settingsMsg.value = okMsg
        } catch (e: Exception) {
            _settingsMsg.value = readable(e)
        } finally {
            _settingsBusy.value = false
        }
    }

    fun changePassword(old: String, new: String) =
        settingsOp("Password aggiornata ✓") { service.changePassword(old, new) }

    fun changeNickname(nick: String) =
        settingsOp("Nome aggiornato ✓") { service.changeNickname(nick) }

    private val tempJobs = mutableMapOf<String, Job>()      // debounce per unità
    private val burstBefore = mutableMapOf<String, AcUnit>() // snapshot per il roll-back del burst

    fun unit(id: String): AcUnit? = _units.value.firstOrNull { it.id == id }

    init {
        viewModelScope.launch { bootstrap() }
        checkForUpdate()
        startPolling()
    }

    @Volatile private var foreground = true
    /** L'app è in primo piano? Il polling gira solo qui, per non consumare in background. */
    fun setForeground(v: Boolean) { foreground = v }

    // ---- stato in tempo reale: rilettura periodica (riflette modifiche fatte col telecomando) ----
    private fun startPolling() = viewModelScope.launch {
        while (true) {
            delay(POLL_MS)
            if (!foreground || _phase.value != Phase.Connected || isBusy()) continue
            val fresh = runCatching { service.loadUnits() }.getOrNull() ?: continue
            if (isBusy()) continue   // un comando può essere partito durante la lettura
            mergeUnits(fresh)
        }
    }

    /** Vero se c'è un comando in volo (invio o debounce temperatura): non aggiornare adesso. */
    private fun isBusy() = _send.value.values.any { it == SendState.Sending } || tempJobs.isNotEmpty()

    /** Applica lo stato fresco senza calpestare le unità con un comando in volo. */
    private fun mergeUnits(fresh: List<AcUnit>) {
        val busy = _send.value.filterValues { it == SendState.Sending }.keys + tempJobs.keys
        _units.value = fresh.map { f -> if (f.id in busy) unit(f.id) ?: f else f }
    }

    /** Controlla se su GitHub c'è una release più recente. Silenzioso: non disturba se fallisce. */
    fun checkForUpdate() = viewModelScope.launch(Dispatchers.IO) {
        _update.value = UpdateChecker(appVersion).check()
    }

    /** All'avvio: prima la sessione salvata, poi (se scaduta) le credenziali salvate, infine login. */
    private suspend fun bootstrap() {
        if (service.hasSession() && service.restore() && tryLoad()) return
        if (service.hasCreds() && runCatching { service.autoLogin() }.getOrDefault(false) && tryLoad()) return
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    fun login(email: String, password: String, remember: Boolean, region: String) {
        _phase.value = Phase.Login(email = email, region = region, busy = true)
        viewModelScope.launch {
            try {
                service.login(email, password, remember, region)
                _phase.value = Phase.Loading
                if (!tryLoad()) _phase.value =
                    Phase.Login(email = email, region = region, error = "Connesso, ma nessuna unità trovata")
            } catch (e: Exception) {
                _phase.value = Phase.Login(email = email, region = region, error = readable(e))
            }
        }
    }

    // ---- registrazione nuovo account ----
    fun startRegister() {
        _phase.value = Phase.Register(email = service.savedEmail(), region = service.savedRegion())
    }

    fun cancelRegister() {
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    /** Passo 1: invia il codice di verifica al nuovo account. */
    fun sendCode(email: String, region: String) {
        _phase.value = Phase.Register(email = email, region = region, busy = true)
        viewModelScope.launch {
            try {
                service.sendRegisterCode(email, region)
                _phase.value = Phase.Register(email = email, region = region, codeSent = true)
            } catch (e: Exception) {
                _phase.value = Phase.Register(email = email, region = region, error = readable(e))
            }
        }
    }

    /** Passo 2: crea l'account. Al successo la sessione è già stabilita (register = login). */
    fun doRegister(email: String, password: String, code: String, region: String, nickname: String) {
        _phase.value = Phase.Register(email = email, region = region, codeSent = true, busy = true)
        viewModelScope.launch {
            try {
                service.register(email, password, code, region, nickname)
                _phase.value = Phase.Loading
                // account nuovo: le unità di solito sono zero → Connected con lista vuota
                runCatching { _units.value = service.loadUnits() }
                _phase.value = Phase.Connected
            } catch (e: Exception) {
                _phase.value = Phase.Register(email = email, region = region, codeSent = true, error = readable(e))
            }
        }
    }

    /** Esci mantenendo le credenziali salvate (rientro automatico al prossimo avvio). */
    fun logout() {
        service.logout()
        _units.value = emptyList(); _send.value = emptyMap()
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    /** Dimentica tutto (sessione + credenziali). */
    fun forget() {
        service.forget()
        _units.value = emptyList(); _send.value = emptyMap()
        _phase.value = Phase.Login(email = "")
    }

    fun refresh() = viewModelScope.launch { runCatching { _units.value = service.loadUnits() } }

    private suspend fun tryLoad(): Boolean = try {
        _units.value = service.loadUnits(); _phase.value = Phase.Connected; true
    } catch (e: Exception) {
        false
    }

    // ---- stato d'invio ----
    private fun setSend(id: String, s: SendState) { _send.value = _send.value + (id to s) }
    private fun holdThenIdle(id: String, ms: Long) = viewModelScope.launch {
        delay(ms)
        if (_send.value[id] != SendState.Sending) setSend(id, SendState.Idle)
    }
    private fun setUnit(u: AcUnit) { _units.value = _units.value.map { if (it.id == u.id) u else it } }

    // ---- comando immediato (accende, modalità, ventola, funzioni) ----
    private fun immediate(id: String, apply: (AcUnit) -> AcUnit, changes: (AcUnit) -> Map<String, Int>) {
        val before = unit(id) ?: return
        val after = apply(before); setUnit(after)
        setSend(id, SendState.Sending)
        viewModelScope.launch {
            try {
                service.push(id, changes(after)); setSend(id, SendState.Ok); holdThenIdle(id, OK_HOLD_MS)
            } catch (e: Exception) {
                setUnit(before); setSend(id, SendState.Error); holdThenIdle(id, ERR_HOLD_MS)
            }
        }
    }

    // ---- temperatura: aggiorna subito, invia UNA volta dopo la quiete (debounce) ----
    private fun debouncedTarget(id: String, newTarget: (AcUnit) -> Float) {
        val cur = unit(id) ?: return
        if (!tempJobs.containsKey(id)) burstBefore[id] = cur  // inizio burst
        setUnit(cur.copy(targetTemp = clampT(newTarget(cur))))
        setSend(id, SendState.Sending)
        tempJobs[id]?.cancel()
        tempJobs[id] = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val before = burstBefore.remove(id) ?: cur
            val target = unit(id)?.targetTemp ?: return@launch
            try {
                service.push(id, targetWire(target)); setSend(id, SendState.Ok); holdThenIdle(id, OK_HOLD_MS)
            } catch (e: Exception) {
                setUnit(before); setSend(id, SendState.Error); holdThenIdle(id, ERR_HOLD_MS)
            } finally {
                tempJobs.remove(id)
            }
        }
    }

    fun stepTarget(id: String, d: Float) = debouncedTarget(id) { it.targetTemp + d }
    fun setTarget(id: String, t: Float) = debouncedTarget(id) { t }

    fun togglePower(id: String) = immediate(id, { it.copy(power = !it.power) }, { powerWire(it.power) })
    fun setMode(id: String, m: Mode) = immediate(id, { it.copy(mode = m) }, { modeChangeWire(it.mode) })
    fun setFan(id: String, f: FanSpeed) = immediate(id, { it.copy(fan = f) }, { fanChangeWire(it.fan) })
    fun toggleSwingV(id: String) = immediate(id, { it.copy(swingV = !it.swingV) }, { swingVWire(it.swingV) })
    fun toggleSwingH(id: String) = immediate(id, { it.copy(swingH = !it.swingH) }, { swingHWire(it.swingH) })
    fun toggleEco(id: String) = immediate(id, { it.copy(eco = !it.eco, turbo = false, night = false) }, { ecoWire(it.eco) })
    fun toggleTurbo(id: String) = immediate(id, { it.copy(turbo = !it.turbo, eco = false, night = false) }, { turboWire(it.turbo) })
    fun toggleNight(id: String) = immediate(id, { it.copy(night = !it.night, eco = false, turbo = false) }, { nightWire(it.night) })
    fun powerAllOff() = _units.value.filter { it.power }.forEach { togglePower(it.id) }

    /** Rinfresca casa: accende tutte le unità online in freddo, 16°, ventola al massimo. */
    fun refreshHouse() = _units.value.filter { it.online }.forEach { u ->
        immediate(
            u.id,
            { it.copy(power = true, mode = Mode.FREDDO, targetTemp = AcUnit.TEMP_MIN,
                      fan = FanSpeed.ALTA, eco = false, turbo = false, night = false) },
            { powerWire(true) + modeChangeWire(Mode.FREDDO) + targetWire(AcUnit.TEMP_MIN) +
                fanChangeWire(FanSpeed.ALTA) },
        )
    }

    private fun clampT(t: Float) = t.coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX)

    private fun readable(e: Exception): String {
        val m = e.message ?: return "Operazione fallita"
        return when {
            "-1008" in m -> "Credenziali non riconosciute"
            "-1036" in m -> "Troppi tentativi, riprova tra qualche minuto"
            "has_been_registered" in m -> "Questo account è già registrato: accedi invece di crearlo"
            "vcode" in m || "-3002" in m -> "Codice di verifica mancante o errato"
            else -> m
        }
    }
}
