# klimakontrol — istruzioni per Claude Code

Leggi questo file **prima** di toccare qualsiasi cosa. Contiene lo stato reale del progetto,
le convenzioni, e le tre trappole in cui si cade se si tira a indovinare.

Rispondi e scrivi commenti/documentazione **in italiano**. Gli identificatori nel codice
restano in inglese.

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
| Discovery e autenticazione BroadLink in LAN | implementati, **mai provati su hardware** |
| Login cloud | implementato, **fallisce con -1008** per causa ancora ignota (vedi §5, trappola 2) |
| Controllo remoto (`sdkcontrol`) | implementato secondo il dex, **mai provato** (serve il login) |
| Stato online batch (`querystate`) | implementato, mai provato |
| Storico consumi (`dataservice`) | implementato, mai provato |
| 79 parametri, enumerati, unità | completi, dal codice sorgente dell'app |
| Pianificazioni | modello e conversione di fuso completi; **scrittura sul filo non ancora nota** |
| App Android | non iniziata |

**Nulla è stato ancora provato su un impianto reale.** Ogni volta che una funzione viene
esercitata per la prima volta contro l'hardware o il cloud, aggiorna questa tabella.

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
tests/         102 test, tutti offline
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
accettano in LAN anche la forma breve `{"temp": 230}`).

La chiave AES si ottiene dal cloud (`getallinfo`) **oppure** dall'autenticazione BroadLink in
LAN, che sui moduli già associati funziona senza account: è la strada per un funzionamento
completamente offline.

---

## 5. Le tre trappole (leggile, sono costate ore)

### Trappola 1 — il `companyid` che gira online è sbagliato

Il login manda `companyid` e `lid`. I progetti open source usano
`8503b08fa57729df9faa45e4c978852c` come companyid della regione internazionale. **Non lo è**:
quel valore compare identico in tutte e quattro le licenze BroadLink dell'app, è una costante
globale. Il companyid vero della regione internazionale è `a8452a8f48ae707edc12e9c52e21f00f`.

Con la coppia sbagliata il cloud risponde `-1008`, che significa "credenziali errate" —
mandando a cercare il problema nel posto sbagliato.

Per questo `cloud.py` **non contiene identificativi ricopiati**: tiene i blob di licenza
estratti dall'APK (`LICENSE_BLOBS`) e ne ricava `licenseId` (primi 16 byte) e `companyid`
(successivi 16) a ogni avvio. Se aggiungi una regione, aggiungi il blob, non i due hash.

### Trappola 2 — il login viene rifiutato e la forma è giusta

Il login usa tre costanti, tutte e tre **verificate** estraendole da
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

**Eppure il cloud risponde `-1008` su tutte e quattro le regioni, con credenziali che
funzionano nell'app.** La forma della richiesta non è più il sospetto principale.

Le ipotesi rimaste, in ordine, e come distinguerle sono in `docs/open-questions.md` §1. La
prima cosa da fare è leggere il **messaggio** del server, non solo il codice: `-1008` copre
"utente inesistente in questo scope", "password errata" e "account bloccato", e sono casi
diversi. Ora l'errore riporta il messaggio, e con `KLIMAKONTROL_DEBUG=1` si vedono richiesta e
risposta grezze.

Sostituibili senza toccare il codice, se un giorno cambiassero:

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

---

## 6. Cosa fare adesso, in ordine

Il dettaglio è in `docs/roadmap.md`; la sintesi:

1. **Sbloccare il login**: estrarre i sali da `libBLAccountEncryptAPI.so`
   (`docs/recipes-adb.md` §1), provarli, aggiornare i default in `cloud.py` documentando la
   provenienza.
2. **Prima prova sul campo**: `login`, `list`, `status`, `on`/`off`, `set`, `energy`. Correggere
   quello che emerge — in particolare `devicetypeflag` e la forma esatta delle risposte, oggi
   dedotte e non viste.
3. **Prova locale**: `discover`, poi `--transport local`. È la parte che rende l'esperienza
   istantanea; verificare anche l'autenticazione LAN senza account.
4. **Chiudere le pianificazioni** (`docs/open-questions.md` §2).
5. **App Android** sopra questa libreria, compilata in CI (il proprietario ha un account
   GitHub; su quella macchina non c'è l'SDK Android).

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
