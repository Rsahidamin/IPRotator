package com.example.iprotator

import android.net.VpnService
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Local SOCKS5 server on 127.0.0.1:1080. The native tunnel (hev-socks5-tunnel) always talks to it,
 * and it forwards each new connection to the *currently active* upstream proxy.
 * Rotation = closeAll() (forced fast-reset) + new connections use the next proxy. The TUN never closes.
 */
class Relay(private val vpn: VpnService, private val pick: () -> Proxy?, private val kill: () -> Boolean) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val conns = ConcurrentHashMap.newKeySet<Socket>()

    fun start() {
        val ss = ServerSocket(1080, 256, InetAddress.getByName("127.0.0.1"))
        server = ss
        pool.execute {
            try { while (true) { val c = ss.accept(); pool.execute { handle(c) } } } catch (_: Exception) {}
        }
    }

    fun stop() { try { server?.close() } catch (_: Exception) {}; closeAll(); pool.shutdownNow() }

    fun closeAll() { conns.forEach { quiet(it) }; conns.clear() }

    private fun quiet(s: Socket?) { try { s?.close() } catch (_: Exception) {} }
    private fun InputStream.readN(n: Int): ByteArray {
        val b = ByteArray(n); var o = 0
        while (o < n) { val r = read(b, o, n - o); if (r < 0) throw EOFException(); o += r }
        return b
    }
    private fun reply(code: Int) = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    private fun handle(c: Socket) {
        var up: Socket? = null
        conns.add(c)
        try {
            val ci = c.getInputStream(); val co = c.getOutputStream()
            if (ci.read() != 5) return
            ci.readN(ci.read().coerceAtLeast(0))
            co.write(byteArrayOf(5, 0)); co.flush()

            val h = ci.readN(4)
            val req = ByteArrayOutputStream(); req.write(h)
            val addr: ByteArray = when (h[3].toInt()) {
                1 -> ci.readN(4)
                4 -> ci.readN(16)
                3 -> { val l = ci.read(); byteArrayOf(l.toByte()) + ci.readN(l) }
                else -> return
            }
            val port = ci.readN(2)
            req.write(addr); req.write(port)
            if (h[1].toInt() != 1) { co.write(reply(7)); return } // only CONNECT (no UDP)

            val p = pick()
            if (p == null) {
                if (kill()) { co.write(reply(2)); return } // kill switch: block
                val host = if (h[3].toInt() == 3) String(addr, 1, addr.size - 1) else InetAddress.getByAddress(addr).hostAddress
                val dport = ((port[0].toInt() and 0xff) shl 8) or (port[1].toInt() and 0xff)
                up = Socket(); vpn.protect(up); up.connect(InetSocketAddress(host, dport), 8000)
                conns.add(up); co.write(reply(0)); co.flush()
            } else {
                up = Socket(); vpn.protect(up)
                up.connect(InetSocketAddress(p.host, p.port), 5000); up.soTimeout = 8000
                conns.add(up)
                val ui = up.getInputStream(); val uo = up.getOutputStream()
                uo.write(if (p.user != null) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)); uo.flush()
                val m = ui.readN(2)
                if (m[1].toInt() == 2) {
                    val u = (p.user ?: "").toByteArray(); val pw = (p.pass ?: "").toByteArray()
                    uo.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(pw.size.toByte()) + pw); uo.flush()
                    if (ui.readN(2)[1].toInt() != 0) { co.write(reply(1)); return }
                } else if (m[1].toInt() != 0) { co.write(reply(1)); return }
                uo.write(req.toByteArray()); uo.flush()
                val rh = ui.readN(4)
                val rest = when (rh[3].toInt()) {
                    1 -> ui.readN(6); 4 -> ui.readN(18)
                    3 -> { val l = ui.read(); byteArrayOf(l.toByte()) + ui.readN(l + 2) }
                    else -> return
                }
                co.write(rh + rest); co.flush()
                if (rh[1].toInt() != 0) return
                up.soTimeout = 0
            }
            pipe(c, up!!)
        } catch (_: Exception) {
        } finally { quiet(c); quiet(up); conns.remove(c); up?.let { conns.remove(it) } }
    }

    private fun pipe(a: Socket, b: Socket) {
        val t = Thread { copy(b.getInputStream(), a.getOutputStream()); try { a.shutdownOutput() } catch (_: Exception) {} }
        t.isDaemon = true; t.start()
        copy(a.getInputStream(), b.getOutputStream())
        try { b.shutdownOutput() } catch (_: Exception) {}
        t.join()
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16384)
        try { while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } } catch (_: Exception) {}
    }
}
