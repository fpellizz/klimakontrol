package net.klimakontrol.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import net.klimakontrol.data.Mode
import net.klimakontrol.data.update.UpdateStatus
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

@Composable
fun SettingsScreen(
    email: String,
    version: String,
    update: UpdateStatus,
    busy: Boolean,
    message: String?,
    onCheckUpdate: () -> Unit,
    onChangeNickname: (String) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onManageHomes: () -> Unit,
    beep: Boolean,
    onToggleBeep: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent
    var nickname by remember { mutableStateOf("") }
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // app bar
        Row(
            Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(c.ink, onBack)
            Spacer(Modifier.width(10.dp))
            Text("Impostazioni", style = QuadType.title, color = c.ink)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // messaggio esito (sopra tutto, visibile dopo un'operazione)
            message?.let {
                val ok = it.endsWith("✓")
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (ok) c.ok.copy(alpha = 0.16f) else c.error.copy(alpha = 0.16f))
                    .padding(12.dp)) {
                    Text(it, style = QuadType.body, color = if (ok) c.ok else c.error)
                }
            }

            // ---- account ----
            Section("Account") {
                Info("Email", email.ifBlank { "—" })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text("Nuovo nome") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Action("Salva nome", enabled = !busy && nickname.isNotBlank(), accent = accent) {
                    onChangeNickname(nickname.trim())
                }

                Spacer(Modifier.height(16.dp))
                Text("Cambia password", style = QuadType.name, color = c.ink)
                Spacer(Modifier.height(8.dp))
                PasswordField(oldPw, { oldPw = it }, "Password attuale", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PasswordField(newPw, { newPw = it }, "Nuova password", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Action("Cambia password",
                    enabled = !busy && oldPw.isNotBlank() && newPw.length >= 6, accent = accent) {
                    onChangePassword(oldPw, newPw); oldPw = ""; newPw = ""
                }
            }

            // ---- app / aggiornamenti ----
            Section("App") {
                Info("Versione", version)
                Spacer(Modifier.height(6.dp))
                val uriHandler = LocalUriHandler.current
                when (val u = update) {
                    is UpdateStatus.Available -> {
                        Text("Aggiornamento disponibile: v${u.latest}", style = QuadType.body, color = accent)
                        Spacer(Modifier.height(8.dp))
                        Action("Scarica l'aggiornamento", enabled = true, accent = accent) {
                            uriHandler.openUri(u.htmlUrl)
                        }
                    }
                    is UpdateStatus.UpToDate -> Text("Sei all'ultima versione.", style = QuadType.body, color = c.ink2)
                    is UpdateStatus.Unknown -> {
                        Action("Controlla aggiornamenti", enabled = !busy, accent = c.surface2, textColor = c.ink) {
                            onCheckUpdate()
                        }
                    }
                }
            }

            // ---- zone (gestione locale: gruppi/filtri definiti dall'utente) ----
            Section("Zone") {
                Text("Raggruppa i climatizzatori per zona (piano terra, zona notte…) e filtra la Home. Tutto in locale.",
                    style = QuadType.body, color = c.ink2)
                Spacer(Modifier.height(10.dp))
                Action("Gestisci zone", enabled = true, accent = c.surface2, textColor = c.ink) { onManageHomes() }
            }

            // ---- suono (sperimentale: dipende dal firmware del modulo) ----
            Section("Suono") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bip del climatizzatore", style = QuadType.body, color = c.ink)
                        Text("Attivo: fa bip a ogni comando. Disattivo: silenzioso (applicato subito).",
                            style = QuadType.micro, color = c.ink3)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = beep, onCheckedChange = onToggleBeep,
                        colors = SwitchDefaults.colors(checkedTrackColor = accent))
                }
            }

            // ---- fuso orario (informativo: nessuna impostazione server) ----
            Section("Fuso orario") {
                Text("Gestito automaticamente. I moduli ragionano in UTC+8 e la conversione è fatta " +
                    "dall'app col fuso del telefono: non c'è nulla da impostare.",
                    style = QuadType.body, color = c.ink2)
            }

            // ---- sessione ----
            Section("Sessione") {
                Action("Esci", enabled = true, accent = c.surface2, textColor = c.ink) { onLogout() }
                Spacer(Modifier.height(8.dp))
                Action("Dimentica credenziali", enabled = true, accent = c.surface1, textColor = c.error) { onForget() }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    val c = Klima.colors
    Column {
        Text(label.uppercase(), style = QuadType.overline, color = c.ink3)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    val c = Klima.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = QuadType.body, color = c.ink2, modifier = Modifier.weight(1f))
        Text(value, style = QuadType.body, color = c.ink)
    }
}

@Composable
private fun Action(
    label: String, enabled: Boolean, accent: Color, textColor: Color = Color(0xFF10161A),
    onClick: () -> Unit,
) {
    val c = Klima.colors
    Box(
        Modifier.pressClickable({ onClick() }, enabled = enabled)
            .fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) accent else c.surfaceOff),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = QuadType.name, color = if (enabled) textColor else c.ink3)
    }
}
