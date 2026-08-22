"""Interfaccia a riga di comando.

    klimakontrol login                    autentica e memorizza la sessione
    klimakontrol list                     elenco unita'
    klimakontrol status <unita>           stato leggibile
    klimakontrol status <unita> --full    tutti i parametri, diagnostica inclusa
    klimakontrol on|off <unita>
    klimakontrol set <unita> temp=23 tcl_mode=freddo tcl_mark=auto
    klimakontrol online                   chi risponde, in una sola chiamata
    klimakontrol energy <unita> [hour|day|month]
    klimakontrol discover                 cerca moduli sulla rete locale
    klimakontrol raw <unita>              JSON grezzo mascherato, per il debug

`<unita>` e' il numero mostrato da `list` oppure un pezzo del nome.
Con `--transport local` si forza la rete locale, con `cloud` il passaggio dal cloud.
Il default e' `auto`: prova la rete locale e ripiega sul cloud.
"""

from __future__ import annotations

import argparse
import getpass
import json
import sys
from typing import Any, Dict, List, Optional

from . import session
from .cloud import (AuthError, CloudClient, CloudDevice, CloudError, RateLimitError,
                    REGION_TRY_ORDER, REGIONS, login_any_region)
from .local import Device, LocalClient, LocalError, discover
from .params import BASIC_SET, PARAMS, decode_status, describe, encode_changes, wire_key


def _pick(devices: List[CloudDevice], token: str) -> CloudDevice:
    if not devices:
        sys.exit("Nessuna unita' memorizzata: lancia prima `klimakontrol login`.")
    if token.isdigit():
        idx = int(token)
        if not 1 <= idx <= len(devices):
            sys.exit("Numero fuori intervallo: sono 1..%d" % len(devices))
        return devices[idx - 1]
    for d in devices:
        if token.lower() in (d.name or "").lower():
            return d
    sys.exit("Nessuna unita' corrisponde a %r" % token)


def _local_client(dev: CloudDevice) -> Optional[LocalClient]:
    if not (dev.lanaddr and dev.aeskey and dev.mac):
        return None
    return LocalClient(Device(host=dev.lanaddr, mac=dev.mac, key=dev.aeskey,
                              devtype=dev.devtype or 0x507C,
                              device_id=dev.local_id, name=dev.name))


def _read_state(cli: CloudClient, dev: CloudDevice, transport: str,
                names: Optional[List[str]] = None) -> Dict[str, Any]:
    if transport in ("auto", "local"):
        lc = _local_client(dev)
        if lc:
            try:
                return lc.get_state()
            except LocalError as exc:
                if transport == "local":
                    sys.exit("Rete locale: %s" % exc)
                print("(rete locale non disponibile: %s - passo dal cloud)" % exc,
                      file=sys.stderr)
        elif transport == "local":
            sys.exit("Manca l'indirizzo LAN dell'unita': usa `discover` o il cloud.")
    return cli.get_state(dev, names)


def _write_state(cli: CloudClient, dev: CloudDevice, transport: str,
                 changes: Dict[str, Any]) -> Dict[str, Any]:
    if transport in ("auto", "local"):
        lc = _local_client(dev)
        if lc:
            try:
                return lc.set_state(changes)
            except LocalError as exc:
                if transport == "local":
                    sys.exit("Rete locale: %s" % exc)
                print("(rete locale non disponibile: %s - passo dal cloud)" % exc,
                      file=sys.stderr)
        elif transport == "local":
            sys.exit("Manca l'indirizzo LAN dell'unita': usa `discover` o il cloud.")
    return cli.set_state(dev, changes)


# ------------------------------------------------------------------ comandi


def cmd_login(args) -> None:
    user = args.user or input("Email o telefono dell'account: ").strip()
    pwd = getpass.getpass("Password (non viene salvata): ")

    if args.region:
        cli = CloudClient(args.region)
        cli.login(user, pwd)
    else:
        # la regione la scegli al primo avvio dell'app e poi non la rivedi mai:
        # invece di farla indovinare, le proviamo in ordine
        print("Regione non indicata: le provo tutte (%s)..."
              % ", ".join(REGION_TRY_ORDER))
        cli = login_any_region(user, pwd)
    print("Regione: %s (%s)" % (cli.region.label, cli.region.code))
    devices = cli.devices()
    path = session.save(cli, devices)
    print("Login riuscito. %d unita' trovate:" % len(devices))
    for i, d in enumerate(devices, 1):
        print("  %d) %-24s pid=%s  ip=%s" % (i, d.name or d.did[:8], d.pid, d.lanaddr or "?"))
    print("Sessione salvata in %s" % path)


