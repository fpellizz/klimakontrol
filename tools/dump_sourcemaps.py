#!/usr/bin/env python3
"""Ricostruisce il codice sorgente dell'app dalle source map incluse nell'APK.

L'app "Intelligent AC" e' una WebView Cordova: ogni pannello di controllo e' un
bundle React in `assets/default*.zip`, e in quei bundle sono state incluse le
source map. `sourcesContent` contiene i file originali non minificati, con i
commenti degli sviluppatori. E' da qui che vengono il dizionario dei parametri,
le mappature di modalita' e ventola, i comandi delle pianificazioni e l'API dei
consumi.

    python3 tools/dump_sourcemaps.py assets/default.zip ricostruito/

Estrae il bundle in una cartella temporanea, trova le .js.map e scrive i sorgenti.
Con --filter limita ai percorsi che contengono una stringa (default: "./src").
"""

import argparse
import json
import os
import re
import shutil
import tempfile
import zipfile


def dump_map(map_path, out_dir, keep):
    with open(map_path, encoding="utf-8") as fh:
        data = json.load(fh)
    sources = data.get("sources") or []
    contents = data.get("sourcesContent") or []
    written = 0
    for i, src in enumerate(sources):
        if keep not in src:
            continue
        if i >= len(contents) or contents[i] is None:
            continue
        rel = re.sub(r"\?.*$", "", src.replace("webpack:///", "").lstrip("./"))
        rel = rel.replace("..", "_")            # niente uscite dalla cartella
        dest = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as fh:
            fh.write(contents[i])
        written += 1
    return written, len(sources)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle", help="default*.zip preso da assets/ dell'APK")
    ap.add_argument("out", help="cartella di destinazione")
    ap.add_argument("--filter", default="./src",
                    help="tiene solo i sorgenti il cui percorso contiene questa stringa "
                         "(usa '' per prendere tutto, node_modules compresi)")
    args = ap.parse_args()

    tmp = tempfile.mkdtemp(prefix="klimakontrol-")
    try:
        with zipfile.ZipFile(args.bundle) as zf:
            maps = [n for n in zf.namelist() if n.endswith(".js.map")]
            if not maps:
                raise SystemExit("nessuna source map in %s" % args.bundle)
            for name in maps:
                zf.extract(name, tmp)
        total = 0
        for name in maps:
            written, seen = dump_map(os.path.join(tmp, name), args.out, args.filter)
            print("%s: %d sorgenti scritti su %d" % (name, written, seen))
            total += written
        print("\n%d file in %s" % (total, args.out))
        print("Da guardare per primi: src/panel/data.js (parametri), "
              "src/panel/Electricity.js (consumi), "
              "~/broadlink-jssdk/dna/adapter.js (ponte nativo).")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
