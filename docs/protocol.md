# Wisnow / "Intelligent AC" — specifiche API ricostruite

**Versione 3** — 20 agosto 2026. Aggiornata dopo l'analisi di `classes.dex`.
Stato: **specifiche complete**. Controllo locale, controllo remoto via cloud, modello dati,
pianificazioni e storico consumi: tutto documentato. Non manca più niente per costruire l'app.

---

## 0. Sintesi per chi ha fretta

| Pezzo | Stato |
| --- | --- |
| Che piattaforma è | **Risolto**: modulo BroadLink DNA, cloud BroadLink, app OEM TCL/ACSmart |
| Protocollo di controllo in LAN | **Completo**: UDP:80, AES-128-CBC, JSON |
| Elenco completo dei parametri | **Completo**: 84 parametri estratti dal codice dell'app |
| Timer e programmi settimanali | **Completo**: vivono *dentro* il climatizzatore, 5 tipi di pianificazione |
| Storico consumi | **Individuata** l'API cloud `dataservice/v1/device/stats` |
| Login cloud e elenco dispositivi | **Completo** (endpoint, firme, salt, cifratura) |
| Controllo da fuori casa | **Risolto**: `POST /device/control/v2/sdkcontrol` sul cloud BroadLink |

Nota importante che cambia il progetto: **le pianificazioni non hanno bisogno di nulla di
sempre acceso**. Sono programmate nel modulo WiFi del climatizzatore, che le esegue da solo
anche a telefono spento e internet giù. Era il dubbio principale sull'architettura "solo
cloud": risolto.

---

## 1. Come è fatta l'app dentro

`com.ab.smartDevice` = **"Intelligent AC"** di ACSmart (supporto TCL). Non è software Wisnow:
è l'app OEM che TCL fornisce ai marchi che rivendono i suoi split.

Architettura reale, ricostruita dall'APK:

```
App Android (Java, SDK BroadLink "BLLet")
  └── WebView Cordova
        └── pannello di controllo = app React (una per tipo di climatizzatore)
              └── modulo "broadlink-jssdk"
                    └── cordova.exec(..., "BLNativeBridge", "devicecontrol", [...])
                          └── libNetworkAPI.so  →  UDP in LAN  oppure  relay cloud
```

I pannelli React sono bundle web dentro `assets/default*.zip`, **con le source map incluse**:
il codice sorgente originale è integralmente recuperabile. È da lì che viene tutto quello che
segue.

Bundle presenti nell'APK, uno per famiglia di prodotto:

| File | PID | Note |
| --- | --- | --- |
| `default.zip` | famiglia generica (usato da `0x507c`) | pannello più completo |
| `default_...2e4e0000.zip` | `0x4e2e` | variante con `save_temp` |
| `default_...d9500000.zip` | `0x50d9` | |
| `default_...d3500000.zip` | `0x50d3` | |
| `default_...cca90000.zip` | `0xa9cc` | |

Ci sono anche 5 file `.script` (uno per PID, cifrati) — sono le definizioni di protocollo che
l'SDK BroadLink usa per ogni modello. Non servono se ricostruiamo noi il protocollo.

---

## 2. Protocollo locale (LAN)

- Trasporto: **UDP porta 80**, framing BroadLink/DNA, magic `5aa5aa555aa5aa55`
- Cifratura: **AES-128-CBC**, senza padding crittografico (zero-padding a 16 byte)
- IV fisso: `562e17996d093d28ddb3ba695a2e6f58`
- Chiave di autenticazione iniziale: `097628343fe99e23765c1513accf8b02`
- Chiave di esercizio: **AES key per-dispositivo**, ottenibile
  1. dall'auth BroadLink in LAN (comando `0x0065`) — **senza account**, su unità già associate;
  2. dal campo `aeskey` di `getallinfo` sul cloud.

Pacchetto esterno:

