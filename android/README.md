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

Il gestore (⚙ nell'header) per ora fa **logout** (provvisorio).

## Prossimi passi

1. Lettura ambiente/temperatura reale (il get ritorna un set fisso senza `envtemp`: valutare `querystate`).
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
