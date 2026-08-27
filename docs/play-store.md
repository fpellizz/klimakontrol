# Pubblicazione su Google Play — guida e checklist

Stato: l'app ha già icona, versione (`0.6.0`/`versionCode 7`), privacy policy (`docs/privacy.html`)
e un controllo aggiornamenti (`UpdateChecker`, via GitHub Releases). Qui c'è tutto il resto,
**pronto da applicare**. Le modifiche a `build.gradle.kts`/CI richiedono un **keystore** (che generi
tu) e vanno applicate insieme, guardando la CI.

## Checklist (in ordine di priorità)

**Bloccanti per caricare su Play**
1. Alzare `compileSdk`/`targetSdk` a **35** (obbligo dal 31/08/2025) o **36** (dal 31/08/2026) — con
   bump AGP (8.6 per 35, **8.9.1** per 36) e Gradle (8.7 / **8.11.1**). È l'unica modifica che può
   rompere la build: farla da sola e verificare la CI.
2. `signingConfigs.release` + firma nel `release` (sotto); generare la **upload key** e
   `keystore.properties` (fuori dal repo, già in `.gitignore`).
3. Privacy policy a URL pubblico: `docs/privacy.html` + **Settings → Pages → branch `main`, `/docs`**
   → `https://fpellizz.github.io/klimakontrol/privacy.html` (sostituire il contatto PLACEHOLDER).
4. **Data Safety form** coerente con la privacy (vedi sotto).
5. Fornire **credenziali di test** in "App access" (l'app è dietro login cloud).

**Release pulita**
6. `isMinifyEnabled = true` + `isShrinkResources = true` + keep rules Tink (già in `proguard-rules.pro`);
   **testare un login reale** sulla release (R8 può rompere reflection non coperta).
7. Job CI `release` su tag → **AAB firmato** da secret base64.
8. Versioning da git tag (opzionale, vedi sotto).
9. Asset listing: icona 512×512, feature graphic 1024×500, ≥2 screenshot, testi.
10. Se l'account developer è nuovo: **closed testing 12 tester × 14 giorni** prima della produzione.

## build.gradle.kts — firma + R8 (da applicare col keystore)

Caricamento proprietà (niente import in cima: uso nomi completi per non rischiare la sintassi .kts):

```kotlin
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = java.util.Properties().apply {
    if (keystorePropsFile.exists()) java.io.FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)
```

Dentro `android { }`:

```kotlin
signingConfigs {
    create("release") {
        val storePath = signingValue("storeFile", "KEYSTORE_FILE")
        if (storePath != null) {
            storeFile = file(storePath)
            storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
        }
    }
}
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
    }
}
```

`keystore.properties` (creare, NON committare — già in `.gitignore`):

```properties
storeFile=klimakontrol-release.jks
storePassword=********
keyAlias=klimakontrol
keyPassword=********
```

## Versioning da git tag (opzionale)

`versionName` dal tag, `versionCode` monotòno derivato. Con fallback se non ci sono tag:

```kotlin
fun gitVersionName(): String = runCatching {
    providers.exec { commandLine("git", "describe", "--tags", "--abbrev=0") }
        .standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.2.0" }
}.getOrDefault("0.2.0")

fun gitVersionCode(): Int {
    val p = gitVersionName().split(".", limit = 3).map { it.toIntOrNull() ?: 0 }
    return p.getOrElse(0){0} * 10000 + p.getOrElse(1){0} * 100 + p.getOrElse(2){0}  // 0.2.0 -> 200
}
```

Poi in `defaultConfig`: `versionCode = gitVersionCode()` / `versionName = gitVersionName()`.
Flusso release: `git tag v0.3.0 && git push --tags`. (Se preferisci, tieni la versione manuale
come ora: è più semplice e non aggiunge rischi.)

## CI — job release su tag → AAB firmato

Secrets nel repo: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Creare il primo con `base64 -w0 klimakontrol-release.jks | gh secret set KEYSTORE_BASE64`.

```yaml
  release:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: android } }
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }        # per git describe
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.11.1' }   # 8.9 se resti su compileSdk 35
      - name: Decodifica keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 --decode > "$RUNNER_TEMP/release.jks"
      - name: Build AAB firmato
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: gradle bundleRelease --no-daemon --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: klimakontrol-release-aab
          path: android/app/build/outputs/bundle/release/*.aab
          if-no-files-found: error
      - name: Pulisci il keystore
        if: always()
        run: rm -f "$RUNNER_TEMP/release.jks"
```

## Data Safety form (Play Console)

- Raccolta di **Email address** e **Password** (Personal info / App activity).
- Finalità: **App functionality / Account management**.
- **Non condivisi** con terze parti (il cloud BroadLink è il provider di funzionamento, non un
  terzo pubblicitario).
- **Cifrati in transito** (HTTPS) e **a riposo** (Android Keystore).
- **Cancellabili** dall'utente (logout / "Dimentica credenziali"; account cloud lato produttore).
- Permessi: solo `INTERNET`, `ACCESS_NETWORK_STATE` (nessun permesso sensibile → niente
  Permissions Declaration Form).

## Asset del listing

- Icona **512×512 PNG** (32-bit con alpha), feature graphic **1024×500**, **≥2 screenshot**
  telefono (min 320px lato corto).
- Titolo ≤ 30 char, breve descrizione ≤ 80, completa ≤ 4000. Categoria: *Tools* o *House & Home*.
- Content rating: questionario IARC (utility → "Everyone").
- Formato di pubblicazione: **AAB** (non APK). Consigliato **Play App Signing**.

## Nota legale (segnalazione, non parere)

- Interoperabilità con hardware di proprietà (direttiva UE 2009/24/CE art. 6). Non ridistribuisce
  codice altrui.
- **Nessun marchio altrui** in nome/package (`net.klimakontrol`)/icona/listing (no BroadLink, TCL,
  Wisnow, "Intelligent AC"). Descrivere la compatibilità con formule neutre.
- **Client di terze parti**: Play può rimuovere app che accedono a un servizio senza autorizzazione
  del titolare. Rischio concreto per un client non ufficiale del cloud BroadLink. Mitigazione:
  chiarire natura indipendente e uso su hardware proprio; in alternativa/complemento, distribuire
  via **GitHub Releases** (che è anche il canale del controllo aggiornamenti in-app).

## Controllo aggiornamenti (già implementato)

`UpdateChecker` confronta `BuildConfig.VERSION_NAME` con l'ultima **GitHub Release**
(`api.github.com/repos/fpellizz/klimakontrol/releases/latest`, campo `tag_name`). Perché funzioni
servono delle **Release** su GitHub: crea un tag `vX.Y.Z` e una Release con l'APK allegato (a mano
o via CI). Più avanti, per la variante Play, si può passare alla **Play In-App Updates API**
(introduce però una dipendenza runtime Play Core) tenendo `UpdateChecker` per le build sideload.
