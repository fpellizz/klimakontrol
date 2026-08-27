# Design — Onboarding a module from scratch (WiFi + account bind)

Date: 2026-08-25 · Status: **proposal, to be approved**

Goal: make the app **self-sufficient**. A user must be able to take an air conditioner (AC unit)
that has never been configured (or has been factory-reset), connect it to the home WiFi and register
it to their own account — **without ever opening the official app**. It is the step that turns the
project into an MVP.

This document arises from the 2026-08-25 spike (static analysis of the APK in `./apk/`), which
delivered a **verdict: feasible** — provisioning is NOT blocked by the native `tfb` cipher like the timers.
See `docs/open-questions.md` §2 for the contrast.

---

## 1. The domain model: one flow, three phases

Onboarding is a chain of three calls, then the verification:

```
  [PHASE A] SoftAP config           [PHASE B] Cloud bind          [PHASE C] Verify
  phone→module hotspot              POST /appsync/.../add          getallinfo lists
  send home SSID+pw         ──▶      {familyId, endpoints:[{        ──▶  the new unit ──▶ control
  module joins WiFi                 pid,did,mac,name,cookie}]}     (already implemented)
  ⤺ returns {did,pid,mac,devkey}    ⤺ ok
```

Salient fact that emerged from the spike: **PHASE A already returns the device's AES key**
(`BLAPConfigResult.devkey`). So the key must not be sought elsewhere: the config itself produces it,
and it is reused both for the bind (`cookie` field) and for immediate control.

As with the rest of the project: **one domain model, two transports**. PHASE A is local
(UDP to port 80 on the module's hotspot, like `local.py`); PHASE B is cloud (encrypted JSON POST,
like `cloud.py`). No new paradigm.

### Findings from the APK (key classes)

| Role | Class / symbol |
| --- | --- |
| Config input | `cn.com.broadlink.sdk.param.controller.BLDeviceConfigParam` → `ssid`, `password`, `gatewayaddr`, `version` |
| Config output | `cn.com.broadlink.sdk.result.controller.BLAPConfigResult` → `did`, `pid`, `mac`, `devkey`, `ssid` |
| Official UI | `com.tcl.smartdevice.activity.DeviceAPConfigureActivity` (SoftAP), `DeviceConfigureGuideActivity` |
| Packet construction | native `libNetworkAPI.so` (present in `apk/split_config.arm64_v8a.apk`) |
| Bind | `BLFamilyManager.addEndpoint` → `POST BLApiUrls.BASE_URL + /appsync/group/dev/manage?operation=add` |
| Bind body | `{familyId, endpoints:[BLEndpointInfo…]}`; the key goes in the **`cookie`** field (Base64), as in `sdkcontrol` |
| Hotspot SSID | prefix `Broadlink_tcl_…` (seen in the strings) |
| Security type | `wificonfigtype` (open / WPA / WPA2 → byte in the packet) |

---

## 2. Constraints (from the project principles — CLAUDE.md)

- **Zero runtime dependencies** in the library: stdlib only. AES is already in `klimakontrol/aes.py`.
- **The tests do not touch the network.** They verify packet construction, signatures, parsing. Server
  responses are simulated by replacing `_request`.
- **No constants copied by hand** from community projects: the SoftAP packet must be **derived
  from the APK** (`libNetworkAPI.so`) and covered by a **golden test**, exactly like the documented
  local packet.
- **Secrets do not end up in the logs**: `devkey` is an AES key → run it through `session.mask()` in every
  print. The `devkey` must NOT go into the debug logs in cleartext.
- Comments/docs in **Italian**, identifiers in **English**.

---

## 3. Step 1 — Bind cloud (library + CLI + offline tests) — *zero risk*

We start here because it is entirely reconstructable from the dex and testable **without HW and without network**.

### What it does
Given a device that has just been configured (`did`, `pid`, `mac`, `devkey`, + a name chosen by the user),
it associates it with the account's "family" (cloud home).

### Protocol
- **URL**: `<host>/appsync/group/dev/manage?operation=add`. `<host>` = `BLApiUrls.BASE_URL`, in
  all likelihood the same `https://<lid>appservice.ibroadlink.com` already used by `_ec4`
  (to be confirmed at the first implementation task by decompiling `BLApiUrls`).
