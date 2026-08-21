package net.klimakontrol.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Scala tipografica del sistema "Quadrante".
 * TODO: aggiungere i font veri (Space Grotesk per i numeri/display, Inter per la UI) come
 * risorse in res/font e sostituire FontFamily.Default. Per ora si usano i font di sistema,
 * mantenendo pesi, dimensioni e cifre tabulari della specifica.
 */
private val Display = FontFamily.Default // -> Space Grotesk
private val Body = FontFamily.Default     // -> Inter

private const val TNUM = "tnum"

object QuadType {
    val tempHero = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 70.sp, fontFeatureSettings = TNUM)
    val tempUnit = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 26.sp)
    val target = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 34.sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 26.sp)
    val unit = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 22.sp)
    val name = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 18.sp)
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 15.sp)
    val micro = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, fontFeatureSettings = TNUM)
    val overline = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.2.sp)
    val badge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.6.sp)
}
