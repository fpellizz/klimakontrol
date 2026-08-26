# Cifrario `tfb` di BroadLink — spec ricostruita da `libNetworkAPI.so`

Analisi statica (ARM64, capstone) di `libNetworkAPI.so` (BuildID `405cf86a…`, NDK r16b,
fork BroadLink di mbedTLS/PolarSSL). Obiettivo: capire se il cifrario proprietario `tfb`,
usato per cifrare sul filo i pacchetti di pianificazione/timer, è riproducibile in Python puro.

**Verdetto in una riga: `tfb` NON è un cifrario proprietario. È AES-128-CBC standard.**
Il muro crittografico non esiste; resta da decodificare il *plaintext* del task (prodotto dallo
script Lua del modello), che però ora è attaccabile per decifratura, non più per crittanalisi.

---

## 1. Identità del cifrario — AES (Rijndael, blocco 128 bit). Confidenza: CERTA

### 1.1 Key schedule (`broadlink_tfb_setkey_enc` @0x30a00)
È il key expansion di AES, byte per byte:
- Seleziona i round dalla lunghezza chiave: `keybits-0x80`→10 round, `-0xc0`→12, `-0x100`→14
  (cioè 128/192/256 bit → 10/12/14 round: la firma inconfondibile di AES).
- Il corpo fa SubWord (lookup S-box byte per byte) + RotWord + XOR con Rcon lungo la catena
  delle parole della round-key. Struttura identica a `mbedtls_aes_setkey_enc`.
- Chiama `aes_gen_tables` (@0x31100) alla prima esecuzione (guardata da un flag in .bss).

### 1.2 Generazione tabelle (`aes_gen_tables` @0x31100) — prova definitiva delle costanti AES
- **Log/exp in GF(2^8)** con generatore **3** e riduzione modulare XOR **0x1b** (il polinomio
  AES x^8+x^4+x^3+x+1 = 0x11B, byte basso 0x1B). Il pattern `if (x & 0x80) x ^= 0x1b; x <<= 1`
  è `xtime`.
- **Rcon[10]** costruito con xtime a partire da 1 (identico a mbedTLS), memorizzato in .bss.
- **S-box** (`FSb[0]=0x63`) costruita con la trasformazione affine di AES:
  `y = x ^ ROTL(x,1) ^ ROTL(x,2) ^ ROTL(x,3) ^ ROTL(x,4) ^ 0x63` (le `(x>>7)|(x<<1)` ripetute
  con accumulo XOR e la costante finale **0x63**).

Le costanti `0x1b` e `0x63` con generatore `3` producono **la S-box AES standard** (`63 7c 77
7b f2 6b 6f c5 …`). Non c'è variante Rijndael: polinomio e costante affine sono quelli canonici.

### 1.3 Block encrypt (`broadlink_tfb_encrypt` @0x31b9c)
AES "fast" a 4 T-table: usa Te0..Te3 (256×4 byte ciascuna) + S-box per l'ultimo round.
Indirizzi tabelle (adrp+add): Te0 @0x14cfbc, Te1 @0x14d3bc, Te2 @0x14d7bc, Te3 @0x14dbbc;
S-box (FSb) @0x14bebc, Rcon @0x14be94, flag-init @0x14be90.

**Nota importante sulle tabelle:** questi indirizzi cadono in **.bss** (inizia @0x14be28,
NOBITS). Le tabelle **non sono in rodata**: sono **generate a runtime** da `aes_gen_tables`
(mbedTLS senza `MBEDTLS_AES_ROM_TABLES`). Perciò non si possono estrarre dal file .so, ma la
loro identità è provata dal codice di generazione (§1.2), non serve leggerne i byte.

### 1.4 Modi disponibili
- `broadlink_tfb_crypt_cdf` @0x33694 — **blocco singolo (ECB)**: mode==1 → `broadlink_tfb_encrypt`,
  altrimenti `broadlink_tfb_decrypt`.
- `broadlink_tfb_crypt_fef` @0x336f4 — **CBC**, costruito su `crypt_cdf`. Attenzione ai nomi
  invertiti: chiamato con `mode=1` fa **CBC-encrypt** (`C_i = AES_enc(P_i XOR IV); IV=C_i`);
  con `mode=0` fa CBC-decrypt. Firma: `crypt_fef(ctx, mode, len, iv, src, dst)`.
- Esistono anche `_cfb128`, `_cfb8`, `_ctr` — non usati dal packing dei task.

Conclusione: **`tfb` = AES-128** (i setkey usano sempre 0x80 = 128 bit nei percorsi di packing),
modalità **CBC** (`fef`).

---

## 2. Chiave, IV e modalità per i pacchetti di pianificazione

