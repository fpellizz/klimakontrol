# Ricette: ADB, log, WebView, cattura del traffico

Procedure pronte. Presuppongono telefono collegato via USB con il debug USB attivo
(`adb devices` deve elencarlo).

---

## 1. Estrarre le librerie native (per i sali)

```bash
mkdir -p apk && cd apk
adb shell pm path com.ab.smartDevice | sed 's/^package://' | tr -d '\r' | \
  while read -r p; do adb pull "$p" . ; done
for a in *.apk; do unzip -o -j "$a" 'lib/*/libBLAccountEncryptAPI.so' -d . 2>/dev/null; done
python3 ../tools/extract_salts.py libBLAccountEncryptAPI.so
```

Se non trova la libreria, guarda cosa c'è:

```bash
for a in *.apk; do echo "== $a"; unzip -l "$a" | grep '\.so$'; done
```

---

## 2. Log dell'app

L'SDK BroadLink logga i corpi delle richieste con il prefisso `Json Param:` e le direttive di
controllo con `BLCommonTools.debug`. L'app imposta `CONTROLLER_LOG_LEVEL=0`, quindi potrebbe
essere muto: si scopre in dieci secondi.

```bash
adb logcat -c
adb logcat | grep -iE 'Json Param|BroadLink|BLLet|dna|sdkcontrol|directive'
```

Mentre gira, fai dall'app ufficiale l'operazione che ti interessa (login, accensione, creazione
di un timer).

Se serve tutto il rumore di quel processo:

```bash
pid=$(adb shell pidof com.ab.smartDevice | tr -d '\r')
adb logcat --pid="$pid"
```

---

## 3. Ispezionare la WebView dell'app

Il pannello di controllo è una WebView Cordova, e nelle build di questa app risulta
ispezionabile. Con il telefono collegato, apri `chrome://inspect` su Chrome nel PC: dovresti
vedere la pagina del climatizzatore mentre è aperta nell'app.

Dalla console JavaScript si può chiamare direttamente il ponte nativo, cioè **far fare all'app
il comando che vuoi** e guardare cosa risponde:

```js
// stato completo
cordova.exec(console.log, console.error, "BLNativeBridge", "devicecontrol",
  [DEVICE_ID, null, {act: "get", params: [], vals: []}, "dev_ctrl",
   {localTimeout: 3000, remoteTimeout: 5000, sendCount: 3}]);

// elenco pianificazioni: e' il comando la cui codifica sul filo ci manca
cordova.exec(console.log, console.error, "BLNativeBridge", "devicecontrol",
  [DEVICE_ID, null, {}, "dev_tasklist",
   {localTimeout: 3000, remoteTimeout: 5000, sendCount: 3}]);
```

`DEVICE_ID` lo dà `cordova.exec(console.log, console.error, "BLNativeBridge", "deviceinfo", [])`.

È il modo più diretto per vedere la forma delle risposte reali senza dipendere dal login.

---

## 4. Catturare il traffico UDP locale (per le pianificazioni)

Il pezzo che manca sulle pianificazioni si chiude con **un** pacchetto. Telefono e PC sulla
stessa rete WiFi dei climatizzatori:

```bash
sudo tcpdump -i any -w timer.pcap 'udp port 80'
```

Mentre gira, crea un timer dall'app ufficiale. Poi decifra: la chiave AES del dispositivo la hai
(dal cloud o dall'autenticazione LAN).

```python
from klimakontrol.local import parse_packet, decode_inner

payload = parse_packet(pacchetto_bytes, bytes.fromhex(aeskey))
print("azione:", payload[0x08])       # 1 = get, 2 = set, ? = dev_taskadd
print(decode_inner(payload))
```

Il byte all'offset `0x08` del payload interno è il codice dell'azione: è quello che stiamo
cercando.

Nota: il PC vede il traffico UDP solo se passa da lui. Su WiFi normalmente non è così — usa la
condivisione della connessione dal PC verso il telefono, oppure il mirroring sulla porta dello
switch, oppure `tcpdump` direttamente sul telefono con PCAPdroid (che salva un `.pcap`
esportabile).

---

## 5. Catturare il traffico HTTPS verso il cloud

Solo dal telefono: **PCAPdroid** più l'addon **PCAPdroid mitm**, filtrando per l'app Intelligent
AC e attivando la decrittazione TLS. Da PC: mitmproxy con il telefono che ci passa attraverso e
il certificato installato.

Attenzione: la cattura decifrata contiene la sessione di login (token e hash della password).
Trattala come materiale sensibile e cambia la password dell'account quando hai finito.

---

## 6. Hook a runtime con Frida (ultima spiaggia per i sali)

Se il `.so` non contiene i sali come stringhe, si leggono mentre l'app li usa. Serve
frida-server sul telefono (richiede root o un APK ripacchettizzato con il gadget).

```bash
frida -U -n "Intelligent AC" -e '
Java.perform(() => {
  const C = Java.use("cn.com.broadlink.account.BLAccountEncryptAPI").getInstance();
  console.log("password:", C.blAccountPasswordEncrypt());
  console.log("token:   ", C.blAccountTokenEncrypt());
  console.log("body:    ", C.blAccountBodyEncrypt());
});'
```
