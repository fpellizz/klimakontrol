package net.klimakontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

private fun modeGlyph(m: Mode) = when (m) {
    Mode.CALDO -> "☀"; Mode.FREDDO -> "❄"; Mode.DEUMIDIFICA -> "💧"; Mode.VENTOLA -> "≋"; Mode.AUTO -> "Ⓐ"
}

private val fanSteps = listOf(FanSpeed.BASSA, FanSpeed.MEDIA, FanSpeed.MEDIO_ALTA, FanSpeed.ALTA)

@Composable
fun DetailScreen(
    unit: AcUnit,
    onBack: () -> Unit,
    onTogglePower: () -> Unit,
    onStep: (Float) -> Unit,
    onSetTarget: (Float) -> Unit,
    onSetMode: (Mode) -> Unit,
    onSetFan: (FanSpeed) -> Unit,
    onToggleEco: () -> Unit,
    onToggleTurbo: () -> Unit,
    onToggleNight: () -> Unit,
    send: SendState = SendState.Idle,
) {
    val c = Klima.colors
    val mode = c.mode(unit.mode)
    val f = ((unit.targetTemp - AcUnit.TEMP_MIN) / (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)).coerceIn(0f, 1f)
    val ambFrac = unit.ambientTemp?.let {
        ((it - AcUnit.TEMP_MIN) / (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)).coerceIn(0f, 1f)
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // filo di "invio in corso" in cima allo schermo
        Box(Modifier.fillMaxWidth().height(2.dp)
            .background(if (send == SendState.Sending) mode.accent else Color.Transparent))

        // ---- app bar (fondo = container modalità) ----
        Column(Modifier.fillMaxWidth().background(mode.container).padding(20.dp, 14.dp, 20.dp, 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = mode.on, style = QuadType.title,
                    modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 6.dp))
                Spacer(Modifier.width(6.dp))
                Text(unit.name, style = QuadType.unit, color = mode.on, modifier = Modifier.weight(1f))
                Text("⋯", color = mode.on, style = QuadType.unit)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(unit.ambientTemp?.let { "Ambiente ${fmt(it)}°" } ?: "Ambiente —")
                    append("  ·  ")
                    append(unit.errorCode?.let { "errore $it" } ?: "nessun errore")
                },
                style = QuadType.micro, color = mode.on.copy(alpha = 0.78f),
            )
        }

        // ---- quadrante hero ----
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                DialRing(
                    frac = f, accent = mode.accent, track = c.border, stroke = 14.dp,
                    modifier = Modifier.fillMaxSize(),
                    ambientFrac = ambFrac, ambientColor = c.ink2, showKnob = true, knobRing = c.bg,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmt(unit.targetTemp), style = QuadType.tempHero, color = c.ink)
                        Text("°C", style = QuadType.tempUnit, color = c.ink2)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(modeGlyph(unit.mode), color = c.ink2, style = QuadType.body)
                        Spacer(Modifier.width(6.dp))
                        Text(unit.mode.label, style = QuadType.body, color = c.ink2)
                    }
                    val sendText = when (send) {
                        SendState.Sending -> "invio…"
                        SendState.Ok -> "✓ confermato"
                        SendState.Error -> "comando non riuscito"
                        SendState.Idle -> ""
                    }
                    val sendColor = when (send) {
                        SendState.Sending -> mode.accent
                        SendState.Ok -> c.ok
                        SendState.Error -> c.error
                        SendState.Idle -> c.ink2
                    }
                    Text(sendText, style = QuadType.micro, color = sendColor,
                        modifier = Modifier.height(18.dp))
                }
            }
        }

        // ---- controlli (scrollabili) ----
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Modalità") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mode.entries.forEach { m ->
                        val sel = m == unit.mode
                        val mc = c.mode(m)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .background(if (sel) mc.container else c.surface1)
                                .clickable { onSetMode(m) }.padding(vertical = 9.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(modeGlyph(m), color = if (sel) mc.on else c.ink2, style = QuadType.name)
                            Spacer(Modifier.height(3.dp))
                            Text(m.label, style = QuadType.badge,
                                color = if (sel) mc.on else c.ink2, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Section("Ventola") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val curIdx = fanSteps.indexOf(unit.fan)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        fanSteps.forEachIndexed { i, fs ->
                            val on = curIdx >= 0 && i <= curIdx
                            Box(
                                Modifier.width(10.dp).height((10 + i * 6).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (on) mode.accent else c.border)
                                    .clickable { onSetFan(fs) },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(unit.fan.label, style = QuadType.body, color = c.ink2)
                }
            }

            Section("Funzioni") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeatureTile("Eco", unit.eco, c.eco, Modifier.weight(1f), onToggleEco)
                    FeatureTile("Turbo", unit.turbo, c.turbo, Modifier.weight(1f), onToggleTurbo)
                    FeatureTile("Notte", unit.night, c.night, Modifier.weight(1f), onToggleNight)
                }
            }
        }

        // ---- thumb zone ----
        Row(
            Modifier.fillMaxWidth().padding(22.dp, 14.dp, 22.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", Modifier.weight(1f)) { onStep(-AcUnit.TEMP_STEP) }
            BigPower(unit.power, mode.accent, onTogglePower)
            StepButton("+", Modifier.weight(1f)) { onStep(AcUnit.TEMP_STEP) }
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    val c = Klima.colors
    Column {
        Text(label.uppercase(), style = QuadType.overline, color = c.ink3)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun FeatureTile(label: String, on: Boolean, feat: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Klima.colors
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface1).clickable { onClick() }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) feat else c.ink3))
        Spacer(Modifier.width(6.dp))
        Text(label, style = QuadType.body, color = if (on) c.ink else c.ink2)
    }
}

@Composable
private fun StepButton(glyph: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Klima.colors
    Box(
        modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(c.surface1)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = QuadType.tempUnit, color = c.ink) }
}

@Composable
private fun BigPower(on: Boolean, accent: Color, onClick: () -> Unit) {
    val c = Klima.colors
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(if (on) c.surface2 else accent).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text("⏻", style = QuadType.unit, color = if (on) c.ink else Color(0xFF1A1208)) }
}
