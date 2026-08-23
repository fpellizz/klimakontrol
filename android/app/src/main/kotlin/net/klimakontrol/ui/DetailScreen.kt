package net.klimakontrol.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import net.klimakontrol.data.AcUnit
import net.klimakontrol.data.FanSpeed
import net.klimakontrol.data.Mode
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

private fun modeGlyph(m: Mode) = when (m) {
    Mode.CALDO -> "☀"; Mode.FREDDO -> "❄"; Mode.DEUMIDIFICA -> "💧"; Mode.VENTOLA -> "≋"; Mode.AUTO -> "Ⓐ"
}

// livelli ventola in ordine di portata (i valori sul filo non sono numericamente ordinati)
private val fanSteps = listOf(
    FanSpeed.BASSA, FanSpeed.MEDIO_BASSA, FanSpeed.MEDIA, FanSpeed.MEDIO_ALTA, FanSpeed.ALTA,
)

/**
 * Converte un punto toccato dentro il quadrante nella temperatura corrispondente.
 * L'arco è quello di [DialRing]: parte a 135° e copre 270° in senso orario (apertura in basso).
 * Nel settore vuoto in basso, aggancia all'estremo più vicino. Snap a passi di 0.5°.
 */
private fun tempAt(pos: Offset, size: IntSize): Float {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val raw = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
    val delta = (((raw - 135f) % 360f) + 360f) % 360f   // gradi dall'inizio dell'arco, 0..360
    val f = when {
        delta <= 270f -> delta / 270f    // dentro l'arco
        delta < 315f -> 1f               // gap in basso, lato caldo (estremo max)
        else -> 0f                       // gap in basso, lato freddo (estremo min)
    }
    val steps = ((AcUnit.TEMP_MIN + f * (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)) / AcUnit.TEMP_STEP).roundToInt()
    return (steps * AcUnit.TEMP_STEP).coerceIn(AcUnit.TEMP_MIN, AcUnit.TEMP_MAX)
}

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
    onToggleSwingV: () -> Unit,
    onToggleSwingH: () -> Unit,
    send: SendState = SendState.Idle,
) {
    val c = Klima.colors
    val mode = c.mode(unit.mode)
    val f = ((unit.targetTemp - AcUnit.TEMP_MIN) / (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)).coerceIn(0f, 1f)
    // l'arco scivola dolcemente verso il nuovo valore (firma animata del quadrante); durante il
    // trascinamento la molla insegue il dito con un microritardo "liquido"
    val animF = animateFloatAsState(f, spring(stiffness = Spring.StiffnessMediumLow), label = "dialFrac").value
    val ambFrac = unit.ambientTemp?.let {
        ((it - AcUnit.TEMP_MIN) / (AcUnit.TEMP_MAX - AcUnit.TEMP_MIN)).coerceIn(0f, 1f)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
      // layout dinamico: il quadrante e le spaziature scalano con l'altezza schermo,
      // entro un limite inferiore e superiore (compatto sui piccoli, arioso sui grandi)
      val dialSize = (maxHeight * 0.33f).coerceIn(210.dp, 272.dp)
      val sectionGap = (maxHeight * 0.02f).coerceIn(10.dp, 20.dp)
      val tempFont = (dialSize.value * 0.30f).sp   // il numero scala col quadrante: mai fuori dall'anello

      Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // filo di "invio in corso" in cima allo schermo
        Box(Modifier.fillMaxWidth().height(2.dp)
            .background(if (send == SendState.Sending) mode.accent else Color.Transparent))

        // ---- app bar (fondo = container modalità) ----
        Column(Modifier.fillMaxWidth().background(mode.container).padding(20.dp, 14.dp, 20.dp, 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackButton(mode.on, onBack)
                Spacer(Modifier.width(10.dp))
                Text(unit.name, style = QuadType.unit, color = mode.on, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    unit.ambientTemp?.let { append("Ambiente ${fmt(it)}°  ·  ") }
                    append(unit.errorCode?.let { "errore $it" } ?: "nessun errore")
                },
                style = QuadType.micro, color = mode.on.copy(alpha = 0.78f),
            )
        }

        // ---- quadrante hero (trascinabile come slider circolare) ----
        Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(dialSize).pointerInput(unit.id) {
                    // tocco/trascinamento sull'anello -> temperatura (stesso arco 135°+270°).
                    // Il centro (dove sta il numero) è ignorato per non cambiare valore leggendo.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val minR = min(size.width, size.height) * 0.30f
                        if ((down.position - center).getDistance() < minR) return@awaitEachGesture
                        onSetTarget(tempAt(down.position, size))
                        down.consume()
                        drag(down.id) { change ->
                            onSetTarget(tempAt(change.position, size))
                            change.consume()
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                DialRing(
                    frac = animF, accent = mode.accent, track = c.border, stroke = 14.dp,
                    modifier = Modifier.fillMaxSize(),
                    ambientFrac = ambFrac, ambientColor = c.ink2, showKnob = true, knobRing = c.bg,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(fmt(unit.targetTemp), style = QuadType.tempHero.copy(fontSize = tempFont), color = c.ink)
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

        // ---- temperatura: +/− subito sotto il quadrante ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", Modifier.weight(1f)) { onStep(-AcUnit.TEMP_STEP) }
            StepButton("+", Modifier.weight(1f)) { onStep(AcUnit.TEMP_STEP) }
        }

        // ---- controlli (scrollabili se serve, ma pensati per stare in una schermata) ----
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            Section("Modalità") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Mode.entries.forEach { m ->
                        val sel = m == unit.mode
                        val mc = c.mode(m)
                        Column(
                            Modifier.weight(1f).pressClickable(onClick = { onSetMode(m) })
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (sel) mc.container else c.surface1)
                                .padding(vertical = 9.dp),
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
                // tutto su una riga: Auto + slider (tocca/trascina) + livello corrente
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val autoSel = unit.fan == FanSpeed.AUTO
                    Box(
                        Modifier.pressClickable(onClick = { onSetFan(FanSpeed.AUTO) })
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (autoSel) mode.container else c.surface1)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text("Auto", style = QuadType.body, color = if (autoSel) mode.on else c.ink2) }
                    Spacer(Modifier.width(12.dp))
                    FanSlider(unit.fan, mode.accent, onSetFan, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Text(unit.fan.label, style = QuadType.body, color = c.ink2)
                }
            }

            // Oscillazione: il modulo la gestisce (ac_vdir/ac_hdir, visti nel set fisso).
            // I controlli non gestiti (silenzioso, salute, display) sono stati tolti perché
            // non compaiono nel set che l'unità ritorna. Vedi docs/open-questions.md §4.
            Section("Oscillazione") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwingTile("Verticale", "↕", unit.swingV, mode.accent, Modifier.weight(1f), onToggleSwingV)
                    SwingTile("Orizzontale", "↔", unit.swingH, mode.accent, Modifier.weight(1f), onToggleSwingH)
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

        // ---- accensione (azione primaria, a portata di pollice) ----
        Box(
            Modifier.fillMaxWidth().padding(22.dp, 6.dp, 22.dp, 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BigPower(unit.power, mode.accent, onTogglePower)
        }
      }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    val c = Klima.colors
    Column {
        Text(label.uppercase(), style = QuadType.overline, color = c.ink3)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun FeatureTile(label: String, on: Boolean, feat: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Klima.colors
    Row(
        modifier.pressClickable(onClick).clip(RoundedCornerShape(14.dp)).background(c.surface1)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) feat else c.ink3))
        Spacer(Modifier.width(6.dp))
        Text(label, style = QuadType.body, color = if (on) c.ink else c.ink2)
    }
}

// tasto oscillazione: freccia direzionale + etichetta, con stato acceso/spento
@Composable
private fun SwingTile(label: String, glyph: String, on: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Klima.colors
    Row(
        modifier.pressClickable(onClick).clip(RoundedCornerShape(14.dp))
            .background(if (on) c.surface2 else c.surface1).padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, style = QuadType.name, color = if (on) accent else c.ink3)
        Spacer(Modifier.width(8.dp))
        Text(label, style = QuadType.body, color = if (on) c.ink else c.ink2)
    }
}

