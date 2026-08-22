package net.klimakontrol.data.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Esito del controllo aggiornamenti. */
sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    data class Available(val latest: String, val htmlUrl: String) : UpdateStatus
    data object Unknown : UpdateStatus   // rete assente, nessuna release, rate limit: non disturbare
}

/**
 * Confronta la versione locale (`BuildConfig.VERSION_NAME`) con l'ultima GitHub Release di
 * fpellizz/klimakontrol. Nessuna libreria esterna: `HttpURLConnection` + `org.json`, come
 * [net.klimakontrol.data.cloud.CloudClient]. Va chiamato fuori dal main thread (Dispatchers.IO).
 */
class UpdateChecker(
    private val currentVersion: String,          // = BuildConfig.VERSION_NAME
    private val repo: String = "fpellizz/klimakontrol",
) {
    fun check(): UpdateStatus = try {
        val json = fetchLatest() ?: return UpdateStatus.Unknown
        val tag = json.optString("tag_name").ifEmpty { return UpdateStatus.Unknown }
        val latest = tag.trimStart('v', 'V')
        if (isNewer(latest, currentVersion)) UpdateStatus.Available(latest, json.optString("html_url"))
        else UpdateStatus.UpToDate
    } catch (_: Exception) {
        UpdateStatus.Unknown                     // mai far crashare per un update-check
    }

    private fun fetchLatest(): JSONObject? {
        val con = URL("https://api.github.com/repos/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        return try {
            con.requestMethod = "GET"
            con.connectTimeout = 8000; con.readTimeout = 12000
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("User-Agent", "klimakontrol-app")  // GitHub dà 403 senza UA
            if (con.responseCode != 200) return null                  // 404 = nessuna release
            JSONObject(con.inputStream.bufferedReader().use { it.readText() })
        } finally {
            con.disconnect()
        }
    }

    /** true se `latest` > `current` in semver (MAJOR.MINOR.PATCH), suffissi ignorati. */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest); val b = parse(current)
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun parse(v: String): IntArray {
        // "1.2.3", "1.2.3-rc1", "1.2" -> [1,2,3]; pezzi non numerici -> 0
        val core = v.trim().substringBefore('-').substringBefore('+')
        val p = core.split('.')
        return IntArray(3) { p.getOrNull(it)?.toIntOrNull() ?: 0 }
    }
}
