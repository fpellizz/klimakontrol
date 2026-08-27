# Recipes: ADB, logs, WebView, traffic capture

Ready-to-use procedures. They assume a phone connected via USB with USB debugging enabled
(`adb devices` must list it).

---

## 1. Extract the native libraries (for the salts)

```bash
mkdir -p apk && cd apk
adb shell pm path com.ab.smartDevice | sed 's/^package://' | tr -d '\r' | \
  while read -r p; do adb pull "$p" . ; done
for a in *.apk; do unzip -o -j "$a" 'lib/*/libBLAccountEncryptAPI.so' -d . 2>/dev/null; done
python3 ../tools/extract_salts.py libBLAccountEncryptAPI.so
```

If it does not find the library, look at what is there:

```bash
for a in *.apk; do echo "== $a"; unzip -l "$a" | grep '\.so$'; done
```

---

## 2. App logs

The BroadLink SDK logs the request bodies with the prefix `Json Param:` and the control
directives with `BLCommonTools.debug`. The app sets `CONTROLLER_LOG_LEVEL=0`, so it might
be silent: you find out in ten seconds.

```bash
adb logcat -c
adb logcat | grep -iE 'Json Param|BroadLink|BLLet|dna|sdkcontrol|directive'
```

While it runs, perform in the official app the operation you are interested in (login, power on,
creating a timer).

If you need all the noise from that process:

```bash
pid=$(adb shell pidof com.ab.smartDevice | tr -d '\r')
adb logcat --pid="$pid"
```

---

## 3. Inspect the app's WebView

The control panel is a Cordova WebView, and in this app's builds it turns out to be
inspectable. With the phone connected, open `chrome://inspect` in Chrome on the PC: you should
see the air conditioner page while it is open in the app.

From the JavaScript console you can call the native bridge directly, that is, **make the app
run the command you want** and watch what it responds:

```js
// full state
cordova.exec(console.log, console.error, "BLNativeBridge", "devicecontrol",
  [DEVICE_ID, null, {act: "get", params: [], vals: []}, "dev_ctrl",
   {localTimeout: 3000, remoteTimeout: 5000, sendCount: 3}]);

// schedule list: the command whose on-the-wire encoding we're missing
cordova.exec(console.log, console.error, "BLNativeBridge", "devicecontrol",
  [DEVICE_ID, null, {}, "dev_tasklist",
   {localTimeout: 3000, remoteTimeout: 5000, sendCount: 3}]);
```

`DEVICE_ID` is given by `cordova.exec(console.log, console.error, "BLNativeBridge", "deviceinfo", [])`.

It is the most direct way to see the shape of the real responses without depending on login.

---

## 4. Capture the local UDP traffic (for the schedules)

The missing piece on the schedules closes with **one** packet. Phone and PC on the same
WiFi network as the air conditioners:

```bash
sudo tcpdump -i any -w timer.pcap 'udp port 80'
```

While it runs, create a timer from the official app. Then decrypt: you have the device's AES key
(from the cloud or from LAN authentication).

```python
from klimakontrol.local import parse_packet, decode_inner

payload = parse_packet(pacchetto_bytes, bytes.fromhex(aeskey))
print("azione:", payload[0x08])       # 1 = get, 2 = set, ? = dev_taskadd
print(decode_inner(payload))
```

The byte at offset `0x08` of the inner payload is the action code: that is what we are
looking for.

Note: the PC sees the UDP traffic only if it passes through it. On WiFi that is normally not the
case — use connection sharing from the PC to the phone, or port mirroring on the switch, or
`tcpdump` directly on the phone with PCAPdroid (which saves an exportable `.pcap`).

---

## 5. Capture the HTTPS traffic to the cloud

From the phone only: **PCAPdroid** plus the **PCAPdroid mitm** addon, filtering for the Intelligent
AC app and enabling TLS decryption. From the PC: mitmproxy with the phone passing through it and
the certificate installed.

Warning: the decrypted capture contains the login session (token and password hash). Treat it
as sensitive material and change the account password when you are done.

---

## 6. Runtime hooks with Frida (last resort for the salts)

If the `.so` does not contain the salts as strings, they are read while the app uses them. You
need frida-server on the phone (requires root or an APK repackaged with the gadget).

```bash
frida -U -n "Intelligent AC" -e '
Java.perform(() => {
  const C = Java.use("cn.com.broadlink.account.BLAccountEncryptAPI").getInstance();
  console.log("password:", C.blAccountPasswordEncrypt());
  console.log("token:   ", C.blAccountTokenEncrypt());
  console.log("body:    ", C.blAccountBodyEncrypt());
});'
```
