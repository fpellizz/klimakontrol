package net.klimakontrol.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.klimakontrol.BuildConfig
import net.klimakontrol.R
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.settings.LocaleStore
import net.klimakontrol.data.Home
import net.klimakontrol.data.branding.VendorBranding
import net.klimakontrol.data.homes.HomesStore
import net.klimakontrol.data.update.UpdateChecker
import net.klimakontrol.data.update.UpdateStatus
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.data.cloud.CloudService
import net.klimakontrol.data.cloud.ecoWire
import net.klimakontrol.data.cloud.fanChangeWire
import net.klimakontrol.data.cloud.modeChangeWire
import net.klimakontrol.data.cloud.nightWire
import net.klimakontrol.data.cloud.powerWire
import net.klimakontrol.data.cloud.swingHWire
import net.klimakontrol.data.cloud.swingVWire
import net.klimakontrol.data.cloud.targetWire
import net.klimakontrol.data.cloud.turboWire
import net.klimakontrol.data.softap.SoftApClient
import net.klimakontrol.data.schedule.Schedule
import net.klimakontrol.data.schedule.ScheduleStore
import net.klimakontrol.data.schedule.Scheduler
import java.util.UUID
import kotlin.math.roundToInt

sealed interface Phase {
    data object Loading : Phase
    data class Login(
        val email: String = "",
        val region: String = "eu",   // regione/vendor scelto (determina il lid)
        val error: String? = null,
        val busy: Boolean = false,
    ) : Phase
    data class Register(
        val email: String = "",
        val region: String = "eu",
        val codeSent: Boolean = false,   // passo 1 fatto: mostra il campo codice
        val error: String? = null,
        val busy: Boolean = false,
    ) : Phase
    data object Connected : Phase
}

/** Esito visibile di un comando per una singola unità. */
enum class SendState { Idle, Sending, Ok, Error }

/** Stato del wizard di onboarding (config SoftAP + bind cloud di un modulo vergine). */
data class OnboardingState(
    val busy: Boolean = false,
    val error: String? = null,
    val sent: Boolean = false,        // config SoftAP inviata
    val responded: Boolean = false,   // il modulo ha risposto (diagnostica)
    val bound: Boolean = false,       // registrato nell'account (bind riuscito)
    val boundName: String? = null,    // nome dell'unità aggiunta
)

/** Stato della schermata pianificazioni (timer) di un'unità.
 *  `canExact` = il sistema concede gli allarmi esatti (Android 12+); se no, i timer scattano
 *  comunque ma con precisione ridotta e la UI lo segnala. */
data class SchedulesState(
    val busy: Boolean = false,
    val error: String? = null,
    val schedules: List<Schedule> = emptyList(),
    val canExact: Boolean = true,
)

private const val DEBOUNCE_MS = 400L
private const val OK_HOLD_MS = 900L
private const val ERR_HOLD_MS = 1600L
private const val POLL_MS = 8_000L   // aggiornamento periodico dello stato reale (uso promiscuo)

class KlimaViewModel(app: Application) : AndroidViewModel(app) {

    private val service = CloudService(app)
    private val homesStore = HomesStore(app)

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase = _phase.asStateFlow()

    private val _units = MutableStateFlow<List<AcUnit>>(emptyList())
    val units = _units.asStateFlow()

    private val _send = MutableStateFlow<Map<String, SendState>>(emptyMap())
    val send = _send.asStateFlow()

    private val _onboarding = MutableStateFlow(OnboardingState())
    val onboarding = _onboarding.asStateFlow()

    private val scheduleStore = ScheduleStore(app)
    private val _schedules = MutableStateFlow(SchedulesState())
    val schedules = _schedules.asStateFlow()

    private val _update = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
    val update = _update.asStateFlow()

    // ---- case (locali): elenco, assegnazioni dispositivo->casa, casa selezionata (filtro) ----
    private val _homes = MutableStateFlow(homesStore.homes())
    val homes = _homes.asStateFlow()
    private val _assignments = MutableStateFlow(homesStore.assignments())  // did -> homeId
    val assignments = _assignments.asStateFlow()
    private val _selectedHome = MutableStateFlow<String?>(null)            // null = Tutte
    val selectedHome = _selectedHome.asStateFlow()

