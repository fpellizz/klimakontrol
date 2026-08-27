# Open questions, with the procedure to close them

Each entry has: what is missing, why it is missing, and the exact commands to solve it. When one
is closed, move it to `docs/reverse-engineering.md` with the answer and update the status table in
`CLAUDE.md`.

---

## 1. Login is rejected with -1008 — ✅ RESOLVED (2026-08-21)

**Cause.** The `companyid` was derived from `blob[16:32]` (per-region); the real one is the
**shared constant** `8503b08fa57729df9faa45e4c978852c` (`blob[120:136]`), the same for all
regions. The per-region `lid` was in fact correct. Fix in `cloud.py::_region_from_license`. A
login on region eu returned, echoed by the server, `companyid: 8503b08f…`. Details in
`CLAUDE.md` §5 pitfall 1. Login, unit list, `querystate`, reading and control via cloud
tested on real hardware.

What follows is the old analysis, kept as a chronicle (it led to a wrong track: the shape of the
request was correct, only the correct companyid was missing).

**Symptom (historical).** `login` returned `-1008` on all four regions, with credentials
that work in the official app.

**What has already been ruled out** (all read from the dex, not inferred):

* the three salts: confirmed by extracting them from `libBLAccountEncryptAPI.so` with `tools/extract_salts.py`
  — they are `4969fj#k23#` (password), `kdixkdqp54545^#*` (key), `xgx3d*fe3478$ukx` (signature);
* the host: `https://<lid>appservice.ibroadlink.com`, because the app initializes the SDK with
  `APP_SERVICE_ENABLE=1` and therefore `setAppServiceHost` overrides all `biz*` hosts;
* the `/account/login` path and the body `{email|phone, password, companyid, lid}`;
* the `lid` and `licenseId` headers plus the common ones (`system`, `appPlatform`, `language`,
  `timestamp`, `appVersion`, `messageId`, `Content-type: application/x-java-serialized-object`);
* the encryption: `AES/CBC/ZeroBytePadding`, IV `eaaaaa3abb5862a21918b5771d1615aa`, key
  `md5(timestamp + salt)` interpreted as hexadecimal;
* the password arrives raw from the interface (`LoginActivity$LoginTask` → `BLAccount.login`):
  no intermediate hash;
* the `licenseId`/`companyid` pairs, derived from the APK's license blobs.

**Remaining hypotheses, in order of probability.**

1. **The account does not exist in this scope.** `-1008` might mean "user not found for
   this companyid/lid pair", not "wrong password". If the account was created with an earlier
   version of the app or with another OEM app on the same platform, it lives under a
   different `companyid`.
2. **A header the server requires and we do not send.** Candidates seen in the dex:
   `datatrace` (Base64 of a JSON), `loginmode: mutuallyexclusive`, and the common headers taken from
   `HTTP_COMMON_HEADER` (which the app fills in with `BLSettingUnits.getCompany()`).
3. **A minimum app version.** We send `appVersion: 1.0.12`; the installed APK is more
   recent. Try with `KLIMAKONTROL_APP_VERSION`.
4. **Clock.** If the `timestamp` is too far from the server's, the derived key is
   valid but the request is discarded. Check `date -u`.

**How it is closed.**

First step, free: **read the server's message**, not just the code.

```bash
KLIMAKONTROL_DEBUG=1 python3 -m klimakontrol login --region eu
```

It prints the raw request and response. If the message says "user not exist" we are in hypothesis 1;
if it says "password error" we are in 2 or 3.

Second step, decisive: **compare with the app's real request**.

```bash
adb logcat -c && adb logcat | grep -iE 'Json Param|Http Url|BroadLink|LoginActivity'
```

and log in from the official app. The SDK logs the body in cleartext with the prefix
`Json Param:` — if the log level allows it. Otherwise a traffic capture
(`docs/recipes-adb.md` §5): you see the real headers and the body length, and the comparison
with ours closes the question in a minute.

Third step, if you need absolute certainty: runtime hook with Frida
(`docs/recipes-adb.md` §6) on the function that builds the body.

**Watch out for the rate limit.** After a few close attempts the cloud responds `-1036` and
blocks logins for a few minutes. Change **one** variable per attempt, and do not loop.