| Offset | Byte | Significato |
| --- | ---: | --- |
| `0x00` | 8 | magic `5aa5aa555aa5aa55` |
| `0x20` | 2 | checksum pacchetto (LE) |
| `0x24` | 2 | device type (es. `0x507c`) |
| `0x26` | 2 | comando `0x006a`, risposta `0x03ee` |
| `0x28` | 2 | nonce, ricopiato nella risposta |
| `0x2a` | 6 | MAC del dispositivo, invertito |
| `0x30` | 4 | device id (osservato `1`) |
| `0x34` | 2 | checksum del payload in chiaro |
| `0x38` | n | payload interno cifrato |

Payload interno:

| Offset | Byte | Significato |
| --- | ---: | --- |
| `0x00` | 2 | lunghezza utile |
| `0x02` | 4 | magic interno `a5a55a5a` |
| `0x06` | 2 | checksum interno |
| `0x08` | 1 | azione: `1` get, `2` set |
| `0x09` | 1 | costante `0x0b` |
| `0x0a` | 4 | lunghezza JSON (LE) |
| `0x0e` | n | corpo JSON |

Checksum: inizializzati a `0xbeaf`, somma dei byte modulo `0x10000`.

### Due dialetti JSON

L'app usa la forma "params/vals" del DNA:

```json
{"act":"set","params":["pwr","temp"],"vals":[[{"val":1,"idx":1}],[{"val":230,"idx":1}]]}
```

I moduli TCL accettano anche la forma abbreviata, verificata dalla community:

```json
{"temp":230}
```

Comandi (il quinto argomento del bridge, `commandStr`):

| Comando | Funzione |
| --- | --- |
| `dev_ctrl` | lettura/scrittura parametri |
| `dev_tasklist` | elenco pianificazioni |
| `dev_taskadd` | crea pianificazione |
| `dev_taskdata` | dettaglio pianificazione (`type`, `index`) |
| `dev_taskdel` | elimina pianificazione (`type`, `index`) |

Timeout usati dall'app: 3 s in locale, 5 s in remoto, 3 pacchetti ripetuti per comando
(il DNA su UDP non ha ritrasmissione: si spara più volte). L'errore `-4000` va ritentato.

---

## 3. Pianificazioni: stanno nel climatizzatore

Cinque tipi, indicizzati da `type`:

| type | Nome interno | Significato |
| ---: | --- | --- |
| 0 | `timerlist` | accensione/spegnimento a data e ora |
| 1 | `delaylist` | ritardo ("tra 2 ore spegni") |
| 2 | `periodlist` | **ricorrente**: ora + giorni della settimana |
| 3 | `cyclelist` | ciclico con due comandi (`cmd1`/`cmd2`) |
| 4 | `randomlist` | casuale in una finestra |

Formato orario: `YYYY-MM-DD HH:mm:ss` per i tipi 0 e 1, `HH:mm:ss` per gli altri.

**Trappola da ricordare**: il firmware ragiona in **UTC+8** (eredità cinese). L'app converte
ogni orario sommando `8 - offset_locale`. Per l'Italia in ora legale (UTC+2) significa +6 ore.
Sbagliare questo significa timer che scattano a caso — probabilmente è uno dei motivi per cui
l'app attuale ti fa imprecare.

Payload di `dev_taskadd`: oltre a `time`, `endtime`, `type`, contiene `data` (e per il tipo 3
anche `data2`) nella forma `params/vals` vista sopra: cioè **una pianificazione porta con sé
lo stato completo** da applicare, non solo on/off. Si possono programmare scenari veri
("alle 22:00 caldo 21°C ventola bassa sleep on").

---

## 4. Parametri: il modello dati completo

84 parametri estratti da `src/panel/data.js`. Molti non sono nemmeno esposti dall'app.

### Controllo principale

