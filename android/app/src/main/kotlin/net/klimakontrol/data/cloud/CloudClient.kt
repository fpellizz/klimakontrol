package net.klimakontrol.data.cloud

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Porting Kotlin del client cloud BroadLink (vedi la libreria Python `klimakontrol/cloud.py`).
 * Contiene la logica VERIFICATA su hardware reale:
 *  - companyid = COSTANTE CONDIVISA in blob[120:136] (NON blob[16:32]) — è ciò che sblocca il -1008;
 *  - password = SHA1(pw + sale), token header = MD5(corpo + sale), chiave AES = fromHex(MD5(ts + sale));
 *  - AES-128-CBC zero-padding, IV eaaaaa3a…;
 *  - la risposta di sdkcontrol arriva in event.payload.data come STRINGA JSON (va ri-parsata);
 *  - il setpoint di temperatura su questi moduli è `save_temp`, non `temp`.
 *
 * NB: è la fondazione di rete, non ancora collegata alla UI (che usa SampleRepository).
 * Le chiamate di rete vanno eseguite fuori dal main thread (Dispatchers.IO).
 */
class CloudException(message: String) : Exception(message)

data class Region(val code: String, val label: String, val licenseId: String, val companyId: String) {
    val baseUrl get() = "https://${licenseId}appservice.ibroadlink.com"
}

data class CloudDevice(
    val did: String, val mac: String, val aeskey: String,
    val pid: String, val name: String, val lanaddr: String?, val devtype: Int,
)

object Salts {
    const val PASSWORD = "4969fj#k23#"
    const val TOKEN = "kdixkdqp54545^#*"
    const val BODY = "xgx3d*fe3478\$ukx"
}

