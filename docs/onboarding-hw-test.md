# Prova su hardware — onboarding di un modulo (Punto 3)

Runbook per provare **end-to-end** l'onboarding ricostruito offline: config **SoftAP** (Punto 2) +
bind (Punto 1). Serve un intervento fisico (reset di un modulo). **Nessun rischio di brick**: se la
nostra procedura fallisce, il modulo si recupera con l'app ufficiale.

## Criterio di successo

Il test è **riuscito** quando: un modulo resettato, ricevute le credenziali WiFi **dai nostri
comandi**, **rientra in rete** ed è di nuovo **controllabile**. Il segnale minimo e più importante è
che `klimakontrol discover` lo **riveda in LAN** dopo il `provision`: quello valida il pacchetto SoftAP.

## Consiglio di sicurezza: usa un modulo che possiedi già

Fai il test su **una** delle tue unità già configurate. Vantaggi:
- **Recupero automatico**: rientrando nel WiFi si **ri-registra al tuo stesso account**, quindi
  ricompare in `klimakontrol list` (con la sua chiave dal cloud) senza bisogno di `bind`.
- Se qualcosa va storto, la ri-aggiungi con l'app ufficiale come hai sempre fatto.

Il comando `bind` (Punto 1) serve davvero solo per un modulo **nuovo** non ancora nel tuo account:
lo provi come passo facoltativo in fondo.

## Prerequisiti

- Un dispositivo con **Python 3.8+** e questo repo: un **PC** sulla rete di casa, oppure il
  **telefono con Termux**. Nota: dovrai **cambiare WiFi** durante la prova (vedi sotto).
- SSID e password del **WiFi di casa** (2.4 GHz — i moduli non stanno sui 5 GHz).
- La procedura dell'app ufficiale per mettere il modulo **in modalità configurazione** (è il gesto
  che fa comparire l'hotspot `Broadlink_tcl_...`; varia per modello — non lo conosco per la tua unità).

> **Due contesti di rete.** Il `provision` si manda **connessi all'hotspot del modulo**
> (`192.168.10.1` raggiungibile); `discover`/`login`/`list` si fanno **sul WiFi di casa**. Si passa
> dall'uno all'altro cambiando rete nelle impostazioni WiFi.

## Passi

### 0. Preparazione (sul WiFi di casa)

```bash
cd /percorso/klimakontrol
export KLIMAKONTROL_DEBUG=1            # stampa richieste/risposte grezze (utile per capire)
python3 -m klimakontrol login          # se non hai già una sessione
python3 -m klimakontrol list           # annota nome/mac dell'unità che resetterai
```

Annota la riga dell'unità bersaglio: ti serve per riconoscerla al ritorno.

### 1. Metti il modulo in modalità configurazione

Usa il gesto di reset/pairing del tuo modello (lo stesso che usa l'app ufficiale in "aggiungi
dispositivo"). **Conferma** che è in config mode: tra le reti WiFi del telefono compare un hotspot
tipo **`Broadlink_tcl_XXXX`** (aperto, senza internet).

### 2. Connetti il dispositivo di test all'hotspot del modulo

Dalle impostazioni WiFi del PC/telefono, **connettiti a `Broadlink_tcl_XXXX`**. Ignora l'avviso
"rete senza internet" (è normale). Verifica che il gateway risponda:

```bash
ping -c 3 192.168.10.1
```

### 3. Manda le credenziali WiFi (il `provision`)

Ancora **connesso all'hotspot del modulo**:

```bash
python3 -m klimakontrol provision --ssid "NOME_WIFI_CASA" --security wpa2
# chiede la password del WiFi (non finisce nei comandi/history)
```

Esito atteso: `Inviato. Nessuna conferma dal modulo (normale): ora dovrebbe connettersi al WiFi.`
(oppure `...ha risposto (N byte)`). Dopo qualche secondo il modulo lascia il suo hotspot e prova a
entrare nel WiFi di casa: l'hotspot `Broadlink_tcl_...` **sparisce** — buon segno.

> **Se la password non "prende"** (il modulo non entra in rete, passo 5): il valore di `--security`
> è l'unica cosa non certa al 100% della ricostruzione. Riprova con `--security wpa12` (WPA1/2),
> poi eventualmente `--security 4`. Vedi "Cosa riportare" in fondo.

### 4. Torna sul WiFi di casa

Riconnetti il dispositivo di test alla **rete di casa** e aspetta ~15–30 s che il modulo si
registri.

### 5. Verifica che il modulo sia rientrato (il test-chiave)

```bash
python3 -m klimakontrol discover
```

Se l'unità **compare** (mac/ip/devtype): 🎉 **il pacchetto SoftAP funziona** — è il risultato che
conta per il Punto 2. Se non compare, riprova `discover` dopo qualche secondo; se resta assente,
vedi troubleshooting.

### 6. Verifica che sia controllabile

Per un modulo **che possiedi già** (ri-registrato al tuo account):

```bash
python3 -m klimakontrol login       # rinfresca la sessione/elenco
python3 -m klimakontrol list        # l'unità deve essere lì
python3 -m klimakontrol status 1    # (indice giusto) legge lo stato
python3 -m klimakontrol on 1        # prova ad accenderla
python3 -m klimakontrol off 1
```

Se accende/spegne: **onboarding end-to-end riuscito**. ✅

### 7. (Facoltativo) Prova il `bind` per un modulo nuovo

Solo se hai un modulo **non** nel tuo account e ne conosci `did/pid/mac/devkey` (es. dalla lettura
del cloud o da una cattura): `klimakontrol bind --did … --pid … --mac … [--name …]` (chiede la
chiave). Poi `list` deve mostrarlo.

## Troubleshooting

| Sintomo | Cosa fare |
| --- | --- |
| `ping 192.168.10.1` fallisce al passo 2 | Non sei sull'hotspot del modulo, o il modulo non è in config mode. Ricontrolla la rete WiFi selezionata e ripeti il passo 1. |
| L'hotspot `Broadlink_tcl_...` **non sparisce** dopo `provision` | Le credenziali non sono state accettate: SSID errato, WiFi a 5 GHz (usa i 2.4 GHz), o `--security` sbagliato → riprova con `wpa12`/`4`. |
| `discover` non lo vede | Il modulo non è entrato in rete (vedi sopra), oppure PC/telefono e modulo sono su VLAN/segmenti diversi. Riprova sullo stesso segmento del WiFi di casa. |
| Compare in `discover` ma non in `list` | È in rete ma non (ancora) nel tuo account: attendi, rifai `login`; per un modulo nuovo serve il `bind`. |

## Cosa riportare (per chiudere il flusso e preparare il wizard)

Con `KLIMAKONTROL_DEBUG=1` e/o annotando a mano:
1. **Ha funzionato?** con quale `--security` (wpa2/wpa12/…): conferma il mapping enum del byte sicurezza.
2. La **risposta grezza** del modulo al `provision`, se c'è (byte/hex) — per capire se contiene già
   qualcosa di utile (es. la chiave).
3. Se `discover`/`list` mostrano did/mac/devtype dell'unità rientrata.

Per catturare la risposta grezza del `provision` (connesso all'hotspot del modulo):

```bash
python3 -c "from klimakontrol.provision import softap_config; \
import sys; r=softap_config(sys.argv[1], input('password WiFi: '), 3); \
print('risposta:', r.hex() if r else None)" "NOME_WIFI_CASA"
```

Questi tre dati chiudono le uniche incognite rimaste (mapping sicurezza, forma della risposta) e
guidano il **Punto 4 (wizard nell'app)**.
