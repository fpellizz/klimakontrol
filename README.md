# klimakontrol

Control of **Wisnow / TCL** air conditioners with the **BroadLink DNA** WiFi module, without
the official app.

The commercial app (`com.ab.smartDevice`, "Intelligent AC") is slow, loses commands and
gets timers wrong. This project talks directly to the air conditioners: at home via UDP
on the local network, away from home through the same cloud the app uses.

The protocol was reconstructed from the official APK — which by mistake includes the source maps
of the control panel, and therefore its source code. The complete specifications are in
[`docs/protocol.md`](docs/protocol.md).

If you work on it with Claude Code, start from [`CLAUDE.md`](CLAUDE.md): the real status, conventions and the
traps you fall into by guessing. Then [`docs/open-questions.md`](docs/open-questions.md)
for the open work and [`docs/roadmap.md`](docs/roadmap.md) for the order.

## What it does today

| | |
| --- | --- |
| Local control | UDP port 80, AES-128-CBC. Response in milliseconds, works without internet |
| Remote control | HTTPS to `appservice.ibroadlink.com`, from any network |
| Automatic transport | tries the local network, falls back to the cloud |
| Data model | 79 documented parameters: control, sensors, comfort, diagnostics, energy |
| Energy usage history | hourly, daily and monthly reports, with operating hours |
| Schedules | complete model and timezone conversion (see *Limitations*) |
| Operational security | maskable output, session saved with 0600 permissions, password never written |

No external dependencies: only Python 3.8+. AES is implemented in the package and verified
against the FIPS-197 vectors, so the code runs even where nothing can be compiled.

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
tests/         88 tests, all offline
```

## Current limitations, stated plainly

1. **Not yet tested on a real system.** Every byte is verified against the specifications and
   against the documented packets (the payload of `set temp 23.0` comes out identical, checksum
   included), but the field test is the next step.
2. **Schedules can be read and modeled, but not yet written.** The commands
   `dev_taskadd` / `dev_tasklist` go through the native layer of the SDK (`libNetworkAPI.so`),
   which builds the packet using the encrypted `.script` files inside the APK. Two ways to
   close the gap: capture a UDP packet while the official app creates a timer, or
   use the cloud API `/appfront/v1/timertask/*`. The data model and the timezone conversion
   are already ready.
3. **`if_function`** is the bitmask of the functions the individual model actually
   supports. The value can be read; the bit → function correspondence still has to be derived.
4. **`devicetypeflag`** is passed as 0 if the cloud does not provide it. To be confirmed in the
   field.

### The authentication salts: the piece that really is missing

The login sends `password = SHA1(password + sale)`, signs the body with a second salt and
encrypts it with a key derived from a third. The app does not keep these three values in the Java code:
it asks three native functions of `libBLAccountEncryptAPI.so`, which do nothing but
return a constant.

Of the three, **only one is verified**: the one for the body signature (`xgx3d*fe3478$ukx`), which
appears in the dex because the `/ec4` and `dataservice` calls also use it. The other two are
the values that circulate in open source projects, and **do not appear in this APK**: they come
from another build of the SDK.

With a wrong salt the cloud responds `-1008` — again "wrong credentials" to correct
credentials. That is why the three salts are replaceable without touching the code:

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

The existing open source projects use `8503b08fa57729df9faa45e4c978852c` as the *company id*
of the international region. It is not: that value appears identical in all four
BroadLink licenses of the app, it is a global constant. The real company id of the
international region is `a8452a8f48ae707edc12e9c52e21f00f`.

With the wrong pair the cloud responds `-1008`, that is **"wrong credentials" to perfectly
correct credentials** — a message that sends you hunting for the problem in the wrong place.
To avoid repeating the mistake, `cloud.py` does not contain hand-copied identifiers: it keeps the
license blobs extracted from the APK and derives licenseId and companyid at every startup (first 16
bytes and next 16, in the clear).

## Next steps

- test on a real system and fix whatever comes up
- Android app on top of this library, compiled in CI
- close the writing of schedules
- map the bits of `if_function`

## Legal note

Interoperability with owned hardware: in the EU, decompilation for this purpose is
expressly allowed (Directive 2009/24/EC, art. 6). This project does not redistribute
someone else's code and does not clone the official app: it talks to its owner's air conditioners.

## License

MIT.
