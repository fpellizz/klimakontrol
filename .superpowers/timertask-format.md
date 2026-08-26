# API cloud BroadLink per le pianificazioni (`/appfront/v1/timertask/*`)

Data: 2026-08-26. Fonte: `classes.dex`/`classes2.dex` dell'APK "Intelligent AC"
(androguard, `tools/dex_inspect.py`). Job dex: `/home/fpellizz/.claude/jobs/5574a44a/tmp/dex/`.

Obiettivo: il formato di richiesta completo dell'API cloud dei timer, cosi' da poterle
scrivere da Python senza passare dal muro Lua/`tfb` del firmware (vedi `.superpowers/task-format.md`:
su questo modello 0x4e2e il Lua **non implementa nemmeno** la pianificazione settimanale — un
motivo in piu' per credere che il percorso reale usato dall'app per questi moduli sia proprio
questo, cloud, non il device task engine nativo).

## Esito in una riga

Host **diverso** da tutto il resto (`appfront`, non `appservice`): `https://<lid>appfront.ibroadlink.com/`.
Corpo **JSON in chiaro** (nessuna cifratura AES, a differenza di login/register), passato cosi'
com'e' via `HttpPost`+`StringEntity`. Auth via **header HTTP semplici** (non nel corpo), presi per
reflection dai campi di `UserHeadParam`. L'azione del timer (`data`/`data2`) e' **la stessa forma
`{act, params, vals}`** usata da `sdkcontrol` — confermato leggendo `BLStdData` bytecode per
bytecode. **Limite importante**: la costruzione esatta del corpo JSON (`httpBody`) per add/modify/
delete/query avviene in **JavaScript**, dentro la WebView ibrida (Cordova) dell'app — quel JS non
e' nel dex e non e' stato recuperato in questo job (niente `assets/www` disponibile). Quanto segue
e' quindi: **certo** per host/path/header/plaintext/BLStdData, e **inferito per analogia forte**
(non byte-confermato) per i nomi esatti delle chiavi JSON top-level del corpo.

---

## 1. Host, path, come si arriva li'

Chi costruisce URL e corpo e li spedisce e' il bridge Cordova
`com.tcl.smartdevice.plugin.BLPluginInterfacer$DataServiceTask` (metodo `doInBackground`, dispatch
su `serviceName`). La UI web (assets, non nel dex) chiama il bridge nativo con un JSON tipo:

```json
{
  "method": "...",
  "serviceName": "timerservice",
  "interfaceName": "<suffisso path, es. appfront/v1/timertask/add>",
  "httpBody": "<stringa JSON gia' pronta, costruita in JS>",
  "filePath": null,
  "isContainTimezone": false
}
```

Quando `serviceName == "timerservice"`, il nativo chiama:

```java
// BLPluginInterfacer$DataServiceTask.timerService(interfaceName, httpBody)
url = com.tcl.smartdevice.http.data.BLApiUrls.BASE_CLOUD_TIMERURL + interfaceName;
return BLCloudTimerPrensenter.getInstance().httpRequest(context, url, httpBody, String.class);
```

`BASE_CLOUD_TIMERURL` (in `com.tcl.smartdevice.http.data.BLApiUrls`, verificato per decompilazione):

```java
public static String BASE_CLOUD_TIMERURL = "https://%sappfront.ibroadlink.com/";
```

Il `%s` e' il `lid` per-regione (sostituito in `BLApiUrls.init(...)`, stesso schema di
`ACCOUNT_SERVER_URL = "%sappservice.ibroadlink.com"` gia' usato da `cloud.py`). **Host diverso**
da quello di login/list/sdkcontrol/bind (`<lid>appservice.ibroadlink.com`): i timer cloud vivono
su un sottodominio a parte, `appfront`, non `appservice`.

I quattro path, confermati letteralmente nel pool di stringhe del dex (dentro
`cn.com.broadlink.base.BLApiUrls$APPFront`, classe SDK generica — presente nel dex ma senza
chiamanti Java diretti trovati; il bridge Cordova costruisce lo stesso suffisso a runtime da JS,
quindi i due percorsi convergono sullo stesso path):

```
POST /appfront/v1/timertask/add
POST /appfront/v1/timertask/modify
POST /appfront/v1/timertask/delete
POST /appfront/v1/timertask/query
```

