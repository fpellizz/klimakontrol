# Hardware test — onboarding a module (Point 3)

Runbook to test **end-to-end** the onboarding reconstructed offline: **SoftAP** config (Point 2) +
bind (Point 1). It requires a physical action (resetting a module). **No brick risk**: if our
procedure fails, the module recovers with the official app.

## Success criterion

The test is **successful** when: a reset module, having received the WiFi credentials **from our
commands**, **rejoins the network** and is **controllable** again. The minimal and most important
signal is that `klimakontrol discover` **sees it again on the LAN** after the `provision`: that
validates the SoftAP packet.

## Safety tip: use a module you already own

Run the test on **one** of your already-configured units. Advantages:
- **Automatic recovery**: on rejoining the WiFi it **re-registers to your same account**, so it
  reappears in `klimakontrol list` (with its key from the cloud) without needing `bind`.
- If something goes wrong, you re-add it with the official app as you always have.

The `bind` command (Point 1) is really only needed for a **new** module not yet in your account:
you test it as an optional step at the end.

## Prerequisites

- A device with **Python 3.8+** and this repo: a **PC** on the home network, or the
  **phone with Termux**. Note: you will have to **switch WiFi** during the test (see below).
- SSID and password of the **home WiFi** (2.4 GHz — the modules do not run on 5 GHz).
- The official app's procedure to put the module **into configuration mode** (it is the gesture
  that makes the `Broadlink_tcl_...` hotspot appear; it varies by model — I don't know it for your unit).

> **Two network contexts.** The `provision` is sent **while connected to the module's hotspot**
> (`192.168.10.1` reachable); `discover`/`login`/`list` are done **on the home WiFi**. You switch
> from one to the other by changing network in the WiFi settings.

## Steps

### 0. Preparation (on the home WiFi)

```bash
cd /path/to/klimakontrol
export KLIMAKONTROL_DEBUG=1            # prints raw requests/responses (useful for understanding)
python3 -m klimakontrol login          # if you don't already have a session
python3 -m klimakontrol list           # note the name/mac of the unit you'll reset
```

Note the row of the target unit: you need it to recognize it on return.

### 1. Put the module into configuration mode

Use the reset/pairing gesture for your model (the same one the official app uses in "add
device"). **Confirm** it is in config mode: among the phone's WiFi networks a hotspot like
**`Broadlink_tcl_XXXX`** appears (open, without internet).

### 2. Connect the test device to the module's hotspot

From the PC/phone WiFi settings, **connect to `Broadlink_tcl_XXXX`**. Ignore the "network without
internet" warning (it is normal). Verify that the gateway responds:

```bash
ping -c 3 192.168.10.1
```

### 3. Send the WiFi credentials (the `provision`)

Still **connected to the module's hotspot**:

```bash
python3 -m klimakontrol provision --ssid "HOME_WIFI_NAME" --security wpa2
# asks for the WiFi password (it doesn't end up in commands/history)
```

Expected result: `Inviato. Nessuna conferma dal modulo (normale): ora dovrebbe connettersi al WiFi.`
(or `...ha risposto (N byte)`). After a few seconds the module leaves its hotspot and tries to
join the home WiFi: the `Broadlink_tcl_...` hotspot **disappears** — a good sign.

> **If the password does not "take"** (the module does not join the network, step 5): the value of
> `--security` is the only thing not 100% certain in the reconstruction. Retry with `--security wpa12`
> (WPA1/2), then possibly `--security 4`. See "What to report" at the end.

### 4. Return to the home WiFi

Reconnect the test device to the **home network** and wait ~15–30 s for the module to
register.

### 5. Verify the module has rejoined (the key test)

```bash
python3 -m klimakontrol discover
```

If the unit **appears** (mac/ip/devtype): 🎉 **the SoftAP packet works** — that is the result that
counts for Point 2. If it does not appear, retry `discover` after a few seconds; if it stays absent,
see troubleshooting.

### 6. Verify it is controllable

For a module **you already own** (re-registered to your account):

```bash
python3 -m klimakontrol login       # refreshes the session/list
python3 -m klimakontrol list        # the unit must be there
python3 -m klimakontrol status 1    # (correct index) reads the state
python3 -m klimakontrol on 1        # tries to turn it on
python3 -m klimakontrol off 1
```

If it turns on/off: **end-to-end onboarding succeeded**. ✅

### 7. (Optional) Test `bind` for a new module

Only if you have a module **not** in your account and you know its `did/pid/mac/devkey` (e.g. from
reading the cloud or from a capture): `klimakontrol bind --did … --pid … --mac … [--name …]` (it asks
for the key). Then `list` must show it.

## Troubleshooting

| Symptom | What to do |
| --- | --- |
| `ping 192.168.10.1` fails at step 2 | You are not on the module's hotspot, or the module is not in config mode. Recheck the selected WiFi network and repeat step 1. |
| The `Broadlink_tcl_...` hotspot **does not disappear** after `provision` | The credentials were not accepted: wrong SSID, 5 GHz WiFi (use 2.4 GHz), or wrong `--security` → retry with `wpa12`/`4`. |
| `discover` does not see it | The module did not join the network (see above), or PC/phone and module are on different VLANs/segments. Retry on the same segment as the home WiFi. |
| Appears in `discover` but not in `list` | It is on the network but not (yet) in your account: wait, redo `login`; for a new module you need `bind`. |

## What to report (to close the flow and prepare the wizard)

With `KLIMAKONTROL_DEBUG=1` and/or by noting by hand:
1. **Did it work?** with which `--security` (wpa2/wpa12/…): confirms the enum mapping of the security byte.
2. The module's **raw response** to the `provision`, if any (bytes/hex) — to understand whether it
   already contains something useful (e.g. the key).
3. Whether `discover`/`list` show the did/mac/devtype of the rejoined unit.

To capture the `provision` raw response (connected to the module's hotspot):

```bash
python3 -c "from klimakontrol.provision import softap_config; \
import sys; r=softap_config(sys.argv[1], input('password WiFi: '), 3); \
print('risposta:', r.hex() if r else None)" "HOME_WIFI_NAME"
```

These three data points close the only remaining unknowns (security mapping, response shape) and
guide **Point 4 (wizard in the app)**.
