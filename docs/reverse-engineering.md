# Come sono state ricavate le specifiche

Cronaca del lavoro, così che chiunque possa rifarlo o verificarlo.

## 1. Identificare la piattaforma

L'app sul Play Store è `com.ab.smartDevice`, "Intelligent AC", editore ACSmart, con
indirizzi di supporto `@tcl.com`. Non è software Wisnow: è l'app OEM che TCL fornisce ai
marchi che rivendono i suoi split. Wisnow è uno di questi.

Il modulo WiFi installato è un **BroadLink DNA** (device type `0x507A`/`0x507C`), e il cloud
non è TCL ma BroadLink app-service (`*.ibroadlink.com`).

## 2. Gli assets dell'APK: le source map

L'app è una WebView Cordova. Il pannello di controllo di ogni famiglia di climatizzatori è
un bundle React dentro `assets/default*.zip`. In quei bundle **sono state incluse le source
map** (`main.*.js.map`, fino a 21 MB): contengono `sourcesContent`, cioè il **codice
sorgente originale** non minificato, con i nomi dei file e i commenti degli sviluppatori.

Da lì vengono:

* il dizionario dei parametri (`src/panel/data.js`) — 84 voci con titoli e tipi
* le mappature dei valori di modalità e ventola (`mainModeControlButtons`, `mainFanControlButtons`)
* l'interfaccia verso il livello nativo (`~/broadlink-jssdk/dna/adapter.js`)
* i comandi delle pianificazioni e la conversione di fuso orario
* l'API dei consumi (`src/panel/Electricity.js`)

Ricostruzione:

```bash
unzip -o assets/default.zip -d bundle 'zh-cn/main.*.js.map'
python3 - <<'PY'
import json, os, re
m = json.load(open('bundle/zh-cn/main.<hash>.js.map'))
for src, content in zip(m['sources'], m['sourcesContent'] or []):
    if content is None or '/~/' in src:
        continue
    path = re.sub(r'\?.*$', '', src.replace('webpack:///./', ''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write(content)
PY
```

## 3. Il dex: il controllo remoto

Il JavaScript si fermava al confine nativo:

```
cordova.exec(ok, err, "BLNativeBridge", "devicecontrol",
             [deviceID, subDeviceID, {act, params, vals}, "dev_ctrl", timeouts])
```

Il resto è nell'SDK Java. `classes.dex`, letto con androguard, ha dato:

* `cn.com.broadlink.base.BLApiUrls` — costruzione degli URL per regione
* `cn.com.broadlink.sdk.b` — i metodi che compongono le direttive di controllo

Le stringhe utili si trovano anche senza decompilare:

```bash
strings -n 6 classes.dex | grep -E "ibroadlink|/device/control|/dataservice"
```

Da qui: `/device/control/v2/sdkcontrol`, `/device/control/v3/sdkcontrol`,
`/device/control/v2/querystate`, `/dataservice/v1/device/stats`,
`/appfront/v1/timertask/*`.

Poi la decompilazione dei metodi che usano quelle stringhe ha dato la forma esatta delle
direttive, incluso il campo `cookie` che porta al cloud la chiave AES del dispositivo, e il
typo `timstamp` nell'header — replicato di proposito nel nostro codice, perché è quello che
il server si aspetta.

## 4. Cosa resta nel nativo

Le pianificazioni (`dev_taskadd`, `dev_tasklist`, `dev_taskdata`, `dev_taskdel`) non passano
per una direttiva dedicata: finiscono in `BLControllerDescParam.setCommand(...)` e da lì nel
livello nativo, che costruisce il pacchetto usando i file `.script` cifrati negli assets.

Due modi per chiudere il buco:

1. catturare il pacchetto UDP locale mentre l'app ufficiale crea un timer — bastano pochi
   pacchetti, la chiave AES è nota quindi si decifra;
2. usare l'API cloud `/appfront/v1/timertask/{add,query,modify,delete}`, che pianifica lato
   server invece che nel modulo.

## 5. Verifica indipendente

Il payload di `set temp 23.0` prodotto da questo codice è identico byte per byte a quello
osservato sul filo, checksum interno compreso:

```
1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
```

È il test `test_matches_documented_golden_packet` in `tests/test_local.py`.
