package com.example.iprotator

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startVpn()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppUi(onToggle = ::toggle, onImport = { picker.launch("*/*") }) }
    }

    private fun toggle() {
        if (Engine.running.value) {
            startService(Intent(this, RotatorVpnService::class.java).setAction("STOP"))
        } else {
            val i = VpnService.prepare(this)
            if (i != null) vpnPerm.launch(i) else startVpn()
        }
    }

    private fun startVpn() { startService(Intent(this, RotatorVpnService::class.java)) }

    private fun importFile(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
        val parsed = parseProxies(text)
        val existing = Engine.proxies.value
        val known = existing.map { it.p }.toSet()
        val added = parsed.filter { it !in known }.distinct()
        Engine.proxies.value = existing + added.map { Item(it) }
        Store.saveProxies()
        Toast.makeText(this, "Imported ${added.size} proxies (${parsed.size} valid lines)", Toast.LENGTH_LONG).show()
    }
}