// slider ventola: barre a scatti, trascinabile (mappa la x sul livello 1..5)
@Composable
private fun FanSlider(current: FanSpeed, accent: Color, onSet: (FanSpeed) -> Unit, modifier: Modifier = Modifier) {
    val c = Klima.colors
    val curIdx = fanSteps.indexOf(current)   // -1 se AUTO
    Row(
        modifier.height(34.dp).pointerInput(Unit) {
            awaitEachGesture {
                // traccia l'ultimo livello inviato NELLA gesture: niente comandi ripetuti
                // trascinando dentro lo stesso segmento
                var lastSent = fanSteps.indexOf(current)
                fun setAt(x: Float) {
                    val i = ((x / size.width) * fanSteps.size).toInt().coerceIn(0, fanSteps.size - 1)
                    if (i != lastSent) { lastSent = i; onSet(fanSteps[i]) }
                }
                val down = awaitFirstDown(requireUnconsumed = false)
                setAt(down.position.x); down.consume()
                drag(down.id) { ch -> setAt(ch.position.x); ch.consume() }
            }
        },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        fanSteps.forEachIndexed { i, _ ->
            val on = curIdx >= 0 && i <= curIdx
            Box(
                Modifier.weight(1f).height((16 + i * 5).dp).clip(RoundedCornerShape(4.dp))
                    .background(if (on) accent else c.border),
            )
        }
    }
}

// tasto "indietro": chevron disegnato (crisp, non dipende dal font) in un cerchietto tenue.
// Condiviso con Impostazioni / Gestione zone.
@Composable
internal fun BackButton(tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.pressClickable(onClick = onClick).size(38.dp).clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height
            val p = Path().apply {
                moveTo(w * 0.62f, h * 0.20f)
                lineTo(w * 0.34f, h * 0.50f)
                lineTo(w * 0.62f, h * 0.80f)
            }
            drawPath(p, tint, style = Stroke(
                width = size.minDimension * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun StepButton(glyph: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Klima.colors
    Box(
        modifier.pressClickable(onClick).height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(c.surface1),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = QuadType.tempUnit, color = c.ink) }
}

@Composable
private fun BigPower(on: Boolean, accent: Color, onClick: () -> Unit) {
    val c = Klima.colors
    Box(
        Modifier.pressClickable(onClick).size(64.dp).clip(CircleShape)
            .background(if (on) c.surface2 else accent),
        contentAlignment = Alignment.Center,
    ) { PowerGlyph(color = if (on) c.ink else Color(0xFF1A1208), modifier = Modifier.size(28.dp)) }
}
