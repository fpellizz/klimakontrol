package net.klimakontrol.data.branding

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
            // preferisci il logo dedicato, poi uno splash, poi il primo PNG disponibile
            pngs["abouticon.png"]
                ?: pngs.entries.firstOrNull { it.key.startsWith("loading") }?.value
                ?: pngs.values.firstOrNull()
        } catch (_: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }
}
