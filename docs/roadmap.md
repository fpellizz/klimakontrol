# Roadmap

In order. Each step depends on the previous one, and each one closes a risk instead of adding
functions on top of an unverified foundation.

---

## Step 1 — Unblock the login — ✅ DONE (2026-08-21)

It was not the salts (already correct): it was the **companyid**, derived from `blob[16:32]` instead of from the
shared constant `blob[120:136]` (`8503b08f…`). Fix in `cloud.py`; `docs/open-questions.md` §1 and
`CLAUDE.md` §5 trap 1 updated. `login` returns a session and the list of units.

---

## Step 2 — First field test

```bash
python3 -m klimakontrol list
python3 -m klimakontrol status 1
python3 -m klimakontrol raw 1              # save the output in docs/samples/
python3 -m klimakontrol on 1
python3 -m klimakontrol set 1 temp=23 tcl_mode=freddo
python3 -m klimakontrol off 1
python3 -m klimakontrol online
python3 -m klimakontrol energy 1 day
```

What can break, in order of probability: the shape of the responses (§5), `devicetypeflag`
(§3), the field names in the energy usage history.

**Done when** an air conditioner turns on from a command given away from home.

Then: update the status table in `CLAUDE.md`, add tests on the real (masked) responses
as fixtures.

---

## Step 3 — The local path — ⚠️ discovery only; control not supported

Outcome of the test on real hardware (2026-08-21):

* `discover` **works**: it finds the modules in broadcast.
* The **LAN auth** establishes a session, but **local control (`0x6a`) gives `-5`** — with the real
  cloud key and the right id. Causes ruled out one by one (key, id, port, password, shape).
* A **capture of the app's traffic** (PCAPdroid) closed the question: the app **never sends**
  local control to these modules; on the LAN it only does discovery, and every command goes to the **cloud**
  (`47.254.182.109`). On this `0x4e2e` firmware pure LAN control is **not supported**.

Conclusion: for these units control is **cloud** (it works). The local path remains useful only for
discovery. Reopen only if offline is truly needed: the native `SDKAuth` would have to be replicated (login +
`packageName` + license), which perhaps authorizes the controller — see `CLAUDE.md` §5 trap 4.

---

## Step 4 — Writable schedules

`docs/open-questions.md` §2. A UDP capture is needed while the official app creates a timer.

**Done when** a weekly program written from here is executed by the module with the
phone off.

It is the function the official app gets most conspicuously wrong: doing it well is the reason
this project exists.

---

## Step 5 — Android app

On top of the library, not in its place. Known constraints:

* the development machine does not have the Android SDK → **compile in CI** (GitHub Actions),
  the APK is downloaded from the artifacts and installed by hand;
* remote control goes through the cloud, local control via UDP: in the app the same
  automatic transport as the CLI is kept;
* a single screen for the multi-split, with `querystate` giving all the units in one call;
* show only the commands the model supports (`if_function`, §4).

Note on the Kotlin rewrite: the protocol is all in `klimakontrol/`, and the delicate logic
(checksum, timezone conversion, directives) is covered by the tests. Port those tests together with the
code, not afterwards.

---

## Ideas, after everything else works

* Home Assistant integration (`RazvanManolache/home-assistant-tcl-intelligent-ac-local` already exists
  for the local part: one can contribute there instead of duplicating).
* History on a local file, to have time series independent of the cloud.
* Open the `.script` files in the assets: they would give the limits and capabilities per model.
* Diagnostics: coil and supply temperatures, compressor frequency and current are already
  readable. With a few weeks of history you can see the drops in performance before they become faults.
