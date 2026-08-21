package net.klimakontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.data.cloud.CloudService
import net.klimakontrol.data.cloud.ecoWire
import net.klimakontrol.data.cloud.fanChangeWire
import net.klimakontrol.data.cloud.modeChangeWire
import net.klimakontrol.data.cloud.nightWire
import net.klimakontrol.data.cloud.powerWire
import net.klimakontrol.data.cloud.targetWire
import net.klimakontrol.data.cloud.turboWire

sealed interface Phase {
    data object Loading : Phase
    data class Login(val email: String = "", val error: String? = null, val busy: Boolean = false) : Phase
    data object Connected : Phase
}

/** Esito visibile di un comando per una singola unità. */
enum class SendState { Idle, Sending, Ok, Error }

private const val DEBOUNCE_MS = 400L
private const val OK_HOLD_MS = 900L
private const val ERR_HOLD_MS = 1600L

class KlimaViewModel(app: Application) : AndroidViewModel(app) {

    private val service = CloudService(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase = _phase.asStateFlow()

    private val _units = MutableStateFlow<List<AcUnit>>(emptyList())
    val units = _units.asStateFlow()

    private val _send = MutableStateFlow<Map<String, SendState>>(emptyMap())
    val send = _send.asStateFlow()

    private val tempJobs = mutableMapOf<String, Job>()      // debounce per unità
    private val burstBefore = mutableMapOf<String, AcUnit>() // snapshot per il roll-back del burst

    fun unit(id: String): AcUnit? = _units.value.firstOrNull { it.id == id }

    init {
        viewModelScope.launch { bootstrap() }
    }

    /** All'avvio: prima la sessione salvata, poi (se scaduta) le credenziali salvate, infine login. */
    private suspend fun bootstrap() {
        if (service.hasSession() && service.restore() && tryLoad()) return
        if (service.hasCreds() && runCatching { service.autoLogin() }.getOrDefault(false) && tryLoad()) return
        _phase.value = Phase.Login(email = service.savedEmail())
    }

    fun login(email: String, password: String, remember: Boolean) {
        _phase.value = Phase.Login(email = email, busy = true)
        viewModelScope.launch {
            try {
                service.login(email, password, remember)
                _phase.value = Phase.Loading
                if (!tryLoad()) _phase.value = Phase.Login(email, "Connesso, ma nessuna unità trovata")
            } catch (e: Exception) {
                _phase.value = Phase.Login(email = email, error = readable(e))
            }
        }
    }

    fun logout() {
        service.logout()
        _units.value = emptyList(); _send.value = emptyMap()
        _phase.value = Phase.Login(email = service.savedEmail())
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
    fun toggleEco(id: String) = immediate(id, { it.copy(eco = !it.eco, turbo = false, night = false) }, { ecoWire(it.eco) })
    fun toggleTurbo(id: String) = immediate(id, { it.copy(turbo = !it.turbo, eco = false, night = false) }, { turboWire(it.turbo) })
    fun toggleNight(id: String) = immediate(id, { it.copy(night = !it.night, eco = false, turbo = false) }, { nightWire(it.night) })
    fun powerAllOff() = _units.value.filter { it.power }.forEach { togglePower(it.id) }

    private fun clampT(t: Float) = t.coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX)

    private fun readable(e: Exception): String {
        val m = e.message ?: return "Login fallito"
        return when {
            "-1008" in m -> "Credenziali non riconosciute"
            "-1036" in m -> "Troppi tentativi, riprova tra qualche minuto"
            else -> m
        }
    }
}
