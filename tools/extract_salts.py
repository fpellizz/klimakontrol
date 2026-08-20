#!/usr/bin/env python3
"""Estrae i sali dell'autenticazione da libBLAccountEncryptAPI.so.

L'app non tiene questi valori in Java: li chiede a tre funzioni native che non
fanno altro che restituire una costante. Nel .so quelle costanti sono stringhe
in chiaro, quindi basta cercarle.

    python3 tools/extract_salts.py libBLAccountEncryptAPI.so

Stampa i candidati e, se li trova, la riga di export pronta da incollare.
"""

import re
import sys


def strings(data, minimum=6, maximum=64):
    pattern = re.compile(rb"[\x20-\x7e]{%d,%d}" % (minimum, maximum))
    return [m.group().decode("ascii") for m in pattern.finditer(data)]


def looks_like_salt(text):
    """I sali BroadLink sono corti, misti, con almeno un simbolo strano."""
    if not 8 <= len(text) <= 32:
        return False
    if "/" in text or " " in text or text.startswith("_"):
        return False
    has_symbol = any(c in "#$*^&%+!@~" for c in text)
    has_digit = any(c.isdigit() for c in text)
    has_alpha = any(c.isalpha() for c in text)
    return has_symbol and has_digit and has_alpha


def main(path):
    data = open(path, "rb").read()
    found = strings(data)
    print("%d stringhe leggibili nel file\n" % len(found))

    known = {
        "xgx3d*fe3478$ukx": "BODY  (firma del corpo, gia' verificato)",
        "kdixkdqp54545^#*": "TOKEN (chiave AES del corpo)",
        "4969fj#k23#": "PASSWORD (sale della password)",
    }
    for text in found:
        if text in known:
            print("CONFERMATO  %-20r  %s" % (text, known[text]))

    candidates = sorted({t for t in found if looks_like_salt(t)})
    print("\nCandidati (%d):" % len(candidates))
    for text in candidates:
        mark = "  <- noto" if text in known else ""
        print("   %r%s" % (text, mark))

    print("\nSe i candidati sono tre e diversi da quelli noti, provali cosi':")
    print("   export KLIMAKONTROL_SALT_PASSWORD='...'")
    print("   export KLIMAKONTROL_SALT_TOKEN='...'")
    print("   export KLIMAKONTROL_SALT_BODY='...'")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
