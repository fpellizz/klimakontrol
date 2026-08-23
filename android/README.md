# klimakontrol — app Android (scheletro)

App nativa **Kotlin + Jetpack Compose** che implementa il sistema di design **"Quadrante"**
(vedi il mockup e `docs/`), sopra la stessa logica cloud della libreria Python.

> Su questa macchina non c'è l'SDK Android: **si compila in CI** (GitHub Actions,
> `.github/workflows/android.yml`). Ad ogni push su `android/**` viene prodotto un APK debug,
> scaricabile dagli *artifact* della run (`klimakontrol-debug-apk`).

## Stato — collegato al cloud

L'app fa **login** e controlla i climatizzatori **veri** via cloud.

- ✅ Tema Quadrante (token chiaro/scuro, colore per modalità) — `ui/theme/`
- ✅ **Rifinitura grafica**: quadrante **animato** (l'arco scivola con una molla), **micro-pressione**
  coerente su tutte le superfici toccabili (`ui/Pressable.kt`, senza ripple), transizioni schermata
  fade + micro-scala
- ✅ **Login** + **selettore regione/vendor** (eu/ab/cn/ru, da `REGIONS`) + **mostra password** +
  sessione persistente (senza password, come `session.py`) — `ui/LoginScreen.kt`, `data/cloud/SessionStore.kt`.
  Il campo password con toggle Mostra/Nascondi (`PasswordField`) è condiviso da login/registrazione/impostazioni
- ✅ **Registrazione nuovo account** (due passi: invio codice email/SMS → codice+password) —
  `ui/RegisterScreen.kt`, `CloudClient.sendRegisterCode()/register()` (multipart). La `register` è
  anche login: al successo la sessione è già pronta. Ricostruita dal dex, come la libreria Python
- ✅ **Home** (panoramica multi-split, stati acceso/spento/offline) — `ui/HomeScreen.kt`
- ✅ **Case (locali)**: gruppi definiti dall'utente, **non** letti dal cloud. Crea/rinomina/elimina
  case e assegna i dispositivi in **Impostazioni → Gestisci case** (`ui/HomesScreen.kt`), poi in
  Home **filtri** con i chip per casa. Le azioni "Rinfresca/Spegni tutte" agiscono sulla casa vista.
  **Export/Import** della configurazione (JSON, via condivisione). Persistenza in `data/homes/HomesStore.kt`
- ✅ **Dettaglio** col **quadrante** (`Canvas`) **trascinabile** (slider circolare: tocca/trascina
  l'anello per impostare la temperatura, oltre ai pulsanti +/−), modalità/ventola/funzioni,
  fascia pollice — `ui/DetailScreen.kt`, `ui/Dial.kt`
- ✅ **Client cloud** in Kotlin (companyid condiviso, `getallinfo`, `sdkcontrol` con `save_temp`)
  collegato via `CloudService` + `KlimaViewModel` — `data/cloud/`
- ✅ **Comandi ottimistici** con roll-back sull'errore (aggiorna subito, invia, torna indietro se fallisce)
- ✅ **Feedback per-comando** (`invio… / ✓ confermato / comando non riuscito`) + **debounce** sulla
  temperatura (un solo comando dopo 400 ms di quiete: niente comandi persi, cloud non intasato)
- ✅ **Font veri** — Space Grotesk (numeri/display) e Inter (UI) via *Downloadable Fonts* di Google
  (nessun binario nel repo; provider Play Services, certificati in `res/values/font_certs.xml`)
- ✅ **"Ricorda le credenziali"** — email+password cifrate nel Keystore (`EncryptedSharedPreferences`),
  auto-login quando la sessione scade; se disattivato, si salva solo la sessione (mai la password)

- ✅ **Ventola completa**: Auto + **slider a scatti** (bassa → alta), trascinabile
- ⚠️ **Stato in tempo reale**: rilettura periodica (10s in foreground) + al ritorno dell'app —
  `KlimaViewModel.startPolling()`, `MainActivity.LifecycleBridge`. **In verifica**: se il modulo non
  riflette i cambi fatti col telecomando IR (come per il controllo locale `-5`), non c'è polling che
  tenga. Diagnostica: `adb logcat -s klima-poll` mostra ogni tick e `cambiate=N` (0 = il modulo non
  vede il cambio)
- ❌ **Bip del climatizzatore**: *tolto*. Il modulo **ignora** il parametro `beep` (non è nel set
  gestito, come mute/salute/display): l'unità suona a ogni comando ricevuto e **non si può zittire
  dall'app**. La feature causava solo beep in più (un push per unità): rimossa.
- 🖼️ **Branding produttore** (Impostazioni → Hardware): inserendo il **codice costruttore** (es.
  `WISNOW`) l'app scarica il **logo** dal cloud del produttore (`/neutralapp/companyinfo?code=…`) e
  lo mostra. Nessun asset impacchettato: arriva a runtime dal *loro* server (interop) —
  `data/branding/VendorBranding.kt`
- ✅ **Oscillazione** — swing **verticale** (`ac_vdir`) e **orizzontale** (`ac_hdir`), i due tasti
  SWING del telecomando. Il modulo riporta lo swing con questi nomi `ac_*` (NON `tcl_vdir`/`tcl_hdir`
  come nell'estrazione APK): visto sul filo il 2026-08-22
- ✅ **Icona app** (`res/mipmap-*`, adattiva: sfondo blu + motivo caldo/freddo nella safe zone)

**Set fisso reale del modulo (0x4e2e), identico su tutte le unità** — è la sua lista di funzioni:
`pwr · tcl_mode · save_temp · tcl_mark · ecomode · pwfmode · tcl_slp · ac_vdir · ac_hdir · ac_errcode`.
L'app espone esattamente questi. **Silenzioso** (`qtmode`), **Salute** (`ac_health`) e **Display**
(`bglight`) sono stati **tolti**: non compaiono nel set → il modulo non li gestisce (sono funzioni
solo-telecomando IR).

Corrispondenza con il telecomando fisico: MODE→Modalità, FAN→Ventola, ECO→Eco, TURBO→Turbo,
SLEEP→Notte, SWING↕↔→Oscillazione. Non pilotabili via cloud (assenti dal set): **MUTE**, **HEALTH**,
**DISPLAY**, **I FEEL**, e **TIMER** (le pianificazioni, feature a parte).

- ✅ **Impostazioni** (⚙ nell'header) — profilo (email), **cambia nome** (`modifynickname`) e
  **cambia password** (`modifypwd`, ricostruiti dal dex), versione app, **controllo aggiornamenti**
  (confronto con l'ultima GitHub Release), logout e "dimentica credenziali" — `ui/SettingsScreen.kt`
- ✅ **Rinfresca casa** / **Spegni tutte** — barra azioni rapide in Home
- ✅ **Controllo aggiornamenti** — `data/update/UpdateChecker.kt` (GitHub Releases API, `BuildConfig.VERSION_NAME`);
  banner in Home se c'è una versione più recente

Fuso orario: **nessuna impostazione server** (i moduli sono fissi a UTC+8, la conversione è locale);
in Impostazioni c'è solo la nota informativa.

## Note

- **Temperatura ambiente**: questi moduli **non la espongono** (`envtemp` non è nel set fisso). In
  UI si mostra solo se disponibile.
- **Valore "on" dello swing**: verticale=7, orizzontale=1 (derivati dal sorgente dell'app). Il nome
  del parametro (`ac_vdir`/`ac_hdir`) è confermato dal filo; il valore no. Se un asse non oscilla,
  il log dice il valore giusto.
- **Diagnostica**: a ogni caricamento l'app logga in Logcat (tag `klima-caps`) l'intero set che il
  modulo ritorna, chiavi e valori: `adb logcat -s klima-caps`.

## Prossimi passi

1. **Pianificazioni** (il tasto TIMER): manca la scrittura sul filo — vedi `docs/open-questions.md` §2.
2. **Pubblicazione Play Store**: build release firmata (AAB), R8, versioning da tag, privacy policy — vedi `docs/play-store.md`.
3. `WindowSizeClass` per griglia su tablet/foldable; abbinamento dispositivi in-app.

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
