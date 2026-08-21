package net.klimakontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Anello del quadrante: arco a 270° (apertura in basso), traccia + progresso, con tacca ambiente
 * e manopola opzionali. `frac` e `ambientFrac` sono 0..1 lungo il range 16–31 °C.
 */
@androidx.compose.runtime.Composable
fun DialRing(
    frac: Float,
    accent: Color,
    track: Color,
    stroke: Dp,
    modifier: Modifier = Modifier,
    ambientFrac: Float? = null,
    ambientColor: Color = accent,
    showKnob: Boolean = false,
    knobRing: Color = accent,
) {
    val start = 135f
    val total = 270f
    Canvas(modifier) {
        val sw = stroke.toPx()
        val inset = sw / 2f
        val topLeft = Offset(inset, inset)
        val arcSize = Size(size.width - sw, size.height - sw)
        val f = frac.coerceIn(0f, 1f)

        drawArc(track, start, total, false, topLeft = topLeft, size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round))
        drawArc(accent, start, total * f, false, topLeft = topLeft, size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round))

        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = (min(size.width, size.height) - sw) / 2f

        ambientFrac?.let { af ->
            val ang = Math.toRadians((start + total * af.coerceIn(0f, 1f)).toDouble())
            val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
            val rIn = radius - sw * 0.75f
            val rOut = radius + sw * 0.55f
            drawLine(
                color = ambientColor,
                start = Offset(cx + rIn * c, cy + rIn * s),
                end = Offset(cx + rOut * c, cy + rOut * s),
                strokeWidth = sw * 0.22f,
                cap = StrokeCap.Round,
            )
        }

        if (showKnob) {
            val ang = Math.toRadians((start + total * f).toDouble())
            val kx = cx + radius * cos(ang).toFloat()
            val ky = cy + radius * sin(ang).toFloat()
            drawCircle(knobRing, radius = sw * 0.62f, center = Offset(kx, ky))
            drawCircle(accent, radius = sw * 0.42f, center = Offset(kx, ky))
        }
    }
}

/** Simbolo di accensione (IEC): anello aperto in alto + barretta verticale. Disegnato, così non
 *  dipende da un glifo del font (U+23FB non c'è nei font di sistema → apparirebbe come "tofu"). */
@androidx.compose.runtime.Composable
fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = min(size.width, size.height)
        val sw = s * 0.11f
        val r = s * 0.30f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val gap = 62f
        drawArc(
            color = color, startAngle = 270f + gap / 2f, sweepAngle = 360f - gap, useCenter = false,
            topLeft = Offset(cx - r, cy - r), size = Size(2 * r, 2 * r),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(cx, cy - r * 0.12f),
            end = Offset(cx, cy - r * 1.08f),
            strokeWidth = sw, cap = StrokeCap.Round,
        )
    }
}
