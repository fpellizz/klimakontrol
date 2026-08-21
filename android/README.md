# klimakontrol — app Android (scheletro)

App nativa **Kotlin + Jetpack Compose** che implementa il sistema di design **"Quadrante"**
(vedi il mockup e `docs/`), sopra la stessa logica cloud della libreria Python.

> Su questa macchina non c'è l'SDK Android: **si compila in CI** (GitHub Actions,
> `.github/workflows/android.yml`). Ad ogni push su `android/**` viene prodotto un APK debug,
> scaricabile dagli *artifact* della run (`klimakontrol-debug-apk`).

## Stato — collegato al cloud

L'app fa **login** e controlla i climatizzatori **veri** via cloud.

- ✅ Tema Quadrante (token chiaro/scuro, colore per modalità) — `ui/theme/`
- ✅ **Login** + sessione persistente (senza password, come `session.py`) — `ui/LoginScreen.kt`, `data/cloud/SessionStore.kt`
- ✅ **Home** (panoramica multi-split, stati acceso/spento/offline) — `ui/HomeScreen.kt`
- ✅ **Dettaglio** col **quadrante** (`Canvas`), modalità/ventola/funzioni, fascia pollice — `ui/DetailScreen.kt`, `ui/Dial.kt`
- ✅ **Client cloud** in Kotlin (companyid condiviso, `getallinfo`, `sdkcontrol` con `save_temp`)
  collegato via `CloudService` + `KlimaViewModel` — `data/cloud/`
- ✅ **Comandi ottimistici** con roll-back sull'errore (aggiorna subito, invia, torna indietro se fallisce)
- ✅ **Feedback per-comando** (`invio… / ✓ confermato / comando non riuscito`) + **debounce** sulla
  temperatura (un solo comando dopo 400 ms di quiete: niente comandi persi, cloud non intasato)
- ✅ **Font veri** — Space Grotesk (numeri/display) e Inter (UI) via *Downloadable Fonts* di Google
  (nessun binario nel repo; provider Play Services, certificati in `res/values/font_certs.xml`)
- ✅ **"Ricorda le credenziali"** — email+password cifrate nel Keystore (`EncryptedSharedPreferences`),
  auto-login quando la sessione scade; se disattivato, si salva solo la sessione (mai la password)

- ✅ **Ventola completa**: Auto + 5 livelli (bassa → alta) con selettore chiaro
- ✅ **Silenzioso** (`qtmode`) — il livello molto basso/silenzioso, verificato accettato dal modulo
- ✅ **Oscillazione** — swing **verticale** (`tcl_vdir`, on=7) e **orizzontale** (`tcl_hdir`, on=1),
  i due tasti SWING del telecomando
- ✅ **Salute** (`ac_health`, ionizzatore) e **Display** (`bglight`, display dell'unità) — i tasti
  HEALTH e DISPLAY del telecomando
- ⚠️ **Rilevamento capacità** — *tentato e accantonato*: il set che il modulo ritorna in lettura
  è un **sottoinsieme fisso**, non la lista di capacità. Prova: `qtmode` è accettato ma non
  riportato; nascondere in base al set riportato ha fatto sparire anche lo swing verticale che
  serve. La vera capacità è in `if_function` (maschera di bit, mappatura ancora ignota —
  `docs/open-questions.md` §4). Finché non è decodificata, si mostrano **tutti** i comandi.

Corrispondenza con il telecomando fisico: MODE→Modalità, FAN→Ventola, ECO→Eco, TURBO→Turbo,
SLEEP→Notte, MUTE→Silenzioso, SWING↕↔→Oscillazione, HEALTH→Salute, DISPLAY→Display.
Restano fuori: **TIMER** (le pianificazioni, feature a parte) e **I FEEL** (funzione del solo
telecomando, che invia la propria lettura di temperatura: nessun parametro cloud).

Il gestore (⚙ nell'header) per ora fa **logout** (provvisorio).

## Note

- **Temperatura ambiente**: indagata — questi moduli **non la espongono**. Il `get` ritorna sempre
  lo stesso set fisso (`pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_errcode`)
  senza `envtemp`, anche chiedendola; `querystate` dà solo lo stato online; e la stessa app ufficiale
  mostra l'ambiente solo se il modello ha il sensore. Quindi in UI l'ambiente si mostra solo se c'è.
- **Silenzioso** non viene riletto dal modulo (`qtmode` non è nel set fisso): resta stato locale
  fino al prossimo cambio.
- **Oscillazione/Salute/Display** si mostrano sempre (vedi sopra: il set riportato non è la lista
  di capacità). Se un comando non muove l'unità, o l'unità non ha quell'aletta, il comando parte
  ma non ha effetto — come lo swing verticale col valore `7`, ancora da confermare sul filo.
- **Diagnostica**: a ogni caricamento l'app logga in Logcat (tag `klima-caps`) l'intero set che il
  modulo ritorna, chiavi e valori. Per catturarlo: `adb logcat -s klima-caps`. Serve a vedere il
  set fisso reale, il valore di `if_function`, e (se presente) il valore di `tcl_vdir` per ricavare
  il comando giusto dello swing verticale.

## Prossimi passi

1. **Pianificazioni** (il tasto TIMER): manca la scrittura sul filo — vedi `docs/open-questions.md` §2.
2. Icona app, schermata impostazioni vera, `WindowSizeClass` per griglia su tablet/foldable.

## Struttura

```
android/
  app/src/main/kotlin/net/klimakontrol/
    MainActivity.kt, KlimaApp.kt
    ui/            HomeScreen, DetailScreen, Dial, KlimaViewModel, theme/
    data/          Model, KlimaRepository (Sample), cloud/CloudClient
```

## Compilare in locale (se hai l'SDK)

```bash
cd android
gradle wrapper --gradle-version 8.9   # genera ./gradlew la prima volta
./gradlew assembleDebug
# APK in app/build/outputs/apk/debug/
```