def cmd_register_code(args) -> None:
    account = args.user or input("Email o telefono del nuovo account: ").strip()
    cc = args.countrycode or ("39" if account.isdigit() else "")
    cli = CloudClient(args.region)
    cli.send_register_code(account, countrycode=cc)
    print("Codice di verifica inviato a %s (regione %s)." % (account, cli.region.code))
    print("Controlla email/SMS, poi:  klimakontrol register --region %s --user %s"
          % (args.region, account))


def cmd_register(args) -> None:
    account = args.user or input("Email o telefono del nuovo account: ").strip()
    code = args.code or input("Codice di verifica ricevuto: ").strip()
    pwd = getpass.getpass("Password del nuovo account: ")
    cc = args.countrycode or ("39" if account.isdigit() else "")
    cli = CloudClient(args.region)
    cli.register(account, pwd, code, nickname=args.nickname or "", countrycode=cc)
    print("Registrazione riuscita. Regione: %s (%s)" % (cli.region.label, cli.region.code))
    # un account nuovo non ha ancora unita': non far fallire il salvataggio se l'elenco e' vuoto
    try:
        devices = cli.devices()
    except Exception as exc:      # noqa: BLE001 — meglio salvare la sessione che perderla
        print("(nessuna unita' leggibile: %s)" % exc)
        devices = []
    path = session.save(cli, devices)
    if devices:
        print("Sessione salvata in %s. %d unita' gia' associate." % (path, len(devices)))
    else:
        print("Sessione salvata in %s. Nessuna unita' ancora: vanno abbinate con l'app "
              "ufficiale (l'abbinamento non e' ancora implementato qui)." % path)


def cmd_list(args) -> None:
    _, devices = session.load()
    if not devices:
        sys.exit("Nessuna sessione: lancia `klimakontrol login`.")
    for i, d in enumerate(devices, 1):
        print("%d) %-24s pid=%s ip=%s" % (i, d.name or d.did[:8], d.pid, d.lanaddr or "?"))


def cmd_status(args) -> None:
    cli, devices = session.load()
    dev = _pick(devices, args.device)
    names = None if args.full else list(BASIC_SET)
    state = _read_state(cli, dev, args.transport, names)
    if not state:
        sys.exit("L'unita' non ha restituito parametri.")
    print("%s" % (dev.name or dev.did[:8]))
    print(describe(state))
    session.save(cli, devices)


def cmd_power(args, value: int) -> None:
    cli, devices = session.load()
    dev = _pick(devices, args.device)
    _write_state(cli, dev, args.transport, {"pwr": value})
    print("%s: %s" % (dev.name or dev.did[:8], "accesa" if value else "spenta"))
    session.save(cli, devices)


def cmd_set(args) -> None:
    cli, devices = session.load()
    dev = _pick(devices, args.device)
    readable: Dict[str, Any] = {}
    for pair in args.assignment:
        key, _, val = pair.partition("=")
        if not _:
            sys.exit("Formato atteso chiave=valore, ricevuto %r" % pair)
        readable[key] = val
    changes = {}
    for k, v in readable.items():
        p = PARAMS.get(k)
        if p is None:
            sys.exit("Parametro sconosciuto: %s" % k)
        changes[wire_key(k)] = p.encode(v if p.kind == "enum" else float(v))
    result = _write_state(cli, dev, args.transport, changes)
    print("Inviato a %s: %s" % (dev.name or dev.did[:8], changes))
    if result:
        print(describe(result))
    session.save(cli, devices)


def cmd_online(args) -> None:
    cli, devices = session.load()
    print(json.dumps(session.mask(cli.query_state(devices)), indent=1, ensure_ascii=False))


