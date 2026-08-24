package net.klimakontrol.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import net.klimakontrol.R

/**
 * Font del sistema "Quadrante", scaricati a runtime da Google Fonts (Downloadable Fonts):
 * Space Grotesk per numeri/display (cifre tabulari, carattere strumentale), Inter per la UI.
 * Nessun file binario nel repo; il provider è Google Play Services (certificati in font_certs.xml).
 * Se il download non è disponibile, Compose ripiega sul font di sistema.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val spaceGrotesk = GoogleFont("Space Grotesk")
private val inter = GoogleFont("Inter")
private val outfit = GoogleFont("Outfit")   // wordmark del nome app

private val Display = FontFamily(
    Font(spaceGrotesk, provider, FontWeight.Light),
    Font(spaceGrotesk, provider, FontWeight.Normal),
    Font(spaceGrotesk, provider, FontWeight.Medium),
    Font(spaceGrotesk, provider, FontWeight.Bold),
)
private val Wordmark = FontFamily(
    Font(outfit, provider, FontWeight.Medium),
    Font(outfit, provider, FontWeight.SemiBold),
    Font(outfit, provider, FontWeight.Bold),
)
private val Body = FontFamily(
    Font(inter, provider, FontWeight.Normal),
    Font(inter, provider, FontWeight.Medium),
    Font(inter, provider, FontWeight.SemiBold),
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
