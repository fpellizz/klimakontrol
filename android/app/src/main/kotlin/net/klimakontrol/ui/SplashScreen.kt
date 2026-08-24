package net.klimakontrol.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import net.klimakontrol.ui.theme.Klima
import net.klimakontrol.ui.theme.QuadType

/** Schermata d'avvio: logo del produttore (se impostato) grande + nome app. */
@Composable
fun SplashScreen(vendorLogo: ByteArray?) {
    val c = Klima.colors
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            vendorLogo?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                if (bmp != null) {
                    Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(30.dp)) {
                        Image(bitmap = bmp, contentDescription = "Logo del produttore",
                            modifier = Modifier.height(110.dp), contentScale = ContentScale.Fit)
                    }
                }
            }
            Text("KlimaKontrol", style = QuadType.wordmark, color = c.ink)
        }
    }
}
