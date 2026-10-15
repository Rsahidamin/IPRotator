package com.example.iprotator

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.widget.Toast
import hev.htproxy.TProxyService
import kotlinx.coroutines.*
import java.io.File

class RotatorVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var relay: Relay? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopAll() else if (!Engine.running.value) startAll()
        return START_NOT_STICKY
    }

    private fun startAll() {
        if (Engine.proxies.value.isEmpty()) {
            Toast.makeText(this, "Import proxies first", Toast.LENGTH_LONG).show(); stopSelf(); return
        }
        val b = Builder().setSession("IP Rotator").setMtu(8500)
            .addAddress("198.18.0.1", 32).addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("198.18.0.2")
        val apps = Store.apps
        if (apps.isNotEmpty()) apps.forEach { try { b.addAllowedApplication(it) } catch (_: Exception) {} }
        else try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
        val fd = b.establish() ?: run { stopSelf(); return }
        tun = fd

        Engine.protector = { protect(it) }
        relay = Relay(this, { Engine.current() }, { Store.kill }).also { it.start() }

        val conf = File(filesDir, "tproxy.yml")
        conf.writeText(
            """
            tunnel:
              mtu: 8500
              ipv4: 198.18.0.1
              ipv6: 'fd00:1:fd00:1:fd00:1:fd00:1'
            socks5:
              port: 1080
              address: 127.0.0.1
              udp: 'udp'
            mapdns:
              address: 198.18.0.2
              port: 53
              network: 100.64.0.0
              netmask: 255.192.0.0
              cache-size: 10000
            misc:
              task-stack-size: 81920
              connect-timeout: 5000
              read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        TProxyService.TProxyStartService(conf.absolutePath, fd.fd)
        Engine.running.value = true

        job = scope.launch {
            Engine.checkAll(); rotate()
            launch { // live stats
                var lastTx = 0L; var lastRx = 0L
                while (isActive) {
                    delay(1000)
                    val s = try { TProxyService.TProxyGetStats() } catch (_: Throwable) { null } ?: continue
                    if (s.size < 4) continue
                    val d = (s[3] - lastRx).coerceAtLeast(0); val u = (s[1] - lastTx).coerceAtLeast(0)
                    lastRx = s[3]; lastTx = s[1]
                    Engine.speeds.value = (Engine.speeds.value + (d to u)).takeLast(60)
                }
            }
            launch { // background health checks
                while (isActive) {
                    delay(45_000); Engine.checkAll()
                    val cur = Engine.activeIndex.value
                    if (cur < 0 || Engine.proxies.value.getOrNull(cur)?.ms == -1) rotate()
                }
            }
            while (isActive) { // rotation timer
                val ms = Store.interval * 1000L
                Engine.nextRotateAt.value = System.currentTimeMillis() + ms
                delay(ms); rotate()
            }
        }
    }

    private fun rotate() {
        val l = Engine.proxies.value; val n = l.size
        if (n == 0) return
        val old = Engine.activeIndex.value
        var next = -1
        for (k in 1..n) { val j = (((old + k) % n) + n) % n; if (l[j].ms >= 0) { next = j; break } }
        Engine.activeIndex.value = next
        if (next != old) relay?.closeAll() // force apps to reconnect on the new IP
    }

    private fun stopAll() {
        job?.cancel()
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        relay?.stop(); relay = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        Engine.running.value = false; Engine.activeIndex.value = -1; Engine.protector = null
        Engine.speeds.value = emptyList()
        stopSelf()
    }

    override fun onRevoke() { stopAll() }
    override fun onDestroy() { if (Engine.running.value) stopAll(); scope.cancel(); super.onDestroy() }
}
