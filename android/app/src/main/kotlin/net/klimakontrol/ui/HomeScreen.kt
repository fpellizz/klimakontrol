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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.klimakontrol.R
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.Home
import net.klimakontrol.data.Mode
import net.klimakontrol.data.update.UpdateStatus
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.ModeColors
import net.klimakontrol.ui.theme.QuadType

private fun frac(u: AcUnit) =
    ((u.targetTemp - AcUnit.TEMP_MIN) / (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)).coerceIn(0f, 1f)

@Composable
fun HomeScreen(
    units: List<AcUnit>,
    onOpen: (AcUnit) -> Unit,
    onTogglePower: (AcUnit) -> Unit,
    onPowerAllOff: () -> Unit,
    onRefreshHouse: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSettings: () -> Unit = {},
    onAddDevice: () -> Unit = {},
    update: UpdateStatus = UpdateStatus.Unknown,
    homes: List<Home> = emptyList(),
    assignments: Map<String, String> = emptyMap(),
    selectedHome: String? = null,
    onSelectHome: (String?) -> Unit = {},
    send: Map<String, SendState> = emptyMap(),
) {
    val c = Klima.colors
    // filtro locale per casa: se una casa è selezionata, mostra solo le sue unità
    val shown = if (selectedHome == null) units
                else units.filter { assignments[it.id] == selectedHome }
    val onCount = shown.count { it.power }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                val hn = homes.firstOrNull { it.id == selectedHome }?.name
                Text(hn ?: stringResource(R.string.home_title), style = QuadType.title, color = c.ink)
                val units = pluralStringResource(R.plurals.home_units_count, shown.size, shown.size)
                val on = pluralStringResource(R.plurals.home_on_count, onCount, onCount)
                Text("$units · $on",
                    style = QuadType.body, color = c.ink2)
            }
            RoundIcon("+", c.ink2, c.surface1, onAddDevice)
            Spacer(Modifier.width(8.dp))
            RoundIcon("⟳", c.ink2, c.surface1, onRefresh)
            Spacer(Modifier.width(8.dp))
            RoundIcon("⚙", c.ink2, c.surface1, onSettings)
        }

        // filtro case (locale): i chip appaiono solo se l'utente ha definito almeno una casa
        if (homes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeChip(stringResource(R.string.home_filter_all), selectedHome == null) { onSelectHome(null) }
                homes.forEach { h -> HomeChip(h.name, selectedHome == h.id) { onSelectHome(h.id) } }
            }
        }

        // banner "aggiornamento disponibile" (tap = apre la release su GitHub)
        (update as? UpdateStatus.Available)?.let { avail ->
            val uriHandler = LocalUriHandler.current
            val cool = c.mode(Mode.FREDDO)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp)).background(cool.container)
                    .clickable { uriHandler.openUri(avail.htmlUrl) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_update_available, avail.latest),
                    style = QuadType.body, color = cool.on, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.home_update_open), style = QuadType.name.copy(fontWeight = FontWeight.SemiBold), color = cool.on)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (selectedHome != null) stringResource(R.string.home_empty_zone)
                            else stringResource(R.string.home_empty),
                            style = QuadType.body, color = c.ink3, textAlign = TextAlign.Center,
                        )
                        if (selectedHome == null) {
                            Spacer(Modifier.height(16.dp))
                            val cool = c.mode(Mode.FREDDO)
                            Row(
                                Modifier.pressClickable(onClick = { onAddDevice() })
                                    .clip(RoundedCornerShape(16.dp)).background(cool.accent)
                                    .padding(horizontal = 22.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("+", color = Color(0xFF10161A),
                                    style = QuadType.name.copy(fontWeight = FontWeight.SemiBold))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.add_ac),
                                    style = QuadType.name.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color(0xFF10161A))
                            }
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { u ->
                UnitCard(u, send[u.id] ?: SendState.Idle, onOpen, onTogglePower)
            }
        }

        // barra azioni rapide: agisce sulle unità mostrate (la casa selezionata)
        if (shown.isNotEmpty()) {
            val cool = c.mode(Mode.FREDDO)
            val canPowerOff = onCount > 0
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Rinfresca casa (primario): tutte accese, 16°, ventola al massimo
                Row(
                    Modifier.weight(1f).pressClickable(onClick = { onRefreshHouse() })
                        .clip(RoundedCornerShape(16.dp)).background(cool.accent).padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("❄", color = Color(0xFF10161A), style = QuadType.body)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_refresh_house), style = QuadType.name.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF10161A))
                }
                // Spegni tutte (secondario): attivo solo se qualcosa è acceso
                Row(
                    Modifier.weight(1f).pressClickable({ onPowerAllOff() }, enabled = canPowerOff)
                        .clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(vertical = 15.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    PowerGlyph(color = if (canPowerOff) c.ink else c.ink3, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_power_all_off), style = QuadType.name.copy(fontWeight = FontWeight.SemiBold),
                        color = if (canPowerOff) c.ink else c.ink3)
                }
            }
        }
    }
}

