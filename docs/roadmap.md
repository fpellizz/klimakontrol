# Roadmap

In ordine. Ogni passo dipende dal precedente, e ognuno chiude un rischio invece di aggiungere
funzioni sopra un fondamento non verificato.

---

## Passo 1 — Sbloccare il login (bloccante)

Estrarre i due sali nativi e provarli: `docs/open-questions.md` §1.

**Fatto quando** `python3 -m klimakontrol login` restituisce una sessione e l'elenco delle unità.

Poi: aggiornare `DEFAULT_SALTS` in `cloud.py` con i valori veri, scrivendo nel commento da dove
vengono; togliere il "da verificare"; aggiornare la trappola 2 in `CLAUDE.md`.

---

## Passo 2 — Prima prova sul campo

```bash
python3 -m klimakontrol list
python3 -m klimakontrol status 1
python3 -m klimakontrol raw 1              # salvare l'output in docs/samples/
python3 -m klimakontrol on 1
python3 -m klimakontrol set 1 temp=23 tcl_mode=freddo
python3 -m klimakontrol off 1
python3 -m klimakontrol online
python3 -m klimakontrol energy 1 day
```

Cosa può rompersi, in ordine di probabilità: la forma delle risposte (§5), `devicetypeflag`
(§3), i nomi dei campi nello storico consumi.

**Fatto quando** un climatizzatore si accende da un comando dato fuori casa.

Poi: aggiornare la tabella di stato in `CLAUDE.md`, aggiungere test sulle risposte reali
(mascherate) come fixture.

---

## Passo 3 — La via locale

```bash
python3 -m klimakontrol discover
python3 -m klimakontrol --transport local status 1
```

Due cose da verificare: che il discovery trovi i moduli, e che l'**autenticazione LAN**
restituisca la chiave AES anche senza account cloud — è quella che rende possibile un
funzionamento del tutto indipendente da BroadLink.

Se il discovery non risponde, provare comunque il controllo diretto verso l'IP noto: sono due
percorsi separati e il secondo è più semplice.

**Fatto quando** un comando in LAN risponde in meno di 300 ms e `--transport auto` sceglie da
solo la strada giusta.

---

## Passo 4 — Pianificazioni scrivibili

`docs/open-questions.md` §2. Serve una cattura UDP mentre l'app ufficiale crea un timer.

**Fatto quando** un programma settimanale scritto da qui viene eseguito dal modulo con il
telefono spento.

È la funzione che l'app ufficiale sbaglia più vistosamente: farla bene è il motivo per cui
questo progetto esiste.

---

## Passo 5 — App Android

Sopra la libreria, non al posto suo. Vincoli noti:

* sulla macchina di sviluppo non c'è l'SDK Android → **compilare in CI** (GitHub Actions),
  l'APK si scarica dagli artifact e si installa a mano;
* il controllo remoto passa dal cloud, quello locale via UDP: in app si tiene lo stesso
  trasporto automatico della CLI;
* schermata unica per il multi-split, con `querystate` che dà tutte le unità in una chiamata;
* mostrare solo i comandi che il modello supporta (`if_function`, §4).

Nota sulla riscrittura in Kotlin: il protocollo è tutto in `klimakontrol/`, e la logica delicata
(checksum, conversione di fuso, direttive) è coperta dai test. Portare quei test insieme al
codice, non dopo.

---

## Idee, dopo che tutto il resto funziona

* Integrazione Home Assistant (esiste già `RazvanManolache/home-assistant-tcl-intelligent-ac-local`
  per la parte locale: si può contribuire lì invece di duplicare).
* Storico su file locale, per avere serie temporali indipendenti dal cloud.
* Aprire i file `.script` degli assets: darebbero i limiti e le capacità per modello.
* Diagnostica: temperature di batteria e mandata, frequenza compressore e corrente sono già
  leggibili. Con qualche settimana di storico si vedono i cali di resa prima che diventino guasti.
