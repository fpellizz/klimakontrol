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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.klimakontrol.data.cloud.REGIONS
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

// etichette brevi per il selettore regione/vendor (i codici sono le chiavi di REGIONS)
private val REGION_ORDER = listOf("eu", "ab", "cn", "ru")
private val REGION_SHORT = mapOf("eu" to "Europa", "ab" to "Altro", "cn" to "Cina", "ru" to "Russia")

/** Selettore regione/vendor a chip, condiviso da login e registrazione. */
@Composable
internal fun RegionChips(selected: String, onSelect: (String) -> Unit) {
    val c = Klima.colors
    val accentMode = c.mode(net.klimakontrol.data.Mode.FREDDO)
    Text("REGIONE", style = QuadType.overline, color = c.ink3)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        REGION_ORDER.filter { it in REGIONS }.forEach { code ->
            val sel = code == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) accentMode.container else c.surface1)
                    .clickable { onSelect(code) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    REGION_SHORT[code] ?: code.uppercase(),
                    style = QuadType.body, textAlign = TextAlign.Center,
                    color = if (sel) accentMode.on else c.ink2,
                )
            }
        }
    }
}

/** Campo password con toggle "Mostra/Nascondi", condiviso da login/registrazione/impostazioni. */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = Klima.colors
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Text(
                if (visible) "Nascondi" else "Mostra",
                style = QuadType.micro, color = c.ink2,
                modifier = Modifier.clickable { visible = !visible }.padding(horizontal = 12.dp),
            )
        },
        modifier = modifier,
    )
}

@Composable
fun LoginScreen(
    state: Phase.Login,
    onLogin: (String, String, Boolean, String) -> Unit,
    onCreateAccount: () -> Unit = {},
) {
    val c = Klima.colors
    val accent = c.mode(net.klimakontrol.data.Mode.FREDDO).accent
    var email by rememberSaveable { mutableStateOf(state.email) }
    var password by remember { mutableStateOf("") }
    var rememberCreds by rememberSaveable { mutableStateOf(true) }
    var region by rememberSaveable { mutableStateOf(state.region) }

    Box(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("klimakontrol", style = QuadType.title, color = c.ink)
            Text("Accedi al tuo account per controllare i climatizzatori.",
                style = QuadType.body, color = c.ink2)
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email o telefono") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(password, { password = it }, "Password", Modifier.fillMaxWidth())

            // ---- regione / vendor (determina il lid dell'account) ----
            RegionChips(region) { region = it }
            Text(
                "Dove vive l'account (di solito Europa). Cambiala solo se l'hai creato in un'altra area.",
                style = QuadType.micro, color = c.ink3,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { rememberCreds = !rememberCreds }.padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = rememberCreds, onCheckedChange = { rememberCreds = it },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
                Spacer(Modifier.width(4.dp))
                Text("Ricorda le credenziali", style = QuadType.body, color = c.ink2)
            }

            state.error?.let {
                Text(it, style = QuadType.micro, color = c.error)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (!state.busy) onLogin(email, password, rememberCreds, region) },
                enabled = !state.busy && email.isNotBlank() && password.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent, contentColor = Color(0xFF10161A),
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                else Text("Accedi", style = QuadType.name)
            }

            Text(
                if (rememberCreds) "Credenziali salvate cifrate sul dispositivo (Keystore); rientri da solo."
                else "Si conserva solo la sessione; la password non viene salvata.",
                style = QuadType.micro, color = c.ink3,
            )

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Non hai un account?", style = QuadType.body, color = c.ink2)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Crea account",
                    style = QuadType.name, color = accent,
                    modifier = Modifier.clickable { onCreateAccount() }.padding(vertical = 4.dp),
                )
            }
        }
    }
}
