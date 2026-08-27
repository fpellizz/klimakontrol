package net.klimakontrol.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.klimakontrol.R

/**
 * Font del sistema "Quadrante", **inclusi nell'app** (nessun Google Play Services, funzionano
 * anche su dispositivi de-Googled → compatibile F-Droid). Space Grotesk per numeri/display (cifre
 * tabulari, carattere strumentale), Inter per la UI, Outfit per il wordmark. Sono istanze statiche
 * ai pesi usati, ricavate dai font variabili ufficiali (OFL); licenze in `assets/fonts/`.
 * Vedi `docs/distribution.md`.
 */
private val Display = FontFamily(
    Font(R.font.space_grotesk_light, FontWeight.Light),
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
private val Wordmark = FontFamily(
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)
private val Body = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private const val TNUM = "tnum"

object QuadType {
    val tempHero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 70.sp, fontFeatureSettings = TNUM)
    val tempUnit = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 26.sp)
    val target = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 34.sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 26.sp)
    val wordmark = TextStyle(fontFamily = Wordmark, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = 0.3.sp)
    val unit = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 22.sp)
    val name = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 18.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val micro = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, fontFeatureSettings = TNUM)
    val overline = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
    val badge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
}
