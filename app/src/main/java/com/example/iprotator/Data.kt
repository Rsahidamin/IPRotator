package com.example.iprotator

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket

class App : Application() {
    override fun onCreate() { super.onCreate(); Store.init(this) }
}

data class Proxy(val host: String, val port: Int, val user: String? = null, val pass: String? = null) {
    fun line() = if (user != null) "$host:$port:$user:$pass" else "$host:$port"
}

/** ms: 0 = unknown, -1 = dead, >0 = latency */
data class Item(val p: Proxy, val ms: Int = 0)

/** Accepts: ip:port | ip:port:user:pass | user:pass@ip:port | socks5://... | csv/space separated */
fun parseProxies(text: String): List<Proxy> = text.lineSequence().mapNotNull { raw ->
    try {
        var s = raw.substringBefore('#').trim().removePrefix("socks5h://").removePrefix("socks5://")
        if (s.isEmpty()) return@mapNotNull null
        s = s.replace(Regex("[,;\\s]+"), ":")
        if ('@' in s) {
            val (cred, hp) = s.split('@', limit = 2)
            val c = cred.split(':'); val h = hp.split(':')
            Proxy(h[0], h[1].toInt().also { require(it in 1..65535) }, c[0], c.getOrElse(1) { "" })
        } else {
            val p = s.split(':')
            val port = p[1].toInt(); require(port in 1..65535)
            if (p.size >= 4) Proxy(p[0], port, p[2], p[3]) else Proxy(p[0], port)
        }
    } catch (e: Exception) { null }
}.toList()

object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("s", 0)
        Engine.proxies.value = parseProxies(sp.getString("p", "") ?: "").map { Item(it) }
    }
    var interval: Int
        get() = sp.getInt("iv", 30)
        set(v) { sp.edit().putInt("iv", v).apply() }
    var kill: Boolean
        get() = sp.getBoolean("k", true)
        set(v) { sp.edit().putBoolean("k", v).apply() }
    /** packages that go through the tunnel; empty = all apps */
    var apps: Set<String>
        get() = (sp.getStringSet("a", emptySet()) ?: emptySet()).toSet()
        set(v) { sp.edit().putStringSet("a", v).apply() }
    fun saveProxies() {
        sp.edit().putString("p", Engine.proxies.value.joinToString("\n") { it.p.line() }).apply()
    }
}

object Engine {
    val proxies = MutableStateFlow<List<Item>>(emptyList())
    val running = MutableStateFlow(false)
    val activeIndex = MutableStateFlow(-1)
    val nextRotateAt = MutableStateFlow(0L)
    val speeds = MutableStateFlow<List<Pair<Long, Long>>>(emptyList()) // (down, up) bytes/s
    @Volatile var protector: ((Socket) -> Unit)? = null

    fun current(): Proxy? {
        val it = proxies.value.getOrNull(activeIndex.value) ?: return null
        return if (it.ms == -1) null else it.p
    }

    private fun ping(p: Proxy): Int = try {
        val s = Socket()
        protector?.invoke(s)
        val t = System.nanoTime()
        s.connect(InetSocketAddress(p.host, p.port), 3000)
        s.close()
        maxOf(1, ((System.nanoTime() - t) / 1_000_000).toInt())
    } catch (e: Exception) { -1 }

    suspend fun checkAll() {
        val snap = proxies.value
        val res = coroutineScope {
            snap.map { async(Dispatchers.IO) { it.p to ping(it.p) } }.awaitAll().toMap()
        }
        proxies.update { cur -> cur.map { it.copy(ms = res[it.p] ?: it.ms) } }
    }
}