- **Body** (JSON):
  ```json
  {
    "familyId": "<family id>",
    "endpoints": [
      { "productId": "<pid>", "endpointId": "<did>", "mac": "<mac>",
        "friendlyName": "<chosen name>", "cookie": "<Base64 of the key, like build_cookie>",
        "roomId": "", "order": 0 }
    ]
  }
  ```
  Fields from `com.tcl.smartdevice.data.BLEndpointInfo`. The minimal set must be narrowed down in
  implementation by trying the real response; `cookie` reuses the already-existing **`Cloud.build_cookie`**.
- **Encryption/headers**: like the other authenticated calls — `_common_headers` +
  (`userid`, `loginsession`, `licenseid`/`lid`). If `BLHttpPostAccessor` encrypts the body in AES like
  `_ec4`, the same path is reused; first implementation task: decompile `BLHttpPostAccessor`
  to confirm whether the body is AES or text with only headers.
- **`familyId`**: already available via `Cloud.family_ids()` (`/ec4/v1/user/getfamilyid`). MVP: bind
  to the first family; no rooms/multiple-families handling (YAGNI).

### Library surface
`cloud.py`:
```python
def bind_device(self, did, pid, mac, aeskey, name="", family_id=None, room_id="") -> Dict
```
- builds the endpoint, encrypts via the existing path, calls `_request`, returns the response
  run through `_ensure_ok`.

### CLI
`klimakontrol bind --did … --pid … --mac … --key … [--name …] [--family …]`
- prints the **masked** response (`session.mask`).

### Tests (offline)
- `test_cloud.py`: `bind_device` builds the expected body (endpoint, cookie, familyId), correct
  headers, with `_request` replaced. No network.

**Done when** the tests pass and the generated body matches the one reconstructed from the dex.

---

## 4. Step 2 — SoftAP config (library + golden test) — *reconstruction from the native*

### What it does
Sends the home WiFi credentials to the module (in configuration mode, which exposes its own
`Broadlink_tcl_…` hotspot). The module connects and responds with `did`/`pid`/`mac`/`devkey`.

