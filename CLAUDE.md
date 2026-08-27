# klimakontrol — istruzioni per Claude Code

Leggi questo file **prima** di toccare qualsiasi cosa. Contiene lo stato reale del progetto,
le convenzioni, e le trappole (§5) in cui si cade se si tira a indovinare.

Rispondi all'utente e scrivi i **commenti nel codice in italiano**. Gli identificatori nel codice
restano in inglese. La **documentazione** (README e `docs/*.md`) è invece **in inglese** (scelta del
proprietario, 2026-08-27): i file `.md` esistenti sono stati tradotti; quelli nuovi vanno scritti in
inglese. Questo file `CLAUDE.md` resta in italiano.

---

## 1. Cos'è questo progetto

Il proprietario ha dei climatizzatori **Wisnow** (marchio italiano su hardware TCL) con modulo
WiFi **BroadLink DNA**. L'app ufficiale (`com.ab.smartDevice`, "Intelligent AC") funziona male:
comandi persi, attese lunghe, timer che scattano all'ora sbagliata.

Obiettivo: una libreria e poi un'app che parlino direttamente ai climatizzatori — in casa via
UDP locale, da fuori attraverso il cloud BroadLink — con controllo immediato, multi-split,
pianificazioni affidabili e storico consumi.

Il protocollo è stato **ricostruito dall'APK ufficiale**, non indovinato. Le specifiche
complete sono in `docs/protocol.md`; come sono state ricavate, in `docs/reverse-engineering.md`
e `docs/apk-analysis.md`.

Quadro legale: interoperabilità con hardware di proprietà, consentita in UE dalla direttiva
2009/24/CE art. 6. Non si ridistribuisce codice altrui e non si clona l'app.

---

## 2. Stato attuale, senza abbellimenti

