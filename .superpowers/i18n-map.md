# Inventario stringhe i18n — KlimaKontrol Android

Mappa completa di ogni stringa **user-facing** nell'app (`net.klimakontrol`), con chiave di
risorsa proposta, valore IT (default) e traduzione EN. Voce informale "tu", sentence-case.

**Convenzioni**
- Argomenti di formato: `%1$s` (stringa), `%1$d`/`%d` (intero). L'ordine è indicato nelle note.
- Escape XML Android: `&` → `&amp;`, `<` → `&lt;`, apostrofo → `\'`. I caratteri `«» … — · ✓ ° ↕ ↔` restano letterali.
- `\n` = a capo letterale (Android lo interpreta).
- FLAG: `[ENUM]` label di enum, `[VM]` stringa risolta nel ViewModel via `context.getString(...)` (non `stringResource`), `[FORMAT]` con argomenti, `[PLURAL]` diventa `<plurals>`.
- `app_name` ("KlimaKontrol") esiste già in `res/values/strings.xml` ed è un **nome proprio**: **escluso**, non si localizza.

**Escluse** (non stringhe di UI): glifi/emoji-only (`❄ ☀ 💧 ≋ Ⓐ ⟳ ⚙ ⊘ + − ° — ↕ ↔ ✓` usati come icona), il wordmark `KlimaKontrol` (nome app), `°C`/`°` come simboli d'unità, literali dentro commenti (`liquido`, `invio in corso`, `indietro`, `tofu`, `aggiornamento disponibile`, `da zero`, `Mostra/Nascondi`), i valori dei temi (`Quadrante`, `Latte`, provider font `Space Grotesk`/`Inter`/`Outfit`, `tnum`, `com.google.android.gms.fonts`), le label d'animazione (`press`, `nav`, `dialFrac`), e i messaggi delle eccezioni tecniche in `data/cloud/*` e `data/update/*` (es. `CloudException("controllo remoto fallito: …")`) che non arrivano mai a schermo tali e quali: passano tutti da `readable()` che li rimappa (vedi sezione ViewModel).

---

## Chiavi condivise fra più schermate

Queste compaiono in più file con testo identico: un'unica chiave.

| chiave | literal IT @file:line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `create_account` | `"Crea account"` @Login:185, @Register:70 (titolo), @Register:111 (bottone) | Crea account | Create account | |
| `email_or_phone_label` | `"Email o telefono"` @Login:131, @Register:78 | Email o telefono | Email or phone | |
| `password_label` | `"Password"` @Login:135 | Password | Password | |
| `add_ac` | `"Aggiungi climatizzatore"` @Home:151, @Settings:148, @Onboarding:85 (titolo) | Aggiungi climatizzatore | Add air conditioner | |
| `manage_zones` | `"Gestisci zone"` @Homes:65 (titolo), @Settings:156 | Gestisci zone | Manage zones | |
| `vendor_logo_desc` | `"Logo del produttore"` @Settings:166, @Splash:39 (contentDescription) | Logo del produttore | Manufacturer logo | |
| `send_sending` | `"invio…"` @Home:249, @Detail:179 | invio… | sending… | |
| `send_confirmed` | `"✓ confermato"` @Home:250, @Detail:180 | ✓ confermato | ✓ confirmed | |
| `send_failed` | `"comando non riuscito"` @Home:251, @Detail:181 | comando non riuscito | command failed | |
| `action_save` | `"Salva"` @Homes:86 | Salva | Save | |
| `action_delete` | `"Elimina"` @Homes:90 | Elimina | Delete | |
| `action_add` | `"Aggiungi"` @Homes:100 | Aggiungi | Add | |

---

## data/Model.kt — enum Mode e FanSpeed  [ENUM]

`Mode.label` e `FanSpeed.label` diventano `@StringRes` (si sostituisce il campo `label: String`
con un `labelRes: Int` risolto in Compose con `stringResource`). Le label di `Mode` sono
capitalizzate (usate come chip/badge, il badge applica `.uppercase()` a runtime); quelle di
`FanSpeed` sono minuscole (usate inline, es. `"· media"`).

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `mode_caldo` | `CALDO("Caldo")` @4 | Caldo | Heat | [ENUM] |
| `mode_freddo` | `FREDDO("Freddo")` @5 | Freddo | Cool | [ENUM] |
| `mode_deumidifica` | `DEUMIDIFICA("Deumidifica")` @6 | Deumidifica | Dry | [ENUM] |
| `mode_ventola` | `VENTOLA("Ventola")` @7 | Ventola | Fan | [ENUM] |
| `mode_auto` | `AUTO("Auto")` @8 | Auto | Auto | [ENUM] |
| `fan_auto` | `AUTO("auto", 0)` @14 | auto | auto | [ENUM] |
| `fan_bassa` | `BASSA("bassa", 1)` @15 | bassa | low | [ENUM] |
| `fan_medio_bassa` | `MEDIO_BASSA("medio-bassa", 2)` @16 | medio-bassa | medium-low | [ENUM] |
| `fan_media` | `MEDIA("media", 3)` @17 | media | medium | [ENUM] |
| `fan_medio_alta` | `MEDIO_ALTA("medio-alta", 4)` @18 | medio-alta | medium-high | [ENUM] |
| `fan_alta` | `ALTA("alta", 5)` @19 | alta | high | [ENUM] |

