package net.klimakontrol.data

import androidx.annotation.StringRes
import net.klimakontrol.R

/** Le cinque modalità del climatizzatore; l'etichetta è una risorsa (localizzata). */
enum class Mode(@StringRes val labelRes: Int) {
    CALDO(R.string.mode_caldo),
    FREDDO(R.string.mode_freddo),
    DEUMIDIFICA(R.string.mode_deumidifica),
    VENTOLA(R.string.mode_ventola),
    AUTO(R.string.mode_auto),
}

/** Velocità ventola: solo gli step che il modello dichiara vanno mostrati. */
enum class FanSpeed(@StringRes val labelRes: Int, val level: Int) {
    AUTO(R.string.fan_auto, 0),
    BASSA(R.string.fan_bassa, 1),
    MEDIO_BASSA(R.string.fan_medio_bassa, 2),
    MEDIA(R.string.fan_media, 3),
    MEDIO_ALTA(R.string.fan_medio_alta, 4),
    ALTA(R.string.fan_alta, 5),
}

/** Stato di raggiungibilità dell'unità (il controllo passa dal cloud). */
enum class Reachability { ONLINE, OFFLINE }

/** Una "casa" definita dall'utente in locale (non arriva dal cloud): un gruppo per filtrare. */
data class Home(val id: String, val name: String)

/**
 * Una unità (split). `targetTemp` è il setpoint (su questi moduli è `save_temp`, decimi di grado
 * nel protocollo, qui in gradi). `ambientTemp` è la temperatura ambiente letta.
 */
data class AcUnit(
    val id: String,
    val name: String,
    val reachable: Reachability = Reachability.ONLINE,
    val power: Boolean = false,
    val mode: Mode = Mode.FREDDO,
    val targetTemp: Float = 23f,
    val ambientTemp: Float? = null,
    val fan: FanSpeed = FanSpeed.AUTO,
    val eco: Boolean = false,
    val turbo: Boolean = false,
    val night: Boolean = false,
    val swingV: Boolean = false,    // ac_vdir: oscillazione verticale (su/giù)
    val swingH: Boolean = false,    // ac_hdir: oscillazione orizzontale (sinistra/destra)
    val errorCode: String? = null,
) {
    val online: Boolean get() = reachable == Reachability.ONLINE

    companion object {
        const val TEMP_MIN = 16f
        const val TEMP_MAX = 31f
        const val TEMP_STEP = 0.5f
    }
}

/** Dati d'esempio, così lo scheletro mostra subito il layout senza rete. */
object Sample {
    val units = listOf(
        AcUnit(
            id = "salone", name = "Salone", power = true, mode = Mode.FREDDO,
            targetTemp = 23f, ambientTemp = 24.5f, fan = FanSpeed.MEDIA,
        ),
        AcUnit(
            id = "camera", name = "camera", power = false, mode = Mode.CALDO,
            targetTemp = 21f, ambientTemp = 22.0f,
        ),
        AcUnit(
            id = "lavoro", name = "lavoro", reachable = Reachability.OFFLINE,
            mode = Mode.AUTO, ambientTemp = null,
        ),
    )
}