Due punti d'ingresso, entrambi `init → setkey_enc(…, 0x80) → crypt_fef(mode=1) → free`
(AES-128-CBC encrypt):

### 2.1 `bl_data_tfb_encrypt(data, len, key)` @0xc3ef4 — usato da `bl_tfb_data_pack`
- **AES-128** (`setkey_enc` con `0x80`).
- **Chiave = argomento del chiamante** (`data_pack` gliela passa, vedi §2.3).
- **IV = costante fissa in rodata** `@0x1144be` = `5a2e6f58ddb3ba696d093d28562e1799`,
  **riordinata a parole di 4 byte** (word-reversal: `b[12:16]+b[8:12]+b[4:8]+b[0:4]`) →
  **IV effettivo = `562e17996d093d28ddb3ba695a2e6f58`**.
- **Padding = ZeroBytePadding**: la lunghezza viene arrotondata a multiplo di 16
  (`(len+0xf) & ~0xf`) senza riempimento esplicito (il buffer va azzerato prima). Stesso schema
  del login (`AES/CBC/ZeroBytePadding` in CLAUDE.md).

### 2.2 `bl_sdk_tfb_encode(data, len, cap, key, iv)` @0xc5530 — entry generica dell'SDK
- **AES-128-CBC**, **chiave e IV entrambi forniti dal chiamante**.
- **Padding = PKCS#7** (aggiunge sempre un blocco: `padlen = 16 - (len & 0xf)`, byte di valore
  `padlen`). La controparte `bl_sdk_tfb_decode` (@PLT `0x1ac30`) è quella usata anche per
  decifrare gli script del modello (§4).

### 2.3 Da dove viene la CHIAVE (`bl_tfb_data_pack` @0xc4650)
La chiave a 16 byte passata a `bl_data_tfb_encrypt` è scelta tra **tre fonti** in base ai campi
della struct dispositivo `x5`:

1. **Chiave per-dispositivo** — se `[x5+0x76] != 0`: 16 byte da `x5+0x7a`. **Ipotesi forte:
   è la `aeskey` del dispositivo** (la stessa che il progetto già ottiene dal cloud
   `getallinfo` e usa per i pacchetti `0x6a` in `local.py`).
2. **Derivata dall'identità** — se `[x5+0x76]==0` e `[x5+0x1cd]==0x459a9a45`: la calcola
   `bl_tfb_pre_set` (@0xc42f0) con `snprintf` in formato `"%u%02x%02x…"` sui byte MAC
   (`x5+0x20..0x25`) e su campi product-id (`x5+0x1d5`, `x5+0x1d9`), poi `networkapi_luen`.
   È un KDF su identità dispositivo (fallback per moduli senza chiave provisionata).
3. **Costante fissa** — altrimenti: costante rodata `@0x114128` =
   `accf8b02765c15133fe99e2309762834`, **word-reversed** → `097628343fe99e23765c1513accf8b02`.

Per i moduli del progetto (0x4e2e, controllati via cloud, con `aeskey` per-dispositivo nota) il
caso atteso è **(1): chiave = AES key del dispositivo**. Da confermare su HW/log.

### 2.4 Materiale rodata trovato (contesto @0x114100)
Regione di IV/chiavi AES (little-endian dump):
```
0x114108  eaa47a3aeb0822a21918c5d71d3615aa   IV (variante licenza)
0x114118  eaaaaa3abb5862a21918b5771d1615aa   << IV DI LOGIN noto (CLAUDE.md) — conferma il tipo
0x114128  accf8b02765c15133fe99e2309762834   KEY costante di fallback (§2.3.3)
0x1144be  5a2e6f58ddb3ba696d093d28562e1799   IV costante dei task (§2.1)
```
Il match con l'IV di login `eaaaaa3a…` (già verificato nel progetto) prova che questa regione è
proprio materiale AES IV/chiave e che l'analisi è agganciata a valori reali.

---

## 3. Struttura del pacchetto di pianificazione (`bl_tfb_data_pack` @0xc4650)

`bl_tfb_data_pack(out, payload, payload_len, u16_a, u16_b, dev_struct, off)`:
- Il pacchetto restituito è un **pacchetto BroadLink DNA standard**, identico per magia e
  checksum a quello di `local.py`:
  - **Magic** a inizio pacchetto interno: pattern **`5aa5`** ripetuto (`…5aa5aa55…`, con
    byteswap secondo endianness).
  - **Checksum** = `bl_getcsum` (@0xc3bf8): **seed `0xBEAF`**, somma dei byte mod 0x10000 —
    **esattamente** lo stesso di `local.py` (checksum seed 0xBEAF @0x20).
