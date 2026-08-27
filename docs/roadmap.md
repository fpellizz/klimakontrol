# Roadmap

In order. Each step depends on the previous one, and each one closes a risk instead of adding
functions on top of an unverified foundation.

---

## Step 1 — Unblock the login — ✅ DONE (2026-08-21)

It was not the salts (already correct): it was the **companyid**, derived from `blob[16:32]` instead of from the
shared constant `blob[120:136]` (`8503b08f…`). Fix in `cloud.py`; `docs/open-questions.md` §1 and
`CLAUDE.md` §5 trap 1 updated. `login` returns a session and the list of units.

---

## Step 2 — First field test — ✅ DONE (2026-08-21)

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

Outcome: `list`, `status`, `on`/`off`, setpoint (`save_temp`) and `online` all work over the
cloud on the owner's plant (`0x4e2e`). Two hardware surprises, now folded into the code: the
setpoint is `save_temp` (not `temp`) and swing is `ac_vdir`/`ac_hdir` (not `tcl_*`) — see
`CLAUDE.md` §5 pitfall 4. Energy usage came back empty (these units do not meter power). Real
masked responses were captured; the status table in `CLAUDE.md` is kept up to date.

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

## Step 4 — Schedules — ✅ RESOLVED (2026-08-27): no native scheduler → phone-side

Tested on real hardware: these `0x4e2e` modules have **no native scheduler**. Device-side tasks
(`dev_taskadd`/`dev_tasklist`) return the current state, not the tasks (the model's Lua script has
the timer commands removed); the cloud timer API `/appfront/v1/timertask/*` is dead code; the
parameter-based "reservation" is not in this model's profile. See `docs/open-questions.md` §2.

So the Android app keeps schedules **phone-side**: `AlarmManager` fires at the set time and sends
the on/off command over the cloud (quick "in X" timers and weekly recurring ones). Verified on
real hardware, including standby/Doze. The one thing lost is running with the phone off — but
offline control does not exist on these modules anyway (the `-5`, see Step 3).

---

## Step 5 — Android app — ✅ DONE (functioning, released)

Built on top of the library (Kotlin / Jetpack Compose), compiled in CI. Login (with region/vendor
picker) and account registration, real cloud control of the air conditioners (power, temperature,
mode, fan, eco/turbo/night, swing), optimistic commands with roll-back, persistent session,
settings, bilingual IT/EN, onboarding wizard (SoftAP config), and phone-side timers (Step 4). See
`android/README.md`. The constraints below shaped it and still hold:

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
