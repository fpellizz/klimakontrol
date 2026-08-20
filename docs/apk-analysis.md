# Analizzare l'APK: procedura completa

Come rifare da zero l'analisi che ha prodotto `docs/protocol.md`. Serve se esce una versione
nuova dell'app, se un pezzo del protocollo cambia, o per verificare che qui non ci sia niente di
inventato.

Il materiale d'analisi (APK, dex, .so, assets) **non va committato**: `.gitignore` li esclude.
Lavora in una cartella `apk/` locale.

---

## 0. Procurarsi l'APK

L'app è installata come *split APK*: il pacchetto base contiene codice e assets, le librerie
native stanno in un pacchetto separato per architettura. Servono entrambi.

Con il telefono collegato e il debug USB attivo:

```bash
mkdir -p apk && cd apk
adb shell pm path com.ab.smartDevice
adb shell pm path com.ab.smartDevice | sed 's/^package://' | tr -d '\r' | \
  while read -r p; do adb pull "$p" . ; done
```

Senza PC: un estrattore APK dal telefono (App Manager, APK Extractor) esportando **anche** le
divisioni.

Cosa c'è dentro:

```bash
unzip -l base.apk | grep -E 'assets/|classes.*dex' | head -30
for a in *.apk; do echo "== $a"; unzip -l "$a" | grep '\.so$'; done
```

---

## 1. Gli assets: il codice sorgente dei pannelli

Questa è la miniera. L'app è una WebView Cordova; ogni famiglia di climatizzatori ha il suo
pannello React in `assets/default*.zip`, e **le source map sono incluse**.

```bash
unzip -o base.apk 'assets/*' -d .
python3 ../tools/dump_sourcemaps.py assets/default.zip ricostruito/
```

Da guardare, in ordine:

| File | Cosa dà |
| --- | --- |
| `src/panel/data.js` | il dizionario dei parametri, con titoli e tipi |
| `src/panel/data.js` (`mainModeControlButtons`, `mainFanControlButtons`) | i valori di modalità e ventola |
| `~/broadlink-jssdk/dna/adapter.js` | il ponte verso il nativo, i comandi, i timeout |
| `~/broadlink-jssdk/dna/time-zone.js` | la conversione UTC+8 |
| `src/panel/Electricity.js` | l'API dei consumi (`dataservice`, i nomi dei report) |
| `src/panel/Timer.js`, `src/components/dna/SDKTimer/` | l'interfaccia delle pianificazioni |
| `src/panel/More.js`, `SaveEnergy.js` | funzioni secondarie e storico di stato |

Corrispondenza `pid` → bundle: leggi `desc.json` dentro ogni zip. I `.script` accanto (uno per
`pid`) sono le definizioni di modello usate dall'SDK nativo: cifrati, vedi
`docs/open-questions.md` §4.

---

## 2. Il dex: l'SDK BroadLink

Il JavaScript si fermava qui:

```js
cordova.exec(ok, err, "BLNativeBridge", "devicecontrol",
             [deviceID, subDeviceID, {act, params, vals}, "dev_ctrl", timeouts])
```

Il resto è Java. Prima passata, senza strumenti:

```bash
unzip -o base.apk 'classes*.dex' -d .
strings -n 6 classes.dex | grep -E 'ibroadlink|/device/control|/dataservice|/ec4|/appfront'
```

È così che sono venuti fuori `/device/control/v2/sdkcontrol`, `querystate`,
`dataservice/v1/device/stats` e gli endpoint di `docs/open-questions.md` §6.

Seconda passata, con androguard:

```bash
pip install androguard
python3 ../tools/dex_inspect.py xref classes.dex '/device/control/v2/sdkcontrol'
python3 ../tools/dex_inspect.py decompile classes.dex 'cn/com/broadlink/sdk/b' > sdk_b.txt
```

Le classi che contano:

| Classe | Contenuto |
| --- | --- |
| `com.tcl.smartdevice.AirApplication` (`initData`) | le quattro licenze BroadLink in chiaro, i `pid` per regione |
| `cn.com.broadlink.base.BLApiUrls` | costruzione degli URL per regione |
| `cn.com.broadlink.sdk.b` | direttive di controllo, querystate, orario del dispositivo |
| `a.a.a.account.a.c` | corpo del login |
| `a.a.a.account.a.b` | firma e cifratura delle chiamate account |
| `cn.com.broadlink.base.BLCommonTools` | `aesNoPadding` (mode, IV), `parseStringToByte`, hash |
| `cn.com.broadlink.account.BLAccountEncryptAPI` | le tre funzioni native dei sali |

Attenzione a un'insidia di metodo: se una stringa esiste nel pool del dex ma `xref` non trova
nessun riferimento, **quella costante non è usata dal codice Java**. È il segnale che vive in una
libreria nativa — ed è esattamente come si è scoperto il problema dei sali. Non dare per buona
una costante solo perché la stringa compare nel file.

---

## 3. Le librerie native

```bash
for a in *.apk; do unzip -o -j "$a" 'lib/*/*.so' -d lib/ 2>/dev/null; done
ls -la lib/
```

Quelle che contano:

| Libreria | Ruolo |
| --- | --- |
| `libBLAccountEncryptAPI.so` | i tre sali dell'autenticazione (stringhe in chiaro) |
| `libNetworkAPI.so` | il protocollo DNA vero: pacchetti, LAN e relay cloud, esecuzione degli `.script` |

Per i sali: `python3 ../tools/extract_salts.py lib/libBLAccountEncryptAPI.so`.

`libNetworkAPI.so` è il posto dove guardare per la codifica dei comandi delle pianificazioni,
ma è molto più semplice catturare un pacchetto UDP e decifrarlo: la chiave è nota
(`docs/open-questions.md` §2).

---

## 4. Verificare invece di credere

Il criterio adottato: ogni cosa scritta in `docs/protocol.md` è stata letta dal codice
dell'app, non dedotta. E c'è una verifica indipendente: il payload di `set temp 23.0` prodotto
da questa libreria è identico byte per byte a quello osservato sul filo, checksum interno
compreso.

```
1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
```

È il test `tests/test_local.py::test_matches_documented_golden_packet`. Quando aggiungi un pezzo
di protocollo, cerca il modo di ancorarlo a un dato osservato allo stesso modo.
