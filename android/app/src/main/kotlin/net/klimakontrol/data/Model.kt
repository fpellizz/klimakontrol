package net.klimakontrol.data

/** Le cinque modalità del climatizzatore (etichette italiane, come nell'app). */
enum class Mode(val label: String) {
    CALDO("Caldo"),
    FREDDO("Freddo"),
    DEUMIDIFICA("Deumidifica"),
    VENTOLA("Ventola"),
    AUTO("Auto"),
}

/** Velocità ventola: solo gli step che il modello dichiara vanno mostrati. */
enum class FanSpeed(val label: String, val level: Int) {
    AUTO("auto", 0),
    BASSA("bassa", 1),
    MEDIO_BASSA("medio-bassa", 2),
    MEDIA("media", 3),
    MEDIO_ALTA("medio-alta", 4),
    ALTA("alta", 5),
}

/** Stato di raggiungibilità dell'unità (il controllo passa dal cloud). */
enum class Reachability { ONLINE, OFFLINE }

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
    val quiet: Boolean = false,     // qtmode: livello silenzioso/bassissimo
    val swingV: Boolean = false,    // tcl_vdir: oscillazione verticale (su/giù)
    val swingH: Boolean = false,    // tcl_hdir: oscillazione orizzontale (sinistra/destra)
    val health: Boolean = false,    // ac_health: modalità "salute" (ionizzatore)
    val display: Boolean = false,   // bglight: display/retroilluminazione dell'unità
    val errorCode: String? = null,
    // parametri che il modulo ha davvero riportato in lettura: è la sua lista di capacità.
    // Vuoto = sconosciuto (offline o lettura fallita): in quel caso non nascondiamo nulla.
    val caps: Set<String> = emptySet(),
) {
    val online: Boolean get() = reachable == Reachability.ONLINE

    /** Il modulo gestisce [wireKey]? Se non sappiamo le capacità (caps vuoto), assumiamo di sì. */
    fun supports(wireKey: String): Boolean = caps.isEmpty() || wireKey in caps

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
