# Publishing on Google Play — guide and checklist

Status: the app already has an icon, version (`0.7.0`/`versionCode 8`), privacy policy (`docs/privacy.html`)
and an update check (`UpdateChecker`, via GitHub Releases). Here is everything else,
**ready to apply**. The changes to `build.gradle.kts`/CI require a **keystore** (that you generate)
and must be applied together, watching the CI.

## Checklist (in order of priority)

**Blockers for uploading to Play**
1. Raise `compileSdk`/`targetSdk` to **35** (mandatory from 31/08/2025) or **36** (from 31/08/2026) — with
   an AGP bump (8.6 for 35, **8.9.1** for 36) and Gradle (8.7 / **8.11.1**). It is the only change that can
   break the build: do it on its own and verify the CI.
2. `signingConfigs.release` + signing in `release` (below); generate the **upload key** and
   `keystore.properties` (outside the repo, already in `.gitignore`).
3. Privacy policy at a public URL: `docs/privacy.html` + **Settings → Pages → branch `main`, `/docs`**
   → `https://fpellizz.github.io/klimakontrol/privacy.html` (replace the PLACEHOLDER contact).
4. **Data Safety form** consistent with the privacy policy (see below).
5. Provide **test credentials** in "App access" (the app is behind a cloud login).

**Clean release**
6. `isMinifyEnabled = true` + `isShrinkResources = true` + Tink keep rules (already in `proguard-rules.pro`);
   **test a real login** on the release (R8 can break uncovered reflection).
7. CI `release` job on tag → **signed AAB** from a base64 secret.
8. Versioning from git tag (optional, see below).
9. Listing assets: 512×512 icon, 1024×500 feature graphic, ≥2 screenshots, texts.
10. If the developer account is new: **closed testing 12 testers × 14 days** before production.

## build.gradle.kts — signing + R8 (to apply with the keystore)

Loading properties (no imports at the top: I use fully-qualified names to not risk the .kts syntax):

```kotlin
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = java.util.Properties().apply {
    if (keystorePropsFile.exists()) java.io.FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)
```

Inside `android { }`:

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

`keystore.properties` (create it, do NOT commit — already in `.gitignore`):

```properties
storeFile=klimakontrol-release.jks
storePassword=********
keyAlias=klimakontrol
keyPassword=********
```

## Versioning from git tag (optional)

`versionName` from the tag, a monotonic derived `versionCode`. With a fallback if there are no tags:

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

Then in `defaultConfig`: `versionCode = gitVersionCode()` / `versionName = gitVersionName()`.
Release flow: `git tag v0.3.0 && git push --tags`. (If you prefer, keep the version manual
as now: it is simpler and adds no risks.)

## CI — release job on tag → signed AAB

Secrets in the repo: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Create the first with `base64 -w0 klimakontrol-release.jks | gh secret set KEYSTORE_BASE64`.

```yaml
  release:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: android } }
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }        # for git describe
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.11.1' }   # 8.9 if you stay on compileSdk 35
      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 --decode > "$RUNNER_TEMP/release.jks"
      - name: Build signed AAB
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
      - name: Clean up the keystore
        if: always()
        run: rm -f "$RUNNER_TEMP/release.jks"
```

## Data Safety form (Play Console)

- Collection of **Email address** and **Password** (Personal info / App activity).
- Purpose: **App functionality / Account management**.
- **Not shared** with third parties (the BroadLink cloud is the operating provider, not an
  advertising third party).
- **Encrypted in transit** (HTTPS) and **at rest** (Android Keystore).
- **Deletable** by the user (logout / "Forget credentials"; cloud account on the manufacturer side).
- Permissions: only `INTERNET`, `ACCESS_NETWORK_STATE` (no sensitive permission → no
  Permissions Declaration Form).

## Listing assets

- **512×512 PNG** icon (32-bit with alpha), **1024×500** feature graphic, **≥2 screenshots**
  phone (min 320px short side).
- Title ≤ 30 chars, short description ≤ 80, full ≤ 4000. Category: *Tools* or *House & Home*.
- Content rating: IARC questionnaire (utility → "Everyone").
- Publishing format: **AAB** (not APK). **Play App Signing** recommended.

## Legal note (a heads-up, not advice)

- Interoperability with owned hardware (EU directive 2009/24/CE art. 6). It does not redistribute
  anyone else's code.
- **No third-party trademark** in name/package (`net.klimakontrol`)/icon/listing (no BroadLink, TCL,
  Wisnow, "Intelligent AC"). Describe compatibility with neutral wording.
- **Third-party client**: Play can remove apps that access a service without the owner's
  authorization. A concrete risk for an unofficial client of the BroadLink cloud. Mitigation:
  clarify the independent nature and use on your own hardware; alternatively/additionally, distribute
  via **GitHub Releases** (which is also the channel of the in-app update check).

## Update check (already implemented)

`UpdateChecker` compares `BuildConfig.VERSION_NAME` with the latest **GitHub Release**
(`api.github.com/repos/fpellizz/klimakontrol/releases/latest`, `tag_name` field). For it to work
you need **Releases** on GitHub: create a `vX.Y.Z` tag and a Release with the APK attached (by hand
or via CI). Later, for the Play variant, you can switch to the **Play In-App Updates API**
(it introduces, however, a Play Core runtime dependency) keeping `UpdateChecker` for sideload builds.
