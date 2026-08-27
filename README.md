# klimakontrol

Control of **Wisnow / TCL** air conditioners with the **BroadLink DNA** WiFi module, without
the official app.

The commercial app (`com.ab.smartDevice`, "Intelligent AC") is slow, loses commands and
gets timers wrong. This project talks directly to the air conditioners: through the same cloud the app uses (the
only path the owner's `0x4e2e` modules accept), and — for modules that allow it — at home via UDP
on the local network.

The protocol was reconstructed from the official APK — which by mistake includes the source maps
of the control panel, and therefore its source code. The complete specifications are in
[`docs/protocol.md`](docs/protocol.md).

If you work on it with Claude Code, start from [`CLAUDE.md`](CLAUDE.md): the real status, conventions and the
traps you fall into by guessing. Then [`docs/open-questions.md`](docs/open-questions.md)
for the open work and [`docs/roadmap.md`](docs/roadmap.md) for the order.

## What it does today

| | |
| --- | --- |
| Remote control | HTTPS to `appservice.ibroadlink.com`, from any network — verified on real hardware |
| Local control | UDP port 80, AES-128-CBC, byte-verified — but the owner's `0x4e2e` modules reject it (cloud-only), see *Limitations* |
| Automatic transport | tries the local network, falls back to the cloud |
| Data model | 79 documented parameters: control, sensors, comfort, diagnostics, energy |
| Energy usage history | endpoint implemented, but these modules do not measure energy (returns empty) |
| Schedules (timers) | phone-side in the Android app; these modules have no native scheduler (see *Limitations*) |
| Operational security | maskable output, session saved with 0600 permissions, password never written |

No external dependencies: only Python 3.8+. AES is implemented in the package and verified
against the FIPS-197 vectors, so the code runs even where nothing can be compiled.

There is also an **Android app** (Kotlin / Jetpack Compose) built on top of this library and
compiled in CI — see [`android/README.md`](android/README.md).

## Installation

```bash
git clone <this repo> klimakontrol
cd klimakontrol
python3 -m klimakontrol --help
```

Optionally, `pip install -e .` to get the `klimakontrol` command in your PATH.

## Usage

```bash
python3 -m klimakontrol login                     # asks for email and password, tries all regions
python3 -m klimakontrol list
python3 -m klimakontrol status 1
python3 -m klimakontrol status 1 --full           # diagnostics and sensors too
python3 -m klimakontrol on 1
python3 -m klimakontrol set 1 temp=23 tcl_mode=freddo tcl_mark=auto
python3 -m klimakontrol off 1
python3 -m klimakontrol online                    # state of all units, one call
python3 -m klimakontrol energy 1 day
python3 -m klimakontrol discover                  # search for modules on the local network
python3 -m klimakontrol raw 1                     # raw JSON, for debugging
```

The region (`ab`, `eu`, `ru`, `cn`) is chosen the first time the official app is launched and then
is no longer visible: if you don't remember it, `login` tries them in order and tells you which one worked.
With `--region eu` you force it.

The password is not saved. The session is, in `~/.config/klimakontrol/session.json`:
the cloud temporarily blocks anyone who logs in again too often (error `-1036`).

To force a transport: `--transport local` or `--transport cloud`.

## As a library

```python
from klimakontrol import CloudClient, LocalClient, Device

cloud = CloudClient("eu")
cloud.login("io@example.com", "…")
salotto = cloud.devices()[0]

cloud.set_state(salotto, {"pwr": 1, "temp": 230, "tcl_mode": 3})
print(cloud.get_state(salotto))

# at home, without going through any cloud
locale = LocalClient(Device(host=salotto.lanaddr, mac=salotto.mac, key=salotto.aeskey))
print(locale.get_state())
```

## Structure

```
klimakontrol/
  aes.py       AES-128-CBC in pure Python (no dependencies)
  local.py     BroadLink/DNA UDP protocol: packets, discovery, LAN authentication
  cloud.py     login, device list, remote control, energy usage
  params.py    dictionary of the 79 parameters, with labels, units and enums
  tasks.py     schedules and the timezone conversion the app gets wrong
  session.py   persistence and masking of sensitive data
  cli.py       command-line interface
docs/
  protocol.md  the complete protocol specifications
tests/         136 tests, all offline
```

## Current limitations, stated plainly

1. **Local control is not supported by these modules.** The library implements it (UDP:80, AES)
   and it is byte-verified against the documented packet, but the owner's `0x4e2e` modules answer
   `-5` to local control and are driven only through the cloud — the official app does the same
   (on the LAN it only does discovery). Remote control over the cloud is tested and works.
2. **These modules have no native scheduler.** Tested on real hardware: device-side tasks
   (`dev_taskadd`/`dev_tasklist`) return the current state, not the tasks (the model's Lua script
   has the timer commands removed); the cloud timer API `/appfront/v1/timertask/*` is dead code in
   the app; and the parameter-based "reservation" is not in this model's profile. So schedules are
   handled **phone-side by the Android app** (`AlarmManager` sends the command at the set time).
   See `docs/open-questions.md` §2. The Python library keeps a device-side schedule model for
   reference, but it does not work on this hardware.
3. **Energy usage is not measured by these modules.** The `dataservice` endpoint answers `ok` but
   empty — these units have no power metering.
4. **`if_function`** is the bitmask of the functions the individual model actually
   supports. The value can be read; the bit → function correspondence still has to be derived
   (on these modules it is not even reported — see `docs/open-questions.md` §4).
5. **`devicetypeflag`** is passed as 0 if the cloud does not provide it.

### The authentication salts

The login sends `password = SHA1(password + salt)`, signs the body with a second salt and
encrypts it with a key derived from a third. The app does not keep these three values in the Java
code: it asks three native functions of `libBLAccountEncryptAPI.so`, which do nothing but return a
constant.

All three are **verified**, extracted from the native library with `tools/extract_salts.py` (only
the body-signature salt `xgx3d*fe3478$ukx` also appears in the dex, because `/ec4` and
`dataservice` use it too). The salts were never the problem: the `-1008` at login was the wrong
**companyid** (see below, and `docs/open-questions.md` §1). They stay replaceable without touching
the code, in case they ever change:

```bash
export KLIMAKONTROL_SALT_PASSWORD='...'
export KLIMAKONTROL_SALT_TOKEN='...'
export KLIMAKONTROL_SALT_BODY='...'
```

And to derive them from the `.so`, which is the only place they live:

```bash
python3 tools/extract_salts.py libBLAccountEncryptAPI.so
```

The library lives in `lib/<abi>/` of the **split APK** (`split_config.arm64_v8a.apk`), not
of the base APK: that is why the `lib` folder is not in the base APK.

### A note on the region identifiers

`8503b08fa57729df9faa45e4c978852c` **is** the real companyid: it is the constant shared by all
four BroadLink licenses of the app, at bytes `[120:136]` of the blob — the same for every region.
What changes per region is the `lid` (bytes `[0:16]`). Using `blob[16:32]` as the companyid — as
some open source projects, and an earlier version of these notes, did — makes the cloud answer
`-1008` ("wrong credentials" to perfectly correct credentials), which sends you hunting in the
wrong place. Verified on 2026-08-21: a successful login on region eu echoed back
`companyid: 8503b08f…`.

To avoid hand-copied identifiers, `cloud.py` keeps the license blobs extracted from the APK and
derives `licenseId = blob[0:16]` and `companyid = blob[120:136]` at every startup.

## Next steps

- ✅ tested on a real system (owner's Wisnow plant, `0x4e2e`): login, list, state, on/off,
  setpoint and `querystate` all work over the cloud
- ✅ Android app (Kotlin/Compose) on top of this library, built in CI — see `android/README.md`
- ✅ schedules resolved: no native scheduler on this hardware → phone-side timers in the app
- map the bits of `if_function`

## Legal note

Interoperability with owned hardware: in the EU, decompilation for this purpose is
expressly allowed (Directive 2009/24/EC, art. 6). This project does not redistribute
someone else's code and does not clone the official app: it talks to its owner's air conditioners.

## License

MIT.
