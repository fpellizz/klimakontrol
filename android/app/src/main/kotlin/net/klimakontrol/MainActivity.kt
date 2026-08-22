package net.klimakontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.klimakontrol.data.AcUnit
import net.klimakontrol.ui.DetailScreen
import net.klimakontrol.ui.HomeScreen
import net.klimakontrol.ui.KlimaViewModel
import net.klimakontrol.ui.Phase
import net.klimakontrol.ui.SendState
import net.klimakontrol.ui.LoginScreen
import net.klimakontrol.ui.RegisterScreen
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.KlimaTheme

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

@Composable
private fun Connected(vm: KlimaViewModel, units: List<AcUnit>) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val send by vm.send.collectAsState()
    val c = Klima.colors

    AnimatedContent(
        targetState = selected,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = Modifier.fillMaxSize().background(c.bg),
        label = "nav",
    ) { sel ->
        val current = units.firstOrNull { it.id == sel }
        if (sel == null || current == null) {
            HomeScreen(
                units = units,
                onOpen = { selected = it.id },
                onTogglePower = { vm.togglePower(it.id) },
                onPowerAllOff = { vm.powerAllOff() },
                onRefresh = { vm.refresh() },
                onSettings = { vm.logout() },
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