- Header a 16 bit copiati con gestione endianness a offset del pacchetto interno `+0x24/+0x26/
  +0x28`; comando/id a `+0x20`; i campi `u16_a/u16_b` finiscono negli offset di intestazione.
- Il **payload cifrato tfb** viene scritto a `body+8` (= out+0x8c circa) da `bl_data_tfb_encrypt`,
  poi si ricalcola il checksum sul pacchetto. La lunghezza totale = `enc_len + 0x38 + 0x54`.

In sintesi: **stesso involucro DNA di `local.py`** (magic 5aa5, checksum 0xBEAF), con il payload
del task cifrato in **AES-128-CBC** invece del solito comando JSON. Il comando/tipo esatto del
pacchetto timer (byte a `+0x20`, da confrontare con `0x6a` controllo / `0x14` apconfig) va
letto da una cattura reale o dal chiamante JNI.

---

## 4. Il vero blocco residuo: il PLAINTEXT del task (livello Lua)

`networkapi_scriptfile_read` (@0xfb6cc): legge da disco un file (`fopen/fread`) e lo passa a
**`bl_sdk_tfb_decode`** → cioè **gli script del modello sono blob AES-CBC (tfb) cifrati**. La
libreria include un **interprete Lua 5.3** (stringhe `…/lua/5.3/?.lua`) e simboli come
`dev_tasklist`, `dev_taskadd`, `periodlist`, `set_timer_cb`.

Quindi la serializzazione byte del task (byte di azione, orari in UTC+8, maschera di ripetizione)
la produce lo **script Lua del modello**, non questo codice nativo. Questo — non il cifrario — è
ciò che resta da ricavare.

**Ma ora è un problema di decodifica, non di crittanalisi**, perché conosciamo il cifrario:
- **Via A (script):** procurarsi il file script del modello (scaricato dal cloud/asset) e
  decifrarlo con AES-CBC (`bl_sdk_tfb_decode` = AES-128-CBC, PKCS#7). Rivela direttamente il Lua
  che serializza i task.
- **Via B (cattura):** catturare un pacchetto timer reale creato dall'app e **decifrare il
  payload** con AES-128-CBC + chiave dispositivo + IV fisso `562e1799…` (o la costante/KDF).
  Poi reverse del formato plaintext, che sarà piccolo e regolare (orari, giorni, azione).

---

## 5. Verdetto di fattibilità (Python puro, solo stdlib)

| Aspetto | Esito |
| --- | --- |
| Cifrario `tfb` | **AES-128-CBC standard** — riproducibile SUBITO riusando `klimakontrol/aes.py` |
| Modalità/padding (percorso task) | CBC, ZeroBytePadding (o PKCS#7 per l'entry `sdk_encode`) |
| IV task | **noto**: costante `5a2e6f58…` word-reversed = `562e17996d093d28ddb3ba695a2e6f58` |
| Chiave task | **quasi certamente la `aeskey` del dispositivo** (già in possesso del progetto); fallback: costante `097628343fe99e23765c1513accf8b02` o KDF su MAC/PID |
| Involucro pacchetto | DNA standard (magic 5aa5, checksum 0xBEAF) — già implementato in `local.py` |
| **Manca** | il **formato plaintext del task** (prodotto dal Lua del modello) — recuperabile decifrando lo script del modello **oppure** una cattura reale (ora possibile: cifrario noto) |

**Conclusione.** Il "muro `tfb`" **cade**: non è un cifrario proprietario ma AES-128-CBC, del
tutto riproducibile in Python puro con il codice AES già presente. L'unico ostacolo residuo per
scrivere le pianificazioni è il **layout del plaintext del task** (serializzazione Lua del
modello), che non richiede più di rompere crittografia: basta **decifrare** lo script del modello
o un pacchetto timer catturato con la chiave del dispositivo e l'IV qui documentato, e reverse
del (piccolo) formato binario. Rischio downgrade da "irrisolvibile" a "decodifica di un formato
osservabile".

### Costanti chiave (hex)
```
S-box gen:      poly 0x1b, affine 0x63, generatore 3         (= AES standard)
IV task  (rodata 0x1144be): 5a2e6f58ddb3ba696d093d28562e1799
IV task effettivo (word-rev): 562e17996d093d28ddb3ba695a2e6f58
KEY fallback (rodata 0x114128): accf8b02765c15133fe99e2309762834
KEY fallback effettiva (word-rev): 097628343fe99e23765c1513accf8b02
IV login (conferma, rodata 0x114118): eaaaaa3abb5862a21918b5771d1615aa
checksum seed: 0xBEAF (bl_getcsum @0xc3bf8) — identico a local.py
```
