# Wisnow / "Intelligent AC" — reconstructed API specifications

**Version 3** — 20 August 2026. Updated after the analysis of `classes.dex`.
Status: **complete specifications**. Local control, remote control via cloud, data model,
schedules and energy usage history: all documented. Nothing is missing anymore to build the app.

---

## 0. Summary for those in a hurry

| Component | Status |
| --- | --- |
| What platform it is | **Solved**: BroadLink DNA module, BroadLink cloud, TCL/ACSmart OEM app |
| Control protocol on the LAN | **Complete**: UDP:80, AES-128-CBC, JSON |
| Complete list of parameters | **Complete**: 84 parameters extracted from the app's code |
| Timers and weekly programs | **Complete**: they live *inside* the air conditioner, 5 types of schedule |
| Energy usage history | **Identified** the cloud API `dataservice/v1/device/stats` |
| Cloud login and device list | **Complete** (endpoint, signatures, salt, encryption) |
| Control from outside the home | **Solved**: `POST /device/control/v2/sdkcontrol` on the BroadLink cloud |

An important note that changes the project: **the schedules do not need anything to be
always on**. They are programmed into the air conditioner's WiFi module, which runs them on its own
even with the phone off and the internet down. This was the main doubt about the "cloud-only"
architecture: solved.

---

## 1. How the app is built inside

`com.ab.smartDevice` = **"Intelligent AC"** by ACSmart (TCL support). It is not Wisnow software:
it is the OEM app that TCL supplies to the brands that resell its splits.

The real architecture, reconstructed from the APK:

```
Android app (Java, BroadLink "BLLet" SDK)
  └── Cordova WebView
        └── control panel = React app (one per air-conditioner type)
              └── "broadlink-jssdk" module
                    └── cordova.exec(..., "BLNativeBridge", "devicecontrol", [...])
                          └── libNetworkAPI.so  →  UDP on LAN  or  cloud relay
```

The React panels are web bundles inside `assets/default*.zip`, **with the source maps included**:
the original source code is fully recoverable. That is where everything that
follows comes from.

Bundles present in the APK, one per product family:

| File | PID | Note |
| --- | --- | --- |
| `default.zip` | generic family (used by `0x507c`) | most complete panel |
| `default_...2e4e0000.zip` | `0x4e2e` | variant with `save_temp` |
| `default_...d9500000.zip` | `0x50d9` | |
| `default_...d3500000.zip` | `0x50d3` | |
| `default_...cca90000.zip` | `0xa9cc` | |

There are also 5 `.script` files (one per PID, encrypted) — these are the protocol definitions that
the BroadLink SDK uses for each model. They are not needed if we reconstruct the protocol ourselves.

---

## 2. Local protocol (LAN)

- Transport: **UDP port 80**, BroadLink/DNA framing, magic `5aa5aa555aa5aa55`
- Encryption: **AES-128-CBC**, without cryptographic padding (zero-padding to 16 bytes)
- Fixed IV: `562e17996d093d28ddb3ba695a2e6f58`
- Initial authentication key: `097628343fe99e23765c1513accf8b02`
- Operating key: **per-device AES key**, obtainable
  1. from the BroadLink auth on the LAN (command `0x0065`) — **without an account**, on already-associated units;
  2. from the `aeskey` field of `getallinfo` on the cloud.

Outer packet:

| Offset | Byte | Meaning |
| --- | ---: | --- |
| `0x00` | 8 | magic `5aa5aa555aa5aa55` |
| `0x20` | 2 | packet checksum (LE) |
| `0x24` | 2 | device type (e.g. `0x507c`) |
| `0x26` | 2 | command `0x006a`, response `0x03ee` |
| `0x28` | 2 | nonce, copied into the response |
| `0x2a` | 6 | device MAC, reversed |
| `0x30` | 4 | device id (observed `1`) |
| `0x34` | 2 | plaintext payload checksum |
| `0x38` | n | encrypted inner payload |

Inner payload:

| Offset | Byte | Meaning |
| --- | ---: | --- |
| `0x00` | 2 | usable length |
| `0x02` | 4 | inner magic `a5a55a5a` |
| `0x06` | 2 | inner checksum |
| `0x08` | 1 | action: `1` get, `2` set |
| `0x09` | 1 | constant `0x0b` |
| `0x0a` | 4 | JSON length (LE) |
| `0x0e` | n | JSON body |

