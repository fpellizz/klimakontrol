# How the specifications were derived

A chronicle of the work, so that anyone can redo it or verify it.

## 1. Identifying the platform

The app on the Play Store is `com.ab.smartDevice`, "Intelligent AC", publisher ACSmart, with
`@tcl.com` support addresses. It is not Wisnow software: it is the OEM app that TCL provides to the
brands that resell its splits. Wisnow is one of them.

The installed WiFi module is a **BroadLink DNA** (device type `0x507A`/`0x507C`), and the cloud
is not TCL but BroadLink app-service (`*.ibroadlink.com`).

## 2. The APK assets: the source maps

The app is a Cordova WebView. The control panel of each air conditioner family is
a React bundle inside `assets/default*.zip`. In those bundles **the source
maps were included** (`main.*.js.map`, up to 21 MB): they contain `sourcesContent`, that is the **original
source code** un-minified, with the file names and the developers' comments.

From there come:

* the parameter dictionary (`src/panel/data.js`) — 84 entries with titles and types
* the mode and fan value mappings (`mainModeControlButtons`, `mainFanControlButtons`)
* the interface to the native layer (`~/broadlink-jssdk/dna/adapter.js`)
* the schedule commands and the timezone conversion
* the energy usage API (`src/panel/Electricity.js`)

Reconstruction:

```bash
unzip -o assets/default.zip -d bundle 'zh-cn/main.*.js.map'
python3 - <<'PY'
import json, os, re
m = json.load(open('bundle/zh-cn/main.<hash>.js.map'))
for src, content in zip(m['sources'], m['sourcesContent'] or []):
    if content is None or '/~/' in src:
        continue
    path = re.sub(r'\?.*$', '', src.replace('webpack:///./', ''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'w').write(content)
PY
```

## 3. The dex: remote control

The JavaScript stopped at the native boundary:

```
cordova.exec(ok, err, "BLNativeBridge", "devicecontrol",
             [deviceID, subDeviceID, {act, params, vals}, "dev_ctrl", timeouts])
```

The rest is in the Java SDK. `classes.dex`, read with androguard, gave:

* `cn.com.broadlink.base.BLApiUrls` — building the URLs per region
* `cn.com.broadlink.sdk.b` — the methods that compose the control directives

The useful strings can also be found without decompiling:

```bash
strings -n 6 classes.dex | grep -E "ibroadlink|/device/control|/dataservice"
```

From here: `/device/control/v2/sdkcontrol`, `/device/control/v3/sdkcontrol`,
`/device/control/v2/querystate`, `/dataservice/v1/device/stats`,
`/appfront/v1/timertask/*`.

Then decompiling the methods that use those strings gave the exact shape of the
directives, including the `cookie` field that carries the device's AES key to the cloud, and the
`timstamp` typo in the header — replicated on purpose in our code, because it is what
the server expects.

## 4. The licenses, and a constant that is easy to misread

`com.tcl.smartdevice.AirApplication.initData` contains the app's four BroadLink licenses,
one per region (`ab`, `cn`, `eu`, `ru`), as Base64 blobs. The blob **is not encrypted**: the first
16 bytes are the `licenseId` (per region), and the `companyid` is the shared constant at bytes
`[120:136]`.

```python
raw = base64.b64decode(blob)
license_id, company_id = raw[0:16].hex(), raw[120:136].hex()
```

Two consequences:

1. The value `8503b08fa57729df9faa45e4c978852c` appears identically in all four licenses: it **is**
   the companyid, shared by every region. The trap is `blob[16:32]` (per region, e.g.
   `a8452a8f…` for the international region): some open source projects — and an earlier version of
   these notes — used that as the companyid, which makes the cloud answer `-1008`. Verified on
   2026-08-21: a successful login on region eu echoed back `companyid: 8503b08f…`. See `CLAUDE.md`
   §5 pitfall 1.
2. To avoid repeating the error, `cloud.py` keeps the blobs and derives the identifiers
   (`licenseId = blob[0:16]`, `companyid = blob[120:136]`) at each startup, instead of copying them
   by hand.

Also from `initData`: the `pid`s of the three machine types (split, portable, window) change per
region — for the international region the split is `0x507c`, for Europe `0x507a`.

## 5. The authentication salts: in the native layer, not in the dex

The login uses three constants that the app asks of `libBLAccountEncryptAPI.so`:

| Native function | Use | Value |
| --- | --- | --- |
| `blAccountPasswordEncrypt()` | `SHA1(password + salt)` | `4969fj#k23#` |
| `blAccountTokenEncrypt()` | `md5(timestamp + salt)` → AES key | `kdixkdqp54545^#*` |
| `blAccountBodyEncrypt()` | `md5(body + salt)` → `token` header | `xgx3d*fe3478$ukx` |

In the dex only the third appears (it is also used by `/ec4` and `dataservice`). The other two are read
from the native library, where they are cleartext strings:
`python3 tools/extract_salts.py libBLAccountEncryptAPI.so`.

Method lesson: if `dex_inspect.py xref` finds no references to a string present in the
pool, that constant **is not used by the Java code**. It is the signal that it lives in the native layer, and
must not be taken for granted just because the string is there.

## 6. The rest of the login chain

* host: `https://<licenseId>appservice.ibroadlink.com`. The app initializes the SDK with
  `APP_SERVICE_ENABLE=1`, and `BLApiUrls.setAppServiceHost` overrides all `biz*` hosts
  (`bizaccount`, `bizihcv0`, `bizpd`, …) with the single one. The `biz*` hosts apply only with
  that flag at zero.
* body: `{email|phone, password, companyid, lid}` (`a.a.a.account.a.c`)
* signature and encryption: `a.a.a.account.a.b` — `AES/CBC/ZeroBytePadding`, IV
  `eaaaaa3abb5862a21918b5771d1615aa`, key from `md5(timestamp + salt)` read as hexadecimal
  (`BLCommonTools.aesNoPadding`, `parseStringToByte`)
* the password arrives raw from the interface: `LoginActivity$LoginTask.doInBackground` →
  `BLAccount.login(user, password)`, no intermediate hash.

Everything above matched, yet the login kept returning `-1008` — because the **companyid** was
being taken from `blob[16:32]` instead of the shared `blob[120:136]` (see §4). Fixed on 2026-08-21:
login, unit list, `querystate` and cloud control all work. Details in `docs/open-questions.md` §1.

## 7. Schedules: no native scheduler on this hardware (CLOSED)

The schedules (`dev_taskadd`, `dev_tasklist`, `dev_taskdata`, `dev_taskdel`) do not go through a
dedicated directive: they end up in `BLControllerDescParam.setCommand(...)` and from there into the
native layer, which builds the packet using the encrypted `.script` files in the assets.

Tested on real hardware (2026-08-27), all three native paths turned out to be dead ends on these
`0x4e2e` modules: `dev_tasklist` via `sdkcontrol` returns the current **state**, not the tasks (the
model's Lua script has the timer commands removed); the cloud API
`/appfront/v1/timertask/{add,query,modify,delete}` is **dead code** in the app; and the
parameter-based "reservation" (`sub_*`) is not in this model's profile. So this hardware has **no
native scheduler**. The Android app therefore keeps the schedules **phone-side** (`AlarmManager`
sends the command at the set time). Full write-up in `docs/open-questions.md` §2.

## 8. Independent verification

The payload of `set temp 23.0` produced by this code is identical byte for byte to the one
observed on the wire, internal checksum included:

```
1800 a5a55a5a 87c4 020b 0c000000 7b2274656d70223a3233307d 000000000000
```

It is the test `test_matches_documented_golden_packet` in `tests/test_local.py`.
