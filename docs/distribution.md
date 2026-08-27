# Distribution outside Google Play

The app is open source and lives on GitHub, and it already has an **in-app update check** that
reads the project's GitHub Releases. That makes GitHub Releases a working private "store" on its
own; the alternatives below build on it. The Play Store path (AAB + Play App Signing) is separate
and documented in [`play-store.md`](play-store.md).

## Prerequisite: a release signing key

Every option except official F-Droid needs the APK signed with a **release key**. You do **not**
need the Play Store keystore for this — generate a fresh one on whatever machine you build from:

```bash
keytool -genkey -v -keystore klimakontrol.jks -alias klimakontrol \
        -keyalg RSA -keysize 2048 -validity 10000
```

- **Back it up** in more than one place. If you lose it you can no longer ship updates: users would
  have to uninstall and reinstall.
- This key is independent of any future Play key. That is fine right now because the app is not
  published anywhere yet, so there are no existing installs tied to another key. (If you later also
  publish on Play with Play App Signing, a sideloaded build and a Play build are signed by different
  keys and cannot update over each other — a minor point until then.)
- The CI currently ships a **debug**-signed APK. When you switch to a release-signed one, if you
  already have the debug build on your phone you must **uninstall it first** (different signature).

## Option 1 — GitHub Releases + in-app updater (+ Obtainium) — recommended

Almost in place. The only change is making CI produce a **release-signed** APK instead of the debug
one, then attaching it to the release (the `attach-apk` job already uploads the APK).

1. Put the keystore and its passwords in **GitHub Secrets** (e.g. the keystore base64-encoded, plus
   store/key passwords and alias).
2. Add a `signingConfig` in `android/app/build.gradle.kts` that reads those from the environment,
   and build `assembleRelease` in the workflow.
3. Attach `klimakontrol-<tag>.apk` to the release as today.

Users then update automatically through the app's built-in update check. For a store-like
experience with no extra work on your side, point users at **Obtainium**
(<https://github.com/ImranR98/Obtainium>): they paste the repo URL and get automatic updates
straight from the GitHub Releases.

## Option 2 — IzzyOnDroid — the practical "F-Droid" for this app

IzzyOnDroid (<https://apt.izzysoft.de/fdroid/>) is a third-party repository compatible with the
F-Droid client. It **pulls the signed APK from your GitHub Releases** — no build-from-source
requirement — and is lenient about non-free bits, labelling them with *anti-features* instead of
rejecting. Users add the IzzyOnDroid repo to their F-Droid client and get automatic updates.

You need:
- a release-signed APK on GitHub Releases (Option 1);
- **fastlane metadata** in the repo (see below);
- a one-time request to add the app (see their inclusion instructions).

## Option 3 — official F-Droid (f-droid.org)

The widest reach and the strongest trust, but the strictest. F-Droid **builds from source and signs
with its own key**, so you do not even provide a keystore. In return:

- **No proprietary dependencies.** This used to be a blocker because the fonts were fetched through
  Google Play Services (`com.google.android.gms.fonts`). That is now fixed: the fonts
  (Space Grotesk, Inter, Outfit — all OFL) are **bundled** in `res/font/`, so there is no GMS
  dependency and they render on de-Googled devices too.
- The app still talks to the **BroadLink cloud**, so it gets the **`NonFreeNet`** anti-feature tag.
  That is allowed — it is a label, not a rejection.
- The build must be **reproducible**, and inclusion goes through a metadata pull request to
  `fdroiddata` with a review that can take weeks.

Worth doing as a later milestone; IzzyOnDroid gets you a working F-Droid-client channel much sooner.

## Others, briefly

- **Accrescent** (<https://accrescent.app/>): modern, security-focused store; currently curated and
  small. Nice to add later.
- **APKPure / APKMirror**: ad-driven mirror sites, not aligned with a FOSS project — skip.
- **Aurora Store** is a *client* for downloading from Play, not a place you publish to — not a
  target.

## Fastlane metadata (shared by IzzyOnDroid and F-Droid)

Both read app metadata from a fastlane layout in the repo:

```
fastlane/metadata/android/en-US/
  title.txt
  short_description.txt
  full_description.txt
  changelogs/<versionCode>.txt      # e.g. 7.txt
  images/icon.png
  images/phoneScreenshots/1.png ...
fastlane/metadata/android/it-IT/     # optional, same structure
```

## Notes

- **versionCode must increase** on every release (already handled in `build.gradle.kts`).
- **Fonts**: bundled as static instances of the official variable fonts (OFL); the license texts
  ship in the app at `assets/fonts/OFL-*.txt`. If a weight is ever added in `theme/Type.kt`, add the
  matching `res/font/*.ttf` instance.
- **Play Store**: for the AAB + Play App Signing path, see [`play-store.md`](play-store.md).
