package net.klimakontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

class KlimaViewModel(app: Application) : AndroidViewModel(app) {

    private val service = CloudService(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase = _phase.asStateFlow()

    private val _units = MutableStateFlow<List<AcUnit>>(emptyList())
    val units = _units.asStateFlow()

    fun unit(id: String): AcUnit? = _units.value.firstOrNull { it.id == id }

    init {
        viewModelScope.launch {
            if (service.hasSession() && service.restore()) {
                loadInto(onFail = Phase.Login(service.savedEmail(), "Sessione scaduta, rientra"))
            } else {
                _phase.value = Phase.Login(email = service.savedEmail())
            }
        }
    }

    fun login(email: String, password: String) {
        _phase.value = Phase.Login(email = email, busy = true)
        viewModelScope.launch {
            try {
                service.login(email, password)
                _phase.value = Phase.Loading
                loadInto(onFail = Phase.Login(email, "Connesso ma nessuna unità"))
            } catch (e: Exception) {
                _phase.value = Phase.Login(email = email, error = readable(e))
            }
        }
    }

    fun logout() {
        service.logout()
        _units.value = emptyList()
        _phase.value = Phase.Login(email = service.savedEmail())
    }

    fun refresh() = viewModelScope.launch {
        runCatching { _units.value = service.loadUnits() }
    }

    private suspend fun loadInto(onFail: Phase) {
        try {
            _units.value = service.loadUnits()
            _phase.value = Phase.Connected
        } catch (e: Exception) {
            _phase.value = onFail
        }
    }

    // ---- comandi ottimistici (aggiorna subito, invia, torna indietro se fallisce) ----
    private fun optimistic(id: String, apply: (AcUnit) -> AcUnit, changes: (AcUnit) -> Map<String, Int>) {
        val before = _units.value.firstOrNull { it.id == id } ?: return
        val after = apply(before)
        _units.value = _units.value.map { if (it.id == id) after else it }
        viewModelScope.launch {
            try {
                service.push(id, changes(after))
            } catch (e: Exception) {
                _units.value = _units.value.map { if (it.id == id) before else it }
            }
        }
    }

    fun togglePower(id: String) = optimistic(id, { it.copy(power = !it.power) }, { powerWire(it.power) })
    fun setTarget(id: String, t: Float) = optimistic(id, { it.copy(targetTemp = clampT(t)) }, { targetWire(it.targetTemp) })
    fun stepTarget(id: String, d: Float) = optimistic(id, { it.copy(targetTemp = clampT(it.targetTemp + d)) }, { targetWire(it.targetTemp) })
    fun setMode(id: String, m: Mode) = optimistic(id, { it.copy(mode = m) }, { modeChangeWire(it.mode) })
    fun setFan(id: String, f: FanSpeed) = optimistic(id, { it.copy(fan = f) }, { fanChangeWire(it.fan) })
    fun toggleEco(id: String) = optimistic(id, { it.copy(eco = !it.eco, turbo = false, night = false) }, { ecoWire(it.eco) })
    fun toggleTurbo(id: String) = optimistic(id, { it.copy(turbo = !it.turbo, eco = false, night = false) }, { turboWire(it.turbo) })
    fun toggleNight(id: String) = optimistic(id, { it.copy(night = !it.night, eco = false, turbo = false) }, { nightWire(it.night) })
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
