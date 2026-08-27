package net.klimakontrol.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

/**
 * Wizard per aggiungere un climatizzatore "da zero" (modulo vergine): config SoftAP.
 * Quattro passi: introduzione → credenziali WiFi di casa → connessione all'hotspot del
 * modulo → invio. La chiave del filo è il pacchetto ricostruito (SoftApClient).
 *
 * Vincolo Android (MVP): la connessione all'hotspot del modulo la fa l'utente a mano nelle
 * impostazioni WiFi; l'app poi manda l'UDP legando il socket alla rete del modulo
 * (vedi SoftApClient, altrimenti su un telefono con dati mobili il pacchetto uscirebbe dal
 * cellulare). Approccio senza permessi nuovi né API WiFi fragili.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onSend: (String, String, Int) -> Unit,
    onBind: (String) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent

    var step by rememberSaveable { mutableStateOf(0) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var security by rememberSaveable { mutableStateOf(3) }   // WPA2 di default

    // config inviata → passo "aggiungi all'account"; bind riuscito → passo finale
    LaunchedEffect(state.sent) { if (state.sent) step = 3 }
    LaunchedEffect(state.bound) { if (state.bound) step = 4 }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // app bar: indietro fra i passi, o esci dal wizard
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(c.ink) { if (step in 1..2) step-- else onBack() }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.add_ac), style = QuadType.title, color = c.ink)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StepDots(current = step, total = 5, accent = accent)

            when (step) {
                0 -> IntroStep(accent) { step = 1 }
                1 -> WifiStep(
                    ssid = ssid, onSsid = { ssid = it },
                    password = password, onPassword = { password = it },
                    security = security, onSecurity = { security = it },
                    accent = accent, onNext = { step = 2 },
                )
                2 -> ConnectStep(
                    state = state, accent = accent,
                    onSend = { onSend(ssid.trim(), password, security) },
                )
                3 -> AddStep(state = state, accent = accent, onBind = onBind)
                else -> DoneStep(state = state, accent = accent, onFinish = onFinish)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntroStep(accent: Color, onStart: () -> Unit) {
    val c = Klima.colors
    Text(stringResource(R.string.onboarding_intro_lead),
        style = QuadType.body, color = c.ink2)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberedLine(1, stringResource(R.string.onboarding_step1), accent)
        NumberedLine(2, stringResource(R.string.onboarding_step2), accent)
        NumberedLine(3, stringResource(R.string.onboarding_step3), accent)
        NumberedLine(4, stringResource(R.string.onboarding_step4), accent)
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1).padding(14.dp)) {
        Text(stringResource(R.string.onboarding_config_hint),
            style = QuadType.micro, color = c.ink3)
    }

    PrimaryButton(stringResource(R.string.onboarding_start), busy = false, enabled = true, accent = accent, onClick = onStart)
}

@Composable
private fun WifiStep(
    ssid: String, onSsid: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    security: Int, onSecurity: (Int) -> Unit,
    accent: Color, onNext: () -> Unit,
) {
    val c = Klima.colors
    Text(stringResource(R.string.onboarding_wifi_title), style = QuadType.name, color = c.ink)
    OutlinedTextField(
        value = ssid, onValueChange = onSsid,
        label = { Text(stringResource(R.string.onboarding_ssid_label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(password, onPassword, stringResource(R.string.onboarding_wifi_password), Modifier.fillMaxWidth())
    SecurityChips(security, onSecurity)
    Text(stringResource(R.string.onboarding_wifi_hint),
        style = QuadType.micro, color = c.ink3)
    PrimaryButton(stringResource(R.string.onboarding_next), busy = false, enabled = ssid.isNotBlank(), accent = accent, onClick = onNext)
}

@Composable
private fun ConnectStep(state: OnboardingState, accent: Color, onSend: () -> Unit) {
    val c = Klima.colors
    val context = LocalContext.current
    Text(stringResource(R.string.onboarding_connect_title), style = QuadType.name, color = c.ink)
    Text(stringResource(R.string.onboarding_connect_desc),
        style = QuadType.body, color = c.ink2)

    Text(stringResource(R.string.onboarding_open_wifi), style = QuadType.name, color = accent,
        modifier = Modifier.clickable {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.padding(vertical = 4.dp))

    state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

    PrimaryButton(stringResource(R.string.onboarding_send), busy = state.busy, enabled = !state.busy,
        accent = accent, onClick = onSend)
}

@Composable
private fun AddStep(state: OnboardingState, accent: Color, onBind: (String) -> Unit) {
    val c = Klima.colors
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }

    Text(stringResource(R.string.onboarding_add_title), style = QuadType.name, color = c.ink)
    Text(stringResource(R.string.onboarding_add_desc), style = QuadType.body, color = c.ink2)

    Text(stringResource(R.string.onboarding_reconnect_wifi), style = QuadType.name, color = accent,
        modifier = Modifier.clickable {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.padding(vertical = 4.dp))

    OutlinedTextField(
        value = name, onValueChange = { name = it },
        label = { Text(stringResource(R.string.onboarding_name_label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

    PrimaryButton(stringResource(R.string.onboarding_search_add), busy = state.busy, enabled = !state.busy,
        accent = accent, onClick = { onBind(name.trim()) })
}

@Composable
private fun DoneStep(state: OnboardingState, accent: Color, onFinish: () -> Unit) {
    val c = Klima.colors
    Text(stringResource(R.string.onboarding_done_title), style = QuadType.title, color = c.ink)
    Text(
        state.boundName?.let { stringResource(R.string.onboarding_bound, it) }
            ?: stringResource(R.string.onboarding_done_hint),
        style = QuadType.body, color = c.ink2,
    )
    PrimaryButton(stringResource(R.string.onboarding_finish), busy = false, enabled = true, accent = accent, onClick = onFinish)
}

// ---- pezzi riusabili locali (coerenti con Register/Settings) ----

private val SECURITY_OPTIONS = listOf(
    0 to R.string.security_open,
    1 to R.string.security_wep,
    2 to R.string.security_wpa,
    3 to R.string.security_wpa2,
    4 to R.string.security_wpa12,
)

@Composable
private fun SecurityChips(selected: Int, onSelect: (Int) -> Unit) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO)
    Column {
        Text(stringResource(R.string.security_heading), style = QuadType.overline, color = c.ink3)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SECURITY_OPTIONS.forEach { (value, labelResId) ->
                val sel = value == selected
                Box(
                    Modifier.pressClickable(onClick = { onSelect(value) })
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) accent.container else c.surface1)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(labelResId), style = QuadType.body, color = if (sel) accent.on else c.ink2)
                }
            }
        }
    }
}

@Composable
private fun NumberedLine(n: Int, text: String, accent: Color) {
    val c = Klima.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center) {
            Text("$n", style = QuadType.micro.copy(fontWeight = FontWeight.SemiBold), color = accent)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = QuadType.body, color = c.ink)
    }
}

@Composable
private fun StepDots(current: Int, total: Int, accent: Color) {
    val c = Klima.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            Box(
                Modifier.height(4.dp).width(if (i == current) 22.dp else 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= current) accent else c.border),
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    label: String, busy: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit,
) {
    Button(
        onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF10161A)),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
        else Text(label, style = QuadType.name)
    }
}
