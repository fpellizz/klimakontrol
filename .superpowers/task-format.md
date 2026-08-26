# Decrypt del model-script BroadLink 0x4e2e — chiave, IV, derivazione, e formato task

Data: 2026-08-26. Obiettivo: decifrare l'asset "script" del modello (il muro `tfb`) ed
estrarre come vengono serializzate le pianificazioni/timer nel formato di byte in chiaro.

## Esito in una riga

**Decrypt riuscito.** Lo script e' **Lua SORGENTE** (leggibile, non bytecode), 27970 byte,
salvato in `/home/fpellizz/.claude/jobs/5574a44a/tmp/model-0x4e2e.lua`. **MA** questo modello
(V1.2) ha **rimosso i comandi/variabili di pianificazione**: il Lua non contiene alcuna
serializzazione di task settimanali. Contiene solo il timer "grezzo" MCU (accensione/spegnimento
a orario singolo) documentato in un commento, che pero' il Lua non popola.

---

## 1. La derivazione della chiave dello script (il pezzo che mancava)

`networkapi_scriptfile_read` @0xfb6cc ha **due percorsi** scelti in base a un numero di versione
letto dal file a offset **0x30** (uint32 little-endian), confronto con `0x3ed` (1005) a 0xfb95c:

* versione **< 1005** → percorso SEMPLICE (chiave statica in rodata + unwrap). **E' il nostro caso**
  (versione file = 1004).
* versione **>= 1005** → percorso COMPLESSO: chiave derivata a runtime da campi del contesto
  device (`ctx+0x1e7/+0x207/+0x27f`) tramite l'helper locale @0xfbd40, sotto rwlock. Dipende da
  materiale per-device che non abbiamo. (Non serve per 0x4e2e.)