    fun selectHome(id: String?) { _selectedHome.value = id }

    fun addHome(name: String) {
        val n = name.trim().ifEmpty { return }
        _homes.value = _homes.value + Home(java.util.UUID.randomUUID().toString(), n)
        homesStore.saveHomes(_homes.value)
    }

    fun renameHome(id: String, name: String) {
        val n = name.trim().ifEmpty { return }
        _homes.value = _homes.value.map { if (it.id == id) it.copy(name = n) else it }
        homesStore.saveHomes(_homes.value)
    }

    fun deleteHome(id: String) {
        _homes.value = _homes.value.filterNot { it.id == id }
        homesStore.saveHomes(_homes.value)
        // togli le assegnazioni a quella casa e resetta il filtro se puntava lì
        _assignments.value = _assignments.value.filterValues { it != id }
        homesStore.saveAssignments(_assignments.value)
        if (_selectedHome.value == id) _selectedHome.value = null
    }

    fun assignUnit(unitId: String, homeId: String?) {
        _assignments.value = if (homeId == null) _assignments.value - unitId
                             else _assignments.value + (unitId to homeId)
        homesStore.saveAssignments(_assignments.value)
    }

    // ---- branding produttore: logo scaricato a runtime dal cloud del produttore, mai impacchettato ----
    private val _vendorCode = MutableStateFlow(homesStore.vendorCode())
    val vendorCode = _vendorCode.asStateFlow()
    private val _vendorLogo = MutableStateFlow(homesStore.vendorLogo())
    val vendorLogo = _vendorLogo.asStateFlow()
    private val _vendorBusy = MutableStateFlow(false)
    val vendorBusy = _vendorBusy.asStateFlow()