/** Blob di licenza estratti dall'APK (Base64). lid = blob[0:16], companyid = blob[120:136]. */
private val LICENSE_BLOBS = mapOf(
    "eu" to ("Europa" to
        "quchhDaeL8Pm3tU6kGElhlfJ5a28nhGDclOc2PJuEjlrVu1aWTUymLgy0lNthB1irlhhWwAAAADs" +
        "3b/pU8KzE42deOVVPI47q3AXOQdWLiiZnytJBYDqZMJe9bUxlnu2yrqpGqCWdsTLCQ2S8+ps6iui" +
        "X3T5hoYJhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "ab" to ("Internazionale / altro" to
        "9uniFWbhCaKHl6ulodjtfqhFKo9IrnB+3BLpxS4h8A8fL+VJIdyF+2ILFTC0PblZKVhhWwAAAABO" +
        "Pz4Zb6oe0ZlL1zmKVmrY2G6JnyY5iP/MgbRVK4EGBNngbjBXIrjugvbdRX/Eo+jEFLPwoaW2G+W/" +
        "0h0q6kkGhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "cn" to ("Cina" to
        "v/1NcC7FOTjDHrEMwBlLSrhnHVwBG6ur22sGiccKtlb88NLmRXYBBuBZzN0ftmg9g7rzWwAAAADX" +
        "CGl+jTb8dv8MuUV6Oe6Q0Qs3MVkr1CxkTbc9eCF9VA9IeSycC7T7L5/gZZyMbk6ZhKXe0Lj49+Xj" +
        "jTYOBlsChQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
    "ru" to ("Russia" to
        "5g3odWUWbER6kM7pbalV91ZHeU3ti7xn32X/K9fQ+wPgkGNrwku8t1BjKi6Z/SE0RFhhWwAAAABJ" +
        "2/PWDRNYtxiTMJolraau62+ditV4GpJUTtEwYccq/2ROZvfPaUyM4m7LY/ZRSRJs8mLdI6W/5YpX" +
        "AZyAtsoAhQOwj6V3Kd+fqkXkyXiFLAAAAAA="),
)

val REGIONS: Map<String, Region> = LICENSE_BLOBS.mapValues { (code, v) ->
    val raw = Base64.decode(v.second, Base64.DEFAULT)
    Region(code, v.first, raw.copyOfRange(0, 16).toHex(), raw.copyOfRange(120, 136).toHex())
}

private val REQUEST_IV = "eaaaaa3abb5862a21918b5771d1615aa".hexToBytes()
private const val APP_VERSION = "1.0.12"

class CloudClient(val region: Region = REGIONS.getValue("eu")) {

    var userid: String? = null; private set
    var loginSession: String? = null; private set
    private var familyKey: String? = null
    private var familyTs: Long = 0

    // ---------------- login ----------------
    fun login(username: String, password: String) {
        val ident = if (username.all { it.isDigit() }) "phone" else "email"
        val body = JSONObject()
            .put(ident, username)
            .put("password", sha1Hex(password + Salts.PASSWORD))
            .put("companyid", region.companyId)
            .put("lid", region.licenseId)
        val bj = body.toString()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val key = md5Hex(ts + Salts.TOKEN).hexToBytes()
        val headers = mapOf(
            "timestamp" to ts,
            "token" to md5Hex(bj + Salts.BODY),
            "lid" to region.licenseId,
            "licenseId" to region.licenseId,
            ident to username,
        )
        val resp = request("${region.baseUrl}/account/login", headers, aesEncrypt(bj.toByteArray(), key))
        ensureOk(resp, "login")
        userid = resp.optString("userid").ifEmpty { null }
        loginSession = resp.optString("loginsession").ifEmpty { null }
        if (userid == null || loginSession == null) throw CloudException("login senza sessione")
    }

    val loggedIn get() = userid != null && loginSession != null

    // ---------------- /ec4 firmate ----------------
    private fun refreshFamilyKey() {
        val resp = ec4("/ec4/v1/common/api", JSONObject())
        familyKey = resp.getString("key"); familyTs = resp.getLong("timestamp")
    }

    private fun ec4(path: String, body: JSONObject): JSONObject {
        check(loggedIn) { "sessione assente" }
        if (familyKey == null && path != "/ec4/v1/common/api") refreshFamilyKey()
        val bj = body.toString()
        val ts = if (path == "/ec4/v1/common/api") (System.currentTimeMillis() / 1000).toString()
                 else familyTs.toString()
        val token = if (path == "/ec4/v1/common/api") md5Hex(bj + Salts.BODY)
                    else md5Hex(bj + Salts.BODY + ts + userid)
        val headers = mapOf(
            "timestamp" to ts, "token" to token,
            "userid" to userid!!, "loginsession" to loginSession!!,
            "licenseid" to region.licenseId, "lid" to region.licenseId,
        )
        val keyHex = familyKey ?: md5Hex(ts + Salts.TOKEN)
        val enc = aesEncrypt(bj.toByteArray(), keyHex.hexToBytes())
        val resp = request("${region.baseUrl}$path", headers, enc)
        return ensureOk(resp, path)
    }

    fun familyIds(): List<String> {
        if (familyKey == null) refreshFamilyKey()
        val resp = ec4("/ec4/v1/user/getfamilyid", JSONObject().put("userid", userid))
        val out = mutableListOf<String>()
        resp.optJSONArray("familyinfo")?.let { arr ->
            for (i in 0 until arr.length()) arr.getJSONObject(i).optString("id").takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }

    fun devices(): List<CloudDevice> {
        val fams = familyIds()
        if (fams.isEmpty()) return emptyList()
        val body = JSONObject().put("userid", userid).put("familyid", JSONArray(fams))
        val resp = ec4("/ec4/v1/family/getallinfo", body)
        val out = mutableListOf<CloudDevice>()
        val seen = HashSet<String>()
        resp.optJSONArray("familyallinfo")?.let { fams2 ->
            for (i in 0 until fams2.length()) {
                val fam = fams2.getJSONObject(i)
                for (field in listOf("devinfo", "subdevinfo")) {
                    val arr = fam.optJSONArray(field) ?: continue
                    for (j in 0 until arr.length()) {
                        val d = arr.getJSONObject(j)
                        val did = d.optString("did"); val aes = d.optString("aeskey")
                        if (did.isEmpty() || aes.isEmpty() || !seen.add(did)) continue
                        out.add(CloudDevice(
                            did = did, mac = d.optString("mac"), aeskey = aes,
                            pid = d.optString("pid"), name = d.optString("name"),
                            lanaddr = d.optString("lanaddr").ifEmpty { null },
                            devtype = d.optInt("devtype"),
                        ))
                    }
                }
            }
        }
        return out
    }

    // ---------------- controllo remoto (sdkcontrol) ----------------
    private fun buildCookie(d: CloudDevice): String {
        val inner = JSONObject().put("device", JSONObject()
            .put("id", 1).put("key", d.aeskey).put("aeskey", d.aeskey)
            .put("did", d.did).put("pid", d.pid).put("mac", d.mac))
        return Base64.encodeToString(inner.toString().toByteArray(), Base64.NO_WRAP)
    }

    private fun sdkControl(d: CloudDevice, payload: JSONObject): JSONObject {
        check(loggedIn) { "sessione assente" }
        val ts = System.currentTimeMillis() / 1000
        val endpoint = JSONObject()
            .put("devicePairedInfo", JSONObject()
                .put("did", d.did).put("pid", d.pid).put("mac", d.mac)
                .put("devicetypeflag", 0).put("cookie", buildCookie(d)))
            .put("endpointId", d.did).put("cookie", JSONObject())
        val directive = JSONObject().put("directive", JSONObject()
            .put("header", JSONObject()
                .put("namespace", "DNA.KeyValueControl").put("name", "KeyValueControl")
                .put("interfaceVersion", "2").put("messageId", "${d.did}-$ts").put("timstamp", ts.toString()))
            .put("endpoint", endpoint).put("payload", payload))
        val headers = mapOf(
            "userid" to userid!!, "loginsession" to loginSession!!,
            "licenseid" to region.licenseId, "lid" to region.licenseId,
        )
        val url = "${region.baseUrl}/device/control/v2/sdkcontrol?license=${region.licenseId}"
        val resp = request(url, headers, directive.toString().toByteArray())
        val event = resp.optJSONObject("event")
            ?: throw CloudException("controllo remoto fallito: ${resp.optString("msg")}")
        return event.optJSONObject("payload") ?: JSONObject()
    }

    /** Legge lo stato: la risposta arriva in payload.data come STRINGA JSON. */
    fun getState(d: CloudDevice, params: List<String>): Map<String, Int> {
        val payload = JSONObject().put("act", "get")
            .put("params", JSONArray(params)).put("vals", JSONArray())
        return flatten(sdkControl(d, payload))
    }

    /** Scrive parametri (usa i nomi sul filo, es. save_temp / pwr / tcl_mode). */
    fun setState(d: CloudDevice, changes: Map<String, Int>): Map<String, Int> {
        val paramsArr = JSONArray(); val valsArr = JSONArray()
        for ((k, v) in changes) {
            paramsArr.put(k)
            valsArr.put(JSONArray().put(JSONObject().put("val", v).put("idx", 1)))
        }
        val payload = JSONObject().put("act", "set").put("params", paramsArr).put("vals", valsArr)
        return flatten(sdkControl(d, payload))
    }

    private fun flatten(payload: JSONObject): Map<String, Int> {
        val dataAny = payload.opt("data")
        val data = when (dataAny) {
            is String -> if (dataAny.isBlank()) JSONObject() else JSONObject(dataAny)
            is JSONObject -> dataAny
            else -> return emptyMap()
        }
        val params = data.optJSONArray("params") ?: return emptyMap()
        val vals = data.optJSONArray("vals") ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until params.length()) {
            val name = params.optString(i)
            val entry = vals.optJSONArray(i)?.optJSONObject(0)
            if (name.isNotEmpty() && entry != null) out[name] = entry.optInt("val")
        }
        return out
    }

    // ---------------- trasporto ----------------
    private fun request(urlStr: String, headers: Map<String, String>, body: ByteArray): JSONObject {
        val url = URL(urlStr)
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000; con.readTimeout = 30000; con.doOutput = true
            val nowMs = System.currentTimeMillis()
            con.setRequestProperty("system", "android")
            con.setRequestProperty("appPlatform", "android")
            con.setRequestProperty("language", "it-it")
            con.setRequestProperty("timestamp", (nowMs / 1000).toString())
            con.setRequestProperty("appVersion", APP_VERSION)
            con.setRequestProperty("messageId", nowMs.toString())
            con.setRequestProperty("Content-type", "application/x-java-serialized-object")
            for ((k, v) in headers) con.setRequestProperty(k, v)
            con.outputStream.use { it.write(body) }
            val stream = if (con.responseCode in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            return JSONObject(text)
        } finally {
            con.disconnect()
        }
    }

    private fun ensureOk(resp: JSONObject, where: String): JSONObject {
        val err = if (resp.has("error")) resp.optInt("error") else resp.optInt("status", 0)
        if (err != 0) throw CloudException("$where: errore $err ${resp.optString("msg")}")
        return resp
    }
}

// ---------------- crypto helper ----------------
private fun sha1Hex(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).toHex()
private fun md5Hex(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).toHex()

private fun aesEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
    val block = 16
    val padded = if (plain.size % block == 0) plain
    else plain.copyOf(plain.size + (block - plain.size % block)) // zero padding
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(REQUEST_IV))
    return cipher.doFinal(padded)
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xFF; sb.append("0123456789abcdef"[v ushr 4]); sb.append("0123456789abcdef"[v and 0x0F]) }
    return sb.toString()
}

private fun String.hexToBytes(): ByteArray {
    val out = ByteArray(length / 2)
    for (i in out.indices) out[i] = ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    return out
}
