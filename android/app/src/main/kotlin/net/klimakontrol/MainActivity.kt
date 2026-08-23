package net.klimakontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import net.klimakontrol.data.AcUnit
import net.klimakontrol.ui.DetailScreen
import net.klimakontrol.ui.HomeScreen
import net.klimakontrol.ui.KlimaViewModel
import net.klimakontrol.ui.Phase
import net.klimakontrol.ui.SendState
import net.klimakontrol.ui.HomesScreen
import net.klimakontrol.ui.LoginScreen
import net.klimakontrol.ui.RegisterScreen
import net.klimakontrol.ui.SettingsScreen
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.KlimaTheme

/** Valori speciali di `selected` per aprire schermate non legate a una singola unità. */
private const val SETTINGS = "__settings__"
private const val HOMES = "__homes__"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KlimaTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot(vm: KlimaViewModel = viewModel()) {
    val phase by vm.phase.collectAsState()
    val units by vm.units.collectAsState()
    val c = Klima.colors

    when (val p = phase) {
        is Phase.Loading -> Box(
            Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = c.mode(net.klimakontrol.data.Mode.FREDDO).accent) }

        is Phase.Login -> LoginScreen(
            p, onLogin = { e, pw, rem, reg -> vm.login(e, pw, rem, reg) },
            onCreateAccount = { vm.startRegister() },
        )

        is Phase.Register -> RegisterScreen(
            p,
            onSendCode = { e, reg -> vm.sendCode(e, reg) },
            onRegister = { e, pw, code, reg, nick -> vm.doRegister(e, pw, code, reg, nick) },
            onBack = { vm.cancelRegister() },
        )

        is Phase.Connected -> Connected(vm, units)
    }
}

/** Traccia il primo piano (per il polling) e rilegge lo stato al ritorno dell'app,
 *  così l'app riflette le modifiche fatte col telecomando durante l'uso promiscuo. */
@Composable
private fun LifecycleBridge(vm: KlimaViewModel) {
    val owner = LocalContext.current as? LifecycleOwner ?: return
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.setForeground(true)
                Lifecycle.Event.ON_RESUME -> vm.refresh()
                Lifecycle.Event.ON_STOP -> vm.setForeground(false)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}

@Composable
private fun Connected(vm: KlimaViewModel, units: List<AcUnit>) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val send by vm.send.collectAsState()
    LifecycleBridge(vm)
    val update by vm.update.collectAsState()
    val settingsMsg by vm.settingsMsg.collectAsState()
    val settingsBusy by vm.settingsBusy.collectAsState()
    val homes by vm.homes.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val selectedHome by vm.selectedHome.collectAsState()
    val beep by vm.beep.collectAsState()
    val c = Klima.colors

    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f)) togetherWith
                (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 1.01f))
        },
        modifier = Modifier.fillMaxSize().background(c.bg),
        label = "nav",
    ) { sel ->
        val current = units.firstOrNull { it.id == sel }
        if (sel == SETTINGS) {
            SettingsScreen(
                email = vm.accountEmail(),
                version = vm.appVersion,
                update = update,
                busy = settingsBusy,
                message = settingsMsg,
                onCheckUpdate = { vm.checkForUpdate() },
                onChangeNickname = { vm.changeNickname(it) },
                onChangePassword = { o, n -> vm.changePassword(o, n) },
                onManageHomes = { selected = HOMES },
                beep = beep,
                onToggleBeep = { vm.setBeep(it) },
                onLogout = { vm.logout() },
                onForget = { vm.forget() },
                onBack = { vm.clearSettingsMsg(); selected = null },
            )
        } else if (sel == HOMES) {
            HomesScreen(
                homes = homes,
                units = units,
                assignments = assignments,
                onAddHome = { vm.addHome(it) },
                onRenameHome = { id, name -> vm.renameHome(id, name) },
                onDeleteHome = { vm.deleteHome(it) },
                onAssign = { u, h -> vm.assignUnit(u, h) },
                onExport = { vm.exportConfig() },
                onImport = { vm.importConfig(it) },
                onBack = { selected = SETTINGS },
            )
        } else if (sel == null || current == null) {
            HomeScreen(
                units = units,
                onOpen = { selected = it.id },
                onTogglePower = { vm.togglePower(it.id) },
                onPowerAllOff = { vm.powerAllOff() },
                onRefreshHouse = { vm.refreshHouse() },
                onRefresh = { vm.refresh() },
                onSettings = { vm.clearSettingsMsg(); selected = SETTINGS },
                update = update,
                homes = homes,
                assignments = assignments,
                selectedHome = selectedHome,
                onSelectHome = { vm.selectHome(it) },
                send = send,
            )
        } else {
            DetailScreen(
                unit = current,
                onBack = { selected = null },
                onTogglePower = { vm.togglePower(current.id) },
                onStep = { d -> vm.stepTarget(current.id, d) },
                onSetTarget = { t -> vm.setTarget(current.id, t) },
                onSetMode = { m -> vm.setMode(current.id, m) },
                onSetFan = { f -> vm.setFan(current.id, f) },
                onToggleEco = { vm.toggleEco(current.id) },
                onToggleTurbo = { vm.toggleTurbo(current.id) },
                onToggleNight = { vm.toggleNight(current.id) },
                onToggleSwingV = { vm.toggleSwingV(current.id) },
                onToggleSwingH = { vm.toggleSwingH(current.id) },
                send = send[current.id] ?: SendState.Idle,
            )
        }
    }
}
