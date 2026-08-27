# klimakontrol — Android app (skeleton)

Native **Kotlin + Jetpack Compose** app that implements the **"Quadrante"** design system
(see the mockup and `docs/`), on top of the same cloud logic as the Python library.

> This machine does not have the Android SDK: **it compiles in CI** (GitHub Actions,
> `.github/workflows/android.yml`). On every push to `android/**` a debug APK is produced,
> downloadable from the run's *artifacts* (`klimakontrol-debug-apk`).

## Status — connected to the cloud

The app performs **login** and controls the **real** air conditioners via cloud.

- ✅ Quadrante theme (light/dark tokens, per-mode color) — `ui/theme/`
- ✅ **Graphic polish**: **animated** dial (the arc glides with a spring), **micro-press**
  consistent across all touchable surfaces (`ui/Pressable.kt`, without ripple), screen transitions
  fade + micro-scale
- ✅ **Login** + **region/vendor selector** (eu/ab/cn/ru, from `REGIONS`) + **show password** +
  persistent session (without password, like `session.py`) — `ui/LoginScreen.kt`, `data/cloud/SessionStore.kt`.
  The password field with a Show/Hide toggle (`PasswordField`) is shared by login/registration/settings
- ✅ **New account registration** (two steps: send email/SMS code → code+password) —
  `ui/RegisterScreen.kt`, `CloudClient.sendRegisterCode()/register()` (multipart). `register` is
  also login: on success the session is already ready. Reconstructed from the dex, like the Python library
- ✅ **Home** (multi-split overview, on/off/offline states) — `ui/HomeScreen.kt`
- ✅ **Homes (local)**: user-defined groups, **not** read from the cloud. Create/rename/delete
  homes and assign devices in **Settings → Manage homes** (`ui/HomesScreen.kt`), then in
  Home you **filter** with the per-home chips. The "Refresh/Turn all off" actions act on the viewed home.
  **Export/Import** of the configuration (JSON, via sharing). Persistence in `data/homes/HomesStore.kt`
- ✅ **Detail** with the **draggable** **dial** (`Canvas`) (circular slider: touch/drag
  the ring to set the temperature, in addition to the +/− buttons), mode/fan/functions,
  thumb strip — `ui/DetailScreen.kt`, `ui/Dial.kt`
- ✅ **Cloud client** in Kotlin (shared companyid, `getallinfo`, `sdkcontrol` with `save_temp`)
  wired via `CloudService` + `KlimaViewModel` — `data/cloud/`
- ✅ **Optimistic commands** with roll-back on error (updates immediately, sends, rolls back if it fails)
- ✅ **Per-command feedback** (`invio… / ✓ confermato / comando non riuscito`) + **debounce** on the
  temperature (a single command after 400 ms of quiet: no lost commands, cloud not congested)
- ✅ **Real fonts** — Space Grotesk (numbers/display) and Inter (UI) via Google's *Downloadable Fonts*
  (no binary in the repo; Play Services provider, certificates in `res/values/font_certs.xml`)
- ✅ **"Remember credentials"** — email+password encrypted in the Keystore (`EncryptedSharedPreferences`),
  auto-login when the session expires; if disabled, only the session is saved (never the password)

- ✅ **Complete fan**: Auto + **stepped slider** (low → high), draggable
- ⚠️ **Real-time status**: periodic re-read (10s in foreground) + on the app's return —
  `KlimaViewModel.startPolling()`, `MainActivity.LifecycleBridge`. **Under verification**: if the module does not
  reflect the changes made with the IR remote (as for local control `-5`), no polling can
  help. Diagnostics: `adb logcat -s klima-poll` shows every tick and `cambiate=N` (0 = the module does not
  see the change)
- ❌ **Air conditioner beep**: *removed*. The module **ignores** the `beep` parameter (it is not in the
  managed set, like mute/health/display): the unit beeps at every command received and **cannot be silenced
  from the app**. The feature only caused extra beeps (one push per unit): removed.
- 🖼️ **Manufacturer branding** (Settings → Hardware): by entering the **manufacturer code** (e.g.
  `WISNOW`) the app downloads the **logo** from the manufacturer's cloud (`/neutralapp/companyinfo?code=…`) and
  displays it. No packaged asset: it arrives at runtime from *their* server (interop) —
  `data/branding/VendorBranding.kt`
- ✅ **Oscillation** — **vertical** swing (`ac_vdir`) and **horizontal** (`ac_hdir`), the two
  SWING keys of the remote. The module reports the swing with these `ac_*` names (NOT `tcl_vdir`/`tcl_hdir`
  as in the APK extraction): seen on the wire on 2026-08-22
- ✅ **App icon** (`res/mipmap-*`, adaptive: blue background + warm/cold motif in the safe zone)

**Real fixed set of the module (0x4e2e), identical on all units** — it is its list of functions:
`pwr · tcl_mode · save_temp · tcl_mark · ecomode · pwfmode · tcl_slp · ac_vdir · ac_hdir · ac_errcode`.
The app exposes exactly these. **Silent** (`qtmode`), **Health** (`ac_health`) and **Display**
(`bglight`) have been **removed**: they do not appear in the set → the module does not manage them (they are
IR-remote-only functions).

Correspondence with the physical remote: MODE→Mode, FAN→Fan, ECO→Eco, TURBO→Turbo,
SLEEP→Night, SWING↕↔→Oscillation. Not drivable via cloud (absent from the set): **MUTE**, **HEALTH**,
**DISPLAY**, **I FEEL**, and **TIMER** (the schedules, a separate feature).

- ✅ **Settings** (⚙ in the header) — profile (email), **change name** (`modifynickname`) and
  **change password** (`modifypwd`, reconstructed from the dex), app version, **update check**
  (comparison with the latest GitHub Release), logout and "forget credentials" — `ui/SettingsScreen.kt`
- ✅ **Refresh home** / **Turn all off** — quick actions bar in Home
- ✅ **Update check** — `data/update/UpdateChecker.kt` (GitHub Releases API, `BuildConfig.VERSION_NAME`);
  banner in Home if there is a newer version

Timezone: **no server setting** (the modules are fixed at UTC+8, the conversion is local);
in Settings there is only the informational note.

## Notes

- **Ambient temperature**: these modules **do not expose it** (`envtemp` is not in the fixed set). In
  the UI it is shown only if available.
- **Swing "on" value**: vertical=7, horizontal=1 (derived from the app's source). The parameter
  name (`ac_vdir`/`ac_hdir`) is confirmed from the wire; the value is not. If an axis does not oscillate,
  the log says the right value.
- **Diagnostics**: on every load the app logs to Logcat (tag `klima-caps`) the entire set that the
  module returns, keys and values: `adb logcat -s klima-caps`.

## Next steps

1. **Schedules** (the TIMER button): writing on the wire is missing — see `docs/open-questions.md` §2.
2. **Play Store publication**: signed release build (AAB), R8, versioning from tag, privacy policy — see `docs/play-store.md`.
3. `WindowSizeClass` for a grid on tablet/foldable; in-app device pairing.

## Structure

```
android/
  app/src/main/kotlin/net/klimakontrol/
    MainActivity.kt, KlimaApp.kt
    ui/            HomeScreen, DetailScreen, Dial, KlimaViewModel, theme/
    data/          Model, KlimaRepository (Sample), cloud/CloudClient
```

## Building locally (if you have the SDK)

```bash
cd android
gradle wrapper --gradle-version 8.9   # generates ./gradlew the first time
./gradlew assembleDebug
# APK is in app/build/outputs/apk/debug/
```