Checksums: initialized to `0xbeaf`, sum of the bytes modulo `0x10000`.

### Two JSON dialects

The app uses the DNA "params/vals" form:

```json
{"act":"set","params":["pwr","temp"],"vals":[[{"val":1,"idx":1}],[{"val":230,"idx":1}]]}
```

The TCL modules also accept the abbreviated form, verified by the community:

```json
{"temp":230}
```

Commands (the bridge's fifth argument, `commandStr`):

| Command | Function |
| --- | --- |
| `dev_ctrl` | read/write parameters |
| `dev_tasklist` | list schedules |
| `dev_taskadd` | create schedule |
| `dev_taskdata` | schedule detail (`type`, `index`) |
| `dev_taskdel` | delete schedule (`type`, `index`) |

Timeouts used by the app: 3 s locally, 5 s remotely, 3 repeated packets per command
(DNA over UDP has no retransmission: it is fired multiple times). The `-4000` error should be retried.

---

## 3. Schedules: they are in the air conditioner

Five types, indexed by `type`:

| type | Internal name | Meaning |
| ---: | --- | --- |
| 0 | `timerlist` | power on/off at a date and time |
| 1 | `delaylist` | delay ("turn off in 2 hours") |
| 2 | `periodlist` | **recurring**: time + days of the week |
| 3 | `cyclelist` | cyclic with two commands (`cmd1`/`cmd2`) |
| 4 | `randomlist` | random within a window |

Time format: `YYYY-MM-DD HH:mm:ss` for types 0 and 1, `HH:mm:ss` for the others.

**Pitfall to remember**: the firmware reasons in **UTC+8** (Chinese heritage). The app converts
every time by adding `8 - offset_locale`. For Italy in daylight saving time (UTC+2) that means +6 hours.
Getting this wrong means timers that fire at random — it is probably one of the reasons why
the current app makes you curse.

Payload of `dev_taskadd`: besides `time`, `endtime`, `type`, it contains `data` (and for type 3
also `data2`) in the `params/vals` form seen above: that is, **a schedule carries with it
the complete state** to apply, not just on/off. You can program real scenarios
("at 22:00 heat 21°C fan low sleep on").

---

## 4. Parameters: the complete data model

84 parameters extracted from `src/panel/data.js`. Many are not even exposed by the app.

### Main control

| Parameter | Type | Meaning | Values |
| --- | --- | --- | --- |
| `pwr` | enum | power on | `0` off, `1` on |
| `temp` | num | set temperature | tenths of °C (`230` = 23.0) |
| `tcl_mode` | enum | mode | `1` heat, `2` dehumidify, `3` cool, `4` fan, `5` auto |
| `tcl_mark` | enum | fan speed | `0` auto, `1` low, `4` medium-low, `2` medium, `5` medium-high, `3` high |
| `ac_vdir` | enum | vertical swing | `7` on, `0` off |
| `ac_hdir` | enum | horizontal swing | `1` on, `0` off |
| `tempunit` | enum | temperature unit | °C / °F |

> **Swing name on the wire** (real hardware, 2026-08-22): the module reports the swing as
> `ac_vdir`/`ac_hdir`, **not** `tcl_vdir`/`tcl_hdir` as in the extraction from the APK. Writing
> `tcl_vdir` moves nothing. The library translates `tcl_vdir`→`ac_vdir` in `WIRE_KEY_ALIASES`
> (like `temp`→`save_temp`). The "on" value (7 / 1) is still the one from the source, to be confirmed.

**Fixed set returned on read by these modules (devtype `0x4e2e`)** — identical on all
units, it is effectively their capability list:
`pwr, tcl_mode, save_temp, tcl_mark, ecomode, pwfmode, tcl_slp, ac_vdir, ac_hdir, ac_errcode`.
Not appearing (therefore not managed via cloud): `qtmode` (mute), `ac_health`, `bglight`, `envtemp`,
`if_function`.

### Environmental readings

| Parameter | Meaning |
| --- | --- |
| `envtemp` | indoor ambient temperature |
| `envtempoutdoor` | outdoor temperature |
| `humidity` | humidity |
| `pm25`, `pm25_class` | fine particulates and quality class |
| `hcho` | formaldehyde |
| `co2_data` | CO₂ |
| `tvoc_vol`, `tvoc_q`, `tvoc_class` | volatile organic compounds |
| `air_quality` | synthetic air quality index |

### Modes and comfort

| Parameter | Meaning |
| --- | --- |
| `ac_slp` / `tcl_slp` | night mode |
| `ecomode`, `savemode`, `save_state` | energy saving |
| `pwfmode` | turbo / maximum power |
| `qtmode` | quiet |
| `ac_health` | "health" mode (ionizer) |
| `no_wfeeling` | flow without the sensation of air |
| `8heat` | maintain at 8 °C (frost protection) |
| `el_heat` | auxiliary electric heater |
| `desicmode`, `smartdesic` | dehumidify / anti-mold |
| `3dairmode` | three-dimensional flow |
| `ac_hwind`, `ac_lwind` | warm air high / cool air |
| `man_wind` | flow guided by the presence sensor |
| `beep` | key-press confirmation sound |
| `bglight` | display backlight |
| `ac_photos` | brightness sensor |
| `auto_study`, `autostd_cmd` | self-learning |

### Diagnostics (this the app does not show you at all)

| Parameter | Meaning |
| --- | --- |
| `ac_errcode` | fault code |
| `ac_type`, `tcl_type`, `devicetype` | machine model/type |
| `compressor_hz`, `compressor_opt` | compressor frequency |
| `in_fan_rpm`, `out_fan_rpm` | indoor/outdoor fan speed |
| `in_coil_temp`, `in_vent_temp` | indoor coil and supply temperature |
| `out_coil_temp`, `out_vent_temp` | outdoor coil and exhaust temperature |
| `out_volt`, `out_cur` | outdoor unit voltage and current |
| `four_way_val`, `solenoid_val` | 4-way valve, solenoid valve |
| `evaportor`, `clean_check` | evaporator self-cleaning |
| `filter_check`, `if_filterdirty` | filter status |
| `humidity_check` | humidity sensor present |

### Energy and reservations

| Parameter | Meaning |
| --- | --- |
| `target_kwh` | consumption target |
| `save_temp` | fixed temperature in saving mode |
| `save_beg_t`, `sava_stp_t` | start/end of the last saving session |
| `save_last_mode`, `save_last_temp` | last saving mode/temperature |
| `sub_on_off`, `sub_time`, `sub_weekday`, `if_subs`, `if_cycle`, `cmd` | integrated reservation |
| `timezone` | module time zone |
| `site_info` | location |
| `if_function` | mask of the functions actually supported by the model |

The last one, `if_function`, is the key to building an interface that shows only the
buttons that *your* model actually supports, instead of filling the screen with dead
functions as the current app does.

---

## 5. BroadLink cloud

Base URL per region: `https://<licenseId>appservice.ibroadlink.com`

The `companyId` is **unique and shared** by all regions (`8503b08fa57729df9faa45e4c978852c`,
bytes `[120:136]` of the license blob); only the `licenseId`/`lid` (bytes `[0:16]`) changes per
region. Confirmed by a successful login on 2026-08-21 (see `CLAUDE.md` §5 pitfall 1).

| Region | licenseId (lid) | companyId |
| --- | --- | --- |
| Europe | `aae72184369e2fc3e6ded53a90612586` | `8503b08fa57729df9faa45e4c978852c` |
| USA/other | `f6e9e21566e109a28797aba5a1d8ed7e` | `8503b08fa57729df9faa45e4c978852c` |
| China | `bffd4d702ec53938c31eb10cc0194b4a` | `8503b08fa57729df9faa45e4c978852c` |
| Russia | `e60de87565166c447a90cee96da955f7` | `8503b08fa57729df9faa45e4c978852c` |

### Account and devices

| Endpoint | Use |
| --- | --- |
| `POST /account/login` | login → `userid`, `loginsession` |
| `POST /account/newregcode` | registration step 1: sends the verification code (email/SMS). Body `{email\|phone, [countrycode], companyid, lid}`, raw POST |
| `POST /account/register` | registration step 2: creates the account → already returns `userid`+`loginsession`. **multipart/form-data**: field `text` = encrypted JSON `{email\|phone, type, password, nickname, sex, code, preferlanguage, companyid, lid, [countrycode]}` |
| `GET /ec4/v1/common/api` | `key` + `timestamp` to sign the following calls |
| `POST /ec4/v1/user/getfamilyid` | list of homes |
| `POST /ec4/v1/family/getallinfo` | devices: `mac`, `lanaddr`, **`aeskey`**, `did`, `pid`, names |

> **Registration** (reconstructed from the dex, 2026-08-22): same hosts/IV/salts as the login. The `password`
> is `SHA1(pw + sale_password)` as in the login. The **verification code is mandatory** (human step
> between the two endpoints). `register` = also login: on success the session is already established. In
> klimakontrol: `CloudClient.send_register_code()` / `.register()`, CLI `register-code` / `register`.

Signatures and encryption:

- compact JSON body, **AES-128-CBC**, IV `eaaaaa3abb5862a21918b5771d1615aa`
- `Content-type: application/x-java-serialized-object`
- login: `password = sha1(pwd + "4969fj#k23#")`, header `token = md5(body + "xgx3d*fe3478$ukx")`,
  AES key = `md5(timestamp + "kdixkdqp54545^#*")`
- `/ec4/...`: `token = md5(body + "xgx3d*fe3478$ukx" + timestamp + userid)`, AES key from
  `/ec4/v1/common/api`
- errors: `-1008` wrong credentials (often actually **wrong companyid**, see above),
  `-1036` rate limit
- the `sdkcontrol` response carries the data in `event.payload.data` as a **JSON string**
  (`params`/`vals`); it must be run through `json.loads`. Temperature setpoint of these modules: **`save_temp`**

### Energy usage history — `dataservice`

The energy usage panel calls the native bridge `cloudServices` with:

```json
{
  "method": "post",
  "serviceName": "dataservice",
  "interfaceName": "v1/device/stats",
  "httpBody": {
    "report": "fw_tcldaystatus_v1",
    "device": [{
      "did": "<device id>",
      "start": "2026-08-20_00:00:00",
      "end": "2026-08-21_00:00:00",
      "sortk": "-occurtime",
      "params": []
    }]
  }
}
```

Available reports: `fw_tcldaystatus_v1` (per hour), `fw_tclmonthstatus_v1` (per day),
`fw_tclyearstatus_v1` (per month). The response comes as `table[0].values`, with for each
interval the kWh, the running time and the connection gaps.

There is also `dataservice` / `v1/device/status` (used by the "saving" page): device
state history, that is **the history of temperatures and power-ons**. The times of
these APIs are also in UTC+8.

**Outcome on real hardware (2026-08-21).** The app points the dataservice calls at the host **`rtasquery`**
(`https://%srtasquery.ibroadlink.com/dataservice/...`), NOT `appservice`. With our code:
`/stats` on `appservice` responds `status:0, ok` but **`table[0]` is empty** (`total:0, cnt:0,
values:null`) for all granularities and all units; `/status` on `appservice` responds
`-12401 服务器忙` ("server busy"), persistent; the host `<lid>rtasquery.ibroadlink.com` **does not
resolve** (the `%s` prefix for rtasquery is not the lid). Moreover these units do not expose any
power/kWh parameter in their state: **they do not measure energy usage**, so there is no history to read.
If one day it is needed, the real `rtasquery` host must be found (probably a fixed regional prefix,
like `app-service-deu-…`).

---

## 6. Remote control — the last piece, found

Extracted from `cn.com.broadlink.sdk` in `classes.dex`. When the phone is not on the LAN, the SDK
does not use any exotic protocol: it makes a **normal HTTPS POST** to the cloud, which forwards the
command to the air conditioner. The format is the "directive" one (Alexa style) that BroadLink uses
across its whole platform.

### Remote command

```
POST https://<licenseId>appservice.ibroadlink.com/device/control/v2/sdkcontrol?license=<licenseId>
```

Header: `userid`, `loginsession`, `licenseid`, `lid` (the same ones obtained from the login).

Body:

```json
{
  "directive": {
    "header": {
      "namespace": "DNA.KeyValueControl",
      "name": "KeyValueControl",
      "interfaceVersion": "2",
      "messageId": "<did>-<unix_ts>",
      "timstamp": "<unix_ts>"
    },
    "endpoint": {
      "devicePairedInfo": {
        "did": "<did>",
        "pid": "<pid>",
        "mac": "<mac>",
        "devicetypeflag": <flag>,
        "cookie": "<base64 of the JSON below>"
      },
      "endpointId": "<did>",
      "cookie": {},
      "devSession": "<optional, if at least 112 characters long>"
    },
    "payload": { "act": "set", "params": ["pwr"], "vals": [[{"val":1,"idx":1}]] }
  }
}
```

The `cookie` field of `devicePairedInfo` is the Base64 of:

```json
{"device":{"id":<id>,"key":"<aeskey>","aeskey":"<aeskey>","did":"<did>","pid":"<pid>","mac":"<mac>"}}
```

That is: **it is the client that passes the device's AES key to the cloud**, the same one obtained
from `getallinfo` or from the LAN authentication. The cloud only acts as a postman.

Two `namespace` variants, chosen by the SDK:

| namespace / name | Use |
| --- | --- |
| `DNA.KeyValueControl` / `KeyValueControl` | parameters in `params`/`vals` form — this is the one we need |
| `DNA.TransmissionControl` / `commonControl` | passing of raw payload |

There is also `/device/control/v3/sdkcontrol` (`interfaceVersion: "3"`), used for group
control: same schema, with `devinfo` and `groupdevice` in the endpoint.

Response:

```json
{"event":{"endpoint":{"endpointId":"...","devSession":"..."},"payload":{"status":0,"msg":"success", ...}}}
```

If the cloud returns a new `devSession` it must be kept and sent back in subsequent
calls: it is the device's session token.

### State and online presence (batch)

```
POST https://<licenseId>appservice.ibroadlink.com/device/control/v2/querystate
```

Header: `userid`, `loginsession`, `licenseid`, `lid`, `companyid`.

```json
{
  "directive": {
    "header": {
      "namespace": "DNA.QueryState",
      "name": "queryState",
      "interfaceVersion": "2",
      "messageType": "controlgw.batch",
      "senderId": "sdk",
      "messageId": "<userid>-<unix_ts>",
      "timstamp": "<unix_ts>"
    },
    "payload": {
      "msgtype": "batch",
      "studata": [{"did": "<did>", "devtype": <devtype>, "devSession": "<optional>"}]
    }
  }
}
```

A single call returns the online state of all the units: perfect for the main
screen of a multi-split.

### Other useful endpoints identified in the dex

| Endpoint | Use |
| --- | --- |
| `/appfront/v1/timertask/{add,query,modify,delete}` | **cloud-side** schedules (alternative to those in the module) |
| `/appfront/v1/scene/*`, `/appfront/v1/trigger/upsert` | scenes and trigger-based automations |
| `/ec4/v1/electricinfo/config` | configuration of energy usage monitoring |
| `/dataservice/v1/device/stats` | energy usage history |
| `/dataservice/v1/device/status` | state history (temperatures, power-ons) |
| `/ec4/v1/dev/*`, `/ec4/v1/module/*`, `/ec4/v1/family/*` | management of homes, rooms, devices, shares |

Note on the URL bases: in normal mode the SDK points everything at
`https://<licenseId>appservice.ibroadlink.com`. There is a "biz" mode that moves the
families to `https://<licenseId>bizihcv0.ibroadlink.com`. For the app we care about the
first one applies.

## 7. Consequences for the project

1. **The schedules are already solved**: they are written into the module, which runs them on its own.
   No need for a device that is always on in the home.
2. **The energy usage history is a cloud REST**: queryable by the app directly, and also
   backwards to reconstruct the past that the module has already recorded.
3. **At home you go local**: instant commands via UDP. Outside the home the same app sends the
   same data structure to `sdkcontrol`. A single domain model, two transports.
4. **There is much more material than the app shows**: diagnostics, coil and supply
   temperatures, compressor frequency, filter status. With `if_function` you build
   an interface that shows only what is real.
