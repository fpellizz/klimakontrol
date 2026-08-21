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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import net.klimakontrol.data.AcUnit
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
    onRefresh: () -> Unit = {},
    onSettings: () -> Unit = {},
    send: Map<String, SendState> = emptyMap(),
) {
    val c = Klima.colors
    val onCount = units.count { it.power }

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
                Text("Casa", style = QuadType.title, color = c.ink)
                Text("${units.size} unità · $onCount ${if (onCount == 1) "accesa" else "accese"}",
                    style = QuadType.body, color = c.ink2)
            }
            RoundIcon("⟳", c.ink2, c.surface1, onRefresh)
            Spacer(Modifier.width(8.dp))
            RoundIcon("⚙", c.ink2, c.surface1, onSettings)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(units, key = { it.id }) { u ->
                UnitCard(u, send[u.id] ?: SendState.Idle, onOpen, onTogglePower)
            }
        }

        if (units.size >= 2 && onCount > 0) {
            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "⏻   Spegni tutte",
                    style = QuadType.name.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface2)
                        .clickable { onPowerAllOff() }
                        .padding(vertical = 15.dp),
                )
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
            .fillMaxWidth()
            .heightIn(min = 108.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(surface)
            .clickable { onOpen(u) }
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
                !u.online -> Text("offline", style = QuadType.micro, color = c.offline)
                u.power -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(u.mode.label, mode.container, mode.on)
                    Spacer(Modifier.width(7.dp))
                    Text("· ${u.fan.label}", style = QuadType.body, color = c.ink2)
                }
                else -> Text("Spenta", style = QuadType.body, color = c.ink2)
            }
            Spacer(Modifier.height(6.dp))
            when (send) {
                SendState.Sending -> Text("invio…", style = QuadType.micro, color = mode.accent)
                SendState.Ok -> Text("✓ confermato", style = QuadType.micro, color = c.ok)
                SendState.Error -> Text("comando non riuscito", style = QuadType.micro, color = c.error)
                SendState.Idle -> Text(
                    if (!u.online) "ultimo dato 3 min fa"
                    else u.ambientTemp?.let { "ambiente ${fmt(it)}°" } ?: "",
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
            .size(52.dp)
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("⏻", color = if (enabled) fg else c.offline, style = QuadType.name)
    }
}

@Composable
private fun RoundIcon(glyph: String, fg: Color, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(bg).clickable { onClick() },
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
