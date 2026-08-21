# Domande aperte, con la procedura per chiuderle

Ogni voce ha: cosa manca, perché manca, e i comandi esatti per risolverla. Quando una si chiude,
spostala in `docs/reverse-engineering.md` con la risposta e aggiorna la tabella di stato in
`CLAUDE.md`.

---

## 1. Il login viene rifiutato con -1008 — ✅ RISOLTO (2026-08-21)

**Causa.** Il `companyid` era derivato da `blob[16:32]` (per-regione); quello vero è la
**costante condivisa** `8503b08fa57729df9faa45e4c978852c` (`blob[120:136]`), uguale per tutte le
regioni. Il `lid` per-regione era invece corretto. Fix in `cloud.py::_region_from_license`. Un
login su region eu ha restituito, echeggiato dal server, `companyid: 8503b08f…`. Dettagli in
`CLAUDE.md` §5 trappola 1. Login, elenco unità, `querystate`, lettura e controllo via cloud
provati su HW reale.

Quanto segue è la vecchia analisi, tenuta come cronaca (portava su una pista sbagliata: la forma
della richiesta era giusta, mancava solo il companyid corretto).

**Sintomo (storico).** `login` restituiva `-1008` su tutte e quattro le regioni, con credenziali
che nell'app ufficiale funzionano.

**Cosa è già stato escluso** (tutto letto dal dex, non dedotto):

* i tre sali: confermati estraendoli da `libBLAccountEncryptAPI.so` con `tools/extract_salts.py`
  — sono `4969fj#k23#` (password), `kdixkdqp54545^#*` (chiave), `xgx3d*fe3478$ukx` (firma);
* l'host: `https://<lid>appservice.ibroadlink.com`, perché l'app inizializza l'SDK con
  `APP_SERVICE_ENABLE=1` e quindi `setAppServiceHost` sovrascrive tutti gli host `biz*`;
* il percorso `/account/login` e il corpo `{email|phone, password, companyid, lid}`;
* gli header `lid` e `licenseId` più quelli comuni (`system`, `appPlatform`, `language`,
  `timestamp`, `appVersion`, `messageId`, `Content-type: application/x-java-serialized-object`);
* la cifratura: `AES/CBC/ZeroBytePadding`, IV `eaaaaa3abb5862a21918b5771d1615aa`, chiave
  `md5(timestamp + sale)` interpretata come esadecimale;
* la password arriva grezza dall'interfaccia (`LoginActivity$LoginTask` → `BLAccount.login`):
  nessun hash intermedio;
* le coppie `licenseId`/`companyid`, ricavate dai blob di licenza dell'APK.

**Ipotesi rimaste, in ordine di probabilità.**

1. **L'account non esiste in questo scope.** `-1008` potrebbe voler dire "utente non trovato per
   questa coppia companyid/lid", non "password sbagliata". Se l'account è stato creato con una
   versione precedente dell'app o con un'altra app OEM della stessa piattaforma, vive sotto un
   altro `companyid`.
