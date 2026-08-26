package net.klimakontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

/**
 * Registrazione di un nuovo account in due passi (come la CLI e l'app ufficiale):
 * 1) email + regione → invio del codice di verifica;
 * 2) codice + password → creazione account (che è anche login: la sessione è già pronta).
 */
@Composable
fun RegisterScreen(
    state: Phase.Register,
    onSendCode: (String, String) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent
    var email by rememberSaveable { mutableStateOf(state.email) }
    var region by rememberSaveable { mutableStateOf(state.region) }
    var code by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.create_account), style = QuadType.title, color = c.ink)

            if (!state.codeSent) {
                Text(stringResource(R.string.register_intro),
                    style = QuadType.body, color = c.ink2)
                Spacer(Modifier.height(2.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text(stringResource(R.string.email_or_phone_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                RegionChips(region) { region = it }

                state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

                Spacer(Modifier.height(4.dp))
                PrimaryButton(stringResource(R.string.register_send_code), busy = state.busy,
                    enabled = !state.busy && email.isNotBlank(), accent = accent) {
                    onSendCode(email.trim(), region)
                }
            } else {
                Text(stringResource(R.string.register_code_sent, email.trim(), region.uppercase()),
                    style = QuadType.body, color = c.ink2)
                Spacer(Modifier.height(2.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it },
                    label = { Text(stringResource(R.string.register_code_label)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(password, { password = it }, stringResource(R.string.register_password_new), Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = nickname, onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.register_name_optional)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { Text(it, style = QuadType.micro, color = c.error) }

                Spacer(Modifier.height(4.dp))
                PrimaryButton(stringResource(R.string.create_account), busy = state.busy,
                    enabled = !state.busy && code.isNotBlank() && password.isNotBlank(), accent = accent) {
                    onRegister(email.trim(), password, code.trim(), region, nickname)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.register_no_code), style = QuadType.body, color = c.ink2)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.register_resend), style = QuadType.name, color = accent,
                        modifier = Modifier.clickable(enabled = !state.busy) { onSendCode(email.trim(), region) }
                            .padding(vertical = 4.dp))
                }
            }

            Text(stringResource(R.string.register_back_to_login), style = QuadType.body, color = c.ink3,
                modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp))
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