| Parametro | Tipo | Significato | Valori |
| --- | --- | --- | --- |
| `pwr` | enum | accensione | `0` off, `1` on |
| `temp` | num | temperatura impostata | decimi di °C (`230` = 23.0) |
| `tcl_mode` | enum | modalità | `1` caldo, `2` deumidifica, `3` freddo, `4` ventola, `5` auto |
| `tcl_mark` | enum | velocità ventola | `0` auto, `1` bassa, `4` medio-bassa, `2` media, `5` medio-alta, `3` alta |
| `ac_vdir` | enum | oscillazione verticale | `7` on, `0` off |
| `ac_hdir` | enum | oscillazione orizzontale | `1` on, `0` off |
| `tempunit` | enum | unità di temperatura | °C / °F |

> **Nome sul filo dello swing** (HW reale, 2026-08-22): il modulo riporta lo swing come
> `ac_vdir`/`ac_hdir`, **non** `tcl_vdir`/`tcl_hdir` come nell'estrazione dall'APK. Scrivere
> `tcl_vdir` non muove nulla. La libreria traduce `tcl_vdir`→`ac_vdir` in `WIRE_KEY_ALIASES`
> (come `temp`→`save_temp`). Il valore "on" (7 / 1) è ancora quello del sorgente, da confermare.

**Set fisso ritornato in lettura da questi moduli (devtype `0x4e2e`)** — identico su tutte le
unità, è di fatto la loro lista di capacità:
`pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode`.
Non compaiono (quindi non gestiti via cloud): `qtmode` (mute), `ac_health`, `bglight`, `envtemp`,
`if_function`.

### Letture ambientali

| Parametro | Significato |
| --- | --- |
| `envtemp` | temperatura ambiente interna |
| `envtempoutdoor` | temperatura esterna |
| `humidity` | umidità |
| `pm25`, `pm25_class` | polveri sottili e classe di qualità |
| `hcho` | formaldeide |
| `co2_data` | CO₂ |
| `tvoc_vol`, `tvoc_q`, `tvoc_class` | composti organici volatili |
| `air_quality` | indice sintetico qualità aria |

### Modalità e comfort

| Parametro | Significato |
| --- | --- |
| `ac_slp` / `tcl_slp` | modalità notte |
| `ecomode`, `savemode`, `save_state` | risparmio energetico |
| `pwfmode` | turbo / potenza massima |
| `qtmode` | silenzioso |
| `ac_health` | modalità "salute" (ionizzatore) |
| `no_wfeeling` | flusso senza sensazione d'aria |
| `8heat` | mantenimento a 8 °C (antigelo) |
| `el_heat` | resistenza elettrica ausiliaria |
| `desicmode`, `smartdesic` | deumidifica / antimuffa |
| `3dairmode` | flusso tridimensionale |
| `ac_hwind`, `ac_lwind` | aria calda alta / aria fresca |
| `man_wind` | flusso guidato dal sensore di presenza |
| `beep` | suono di conferma tasti |
| `bglight` | retroilluminazione display |
| `ac_photos` | sensore di luminosità |
| `auto_study`, `autostd_cmd` | autoapprendimento |

### Diagnostica (questa l'app non te la mostra affatto)

| Parametro | Significato |
| --- | --- |
| `ac_errcode` | codice guasto |
| `ac_type`, `tcl_type`, `devicetype` | modello/tipologia macchina |
| `compressor_hz`, `compressor_opt` | frequenza compressore |
| `in_fan_rpm`, `out_fan_rpm` | giri ventole interna/esterna |
| `in_coil_temp`, `in_vent_temp` | temperatura batteria e mandata interna |
| `out_coil_temp`, `out_vent_temp` | temperatura batteria e scarico esterno |
| `out_volt`, `out_cur` | tensione e corrente unità esterna |
| `four_way_val`, `solenoid_val` | valvola a 4 vie, elettrovalvola |
| `evaportor`, `clean_check` | autopulizia evaporatore |
| `filter_check`, `if_filterdirty` | stato filtri |
| `humidity_check` | sensore umidità presente |

