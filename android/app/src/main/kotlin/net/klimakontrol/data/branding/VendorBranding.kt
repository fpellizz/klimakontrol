package net.klimakontrol.data.branding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Scarica il pacchetto di branding del produttore dal cloud BroadLink, come fa l'app ufficiale:
 * `GET https://<lid>appservice.ibroadlink.com/neutralapp/companyinfo?code=<CODICE>` → uno ZIP di
 * asset, da cui estraiamo un PNG del logo.
 *
 * Nota: non impacchettiamo nessun asset del produttore — l'immagine arriva a runtime dal *loro*
 * server (interoperabilità con l'hardware di proprietà dell'utente). Nessuna dipendenza esterna.
 */
object VendorBranding {

    /** Byte del PNG del logo (o null). `baseUrl` = `https://<lid>appservice.ibroadlink.com`. */
    fun fetchLogo(baseUrl: String, code: String): ByteArray? {
        val c = code.trim()
        if (c.isEmpty()) return null
        val url = "$baseUrl/neutralapp/companyinfo?code=" + URLEncoder.encode(c, "UTF-8")
        val con = URL(url).openConnection() as HttpURLConnection
        return try {
            con.requestMethod = "GET"
            con.connectTimeout = 10000; con.readTimeout = 20000
            if (con.responseCode != 200) return null
            val pngs = LinkedHashMap<String, ByteArray>()
            ZipInputStream(con.inputStream).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".png", ignoreCase = true)) {
                        pngs[e.name.substringAfterLast('/').lowercase()] = zip.readBytes()
                    }
                    e = zip.nextEntry
                }
            }
            // preferisci lo splash `loading_*` (logo a colori su bianco, ben visibile) — l'aboutIcon
            // è la versione tutta bianca, invisibile su sfondo chiaro. Poi fallback.
            val chosen = pngs["loading_640x960.png"]
                ?: pngs.entries.firstOrNull { it.key.startsWith("loading") }?.value
                ?: pngs["abouticon.png"]
                ?: pngs.values.firstOrNull()
            // ritaglia i margini uniformi (bianco/trasparente) attorno al marchio, così riempie il
            // riquadro invece di essere piccolo in mezzo a molto bianco
            chosen?.let { autoCrop(it) ?: it }
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }

    /** Ritaglia i bordi uniformi (quasi-bianco o trasparente) attorno al contenuto. */
    private fun autoCrop(png: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val w = bmp.width; val h = bmp.height
        if (w == 0 || h == 0) return null
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val a = (p ushr 24) and 0xFF
                val r = (p ushr 16) and 0xFF; val g = (p ushr 8) and 0xFF; val b = p and 0xFF
                val background = a < 16 || (r > 240 && g > 240 && b > 240)  // trasparente o quasi-bianco
                if (!background) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null  // immagine tutta "sfondo"
        val pad = (maxOf(maxX - minX, maxY - minY) * 0.06f).toInt()
        minX = (minX - pad).coerceAtLeast(0); minY = (minY - pad).coerceAtLeast(0)
        maxX = (maxX + pad).coerceAtMost(w - 1); maxY = (maxY + pad).coerceAtMost(h - 1)
        val cropped = Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
