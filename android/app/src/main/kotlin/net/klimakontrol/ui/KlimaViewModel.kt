package net.klimakontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.KlimaRepository
import net.klimakontrol.data.Mode
import net.klimakontrol.data.SampleRepository

class KlimaViewModel : ViewModel() {

    // Per ora sorgente d'esempio; un factory potrà iniettare CloudRepository al login.
    private val repo: KlimaRepository = SampleRepository()

    val units get() = repo.units
    fun unit(id: String): AcUnit? = repo.unit(id)

    fun togglePower(id: String) = withUnit(id) { repo.setPower(id, !it.power) }
    fun stepTarget(id: String, delta: Float) = withUnit(id) { repo.setTarget(id, it.targetTemp + delta) }
    fun toggleEco(id: String) = withUnit(id) { repo.setFeature(id, eco = !it.eco) }
    fun toggleTurbo(id: String) = withUnit(id) { repo.setFeature(id, turbo = !it.turbo) }
    fun toggleNight(id: String) = withUnit(id) { repo.setFeature(id, night = !it.night) }

    fun setTarget(id: String, temp: Float) = bg { repo.setTarget(id, temp) }
    fun setMode(id: String, mode: Mode) = bg { repo.setMode(id, mode) }
    fun setFan(id: String, fan: FanSpeed) = bg { repo.setFan(id, fan) }
    fun powerAllOff() = bg { units.value.filter { it.power }.forEach { repo.setPower(it.id, false) } }

    private fun bg(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun withUnit(id: String, block: suspend (AcUnit) -> Unit) {
        viewModelScope.launch { repo.unit(id)?.let { block(it) } }
    }
}