def cmd_energy(args) -> None:
    cli, devices = session.load()
    dev = _pick(devices, args.device)
    data = cli.energy(dev, args.granularity)
    print(json.dumps(session.mask(data), indent=1, ensure_ascii=False)[:6000])


def cmd_raw(args) -> None:
    cli, devices = session.load()
    dev = _pick(devices, args.device)
    state = _read_state(cli, dev, args.transport, None)
    print(json.dumps({"stato_grezzo": state, "decodificato": decode_status(state)},
                     indent=1, ensure_ascii=False))


def cmd_discover(args) -> None:
    found = discover(timeout=args.timeout)
    if not found:
        print("Nessun modulo trovato. Sei sulla stessa rete WiFi dei climatizzatori?")
        return
    for f in found:
        print("%-16s devtype=0x%04x  mac=%s  %s"
              % (f["host"], f["devtype"], f["mac"], f.get("name", "")))


# ------------------------------------------------------------------ parser


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="klimakontrol", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--transport", choices=("auto", "local", "cloud"), default="auto",
                   help="come raggiungere l'unita' (default: auto)")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("login", help="autentica e memorizza la sessione")
    sp.add_argument("--region", help="ab | eu | ru | cn (se omessa, le prova tutte)")
    sp.add_argument("--user")
    sp.set_defaults(func=cmd_login)

    sp = sub.add_parser("register-code", help="invia il codice di verifica per un nuovo account")
    sp.add_argument("--region", required=True, help="ab | eu | ru | cn (obbligatoria)")
    sp.add_argument("--user", help="email o telefono del nuovo account")
    sp.add_argument("--countrycode", help="prefisso internazionale se telefono (es. 39)")
    sp.set_defaults(func=cmd_register_code)

    sp = sub.add_parser("register", help="crea un nuovo account (dopo register-code)")
    sp.add_argument("--region", required=True, help="ab | eu | ru | cn (obbligatoria)")
    sp.add_argument("--user", help="email o telefono")
    sp.add_argument("--code", help="codice di verifica ricevuto al passo register-code")
    sp.add_argument("--nickname", help="soprannome (default: l'account stesso)")
    sp.add_argument("--countrycode", help="prefisso internazionale se telefono (es. 39)")
    sp.set_defaults(func=cmd_register)

    sub.add_parser("list", help="elenco unita'").set_defaults(func=cmd_list)

    sp = sub.add_parser("status", help="stato dell'unita'")
    sp.add_argument("device")
    sp.add_argument("--full", action="store_true", help="tutti i parametri")
    sp.set_defaults(func=cmd_status)

    sp = sub.add_parser("on", help="accende")
    sp.add_argument("device")
    sp.set_defaults(func=lambda a: cmd_power(a, 1))

    sp = sub.add_parser("off", help="spegne")
    sp.add_argument("device")
    sp.set_defaults(func=lambda a: cmd_power(a, 0))

    sp = sub.add_parser("set", help="imposta parametri (chiave=valore)")
    sp.add_argument("device")
    sp.add_argument("assignment", nargs="+")
    sp.set_defaults(func=cmd_set)

    sub.add_parser("online", help="presenza online di tutte le unita'").set_defaults(func=cmd_online)

    sp = sub.add_parser("energy", help="storico consumi")
    sp.add_argument("device")
    sp.add_argument("granularity", nargs="?", default="hour",
                    choices=("hour", "day", "month"))
    sp.set_defaults(func=cmd_energy)

    sp = sub.add_parser("raw", help="JSON grezzo mascherato")
    sp.add_argument("device")
    sp.set_defaults(func=cmd_raw)

    sp = sub.add_parser("discover", help="cerca moduli sulla rete locale")
    sp.add_argument("--timeout", type=float, default=4.0)
    sp.set_defaults(func=cmd_discover)

    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except RateLimitError as exc:
        print("Il cloud ha messo in pausa i login: %s" % exc, file=sys.stderr)
        return 3
    except AuthError as exc:
        print("Autenticazione: %s" % exc, file=sys.stderr)
        return 4
    except (CloudError, LocalError) as exc:
        print("Errore: %s" % exc, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print()
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
