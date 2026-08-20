# klimakontrol

Controllo dei climatizzatori **Wisnow / TCL** con modulo WiFi **BroadLink DNA**, senza
l'app ufficiale.

L'app in commercio (`com.ab.smartDevice`, "Intelligent AC") è lenta, perde i comandi e
sbaglia i timer. Questo progetto parla direttamente con i climatizzatori: in casa via UDP
sulla rete locale, da fuori casa attraverso lo stesso cloud che usa l'app.

Il protocollo è stato ricostruito dall'APK ufficiale — che include per errore le source map
del pannello di controllo, e quindi il suo codice sorgente. Le specifiche complete sono in
[`docs/protocol.md`](docs/protocol.md).

## Cosa fa oggi

| | |
| --- | --- |
| Controllo locale | UDP porta 80, AES-128-CBC. Risposta in millisecondi, funziona senza internet |
| Controllo remoto | HTTPS verso `appservice.ibroadlink.com`, da qualsiasi rete |
| Trasporto automatico | prova la rete locale, ripiega sul cloud |
| Modello dati | 79 parametri documentati: controllo, sensori, comfort, diagnostica, energia |
| Storico consumi | report orari, giornalieri e mensili, con ore di funzionamento |
| Pianificazioni | modello e conversione di fuso completi (vedi *Limiti*) |
| Sicurezza operativa | output mascherabile, sessione salvata con permessi 0600, password mai scritta |

Niente dipendenze esterne: solo Python 3.8+. L'AES è implementato nel pacchetto e verificato
contro i vettori FIPS-197, così il codice gira anche dove non si può compilare nulla.

## Installazione

```bash
git clone <questo repo> klimakontrol
cd klimakontrol
python3 -m klimakontrol --help
```

Volendo, `pip install -e .` per avere il comando `klimakontrol` nel PATH.

## Uso

```bash
python3 -m klimakontrol login                     # chiede email e password, prova tutte le regioni
python3 -m klimakontrol list
python3 -m klimakontrol status 1
python3 -m klimakontrol status 1 --full           # anche diagnostica e sensori
python3 -m klimakontrol on 1
python3 -m klimakontrol set 1 temp=23 tcl_mode=freddo tcl_mark=auto
python3 -m klimakontrol off 1
python3 -m klimakontrol online                    # stato di tutte le unità, una chiamata
python3 -m klimakontrol energy 1 day
python3 -m klimakontrol discover                  # cerca moduli sulla rete locale
python3 -m klimakontrol raw 1                     # JSON grezzo, per il debug
```

La regione (`ab`, `eu`, `ru`, `cn`) si scegle al primo avvio dell'app ufficiale e poi non
è più visibile: se non la ricordi, `login` le prova in ordine e ti dice quale ha funzionato.
Con `--region eu` la forzi.

La password non viene salvata. La sessione sì, in `~/.config/klimakontrol/session.json`:
il cloud blocca temporaneamente chi rifà il login troppo spesso (errore `-1036`).

Per forzare un trasporto: `--transport local` oppure `--transport cloud`.

## Come libreria

```python
from klimakontrol import CloudClient, LocalClient, Device

cloud = CloudClient("eu")
cloud.login("io@example.com", "…")
salotto = cloud.devices()[0]

cloud.set_state(salotto, {"pwr": 1, "temp": 230, "tcl_mode": 3})
print(cloud.get_state(salotto))

# in casa, senza passare da nessun cloud
locale = LocalClient(Device(host=salotto.lanaddr, mac=salotto.mac, key=salotto.aeskey))
print(locale.get_state())
```

## Struttura

```
klimakontrol/
  aes.py       AES-128-CBC in Python puro (nessuna dipendenza)
  local.py     protocollo UDP BroadLink/DNA: pacchetti, discovery, autenticazione LAN
  cloud.py     login, elenco dispositivi, controllo remoto, consumi
  params.py    dizionario dei 79 parametri, con etichette, unità ed enumerati
  tasks.py     pianificazioni e la conversione di fuso che l'app sbaglia
  session.py   persistenza e mascheramento dei dati sensibili
  cli.py       interfaccia a riga di comando
docs/
  protocol.md  le specifiche complete del protocollo
tests/         88 test, tutti offline
```

## Limiti attuali, dichiarati

1. **Non ancora provato su un impianto vero.** Ogni byte è verificato contro le specifiche e
   contro i pacchetti documentati (il payload di `set temp 23.0` esce identico, checksum
   incluso), ma la prova sul campo è il prossimo passo.
2. **Le pianificazioni si leggono e si modellano, ma non si scrivono ancora.** I comandi
   `dev_taskadd` / `dev_tasklist` passano dal livello nativo dell'SDK (`libNetworkAPI.so`),
   che costruisce il pacchetto usando i file `.script` cifrati dentro l'APK. Due strade per
   chiudere il buco: catturare un pacchetto UDP mentre l'app ufficiale crea un timer, oppure
   usare l'API cloud `/appfront/v1/timertask/*`. Il modello dati e la conversione di fuso
   sono già pronti.
3. **`if_function`** è la maschera di bit delle funzioni che il singolo modello supporta
   davvero. Il valore si legge; la corrispondenza bit → funzione va ancora ricavata.
4. **`devicetypeflag`** viene passato come 0 se il cloud non lo fornisce. Da confermare sul
   campo.

### Una nota sugli identificativi di regione

I progetti open source esistenti usano `8503b08fa57729df9faa45e4c978852c` come *company id*
della regione internazionale. Non lo è: quel valore compare identico in tutte e quattro le
licenze BroadLink dell'app, è una costante globale. Il company id vero della regione
internazionale è `a8452a8f48ae707edc12e9c52e21f00f`.

Con la coppia sbagliata il cloud risponde `-1008`, cioè **"credenziali errate" a credenziali
perfettamente corrette** — un messaggio che manda a caccia del problema nel posto sbagliato.
Per non ripetere l'errore, `cloud.py` non contiene identificativi ricopiati a mano: tiene i
blob di licenza estratti dall'APK e ne ricava licenseId e companyid a ogni avvio (primi 16
byte e successivi 16, in chiaro).

## Prossimi passi

- provare su un impianto reale e correggere quello che emerge
- app Android sopra questa libreria, compilata in CI
- chiudere la scrittura delle pianificazioni
- mappare i bit di `if_function`

## Nota legale

Interoperabilità con hardware di proprietà: in UE la decompilazione a questo scopo è
espressamente consentita (direttiva 2009/24/CE, art. 6). Questo progetto non ridistribuisce
codice altrui e non clona l'app ufficiale: parla con i climatizzatori del suo proprietario.

## Licenza

MIT.