### Energia e prenotazioni

| Parametro | Significato |
| --- | --- |
| `target_kwh` | obiettivo di consumo |
| `save_temp` | temperatura fissa in modalità risparmio |
| `save_beg_t`, `sava_stp_t` | inizio/fine ultima sessione di risparmio |
| `save_last_mode`, `save_last_temp` | ultima modalità/temperatura di risparmio |
| `sub_on_off`, `sub_time`, `sub_weekday`, `if_subs`, `if_cycle`, `cmd` | prenotazione integrata |
| `timezone` | fuso orario del modulo |
| `site_info` | posizione |
| `if_function` | maschera delle funzioni realmente supportate dal modello |

Quest'ultimo, `if_function`, è la chiave per costruire un'interfaccia che mostra solo i
pulsanti che il *tuo* modello supporta davvero, invece di riempire lo schermo di funzioni
morte come fa l'app attuale.

---

## 5. Cloud BroadLink

Base URL per regione: `https://<licenseId>appservice.ibroadlink.com`

Il `companyId` è **unico e condiviso** da tutte le regioni (`8503b08fa57729df9faa45e4c978852c`,
byte `[120:136]` del blob di licenza); solo il `licenseId`/`lid` (byte `[0:16]`) cambia per
regione. Confermato da un login riuscito il 2026-08-21 (vedi `CLAUDE.md` §5 trappola 1).

| Regione | licenseId (lid) | companyId |
| --- | --- | --- |
| Europa | `aae72184369e2fc3e6ded53a90612586` | `8503b08fa57729df9faa45e4c978852c` |
| USA/altro | `f6e9e21566e109a28797aba5a1d8ed7e` | `8503b08fa57729df9faa45e4c978852c` |
| Cina | `bffd4d702ec53938c31eb10cc0194b4a` | `8503b08fa57729df9faa45e4c978852c` |
| Russia | `e60de87565166c447a90cee96da955f7` | `8503b08fa57729df9faa45e4c978852c` |

### Account e dispositivi

| Endpoint | Uso |
| --- | --- |
| `POST /account/login` | login → `userid`, `loginsession` |
| `POST /account/newregcode` | passo 1 registrazione: invia il codice di verifica (email/SMS). Corpo `{email\|phone, [countrycode], companyid, lid}`, POST grezzo |
| `POST /account/register` | passo 2 registrazione: crea l'account → ritorna già `userid`+`loginsession`. **multipart/form-data**: campo `text` = JSON cifrato `{email\|phone, type, password, nickname, sex, code, preferlanguage, companyid, lid, [countrycode]}` |
| `GET /ec4/v1/common/api` | `key` + `timestamp` per firmare le chiamate seguenti |
| `POST /ec4/v1/user/getfamilyid` | elenco case |
| `POST /ec4/v1/family/getallinfo` | dispositivi: `mac`, `lanaddr`, **`aeskey`**, `did`, `pid`, nomi |

> **Registrazione** (ricostruita dal dex, 2026-08-22): stessi host/IV/sali del login. Il `password`
> è `SHA1(pw + sale_password)` come nel login. Il **codice di verifica è obbligatorio** (passo umano
> tra i due endpoint). `register` = anche login: al successo la sessione è già stabilita. In
> klimakontrol: `CloudClient.send_register_code()` / `.register()`, CLI `register-code` / `register`.

Firme e cifratura:

- corpo JSON compatto, **AES-128-CBC**, IV `eaaaaa3abb5862a21918b5771d1615aa`
- `Content-type: application/x-java-serialized-object`
- login: `password = sha1(pwd + "4969fj#k23#")`, header `token = md5(body + "xgx3d*fe3478$ukx")`,
  chiave AES = `md5(timestamp + "kdixkdqp54545^#*")`
- `/ec4/...`: `token = md5(body + "xgx3d*fe3478$ukx" + timestamp + userid)`, chiave AES da
  `/ec4/v1/common/api`