## 2. Writing schedules on the wire — blocked by the native layer (investigation 2026-08-21)

**What is missing.** The on-the-wire encoding of `dev_taskadd`, `dev_tasklist`, `dev_taskdata`,
`dev_taskdel`. The data model and the timezone conversion are already in `tasks.py`; the task
payload is built by the WebView JS (`app.html`/`main.*.js`) with the same encode(1)/decode(2)
transform of `to_wire`/`from_wire`. What is missing is the **action byte** and the exact
packaging.

**What I discovered (why it is hard).**

* The UI uses the **device-side** tasks via the bridge `devicecontrol(deviceID, subDeviceID, payload,
  "dev_taskadd", cfg)`: `dev_tasklist` sends payload `{}`, `dev_taskadd` the encoded task.
* Since the app is **cloud-only** (PCAPdroid capture: no local control, only discovery),
  the task commands do NOT use `KeyValueControl` like `dev_ctrl`. In the dex (`cn.com.broadlink.sdk.b`)
  the commands other than `dev_ctrl` go via the `dev_passthrough` / `DNA.TransmissionControl`
  route, where the payload is the **raw device packet in Base64**, built by the native layer.
* The raw packet is built by `libNetworkAPI.so` running the **model's Lua script**
  (`…2e4e0000.script`). But the `.script` is **encrypted with a proprietary BroadLink cipher "tfb"**
  (NOT AES): native functions `networkapi_scriptfile_read` → `broadlink_tfb_decrypt`,
  `broadlink_tfb_setkey_dec`, `broadlink_tfb_crypt_cfb128/…`; embedded **Lua 5.3** VM.

So the action byte + the task structure are behind a **proprietary cipher + Lua**
in the native layer: not derivable from the dex nor from the JS.

**How it would be closed** — none is trivial.

1. **RE of the `tfb` cipher**: extract the key from `networkapi_scriptfile_read` and reimplement
   `broadlink_tfb_decrypt` (from the ARM assembly) to decrypt the `.script`, then read the Lua.
   Bonus: it also unlocks `if_function` and the temperature limits (§4).
2. **Brute-force of the action byte** via cloud passthrough (`DNA.TransmissionControl`): build
   a raw `dev_tasklist` with a guessed action byte (get=1, set=2 → tasks 3-8) and see
   which one the module accepts. It does not need the `.script`, but the passthrough format must be
   replicated and you write to the module blind.
3. **TLS capture** of the real request (needs a repackaged APK for the user cert, or Frida),
   then Base64-decode + decrypt with the device key.

Note: the old idea of capturing a **local UDP packet** from the app **does not work** — the app
never sends control/task locally (only discovery). There is also the cloud API
`/appfront/v1/timertask/*` (server-side scheduling), but it loses the offline advantage.

**Update (2026-08-27) — CLOSED: this hardware has no native scheduler.**
All three native routes tried, all negative on `0x4e2e`:

1. **Device-task** (`dev_taskadd`/`dev_tasklist` via `sdkcontrol`): tested on real hardware —
   `dev_tasklist` **returns the air conditioner's state, not the tasks** (the module treats the unknown
   `act` as "read everything"). Consistent with the model's Lua script, which for this
   model has the timer commands **removed**. Dead end.
2. **Cloud API `/appfront/v1/timertask/*`**: **dead code** in the app. RE of the dex + the JS
   bundles: the 4 SDK methods (`BLApiUrls$APPFront.URL_*_CLOUD_TIMER`) have **0 callers**; no
   panel bundle sends `serviceName:"timerservice"`; the **body schema is not derivable**
   from this APK (built in a JS module that is not included). There is also a risk it is not enabled
   cloud-side for this company. Not pursued.
3. **Parameter-based "reservation"** (`if_subs`/`sub_on_off`/`if_cycle`/`sub_weekday`/`sub_time`/`cmd`,
   with a normal `set`): it is the real mechanism for **other** TCL models, but the **firmware profile
   of `0x4e2e`** (`www/model/profile.js`) lists only the usual 10 parameters and **not** the `sub_*`;
   and the `get` always returns those 10, so not even the official app reads back a reservation on
   these units. Not applicable.