| Pezzo | Stato |
| --- | --- |
| Protocollo locale UDP:80 (AES, pacchetti, checksum) | **implementato e verificato** contro un pacchetto reale documentato |
| Discovery in LAN | **provato su HW** (2026-08-21): trova le unità in broadcast |
| Autenticazione BroadLink in LAN | **provata su HW**: la sessione si stabilisce, ma il controllo locale dà -5 e l'app non lo usa (vedi §5, trappola 4) |
| Login cloud | **funziona su HW** (2026-08-21). Il vecchio -1008 era il companyid sbagliato: risolto (vedi §5, trappola 1) |
| Registrazione account | **implementata** in lib+CLI (`register-code` + `register`), ricostruita dal dex e coperta da test offline; **non ancora provata su HW**. Due passi: `POST /account/newregcode` (invia il codice email/SMS) e `POST /account/register` (multipart, campo `text` cifrato) che ritorna già `userid`+`loginsession`. Stessi host/IV/sali del login; codice di verifica obbligatorio (passo umano) |
| Controllo remoto (`sdkcontrol`) | **provato su HW**: `on`/`off` (`pwr`) e setpoint (`save_temp`) cambiati via cloud e verificati in lettura |
| Stato online batch (`querystate`) | **provato su HW**: tutte le unità online |
| Lettura stato (`sdkcontrol get`) | **provata su HW**: la risposta arriva in `payload.data` come **stringa JSON** (`json.loads`) |
| Storico consumi (`dataservice`) | **provato su HW**: l'endpoint `/stats` risponde `ok` ma **vuoto** — queste unità non misurano i consumi (nessun parametro di potenza). `/status` dà `-12401`; l'host `rtasquery` (quello vero dei dataservice) non risolve col template `<lid>` |
| 79 parametri, enumerati, unità | completi, dal codice sorgente dell'app. Nota: su questi moduli 0x4e2e il setpoint è **`save_temp`**, non `temp` (aliasato in `params.wire_key`) |
| Pianificazioni (timer) | **CHIUSE le vie native su questo HW** (prova su `0x4e2e`, 2026-08-27): `dev_tasklist` via `sdkcontrol` **ritorna lo stato, non i task** (lo script Lua del modello ha i timer rimossi); l'API cloud `/appfront/v1/timertask/*` è **codice morto** nell'app (nessun modello la usa, schema non ricavabile); la "Reservation" a parametri (`sub_*`) **non è nel profilo** di questi moduli. Conclusione: **questo hardware non ha uno scheduler nativo**. → L'**app** usa **timer lato telefono** (`AlarmManager` + `BroadcastReceiver`, `data/schedule/`): all'ora manda on/off via cloud, ri-arma il ricorrente/consuma il one-shot, riprogramma al reboot; rapido ("tra X", passi di 30') + ricorrenti settimanali. **Provato su HW (2026-08-27)**: scatta con schermo acceso **e in standby/Doze** (`setExactAndAllowWhileIdle`, telefono OnePlus Nord). Limite: a telefono spento o app force-stoppata dall'OEM può non scattare (ma il controllo offline su questi moduli non esiste comunque, §5 trappola 4). La **lib+CLI Python** (`add_task`/`list_tasks`/`task`) resta **device-side** e quindi **non funzionante su questo HW** (lasciata come riferimento; `tfb` è comunque AES-128 standard, vedi memory `klimakontrol-tfb-is-aes`). Dettagli in `docs/open-questions.md §2`. |
| Onboarding modulo — bind cloud (Punto 1) | **implementato** in lib+CLI (`bind_device` + comando `bind`), ricostruito dal dex (`BLFamilyManager.addEndpoint` → `POST /appsync/group/dev/manage?operation=add`, corpo JSON **in chiaro**, chiave AES nel `cookie`; `familyId` da `getfamilyid`), coperto da test offline; **non ancora provato su HW** (forma della risposta da confermare con `KLIMAKONTROL_DEBUG=1`). Design/piano in `docs/superpowers/specs\|plans/2026-08-25-onboarding-*` |
| Onboarding modulo — config SoftAP (Punto 2) | **implementato** in lib+CLI (`provision.py`: `build_softap_packet`+`softap_config`, comando `provision`), **ricostruito dal nativo** `libNetworkAPI.so` (spec in `docs/softap-apconfig.md`): pacchetto di setup BroadLink storico, **136 byte in chiaro**, comando `0x14`, checksum `0xBEAF`, UDP `192.168.10.1:80`. **Test dorato** byte-esatto (`TestNet`/`secret12` → `0xC482`). **Non** è il muro `tfb` (quello sono le varianti `protocol=1/2`). **Non ancora provato su HW**. Manca ancora: ricavare `did/pid/mac/devkey` dopo la config (discovery+auth o cloud) e il wizard nell'app (Punto 4) |
| App Android | **funzionante**: Kotlin + Jetpack Compose (sistema "Quadrante"), compila in **CI** (`.github/workflows/android.yml`). Fa **login** (con **selettore regione/vendor**) e **registrazione nuovo account** (due passi, come la lib), e **controlla i climatizzatori veri** via cloud (accensione, temperatura, modalità, ventola, eco/turbo/notte, **oscillazione** `ac_vdir`/`ac_hdir`), con comandi ottimistici e roll-back; sessione persistente senza password; **icona app** adattiva. In più: **Impostazioni** (cambio nickname `modifynickname` e password `modifypwd`, ricostruiti dal dex; versione app; controllo aggiornamenti via GitHub Releases; logout/forget), barra **Rinfresca casa/Spegni tutte** in Home. **Bilingue** (italiano default + inglese) con **selettore lingua** in Impostazioni (risorse `res/values`+`values-en`; switch a runtime via `LocaleStore`+`attachBaseContext`, senza AppCompat). Versione `0.7.0`. Pubblicazione Play: tutto documentato in `docs/play-store.md` (firma/AAB/CI da applicare col keystore). Espone solo i 10 del set fisso reale (§5, trappola 4): tolti mute/salute/display perché non gestiti. In più: **wizard di onboarding end-to-end** (Punto 4) — aggiungi un climatizzatore vergine dall'app, ora **completo in Kotlin**: config SoftAP (`data/softap/SoftApClient.kt`) → **discovery+auth in LAN** (`data/onboarding/LanProbe.kt`, porta di `local.py`: trova il modulo e ne ricava la chiave) → **bind cloud** (`CloudClient.bindDevice` = `bind_device`, POST `/appsync/group/dev/manage`) orchestrati da `CloudService.addNewModule`; `did`/`pid` ricavati dai dati reali dell'account (`did`=10 byte zero+MAC, `pid`=devtype LE) — **da confermare su modulo vergine**. `ui/OnboardingScreen.kt` a 5 passi (aggiunto "Aggiungi all'account"). Conferma end-to-end **HW-gated** (serve un modulo vergine, `docs/onboarding-hw-test.md`). In più: **timer lato telefono** dal Dettaglio (icona orologio) — **timer rapido** ("tra X ore", passi di 30') + **ricorrenti** settimanali on/off (ora, giorni, temperatura), `ui/TimerScreen.kt` + `data/schedule/` (`AlarmManager`→`AlarmReceiver`→invio cloud; ri-arma al reboot; notifica esito). Non usa più i task device-side (chiusi su questo HW, vedi riga Pianificazioni). Provato su HW il 2026-08-21/22; **timer provati su HW il 2026-08-27, anche in standby**; wizard: non ancora. Vedi `android/README.md` |

La via cloud è ora **provata su un impianto reale** (impianto Wisnow del proprietario, 3 unità,
devtype `0x4e2e`): login, lista, stato, online, `on`/`off` e setpoint funzionano. Chiuse anche, con
esito negativo ma definitivo: il controllo **locale** (il `-5`: l'app è cloud-only, §5 trappola 4) e
lo **storico consumi** (le unità non lo misurano). Resta da provare: le **pianificazioni**. Ogni
volta che una funzione viene esercitata per la prima volta, aggiorna questa tabella.

---

## 3. Come si lavora qui

```bash
python3 -m unittest discover -s tests -q     # devono passare tutti, sempre
python3 -m klimakontrol --help
```

Regole non negoziabili:

1. **Zero dipendenze a runtime.** Solo la libreria standard di Python 3.8+. L'AES è
   implementato in `klimakontrol/aes.py` proprio per non dipendere da `cryptography` o
   `pycryptodome`: il codice deve girare anche dove non si può compilare nulla (Termux, NAS,
   container minimali). Se ti viene voglia di aggiungere `requests`, non farlo.
   Le sole eccezioni sono gli strumenti in `tools/` per l'analisi dell'APK, che possono usare
   `androguard` — sono di sviluppo, non fanno parte del pacchetto.
2. **I test non toccano la rete.** Mai. Si testano costruzione di pacchetti, firme,
   conversioni, parsing. Le risposte del server si simulano sostituendo `_request`.
3. **Un test dorato non si tocca.** `tests/test_local.py::test_matches_documented_golden_packet`
   verifica che il payload di `set temp 23.0` esca identico byte per byte a quello osservato sul
   filo, checksum interno compreso. È l'unica prova indipendente che l'implementazione è
   corretta. Se si rompe, hai rotto il protocollo, non il test.
4. **Niente costanti ricopiate a mano.** Vedi §5: due volte su due, i valori presi dai progetti
   della community erano sbagliati. Se un valore si può derivare dall'APK, derivalo nel codice e
   scrivi da dove viene.
5. **I segreti non finiscono nei log né nei file.** `session.py` salva a 0600 e non scrive mai la
   password; `session.mask()` sostituisce chiavi, MAC, ID e token con la loro lunghezza, così
   l'output si può incollare in una chat. Usalo in ogni comando che stampa JSON.
6. Commit con messaggi che spiegano *perché*, non *cosa*. La storia di questo repo è anche il
   diario dell'analisi.

### Struttura

```
klimakontrol/
  aes.py       AES-128-CBC in Python puro (cifra e decifra), vettori FIPS-197 nei test
  local.py     UDP:80 BroadLink/DNA: pacchetti, checksum, discovery, auth LAN, get/set
  cloud.py     regioni, login, dispositivi, sdkcontrol, querystate, consumi, storico stato
  params.py    i 79 parametri: etichette italiane, categorie, unità, enumerati, encode/decode
  tasks.py     le 5 famiglie di pianificazioni e la conversione UTC+8
  session.py   persistenza (0600, senza password) e mascheramento dei segreti
  cli.py       CLI con trasporto automatico locale/cloud
tools/         strumenti di analisi dell'APK (androguard) e estrazione dei sali
docs/          protocollo, cronaca del reverse engineering, domande aperte, roadmap, ricette
tests/         136 test, tutti offline
```

---

## 4. Il modello mentale in dieci righe

Un climatizzatore è identificato da `did` (id cloud), `mac`, `pid` (modello) e una **chiave AES
per-dispositivo** (`aeskey`). Con quella chiave si parla al modulo in due modi:

* **in LAN**: pacchetto UDP alla porta 80, payload JSON cifrato AES-128-CBC. Millisecondi.
* **dal cloud**: `POST /device/control/v2/sdkcontrol` con una "direttiva" che contiene lo
  **stesso JSON** e, in un campo `cookie` in Base64, la chiave AES del dispositivo. Il cloud fa
  solo da postino.

Quindi: **un solo modello di dominio, due trasporti**. Il JSON di comando è sempre
`{"act": "get"|"set", "params": [...], "vals": [[{"val": N, "idx": 1}]]}` (i moduli TCL
accettano in LAN anche la forma breve `{"save_temp": 230}`). Attenzione: su questi moduli il
setpoint è `save_temp`, non `temp` (vedi §5, trappola 4).

La chiave AES si ottiene dal cloud (`getallinfo`) **oppure** dall'autenticazione BroadLink in LAN.
Nota da HW reale: l'auth LAN stabilisce la sessione senza account, ma il **controllo** locale poi
risponde `-5` (§5, trappola 4) — il funzionamento del tutto offline non è ancora sbloccato; per ora
si controlla via cloud.

---

## 5. Le tre trappole (leggile, sono costate ore)

### Trappola 1 — il `companyid` è la costante condivisa (non `blob[16:32]`)

Il login manda `companyid` e `lid`. Il companyid vero è **`8503b08fa57729df9faa45e4c978852c`**:
la **costante condivisa** da tutte e quattro le licenze BroadLink dell'app, ai byte `[120:136]`
del blob. Il `lid` invece è **per-regione** (i primi 16 byte del blob).

⚠️ Questa nota diceva **l'opposto** e ha bloccato il login per giorni: sosteneva che `8503b08f…`
(il valore usato dai progetti community) fosse sbagliato e che il companyid vero fosse
`blob[16:32]` (per-regione, es. eu `57c9e5ad…`). Era il contrario. Il 2026-08-21 un login
riuscito su region eu ha restituito, **echeggiato dal server**, `companyid: 8503b08f…`; con
`blob[16:32]` il cloud rispondeva sempre `-1008` ("credenziali errate"), mandando a cercare il
problema nel posto sbagliato. L'app "Intelligent AC" è **una sola company OEM** declinata su più
regioni: un companyid condiviso, un lid diverso per regione.

Per questo `cloud.py` **non contiene identificativi ricopiati**: tiene i blob di licenza
(`LICENSE_BLOBS`) e ne ricava `licenseId = blob[0:16]` e `companyid = blob[120:136]` a ogni
avvio. Se aggiungi una regione, aggiungi il blob, non i due hash.

### Trappola 2 — il login: la forma è giusta, la colpa era il companyid

Il login usa tre costanti (i "sali"), tutte e tre **verificate** estraendole da
`libBLAccountEncryptAPI.so` con `tools/extract_salts.py`:

```
password = SHA1(password + "4969fj#k23#")
token    = md5(corpoJson + "xgx3d*fe3478$ukx")     # header
chiave   = md5(timestamp + "kdixkdqp54545^#*")     # AES del corpo, esadecimale -> 16 byte
```

Verificato dal dex anche tutto il resto: host `https://<lid>appservice.ibroadlink.com`
(l'SDK parte con `APP_SERVICE_ENABLE=1`, quindi *non* usa gli host `biz*`), percorso
`/account/login`, corpo `{email|phone, password, companyid, lid}`, header `lid` e `licenseId`,
`AES/CBC/ZeroBytePadding` con IV `eaaaaa3abb5862a21918b5771d1615aa`, e la password passata
grezza dall'interfaccia a `BLAccount.login` senza pre-elaborazioni.

**Il `-1008` NON era la forma della richiesta né i sali** (tutto byte-corretto): era il
**companyid** sbagliato — vedi trappola 1. Risolto il 2026-08-21: login, elenco unità,
`querystate` e controllo via cloud provati e funzionanti. Se un giorno il login tornasse a
fallire, il primo passo resta leggere il **messaggio** del server (`KLIMAKONTROL_DEBUG=1` stampa
richiesta e risposta grezze): `-1008` copre "utente inesistente in questo scope", "password
errata" e "account bloccato", casi diversi.

I sali sono sostituibili senza toccare il codice, se un giorno cambiassero:

```bash
export KLIMAKONTROL_SALT_PASSWORD='...'
export KLIMAKONTROL_SALT_TOKEN='...'
export KLIMAKONTROL_SALT_BODY='...'
export KLIMAKONTROL_APP_VERSION='1.0.12'
```

### Trappola 3 — il firmware vive in UTC+8

Il modulo ragiona in UTC+8 (eredità del cloud cinese). Ogni orario — pianificazioni e anche le
finestre temporali delle API dei consumi — va convertito sommando `8 - offset_locale` ore. Per
l'Italia: +6 in ora legale, +7 in ora solare.

Nel codice dell'app ci sono **tre implementazioni diverse** di questa conversione, con i
commenti di debug ancora dentro. È quasi certamente il motivo per cui i timer dell'app
ufficiale scattano quando gli pare. Qui la conversione sta in un posto solo:
`tasks.to_device_time` / `from_device_time`, e in `cloud._fmt_device_time`.

### Trappola 4 — su questi moduli il setpoint è `save_temp`, e il controllo locale dà -5

Due cose emerse alla prima prova su hardware reale (unità Wisnow, devtype `0x4e2e`):

* **`temp` viene ignorato**: il setpoint di temperatura effettivo è **`save_temp`** (stessa scala,
  decimi di grado). Verificato: `set temp=N` non muoveva nulla, `save_temp=N` sì. `params.wire_key`
  traduce `temp`→`save_temp` sul filo, così l'utente usa il nome naturale. Il pacchetto dorato
  locale usa ancora `temp` perché è un modello `0x507C` diverso.

  **Stessa storia per lo swing** (visto sul filo il 2026-08-22): il modulo usa **`ac_vdir`/`ac_hdir`**,
  non `tcl_vdir`/`tcl_hdir` come nell'estrazione APK — scrivere `tcl_vdir` non muoveva l'aletta.
  Aggiunti a `WIRE_KEY_ALIASES`. Corollario importante: il `get` ritorna **sempre lo stesso set
  fisso di 10** (identico su tutte le unità), che *è* la lista di capacità del modello:
  `pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode`.
  Ciò che non c'è (`qtmode`/mute, `ac_health`, `bglight`, `envtemp`, `if_function`) **non è gestito
  via cloud** — sono funzioni solo-telecomando IR. Non aggiungerle in UI: sono pulsanti morti.
* **Il controllo locale (UDP `0x6a`) risponde `-5`** anche con la chiave AES vera del cloud e l'id
  giusto (`terminalid`). Non è la chiave, l'id, la porta sorgente, la password del device né la forma
  del comando (tutti esclusi). **Perché**: una cattura del traffico dell'app (PCAPdroid, 2026-08-21)
  mostra che **l'app non manda MAI un controllo locale** a questi moduli — in LAN fa solo *discovery*
  (broadcast `0x06`); ogni comando va in HTTPS al cloud (`47.254.182.109`). Su questo firmware `0x4e2e`
  il controllo LAN puro non è supportato: **la via locale serve solo al discovery, il controllo è
  cloud**. Il `-5` non è un bug da sistemare. (Se un giorno servisse il locale, andrebbe replicato
  l'`SDKAuth` nativo — login + `packageName` + license — che forse autorizza il controllore.)

---

## 6. Cosa fare adesso, in ordine

Il dettaglio è in `docs/roadmap.md`; la sintesi:

1. ✅ **Login sbloccato** (companyid, §5 trappola 1). Via cloud provati su HW: `login`, `list`,
   lettura stato, `on`/`off` (`pwr`), setpoint (`save_temp`), `querystate`. Tutto il controllo
   utile funziona da fuori casa.
2. ✅/❌ **Chiusi con esito negativo ma definitivo**: controllo **locale** (`-5`, l'app è cloud-only,
   §5 trappola 4) e **storico consumi** (queste unità non lo misurano). Non serve insistere.
3. **Pianificazioni** — l'unico grosso pezzo ancora aperto, ma **gated dal nativo**: la codifica
   sul filo la fa lo script **Lua** del modello, cifrato col cifrario proprietario **`tfb`**
   (`docs/open-questions.md` §2). Richiede RE del cifrario o brute-force del byte di azione.
4. **App Android** — ✅ **funzionante** (Kotlin+Compose, sistema "Quadrante"): login + controllo
   reale dei climatizzatori via cloud, comandi ottimistici con roll-back, sessione persistente.
   Rifiniture rimaste: debounce + feedback "invio…/✓/errore" per comando; font veri
   (Space Grotesk/Inter); lettura temperatura ambiente (`querystate`); icona e impostazioni.
   Dettagli in `android/README.md`.

---

## 7. Cose da non fare

* Non aggiungere dipendenze a runtime.
* Non mettere la rete nei test.
* Non ricopiare costanti da progetti di terzi senza verificarle nell'APK.
* Non stampare chiavi AES, `did`, MAC o token senza passarli da `session.mask()`.
* Non ritentare il login in ciclo: il cloud risponde `-1036` e blocca l'account per un po'.
  `login_any_region()` si ferma al primo rate limit, di proposito.
* Non "sistemare" il test dorato per farlo passare.
* Non toccare `assets`, `.dex` o `.so` dentro il repo: sono materiale d'analisi, restano fuori
  (vedi `.gitignore`).
