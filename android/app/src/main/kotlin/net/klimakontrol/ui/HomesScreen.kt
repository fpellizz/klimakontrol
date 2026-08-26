package net.klimakontrol.ui

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.klimakontrol.R
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.Home
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

@Composable
fun HomesScreen(
    homes: List<Home>,
    units: List<AcUnit>,
    assignments: Map<String, String>,
    onAddHome: (String) -> Unit,
    onRenameHome: (String, String) -> Unit,
    onDeleteHome: (String) -> Unit,
    onAssign: (String, String?) -> Unit,
    onExport: () -> String,
    onImport: (String) -> Boolean,
    onBack: () -> Unit,
) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO).accent
    val context = LocalContext.current
    var newHome by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 10.dp, 20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(c.ink, onBack)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.manage_zones), style = QuadType.title, color = c.ink)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            note?.let { Text(it, style = QuadType.body, color = if (it.endsWith("✓")) c.ok else c.error) }

            // ---- crea / rinomina / elimina zone ----
            Section(stringResource(R.string.homes_your_zones)) {
                if (homes.isEmpty()) Text(stringResource(R.string.homes_no_zones),
                    style = QuadType.body, color = c.ink3)
                homes.forEach { h ->
                    var name by remember(h.id) { mutableStateOf(h.name) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        MiniButton(stringResource(R.string.action_save), accent, enabled = name.isNotBlank() && name != h.name) {
                            onRenameHome(h.id, name); note = context.getString(R.string.homes_saved)
                        }
                        Spacer(Modifier.width(6.dp))
                        MiniButton(stringResource(R.string.action_delete), c.error, enabled = true) { onDeleteHome(h.id) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newHome, onValueChange = { newHome = it }, singleLine = true,
                        label = { Text(stringResource(R.string.homes_new_zone)) }, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    MiniButton(stringResource(R.string.action_add), accent, enabled = newHome.isNotBlank()) {
                        onAddHome(newHome); newHome = ""
                    }
                }
            }

            // ---- assegna dispositivi alle case ----
            Section(stringResource(R.string.homes_assign_devices)) {
                if (units.isEmpty()) Text(stringResource(R.string.homes_no_devices), style = QuadType.body, color = c.ink3)
                units.forEach { u ->
                    Text(u.name, style = QuadType.name, color = c.ink, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val cur = assignments[u.id]
                        HomeChip(stringResource(R.string.homes_unassigned), cur == null) { onAssign(u.id, null) }
                        homes.forEach { h -> HomeChip(h.name, cur == h.id) { onAssign(u.id, h.id) } }
                    }
                }
            }

            // ---- backup / esporta / importa ----
            Section(stringResource(R.string.homes_backup)) {
                MiniButton(stringResource(R.string.homes_export), accent, enabled = true, fill = true) {
                    val json = onExport()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.homes_export_subject))
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.homes_export)))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = importText, onValueChange = { importText = it },
                    label = { Text(stringResource(R.string.homes_import_hint)) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                MiniButton(stringResource(R.string.homes_import), accent, enabled = importText.isNotBlank(), fill = true) {
                    note = if (onImport(importText)) { importText = ""; context.getString(R.string.homes_config_imported) }
                           else context.getString(R.string.homes_config_invalid)
                }
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
private fun MiniButton(label: String, tint: Color, enabled: Boolean, fill: Boolean = false, onClick: () -> Unit) {
    val c = Klima.colors
    val m = if (fill) Modifier.fillMaxWidth() else Modifier
    Box(
        m.pressClickable(onClick = onClick, enabled = enabled).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) tint.copy(alpha = 0.18f) else c.surfaceOff)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = QuadType.body, color = if (enabled) tint else c.ink3)
    }
}
