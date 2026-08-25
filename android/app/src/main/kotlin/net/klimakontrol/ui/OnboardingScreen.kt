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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

/**
 * Wizard per aggiungere un climatizzatore "da zero" (modulo vergine): config SoftAP.
 * Quattro passi: introduzione → credenziali WiFi di casa → connessione all'hotspot del
 * modulo → invio. La chiave del filo è il pacchetto ricostruito (SoftApClient).
 *
 * Vincolo Android (MVP): la connessione all'hotspot del modulo la fa l'utente a mano nelle
 * impostazioni WiFi; l'app poi manda l'UDP. Semplice e robusto su ogni versione.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onSend: (String, String, Int) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent

    var step by rememberSaveable { mutableStateOf(0) }
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var security by rememberSaveable { mutableStateOf(3) }   // WPA2 di default

    // quando l'invio riesce, avanza all'ultimo passo
    LaunchedEffect(state.sent) { if (state.sent) step = 3 }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // app bar: indietro fra i passi, o esci dal wizard
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(c.ink) { if (step in 1..2) step-- else onBack() }
            Spacer(Modifier.width(10.dp))
            Text("Aggiungi climatizzatore", style = QuadType.title, color = c.ink)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StepDots(current = step, total = 4, accent = accent)

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
                else -> DoneStep(state = state, accent = accent, onFinish = onFinish)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IntroStep(accent: Color, onStart: () -> Unit) {
    val c = Klima.colors
    Text("Colleghiamo il modulo WiFi del climatizzatore alla tua rete di casa — senza l'app ufficiale.",
        style = QuadType.body, color = c.ink2)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NumberedLine(1, "Metti il climatizzatore in modalità configurazione", accent)
        NumberedLine(2, "Inserisci la password del tuo WiFi", accent)
        NumberedLine(3, "Connetti il telefono all'hotspot del climatizzatore", accent)
        NumberedLine(4, "Invio: il modulo entra nella tua rete", accent)
    }

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface1).padding(14.dp)) {
        Text("Per la modalità configurazione usa lo stesso gesto dell'app ufficiale: di solito si tiene " +
            "premuto un tasto finché non compare una rete WiFi che inizia con «Broadlink_tcl_».",
            style = QuadType.micro, color = c.ink3)
    }

    PrimaryButton("Inizia", busy = false, enabled = true, accent = accent, onClick = onStart)
}

@Composable
private fun WifiStep(
    ssid: String, onSsid: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    security: Int, onSecurity: (Int) -> Unit,
    accent: Color, onNext: () -> Unit,
) {
    val c = Klima.colors
    Text("La tua rete WiFi", style = QuadType.name, color = c.ink)
    OutlinedTextField(
        value = ssid, onValueChange = onSsid,
        label = { Text("Nome rete (SSID)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(password, onPassword, "Password WiFi", Modifier.fillMaxWidth())
    SecurityChips(security, onSecurity)
    Text("Il modulo si collega solo a reti a 2.4 GHz. Di solito la sicurezza è WPA2.",
        style = QuadType.micro, color = c.ink3)
    PrimaryButton("Avanti", busy = false, enabled = ssid.isNotBlank(), accent = accent, onClick = onNext)
}

@Composable
private fun ConnectStep(state: OnboardingState, accent: Color, onSend: () -> Unit) {
    val c = Klima.colors
    val context = LocalContext.current
    Text("Connetti il telefono al climatizzatore", style = QuadType.name, color = c.ink)
    Text("Apri le impostazioni WiFi e connettiti alla rete «Broadlink_tcl_…» del climatizzatore. " +
        "È una rete senza internet: va bene. Poi torna qui e invia.",
        style = QuadType.body, color = c.ink2)

    Text("Apri impostazioni WiFi", style = QuadType.name, color = accent,
        modifier = Modifier.clickable {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.padding(vertical = 4.dp))

    state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

    PrimaryButton("Invia credenziali", busy = state.busy, enabled = !state.busy,
        accent = accent, onClick = onSend)
}

@Composable
private fun DoneStep(state: OnboardingState, accent: Color, onFinish: () -> Unit) {
    val c = Klima.colors
    Text("Fatto ✓", style = QuadType.title, color = c.ok)
    Text("Credenziali inviate. Il climatizzatore si sta connettendo alla tua rete. Riconnetti il " +
        "telefono al WiFi di casa: tra poco l'unità comparirà nell'elenco.",
        style = QuadType.body, color = c.ink2)
    if (state.responded) {
        Text("Il modulo ha risposto alla configurazione.", style = QuadType.micro, color = c.ink3)
    }
    PrimaryButton("Fine", busy = false, enabled = true, accent = accent, onClick = onFinish)
}

// ---- pezzi riusabili locali (coerenti con Register/Settings) ----

private val SECURITY_OPTIONS = listOf(0 to "Aperta", 1 to "WEP", 2 to "WPA", 3 to "WPA2", 4 to "WPA1/2")

@Composable
private fun SecurityChips(selected: Int, onSelect: (Int) -> Unit) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO)
    Column {
        Text("SICUREZZA", style = QuadType.overline, color = c.ink3)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SECURITY_OPTIONS.forEach { (value, label) ->
                val sel = value == selected
                Box(
                    Modifier.pressClickable(onClick = { onSelect(value) })
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) accent.container else c.surface1)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(label, style = QuadType.body, color = if (sel) accent.on else c.ink2)
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