(`interfaceName` passato dal JS e' quasi certamente `"appfront/v1/timertask/add"` etc., senza `/`
iniziale visto che `BASE_CLOUD_TIMERURL` termina gia' con `/`.)

---

## 2. Auth / header — plaintext, non nel corpo

`BLCloudTimerPrensenter.httpRequest` (decompilato per intero, riportato qui):

```java
public Object httpRequest(Context ctx, String url, String bodyString, Class respClass) {
    UserHeadParam h = new UserHeadParam();
    h.setLoginsession(BLUserInfoUnits.getInstance().getLoginsession());
    h.setUserid(BLUserInfoUnits.getInstance().getUserid());
    h.setLanguage(BLCommonUtils.getLanguage());
    h.setLicenseid(BLLet.getLicenseId());
    h.setFamilyid(BLFamilyManager.getInstance().getmFamilyId());
    h.setIdentity(Base64(JSON.toJSONString(h).getBytes()));   // <- si auto-include (vedi nota)
    return new HttpAccessor(ctx, 1).execute(url, h, bodyString, respClass);
}
```

`UserHeadParam extends BaseHeadParam`. `HttpAccessor(ctx, 1)` (metodo=1, POST):
`addHeaderParam` fa reflection sui campi dell'oggetto passato come "param 2" e li mette come
**header HTTP** (`mHttpRequest.setHeader(fieldName, String.valueOf(value))`) — uno per campo non
null. Quindi gli header effettivi sono, uno per riga (nome campo == nome header):

| header | valore |
| --- | --- |
| `userid` | `BLUserInfoUnits.getUserid()` (dalla sessione salvata dopo login) |
| `loginsession` | `BLUserInfoUnits.getLoginsession()` |
| `licenseid` | `BLLet.getLicenseId()` — stesso valore usato da login (blob-derivato, vedi trappola 1 in CLAUDE.md) |
| `familyid` | `BLFamilyManager.getmFamilyId()` — l'id famiglia cloud (lo stesso di `getfamilyid` usato dal bind, vedi Punto 1 onboarding) |
| `language` | locale corrente |
| `identity` | Base64(JSON dell'oggetto `UserHeadParam` stesso, **prima** di settare `identity`) — un self-hash/self-echo, non un segreto derivato |
| (da `BaseHeadParam`, ereditati) `system` | `"android"` |
| `appPlatform` | `"android"` |
| `appVersion` | `"1.0.12"` |
| `locate` | paese (da `BLCommonUtils.getCountry()`) |
| `mobileinfo` | `Build.MODEL` |
| `licenseid` | (duplicato — settato anche dal base ctor) |
| `userid` | (idem) |
| `timestamp`, `token` | **assenti/null** — `UserHeadParam()` usa il costruttore vuoto, non quello `(timestamp, token)`; niente firma HMAC-like per questa chiamata (a differenza di `privateDataService`, che invece firma con lo stesso salt `"xgx3d*fe3478$ukx"` del login — vedi sotto) |

Nessun campo `sign`/`companyid` per il timer: `UserHeadParam.setSign(...)` esiste come metodo ma
**non viene chiamato** in questo percorso (e' usato altrove, es. `privateDataService`, che firma
con `SHA1(prefix + timestamp + "broadlinkappmanage@" + licenseId)` — non serve qui, solo per
riferimento/contrasto).

### Corpo: plaintext, non AES

`HttpAccessor.access()` con `mMethod==1`: costruisce `HttpPost`, mette gli header sopra, e per il
corpo (`addBodyParam`) — dato che `bodyString` e' una `String` — fa semplicemente:

```java
((HttpPost) mHttpRequest).setEntity(new StringEntity(bodyString, "UTF-8"));
```

**Nessuna cifratura AES sul corpo.** Diverso da `/account/login` e `/account/register` (che sono
AES-CBC con gli IV/sali di trappola 2), **uguale** al bind (`/appsync/group/dev/manage`, gia'
implementato in `bind_device`): corpo JSON in chiaro, chiave/segreti (se servono) nell'header o
altrove, mai nel body cifrato. La risposta viene letta come stringa e restituita cosi' com'e' al
JS (`respClass = String.class` nel caso timer — fastjson non la deserializza lato nativo, il
parsing JSON della risposta lo fa il JS).

---

## 3. L'azione del timer: `BLStdData` == la stessa forma di `sdkcontrol`

`cn.com.broadlink.sdk.data.controller.BLStdData` (decompilato per intero):

```java
public class BLStdData implements Parcelable {
    private String act;              // default "set"
    private ArrayList params;        // lista di nomi parametro (String)
    private ArrayList vals;           // lista di liste di BLStdData$Value
}
public class BLStdData$Value implements Parcelable {
    private int idx;      // default 1
    private Object val;
}
```

Serializzato da fastjson per reflection sui getter → **esattamente**
`{"act": "set", "params": [...], "vals": [[{"val": N, "idx": 1}, ...], ...]}` — la stessa identica
forma gia' verificata per `sdkcontrol` e implementata in `cloud.py`. Conferma forte: **il payload
di comando di una pianificazione cloud si costruisce col codice gia' scritto** per il controllo
immediato, senza bisogno di reinventare nulla.

`BLTaskDataResult extends BLBaseResult` porta **due** `BLStdData`: campi `data` e `data2` — quasi
certamente "azione quando scatta il timer" e "azione di ripristino/spegnimento" (coppia on/off,
tipica dei timer BroadLink: es. accendi a un orario, spegni a un altro — oppure "azione primaria"
+ "azione di fallback"). Non c'e' testo che dica esplicitamente a cosa serva `data2`; l'ipotesi
piu' plausibile resta on/off.

---

## 4. Corpo JSON di add/modify/delete/query — CERTO vs INFERITO

**Non trovato nel dex un builder Java per il corpo di queste 4 chiamate**: la stringa `httpBody`
arriva gia' pronta dal JS della WebView (vedi §1). Quindi la forma esatta delle chiavi top-level
**non e' verificabile da questo dex**. Quello che si puo' dire con certezza dal dex, e da cui
inferire per analogia:

**Certo (dal dex):**
- l'azione del timer e' un `BLStdData` con la forma `{act,params,vals}` (§3)
- esistono 5 "famiglie" di task, con costanti intere (`cn.com.broadlink.sdk.constants.controller.BLDeviceTaskType`, decompilata per intero):
  ```java
  COMMON_TIMER_TASK = 0;   // orario assoluto — BLTimerInfo (year/month/day/hour/min/sec)
  DELAY_TIMER_TASK  = 1;   // "tra N secondi/minuti" (probabile, nome parla da solo)
  PERIOD_TIMER_TASK = 2;   // settimanale ricorrente — BLPeriodInfo (hour/min/sec + repeat=lista giorni)
  CYCLE_TIMER_TASK  = 3;   // finestra ciclica start/end — BLCycleInfo (start_hour/min/sec, end_hour/min/sec, cmd1duration, cmd2duration)
  RANDOM_TIMER_TASK = 4;   // timer random (tipico anti-intrusione)
  ```
  Corrispondenza rinforzata da `BLQueryTaskResult` (risposta del device task engine nativo, stessa
  famiglia concettuale), che ha **una lista per tipo**, nominata esattamente come il tipo:
  ```java
  class BLQueryTaskResult extends BLBaseResult {
      ArrayList timer;   // COMMON_TIMER_TASK
      ArrayList delay;   // DELAY_TIMER_TASK
      ArrayList period;  // PERIOD_TIMER_TASK
      ArrayList cycle;   // CYCLE_TIMER_TASK
      ArrayList random;  // RANDOM_TIMER_TASK
  }
  ```
- le classi dato per l'orario/ricorrenza (`cn.com.broadlink.sdk.data.controller.*`, tutte
  decompilate per intero):
  ```java
  class BLTimerInfo {   // COMMON_TIMER_TASK — orario assoluto
      int index; boolean enable;
      int year, month, day, hour, min, sec;
  }
  class BLPeriodInfo {  // PERIOD_TIMER_TASK — settimanale
      int index; boolean enable;
      List repeat;      // maschera/lista giorni della settimana (interi, come da BLPeriodInfo.setRepeat)
      int hour, min, sec;
  }
  class BLCycleInfo {   // CYCLE_TIMER_TASK — finestra ciclica
      int index; boolean enable;
      List repeat;
      int start_hour, start_min, start_sec;
      int end_hour, end_min, end_sec;
      int cmd1duration, cmd2duration;
  }
  ```

**Inferito per analogia (non byte-confermato — da verificare con `KLIMAKONTROL_DEBUG=1` /
cattura di rete sull'app reale prima di fidarsene in produzione):**

Corpo plausibile per `POST /appfront/v1/timertask/add` (un record), costruito rispecchiando 1:1 i
nomi di campo Java visti sopra (fastjson serializza per nome di campo, e questo e' lo stile usato
ovunque nell'SDK — es. `sdkcontrol` rispecchia `BLStdData` cosi' com'e'):

```json
{
  "did": "<id cloud del dispositivo>",
  "familyId": "<gia' anche in header>",
  "taskType": 2,
  "timerInfo": null,
  "periodInfo": { "index": 0, "enable": true, "repeat": [1,2,3,4,5], "hour": 7, "min": 30, "sec": 0 },
  "cycleInfo": null,
  "data": { "act": "set", "params": ["pwr"], "vals": [[{"val": 1, "idx": 1}]] },
  "data2": null
}
```

con `timerInfo`/`periodInfo`/`cycleInfo` presumibilmente mutuamente esclusivi a seconda di
`taskType`. Per `modify` probabilmente lo stesso corpo con un id/index di task esistente; per
`delete` probabilmente `{"did":..., "taskType":..., "index":...}` (o un id di task dedicato non
visto qui); per `query` probabilmente solo `{"did": "..."}` con risposta shape simile a
`BLQueryTaskResult` (liste `timer`/`delay`/`period`/`cycle`/`random`). **Questi nomi di chiave
top-level (`did`, `taskType`, `timerInfo`, `periodInfo`, `cycleInfo`, `index`) sono la parte
davvero non confermata**: vanno verificati o con l'unico modo rimasto — catturare il traffico
reale dell'app (PCAPdroid, come gia' fatto per lo swing in trappola 4) mentre si crea un timer
dalla UI, oppure recuperando ed esaminando gli asset JS della WebView (`assets/www/**/*.js`
nell'APK, non presenti in questo job).

---

## 5. Il percorso device-side (nativo, NON cloud) — per contrasto, non e' quello da usare qui

Esiste **anche** un secondo meccanismo, completamente diverso, per le pianificazioni: l'SDK nativo
`cn.com.broadlink.sdk.BLLet$Controller` espone (decompilato, solo firme — corpo nativo/JNI):

```java
static BLQueryTaskResult updateTask(did, mac, index, enable, BLTimerInfo, BLStdData action[, BLStdData action2][, BLConfigParam]);
static BLQueryTaskResult updateTask(did, mac, enable, BLPeriodInfo, BLStdData action[, BLConfigParam]);
static BLQueryTaskResult updateTask(did, mac, index, enable, BLCycleInfo, BLStdData action[, BLStdData action2][, BLConfigParam]);
static BLQueryTaskResult delTask(did, mac, index, taskType[, BLConfigParam]);
static BLQueryTaskResult queryTask(did, mac[, BLConfigParam]);
static BLTaskDataResult  queryTaskData(did, mac, index, taskType[, BLConfigParam]);
```

Usato dal bridge Cordova `BLPluginInterfacer$QueryTimerTask`/`QueryTimerDetailTask` (per la query,
non per add/modify — non ho trovato nel dex l'equivalente `AddTimerTask`/`DelTimerTask`, il che e'
coerente con l'ipotesi che add/modify per questi moduli vadano tutti dal path cloud appfront).
Questo e' il path che finisce nel pacchetto device `DEV_TASKADD`/`DEV_TASKDATA` — quello codificato
via script Lua del modello e cifrato col cifrario proprietario `tfb`, il muro gia' documentato in
`docs/open-questions.md` §2 e in `.superpowers/task-format.md`. **Non e' la priorita' di questo
documento** ne' probabilmente la via giusta per 0x4e2e: lo spike su questo modello (`task-format.md`,
2026-08-26) ha scoperto che il Lua del firmware V1.2 **non implementa affatto** la serializzazione
dei task settimanali — solo un timer MCU grezzo non popolato. Questo rende il path cloud
`/appfront/v1/timertask/*` (§1-4) l'unica via plausibile per pianificazioni funzionanti su questi
moduli, un indizio in piu' (non una prova) che l'app usi davvero quella per l'AC.

---

## 6. Prossimi passi consigliati

1. **Catturare il traffico reale** (PCAPdroid, come gia' fatto per lo swing) mentre si crea/
   modifica/cancella un timer dalla UI dell'app su un'unita' Wisnow vera — l'unico modo per
   confermare i nomi esatti delle chiavi top-level del §4.
2. In alternativa, estrarre `assets/www/**` dall'APK originale (non presente in questo job — solo
   `classes.dex`/`classes2.dex` erano disponibili) e cercare il JS che costruisce `httpBody` per
   `timerservice` (`interfaceName` contenente `timertask`).
3. Se confermato, l'implementazione in `cloud.py` puo' riusare interamente l'encoding di
   `BLStdData` gia' scritto per `sdkcontrol` (stesso `{act,params,vals}`) — nessun nuovo modello
   di comando da inventare, solo l'involucro `{did, taskType, timerInfo|periodInfo|cycleInfo, data,
   data2}` da confermare.