2. **Un header che il server pretende e noi non mandiamo.** Candidati visti nel dex:
   `datatrace` (Base64 di un JSON), `loginmode: mutuallyexclusive`, e gli header comuni presi da
   `HTTP_COMMON_HEADER` (che l'app riempie con `BLSettingUnits.getCompany()`).
3. **Una versione minima dell'app.** Mandiamo `appVersion: 1.0.12`; l'APK installato è più
   recente. Si prova con `KLIMAKONTROL_APP_VERSION`.
4. **Orologio.** Se il `timestamp` è troppo lontano da quello del server, la chiave derivata è
   valida ma la richiesta viene scartata. Verificare `date -u`.

**Come si chiude.**

Primo passo, gratis: **leggere il messaggio del server**, non solo il codice.

```bash
KLIMAKONTROL_DEBUG=1 python3 -m klimakontrol login --region eu
```

Stampa richiesta e risposta grezze. Se il messaggio dice "user not exist" siamo nell'ipotesi 1;
se dice "password error" siamo nella 2 o nella 3.

Secondo passo, decisivo: **confrontare con la richiesta vera dell'app**.

```bash
adb logcat -c && adb logcat | grep -iE 'Json Param|Http Url|BroadLink|LoginActivity'
```

e fare il login dall'app ufficiale. L'SDK logga il corpo in chiaro con il prefisso
`Json Param:` — se il livello di log lo permette. Altrimenti cattura del traffico
(`docs/recipes-adb.md` §5): si vedono gli header veri e la lunghezza del corpo, e il confronto
con i nostri chiude la questione in un minuto.

Terzo passo, se serve la certezza assoluta: hook a runtime con Frida
(`docs/recipes-adb.md` §6) sulla funzione che costruisce il corpo.

**Attenzione al rate limit.** Dopo pochi tentativi ravvicinati il cloud risponde `-1036` e
blocca i login per qualche minuto. Cambia **una** variabile per tentativo, e non ciclare.

## 2. La scrittura delle pianificazioni sul filo — bloccata dal nativo (indagine 2026-08-21)

**Cosa manca.** La codifica sul filo di `dev_taskadd`, `dev_tasklist`, `dev_taskdata`,
`dev_taskdel`. Il modello dati e la conversione di fuso sono già in `tasks.py`; il payload del
task è costruito dal JS della WebView (`app.html`/`main.*.js`) con lo stesso transform
encode(1)/decode(2) di `to_wire`/`from_wire`. Manca il **byte di azione** e l'esatto
impacchettamento.

**Cosa ho scoperto (perché è dura).**

* La UI usa i task **device-side** via il bridge `devicecontrol(deviceID, subDeviceID, payload,
  "dev_taskadd", cfg)`: `dev_tasklist` manda payload `{}`, `dev_taskadd` il task codificato.
* Siccome l'app è **cloud-only** (cattura PCAPdroid: nessun controllo locale, solo discovery),
  i comandi task NON usano `KeyValueControl` come `dev_ctrl`. Nel dex (`cn.com.broadlink.sdk.b`)
  i comandi diversi da `dev_ctrl` vanno per la via `dev_passthrough` / `DNA.TransmissionControl`,
  dove il payload è il **pacchetto grezzo del dispositivo in Base64**, costruito dal nativo.
* Il pacchetto grezzo lo costruisce `libNetworkAPI.so` eseguendo lo **script Lua del modello**
  (`…2e4e0000.script`). Ma il `.script` è **cifrato con un cifrario proprietario BroadLink "tfb"**
  (NON AES): funzioni native `networkapi_scriptfile_read` → `broadlink_tfb_decrypt`,
  `broadlink_tfb_setkey_dec`, `broadlink_tfb_crypt_cfb128/…`; VM **Lua 5.3** integrata.

Quindi il byte di azione + la struttura del task stanno dietro un **cifrario proprietario + Lua**
nel nativo: non derivabili dal dex né dal JS.

**Come si chiuderebbe** — nessuna è banale.

1. **RE del cifrario `tfb`**: estrarre la chiave da `networkapi_scriptfile_read` e reimplementare
   `broadlink_tfb_decrypt` (dall'assembly ARM) per decifrare il `.script`, poi leggere il Lua.
   Bonus: sblocca anche `if_function` e i limiti di temperatura (§4).
2. **Brute-force del byte di azione** via cloud passthrough (`DNA.TransmissionControl`): costruire
   un `dev_tasklist` grezzo con byte di azione indovinato (get=1, set=2 → i task 3-8) e vedere
   quale il modulo accetta. Non serve il `.script`, ma va replicato il formato passthrough e si
   scrive alla cieca sul modulo.
3. **Cattura TLS** della richiesta reale (serve APK ripacchettizzato per il cert utente, o Frida),
   poi Base64-decode + decifra con la chiave del dispositivo.

Nota: la vecchia idea di catturare un **pacchetto UDP locale** dell'app **non funziona** — l'app
non manda mai controllo/task in locale (solo discovery). Esiste anche l'API cloud
`/appfront/v1/timertask/*` (pianificazione lato server), ma perde il pregio dell'offline.

---

## 3. `devicetypeflag`

Nel corpo di `sdkcontrol` c'è `devicePairedInfo.devicetypeflag`, letto dall'SDK con
`BLDNADevice.getDeviceFlag()`. Non è chiaro da quale campo di `getallinfo` arrivi: oggi
`cloud.py` prende `devicetypeflag`/`devicetypeFlag` se presenti, altrimenti 0.

**Come si chiude.** Al primo login riuscito, guardare il record grezzo del dispositivo:

```bash
python3 -m klimakontrol raw 1
```

Nel dex c'è un ramo che tratta il valore `4` in modo speciale (`getDeviceFlag() == 4` forza il
percorso remoto), quindi il campo conta almeno in qualche caso.

---

## 4. La maschera `if_function`

`if_function` dice quali funzioni il singolo modello supporta davvero. Il valore si legge; la
corrispondenza bit → funzione no.

**Aggiornamento (2026-08-22):** su questi moduli `0x4e2e` `if_function` **non viene nemmeno
riportato** nel set fisso del `get`. In compenso quel set fisso (10 parametri, §5) *è* di fatto la
lista di capacità: include entrambi gli swing e omette mute/salute/display. Provato a nascondere i
controlli in base al set riportato: funziona, ma solo con le chiavi giuste (`ac_vdir`/`ac_hdir`,
non `tcl_*`) — con quelle sbagliate spariva anche lo swing reale. Per ora l'app espone esattamente
i 10 del set, senza euristiche.

**Come si chiude.** Leggere `if_function` sull'impianto, poi confrontare con i pulsanti che
l'app ufficiale mostra per quel modello. Da lì si ricava a quale bit corrisponde ogni funzione.
Serve per costruire un'interfaccia che mostra solo il vero, invece di riempire lo schermo di
comandi morti come fa l'app.

Alternativa più rapida: i file `.script` negli assets (uno per `pid`) contengono la definizione
del modello. Sono cifrati, ma la chiave è probabilmente derivabile: se si aprono, danno
`if_function`, i limiti di temperatura e l'elenco dei parametri per modello, tutto insieme.

---

## 5. La forma esatta delle risposte del cloud — ✅ in parte RISOLTA (2026-08-21)

`sdk_control` legge `event.payload.data`. **Visto su risposta reale**: `data` arriva come
**stringa JSON** (non oggetto), del tipo
`'{"params":["pwr","tcl_mode","save_temp",...],"vals":[[{"val":1,"idx":1}],...]}'`. `get_state`
e `set_state` ora ne fanno `json.loads`. Nota: il `get` ignora i `params` richiesti e ritorna
sempre un set fisso di parametri deciso dal modulo. `querystate` ritorna
`event.payload.data` come lista `[{"did":..., "state":0|1}]`.

**Set fisso visto sul filo (2026-08-22, devtype `0x4e2e`, identico su tutte le unità):**
`pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode`.
Due sorprese: lo swing è `ac_vdir`/`ac_hdir` (non `tcl_vdir`/`tcl_hdir`); e `qtmode`, `ac_health`,
`bglight`, `envtemp`, `if_function` **non** vi compaiono — su questo modello non sono gestiti.

Lo stesso vale per `dataservice`: il pannello dell'app legge `table[0].values`, ma la forma
delle singole righe (nomi dei campi per kWh, ore di funzionamento, buchi di rete) va vista.

---

## 6. Gli altri endpoint individuati e non studiati

Trovati nel dex, mai provati. In ordine di utilità per questo progetto:

| Endpoint | A cosa servirebbe |
| --- | --- |
| `/appfront/v1/timertask/*` | pianificazioni lato cloud (vedi §2) |
| `/appfront/v1/scene/*`, `/appfront/v1/trigger/upsert` | scene e automazioni a trigger |
| `/ec4/v1/electricinfo/config` | configurazione del monitoraggio consumi |
| `/device/control/v3/sdkcontrol` | controllo di gruppo (più unità in una chiamata) |
| `/ec4/v1/dev/*`, `/ec4/v1/module/*` | rinominare unità e stanze, condivisioni |
| `/dataservice/v1/device/status` | storico di stato (implementato, mai provato) |