**Decision:** the app keeps the timers **phone-side** (`android/.../data/schedule/`): `AlarmManager`
fires a `BroadcastReceiver` at the right time, which sends on/off via cloud (the same
`sdkcontrol` as manual control), re-arms the recurring one / consumes the one-shot, reschedules at
reboot. Compromise: with the phone off it may not fire — but **offline** control on these
modules does not exist anyway (the `-5`, §5 pitfall 4 of CLAUDE), so nothing real is lost.
The Python lib+CLI stays device-side (reference), not working on this hardware.

---

## 3. `devicetypeflag`

In the `sdkcontrol` body there is `devicePairedInfo.devicetypeflag`, read by the SDK with
`BLDNADevice.getDeviceFlag()`. It is unclear which field of `getallinfo` it comes from: today
`cloud.py` takes `devicetypeflag`/`devicetypeFlag` if present, otherwise 0.

**How it is closed.** At the first successful login, look at the device's raw record:

```bash
python3 -m klimakontrol raw 1
```

In the dex there is a branch that treats the value `4` in a special way (`getDeviceFlag() == 4` forces the
remote path), so the field matters at least in some cases.

---

## 4. The `if_function` mask

`if_function` says which functions the individual model actually supports. The value can be read; the
bit → function correspondence cannot.

**Update (2026-08-22):** on these `0x4e2e` modules `if_function` **is not even
reported** in the fixed set of the `get`. On the other hand, that fixed set (10 parameters, §5) *is* in fact the
capability list: it includes both swings and omits mute/health/display. Tried to hide the
controls based on the reported set: it works, but only with the right keys (`ac_vdir`/`ac_hdir`,
not `tcl_*`) — with the wrong ones the real swing disappeared too. For now the app exposes exactly
the 10 of the set, without heuristics.

**How it is closed.** Read `if_function` on the system, then compare with the buttons that
the official app shows for that model. From there you derive which bit corresponds to each function.
It is needed to build an interface that shows only the real ones, instead of filling the screen with
dead commands as the app does.

Quicker alternative: the `.script` files in the assets (one per `pid`) contain the definition
of the model. They are encrypted, but the key is probably derivable: if they are opened, they give
`if_function`, the temperature limits and the list of parameters per model, all together.

---

## 5. The exact shape of the cloud responses — ✅ partly RESOLVED (2026-08-21)

`sdk_control` reads `event.payload.data`. **Seen on a real response**: `data` arrives as a
**JSON string** (not an object), of the type
`'{"params":["pwr","tcl_mode","save_temp",...],"vals":[[{"val":1,"idx":1}],...]}'`. `get_state`
and `set_state` now `json.loads` it. Note: the `get` ignores the requested `params` and always
returns a fixed set of parameters decided by the module. `querystate` returns
`event.payload.data` as a list `[{"did":..., "state":0|1}]`.

**Fixed set seen on the wire (2026-08-22, devtype `0x4e2e`, identical on all units):**
`pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode`.
Two surprises: the swing is `ac_vdir`/`ac_hdir` (not `tcl_vdir`/`tcl_hdir`); and `qtmode`, `ac_health`,
`bglight`, `envtemp`, `if_function` do **not** appear — on this model they are not handled.

The same holds for `dataservice`: the app's panel reads `table[0].values`, but the shape
of the individual rows (field names for kWh, running hours, network gaps) has yet to be seen.

---

## 6. The other endpoints identified and not studied

Found in the dex, never tried. In order of usefulness for this project:

| Endpoint | What it would be for |
| --- | --- |
| `/appfront/v1/timertask/*` | cloud-side schedules (see §2) |
| `/appfront/v1/scene/*`, `/appfront/v1/trigger/upsert` | scenes and trigger-based automations |
| `/ec4/v1/electricinfo/config` | configuration of energy usage monitoring |
| `/device/control/v3/sdkcontrol` | group control (multiple units in one call) |
| `/ec4/v1/dev/*`, `/ec4/v1/module/*` | rename units and rooms, sharing |
| `/dataservice/v1/device/status` | state history (implemented, never tried) |
