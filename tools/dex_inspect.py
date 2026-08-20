#!/usr/bin/env python3
"""Ispeziona classes.dex: stringhe, riferimenti, decompilazione.

Serve per rifare (o continuare) l'analisi dell'SDK BroadLink dentro l'app.
Richiede androguard, che e' una dipendenza di **sviluppo**:

    pip install androguard

Uso:

    # tutte le stringhe che assomigliano a un endpoint o a un host
    python3 tools/dex_inspect.py strings classes.dex --grep 'ibroadlink|/device/control|/ec4'

    # chi usa una stringa (cosi' si e' trovato il controllo remoto)
    python3 tools/dex_inspect.py xref classes.dex '/device/control/v2/sdkcontrol'

    # decompila le classi il cui nome contiene un pezzo
    python3 tools/dex_inspect.py decompile classes.dex 'cn/com/broadlink/sdk/b' > b.txt

Scorciatoia senza androguard, spesso sufficiente per orientarsi:

    strings -n 6 classes.dex | grep -E 'ibroadlink|/device/control'
"""

import argparse
import re
import sys


def load(path):
    try:
        from loguru import logger
        logger.remove()                     # androguard e' molto loquace
    except Exception:
        pass
    try:
        from androguard.misc import AnalyzeDex
    except ImportError:
        sys.exit("serve androguard: pip install androguard")
    return AnalyzeDex(path)


def cmd_strings(args):
    _, _, dx = load(args.dex)
    pattern = re.compile(args.grep) if args.grep else None
    seen = set()
    for s in dx.get_strings():
        text = str(s.get_value() if hasattr(s, "get_value") else s)
        if len(text) < args.min:
            continue
        if pattern and not pattern.search(text):
            continue
        if text in seen:
            continue
        seen.add(text)
        print(text)


def cmd_xref(args):
    _, _, dx = load(args.dex)
    found = False
    for s in dx.find_strings("^" + re.escape(args.needle) + "$"):
        for m in s.get_xref_from():
            found = True
            cls = getattr(m[0], "name", m[0])
            meth = getattr(m[1], "name", m[1])
            print("%s -> %s" % (cls, meth))
    if not found:
        print("nessun riferimento. Attenzione: se la stringa esiste nel pool ma non ha "
              "riferimenti, la costante vive probabilmente in una libreria nativa "
              "(e' esattamente il caso dei sali dell'autenticazione).")


def cmd_decompile(args):
    _, d, dx = load(args.dex)
    from androguard.decompiler.decompiler import DecompilerDAD
    d.set_decompiler(DecompilerDAD(d, dx))
    for cls in d.get_classes():
        name = cls.get_name()
        if args.match in name:
            print("////// %s" % name)
            print(cls.get_source())


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("strings")
    p.add_argument("dex")
    p.add_argument("--grep")
    p.add_argument("--min", type=int, default=6)
    p.set_defaults(func=cmd_strings)

    p = sub.add_parser("xref")
    p.add_argument("dex")
    p.add_argument("needle")
    p.set_defaults(func=cmd_xref)

    p = sub.add_parser("decompile")
    p.add_argument("dex")
    p.add_argument("match")
    p.set_defaults(func=cmd_decompile)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
