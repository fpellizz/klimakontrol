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

## 4. Le licenze, e due costanti sbagliate che girano in rete

`com.tcl.smartdevice.AirApplication.initData` contiene le quattro licenze BroadLink dell'app,
una per regione (`ab`, `cn`, `eu`, `ru`), come blob Base64. Il blob **non è cifrato**: i primi
16 byte sono il `licenseId`, i 16 successivi il `companyid`.

```python
raw = base64.b64decode(blob)
license_id, company_id = raw[0:16].hex(), raw[16:32].hex()
```

Due conseguenze:

1. Il valore `8503b08fa57729df9faa45e4c978852c`, che nei progetti open source compare come
   *company id* della regione internazionale, compare identico in tutte e quattro le licenze:
   è una costante globale, non un companyid. Quello vero è
   `a8452a8f48ae707edc12e9c52e21f00f`.
2. Per non ripetere l'errore, `cloud.py` tiene i blob e ne deriva gli identificativi a ogni
   avvio, invece di ricopiarli.

Sempre da `initData`: i `pid` dei tre tipi di macchina (split, portatile, finestra) cambiano per
regione — per la regione internazionale lo split è `0x507c`, per l'Europa `0x507a`.

## 5. I sali dell'autenticazione: nel nativo, non nel dex

Il login usa tre costanti che l'app chiede a `libBLAccountEncryptAPI.so`:

| Funzione nativa | Uso | Valore |
| --- | --- | --- |
| `blAccountPasswordEncrypt()` | `SHA1(password + sale)` | `4969fj#k23#` |
| `blAccountTokenEncrypt()` | `md5(timestamp + sale)` → chiave AES | `kdixkdqp54545^#*` |
| `blAccountBodyEncrypt()` | `md5(corpo + sale)` → header `token` | `xgx3d*fe3478$ukx` |

Nel dex compare solo il terzo (lo usano anche `/ec4` e `dataservice`). Gli altri due si leggono
dalla libreria nativa, dove sono stringhe in chiaro:
`python3 tools/extract_salts.py libBLAccountEncryptAPI.so`.

Lezione di metodo: se `dex_inspect.py xref` non trova riferimenti a una stringa presente nel
pool, quella costante **non è usata dal codice Java**. È il segnale che vive nel nativo, e non
va data per buona solo perché la stringa c'è.

## 6. Il resto della catena del login

* host: `https://<licenseId>appservice.ibroadlink.com`. L'app inizializza l'SDK con
  `APP_SERVICE_ENABLE=1`, e `BLApiUrls.setAppServiceHost` sovrascrive tutti gli host `biz*`
  (`bizaccount`, `bizihcv0`, `bizpd`, …) con quello singolo. Gli host `biz*` valgono solo con
  quel flag a zero.
* corpo: `{email|phone, password, companyid, lid}` (`a.a.a.account.a.c`)
* firma e cifratura: `a.a.a.account.a.b` — `AES/CBC/ZeroBytePadding`, IV
  `eaaaaa3abb5862a21918b5771d1615aa`, chiave da `md5(timestamp + sale)` letta come esadecimale
  (`BLCommonTools.aesNoPadding`, `parseStringToByte`)
* la password arriva grezza dall'interfaccia: `LoginActivity$LoginTask.doInBackground` →
  `BLAccount.login(utente, password)`, nessun hash intermedio.

Nonostante tutto questo combaci, il login viene ancora rifiutato con `-1008`: vedi
`docs/open-questions.md` §1.

## 7. Cosa resta nel nativo

Le pianificazioni (`dev_taskadd`, `dev_tasklist`, `dev_taskdata`, `dev_taskdel`) non passano
per una direttiva dedicata: finiscono in `BLControllerDescParam.setCommand(...)` e da lì nel
livello nativo, che costruisce il pacchetto usando i file `.script` cifrati negli assets.

Due modi per chiudere il buco:

1. catturare il pacchetto UDP locale mentre l'app ufficiale crea un timer — bastano pochi
   pacchetti, la chiave AES è nota quindi si decifra;
2. usare l'API cloud `/appfront/v1/timertask/{add,query,modify,delete}`, che pianifica lato
   server invece che nel modulo.

## 8. Verifica indipendente

Il payload di `set temp 23.0` prodotto da questo codice è identico byte per byte a quello
osservato sul filo, checksum interno compreso:

```
1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
```

È il test `test_matches_documented_golden_packet` in `tests/test_local.py`.
