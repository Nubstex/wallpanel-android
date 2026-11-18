package xyz.wallpanel.app.utils

import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Philips SICP (Serial Interface Communication Protocol) client. The app and the panel it
 * controls are the same device, so commands always go to the panel's own loopback rather than
 * a LAN address. Port/framing match the reference sicp_test.py script: [length, monitorId,
 * groupId, ...data, checksum], checksum = XOR of every byte including the length byte.
 */
object SicpClient {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5000
    private const val TIMEOUT_MS = 3000
    private const val MONITOR_ID = 1
    private const val GROUP_ID = 0

    fun send(data: IntArray) {
        Thread {
            try {
                val payload = ArrayList<Int>(data.size + 2)
                payload.add(MONITOR_ID)
                payload.add(GROUP_ID)
                data.forEach { payload.add(it) }

                val buf = ArrayList<Int>(payload.size + 2)
                buf.add(payload.size + 2) // length byte + checksum byte
                buf.addAll(payload)

                var checksum = 0
                for (b in buf) checksum = checksum xor (b and 0xFF)
                buf.add(checksum)

                val bytes = ByteArray(buf.size) { buf[it].toByte() }
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS
                    socket.getOutputStream().apply {
                        write(bytes)
                        flush()
                    }
                    try {
                        socket.getInputStream().read(ByteArray(1024))
                    } catch (e: Exception) {
                        // response is best-effort only, nothing to act on
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "SICP send failed")
            }
        }.start()
    }

    fun screenOff() = send(intArrayOf(0x18, 0x01))
    fun screenOn() = send(intArrayOf(0x18, 0x02))
    fun setColor(r: Int, g: Int, b: Int) = send(intArrayOf(0xF3, 1, r and 0xFF, g and 0xFF, b and 0xFF))
    fun ledOff() = send(intArrayOf(0xF3, 0, 0, 0, 0))
}
