# Config SoftAP (AP-config) — pacchetto ricostruito da `libNetworkAPI.so`

Ricostruito il 2026-08-25 dal disassembly ARM64 di `libNetworkAPI.so`
(`apk/split_config.arm64_v8a.apk`), non indovinato. È il **Punto 2** dell'onboarding di un modulo
vergine (vedi `docs/superpowers/specs/2026-08-25-onboarding-provisioning-design.md`).

## In una riga

Per dare a un modulo in modalità **SoftAP** le credenziali WiFi di casa, si manda un pacchetto di
**setup BroadLink storico**: **136 byte (0x88), in chiaro, senza AES, senza magic `5aa5`**, comando
**`0x14`**, checksum seed **`0xBEAF`** all'offset `0x20`, in **UDP a `192.168.10.1:80`** (il gateway
della SoftAP del modulo, non broadcast), inviato ripetutamente. **Non c'è il muro `tfb`** su questo
percorso.

## Catena nativa (verificata)

```
Java_..._deviceAPConfig → networkapi_device_apconfig  (parse config JSON, switch su "protocol")
   protocol == 0 / assente → networkapi_ap_config   ◀── SoftAP classico in chiaro (QUESTO)
   protocol == 1 → networkapi_ap_security_config     (FTBC/`tfb`-encrypt — il muro)
   protocol == 2 → aux_protocol_ap_config            (curve25519 — cloud-bound)
```
`networkapi_ap_config` compone il pacchetto (`bl_data_pack` con device-ptr NULL → salta
magic/devtype/MAC/id), calcola il checksum (`bl_getcsum` seed `0xBEAF`, identico a `local.checksum`)
e invia via `bl_protocol_passthrough` → `sendto` in loop a `192.168.10.1:80`.

## Layout del pacchetto (0x88 = 136 byte)

Tutto zero tranne:

| offset | campo | valore |
| --- | --- | --- |
| `0x20` (LE) | checksum | `0xBEAF + somma(byte)` mod `0x10000`, calcolato con `[0x20:0x22]` a zero |
| `0x26` | comando | `0x14` (LE `14 00`) |
| `0x44` | SSID | fino a 32 byte, zero-pad |
| `0x64` | password | fino a 32 byte, zero-pad |
| `0x84` | ssid_len | `min(len(ssid), 32)` |
| `0x85` | password_len | `min(len(password), 32)` |
| `0x86` | security | il campo `type` scritto **verbatim** (nessuna tabella nel nativo) |
| `0x87` | padding | `0x00` |

Gli offset `0x44 / 0x64 / 0x84 / 0x85 / 0x86` e il comando `0x14` coincidono col classico
`python-broadlink` `setup()`, ma qui sono **derivati da questo binario** (istruzioni
`memcpy`/`strb` in `networkapi_ap_config`).

Mapping `security` (convenzione BroadLink, **probabile** — da confermare su HW):
`0`=open, `1`=WEP, `2`=WPA1, `3`=WPA2, `4`=WPA1/2.

## Pacchetto dorato (per il test)

`ssid="TestNet"`, `password="secret12"`, `security=0` → checksum **`0xC482`** (LE `82 c4`):

```
0000000000000000000000000000000000000000000000000000000000000000
82c4000000001400000000000000000000000000000000000000000000000000
00000000546573744e6574000000000000000000000000000000000000000000
0000000073656372657431320000000000000000000000000000000000000000
0000000007080000
```
(concatenare le righe — 136 byte in tutto: 0x20 checksum `82 c4`, 0x26 cmd `14`,
0x44 "TestNet", 0x64 "secret12", 0x84 len 7, 0x85 len 8)

## Limiti onesti / cosa NON copre

- Il percorso classico **manda** le credenziali; la sua risposta UDP **non** fornisce offline
  `did/pid/mac/devkey`. Quelli si ottengono, a modulo in rete, con **discovery + auth** (già in
  `local.py`) o si confermano su HW (Punto 3). L'`ApConfigResult{devkey}` del design era ottimistico.
- Le varianti `protocol=1/2` (FTBC/`tfb`, curve25519) sono fuori portata (stesso muro dei timer).
- La destinazione è hard-coded `192.168.10.1:80`; `gatewayaddr` serve solo a EasyConfig, non qui.
