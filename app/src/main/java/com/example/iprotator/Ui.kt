package com.example.iprotator

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Green = Color(0xFF00E676)
private val Blue = Color(0xFF40C4FF)
private val Bg = Color(0xFF0B0F14)
private val CardC = Color(0xFF151B23)

@Composable
fun AppUi(onToggle: () -> Unit, onImport: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = CardC)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = CardC) {
                    listOf("Rotator", "Proxies", "Settings").forEachIndexed { i, t ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = { Text(listOf("⚡", "🛡", "⚙")[i]) }, label = { Text(t) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> Dashboard(onToggle)
                    1 -> Pool(onImport)
                    else -> SettingsTab()
                }
            }
        }
    }
}

private fun fmt(b: Long) = if (b >= 1_048_576) "%.1f MB/s".format(b / 1048576.0) else "%.0f KB/s".format(b / 1024.0)

@Composable
fun Dashboard(onToggle: () -> Unit) {
    val running by Engine.running.collectAsState()
    val list by Engine.proxies.collectAsState()
    val idx by Engine.activeIndex.collectAsState()
    val next by Engine.nextRotateAt.collectAsState()
    val speeds by Engine.speeds.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val total = Store.interval * 1000f
    val remain = (next - now).coerceAtLeast(0) / 1000f
    val active = list.getOrNull(idx)

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
            if (running) CircularProgressIndicator(progress = { (remain * 1000f / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(), color = Green, strokeWidth = 6.dp)
            Box(Modifier.size(160.dp).clip(CircleShape).background(if (running) Green else Color(0xFF37414D))
                .clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Text(if (running) "ON" else "OFF", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    color = if (running) Color.Black else Color.White)
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardC)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Active proxy", color = Color.Gray, fontSize = 12.sp)
                Text(if (running && active != null) "${active.p.host}:${active.p.port}" else "—",
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = Green)
                Text("Next rotation in", color = Color.Gray, fontSize = 12.sp)
                Text(if (running) "%02d:%02ds".format(remain.toInt() / 60, remain.toInt() % 60) else "—",
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                val last = speeds.lastOrNull()
                Text("↓ ${fmt(last?.first ?: 0)}    ↑ ${fmt(last?.second ?: 0)}", fontFamily = FontFamily.Monospace)
            }
        }
        Chart(speeds.map { it.first }, speeds.map { it.second })
        Text("${list.count { it.ms > 0 }} of ${list.size} proxies alive", color = Color.Gray)
    }
}

@Composable
fun Chart(down: List<Long>, up: List<Long>) {
    Canvas(Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(12.dp)).background(CardC)) {
        val mx = maxOf(1L, down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L).toFloat()
        fun line(v: List<Long>, c: Color) {
            if (v.size < 2) return
            val p = Path()
            v.forEachIndexed { i, x ->
                val px = size.width * i / (v.size - 1)
                val py = size.height * (1f - x / mx) * 0.9f + 4f
                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            drawPath(p, c, style = Stroke(3f))
        }
        line(down, Green); line(up, Blue)
    }
}

@Composable
fun Pool(onImport: () -> Unit) {
    val list by Engine.proxies.collectAsState()
    val idx by Engine.activeIndex.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport) { Text("Import .txt/.csv") }
            OutlinedButton(onClick = { scope.launch { Engine.checkAll() } }) { Text("Test") }
            OutlinedButton(onClick = { Engine.proxies.value = emptyList(); Store.saveProxies() }) { Text("Clear") }
        }
        Text("Formats: ip:port  or  ip:port:user:pass  (one per line)", color = Color.Gray, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(list) { i, item ->
                val dot = when { item.ms == 0 -> Color.Gray; item.ms < 0 -> Color(0xFFFF5252)
                    item.ms < 400 -> Green; else -> Color(0xFFFFD740) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(if (i == idx) 2.dp else 0.dp, Green, RoundedCornerShape(10.dp))
                    .background(CardC).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(12.dp))
                    Text(if (i == idx) "🎯 " else "", fontSize = 14.sp)
                    Text("${item.p.host}:${item.p.port}" + if (item.p.user != null) "  🔑" else "",
                        fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Text(when { item.ms > 0 -> "${item.ms} ms"; item.ms < 0 -> "dead"; else -> "?" }, color = Color.Gray)
                }
            }
        }
    }
}

private fun loadApps(c: Context): List<Pair<String, String>> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != c.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
}

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    var iv by remember { mutableFloatStateOf(Store.interval.toFloat()) }
    var kill by remember { mutableStateOf(Store.kill) }
    var sel by remember { mutableStateOf(Store.apps) }
    var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Rotation interval: ${iv.toInt()} s", fontWeight = FontWeight.Bold)
            Slider(value = iv, onValueChange = { iv = it; Store.interval = it.toInt() }, valueRange = 10f..300f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Kill switch", fontWeight = FontWeight.Bold)
                    Text("Block internet if no proxy is alive", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = kill, onCheckedChange = { kill = it; Store.kill = it })
            }
            Spacer(Modifier.height(8.dp))
            Text("Split tunneling", fontWeight = FontWeight.Bold)
            Text("Ticked apps use the rotating tunnel. None ticked = all apps. Reconnect to apply.",
                color = Color.Gray, fontSize = 12.sp)
        }
        items(apps) { (pkg, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pkg in sel, onCheckedChange = {
                    sel = if (it) sel + pkg else sel - pkg; Store.apps = sel
                })
                Text(label)
            }
        }
    }
}
