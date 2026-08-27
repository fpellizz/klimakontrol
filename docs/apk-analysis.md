# Analyzing the APK: the complete procedure

How to redo from scratch the analysis that produced `docs/protocol.md`. Useful if a new version
of the app comes out, if a piece of the protocol changes, or to verify that there is nothing
made up here.

The analysis material (APK, dex, .so, assets) **must not be committed**: `.gitignore` excludes them.
Work in a local `apk/` folder.

---

## 0. Obtaining the APK

The app is installed as a *split APK*: the base package contains code and assets, the native
libraries are in a separate package per architecture. Both are needed.

With the phone connected and USB debugging enabled:

```bash
mkdir -p apk && cd apk
adb shell pm path com.ab.smartDevice
adb shell pm path com.ab.smartDevice | sed 's/^package://' | tr -d '\r' | \
  while read -r p; do adb pull "$p" . ; done
```

Without a PC: an APK extractor on the phone (App Manager, APK Extractor) exporting **also** the
splits.

What is inside:

```bash
unzip -l base.apk | grep -E 'assets/|classes.*dex' | head -30
for a in *.apk; do echo "== $a"; unzip -l "$a" | grep '\.so$'; done
```

---

## 1. The assets: the source code of the panels

This is the goldmine. The app is a Cordova WebView; each air conditioner family has its
React panel in `assets/default*.zip`, and **the source maps are included**.

```bash
unzip -o base.apk 'assets/*' -d .
python3 ../tools/dump_sourcemaps.py assets/default.zip reconstructed/
```

To look at, in order:

| File | What it gives |
| --- | --- |
| `src/panel/data.js` | the parameter dictionary, with titles and types |
| `src/panel/data.js` (`mainModeControlButtons`, `mainFanControlButtons`) | the mode and fan values |
| `~/broadlink-jssdk/dna/adapter.js` | the bridge to the native layer, the commands, the timeouts |
| `~/broadlink-jssdk/dna/time-zone.js` | the UTC+8 conversion |
| `src/panel/Electricity.js` | the energy usage API (`dataservice`, the report names) |
| `src/panel/Timer.js`, `src/components/dna/SDKTimer/` | the schedule interface |
| `src/panel/More.js`, `SaveEnergy.js` | secondary functions and state history |

`pid` → bundle mapping: read `desc.json` inside each zip. The adjacent `.script` files (one per
`pid`) are the model definitions used by the native SDK: encrypted, see
`docs/open-questions.md` §4.

---

## 2. The dex: the BroadLink SDK

The JavaScript stopped here:

```js
cordova.exec(ok, err, "BLNativeBridge", "devicecontrol",
             [deviceID, subDeviceID, {act, params, vals}, "dev_ctrl", timeouts])
```

The rest is Java. First pass, without tools:

```bash
unzip -o base.apk 'classes*.dex' -d .
strings -n 6 classes.dex | grep -E 'ibroadlink|/device/control|/dataservice|/ec4|/appfront'
```

This is how `/device/control/v2/sdkcontrol`, `querystate`,
`dataservice/v1/device/stats` and the endpoints of `docs/open-questions.md` §6 came out.

Second pass, with androguard:

```bash
pip install androguard
python3 ../tools/dex_inspect.py xref classes.dex '/device/control/v2/sdkcontrol'
python3 ../tools/dex_inspect.py decompile classes.dex 'cn/com/broadlink/sdk/b' > sdk_b.txt
```

The classes that matter:

| Class | Content |
| --- | --- |
| `com.tcl.smartdevice.AirApplication` (`initData`) | the four BroadLink licenses in cleartext, the per-region `pid`s |
| `cn.com.broadlink.base.BLApiUrls` | building the URLs per region |
| `cn.com.broadlink.sdk.b` | control directives, querystate, device time |
| `a.a.a.account.a.c` | the login body |
| `a.a.a.account.a.b` | signature and encryption of the account calls |
| `cn.com.broadlink.base.BLCommonTools` | `aesNoPadding` (mode, IV), `parseStringToByte`, hash |
| `cn.com.broadlink.account.BLAccountEncryptAPI` | the three native salt functions |

Watch out for a method pitfall: if a string exists in the dex pool but `xref` finds
no reference, **that constant is not used by the Java code**. It is the signal that it lives in a native
library — and it is exactly how the salts problem was discovered. Do not take
a constant for granted just because the string appears in the file.

---

## 3. The native libraries

```bash
for a in *.apk; do unzip -o -j "$a" 'lib/*/*.so' -d lib/ 2>/dev/null; done
ls -la lib/
```

The ones that matter:

| Library | Role |
| --- | --- |
| `libBLAccountEncryptAPI.so` | the three authentication salts (cleartext strings) |
| `libNetworkAPI.so` | the real DNA protocol: packets, LAN and cloud relay, execution of the `.script`s |

For the salts: `python3 ../tools/extract_salts.py lib/libBLAccountEncryptAPI.so`.

`libNetworkAPI.so` is where the schedule-command encoding would live — but this turned out to be
moot: on these `0x4e2e` modules there is no native scheduler at all (tested on real hardware), so
the Android app keeps schedules phone-side instead (`docs/open-questions.md` §2).

---

## 4. Verify instead of believe

The criterion adopted: everything written in `docs/protocol.md` was read from the app's
code, not inferred. And there is an independent verification: the payload of `set temp 23.0` produced
by this library is identical byte for byte to the one observed on the wire, internal checksum
included.

```
1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
```

It is the test `tests/test_local.py::test_matches_documented_golden_packet`. When you add a piece
of protocol, look for a way to anchor it to an observed datum in the same way.