- errori: `-1008` credenziali errate (spesso in realtà **companyid sbagliato**, vedi sopra),
  `-1036` rate limit
- la risposta di `sdkcontrol` porta i dati in `event.payload.data` come **stringa JSON**
  (`params`/`vals`); va fatto `json.loads`. Setpoint temperatura di questi moduli: **`save_temp`**

### Storico consumi — `dataservice`

Il pannello consumi chiama il bridge nativo `cloudServices` con:

```json
{
  "method": "post",
  "serviceName": "dataservice",
  "interfaceName": "v1/device/stats",
  "httpBody": {
    "report": "fw_tcldaystatus_v1",
    "device": [{
      "did": "<device id>",
      "start": "2026-08-20_00:00:00",
      "end": "2026-08-21_00:00:00",
      "sortk": "-occurtime",
      "params": []
    }]
  }
}
```

Report disponibili: `fw_tcldaystatus_v1` (per ora), `fw_tclmonthstatus_v1` (per giorno),
`fw_tclyearstatus_v1` (per mese). La risposta arriva come `table[0].values`, con per ogni
intervallo i kWh, il tempo di funzionamento e i buchi di connessione.

Esiste anche `dataservice` / `v1/device/status` (usato dalla pagina "risparmio"): storico di
stato del dispositivo, cioè **la cronologia di temperature e accensioni**. Anche gli orari di
queste API sono in UTC+8.

**Esito su HW reale (2026-08-21).** L'app punta i dataservice all'host **`rtasquery`**
(`https://%srtasquery.ibroadlink.com/dataservice/...`), NON `appservice`. Col nostro codice:
`/stats` su `appservice` risponde `status:0, ok` ma **`table[0]` è vuoto** (`total:0, cnt:0,
values:null`) per tutte le granularità e tutte le unità; `/status` su `appservice` risponde
`-12401 服务器忙` ("server occupato"), persistente; l'host `<lid>rtasquery.ibroadlink.com` **non
risolve** (il prefisso `%s` per rtasquery non è il lid). Inoltre queste unità non espongono alcun
parametro di potenza/kWh nello stato: **non misurano i consumi**, quindi non c'è storico da leggere.
Se un giorno serve, va trovato il vero host `rtasquery` (probabilmente un prefisso regionale fisso,
come `app-service-deu-…`).

---

## 6. Controllo remoto — l'ultimo pezzo, trovato

Estratto da `cn.com.broadlink.sdk` in `classes.dex`. Quando il telefono non è in LAN, l'SDK
non usa nessun protocollo esotico: fa una **normale POST HTTPS** al cloud, che inoltra il
comando al climatizzatore. Il formato è quello a "direttive" (stile Alexa) che BroadLink usa
in tutta la sua piattaforma.

### Comando remoto

```
POST https://<licenseId>appservice.ibroadlink.com/device/control/v2/sdkcontrol?license=<licenseId>
```

Header: `userid`, `loginsession`, `licenseid`, `lid` (gli stessi ottenuti dal login).

Corpo:

```json
{
  "directive": {
    "header": {
      "namespace": "DNA.KeyValueControl",
      "name": "KeyValueControl",
      "interfaceVersion": "2",
      "messageId": "<did>-<unix_ts>",
      "timstamp": "<unix_ts>"
    },
    "endpoint": {
      "devicePairedInfo": {
        "did": "<did>",
        "pid": "<pid>",
        "mac": "<mac>",
        "devicetypeflag": <flag>,
        "cookie": "<base64 del JSON qui sotto>"
      },
      "endpointId": "<did>",
      "cookie": {},
      "devSession": "<opzionale, se lungo almeno 112 caratteri>"
    },
    "payload": { "act": "set", "params": ["pwr"], "vals": [[{"val":1,"idx":1}]] }
  }
}
```

Il campo `cookie` di `devicePairedInfo` è il Base64 di:

