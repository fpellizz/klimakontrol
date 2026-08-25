package net.klimakontrol.data.softap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Config SoftAP di un modulo vergine: pacchetto di setup BroadLink.
 *
 * Porta in Kotlin di `klimakontrol/provision.py` (spec: docs/softap-apconfig.md), ricostruito
 * dal nativo `libNetworkAPI.so`: 136 byte in chiaro, comando 0x14, checksum seed 0xBEAF,
 * inviato in UDP a 192.168.10.1:80. Il pacchetto è verificato byte-per-byte dal test dorato
 * Python (TestNet/secret12 → checksum 0xC482): questa porta ne replica esattamente la logica.
 */
object SoftApClient {

    const val GATEWAY = "192.168.10.1"
    const val PORT = 80
    private const val CMD_APCONFIG = 0x14
    private const val FIELD = 0x20   // ogni campo (ssid/password) è lungo 32 byte

    /** Costruisce il pacchetto SoftAP (136 byte). Vedi docs/softap-apconfig.md. */
    fun buildPacket(ssid: ByteArray, password: ByteArray, security: Int): ByteArray {
        val s = if (ssid.size > FIELD) ssid.copyOf(FIELD) else ssid
        val p = if (password.size > FIELD) password.copyOf(FIELD) else password
        val pkt = ByteArray(0x88)
        pkt[0x26] = CMD_APCONFIG.toByte()
        System.arraycopy(s, 0, pkt, 0x44, s.size)
        System.arraycopy(p, 0, pkt, 0x64, p.size)
        pkt[0x84] = s.size.toByte()
        pkt[0x85] = p.size.toByte()
        pkt[0x86] = (security and 0xFF).toByte()
        var c = 0xBEAF                                     // seed; [0x20:0x22] sono ancora zero
        for (b in pkt) c = (c + (b.toInt() and 0xFF)) and 0xFFFF
        pkt[0x20] = (c and 0xFF).toByte()
        pkt[0x21] = ((c ushr 8) and 0xFF).toByte()
        return pkt
    }

    /**
     * Manda al modulo (in SoftAP) le credenziali WiFi. Ritorna la risposta grezza o null.
     *
     * Prerequisito: il telefono dev'essere connesso all'hotspot del modulo (Broadlink_tcl_…),
     * così 192.168.10.1 è raggiungibile. L'invio è ripetuto come nel nativo.
     *
     * Su Android moderno, con i dati mobili attivi, il sistema tiene la rete "validata" (cellulare)
     * come default per i socket: senza legare il socket alla rete WiFi del modulo, il pacchetto
     * uscirebbe dall'interfaccia sbagliata. Qui troviamo la rete WiFi **senza internet** (l'hotspot
     * del modulo) e ci leghiamo il socket. Serve solo ACCESS_NETWORK_STATE (già nel manifest).
     */
    suspend fun provision(
        context: Context,
        ssid: String,
        password: String,
        security: Int,
        tries: Int = 3,
        timeoutMs: Int = 2000,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val pkt = buildPacket(
            ssid.toByteArray(Charsets.UTF_8),
            password.toByteArray(Charsets.UTF_8),
            security,
        )
        DatagramSocket().use { sock ->
            bindToSoftApNetwork(context, sock)
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(GATEWAY)
            val out = DatagramPacket(pkt, pkt.size, addr, PORT)
            val n = if (tries < 1) 1 else tries
            for (i in 0 until n) {
                sock.send(out)
                if (i < n - 1) Thread.sleep(200)
            }
            try {
                val buf = ByteArray(2048)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                resp.data.copyOf(resp.length)
            } catch (e: SocketTimeoutException) {
                null
            }
        }
    }

    /** Lega il socket alla rete WiFi senza internet (l'hotspot del modulo), se presente. */
    private fun bindToSoftApNetwork(context: Context, socket: DatagramSocket) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val apNetwork = cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            apNetwork?.bindSocket(socket)
        } catch (e: Exception) {
            // best effort: se non riusciamo a legare, proviamo comunque a inviare
        }
    }
}
