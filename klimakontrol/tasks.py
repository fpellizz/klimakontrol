"""Pianificazioni interne al modulo WiFi.

Il modulo esegue le pianificazioni da solo: una volta scritte, funzionano anche
a telefono spento e con internet giu'. Cinque tipi, ognuno con la sua lista.

    type 0  timerlist    una volta, a data e ora
    type 1  delaylist    ritardo ("tra due ore spegni")
    type 2  periodlist   ricorrente: ora + giorni della settimana
    type 3  cyclelist    ciclico, con due comandi alternati (data / data2)
    type 4  randomlist   casuale entro una finestra

ATTENZIONE - la trappola del fuso orario. Il firmware ragiona in UTC+8, eredita'
del cloud cinese. L'app converte ogni orario sommando (8 - offset_locale) ore.
Per l'Italia significa +6 ore in ora legale e +7 in ora solare. Sbagliare questa
conversione e' esattamente il motivo per cui i timer dell'app ufficiale scattano
quando gli pare: nel loro codice ci sono tre implementazioni diverse della stessa
conversione, con i commenti di debug ancora dentro.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

DEVICE_TZ = 8  # il firmware vive in UTC+8

TYPE_ONCE = 0
TYPE_DELAY = 1
TYPE_PERIOD = 2
TYPE_CYCLE = 3
TYPE_RANDOM = 4

TYPE_NAMES = {
    TYPE_ONCE: "una volta",
    TYPE_DELAY: "ritardo",
    TYPE_PERIOD: "ricorrente",
    TYPE_CYCLE: "ciclico",
    TYPE_RANDOM: "casuale",
}

LIST_KEYS = {
    TYPE_ONCE: "timerlist",
    TYPE_DELAY: "delaylist",
    TYPE_PERIOD: "periodlist",
    TYPE_CYCLE: "cyclelist",
    TYPE_RANDOM: "randomlist",
}

DATETIME_FMT = "%Y-%m-%d %H:%M:%S"
TIME_FMT = "%H:%M:%S"

#: comandi accettati dal modulo (il payload sul filo passa dal livello nativo:
#: vedi docs/protocol.md, sezione "pianificazioni")
CMD_LIST = "dev_tasklist"
CMD_ADD = "dev_taskadd"
CMD_DATA = "dev_taskdata"
CMD_DELETE = "dev_taskdel"

WEEKDAYS = ("lun", "mar", "mer", "gio", "ven", "sab", "dom")


def time_format(task_type: int) -> str:
    """I tipi 0 e 1 usano data e ora, gli altri solo l'ora."""
    return DATETIME_FMT if task_type in (TYPE_ONCE, TYPE_DELAY) else TIME_FMT


def local_utc_offset_hours(when: Optional[datetime] = None) -> float:
    """Offset locale in ore, tenendo conto dell'ora legale."""
    when = when or datetime.now()
    off = when.astimezone().utcoffset()
    return (off.total_seconds() / 3600.0) if off else 0.0


def to_device_time(local: datetime, tz_offset: Optional[float] = None) -> datetime:
    """Converte un orario locale nell'orario che il modulo si aspetta."""
    if tz_offset is None:
        tz_offset = local_utc_offset_hours(local)
    return local + timedelta(hours=DEVICE_TZ - tz_offset)


def from_device_time(device: datetime, tz_offset: Optional[float] = None) -> datetime:
    """Converte un orario riportato dal modulo in orario locale."""
    if tz_offset is None:
        tz_offset = local_utc_offset_hours(device)
    return device - timedelta(hours=DEVICE_TZ - tz_offset)


@dataclass
class Task:
    """Una pianificazione."""
    type: int
    time: datetime
    enable: int = 1
    index: Optional[int] = None
    endtime: Optional[datetime] = None
    weekday: List[int] = field(default_factory=list)   # 0 = lunedi
    status: Dict[str, Any] = field(default_factory=dict)       # stato da applicare
    status2: Dict[str, Any] = field(default_factory=dict)      # secondo comando (tipo 3)
    raw: Dict[str, Any] = field(default_factory=dict)

    # -- serializzazione

    def to_wire(self, tz_offset: Optional[float] = None) -> Dict[str, Any]:
        """Struttura da passare a `dev_taskadd`, con orari gia' convertiti."""
        from .local import build_params_vals

        fmt = time_format(self.type)
        out: Dict[str, Any] = {
            "type": self.type,
            "enable": self.enable,
            "time": to_device_time(self.time, tz_offset).strftime(fmt),
        }
        if self.index is not None:
            out["index"] = self.index
        if self.endtime is not None:
            out["endtime"] = to_device_time(self.endtime, tz_offset).strftime(fmt)
        if self.weekday:
            out["week"] = sum(1 << d for d in self.weekday)
        if self.status:
            out["data"] = build_params_vals(self.status)
        if self.status2:
            out["data2"] = build_params_vals(self.status2)
        return out

    @classmethod
    def from_wire(cls, raw: Dict[str, Any], task_type: int,
                  tz_offset: Optional[float] = None) -> "Task":
        from .local import flatten_params_vals

        fmt = time_format(task_type)
        def parse(value):
            if not value:
                return None
            try:
                dt = datetime.strptime(value, fmt)
            except ValueError:
                return None
            if fmt == TIME_FMT:
                today = datetime.now()
                dt = dt.replace(year=today.year, month=today.month, day=today.day)
            return from_device_time(dt, tz_offset)

        week = raw.get("week", 0) or 0
        return cls(
            type=task_type,
            time=parse(raw.get("time")) or datetime.now(),
            enable=raw.get("enable", 1),
            index=raw.get("index"),
            endtime=parse(raw.get("endtime")),
            weekday=[d for d in range(7) if week & (1 << d)],
            status=flatten_params_vals(raw.get("data") or {}),
            status2=flatten_params_vals(raw.get("data2") or {}),
            raw=raw,
        )

    def describe(self) -> str:
        from .params import PARAMS
        when = self.time.strftime("%d/%m %H:%M" if self.type in (TYPE_ONCE, TYPE_DELAY) else "%H:%M")
        parts = ["%-11s %s" % (TYPE_NAMES.get(self.type, self.type), when)]
        if self.weekday:
            parts.append("[" + " ".join(WEEKDAYS[d] for d in self.weekday) + "]")
        if self.status:
            bits = []
            for k, v in self.status.items():
                p = PARAMS.get(k)
                bits.append("%s=%s" % (p.label if p else k, p.decode(v) if p else v))
            parts.append("-> " + ", ".join(bits))
        if not self.enable:
            parts.append("(disattivata)")
        return " ".join(parts)


def parse_task_list(response: Dict[str, Any],
                    tz_offset: Optional[float] = None) -> List[Task]:
    """Legge la risposta di `dev_tasklist` e restituisce tutte le pianificazioni."""
    data = response.get("data", response)
    out: List[Task] = []
    for task_type, key in LIST_KEYS.items():
        for raw in (data.get(key) or []):
            out.append(Task.from_wire(raw, task_type, tz_offset))
    return out
