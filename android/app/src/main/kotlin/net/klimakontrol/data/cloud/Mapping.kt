package net.klimakontrol.data.cloud

import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.data.Reachability
import kotlin.math.roundToInt

/** Corrispondenze filo <-> dominio (dai 79 parametri della libreria Python). */
object Wire {
    // tcl_mode
    private val MODE = mapOf(1 to Mode.CALDO, 2 to Mode.DEUMIDIFICA, 3 to Mode.FREDDO, 4 to Mode.VENTOLA, 5 to Mode.AUTO)
    private val MODE_REV = MODE.entries.associate { (k, v) -> v to k }
    fun mode(v: Int): Mode = MODE[v] ?: Mode.AUTO
    fun modeWire(m: Mode): Int = MODE_REV.getValue(m)

    // tcl_mark (velocità ventola): l'ordine dei valori non è numerico
    private val FAN = mapOf(0 to FanSpeed.AUTO, 1 to FanSpeed.BASSA, 4 to FanSpeed.MEDIO_BASSA,
        2 to FanSpeed.MEDIA, 5 to FanSpeed.MEDIO_ALTA, 3 to FanSpeed.ALTA)
    private val FAN_REV = FAN.entries.associate { (k, v) -> v to k }
    fun fan(v: Int): FanSpeed = FAN[v] ?: FanSpeed.AUTO
    fun fanWire(f: FanSpeed): Int = FAN_REV.getValue(f)
}

/** Costruisce un [AcUnit] dallo stato grezzo restituito da `sdkcontrol get`. */
fun cloudUnit(dev: CloudDevice, s: Map<String, Int>, online: Boolean): AcUnit {
    fun tenths(k: String): Float? = s[k]?.let { it / 10f }
    val err = s["ac_errcode"]?.takeIf { it != 0 }?.let { "E$it" }
    return AcUnit(
        id = dev.did,
        name = dev.name.ifBlank { dev.mac },
        reachable = if (online) Reachability.ONLINE else Reachability.OFFLINE,
        power = (s["pwr"] ?: 0) == 1,
        mode = Wire.mode(s["tcl_mode"] ?: 5),
        targetTemp = tenths("save_temp") ?: 23f,
        ambientTemp = tenths("envtemp"),
        fan = Wire.fan(s["tcl_mark"] ?: 0),
        eco = (s["ecomode"] ?: 0) == 1,
        turbo = (s["pwfmode"] ?: 0) == 1,
        night = (s["tcl_slp"] ?: 0) == 1,
        // il modulo riporta lo swing come ac_vdir/ac_hdir (NON tcl_*): visto sul filo il 2026-08-22
        swingV = (s["ac_vdir"] ?: 0) != 0,
        swingH = (s["ac_hdir"] ?: 0) != 0,
        errorCode = err,
    )
}

/** I parametri da chiedere in lettura. Il modulo ignora la richiesta e ritorna comunque il suo
 *  set fisso di 10 (visto sul filo il 2026-08-22, identico su tutte le unità di questo modello):
 *  pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode. */
val READ_PARAMS = listOf(
    "pwr", "tcl_mode", "save_temp", "tcl_mark",
    "ecomode", "pwfmode", "tcl_slp", "ac_vdir", "ac_hdir", "ac_errcode",
)

/** Comandi -> parametri sul filo. */
fun powerWire(on: Boolean) = mapOf("pwr" to if (on) 1 else 0)
fun targetWire(temp: Float) = mapOf("save_temp" to (temp * 10f).roundToInt())
fun modeChangeWire(m: Mode) = mapOf("tcl_mode" to Wire.modeWire(m))
fun fanChangeWire(f: FanSpeed) = mapOf("tcl_mark" to Wire.fanWire(f))
fun ecoWire(on: Boolean) = mapOf("ecomode" to if (on) 1 else 0)
fun turboWire(on: Boolean) = mapOf("pwfmode" to if (on) 1 else 0)
fun nightWire(on: Boolean) = mapOf("tcl_slp" to if (on) 1 else 0)
// oscillazione: il modulo usa ac_vdir/ac_hdir. Valore "on" derivato dal sorgente dell'app
// (verticale=7, orizzontale=1): da confermare sul filo di questa unità.
fun swingVWire(on: Boolean) = mapOf("ac_vdir" to if (on) 7 else 0)
fun swingHWire(on: Boolean) = mapOf("ac_hdir" to if (on) 1 else 0)
