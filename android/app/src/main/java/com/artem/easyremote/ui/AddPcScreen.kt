@file:OptIn(ExperimentalMaterial3Api::class)

package com.artem.easyremote.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.artem.easyremote.AppViewModel
import com.artem.easyremote.PairUi
import com.artem.easyremote.Pc
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AddPcScreen(vm: AppViewModel) {
    val wall by vm.paywall.state.collectAsState()
    val pair by vm.pair.collectAsState()
    val found by vm.discovery.found.collectAsState()
    val pcs by vm.pcs.collectAsState()
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val working = pair is PairUi.Working

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw != null) vm.pairFromLink(raw) else vm.pairError("The QR code was empty.")
            }
            .addOnFailureListener { e ->
                // Cancelled by the user, or Google Play services' scanner is not available
                val msg = e.message.orEmpty()
                if (!msg.contains("cancel", ignoreCase = true)) {
                    vm.pairError("Could not open the QR scanner ($msg). You can enter the code manually instead.")
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add PC") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (wall.enabled && pcs.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your first PC is free. Each additional PC needs a one-time purchase.", style = MaterialTheme.typography.bodyMedium)
                        if (wall.credits > 0) {
                            Text(
                                if (wall.credits == 1) "1 PC slot bought: pair your PC now." else "${wall.credits} PC slots bought.",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Button(
                            onClick = { context.findActivity()?.let { vm.paywall.buy(it) } },
                            enabled = !wall.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Buy a PC slot" + (wall.price?.let { " – $it" } ?: "")) }
                        wall.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Text("On your PC, run", style = MaterialTheme.typography.bodyMedium)
            Text("easyremote pair", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Button(onClick = { scan() }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Scan QR code")
            }

            Text("No camera? Enter the code", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))

            val unpaired: List<Pair<String, String>> = found.entries
                .filter { (id, _) -> pcs.none { it.shortId == id } }
                .map { (_, f) -> f.name to f.host }
            if (unpaired.isNotEmpty()) {
                Text("Nearby", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, host) in unpaired) {
                        AssistChip(onClick = { address = host }, label = { Text(name) })
                    }
                }
            }

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("PC address (e.g. 192.168.1.50)") },
                singleLine = true,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Code (ABCD-EFGH-IJKL-MNOP)") },
                singleLine = true,
                enabled = !working,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.pairManual(address, code) },
                enabled = !working && address.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair") }

            when (val p = pair) {
                is PairUi.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Pairing…")
                }
                is PairUi.Error -> Text(p.message, color = MaterialTheme.colorScheme.error)
                is PairUi.Idle -> {}
            }
        }
    }
}
