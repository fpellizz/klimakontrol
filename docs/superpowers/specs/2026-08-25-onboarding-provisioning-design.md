# Design — Onboarding di un modulo da zero (WiFi + bind account)

Data: 2026-08-25 · Stato: **proposta, da approvare**

Obiettivo: rendere l'app **autosufficiente**. Un utente deve poter prendere un climatizzatore
mai configurato (o resettato di fabbrica), collegarlo al WiFi di casa e registrarlo al proprio
account — **senza mai aprire l'app ufficiale**. È il passo che trasforma il progetto in un MVP.

Questo documento nasce dallo spike del 2026-08-25 (analisi statica dell'APK in `./apk/`), che ha
dato **verdetto: fattibile** — il provisioning NON è bloccato dal cifrario nativo `tfb` come i timer.
Vedi `docs/open-questions.md` §2 per il contrasto.

---

## 1. Il modello di dominio: un flusso, tre fasi

L'onboarding è una catena di tre chiamate, poi la verifica:

```
  [FASE A] SoftAP config            [FASE B] Bind cloud           [FASE C] Verifica
  telefono→hotspot modulo           POST /appsync/.../add          getallinfo elenca
  invia SSID+pw casa        ──▶      {familyId, endpoints:[{        ──▶  la nuova unità ──▶ controllo
  modulo entra in WiFi              pid,did,mac,name,cookie}]}     (già implementato)
  ⤺ ritorna {did,pid,mac,devkey}    ⤺ ok
```

Dato saliente emerso dallo spike: **la FASE A restituisce già la chiave AES** del dispositivo
(`BLAPConfigResult.devkey`). Quindi la chiave non va cercata altrove: la produce la config stessa,
e la si riusa sia per il bind (campo `cookie`) sia per il controllo immediato.

Come per il resto del progetto: **un solo modello di dominio, due trasporti**. La FASE A è locale
(UDP alla porta 80 sull'hotspot del modulo, come `local.py`); la FASE B è cloud (POST JSON cifrato,
come `cloud.py`). Nessun nuovo paradigma.

### Riscontri dall'APK (classi chiave)

| Ruolo | Classe / simbolo |
| --- | --- |
| Input config | `cn.com.broadlink.sdk.param.controller.BLDeviceConfigParam` → `ssid`, `password`, `gatewayaddr`, `version` |
| Output config | `cn.com.broadlink.sdk.result.controller.BLAPConfigResult` → `did`, `pid`, `mac`, `devkey`, `ssid` |
| UI ufficiale | `com.tcl.smartdevice.activity.DeviceAPConfigureActivity` (SoftAP), `DeviceConfigureGuideActivity` |
| Costruzione pacchetto | nativo `libNetworkAPI.so` (presente in `apk/split_config.arm64_v8a.apk`) |
| Bind | `BLFamilyManager.addEndpoint` → `POST BLApiUrls.BASE_URL + /appsync/group/dev/manage?operation=add` |
| Corpo bind | `{familyId, endpoints:[BLEndpointInfo…]}`; la chiave va nel campo **`cookie`** (Base64), come in `sdkcontrol` |
| SSID hotspot | prefisso `Broadlink_tcl_…` (visto nelle stringhe) |
| Tipo sicurezza | `wificonfigtype` (open / WPA / WPA2 → byte nel pacchetto) |

---

## 2. Vincoli (dai principi del progetto — CLAUDE.md)

- **Zero dipendenze a runtime** nella libreria: solo stdlib. L'AES è già in `klimakontrol/aes.py`.
- **I test non toccano la rete.** Si verificano costruzione pacchetti, firme, parsing. Le risposte
  del server si simulano sostituendo `_request`.
- **Niente costanti ricopiate a mano** da progetti community: il pacchetto SoftAP va **derivato
  dall'APK** (`libNetworkAPI.so`) e coperto da un **test dorato**, esattamente come il pacchetto
  locale documentato.
- **I segreti non finiscono nei log**: `devkey` è una chiave AES → passa da `session.mask()` in ogni
  stampa. Il `devkey` NON va nei log di debug in chiaro.
- Commenti/doc in **italiano**, identificatori in **inglese**.

---

## 3. Punto 1 — Bind cloud (libreria + CLI + test offline) — *rischio zero*

Si parte da qui perché è interamente ricostruibile dal dex e testabile **senza HW e senza rete**.

### Cosa fa
Dato un dispositivo appena configurato (`did`, `pid`, `mac`, `devkey`, + nome scelto dall'utente),
lo associa alla "famiglia" (casa cloud) dell'account.

### Protocollo
- **URL**: `<host>/appsync/group/dev/manage?operation=add`. `<host>` = `BLApiUrls.BASE_URL`, con
  ogni probabilità lo stesso `https://<lid>appservice.ibroadlink.com` già usato da `_ec4`
  (da confermare al primo task d'implementazione decompilando `BLApiUrls`).
- **Corpo** (JSON):
  ```json
  {
    "familyId": "<id famiglia>",
    "endpoints": [
      { "productId": "<pid>", "endpointId": "<did>", "mac": "<mac>",
        "friendlyName": "<nome scelto>", "cookie": "<Base64 della chiave, come build_cookie>",
        "roomId": "", "order": 0 }
    ]
  }
  ```
  Campi da `com.tcl.smartdevice.data.BLEndpointInfo`. Il set minimo va ristretto in
  implementazione provando la risposta reale; `cookie` riusa **`Cloud.build_cookie`** già esistente.
- **Cifratura/headers**: come le altre chiamate autenticate — `_common_headers` +
  (`userid`, `loginsession`, `licenseid`/`lid`). Se `BLHttpPostAccessor` cifra il corpo in AES come
  `_ec4`, si riusa lo stesso percorso; primo task d'implementazione: decompilare `BLHttpPostAccessor`
  per confermare se il body è AES o testo con soli header.
- **`familyId`**: già disponibile via `Cloud.family_ids()` (`/ec4/v1/user/getfamilyid`). MVP: bind
  alla prima famiglia; nessuna gestione stanze/famiglie multiple (YAGNI).

### Superficie in libreria
`cloud.py`:
```python
def bind_device(self, did, pid, mac, aeskey, name="", family_id=None, room_id="") -> Dict
```
- costruisce l'endpoint, cifra col percorso esistente, chiama `_request`, ritorna la risposta
  passata da `_ensure_ok`.

### CLI
`klimakontrol bind --did … --pid … --mac … --key … [--name …] [--family …]`
- stampa la risposta **mascherata** (`session.mask`).

### Test (offline)
- `test_cloud.py`: `bind_device` costruisce il corpo atteso (endpoint, cookie, familyId), header
  corretti, con `_request` sostituito. Nessuna rete.

**Fatto quando** i test passano e il corpo generato combacia con quello ricostruito dal dex.

---

## 4. Punto 2 — SoftAP config (libreria + test dorato) — *ricostruzione dal nativo*

### Cosa fa
Manda al modulo (in modalità configurazione, che espone il proprio hotspot `Broadlink_tcl_…`) le
credenziali del WiFi di casa. Il modulo si connette e risponde con `did`/`pid`/`mac`/`devkey`.

### Modalità: **SoftAP**, non SmartConfig
Confermato dallo spike (`DeviceAPConfigureActivity`, connessione all'hotspot del device). Il telefono
si unisce alla rete del modulo (gateway tipo `192.168.10.1`); l'app invia un pacchetto UDP di setup
alla porta 80 di quel gateway.

### Il pacchetto (da derivare, non da indovinare)
Formato SoftAP BroadLink **noto** (SSID/password a offset fissi + checksum per la v1; variante v2
cifrata AES con chiave fissa nota) — selezionato da `version`/`wificonfigtype`. Diversamente dai
timer, **l'algoritmo esiste**; va solo confermato per questo modello `0x4e2e`.

Decisione di metodo (vedi §7, Decisione 1): **disassemblare `libNetworkAPI.so`** (in locale) per
ricavare offset, versione e checksum reali → costruire un **test dorato** byte-per-byte, come
`test_local.py::test_matches_documented_golden_packet`. Nessuna cattura HW necessaria per il formato.

### Superficie in libreria
Nuovo modulo `klimakontrol/provision.py` (o estensione di `local.py`):
```python
def build_softap_packet(ssid, password, security, version) -> bytes   # coperto da golden test
def softap_config(ssid, password, security=..., timeout=...) -> ApConfigResult  # invio UDP + attesa
```
`ApConfigResult` = dataclass `{did, pid, mac, devkey}`.

### CLI
`klimakontrol provision --ssid … --password … [--security wpa2]`
- esegue la config, stampa `did/pid/mac` (chiave **mascherata**), e suggerisce il comando `bind`.
- opzione `--and-bind` per concatenare Punto 1 quando la sessione cloud è presente.

### Test (offline)
- **Golden test** del pacchetto: `build_softap_packet("<ssid noto>", …)` == byte attesi (dal
  disassembly). Immutabile come l'altro golden.
- Parsing della risposta del modulo in `ApConfigResult` con un campione simulato.

**Fatto quando** il golden passa e `softap_config` è testato con risposta simulata.

---

## 5. Punto 3 — Prova su hardware — *il test invasivo*

Prima prova end-to-end. Ordine (niente HW finché Punti 1 e 2 non sono verdi offline):

1. **Backup mentale**: annota did/pid/mac attuali del modulo che resetti (da `list`), così sai cosa
   riaggiungere in caso.
2. **Reset di fabbrica** del modulo → entra in modalità configurazione (hotspot `Broadlink_tcl_…`).
3. Da un ambiente con Python (telefono in Termux, o PC che si unisce all'hotspot):
   `klimakontrol provision --ssid CASA --password … --and-bind` → verifica did/pid/mac/devkey.
4. `klimakontrol list` / `status` → la nuova unità compare e **si controlla** (on/off, setpoint).
5. Aggiorna la tabella di stato in `CLAUDE.md` e aggiungi la risposta reale (mascherata) come fixture.

**Rischi e recupero**
- Se la nostra config fallisce, il modulo resta configurabile: lo si recupera con l'app ufficiale.
  Danno = un climatizzatore senza cloud per il tempo del test. **Nessun brick.**
- Fare il test su **una** unità, non su tutte.

**Fatto quando** un modulo resettato torna online e controllabile passando solo dai nostri comandi.

---

## 6. Punto 4 — Wizard di onboarding nell'app (Compose)

Solo dopo che 1–3 funzionano. Nuova schermata a passi, raggiungibile da Home ("+ Aggiungi
climatizzatore") e da Impostazioni.

### Flusso schermate
1. **Istruzioni**: "Tieni premuto … sul climatizzatore finché lampeggia" (entra in config).
2. **WiFi di casa**: campo SSID (precompilato con la rete corrente se leggibile) + password
   (con il toggle Mostra/Nascondi già esistente, `PasswordField`).
3. **Connessione all'hotspot del modulo** (vedi Decisione 2).
4. **Progresso**: "Configuro… / Registro all'account… / Fatto ✓" con stati ed errori chiari
   (riusa il vocabolario `invio…/✓/errore` già in app).
5. **Nome**: l'utente dà un nome all'unità (→ `friendlyName` del bind); poi torna in Home dove la
   nuova unità appare (il polling la prende, o `refresh()` immediato).

### Integrazione VM
- `KlimaViewModel`: nuovo stato `OnboardingState` (Idle → JoiningAp → Configuring → Binding →
  Done/Error) e funzioni `startOnboarding`, `submitWifi`, `cancelOnboarding`.
- Riusa `CloudService` per il bind e un nuovo `ProvisionClient` Kotlin per la FASE A (UDP), speculare
  a `provision.py`. La logica delicata (pacchetto) è coperta dai test Python; il Kotlin la replica
  con gli stessi vettori.

### Portata (MVP, YAGNI)
- Bind alla **famiglia predefinita**, stanza vuota. Niente gestione famiglie/stanze cloud.
- Le "Zone" locali (feature esistente) restano il modo di raggruppare in app.

**Fatto quando** dall'app, su un modulo resettato, in ≤5 tocchi l'unità è aggiunta e controllabile.

---

## 7. Decisioni aperte (raccomandazione inclusa)

**Decisione 1 — come ricavare il pacchetto SoftAP.**
- **(A) Disassemblare `libNetworkAPI.so`** (in locale) → golden byte-esatto. Rigoroso, rispetta
  "niente costanti ricopiate". Più lavoro. **← raccomandata.**
- (B) Riusare il formato pubblico `python-broadlink` e confermarlo con una cattura. Più veloce ma
  va comunque verificato sull'APK, e la cattura richiede il reset invasivo solo per il campione.
- (C) Catturare dal traffico dell'app ufficiale. Richiede HW già in fase di ricostruzione.

**Decisione 2 — come l'app si unisce all'hotspot del modulo.**
- **(A) Connessione manuale (MVP)**: l'app dice all'utente di scegliere l'hotspot `Broadlink_tcl_…`
  nelle impostazioni WiFi, poi manda l'UDP. Semplice, robusta su ogni versione Android. **← raccomandata
  per l'MVP.**
- (B) `WifiNetworkSpecifier` (API 29+) per agganciarsi in-app, con `bindProcessToNetwork` per
  mandare UDP sulla rete senza internet. Più fluida ma fragile (permessi, rete "senza connettività",
  fallback per API 26–28). Rimandabile a una rifinitura.

**Decisione 3 — forma dei comandi CLI.** `provision` e `bind` **separati** (bind testabile offline
da solo), con `provision --and-bind` per l'end-to-end. **← raccomandata.**

---

## 8. Sequenza di lavoro e definizione di "fatto"

| # | Milestone | Rete/HW | Fatto quando |
| --- | --- | --- | --- |
| 1 | Bind cloud (lib+CLI+test) | offline | test verdi, corpo == dex |
| 2 | SoftAP config (lib+golden) | offline | golden verde |
| 3 | Prova su HW | HW | modulo resettato torna controllabile dai nostri comandi |
| 4 | Wizard app | HW | ≤5 tocchi per aggiungere un'unità |

Ogni milestone aggiorna la tabella di stato in `CLAUDE.md`.

---

## 9. Rischi e mitigazioni

| Rischio | Mitigazione |
| --- | --- |
| Byte del pacchetto SoftAP diversi dal formato pubblico | Derivarli dal disassembly (Decisione 1A) + golden test |
| `BLHttpPostAccessor` cifra il body diversamente da `_ec4` | Primo task del Punto 1: decompilarlo e confermare |
| Campi `BLEndpointInfo` sbagliati → bind rifiutato | Ridurre al set minimo provando la risposta reale; log del messaggio server (`KLIMAKONTROL_DEBUG=1`) |
| Test HW: modulo senza cloud durante la prova | Recuperabile con app ufficiale; provare su una sola unità |
| Android WiFi API fragile | MVP con connessione manuale (Decisione 2A) |
| `devkey` nei log | `session.mask()` obbligatorio su ogni stampa |

---

## 10. Fuori portata (esplicito)

- Gestione famiglie/stanze cloud, condivisione dispositivi, rinomina lato cloud.
- SmartConfig/EasyConfig (usiamo SoftAP).
- Aggancio automatico all'hotspot su tutte le versioni Android (rifinitura post-MVP).
- Pianificazioni/timer (progetto a parte, gated dal `tfb`).