### Mode: **SoftAP**, not SmartConfig
Confirmed by the spike (`DeviceAPConfigureActivity`, connection to the device's hotspot). The phone
joins the module's network (gateway like `192.168.10.1`); the app sends a UDP setup packet
to port 80 of that gateway.

### The packet (to be derived, not guessed)
**Known** BroadLink SoftAP format (SSID/password at fixed offsets + checksum for v1; v2 variant
AES-encrypted with a known fixed key) — selected by `version`/`wificonfigtype`. Unlike the
timers, **the algorithm exists**; it only needs to be confirmed for this `0x4e2e` model.

Method decision (see §7, Decision 1): **disassemble `libNetworkAPI.so`** (locally) to
derive the real offsets, version and checksum → build a byte-for-byte **golden test**, like
`test_local.py::test_matches_documented_golden_packet`. No HW capture is necessary for the format.

### Library surface
New module `klimakontrol/provision.py` (or an extension of `local.py`):
```python
def build_softap_packet(ssid, password, security, version) -> bytes   # coperto da golden test
def softap_config(ssid, password, security=..., timeout=...) -> ApConfigResult  # invio UDP + attesa
```
`ApConfigResult` = dataclass `{did, pid, mac, devkey}`.

### CLI
`klimakontrol provision --ssid … --password … [--security wpa2]`
- runs the config, prints `did/pid/mac` (key **masked**), and suggests the `bind` command.
- `--and-bind` option to chain Step 1 when the cloud session is present.

### Tests (offline)
- **Golden test** of the packet: `build_softap_packet("<ssid noto>", …)` == expected bytes (from
  the disassembly). Immutable like the other golden.
- Parsing of the module's response into `ApConfigResult` with a simulated sample.

**Done when** the golden passes and `softap_config` is tested with a simulated response.

---

## 5. Step 3 — Test on real hardware — *the invasive test*

First end-to-end test. Order (no HW until Steps 1 and 2 are green offline):

1. **Mental backup**: note the current did/pid/mac of the module you reset (from `list`), so you know what
   to re-add if needed.
2. **Factory reset** of the module → it enters configuration mode (hotspot `Broadlink_tcl_…`).
3. From an environment with Python (phone in Termux, or a PC that joins the hotspot):
   `klimakontrol provision --ssid CASA --password … --and-bind` → verify did/pid/mac/devkey.
4. `klimakontrol list` / `status` → the new unit appears and **can be controlled** (on/off, setpoint).
5. Update the status table in `CLAUDE.md` and add the real (masked) response as a fixture.

**Risks and recovery**
- If our config fails, the module remains configurable: it is recovered with the official app.
  Damage = one air conditioner without cloud for the duration of the test. **No brick.**
- Do the test on **one** unit, not on all of them.

**Done when** a reset module comes back online and controllable going only through our commands.

---

## 6. Step 4 — Onboarding wizard in the app (Compose)

Only after 1–3 work. A new step-based screen, reachable from Home ("+ Add
air conditioner") and from Settings.

### Screen flow
1. **Instructions**: "Hold down … on the air conditioner until it flashes" (it enters config).
2. **Home WiFi**: SSID field (prefilled with the current network if readable) + password
   (with the already-existing Show/Hide toggle, `PasswordField`).
3. **Connection to the module's hotspot** (see Decision 2).
4. **Progress**: "Configuring… / Registering to the account… / Done ✓" with clear states and errors
   (reuses the `invio…/✓/errore` vocabulary already in the app).
5. **Name**: the user gives the unit a name (→ `friendlyName` of the bind); then returns to Home where the
   new unit appears (the polling picks it up, or an immediate `refresh()`).

### VM integration
- `KlimaViewModel`: new state `OnboardingState` (Idle → JoiningAp → Configuring → Binding →
  Done/Error) and functions `startOnboarding`, `submitWifi`, `cancelOnboarding`.
- Reuses `CloudService` for the bind and a new Kotlin `ProvisionClient` for PHASE A (UDP), mirroring
  `provision.py`. The delicate logic (packet) is covered by the Python tests; the Kotlin replicates it
  with the same vectors.

### Scope (MVP, YAGNI)
- Bind to the **default family**, empty room. No cloud families/rooms handling.
- The local "Zones" (existing feature) remain the way to group in the app.

**Done when** from the app, on a reset module, the unit is added and controllable in ≤5 taps.

---

## 7. Open decisions (recommendation included)

**Decision 1 — how to derive the SoftAP packet.**
- **(A) Disassemble `libNetworkAPI.so`** (locally) → byte-exact golden. Rigorous, respects
  "no constants copied". More work. **← recommended.**
- (B) Reuse the public `python-broadlink` format and confirm it with a capture. Faster but
  it must still be verified against the APK, and the capture requires the invasive reset just for the sample.
- (C) Capture from the official app's traffic. Requires HW already in the reconstruction phase.

**Decision 2 — how the app joins the module's hotspot.**
- **(A) Manual connection (MVP)**: the app tells the user to select the `Broadlink_tcl_…` hotspot
  in the WiFi settings, then sends the UDP. Simple, robust on every Android version. **← recommended
  for the MVP.**
- (B) `WifiNetworkSpecifier` (API 29+) to attach in-app, with `bindProcessToNetwork` to
  send UDP over the network without internet. Smoother but fragile (permissions, a "no connectivity"
  network, fallback for API 26–28). Can be deferred to a refinement.

**Decision 3 — form of the CLI commands.** `provision` and `bind` **separate** (bind testable offline
on its own), with `provision --and-bind` for the end-to-end. **← recommended.**

---

## 8. Work sequence and definition of "done"

| # | Milestone | Network/HW | Done when |
| --- | --- | --- | --- |
| 1 | Bind cloud (lib+CLI+tests) | offline | tests green, body == dex |
| 2 | SoftAP config (lib+golden) | offline | golden green |
| 3 | Test on HW | HW | reset module becomes controllable again from our commands |
| 4 | App wizard | HW | ≤5 taps to add a unit |

Every milestone updates the status table in `CLAUDE.md`.

---

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| SoftAP packet bytes different from the public format | Derive them from the disassembly (Decision 1A) + golden test |
| `BLHttpPostAccessor` encrypts the body differently from `_ec4` | First task of Step 1: decompile it and confirm |
| Wrong `BLEndpointInfo` fields → bind rejected | Reduce to the minimal set by trying the real response; log the server message (`KLIMAKONTROL_DEBUG=1`) |
| HW test: module without cloud during the test | Recoverable with the official app; test on a single unit |
| Fragile Android WiFi API | MVP with manual connection (Decision 2A) |
| `devkey` in the logs | `session.mask()` mandatory on every print |

---

## 10. Out of scope (explicit)

- Cloud families/rooms handling, device sharing, cloud-side renaming.
- SmartConfig/EasyConfig (we use SoftAP).
- Automatic hotspot attach on all Android versions (post-MVP refinement).
- Schedules/timers (a separate project, gated by `tfb`).