Nota `Sample` (unità d'esempio: "Salone", "camera", "lavoro"): sono **dati di prova**, non UI
localizzabile — esclusi.

---

## ui/KlimaViewModel.kt — messaggi risolti nel ViewModel  [VM]

Risolti fuori da un `@Composable`: servono via `getApplication<Application>().getString(...)`
(o iniettando un resolver). I due messaggi "ok" terminano con `✓`: la SettingsScreen colora il
banner con `it.endsWith("✓")`, quindi **mantieni il `✓` finale** anche in EN.

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `error_bad_credentials` | `"Email o password errati"` @436 | Email o password errati | Wrong email or password | [VM] |
| `error_too_many_attempts` | `"Troppi tentativi, riprova tra qualche minuto"` @437 | Troppi tentativi, riprova tra qualche minuto | Too many attempts, try again in a few minutes | [VM] |
| `error_already_registered` | `"Questo account è già registrato: accedi invece di crearlo"` @438 | Questo account è già registrato: accedi invece di crearlo | This account already exists: sign in instead of creating it | [VM] |
| `error_bad_vcode` | `"Codice di verifica mancante o errato"` @439 | Codice di verifica mancante o errato | Verification code missing or wrong | [VM] |
| `error_op_failed_code` | `"Operazione non riuscita (errore ${it.value})"` @441 | Operazione non riuscita (errore %1$s) | Operation failed (error %1$s) | [VM][FORMAT] |
| `error_op_generic` | `"Operazione non riuscita"` @442 | Operazione non riuscita | Operation failed | [VM] |
| `error_op_failed` | `"Operazione fallita"` @434 | Operazione fallita | Operation failed | [VM] |
| `login_no_units` | `"Connesso, ma nessuna unità trovata"` @255 | Connesso, ma nessuna unità trovata | Connected, but no units found | [VM] |
| `settings_password_updated` | `"Password aggiornata ✓"` @179 | Password aggiornata ✓ | Password updated ✓ | [VM] |
| `settings_name_updated` | `"Nome aggiornato ✓"` @182 | Nome aggiornato ✓ | Name updated ✓ | [VM] |
| `onboarding_send_failed` | `"Invio non riuscito. Sei connesso all'hotspot «Broadlink_tcl_…» del climatizzatore?"` @315 | Invio non riuscito. Sei connesso all\'hotspot «Broadlink_tcl_…» del climatizzatore? | Send failed. Are you connected to the air conditioner\'s «Broadlink_tcl_…» hotspot? | [VM] |

Note:
- `error_op_failed_code` @441: `%1$s` = codice errore numerico (stringa, es. `-1036`) estratto con regex dal messaggio del server.
- `error_op_failed` ("Operazione fallita") ed `error_op_generic` ("Operazione non riuscita") sono due literal distinti anche se quasi sinonimi: tenuti separati per fedeltà.

---

## ui/LoginScreen.kt

`REGION_SHORT` (@49) mappa i codici regione alle etichette; il selettore chip è condiviso con
RegisterScreen. `PasswordField` (show/hide) è condiviso con Register/Settings/Onboarding.

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `region_eu` | `"eu" to "Europa"` @49 | Europa | Europe | |
| `region_ab` | `"ab" to "Altro"` @49 | Altro | Other | |
| `region_cn` | `"cn" to "Cina"` @49 | Cina | China | |
| `region_ru` | `"ru" to "Russia"` @49 | Russia | Russia | |
| `region_heading` | `"REGIONE"` @56 | REGIONE | REGION | |
| `password_show` | `"Mostra"` @93 | Mostra | Show | |
| `password_hide` | `"Nascondi"` @93 | Nascondi | Hide | |
| `login_lead` | `"Accedi al tuo account per controllare i climatizzatori."` @125 | Accedi al tuo account per controllare i climatizzatori. | Sign in to your account to control your air conditioners. | |
| `login_region_hint` | `"Dove vive l'account (di solito Europa). Cambiala solo se l'hai creato in un'altra area."` @140 | Dove vive l\'account (di solito Europa). Cambiala solo se l\'hai creato in un\'altra area. | Where the account lives (usually Europe). Change it only if you created it in another area. | |
| `login_remember` | `"Ricorda le credenziali"` @153 | Ricorda le credenziali | Remember credentials | |
| `login_button` | `"Accedi"` @171 | Accedi | Sign in | |
| `login_remember_on` | `"Credenziali salvate cifrate sul dispositivo (Keystore); rientri da solo."` @175 | Credenziali salvate cifrate sul dispositivo (Keystore); rientri da solo. | Credentials saved encrypted on the device (Keystore); you\'re signed back in automatically. | |
| `login_remember_off` | `"Si conserva solo la sessione; la password non viene salvata."` @176 | Si conserva solo la sessione; la password non viene salvata. | Only the session is kept; the password is not saved. | |
| `login_no_account` | `"Non hai un account?"` @182 | Non hai un account? | Don\'t have an account? | |

(`"Crea account"` @185 → `create_account`, condivisa. `"Email o telefono"` @131 → `email_or_phone_label`. `"Password"` @135 → `password_label`.)

---

## ui/RegisterScreen.kt

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `register_intro` | `"Inserisci email e regione: ti mandiamo un codice di verifica."` @73 | Inserisci email e regione: ti mandiamo un codice di verifica. | Enter your email and region: we\'ll send you a verification code. | |
| `register_send_code` | `"Invia codice"` @87 | Invia codice | Send code | |
| `register_code_sent` | `"Codice inviato a ${email.trim()} (${region.uppercase()}). Inseriscilo e scegli una password."` @92 | Codice inviato a %1$s (%2$s). Inseriscilo e scegli una password. | We sent a code to %1$s (%2$s). Enter it and choose a password. | [FORMAT] |
| `register_code_label` | `"Codice di verifica"` @97 | Codice di verifica | Verification code | |
| `register_password_new` | `"Password (nuova)"` @101 | Password (nuova) | Password (new) | |
| `register_name_optional` | `"Nome (facoltativo)"` @104 | Nome (facoltativo) | Name (optional) | |
| `register_no_code` | `"Codice non arrivato?"` @117 | Codice non arrivato? | Code didn\'t arrive? | |
| `register_resend` | `"Rimanda"` @119 | Rimanda | Resend | |
| `register_back_to_login` | `"Torna al login"` @125 | Torna al login | Back to sign in | |

Note:
- `register_code_sent` @92: `%1$s` = email (trim), `%2$s` = codice regione maiuscolo (es. `EU`).
- `"Crea account"` @70/@111 → `create_account`. `"Email o telefono"` @78 → `email_or_phone_label`.

---

## ui/HomeScreen.kt

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `home_title` | `"Dispositivi"` @83 (fallback header) | Dispositivi | Devices | |
| `home_units_count` | parte di `"${shown.size} unità · …"` @84 | *(plurals, vedi sotto)* | *(plurals)* | [PLURAL][FORMAT] |
| `home_on_count` | parte di `"… $onCount ${if (onCount == 1) "accesa" else "accese"}"` @84 | *(plurals, vedi sotto)* | *(plurals)* | [PLURAL][FORMAT] |
| `home_filter_all` | `"Tutte"` @101 | Tutte | All | |
| `home_update_available` | `"Aggiornamento disponibile · v${avail.latest}"` @117 | Aggiornamento disponibile · v%1$s | Update available · v%1$s | [FORMAT] |
| `home_update_open` | `"Apri"` @119 | Apri | Open | |
| `home_empty_zone` | `"Nessuna unità in questa zona.\nAssegnale da Impostazioni → Gestisci zone."` @135 | Nessuna unità in questa zona.\nAssegnale da Impostazioni → Gestisci zone. | No units in this zone.\nAssign them from Settings → Manage zones. | |
| `home_empty` | `"Nessun climatizzatore.\nAggiungine uno per iniziare."` @136 | Nessun climatizzatore.\nAggiungine uno per iniziare. | No air conditioners.\nAdd one to get started. | |
| `home_refresh_house` | `"Rinfresca casa"` @180 | Rinfresca casa | Cool the house | |
| `home_power_all_off` | `"Spegni tutte"` @191 | Spegni tutte | Turn all off | |
| `unit_offline` | `"offline"` @239 | offline | offline | |
| `unit_off` | `"Spenta"` @245 | Spenta | Off | |
| `home_last_data` | `"ultimo dato 3 min fa"` @253 | ultimo dato 3 min fa | last data 3 min ago | |
| `home_ambient` | `"ambiente ${fmt(it)}°"` @254 | ambiente %1$s° | ambient %1$s° | [FORMAT] |

### Header composto (@84) — decomposizione plurals

Sorgente attuale:
```kotlin
Text("${shown.size} unità · $onCount ${if (onCount == 1) "accesa" else "accese"}", …)
```

Diventa due `<plurals>` uniti dal separatore decorativo `" · "` (middot con spazi, resta glue nel codice):

```kotlin
val units = resources.getQuantityString(R.plurals.home_units_count, shown.size, shown.size)
val on    = resources.getQuantityString(R.plurals.home_on_count, onCount, onCount)
Text("$units · $on", …)
```

- `home_units_count`: IT invariabile ("unità" non flette) → one/other identici; conta = `shown.size`. `%d` = conteggio.
  - IT: one `%d unità` · other `%d unità`
  - EN: one `%d unit` · other `%d units`
- `home_on_count`: IT flette accesa/accese; `onCount==1`→`one`, altrimenti (incluso 0)→`other`; conta = `onCount`. `%d` = conteggio.
  - IT: one `%d accesa` · other `%d accese`
  - EN: one `%d on` · other `%d on`

Altre note HomeScreen:
- `"· ${u.fan.label}"` @243: `·` è glifo decorativo + label enum (`fan_*`) → nessuna chiave nuova, si assembla in codice (`"· " + stringResource(fan)`).
- `home_ambient` @254: `%1$s` = temperatura formattata (`fmt`, es. `24.5`); il `°` resta nel valore.
- `"invio…"`/`"✓ confermato"`/`"comando non riuscito"` @249-251 → `send_*` condivise. Glifi `+ ⟳ ⚙ ⊘ ❄ ° —` esclusi.

---

## ui/DetailScreen.kt

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `detail_ambient` | `"Ambiente ${fmt(it)}°  ·  "` @135 | Ambiente %1$s° | Ambient %1$s° | [FORMAT] |
| `detail_error_code` | `"errore $it"` @136 | errore %1$s | error %1$s | [FORMAT] |
| `detail_no_error` | `"nessun errore"` @136 | nessun errore | no error | |
| `detail_section_mode` | `"Modalità"` @212 | Modalità | Mode | |
| `detail_section_fan` | `"Ventola"` @233 | Ventola | Fan | |
| `detail_fan_auto` | `"Auto"` @242 (chip ventola Auto) | Auto | Auto | |
| `detail_section_swing` | `"Oscillazione"` @253 | Oscillazione | Swing | |
| `swing_vertical` | `"Verticale"` @255 | Verticale | Vertical | |
| `swing_horizontal` | `"Orizzontale"` @256 | Orizzontale | Horizontal | |
| `detail_section_functions` | `"Funzioni"` @260 | Funzioni | Functions | |
| `feature_eco` | `"Eco"` @262 | Eco | Eco | |
| `feature_turbo` | `"Turbo"` @263 | Turbo | Turbo | |
| `feature_night` | `"Notte"` @264 | Notte | Night | |

Note:
- Sottotitolo app-bar @133-137 (`buildString`): `detail_ambient` (`%1$s` = temp formattata) e poi, dopo il separatore decorativo `"  ·  "` (glue in codice, solo se c'è la temperatura), o `detail_error_code` (`%1$s` = codice errore) o `detail_no_error`. Assemblaggio suggerito: costruire con `buildString` come ora, sostituendo i literal con `getString`.
- `"°C"` @171 e `"°"` sono simboli d'unità (universali) → **esclusi** (giudizio, vedi in fondo).
- Glifi modalità (`☀ ❄ 💧 ≋ Ⓐ` @58), `− +` @202-203, `↕ ↔` @255-256 → esclusi. `send_*` @179-181 condivise. `"Auto"` chip @242 tenuto separato da `mode_auto`/`fan_auto` (case/semantica diversi).

---

## ui/HomesScreen.kt

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `homes_your_zones` | `"Le tue zone"` @75 | Le tue zone | Your zones | |
| `homes_no_zones` | `"Nessuna zona. Creane una qui sotto (es. Piano terra, Zona notte…)."` @76 | Nessuna zona. Creane una qui sotto (es. Piano terra, Zona notte…). | No zones. Create one below (e.g. Ground floor, Night area…). | |
| `homes_saved` | `"Salvato ✓"` @87 | Salvato ✓ | Saved ✓ | |
| `homes_new_zone` | `"Nuova zona"` @97 | Nuova zona | New zone | |
| `homes_assign_devices` | `"Assegna dispositivi"` @107 | Assegna dispositivi | Assign devices | |
| `homes_no_devices` | `"Nessun dispositivo."` @108 | Nessun dispositivo. | No devices. | |
| `homes_unassigned` | `"Nessuna"` @117 (chip: unità senza zona) | Nessuna | None | |
| `homes_backup` | `"Backup"` @124 | Backup | Backup | |
| `homes_export` | `"Esporta configurazione"` @125 (bottone) e @132 (titolo chooser) | Esporta configurazione | Export configuration | |
| `homes_export_subject` | `"klimakontrol — configurazione zone"` @129 (EXTRA_SUBJECT) | klimakontrol — configurazione zone | klimakontrol — zone configuration | |
| `homes_import_hint` | `"Incolla qui una configurazione"` @137 | Incolla qui una configurazione | Paste a configuration here | |
| `homes_import` | `"Importa"` @140 | Importa | Import | |
| `homes_config_imported` | `"Configurazione importata ✓"` @141 | Configurazione importata ✓ | Configuration imported ✓ | |
| `homes_config_invalid` | `"Configurazione non valida"` @142 | Configurazione non valida | Invalid configuration | |

Note:
- `"Gestisci zone"` @65 → `manage_zones`. `"Salva"` @86 → `action_save`. `"Elimina"` @90 → `action_delete`. `"Aggiungi"` @100 → `action_add`.
- `homes_saved` e `homes_config_imported` terminano con `✓`: il colore del `note` dipende da `it.endsWith("✓")` (@72) → mantieni il `✓` in EN.

---

## ui/SettingsScreen.kt

Le label di `Section` sono passate in case normale e maiuscolate a runtime da `.uppercase()`
(@207): memorizza in case normale.

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `settings_title` | `"Impostazioni"` @76 | Impostazioni | Settings | |
| `settings_section_account` | `"Account"` @95 | Account | Account | |
| `settings_email_label` | `"Email"` @96 | Email | Email | |
| `settings_new_name` | `"Nuovo nome"` @100 | Nuovo nome | New name | |
| `settings_save_name` | `"Salva nome"` @104 | Salva nome | Save name | |
| `settings_change_password` | `"Cambia password"` @109 (titolo) e @115 (bottone) | Cambia password | Change password | |
| `settings_current_password` | `"Password attuale"` @111 | Password attuale | Current password | |
| `settings_new_password` | `"Nuova password"` @113 | Nuova password | New password | |
| `settings_section_app` | `"App"` @122 | App | App | |
| `settings_version` | `"Versione"` @123 | Versione | Version | |
| `settings_update_available` | `"Aggiornamento disponibile: v${u.latest}"` @128 | Aggiornamento disponibile: v%1$s | Update available: v%1$s | [FORMAT] |
| `settings_download_update` | `"Scarica l'aggiornamento"` @130 | Scarica l\'aggiornamento | Download the update | |
| `settings_up_to_date` | `"Sei all'ultima versione."` @134 | Sei all\'ultima versione. | You\'re on the latest version. | |
| `settings_check_updates` | `"Controlla aggiornamenti"` @136 | Controlla aggiornamenti | Check for updates | |
| `settings_section_devices` | `"Dispositivi"` @144 | Dispositivi | Devices | |
| `settings_devices_desc` | `"Aggiungi un climatizzatore collegando il suo modulo WiFi alla rete di casa, senza l'app ufficiale."` @145-146 | Aggiungi un climatizzatore collegando il suo modulo WiFi alla rete di casa, senza l\'app ufficiale. | Add an air conditioner by connecting its WiFi module to your home network, without the official app. | |
| `settings_section_zones` | `"Zone"` @152 | Zone | Zones | |
| `settings_zones_desc` | `"Raggruppa i climatizzatori per zona (piano terra, zona notte…) e filtra la Home. Tutto in locale."` @153 | Raggruppa i climatizzatori per zona (piano terra, zona notte…) e filtra la Home. Tutto in locale. | Group your air conditioners by zone (ground floor, night area…) and filter the Home screen. All stored locally. | |
| `settings_section_hardware` | `"Hardware"` @160 | Hardware | Hardware | |
| `settings_vendor_hint` | `"Codice del costruttore (dal QR o dalla confezione), es. WISNOW. Il logo viene scaricato dal cloud del produttore."` @172 | Codice del costruttore (dal QR o dalla confezione), es. WISNOW. Il logo viene scaricato dal cloud del produttore. | Manufacturer code (from the QR or the box), e.g. WISNOW. The logo is downloaded from the manufacturer\'s cloud. | |
| `settings_vendor_code_label` | `"Codice costruttore"` @177 | Codice costruttore | Manufacturer code | |
| `settings_vendor_downloading` | `"Scarico…"` @179 | Scarico… | Downloading… | |
| `settings_vendor_apply` | `"Applica"` @179 | Applica | Apply | |
| `settings_section_timezone` | `"Fuso orario"` @185 | Fuso orario | Time zone | |
| `settings_timezone_desc` | `"Gestito automaticamente. I moduli ragionano in UTC+8 e la conversione è fatta dall'app col fuso del telefono: non c'è nulla da impostare."` @186-187 | Gestito automaticamente. I moduli ragionano in UTC+8 e la conversione è fatta dall\'app col fuso del telefono: non c\'è nulla da impostare. | Handled automatically. The modules think in UTC+8 and the conversion is done by the app using the phone\'s time zone: there\'s nothing to set. | |
| `settings_section_session` | `"Sessione"` @192 | Sessione | Session | |
| `settings_logout` | `"Esci"` @193 | Esci | Sign out | |
| `settings_forget` | `"Dimentica credenziali"` @195 | Dimentica credenziali | Forget credentials | |

Note:
- `settings_update_available` @128: `%1$s` = versione (`u.latest`).
- `"Aggiungi climatizzatore"` @148 → `add_ac`. `"Gestisci zone"` @156 → `manage_zones`. `"Logo del produttore"` @166 → `vendor_logo_desc`. Placeholder `"—"` @96 = glifo (escluso).
- Il banner `message` colora con `it.endsWith("✓")` (@86): i messaggi ok provengono dal VM (`settings_password_updated`/`settings_name_updated`) e conservano il `✓`.

---

## ui/SplashScreen.kt

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `vendor_logo_desc` | `"Logo del produttore"` @39 (contentDescription) | Logo del produttore | Manufacturer logo | |

(`"KlimaKontrol"` @44 = wordmark/nome app → escluso.)

---

## ui/OnboardingScreen.kt

`SECURITY_OPTIONS` (@200) mappa i valori di sicurezza WiFi alle etichette dei chip.

| chiave | literal IT @line | valore IT | valore EN | flag |
| --- | --- | --- | --- | --- |
| `onboarding_intro_lead` | `"Colleghiamo il modulo WiFi del climatizzatore alla tua rete di casa — senza l'app ufficiale."` @118 | Colleghiamo il modulo WiFi del climatizzatore alla tua rete di casa — senza l\'app ufficiale. | Let\'s connect the air conditioner\'s WiFi module to your home network — without the official app. | |
| `onboarding_step1` | `"Metti il climatizzatore in modalità configurazione"` @122 | Metti il climatizzatore in modalità configurazione | Put the air conditioner into configuration mode | |
| `onboarding_step2` | `"Inserisci la password del tuo WiFi"` @123 | Inserisci la password del tuo WiFi | Enter your WiFi password | |
| `onboarding_step3` | `"Connetti il telefono all'hotspot del climatizzatore"` @124 | Connetti il telefono all\'hotspot del climatizzatore | Connect your phone to the air conditioner\'s hotspot | |
| `onboarding_step4` | `"Invio: il modulo entra nella tua rete"` @125 | Invio: il modulo entra nella tua rete | Send: the module joins your network | |
| `onboarding_config_hint` | `"Per la modalità configurazione usa lo stesso gesto dell'app ufficiale: di solito si tiene premuto un tasto finché non compare una rete WiFi che inizia con «Broadlink_tcl_»."` @129-130 | Per la modalità configurazione usa lo stesso gesto dell\'app ufficiale: di solito si tiene premuto un tasto finché non compare una rete WiFi che inizia con «Broadlink_tcl_». | For configuration mode use the same gesture as the official app: usually you hold a button until a WiFi network starting with «Broadlink_tcl_» appears. | |
| `onboarding_start` | `"Inizia"` @134 | Inizia | Start | |
| `onboarding_wifi_title` | `"La tua rete WiFi"` @145 | La tua rete WiFi | Your WiFi network | |
| `onboarding_ssid_label` | `"Nome rete (SSID)"` @148 | Nome rete (SSID) | Network name (SSID) | |
| `onboarding_wifi_password` | `"Password WiFi"` @151 | Password WiFi | WiFi password | |
| `onboarding_wifi_hint` | `"Il modulo si collega solo a reti a 2.4 GHz. Di solito la sicurezza è WPA2."` @153 | Il modulo si collega solo a reti a 2.4 GHz. Di solito la sicurezza è WPA2. | The module only connects to 2.4 GHz networks. Security is usually WPA2. | |
| `onboarding_next` | `"Avanti"` @155 | Avanti | Next | |
| `onboarding_connect_title` | `"Connetti il telefono al climatizzatore"` @162 | Connetti il telefono al climatizzatore | Connect your phone to the air conditioner | |
| `onboarding_connect_desc` | `"Apri le impostazioni WiFi e connettiti alla rete «Broadlink_tcl_…» del climatizzatore. È una rete senza internet: va bene. Poi torna qui e invia."` @163-164 | Apri le impostazioni WiFi e connettiti alla rete «Broadlink_tcl_…» del climatizzatore. È una rete senza internet: va bene. Poi torna qui e invia. | Open WiFi settings and connect to the air conditioner\'s «Broadlink_tcl_…» network. It\'s a network with no internet: that\'s fine. Then come back here and send. | |
| `onboarding_open_wifi` | `"Apri impostazioni WiFi"` @167 | Apri impostazioni WiFi | Open WiFi settings | |
| `onboarding_send` | `"Invia credenziali"` @176 | Invia credenziali | Send credentials | |
| `onboarding_done_title` | `"Credenziali inviate"` @183 | Credenziali inviate | Credentials sent | |
| `onboarding_done_responded` | `"Il climatizzatore ha ricevuto la configurazione e si sta connettendo alla tua rete."` @186 | Il climatizzatore ha ricevuto la configurazione e si sta connettendo alla tua rete. | The air conditioner received the configuration and is connecting to your network. | |
| `onboarding_done_no_response` | `"Abbiamo mandato le credenziali al climatizzatore (di solito non risponde: è normale)."` @188 | Abbiamo mandato le credenziali al climatizzatore (di solito non risponde: è normale). | We sent the credentials to the air conditioner (it usually doesn\'t reply: that\'s normal). | |
| `onboarding_done_hint` | `"Ora riconnetti il telefono al WiFi di casa. Se il modulo entra in rete ed è già associato a questo account, tra poco l'unità comparirà nell'elenco; un modulo mai visto da questo account potrebbe richiedere ancora un passaggio."` @191-193 | Ora riconnetti il telefono al WiFi di casa. Se il modulo entra in rete ed è già associato a questo account, tra poco l\'unità comparirà nell\'elenco; un modulo mai visto da questo account potrebbe richiedere ancora un passaggio. | Now reconnect your phone to your home WiFi. If the module joins the network and is already linked to this account, the unit will appear in the list shortly; a module this account has never seen may need one more step. | |
| `onboarding_finish` | `"Fine"` @195 | Fine | Done | |
| `security_open` | `0 to "Aperta"` @200 | Aperta | Open | |
| `security_wep` | `1 to "WEP"` @200 | WEP | WEP | |
| `security_wpa` | `2 to "WPA"` @200 | WPA | WPA | |
| `security_wpa2` | `3 to "WPA2"` @200 | WPA2 | WPA2 | |
| `security_wpa12` | `4 to "WPA1/2"` @200 | WPA1/2 | WPA1/2 | |
| `security_heading` | `"SICUREZZA"` @207 | SICUREZZA | SECURITY | |

(`"Aggiungi climatizzatore"` @85 → `add_ac`. Glifo `"$n"` @234 numero passo, e `↕/↔`, esclusi.)

---

# Blocco 1 — `res/values/strings.xml` (IT, default)

Da incollare nel `<resources>` (mantiene `app_name` esistente). Chiavi raggruppate per area.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">KlimaKontrol</string>

    <!-- ==== Comuni / condivise ==== -->
    <string name="create_account">Crea account</string>
    <string name="email_or_phone_label">Email o telefono</string>
    <string name="password_label">Password</string>
    <string name="add_ac">Aggiungi climatizzatore</string>
    <string name="manage_zones">Gestisci zone</string>
    <string name="vendor_logo_desc">Logo del produttore</string>
    <string name="send_sending">invio…</string>
    <string name="send_confirmed">✓ confermato</string>
    <string name="send_failed">comando non riuscito</string>
    <string name="action_save">Salva</string>
    <string name="action_delete">Elimina</string>
    <string name="action_add">Aggiungi</string>

    <!-- ==== Enum: modalità ==== -->
    <string name="mode_caldo">Caldo</string>
    <string name="mode_freddo">Freddo</string>
    <string name="mode_deumidifica">Deumidifica</string>
    <string name="mode_ventola">Ventola</string>
    <string name="mode_auto">Auto</string>

    <!-- ==== Enum: ventola ==== -->
    <string name="fan_auto">auto</string>
    <string name="fan_bassa">bassa</string>
    <string name="fan_medio_bassa">medio-bassa</string>
    <string name="fan_media">media</string>
    <string name="fan_medio_alta">medio-alta</string>
    <string name="fan_alta">alta</string>

    <!-- ==== Messaggi ViewModel ==== -->
    <string name="error_op_failed">Operazione fallita</string>
    <string name="error_bad_credentials">Email o password errati</string>
    <string name="error_too_many_attempts">Troppi tentativi, riprova tra qualche minuto</string>
    <string name="error_already_registered">Questo account è già registrato: accedi invece di crearlo</string>
    <string name="error_bad_vcode">Codice di verifica mancante o errato</string>
    <string name="error_op_failed_code">Operazione non riuscita (errore %1$s)</string>
    <string name="error_op_generic">Operazione non riuscita</string>
    <string name="login_no_units">Connesso, ma nessuna unità trovata</string>
    <string name="settings_password_updated">Password aggiornata ✓</string>
    <string name="settings_name_updated">Nome aggiornato ✓</string>
    <string name="onboarding_send_failed">Invio non riuscito. Sei connesso all\'hotspot «Broadlink_tcl_…» del climatizzatore?</string>

    <!-- ==== Login ==== -->
    <string name="region_eu">Europa</string>
    <string name="region_ab">Altro</string>
    <string name="region_cn">Cina</string>
    <string name="region_ru">Russia</string>
    <string name="region_heading">REGIONE</string>
    <string name="password_show">Mostra</string>
    <string name="password_hide">Nascondi</string>
    <string name="login_lead">Accedi al tuo account per controllare i climatizzatori.</string>
    <string name="login_region_hint">Dove vive l\'account (di solito Europa). Cambiala solo se l\'hai creato in un\'altra area.</string>
    <string name="login_remember">Ricorda le credenziali</string>
    <string name="login_button">Accedi</string>
    <string name="login_remember_on">Credenziali salvate cifrate sul dispositivo (Keystore); rientri da solo.</string>
    <string name="login_remember_off">Si conserva solo la sessione; la password non viene salvata.</string>
    <string name="login_no_account">Non hai un account?</string>

    <!-- ==== Registrazione ==== -->
    <string name="register_intro">Inserisci email e regione: ti mandiamo un codice di verifica.</string>
    <string name="register_send_code">Invia codice</string>
    <string name="register_code_sent">Codice inviato a %1$s (%2$s). Inseriscilo e scegli una password.</string>
    <string name="register_code_label">Codice di verifica</string>
    <string name="register_password_new">Password (nuova)</string>
    <string name="register_name_optional">Nome (facoltativo)</string>
    <string name="register_no_code">Codice non arrivato?</string>
    <string name="register_resend">Rimanda</string>
    <string name="register_back_to_login">Torna al login</string>

    <!-- ==== Home ==== -->
    <string name="home_title">Dispositivi</string>
    <string name="home_filter_all">Tutte</string>
    <string name="home_update_available">Aggiornamento disponibile · v%1$s</string>
    <string name="home_update_open">Apri</string>
    <string name="home_empty_zone">Nessuna unità in questa zona.\nAssegnale da Impostazioni → Gestisci zone.</string>
    <string name="home_empty">Nessun climatizzatore.\nAggiungine uno per iniziare.</string>
    <string name="home_refresh_house">Rinfresca casa</string>
    <string name="home_power_all_off">Spegni tutte</string>
    <string name="unit_offline">offline</string>
    <string name="unit_off">Spenta</string>
    <string name="home_last_data">ultimo dato 3 min fa</string>
    <string name="home_ambient">ambiente %1$s°</string>
    <plurals name="home_units_count">
        <item quantity="one">%d unità</item>
        <item quantity="other">%d unità</item>
    </plurals>
    <plurals name="home_on_count">
        <item quantity="one">%d accesa</item>
        <item quantity="other">%d accese</item>
    </plurals>

    <!-- ==== Dettaglio ==== -->
    <string name="detail_ambient">Ambiente %1$s°</string>
    <string name="detail_error_code">errore %1$s</string>
    <string name="detail_no_error">nessun errore</string>
    <string name="detail_section_mode">Modalità</string>
    <string name="detail_section_fan">Ventola</string>
    <string name="detail_fan_auto">Auto</string>
    <string name="detail_section_swing">Oscillazione</string>
    <string name="swing_vertical">Verticale</string>
    <string name="swing_horizontal">Orizzontale</string>
    <string name="detail_section_functions">Funzioni</string>
    <string name="feature_eco">Eco</string>
    <string name="feature_turbo">Turbo</string>
    <string name="feature_night">Notte</string>

    <!-- ==== Zone (HomesScreen) ==== -->
    <string name="homes_your_zones">Le tue zone</string>
    <string name="homes_no_zones">Nessuna zona. Creane una qui sotto (es. Piano terra, Zona notte…).</string>
    <string name="homes_saved">Salvato ✓</string>
    <string name="homes_new_zone">Nuova zona</string>
    <string name="homes_assign_devices">Assegna dispositivi</string>
    <string name="homes_no_devices">Nessun dispositivo.</string>
    <string name="homes_unassigned">Nessuna</string>
    <string name="homes_backup">Backup</string>
    <string name="homes_export">Esporta configurazione</string>
    <string name="homes_export_subject">klimakontrol — configurazione zone</string>
    <string name="homes_import_hint">Incolla qui una configurazione</string>
    <string name="homes_import">Importa</string>
    <string name="homes_config_imported">Configurazione importata ✓</string>
    <string name="homes_config_invalid">Configurazione non valida</string>

    <!-- ==== Impostazioni ==== -->
    <string name="settings_title">Impostazioni</string>
    <string name="settings_section_account">Account</string>
    <string name="settings_email_label">Email</string>
    <string name="settings_new_name">Nuovo nome</string>
    <string name="settings_save_name">Salva nome</string>
    <string name="settings_change_password">Cambia password</string>
    <string name="settings_current_password">Password attuale</string>
    <string name="settings_new_password">Nuova password</string>
    <string name="settings_section_app">App</string>
    <string name="settings_version">Versione</string>
    <string name="settings_update_available">Aggiornamento disponibile: v%1$s</string>
    <string name="settings_download_update">Scarica l\'aggiornamento</string>
    <string name="settings_up_to_date">Sei all\'ultima versione.</string>
    <string name="settings_check_updates">Controlla aggiornamenti</string>
    <string name="settings_section_devices">Dispositivi</string>
    <string name="settings_devices_desc">Aggiungi un climatizzatore collegando il suo modulo WiFi alla rete di casa, senza l\'app ufficiale.</string>
    <string name="settings_section_zones">Zone</string>
    <string name="settings_zones_desc">Raggruppa i climatizzatori per zona (piano terra, zona notte…) e filtra la Home. Tutto in locale.</string>
    <string name="settings_section_hardware">Hardware</string>
    <string name="settings_vendor_hint">Codice del costruttore (dal QR o dalla confezione), es. WISNOW. Il logo viene scaricato dal cloud del produttore.</string>
    <string name="settings_vendor_code_label">Codice costruttore</string>
    <string name="settings_vendor_downloading">Scarico…</string>
    <string name="settings_vendor_apply">Applica</string>
    <string name="settings_section_timezone">Fuso orario</string>
    <string name="settings_timezone_desc">Gestito automaticamente. I moduli ragionano in UTC+8 e la conversione è fatta dall\'app col fuso del telefono: non c\'è nulla da impostare.</string>
    <string name="settings_section_session">Sessione</string>
    <string name="settings_logout">Esci</string>
    <string name="settings_forget">Dimentica credenziali</string>

    <!-- ==== Onboarding (config SoftAP) ==== -->
    <string name="onboarding_intro_lead">Colleghiamo il modulo WiFi del climatizzatore alla tua rete di casa — senza l\'app ufficiale.</string>
    <string name="onboarding_step1">Metti il climatizzatore in modalità configurazione</string>
    <string name="onboarding_step2">Inserisci la password del tuo WiFi</string>
    <string name="onboarding_step3">Connetti il telefono all\'hotspot del climatizzatore</string>
    <string name="onboarding_step4">Invio: il modulo entra nella tua rete</string>
    <string name="onboarding_config_hint">Per la modalità configurazione usa lo stesso gesto dell\'app ufficiale: di solito si tiene premuto un tasto finché non compare una rete WiFi che inizia con «Broadlink_tcl_».</string>
    <string name="onboarding_start">Inizia</string>
    <string name="onboarding_wifi_title">La tua rete WiFi</string>
    <string name="onboarding_ssid_label">Nome rete (SSID)</string>
    <string name="onboarding_wifi_password">Password WiFi</string>
    <string name="onboarding_wifi_hint">Il modulo si collega solo a reti a 2.4 GHz. Di solito la sicurezza è WPA2.</string>
    <string name="onboarding_next">Avanti</string>
    <string name="onboarding_connect_title">Connetti il telefono al climatizzatore</string>
    <string name="onboarding_connect_desc">Apri le impostazioni WiFi e connettiti alla rete «Broadlink_tcl_…» del climatizzatore. È una rete senza internet: va bene. Poi torna qui e invia.</string>
    <string name="onboarding_open_wifi">Apri impostazioni WiFi</string>
    <string name="onboarding_send">Invia credenziali</string>
    <string name="onboarding_done_title">Credenziali inviate</string>
    <string name="onboarding_done_responded">Il climatizzatore ha ricevuto la configurazione e si sta connettendo alla tua rete.</string>
    <string name="onboarding_done_no_response">Abbiamo mandato le credenziali al climatizzatore (di solito non risponde: è normale).</string>
    <string name="onboarding_done_hint">Ora riconnetti il telefono al WiFi di casa. Se il modulo entra in rete ed è già associato a questo account, tra poco l\'unità comparirà nell\'elenco; un modulo mai visto da questo account potrebbe richiedere ancora un passaggio.</string>
    <string name="onboarding_finish">Fine</string>
    <string name="security_open">Aperta</string>
    <string name="security_wep">WEP</string>
    <string name="security_wpa">WPA</string>
    <string name="security_wpa2">WPA2</string>
    <string name="security_wpa12">WPA1/2</string>
    <string name="security_heading">SICUREZZA</string>
</resources>
```

---

# Blocco 2 — `res/values-en/strings.xml` (EN)

Stesse chiavi (senza `app_name`: non si sovrascrive il nome app).

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <!-- ==== Shared / common ==== -->
    <string name="create_account">Create account</string>
    <string name="email_or_phone_label">Email or phone</string>
    <string name="password_label">Password</string>
    <string name="add_ac">Add air conditioner</string>
    <string name="manage_zones">Manage zones</string>
    <string name="vendor_logo_desc">Manufacturer logo</string>
    <string name="send_sending">sending…</string>
    <string name="send_confirmed">✓ confirmed</string>
    <string name="send_failed">command failed</string>
    <string name="action_save">Save</string>
    <string name="action_delete">Delete</string>
    <string name="action_add">Add</string>

    <!-- ==== Enum: mode ==== -->
    <string name="mode_caldo">Heat</string>
    <string name="mode_freddo">Cool</string>
    <string name="mode_deumidifica">Dry</string>
    <string name="mode_ventola">Fan</string>
    <string name="mode_auto">Auto</string>

    <!-- ==== Enum: fan ==== -->
    <string name="fan_auto">auto</string>
    <string name="fan_bassa">low</string>
    <string name="fan_medio_bassa">medium-low</string>
    <string name="fan_media">medium</string>
    <string name="fan_medio_alta">medium-high</string>
    <string name="fan_alta">high</string>

    <!-- ==== ViewModel messages ==== -->
    <string name="error_op_failed">Operation failed</string>
    <string name="error_bad_credentials">Wrong email or password</string>
    <string name="error_too_many_attempts">Too many attempts, try again in a few minutes</string>
    <string name="error_already_registered">This account already exists: sign in instead of creating it</string>
    <string name="error_bad_vcode">Verification code missing or wrong</string>
    <string name="error_op_failed_code">Operation failed (error %1$s)</string>
    <string name="error_op_generic">Operation failed</string>
    <string name="login_no_units">Connected, but no units found</string>
    <string name="settings_password_updated">Password updated ✓</string>
    <string name="settings_name_updated">Name updated ✓</string>
    <string name="onboarding_send_failed">Send failed. Are you connected to the air conditioner\'s «Broadlink_tcl_…» hotspot?</string>

    <!-- ==== Login ==== -->
    <string name="region_eu">Europe</string>
    <string name="region_ab">Other</string>
    <string name="region_cn">China</string>
    <string name="region_ru">Russia</string>
    <string name="region_heading">REGION</string>
    <string name="password_show">Show</string>
    <string name="password_hide">Hide</string>
    <string name="login_lead">Sign in to your account to control your air conditioners.</string>
    <string name="login_region_hint">Where the account lives (usually Europe). Change it only if you created it in another area.</string>
    <string name="login_remember">Remember credentials</string>
    <string name="login_button">Sign in</string>
    <string name="login_remember_on">Credentials saved encrypted on the device (Keystore); you\'re signed back in automatically.</string>
    <string name="login_remember_off">Only the session is kept; the password is not saved.</string>
    <string name="login_no_account">Don\'t have an account?</string>

    <!-- ==== Registration ==== -->
    <string name="register_intro">Enter your email and region: we\'ll send you a verification code.</string>
    <string name="register_send_code">Send code</string>
    <string name="register_code_sent">We sent a code to %1$s (%2$s). Enter it and choose a password.</string>
    <string name="register_code_label">Verification code</string>
    <string name="register_password_new">Password (new)</string>
    <string name="register_name_optional">Name (optional)</string>
    <string name="register_no_code">Code didn\'t arrive?</string>
    <string name="register_resend">Resend</string>
    <string name="register_back_to_login">Back to sign in</string>

    <!-- ==== Home ==== -->
    <string name="home_title">Devices</string>
    <string name="home_filter_all">All</string>
    <string name="home_update_available">Update available · v%1$s</string>
    <string name="home_update_open">Open</string>
    <string name="home_empty_zone">No units in this zone.\nAssign them from Settings → Manage zones.</string>
    <string name="home_empty">No air conditioners.\nAdd one to get started.</string>
    <string name="home_refresh_house">Cool the house</string>
    <string name="home_power_all_off">Turn all off</string>
    <string name="unit_offline">offline</string>
    <string name="unit_off">Off</string>
    <string name="home_last_data">last data 3 min ago</string>
    <string name="home_ambient">ambient %1$s°</string>
    <plurals name="home_units_count">
        <item quantity="one">%d unit</item>
        <item quantity="other">%d units</item>
    </plurals>
    <plurals name="home_on_count">
        <item quantity="one">%d on</item>
        <item quantity="other">%d on</item>
    </plurals>

    <!-- ==== Detail ==== -->
    <string name="detail_ambient">Ambient %1$s°</string>
    <string name="detail_error_code">error %1$s</string>
    <string name="detail_no_error">no error</string>
    <string name="detail_section_mode">Mode</string>
    <string name="detail_section_fan">Fan</string>
    <string name="detail_fan_auto">Auto</string>
    <string name="detail_section_swing">Swing</string>
    <string name="swing_vertical">Vertical</string>
    <string name="swing_horizontal">Horizontal</string>
    <string name="detail_section_functions">Functions</string>
    <string name="feature_eco">Eco</string>
    <string name="feature_turbo">Turbo</string>
    <string name="feature_night">Night</string>

    <!-- ==== Zones (HomesScreen) ==== -->
    <string name="homes_your_zones">Your zones</string>
    <string name="homes_no_zones">No zones. Create one below (e.g. Ground floor, Night area…).</string>
    <string name="homes_saved">Saved ✓</string>
    <string name="homes_new_zone">New zone</string>
    <string name="homes_assign_devices">Assign devices</string>
    <string name="homes_no_devices">No devices.</string>
    <string name="homes_unassigned">None</string>
    <string name="homes_backup">Backup</string>
    <string name="homes_export">Export configuration</string>
    <string name="homes_export_subject">klimakontrol — zone configuration</string>
    <string name="homes_import_hint">Paste a configuration here</string>
    <string name="homes_import">Import</string>
    <string name="homes_config_imported">Configuration imported ✓</string>
    <string name="homes_config_invalid">Invalid configuration</string>

    <!-- ==== Settings ==== -->
    <string name="settings_title">Settings</string>
    <string name="settings_section_account">Account</string>
    <string name="settings_email_label">Email</string>
    <string name="settings_new_name">New name</string>
    <string name="settings_save_name">Save name</string>
    <string name="settings_change_password">Change password</string>
    <string name="settings_current_password">Current password</string>
    <string name="settings_new_password">New password</string>
    <string name="settings_section_app">App</string>
    <string name="settings_version">Version</string>
    <string name="settings_update_available">Update available: v%1$s</string>
    <string name="settings_download_update">Download the update</string>
    <string name="settings_up_to_date">You\'re on the latest version.</string>
    <string name="settings_check_updates">Check for updates</string>
    <string name="settings_section_devices">Devices</string>
    <string name="settings_devices_desc">Add an air conditioner by connecting its WiFi module to your home network, without the official app.</string>
    <string name="settings_section_zones">Zones</string>
    <string name="settings_zones_desc">Group your air conditioners by zone (ground floor, night area…) and filter the Home screen. All stored locally.</string>
    <string name="settings_section_hardware">Hardware</string>
    <string name="settings_vendor_hint">Manufacturer code (from the QR or the box), e.g. WISNOW. The logo is downloaded from the manufacturer\'s cloud.</string>
    <string name="settings_vendor_code_label">Manufacturer code</string>
    <string name="settings_vendor_downloading">Downloading…</string>
    <string name="settings_vendor_apply">Apply</string>
    <string name="settings_section_timezone">Time zone</string>
    <string name="settings_timezone_desc">Handled automatically. The modules think in UTC+8 and the conversion is done by the app using the phone\'s time zone: there\'s nothing to set.</string>
    <string name="settings_section_session">Session</string>
    <string name="settings_logout">Sign out</string>
    <string name="settings_forget">Forget credentials</string>

    <!-- ==== Onboarding (SoftAP config) ==== -->
    <string name="onboarding_intro_lead">Let\'s connect the air conditioner\'s WiFi module to your home network — without the official app.</string>
    <string name="onboarding_step1">Put the air conditioner into configuration mode</string>
    <string name="onboarding_step2">Enter your WiFi password</string>
    <string name="onboarding_step3">Connect your phone to the air conditioner\'s hotspot</string>
    <string name="onboarding_step4">Send: the module joins your network</string>
    <string name="onboarding_config_hint">For configuration mode use the same gesture as the official app: usually you hold a button until a WiFi network starting with «Broadlink_tcl_» appears.</string>
    <string name="onboarding_start">Start</string>
    <string name="onboarding_wifi_title">Your WiFi network</string>
    <string name="onboarding_ssid_label">Network name (SSID)</string>
    <string name="onboarding_wifi_password">WiFi password</string>
    <string name="onboarding_wifi_hint">The module only connects to 2.4 GHz networks. Security is usually WPA2.</string>
    <string name="onboarding_next">Next</string>
    <string name="onboarding_connect_title">Connect your phone to the air conditioner</string>
    <string name="onboarding_connect_desc">Open WiFi settings and connect to the air conditioner\'s «Broadlink_tcl_…» network. It\'s a network with no internet: that\'s fine. Then come back here and send.</string>
    <string name="onboarding_open_wifi">Open WiFi settings</string>
    <string name="onboarding_send">Send credentials</string>
    <string name="onboarding_done_title">Credentials sent</string>
    <string name="onboarding_done_responded">The air conditioner received the configuration and is connecting to your network.</string>
    <string name="onboarding_done_no_response">We sent the credentials to the air conditioner (it usually doesn\'t reply: that\'s normal).</string>
    <string name="onboarding_done_hint">Now reconnect your phone to your home WiFi. If the module joins the network and is already linked to this account, the unit will appear in the list shortly; a module this account has never seen may need one more step.</string>
    <string name="onboarding_finish">Done</string>
    <string name="security_open">Open</string>
    <string name="security_wep">WEP</string>
    <string name="security_wpa">WPA</string>
    <string name="security_wpa2">WPA2</string>
    <string name="security_wpa12">WPA1/2</string>
    <string name="security_heading">SECURITY</string>
</resources>
```

---

## Scelte / casi ambigui (giudizio)

1. **`°C` (@Detail:171) e `°` (@Home:265)**: trattati come simboli d'unità universali → esclusi. Il `°` che compare *dentro* i valori composti (`home_ambient`, `detail_ambient`) resta invece incluso nel valore.
2. **Header Home composto** (@Home:84): scomposto in due `<plurals>` (`home_units_count`, `home_on_count`) uniti dal middot `" · "` in codice. `unità` è invariabile in IT (one=other); in EN diventa `unit`/`units`. La forma `one` scatta solo a `1`, `0` cade in `other` (coerente col codice: `accese` per 0).
3. **`WEP/WPA/WPA2/WPA1/2`, `Russia`, `Eco/Turbo/Auto`**: label user-facing identiche nelle due lingue → incluse comunque (sono opzioni selezionabili/visibili) con lo stesso valore.
4. **Suffisso `✓`**: `settings_password_updated`, `settings_name_updated`, `homes_saved`, `homes_config_imported` devono **conservare il `✓` finale**: due composable (`Settings` @86, `Homes` @72) decidono il colore con `endsWith("✓")`. Se lo togli in EN, il banner "successo" diventa rosso.
5. **`error_op_failed` vs `error_op_generic`**: due literal quasi sinonimi ("Operazione fallita" / "Operazione non riuscita") tenuti come chiavi distinte per fedeltà 1:1 al sorgente; in EN collassano entrambi su "Operation failed".
6. **`detail_fan_auto` vs `mode_auto`/`fan_auto`**: il chip "Auto" della ventola (@Detail:242) è un literal separato (capitalizzato) dai due enum; tenuto come chiave propria per non forzare il case.
7. **`homes_export_subject`** (Intent EXTRA_SUBJECT, @Homes:129): visibile all'utente nel foglio di condivisione/oggetto email → incluso. Il brand "klimakontrol" (minuscolo) resta invariato.
8. **`data/cloud/*` e `data/update/*`**: i messaggi `CloudException(...)` non sono UI diretta — vengono catturati e rimappati da `readable()`; localizzati sono i soli output di `readable()` (sezione ViewModel). Esclusi come da consegna.
