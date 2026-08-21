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

## 2. La scrittura delle pianificazioni sul filo

**Cosa manca.** Come vengono impacchettati `dev_taskadd`, `dev_tasklist`, `dev_taskdata`,
`dev_taskdel`. Il modello dati e la conversione di fuso sono già in `tasks.py`; manca la
codifica del comando nel pacchetto.

**Perché manca.** Per `dev_ctrl` il livello nativo usa l'azione `1` (get) o `2` (set) nel
payload interno. Gli altri comandi finiscono in
`BLControllerDescParam.setCommand("dev_taskadd")` e poi nel nativo, che costruisce il pacchetto
usando i file `.script` cifrati negli assets dell'APK. Il byte di azione corrispondente non è
noto.

**Come si chiude** — due strade, la prima è quella buona.

1. **Cattura del pacchetto UDP locale.** La chiave AES del dispositivo è nota, quindi il
   pacchetto si decifra e si legge il payload interno. Con il telefono e il PC sulla stessa rete:

   ```bash
   # sul PC, con il telefono che crea un timer dall'app ufficiale
   sudo tcpdump -i any -w timer.pcap 'udp port 80'
   ```

   Poi si decifra con `klimakontrol.local.parse_packet` usando la chiave del dispositivo, e si
   guarda il byte all'offset `0x08` del payload interno: è l'azione per `dev_taskadd`.
   Basta *un* pacchetto per comando.

2. **API cloud `/appfront/v1/timertask/{add,query,modify,delete}`.** Individuata nel dex ma non
   ancora studiata. Pianifica lato server invece che nel modulo: comodo, ma perde il pregio
   grosso (il modulo esegue anche a telefono spento e senza internet).

Priorità: la 1 sblocca la funzione più preziosa del progetto.

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
