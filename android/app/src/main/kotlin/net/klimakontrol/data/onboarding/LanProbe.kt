package net.klimakontrol.data.onboarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Un modulo trovato in LAN (discovery), con chiave/id riempiti dopo l'auth. */
data class LanDevice(
    val mac: String,          // "aa:bb:cc:dd:ee:ff"
    val devtype: Int,
    val name: String,
    val host: String,
    val deviceId: Int = 1,
    val key: String = "",     // aeskey (32 hex), riempita da [authenticate]
)

/**
 * Discovery + autenticazione BroadLink/DNA in LAN — porta di `klimakontrol/local.py`
 * (le parti che servono all'onboarding: trovare un modulo e ricavarne la chiave AES).
 *
 * Provata su HW il 2026-08-21: discovery e auth funzionano su questi moduli (è il controllo
 * `0x6a` che poi dà `-5`; per l'onboarding serve solo la chiave, che l'auth restituisce).
 * Byte-per-byte fedele a `local.py`: stessi magic, IV/chiave iniziale, offset e checksum.
 */
object LanProbe {

    private val LOCAL_IV = "562e17996d093d28ddb3ba695a2e6f58".hex()
    private val INIT_KEY = "097628343fe99e23765c1513accf8b02".hex()
    private val MAGIC = "5aa5aa555aa5aa55".hex()
    private const val PORT = 80
    private const val CMD_AUTH = 0x0065
    private const val CMD_DISCOVERY = 0x0006
    private const val CHECKSUM_SEED = 0xBEAF
    private val rnd = SecureRandom()

    // ---- checksum e little-endian ----
    private fun checksum(data: ByteArray): Int {
        var c = CHECKSUM_SEED
        for (b in data) c += b.toInt() and 0xFF
        return c and 0xFFFF
    }

