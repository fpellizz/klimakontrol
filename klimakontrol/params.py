"""Dizionario dei parametri esposti dai moduli WiFi TCL/BroadLink DNA.

Ricostruito da `src/panel/data.js` dell'app "Intelligent AC" (com.ab.smartDevice),
recuperato dalle source map incluse per errore nell'APK.

Ogni parametro ha: etichetta in italiano, categoria, tipo, e per gli enumerati la
mappa valore -> significato. `scale` indica il fattore per passare dal valore sul
filo all'unita' leggibile (es. temp = decimi di grado).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Optional

# categorie
CONTROL = "controllo"
SENSOR = "sensore"
COMFORT = "comfort"
DIAG = "diagnostica"
ENERGY = "energia"
SYSTEM = "sistema"

ON_OFF = {0: "off", 1: "on"}

MODE = {1: "caldo", 2: "deumidifica", 3: "freddo", 4: "ventola", 5: "auto"}
FAN = {0: "auto", 1: "bassa", 4: "medio-bassa", 2: "media", 5: "medio-alta", 3: "alta"}
VDIR = {0: "ferma", 7: "oscillante"}
HDIR = {0: "ferma", 1: "oscillante"}
TEMP_UNIT = {0: "celsius", 1: "fahrenheit"}


@dataclass(frozen=True)
class Param:
    name: str
    label: str
    category: str
    kind: str = "int"          # int | enum | text
    unit: Optional[str] = None
    scale: float = 1.0
    values: Dict[int, str] = field(default_factory=dict)
    writable: bool = False

    def decode(self, raw: Any) -> Any:
        """Dal valore sul filo al valore leggibile."""
        if raw is None:
            return None
        if self.kind == "enum":
            return self.values.get(raw, raw)
        if self.scale != 1.0:
            try:
                return round(float(raw) * self.scale, 2)
            except (TypeError, ValueError):
                return raw
        return raw

    def encode(self, value: Any) -> Any:
        """Dal valore leggibile al valore da mettere sul filo."""
        if self.kind == "enum" and isinstance(value, str):
            for k, v in self.values.items():
                if v == value.lower():
                    return k
            raise ValueError("valore non valido per %s: %r (ammessi: %s)"
                             % (self.name, value, ", ".join(sorted(self.values.values()))))
        if self.scale != 1.0:
            return int(round(float(value) / self.scale))
        return int(value)


def _p(name, label, category, **kw):
    return Param(name=name, label=label, category=category, **kw)


PARAMS: Dict[str, Param] = {p.name: p for p in (
    # --- controllo principale
    _p("pwr", "accensione", CONTROL, kind="enum", values=ON_OFF, writable=True),
    _p("temp", "temperatura impostata", CONTROL, unit="C", scale=0.1, writable=True),
    _p("tcl_mode", "modalita", CONTROL, kind="enum", values=MODE, writable=True),
    _p("tcl_mark", "velocita ventola", CONTROL, kind="enum", values=FAN, writable=True),
    _p("tcl_vdir", "oscillazione verticale", CONTROL, kind="enum", values=VDIR, writable=True),
    _p("tcl_hdir", "oscillazione orizzontale", CONTROL, kind="enum", values=HDIR, writable=True),
    _p("tempunit", "unita di temperatura", CONTROL, kind="enum", values=TEMP_UNIT, writable=True),

    # --- letture ambientali
    _p("envtemp", "temperatura ambiente", SENSOR, unit="C"),
    _p("envtempoutdoor", "temperatura esterna", SENSOR, unit="C"),
    _p("humidity", "umidita", SENSOR, unit="%"),
    _p("pm25", "PM2.5", SENSOR, unit="ug/m3"),
    _p("pm25_class", "classe PM2.5", SENSOR),
    _p("hcho", "formaldeide", SENSOR),
    _p("co2_data", "CO2", SENSOR, unit="ppm"),
    _p("tvoc_vol", "TVOC volumetrico", SENSOR),
    _p("tvoc_q", "TVOC massico", SENSOR),
    _p("tvoc_class", "classe TVOC", SENSOR),
    _p("air_quality", "qualita aria", SENSOR),
    _p("warm_prompt", "avviso qualita aria", SENSOR),

    # --- comfort
    _p("ac_slp", "modalita notte", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("tcl_slp", "modalita notte (variante)", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("ecomode", "risparmio energetico", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("savemode", "modalita risparmio", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("save_state", "stato risparmio in corso", COMFORT, kind="enum", values=ON_OFF),
    _p("pwfmode", "turbo", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("qtmode", "silenzioso", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("ac_health", "modalita salute", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("no_wfeeling", "senza sensazione d'aria", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("8heat", "mantenimento 8 C", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("el_heat", "resistenza elettrica", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("desicmode", "deumidifica", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("smartdesic", "antimuffa intelligente", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("3dairmode", "flusso 3D", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("ac_hwind", "aria calda alta", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("ac_lwind", "aria fresca", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("man_wind", "flusso su presenza", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("beep", "suono tasti", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("bglight", "retroilluminazione", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("ac_photos", "sensore luminosita", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("auto_study", "autoapprendimento", COMFORT, kind="enum", values=ON_OFF, writable=True),
    _p("autostd_cmd", "comando autoapprendimento", COMFORT),
    _p("dynamo", "modalita generatore", COMFORT, kind="enum", values=ON_OFF),
    _p("camera", "telecamera", COMFORT, kind="enum", values=ON_OFF),

    # --- diagnostica
    _p("ac_errcode", "codice guasto", DIAG),
    _p("ac_type", "tipo macchina", DIAG),
    _p("tcl_type", "categoria macchina", DIAG),
    _p("devicetype", "tipo dispositivo", DIAG),
    _p("if_function", "maschera funzioni supportate", DIAG),
    _p("compressor_hz", "frequenza compressore", DIAG, unit="Hz"),
    _p("compressor_opt", "livello compressore", DIAG),
    _p("in_fan_rpm", "giri ventola interna", DIAG, unit="rpm"),
    _p("out_fan_rpm", "giri ventola esterna", DIAG, unit="rpm"),
    _p("in_coil_temp", "temperatura batteria interna", DIAG, unit="C"),
    _p("in_vent_temp", "temperatura mandata interna", DIAG, unit="C"),
    _p("out_coil_temp", "temperatura batteria esterna", DIAG, unit="C"),
    _p("out_vent_temp", "temperatura scarico esterno", DIAG, unit="C"),
    _p("out_volt", "tensione unita esterna", DIAG),
    _p("out_cur", "corrente unita esterna", DIAG),
    _p("four_way_val", "valvola a 4 vie", DIAG, kind="enum", values=ON_OFF),
    _p("solenoid_val", "elettrovalvola", DIAG, kind="enum", values=ON_OFF),
    _p("evaportor", "pulizia evaporatore", DIAG, kind="enum", values=ON_OFF, writable=True),
    _p("clean_check", "controllo pulizia", DIAG, kind="enum", values=ON_OFF),
    _p("filter_check", "controllo filtri", DIAG, kind="enum", values=ON_OFF),
    _p("if_filterdirty", "filtri sporchi", DIAG, kind="enum", values=ON_OFF),
    _p("humidity_check", "sensore umidita presente", DIAG, kind="enum", values=ON_OFF),

    # --- energia
    _p("target_kwh", "obiettivo consumo", ENERGY, unit="kWh", writable=True),
    _p("save_temp", "temperatura fissa risparmio", ENERGY, unit="C", scale=0.1, writable=True),
    _p("save_beg_t", "inizio ultima sessione risparmio", ENERGY, kind="text"),
    _p("sava_stp_t", "fine ultima sessione risparmio", ENERGY, kind="text"),
    _p("save_last_mode", "ultima modalita risparmio", ENERGY),
    _p("save_last_temp", "ultima temperatura risparmio", ENERGY, unit="C", scale=0.1),

    # --- sistema e prenotazioni
    _p("timezone", "fuso orario del modulo", SYSTEM),
    _p("site_info", "posizione", SYSTEM, kind="text"),
    _p("sub_on_off", "prenotazione attiva", SYSTEM, kind="enum", values=ON_OFF, writable=True),
    _p("sub_time", "orario prenotazione", SYSTEM, kind="text", writable=True),
    _p("sub_weekday", "giorni prenotazione", SYSTEM, kind="text", writable=True),
    _p("if_subs", "flag prenotazione", SYSTEM),
    _p("if_cycle", "flag ciclicita", SYSTEM),
    _p("cmd", "comando prenotazione", SYSTEM, kind="text"),
)}

#: parametri che vale la pena leggere in un colpo solo
READ_SET = tuple(PARAMS)

#: sottoinsieme per la schermata principale
BASIC_SET = ("pwr", "temp", "envtemp", "tcl_mode", "tcl_mark", "tcl_vdir", "tcl_hdir",
             "ac_slp", "ecomode", "pwfmode", "qtmode", "ac_errcode")


def decode_status(raw: Dict[str, Any]) -> Dict[str, Any]:
    """Traduce uno stato grezzo in valori leggibili, ignorando gli sconosciuti."""
    out = {}
    for k, v in raw.items():
        p = PARAMS.get(k)
        out[k] = p.decode(v) if p else v
    return out


def encode_changes(changes: Dict[str, Any]) -> Dict[str, Any]:
    """Traduce valori leggibili (es. tcl_mode="freddo", temp=23.5) in valori sul filo."""
    out = {}
    for k, v in changes.items():
        p = PARAMS.get(k)
        if p is None:
            out[k] = v
            continue
        if not p.writable:
            raise ValueError("il parametro %s e' di sola lettura" % k)
        out[k] = p.encode(v)
    return out


def describe(raw: Dict[str, Any], categories=None) -> str:
    """Riepilogo leggibile di uno stato, per la CLI."""
    lines = []
    for name, value in raw.items():
        p = PARAMS.get(name)
        if p is None:
            lines.append("  %-22s %s" % (name, value))
            continue
        if categories and p.category not in categories:
            continue
        val = p.decode(value)
        unit = (" " + p.unit) if p.unit and p.kind != "enum" else ""
        lines.append("  %-22s %s%s" % (p.label, val, unit))
    return "\n".join(lines)


def supported_functions(if_function: Optional[int]) -> Optional[int]:
    """`if_function` e' una maschera di bit: quali funzioni il modello ha davvero.

    La corrispondenza bit -> funzione non e' ancora mappata: va ricavata
    confrontando il valore con i pulsanti che l'app mostra sul tuo modello.
    Restituisce il valore grezzo per ora.
    """
    return if_function
