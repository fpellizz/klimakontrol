package net.klimakontrol.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
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
    onAddDevice: () -> Unit,
    vendorCode: String,
    vendorLogo: ByteArray?,
    vendorBusy: Boolean,
    onSetVendorCode: (String) -> Unit,
    onLogout: () -> Unit,
    onForget: () -> Unit,
    currentLanguage: String,
    onSetLanguage: (String) -> Unit,
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
            Text(stringResource(R.string.settings_title), style = QuadType.title, color = c.ink)
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
            Section(stringResource(R.string.settings_section_account)) {
                Info(stringResource(R.string.settings_email_label), email.ifBlank { "—" })
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.settings_new_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Action(stringResource(R.string.settings_save_name), enabled = !busy && nickname.isNotBlank(), accent = accent) {
                    onChangeNickname(nickname.trim())
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_change_password), style = QuadType.name, color = c.ink)
                Spacer(Modifier.height(8.dp))
                PasswordField(oldPw, { oldPw = it }, stringResource(R.string.settings_current_password), Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PasswordField(newPw, { newPw = it }, stringResource(R.string.settings_new_password), Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Action(stringResource(R.string.settings_change_password),
                    enabled = !busy && oldPw.isNotBlank() && newPw.length >= 6, accent = accent) {
                    onChangePassword(oldPw, newPw); oldPw = ""; newPw = ""
                }
            }

            // ---- app / aggiornamenti ----
            Section(stringResource(R.string.settings_section_app)) {
                Info(stringResource(R.string.settings_version), version)
                Spacer(Modifier.height(6.dp))
                val uriHandler = LocalUriHandler.current
                when (val u = update) {
                    is UpdateStatus.Available -> {
                        Text(stringResource(R.string.settings_update_available, u.latest), style = QuadType.body, color = accent)
                        Spacer(Modifier.height(8.dp))
                        Action(stringResource(R.string.settings_download_update), enabled = true, accent = accent) {
                            uriHandler.openUri(u.htmlUrl)
                        }
                    }
                    is UpdateStatus.UpToDate -> Text(stringResource(R.string.settings_up_to_date), style = QuadType.body, color = c.ink2)
                    is UpdateStatus.Unknown -> {
                        Action(stringResource(R.string.settings_check_updates), enabled = !busy, accent = c.surface2, textColor = c.ink) {
                            onCheckUpdate()
                        }
                    }
                }
            }

            // ---- lingua (selettore: sistema / italiano / inglese) ----
            Section(stringResource(R.string.settings_section_language)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeChip(stringResource(R.string.language_system), currentLanguage == "system") { onSetLanguage("system") }
                    HomeChip(stringResource(R.string.language_it), currentLanguage == "it") { onSetLanguage("it") }
                    HomeChip(stringResource(R.string.language_en), currentLanguage == "en") { onSetLanguage("en") }
                }
            }

            // ---- dispositivi: aggiungi un modulo vergine (config SoftAP) ----
            Section(stringResource(R.string.settings_section_devices)) {
                Text(stringResource(R.string.settings_devices_desc), style = QuadType.body, color = c.ink2)
                Spacer(Modifier.height(10.dp))
                Action(stringResource(R.string.add_ac), enabled = true, accent = accent) { onAddDevice() }
            }

            // ---- zone (gestione locale: gruppi/filtri definiti dall'utente) ----
            Section(stringResource(R.string.settings_section_zones)) {
                Text(stringResource(R.string.settings_zones_desc),
                    style = QuadType.body, color = c.ink2)
                Spacer(Modifier.height(10.dp))
                Action(stringResource(R.string.manage_zones), enabled = true, accent = c.surface2, textColor = c.ink) { onManageHomes() }
            }

            // ---- hardware / branding produttore (logo scaricato a runtime dal cloud del produttore) ----
            Section(stringResource(R.string.settings_section_hardware)) {
                vendorLogo?.let { bytes ->
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    if (bmp != null) {
                        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
                            Image(bitmap = bmp, contentDescription = stringResource(R.string.vendor_logo_desc),
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                Text(stringResource(R.string.settings_vendor_hint),
                    style = QuadType.micro, color = c.ink3)
                Spacer(Modifier.height(8.dp))
                var code by remember(vendorCode) { mutableStateOf(vendorCode) }
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_vendor_code_label)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Action(if (vendorBusy) stringResource(R.string.settings_vendor_downloading) else stringResource(R.string.settings_vendor_apply), enabled = !vendorBusy, accent = accent) {
                    onSetVendorCode(code)
                }
            }

            // ---- fuso orario (informativo: nessuna impostazione server) ----
            Section(stringResource(R.string.settings_section_timezone)) {
                Text(stringResource(R.string.settings_timezone_desc),
                    style = QuadType.body, color = c.ink2)
            }

            // ---- sessione ----
            Section(stringResource(R.string.settings_section_session)) {
                Action(stringResource(R.string.settings_logout), enabled = true, accent = c.surface2, textColor = c.ink) { onLogout() }
                Spacer(Modifier.height(8.dp))
                Action(stringResource(R.string.settings_forget), enabled = true, accent = c.surface1, textColor = c.error) { onForget() }
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