    private fun putLE16(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte(); a[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putLE32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte(); a[off + 1] = ((v ushr 8) and 0xFF).toByte()
        a[off + 2] = ((v ushr 16) and 0xFF).toByte(); a[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun le16(a: ByteArray, off: Int) = (a[off].toInt() and 0xFF) or ((a[off + 1].toInt() and 0xFF) shl 8)

    // ---- AES-128-CBC con l'IV locale (payload già multiplo di 16) ----
    private fun aes(mode: Int, data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(LOCAL_IV))
        val src = if (data.size % 16 == 0) data else data.copyOf(data.size + (16 - data.size % 16))
        return c.doFinal(src)
    }

    // ---- pacchetto DNA (0x38 di header + payload cifrato) ----
    private fun buildPacket(devtype: Int, command: Int, mac6: ByteArray, deviceId: Int,
                            payload: ByteArray, key: ByteArray): ByteArray {
        val nonce = rnd.nextInt(0x10000)
        val header = ByteArray(0x38)
        System.arraycopy(MAGIC, 0, header, 0, 8)
        putLE16(header, 0x24, devtype)
        putLE16(header, 0x26, command)
        putLE16(header, 0x28, nonce and 0xFFFF)
        for (i in 0 until 6) header[0x2A + i] = mac6[5 - i]   // MAC invertito
        putLE32(header, 0x30, deviceId)
        putLE16(header, 0x34, checksum(payload))
        val full = header + aes(Cipher.ENCRYPT_MODE, payload, key)
        val c = checksum(full)                                // [0x20:0x22] ancora zero
        putLE16(full, 0x20, c)
        return full
    }

    private fun parsePacket(packet: ByteArray, key: ByteArray): ByteArray {
        if (packet.size < 0x38) throw LanProbeError("risposta troppo corta (${packet.size} byte)")
        val err = le16(packet, 0x22)
        if (err != 0) {
            val signed = if (err > 0x7FFF) err - 0x10000 else err
            throw LanProbeError("il dispositivo ha risposto con errore $signed")
        }
        var enc = packet.copyOfRange(0x38, packet.size)
        if (enc.isEmpty()) throw LanProbeError("risposta senza payload")
        if (enc.size % 16 != 0) enc = enc.copyOf(enc.size - enc.size % 16)
        return aes(Cipher.DECRYPT_MODE, enc, key)
    }

    // ---- discovery ----
    private fun buildDiscoveryPacket(localIp: String, localPort: Int): ByteArray {
        val cal = Calendar.getInstance()
        val tzOffset = cal.timeZone.getOffset(cal.timeInMillis) / 3600000
        val year = cal.get(Calendar.YEAR)
        val pkt = ByteArray(0x30)
        System.arraycopy(MAGIC, 0, pkt, 0, 8)
        putLE32(pkt, 0x08, tzOffset)
        putLE16(pkt, 0x0C, year)
        pkt[0x0E] = cal.get(Calendar.MINUTE).toByte()
        pkt[0x0F] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        pkt[0x10] = (year % 100).toByte()
        pkt[0x11] = (cal.get(Calendar.DAY_OF_WEEK) % 7).toByte()   // Calendar: DOM=1..SAB=7
        pkt[0x12] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        pkt[0x13] = (cal.get(Calendar.MONTH) + 1).toByte()
        val ip = localIp.split(".").map { it.toInt() }
        if (ip.size == 4) for (i in 0 until 4) pkt[0x18 + i] = ip[3 - i].toByte()   // IP invertito
        putLE16(pkt, 0x1C, localPort)
        pkt[0x26] = CMD_DISCOVERY.toByte()
        putLE16(pkt, 0x20, checksum(pkt))
        return pkt
    }

    private fun parseDiscoveryResponse(data: ByteArray, host: String): LanDevice? {
        if (data.size < 0x40) return null
        val devtype = le16(data, 0x34)
        val macRaw = ByteArray(6) { data[0x3A + (5 - it)] }        // invertito
        val mac = macRaw.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
        val name = if (data.size > 0x40) {
            val end = (0x40 until data.size).firstOrNull { data[it].toInt() == 0 } ?: data.size
            String(data, 0x40, end - 0x40, Charsets.UTF_8)
        } else ""
        return LanDevice(mac = mac, devtype = devtype, name = name, host = host)
    }

    /** Cerca i moduli DNA in broadcast sulla rete WiFi di casa. */
    fun discover(context: Context, timeoutMs: Int = 4000): List<LanDevice> {
        val found = LinkedHashMap<String, LanDevice>()
        DatagramSocket().use { sock ->
            val localIp = bindToWifi(context, sock) ?: "0.0.0.0"
            sock.broadcast = true
            sock.soTimeout = 1000
            val pkt = buildDiscoveryPacket(localIp, sock.localPort)
            sock.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), PORT))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val resp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(resp)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val dev = parseDiscoveryResponse(resp.data.copyOf(resp.length), resp.address.hostAddress ?: "")
                if (dev != null) found[dev.mac] = dev
            }
        }
        return found.values.toList()
    }

    /** Autentica un modulo trovato e ne ricava chiave AES + id (LAN). */
    fun authenticate(context: Context, dev: LanDevice, tries: Int = 3, timeoutMs: Int = 3000): LanDevice {
        val mac6 = ByteArray(6)
        val hex = dev.mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "MAC non valido: ${dev.mac}" }
        for (i in 0 until 6) mac6[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()

        val payload = ByteArray(0x50)
        val rand16 = ByteArray(16).also { rnd.nextBytes(it) }
        System.arraycopy(rand16, 0, payload, 0x04, 16)
        payload[0x1E] = 0x01
        payload[0x2D] = 0x01
        val tag = "Test 1".toByteArray(Charsets.US_ASCII)
        System.arraycopy(tag, 0, payload, 0x30, tag.size)

        val pkt = buildPacket(dev.devtype, CMD_AUTH, mac6, 0, payload, INIT_KEY)
        val resp = sendReceive(context, dev.host, pkt, tries, timeoutMs)
            ?: throw LanProbeError("nessuna risposta all'autenticazione da ${dev.host}")
        val plain = parsePacket(resp, INIT_KEY)
        if (plain.size < 0x14) throw LanProbeError("risposta di autenticazione troppo corta")
        val deviceId = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8) or
            ((plain[2].toInt() and 0xFF) shl 16) or ((plain[3].toInt() and 0xFF) shl 24)
        val key = plain.copyOfRange(0x04, 0x14).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return dev.copy(deviceId = deviceId, key = key)
    }

    private fun sendReceive(context: Context, host: String, pkt: ByteArray, tries: Int, timeoutMs: Int): ByteArray? {
        DatagramSocket().use { sock ->
            bindToWifi(context, sock)
            sock.soTimeout = timeoutMs
            val out = DatagramPacket(pkt, pkt.size, InetAddress.getByName(host), PORT)
            val n = if (tries < 1) 1 else tries
            for (i in 0 until n) {
                sock.send(out)
                try {
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    sock.receive(resp)
                    return resp.data.copyOf(resp.length)
                } catch (e: SocketTimeoutException) {
                    // riprova
                }
            }
        }
        return null
    }

    /** Lega il socket alla rete WiFi con internet (quella di casa) e ne ritorna l'IPv4. */
    private fun bindToWifi(context: Context, socket: DatagramSocket): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val net = cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } ?: return null
            net.bindSocket(socket)
            cm.getLinkProperties(net)?.linkAddresses
                ?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun String.hex(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) out[i] =
            ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        return out
    }
}

class LanProbeError(message: String) : Exception(message)
