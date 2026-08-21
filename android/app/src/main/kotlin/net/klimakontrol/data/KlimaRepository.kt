package net.klimakontrol.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sorgente delle unità e dei comandi. Lo scheletro usa [SampleRepository] (in memoria);
 * quando colleghiamo il cloud, un `CloudRepository` implementerà la stessa interfaccia
 * sopra [net.klimakontrol.data.cloud.CloudClient].
 */
interface KlimaRepository {
    val units: StateFlow<List<AcUnit>>
    fun unit(id: String): AcUnit? = units.value.firstOrNull { it.id == id }
    suspend fun setPower(id: String, on: Boolean)
    suspend fun setTarget(id: String, temp: Float)
    suspend fun setMode(id: String, mode: Mode)
    suspend fun setFan(id: String, fan: FanSpeed)
    suspend fun setFeature(id: String, eco: Boolean? = null, turbo: Boolean? = null, night: Boolean? = null)
    suspend fun refresh() {}
}

/** Implementazione in memoria: aggiornamenti ottimistici immediati, nessuna rete. */
class SampleRepository(initial: List<AcUnit> = Sample.units) : KlimaRepository {
    private val _units = MutableStateFlow(initial)
    override val units: StateFlow<List<AcUnit>> = _units.asStateFlow()

    private fun update(id: String, block: (AcUnit) -> AcUnit) {
        _units.value = _units.value.map { if (it.id == id) block(it) else it }
    }

    override suspend fun setPower(id: String, on: Boolean) = update(id) { it.copy(power = on) }
    override suspend fun setTarget(id: String, temp: Float) =
        update(id) { it.copy(targetTemp = temp.coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX)) }
    override suspend fun setMode(id: String, mode: Mode) = update(id) { it.copy(mode = mode) }
    override suspend fun setFan(id: String, fan: FanSpeed) = update(id) { it.copy(fan = fan) }
    override suspend fun setFeature(id: String, eco: Boolean?, turbo: Boolean?, night: Boolean?) =
        update(id) {
            // eco/turbo/notte sono mutuamente esclusive
            when {
                eco == true -> it.copy(eco = true, turbo = false, night = false)
                turbo == true -> it.copy(eco = false, turbo = true, night = false)
                night == true -> it.copy(eco = false, turbo = false, night = true)
                else -> it.copy(eco = eco ?: it.eco, turbo = turbo ?: it.turbo, night = night ?: it.night)
            }
        }
}
