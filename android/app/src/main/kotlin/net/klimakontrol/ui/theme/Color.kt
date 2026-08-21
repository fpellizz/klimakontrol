package net.klimakontrol.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import net.klimakontrol.data.Mode

/** Colori accento per una modalità: arco/attivi, fondo tinto, testo su fondo. */
@Immutable
data class ModeColors(val accent: Color, val container: Color, val on: Color)

/** L'intera palette del sistema "Quadrante" per un tema (scuro o chiaro). */
@Immutable
data class QuadColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surfaceOff: Color,
    val border: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val offline: Color,
    val error: Color,
    val ok: Color,
    val eco: Color,
    val turbo: Color,
    val night: Color,
    val modes: Map<Mode, ModeColors>,
    val glow: Float,
) {
    fun mode(m: Mode): ModeColors = modes.getValue(m)
}

// ---- TEMA SCURO (primario, dark-first) ----
val DarkQuad = QuadColors(
    bg = Color(0xFF0F0D0B),
    surface1 = Color(0xFF1A1712),
    surface2 = Color(0xFF241F19),
    surfaceOff = Color(0xFF151310),
    border = Color(0xFF332C23),
    ink = Color(0xFFF4EFE7),
    ink2 = Color(0xFFB7ADA0),
    ink3 = Color(0xFF7C7264),
    offline = Color(0xFF6E6559),
    error = Color(0xFFFF6B5A),
    ok = Color(0xFF5FD08A),
    eco = Color(0xFF5FD08A),
    turbo = Color(0xFFFF6B5A),
    night = Color(0xFF8B93FF),
    glow = 0.14f,
    modes = mapOf(
        Mode.CALDO to ModeColors(Color(0xFFFF7A45), Color(0xFF40210F), Color(0xFFFFD9C6)),
        Mode.FREDDO to ModeColors(Color(0xFF45B6FF), Color(0xFF06304A), Color(0xFFBCE4FB)),
        Mode.DEUMIDIFICA to ModeColors(Color(0xFF35D0B8), Color(0xFF063F3A), Color(0xFFA6EDE4)),
        Mode.VENTOLA to ModeColors(Color(0xFFA9B7C6), Color(0xFF26303B), Color(0xFFCBD7E4)),
        Mode.AUTO to ModeColors(Color(0xFFB79CF0), Color(0xFF2C1A5A), Color(0xFFDECEFB)),
    ),
)

// ---- TEMA CHIARO ("Latte") ----
val LightQuad = QuadColors(
    bg = Color(0xFFFBF6EF),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF2ECE3),
    surfaceOff = Color(0xFFF0EAE1),
    border = Color(0xFFE7DED2),
    ink = Color(0xFF241C14),
    ink2 = Color(0xFF6A5D4E),
    ink3 = Color(0xFF9C8E7E),
    offline = Color(0xFF9A9086),
    error = Color(0xFFC0392B),
    ok = Color(0xFF2FA36B),
    eco = Color(0xFF2FA36B),
    turbo = Color(0xFFD93A3A),
    night = Color(0xFF5C63D6),
    glow = 0.10f,
    modes = mapOf(
        Mode.CALDO to ModeColors(Color(0xFFE85D1F), Color(0xFFFFDCC8), Color(0xFF7A2E00)),
        Mode.FREDDO to ModeColors(Color(0xFF0A84D6), Color(0xFFCDEBFB), Color(0xFF00405B)),
        Mode.DEUMIDIFICA to ModeColors(Color(0xFF0FA894), Color(0xFFB7ECEA), Color(0xFF00504C)),
        Mode.VENTOLA to ModeColors(Color(0xFF5A6675), Color(0xFFDBE4EE), Color(0xFF33404E)),
        Mode.AUTO to ModeColors(Color(0xFF7C5CFA), Color(0xFFE8DDFB), Color(0xFF3B1E75)),
    ),
)