    /** Imposta il codice costruttore e scarica il logo dal cloud (in cache locale). */
    fun setVendorCode(code: String) {
        val c = code.trim()
        _vendorCode.value = c
        homesStore.setVendorCode(c)
        if (c.isEmpty()) { homesStore.saveVendorLogo(null); _vendorLogo.value = null; return }
        _vendorBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = runCatching { VendorBranding.fetchLogo(service.baseUrl(), c) }.getOrNull()
            if (bytes != null) { homesStore.saveVendorLogo(bytes); _vendorLogo.value = bytes }
            _vendorBusy.value = false
        }
    }

    fun exportConfig(): String = homesStore.exportJson()

    fun importConfig(json: String): Boolean = try {
        homesStore.importJson(json)
        _homes.value = homesStore.homes()
        _assignments.value = homesStore.assignments()
        _selectedHome.value = null
        true
    } catch (e: Exception) { false }

    val appVersion: String = BuildConfig.VERSION_NAME

    // ---- impostazioni: feedback delle operazioni account ----
    private val _settingsMsg = MutableStateFlow<String?>(null)
    val settingsMsg = _settingsMsg.asStateFlow()
    private val _settingsBusy = MutableStateFlow(false)
    val settingsBusy = _settingsBusy.asStateFlow()

    fun accountEmail(): String = service.savedEmail()
    fun clearSettingsMsg() { _settingsMsg.value = null }

    private fun settingsOp(okMsg: String, block: suspend () -> Unit) = viewModelScope.launch {
        _settingsBusy.value = true; _settingsMsg.value = null
        try {
            block(); _settingsMsg.value = okMsg
        } catch (e: Exception) {
            _settingsMsg.value = readable(e)
        } finally {
            _settingsBusy.value = false
        }
    }

    fun changePassword(old: String, new: String) =
        settingsOp(ctx().getString(R.string.settings_password_updated)) { service.changePassword(old, new) }

    fun changeNickname(nick: String) =
        settingsOp(ctx().getString(R.string.settings_name_updated)) { service.changeNickname(nick) }

    private val tempJobs = mutableMapOf<String, Job>()      // debounce temperatura per unità
    private val burstBefore = mutableMapOf<String, AcUnit>() // snapshot per il roll-back del burst temp
    private val fanJobs = mutableMapOf<String, Job>()       // debounce ventola per unità
    private val fanBurstBefore = mutableMapOf<String, AcUnit>() // snapshot roll-back del burst ventola

    fun unit(id: String): AcUnit? = _units.value.firstOrNull { it.id == id }

    init {
        viewModelScope.launch { bootstrap() }
        checkForUpdate()
        startPolling()
    }

    @Volatile private var foreground = true
    /** L'app è in primo piano? Il polling gira solo qui, per non consumare in background.
     *  Tornando in primo piano ricarica SUBITO (non aspetta il prossimo tick del polling). */
    fun setForeground(v: Boolean) {
        val wasBackground = !foreground
        foreground = v
        if (v && wasBackground && _phase.value == Phase.Connected) refresh()
    }

    // ---- stato in tempo reale: rilettura periodica (riflette modifiche fatte col telecomando) ----
    private fun startPolling() = viewModelScope.launch {
        while (true) {
            delay(POLL_MS)
            if (!foreground || _phase.value != Phase.Connected || isBusy()) {
                android.util.Log.i("klima-poll",
                    "skip (fg=$foreground, connesso=${_phase.value == Phase.Connected}, busy=${isBusy()})")
                continue
            }
            val fresh = runCatching { service.loadUnits() }.getOrNull()
            if (fresh == null) { android.util.Log.i("klima-poll", "lettura fallita"); continue }
            if (isBusy()) continue   // un comando può essere partito durante la lettura
            // quante unità hanno lo stato diverso da prima? (0 = il modulo NON riflette il cambio)
            val before = _units.value.associateBy { it.id }
            val changed = fresh.count { f -> before[f.id]?.let { it != f } ?: true }
            android.util.Log.i("klima-poll", "ok: ${fresh.size} unità lette, cambiate=$changed")
            mergeUnits(fresh)
        }
    }

    /** Vero se c'è un comando in volo (invio o debounce temperatura): non aggiornare adesso. */
    private fun isBusy() = _send.value.values.any { it == SendState.Sending } ||
        tempJobs.isNotEmpty() || fanJobs.isNotEmpty()

    /** Applica lo stato fresco senza calpestare le unità con un comando in volo. */
    private fun mergeUnits(fresh: List<AcUnit>) {
        val busy = _send.value.filterValues { it == SendState.Sending }.keys + tempJobs.keys + fanJobs.keys
        _units.value = fresh.map { f -> if (f.id in busy) unit(f.id) ?: f else f }
    }

    /** Controlla se su GitHub c'è una release più recente. Silenzioso: non disturba se fallisce. */
    fun checkForUpdate() = viewModelScope.launch(Dispatchers.IO) {
        _update.value = UpdateChecker(appVersion).check()
    }

    /** All'avvio: prima la sessione salvata, poi (se scaduta) le credenziali salvate, infine login. */
    private suspend fun bootstrap() {
        if (service.hasSession() && service.restore() && tryLoad()) return
        if (service.hasCreds() && runCatching { service.autoLogin() }.getOrDefault(false) && tryLoad()) return
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    fun login(email: String, password: String, remember: Boolean, region: String) {
        _phase.value = Phase.Login(email = email, region = region, busy = true)
        viewModelScope.launch {
            try {
                service.login(email, password, remember, region)
                _phase.value = Phase.Loading
                if (!tryLoad()) _phase.value =
                    Phase.Login(email = email, region = region, error = ctx().getString(R.string.login_no_units))
            } catch (e: Exception) {
                _phase.value = Phase.Login(email = email, region = region, error = readable(e))
            }
        }
    }

    // ---- registrazione nuovo account ----
    fun startRegister() {
        _phase.value = Phase.Register(email = service.savedEmail(), region = service.savedRegion())
    }

    fun cancelRegister() {
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    /** Passo 1: invia il codice di verifica al nuovo account. */
    fun sendCode(email: String, region: String) {
        _phase.value = Phase.Register(email = email, region = region, busy = true)
        viewModelScope.launch {
            try {
                service.sendRegisterCode(email, region)
                _phase.value = Phase.Register(email = email, region = region, codeSent = true)
            } catch (e: Exception) {
                _phase.value = Phase.Register(email = email, region = region, error = readable(e))
            }
        }
    }

    /** Passo 2: crea l'account. Al successo la sessione è già stabilita (register = login). */
    fun doRegister(email: String, password: String, code: String, region: String, nickname: String) {
        _phase.value = Phase.Register(email = email, region = region, codeSent = true, busy = true)
        viewModelScope.launch {
            try {
                service.register(email, password, code, region, nickname)
                _phase.value = Phase.Loading
                // account nuovo: le unità di solito sono zero → Connected con lista vuota
                runCatching { _units.value = service.loadUnits() }
                _phase.value = Phase.Connected
            } catch (e: Exception) {
                _phase.value = Phase.Register(email = email, region = region, codeSent = true, error = readable(e))
            }
        }
    }

    // ---- onboarding di un modulo vergine (config SoftAP) ----
    /** Reset dello stato del wizard (all'apertura). */
    fun startOnboarding() {
        _onboarding.value = OnboardingState()
    }

    /** Manda le credenziali WiFi al modulo in SoftAP. Al successo il wizard avanza a "fatto". */
    fun onboardingSend(ssid: String, password: String, security: Int) {
        _onboarding.value = OnboardingState(busy = true)
        viewModelScope.launch {
            try {
                val resp = SoftApClient.provision(getApplication<Application>(), ssid, password, security)
                _onboarding.value = OnboardingState(sent = true, responded = resp != null)
            } catch (e: Exception) {
                _onboarding.value = OnboardingState(
                    error = ctx().getString(R.string.onboarding_send_failed),
                )
            }
        }
    }

    /** Registra nell'account il modulo appena configurato: discovery+auth in LAN → bind cloud.
     *  Da chiamare col telefono tornato sulla WiFi di casa. */
    fun onboardingBind(name: String) {
        _onboarding.value = _onboarding.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val boundName = service.addNewModule(getApplication(), name.trim())
                _onboarding.value = _onboarding.value.copy(busy = false, bound = true, boundName = boundName)
                runCatching { _units.value = service.loadUnits() }   // rifletti subito la nuova unità
            } catch (e: Exception) {
                _onboarding.value = _onboarding.value.copy(busy = false, error = readable(e))
            }
        }
    }

    /** Chiude il wizard e rilegge l'elenco. */
    fun onboardingDone() {
        _onboarding.value = OnboardingState()
        refresh()
    }

    // ---- pianificazioni (timer) ----
    // ---- pianificazioni lato telefono (l'hardware non ha scheduler nativo: vedi Schedule) ----

    private fun app() = getApplication<Application>()

    /** Ricarica l'elenco per l'unità dallo store locale (istantaneo, niente rete). */
    fun loadSchedules(unitId: String) {
        _schedules.value = SchedulesState(
            schedules = scheduleStore.forUnit(unitId)
                .sortedWith(compareBy({ it.recurring }, { it.hour * 60 + it.minute })),
            canExact = Scheduler.canExact(app()),
        )
    }

    private fun buildAction(turnOn: Boolean, temp: Float?): Map<String, Int> =
        if (turnOn) buildMap {
            put("pwr", 1)
            temp?.let { put("save_temp", (it.coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX) * 10f).roundToInt()) }
        } else mapOf("pwr" to 0)

    /** Timer rapido "una volta": accende/spegne tra [delayMinutes] minuti. */
    fun addQuickTimer(unitId: String, delayMinutes: Int, turnOn: Boolean, temp: Float?) {
        val name = unit(unitId)?.name ?: unitId
        val s = Schedule(
            id = UUID.randomUUID().toString(), unitId = unitId, unitName = name,
            recurring = false, fireAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L,
            action = buildAction(turnOn, temp),
        )
        scheduleStore.upsert(s); Scheduler.arm(app(), s); loadSchedules(unitId)
    }

    /** Timer ricorrente settimanale: accende/spegne a [hour]:[minute] nei giorni scelti. */
    fun addWeeklyTimer(unitId: String, hour: Int, minute: Int, weekday: List<Int>,
                       turnOn: Boolean, temp: Float?) {
        val name = unit(unitId)?.name ?: unitId
        val s = Schedule(
            id = UUID.randomUUID().toString(), unitId = unitId, unitName = name,
            recurring = true, hour = hour, minute = minute, weekday = weekday.sorted(),
            action = buildAction(turnOn, temp),
        )
        scheduleStore.upsert(s); Scheduler.arm(app(), s); loadSchedules(unitId)
    }

    fun toggleSchedule(unitId: String, id: String) {
        val s = scheduleStore.get(id) ?: return
        val updated = s.copy(enabled = !s.enabled)
        scheduleStore.upsert(updated)
        if (updated.enabled) Scheduler.arm(app(), updated) else Scheduler.cancel(app(), updated)
        loadSchedules(unitId)
    }

    fun deleteSchedule(unitId: String, id: String) {
        scheduleStore.get(id)?.let { Scheduler.cancel(app(), it) }
        scheduleStore.delete(id); loadSchedules(unitId)
    }

    /** Esci mantenendo le credenziali salvate (rientro automatico al prossimo avvio). */
    fun logout() {
        service.logout()
        _units.value = emptyList(); _send.value = emptyMap()
        _phase.value = Phase.Login(email = service.savedEmail(), region = service.savedRegion())
    }

    /** Dimentica tutto (sessione + credenziali). */
    fun forget() {
        service.forget()
        _units.value = emptyList(); _send.value = emptyMap()
        _phase.value = Phase.Login(email = "")
    }

    fun refresh() = viewModelScope.launch { runCatching { _units.value = service.loadUnits() } }

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    /** Refresh manuale (tasto ⟳) con feedback visibile: ricarica lo stato reale dal cloud. */
    fun manualRefresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val job = launch { runCatching { _units.value = service.loadUnits() } }
            delay(500)          // garantisce che lo spinner sia percepibile
            job.join()
            _refreshing.value = false
        }
    }

    private suspend fun tryLoad(): Boolean = try {
        _units.value = service.loadUnits(); _phase.value = Phase.Connected; true
    } catch (e: Exception) {
        false
    }

    // ---- stato d'invio ----
    private fun setSend(id: String, s: SendState) { _send.value = _send.value + (id to s) }
    private fun holdThenIdle(id: String, ms: Long) = viewModelScope.launch {
        delay(ms)
        if (_send.value[id] != SendState.Sending) setSend(id, SendState.Idle)
    }
    private fun setUnit(u: AcUnit) { _units.value = _units.value.map { if (it.id == u.id) u else it } }

    // ---- comando immediato (accende, modalità, ventola, funzioni) ----
    private fun immediate(id: String, apply: (AcUnit) -> AcUnit, changes: (AcUnit) -> Map<String, Int>) {
        val before = unit(id) ?: return
        val after = apply(before); setUnit(after)
        setSend(id, SendState.Sending)
        viewModelScope.launch {
            try {
                service.push(id, changes(after)); setSend(id, SendState.Ok); holdThenIdle(id, OK_HOLD_MS)
            } catch (e: Exception) {
                setUnit(before); setSend(id, SendState.Error); holdThenIdle(id, ERR_HOLD_MS)
            }
        }
    }

    // ---- comando con debounce: aggiorna subito lo stato, ma invia UNA sola volta dopo la quiete.
    //      Usato da temperatura e ventola (slider/step): niente un comando — e un bip — per passo. ----
    private fun debounced(id: String, jobs: MutableMap<String, Job>, snapshots: MutableMap<String, AcUnit>,
                          apply: (AcUnit) -> AcUnit, wire: (AcUnit) -> Map<String, Int>) {
        val cur = unit(id) ?: return
        if (!jobs.containsKey(id)) snapshots[id] = cur   // inizio burst
        setUnit(apply(cur))
        setSend(id, SendState.Sending)
        jobs[id]?.cancel()
        jobs[id] = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val before = snapshots.remove(id) ?: cur
            val u = unit(id) ?: return@launch
            try {
                service.push(id, wire(u)); setSend(id, SendState.Ok); holdThenIdle(id, OK_HOLD_MS)
            } catch (e: Exception) {
                setUnit(before); setSend(id, SendState.Error); holdThenIdle(id, ERR_HOLD_MS)
            } finally {
                jobs.remove(id)
            }
        }
    }

    private fun debouncedTarget(id: String, newTarget: (AcUnit) -> Float) =
        debounced(id, tempJobs, burstBefore,
            { it.copy(targetTemp = clampT(newTarget(it))) }, { targetWire(it.targetTemp) })

    fun stepTarget(id: String, d: Float) = debouncedTarget(id) { it.targetTemp + d }
    fun setTarget(id: String, t: Float) = debouncedTarget(id) { t }

    fun togglePower(id: String) = immediate(id, { it.copy(power = !it.power) }, { powerWire(it.power) })
    fun setMode(id: String, m: Mode) = immediate(id, { it.copy(mode = m) }, { modeChangeWire(it.mode) })
    // ventola: debounce come la temperatura — trascinando lo slider parte un solo comando alla fine
    fun setFan(id: String, f: FanSpeed) =
        debounced(id, fanJobs, fanBurstBefore, { it.copy(fan = f) }, { fanChangeWire(it.fan) })
    fun toggleSwingV(id: String) = immediate(id, { it.copy(swingV = !it.swingV) }, { swingVWire(it.swingV) })
    fun toggleSwingH(id: String) = immediate(id, { it.copy(swingH = !it.swingH) }, { swingHWire(it.swingH) })
    fun toggleEco(id: String) = immediate(id, { it.copy(eco = !it.eco, turbo = false, night = false) }, { ecoWire(it.eco) })
    fun toggleTurbo(id: String) = immediate(id, { it.copy(turbo = !it.turbo, eco = false, night = false) }, { turboWire(it.turbo) })
    fun toggleNight(id: String) = immediate(id, { it.copy(night = !it.night, eco = false, turbo = false) }, { nightWire(it.night) })
    /** Le unità della casa selezionata (o tutte se il filtro è "Tutte"): le azioni di massa
     *  (spegni tutte / rinfresca casa) agiscono su queste, coerenti con ciò che si vede. */
    private fun visibleUnits(): List<AcUnit> {
        val h = _selectedHome.value ?: return _units.value
        return _units.value.filter { _assignments.value[it.id] == h }
    }

    fun powerAllOff() = visibleUnits().filter { it.power }.forEach { togglePower(it.id) }

    /** Rinfresca casa: accende le unità online (della casa vista) in freddo, 16°, ventola al massimo. */
    fun refreshHouse() = visibleUnits().filter { it.online }.forEach { u ->
        immediate(
            u.id,
            { it.copy(power = true, mode = Mode.FREDDO, targetTemp = AcUnit.TEMP_MIN,
                      fan = FanSpeed.ALTA, eco = false, turbo = false, night = false) },
            { powerWire(true) + modeChangeWire(Mode.FREDDO) + targetWire(AcUnit.TEMP_MIN) +
                fanChangeWire(FanSpeed.ALTA) },
        )
    }

    private fun clampT(t: Float) = t.coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX)

    /** Context con la lingua scelta dall'utente, per risolvere stringhe fuori da @Composable. */
    private fun ctx() = LocaleStore.wrap(getApplication())

    private fun readable(e: Exception): String {
        val c = ctx()
        val m = e.message ?: return c.getString(R.string.error_op_failed)
        return when {
            "-1006" in m || "-1008" in m -> c.getString(R.string.error_bad_credentials)
            "-1036" in m -> c.getString(R.string.error_too_many_attempts)
            "has_been_registered" in m -> c.getString(R.string.error_already_registered)
            "vcode" in m || "-3002" in m -> c.getString(R.string.error_bad_vcode)
            // fallback: mai mostrare il messaggio cinese grezzo del server, solo il codice
            else -> Regex("-?\\d{3,5}").find(m)?.let { c.getString(R.string.error_op_failed_code, it.value) }
                ?: c.getString(R.string.error_op_generic)
        }
    }
}
