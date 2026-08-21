package net.klimakontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import net.klimakontrol.ui.DetailScreen
import net.klimakontrol.ui.HomeScreen
import net.klimakontrol.ui.KlimaViewModel
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.KlimaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KlimaTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot(vm: KlimaViewModel = viewModel()) {
    val units by vm.units.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val bg = Klima.colors.bg

    AnimatedContent(
        targetState = selected,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = Modifier.fillMaxSize().background(bg),
        label = "nav",
    ) { sel ->
        val current = units.firstOrNull { it.id == sel }
        if (sel == null || current == null) {
            HomeScreen(
                units = units,
                onOpen = { selected = it.id },
                onTogglePower = { vm.togglePower(it.id) },
                onPowerAllOff = { vm.powerAllOff() },
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
            )
        }
    }
}