@Composable
private fun UnitCard(u: AcUnit, send: SendState, onOpen: (AcUnit) -> Unit, onTogglePower: (AcUnit) -> Unit) {
    val c = Klima.colors
    val mode: ModeColors = c.mode(u.mode)
    val surface = if (!u.online) c.surface1 else if (u.power) c.surface1 else c.surfaceOff
    val stripe = if (u.online && u.power) mode.accent else c.border

    Row(
        Modifier
            .pressClickable(onClick = { onOpen(u) })
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(surface)
            .padding(start = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // striscia accento
        Box(Modifier.padding(vertical = 16.dp).width(3.dp).height(72.dp)
            .clip(RoundedCornerShape(3.dp)).background(stripe))
        Spacer(Modifier.width(15.dp))

        // mini-quadrante
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (u.online) {
                DialRing(
                    frac = frac(u), accent = if (u.power) mode.accent else c.ink3,
                    track = c.border, stroke = 4.dp, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("⊘", color = c.offline, style = QuadType.title)
            }
        }
        Spacer(Modifier.width(14.dp))

        // corpo
        Column(Modifier.weight(1f)) {
            Text(u.name, style = QuadType.name, color = if (u.online) c.ink else c.offline)
            Spacer(Modifier.height(3.dp))
            when {
                !u.online -> Text(stringResource(R.string.unit_offline), style = QuadType.micro, color = c.offline)
                u.power -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(stringResource(u.mode.labelRes), mode.container, mode.on)
                    Spacer(Modifier.width(7.dp))
                    Text("· " + stringResource(u.fan.labelRes), style = QuadType.body, color = c.ink2)
                }
                else -> Text(stringResource(R.string.unit_off), style = QuadType.body, color = c.ink2)
            }
            Spacer(Modifier.height(6.dp))
            when (send) {
                SendState.Sending -> Text(stringResource(R.string.send_sending), style = QuadType.micro, color = mode.accent)
                SendState.Ok -> Text(stringResource(R.string.send_confirmed), style = QuadType.micro, color = c.ok)
                SendState.Error -> Text(stringResource(R.string.send_failed), style = QuadType.micro, color = c.error)
                SendState.Idle -> Text(
                    if (!u.online) stringResource(R.string.home_last_data)
                    else u.ambientTemp?.let { stringResource(R.string.home_ambient, fmt(it)) } ?: "",
                    style = QuadType.micro, color = c.ink3,
                )
            }
        }

        // destra: target + power
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 16.dp)) {
            if (u.online && u.power) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(u.targetTemp), style = QuadType.target, color = c.ink)
                    Text("°", style = QuadType.body, color = c.ink2)
                }
            } else {
                Text("—", style = QuadType.target, color = c.ink3)
            }
            Spacer(Modifier.height(8.dp))
            PowerToggle(
                on = u.power, enabled = u.online, accent = mode.accent,
                onClick = { onTogglePower(u) },
            )
        }
    }
}

@Composable
private fun PowerToggle(on: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    val c = Klima.colors
    val bg = if (on) accent else Color.Transparent
    val fg = if (on) Color(0xFF1A1208) else c.ink3
    Box(
        Modifier
            .pressClickable({ onClick() }, enabled = enabled)
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        PowerGlyph(color = if (enabled) fg else c.offline, modifier = Modifier.size(22.dp))
    }
}

// chip di filtro/selezione casa (Tutte / nome casa), condiviso con la schermata Gestisci case
@Composable
internal fun HomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Klima.colors
    val accent = c.mode(Mode.FREDDO)
    Box(
        Modifier.pressClickable(onClick = onClick).clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent.container else c.surface1)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = QuadType.body, color = if (selected) accent.on else c.ink2)
    }
}

@Composable
private fun RoundIcon(glyph: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.pressClickable(onClick).size(38.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = fg, style = QuadType.body) }
}

@Composable
private fun Badge(text: String, container: Color, on: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(container).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text.uppercase(), style = QuadType.badge, color = on)
    }
}

internal fun fmt(t: Float): String =
    String.format(java.util.Locale.US, "%.1f", t)