```json
{"device":{"id":<id>,"key":"<aeskey>","aeskey":"<aeskey>","did":"<did>","pid":"<pid>","mac":"<mac>"}}
```

Cioè: **è il client a passare al cloud la chiave AES del dispositivo**, la stessa che si ottiene
da `getallinfo` o dall'autenticazione in LAN. Il cloud fa solo da postino.

Due varianti di `namespace`, scelte dall'SDK:

| namespace / name | Uso |
| --- | --- |
| `DNA.KeyValueControl` / `KeyValueControl` | parametri in forma `params`/`vals` — è quella che serve a noi |
| `DNA.TransmissionControl` / `commonControl` | passaggio di payload grezzo |

Esiste anche `/device/control/v3/sdkcontrol` (`interfaceVersion: "3"`), usata per il controllo
di gruppo: stesso schema, con `devinfo` e `groupdevice` nell'endpoint.

Risposta:

```json
{"event":{"endpoint":{"endpointId":"...","devSession":"..."},"payload":{"status":0,"msg":"success", ...}}}
```

Se il cloud restituisce un `devSession` nuovo va conservato e rimandato nelle chiamate
successive: è il token di sessione del dispositivo.

### Stato e presenza online (batch)

```
POST https://<licenseId>appservice.ibroadlink.com/device/control/v2/querystate
```

Header: `userid`, `loginsession`, `licenseid`, `lid`, `companyid`.

```json
{
  "directive": {
    "header": {
      "namespace": "DNA.QueryState",
      "name": "queryState",
      "interfaceVersion": "2",
      "messageType": "controlgw.batch",
      "senderId": "sdk",
      "messageId": "<userid>-<unix_ts>",
      "timstamp": "<unix_ts>"
    },
    "payload": {
      "msgtype": "batch",
      "studata": [{"did": "<did>", "devtype": <devtype>, "devSession": "<opzionale>"}]
    }
  }
}
```

Una sola chiamata restituisce lo stato online di tutte le unità: perfetta per la schermata
principale di un multi-split.

### Altri endpoint utili individuati nel dex

| Endpoint | Uso |
| --- | --- |
| `/appfront/v1/timertask/{add,query,modify,delete}` | pianificazioni **lato cloud** (alternativa a quelle nel modulo) |
| `/appfront/v1/scene/*`, `/appfront/v1/trigger/upsert` | scene e automazioni a trigger |
| `/ec4/v1/electricinfo/config` | configurazione del monitoraggio consumi |
| `/dataservice/v1/device/stats` | storico consumi |
| `/dataservice/v1/device/status` | storico di stato (temperature, accensioni) |
| `/ec4/v1/dev/*`, `/ec4/v1/module/*`, `/ec4/v1/family/*` | gestione case, stanze, dispositivi, condivisioni |

Nota sulle basi URL: in modalità normale l'SDK punta tutto a
`https://<licenseId>appservice.ibroadlink.com`. Esiste una modalità "biz" che sposta le
famiglie su `https://<licenseId>bizihcv0.ibroadlink.com`. Per l'app che ci interessa vale la
prima.

## 7. Conseguenze sul progetto

1. **Le pianificazioni sono già risolte**: si scrivono nel modulo, che le esegue da solo.
   Nessun bisogno di un dispositivo sempre acceso in casa.
2. **Lo storico consumi è una REST cloud**: interrogabile dall'app direttamente, e anche a
   ritroso per ricostruire il passato che il modulo ha già registrato.
3. **In casa si va in locale**: comandi istantanei via UDP. Fuori casa la stessa app manda la
   stessa struttura dati a `sdkcontrol`. Un solo modello di dominio, due trasporti.
4. **C'è molto più materiale di quanto l'app mostri**: diagnostica, temperature di batteria e
   mandata, frequenza compressore, stato filtri. Con `if_function` si costruisce
   un'interfaccia che mostra solo il vero.
