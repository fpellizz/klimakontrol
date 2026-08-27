# SoftAP config (AP-config) — packet reconstructed from `libNetworkAPI.so`

Reconstructed on 2026-08-25 from the ARM64 disassembly of `libNetworkAPI.so`
(`apk/split_config.arm64_v8a.apk`), not guessed. It is **Point 2** of onboarding a virgin
module (see `docs/superpowers/specs/2026-08-25-onboarding-provisioning-design.md`).

## In one line

To give a module in **SoftAP** mode the home WiFi credentials, you send a **historic BroadLink
setup** packet: **136 bytes (0x88), in cleartext, no AES, no `5aa5` magic**, command
**`0x14`**, checksum seed **`0xBEAF`** at offset `0x20`, over **UDP to `192.168.10.1:80`** (the
gateway of the module's SoftAP, not broadcast), sent repeatedly. **There is no `tfb` wall** on
this path.

## Native chain (verified)

```
Java_..._deviceAPConfig → networkapi_device_apconfig  (parse config JSON, switch su "protocol")
   protocol == 0 / assente → networkapi_ap_config   ◀── SoftAP classico in chiaro (QUESTO)
   protocol == 1 → networkapi_ap_security_config     (FTBC/`tfb`-encrypt — il muro)
   protocol == 2 → aux_protocol_ap_config            (curve25519 — cloud-bound)
```
`networkapi_ap_config` builds the packet (`bl_data_pack` with device-ptr NULL → skips
magic/devtype/MAC/id), computes the checksum (`bl_getcsum` seed `0xBEAF`, identical to `local.checksum`)
and sends via `bl_protocol_passthrough` → `sendto` in a loop to `192.168.10.1:80`.

## Packet layout (0x88 = 136 bytes)

All zero except:

| offset | field | value |
| --- | --- | --- |
| `0x20` (LE) | checksum | `0xBEAF + sum(bytes)` mod `0x10000`, computed with `[0x20:0x22]` at zero |
| `0x26` | command | `0x14` (LE `14 00`) |
| `0x44` | SSID | up to 32 bytes, zero-pad |
| `0x64` | password | up to 32 bytes, zero-pad |
| `0x84` | ssid_len | `min(len(ssid), 32)` |
| `0x85` | password_len | `min(len(password), 32)` |
| `0x86` | security | the `type` field written **verbatim** (no table in the native code) |
| `0x87` | padding | `0x00` |

The offsets `0x44 / 0x64 / 0x84 / 0x85 / 0x86` and the command `0x14` match the classic
`python-broadlink` `setup()`, but here they are **derived from this binary** (`memcpy`/`strb`
instructions in `networkapi_ap_config`).

`security` mapping (BroadLink convention, **probable** — to be confirmed on real hardware):
`0`=open, `1`=WEP, `2`=WPA1, `3`=WPA2, `4`=WPA1/2.

## Golden packet (for the test)

`ssid="TestNet"`, `password="secret12"`, `security=0` → checksum **`0xC482`** (LE `82 c4`):

```
0000000000000000000000000000000000000000000000000000000000000000
82c4000000001400000000000000000000000000000000000000000000000000
00000000546573744e6574000000000000000000000000000000000000000000
0000000073656372657431320000000000000000000000000000000000000000
0000000007080000
```
(concatenate the lines — 136 bytes in total: 0x20 checksum `82 c4`, 0x26 cmd `14`,
0x44 "TestNet", 0x64 "secret12", 0x84 len 7, 0x85 len 8)

## Honest limits / what it does NOT cover

- The classic path **sends** the credentials; its UDP response does **not** provide
  `did/pid/mac/devkey` offline. Those are obtained, once the module is on the network, with
  **discovery + auth** (already in `local.py`) or confirmed on real hardware (Point 3). The design's
  `ApConfigResult{devkey}` was optimistic.
- The `protocol=1/2` variants (FTBC/`tfb`, curve25519) are out of reach (same wall as the timers).
- The destination is hard-coded `192.168.10.1:80`; `gatewayaddr` is only needed for EasyConfig, not here.