### Perche' lo spike precedente falliva
Erano corretti KEY e IV, ma lo spike (a) decifrava tutto il file da offset 0/0x10 invece che
**dal corpo a offset 0x40**, e (b) **non faceva lo unwrap a due stadi** (usava la costante rodata
direttamente come chiave del corpo, mentre la chiave del corpo e' quella *sbustata*).

### Costanti statiche (rodata, byte grezzi, .rodata: vaddr==fileoffset)
```
STATIC_KEY (rodata @0x1184a1) = 3a211807bc5a93e2d42d8037e48a987a
STATIC_IV  (rodata @0x1184b1) = 562e17996d093d28ddb3ba695a2e6f58
```
Nota: `STATIC_IV` e' esattamente la "task IV" della consegna gia' **word-reversed**
(`5a2e6f58ddb3ba696d093d28562e1799` → `562e17996d093d28ddb3ba695a2e6f58`). Si usano i byte
**grezzi** cosi' come sono in rodata (nessuna ulteriore inversione: vengono `memcpy` diretti).

### Cifrario
`bl_sdk_tfb_decode` (PLT @0x1ac30 → reale @0xc56a0) = **AES-128-CBC, padding PKCS#7**, firma
`(data, len, key, iv)` — decifra in place e ritorna la lunghezza del chiaro. Confermato che e' AES
standard (dallo spike `tfb`).

### Layout del file `.script`
```
[0x00 : 0x10]  MD5(file[0x10:end])        <- checksum d'integrita' (networkapi_luen == MD5 raw)
[0x10 : 0x30]  chiave-corpo BUSTATA        <- 32B ciphertext (16B chiave + blocco pad PKCS#7)
[0x30 : 0x40]  header                       <- uint32 LE versione (=1004), resto zero
[0x40 : end ]  CORPO Lua cifrato            <- (len-0x40) byte, multiplo di 16
```

### Ricetta di decifratura (verificata, riproducibile)
```python
from klimakontrol.aes import decrypt_cbc   # firma: decrypt_cbc(data, key, iv)
import hashlib
f  = open("....2e4e....script","rb").read()
SK = bytes.fromhex("3a211807bc5a93e2d42d8037e48a987a")
IV = bytes.fromhex("562e17996d093d28ddb3ba695a2e6f58")
strip = lambda b: b[:-b[-1]] if 1 <= b[-1] <= 16 and b[-b[-1]:]==bytes([b[-1]])*b[-1] else b
assert hashlib.md5(f[0x10:]).digest() == f[:16]          # integrita' OK
content_key = strip(decrypt_cbc(f[0x10:0x30], SK, IV))    # -> 615111e4884190ca27927daa1b72b013
lua         = strip(decrypt_cbc(f[0x40:],     content_key, IV))
open("model-0x4e2e.lua","wb").write(lua)
```
`content_key` (per-file, sbustata) = `615111e4884190ca27927daa1b72b013`.
MD5 header verificato = match. Corpo = Lua sorgente valido (inizia con `------ TCL 空调...`).

---

## 2. Cosa contiene il Lua (e cosa NO)

Funzioni definite: `profile`, `tableToString`, `stringToTable`, `getCurrentStatus`,
`calcCheckBcc`, `translate`, `saveRespStatus`, `reqProc`, `resProc`, `devQuery`. **Nient'altro.**

Il modello dichiara nell'intestazione:
```
--V1.2 删除定时相关的命令和变量   (V1.2: RIMOSSI i comandi e le variabili di pianificazione)
--V1.3 添加函数  添加睡眠模式      (V1.3: aggiunte funzioni; aggiunta modalita' sleep)
--20181023
```

Ricerca esaustiva nel sorgente: **nessun** `task`, `period`, `timer`, `dev_taskadd`,
`dev_tasklist`, `periodlist`, `schedul*`. Solo il carattere `定时` (timer) compare, e **solo in
commenti**. → Questo model-script **non serializza pianificazioni**.

Il Lua fa esclusivamente `translate(dir, cmd, resp, sta)`: converte fra il JSON di controllo
standard (`{act, params, vals}`) e il frame seriale privato **0xBB** dell'MCU TCL, per i 10
parametri gia' noti: `pwr, ecomode, pwfmode, tcl_mode, tcl_mark, ac_vdir, ac_hdir, save_temp,
tcl_slp, ac_errcode`. Coincide con il set fisso di 10 di §5/trappola 4 del CLAUDE.md.

### Frame di controllo 0xBB (serializzazione reale dello stato/comando)
Costruito da `getCurrentStatus`+`reqProc`+`calcCheckBcc`. 31 byte totali (Lua 1-indexed;
sul filo `cmd[1]` = byte 0):
```
cmd[1]=0xBB  header (split AC)
cmd[2]=0x00  src addr (wifi)
cmd[3]=0x01  dst addr (MCU)
cmd[4]        func code: 0x03=set, 0x04=get
cmd[5]=0x19  data length = 25
cmd[6..30]    data area (25 byte)
cmd[31]       BCC = XOR di tutti i byte precedenti
```
Bit significativi nell'area dati (i default: `Agreement_Transfor[8] |= 0x60`):
```
cmd[8]  bit2 = pwr(accensione)      bit7 = ecomode(economia)
cmd[9]  bit6 = pwfmode(strong)      bit0-3 = tcl_mode  (1=caldo,2=deumid,3=freddo,7=vent,8=auto)
cmd[10] bit0-3 = codice temperatura (0x0f=16.0C ... 0x00=31.0C, invertito; passo 1.0C)
cmd[11] bit0-2 = tcl_mark ventola (0=auto,2=basso,3=medio,5=alto)   bit3-5(0x38)=ac_vdir(swing V, 7=on)
cmd[12] bit1(0x02) = mezzo grado (+0.5C)     bit3(0x08) = ac_hdir(swing H)
cmd[20] bit0-2 = tcl_slp sleep (0=off,1=normale,2=anziano,3=bambino)
get     = frame fisso BB 00 01 04 0F 00...00 B1 (21 byte)
```

---

## 3. Il formato timer/task: cosa sappiamo, e il gap che resta

### Timer MCU "grezzo" (documentato nel commento, NON usato dal Lua)
Il commento del layout del frame di stato **dal MCU** (righe 44-68) descrive l'area dati riportata
(`sta[]`), a partire dal 6° byte:
```
sta[6],sta[7] = versione hi/lo
sta[8]  = 主机设定模式 (modo)      sta[9]  = 风模式 (ventola)
sta[10] = 是否定时开关机  <- FLAG timer on/off abilitato
sta[11] = 风摆 (swing)             (verificato: il codice legge lo swing proprio da sta[11])
sta[12] = 定时关机小时  <- ora di SPEGNIMENTO programmato
sta[13] = 定时关机分钟  <- minuti di spegnimento
sta[14] = 定时开机小时  <- ora di ACCENSIONE programmata
sta[15] = 定时开机分钟  <- minuti di accensione
sta[16..] = 预留/故障码 (riservati + codice guasto)
```
Sul filo (frame 0xBB, byte 0-indexed; area dati inizia al byte 5 = `sta[6]`):
```
byte  9 = timer-enable        byte 11 = off-hour   byte 12 = off-min
byte 13 = on-hour             byte 14 = on-min
```
**E' un singolo timer giornaliero on-time/off-time. NON c'e' maschera giorni-settimana, NON c'e'
ripetizione, NON c'e' temp/modo per-slot.** Inoltre `getCurrentStatus` azzera l'intera area e
riempie solo modo/power/temp/ventola/swing/sleep: **il Lua non scrive mai questi byte timer**, e
`reqProc` non espone alcun parametro timer. Quindi neppure questo timer semplice e' raggiungibile
tramite il percorso cloud `sdkcontrol`→`translate` su questo firmware.

### Conclusione sul muro delle pianificazioni (aggiorna open-questions §2)
L'ipotesi "la codifica delle pianificazioni sul filo la fa lo script Lua del modello, cifrato con
tfb" e' **FALSA** per 0x4e2e: il Lua e' ora in chiaro e **non contiene** codifica di task. Le
pianificazioni settimanali dell'app BroadLink (`dev_taskadd`/`dev_tasklist`/`periodlist`) sono
quasi certamente un meccanismo **generico del task-engine BroadLink** (nel firmware/cloud), non
un fatto del model-script: il task memorizzato e' con ogni probabilita' `{orario, maschera-giorni,
azione = un normale comando di controllo}` e al momento giusto il modulo ri-emette quel comando —
lo **stesso** JSON che gia' inviamo via `sdkcontrol`. Non c'e' quindi un byte-format di task
"nascosto" dentro il pacchetto AC.

Il muro `tfb` sul model-script e' **abbattuto** (chiave/IV/derivazione sopra, riproducibili in
Python puro). Il gap residuo per le pianificazioni **non** e' piu' la decifratura Lua, ma la
**forma del pacchetto del task-engine BroadLink** (`dev_taskadd`), che e' protocollo generico e va
ricavato dal dex/APK o da una cattura — non da questo asset. Da valutare come prossimo passo, se
si vuole procedere con le pianificazioni.

---

## Artefatti
* Script decifrato: `/home/fpellizz/.claude/jobs/5574a44a/tmp/model-0x4e2e.lua` (27970 byte, Lua sorgente)
* Sorgente cifrato: `/home/fpellizz/.claude/jobs/5574a44a/tmp/0000000000000000000000002e4e0000.script`
* Il cross-check 0x507c indicato dalla consegna **non e' presente** nella dir tmp.
