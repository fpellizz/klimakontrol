# klimakontrol — app Android (scheletro)

App nativa **Kotlin + Jetpack Compose** che implementa il sistema di design **"Quadrante"**
(vedi il mockup e `docs/`), sopra la stessa logica cloud della libreria Python.

> Su questa macchina non c'è l'SDK Android: **si compila in CI** (GitHub Actions,
> `.github/workflows/android.yml`). Ad ogni push su `android/**` viene prodotto un APK debug,
> scaricabile dagli *artifact* della run (`klimakontrol-debug-apk`).

## Stato — è uno scheletro

Funziona e si vede il layout, ma su **dati d'esempio** (`SampleRepository`): nessun login richiesto.

- ✅ Tema Quadrante (token chiaro/scuro, colore per modalità) — `ui/theme/`
- ✅ Schermata **Home** (panoramica multi-split, stati acceso/spento/offline) — `ui/HomeScreen.kt`
- ✅ Schermata **Dettaglio** con il **quadrante** (`Canvas`), modalità/ventola/funzioni, fascia pollice — `ui/DetailScreen.kt`, `ui/Dial.kt`
- ✅ **Client cloud** portato in Kotlin (login col companyid condiviso, `getallinfo`, `sdkcontrol`
  con `save_temp`) — `data/cloud/CloudClient.kt`. **Non ancora collegato alla UI.**

## Prossimi passi

1. `CloudRepository` che implementa `KlimaRepository` sopra `CloudClient` (thread IO), con schermata
   di **login** e memorizzazione sessione (0600, senza password — come `session.py`).
2. Stato ottimistico + debounce + roll-back sull'errore (il punto in cui battiamo l'app ufficiale).
3. Font veri: **Space Grotesk** (numeri) e **Inter** (UI) in `res/font/` al posto di `FontFamily.Default`.
4. Icona app, edge-to-edge rifinito, `WindowSizeClass` per griglia su tablet/foldable.

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
