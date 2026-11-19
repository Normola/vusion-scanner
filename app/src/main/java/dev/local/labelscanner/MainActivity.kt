package dev.local.labelscanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ScannerApp(viewModel)
            }
        }
    }
}

/** Permissions that must be granted to scan. Precise location is needed for beacon results. */
private val requiredPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** What we ask for. Android 12+ ignores a FINE request unless COARSE is requested alongside it. */
private val requestedPermissions: Array<String> =
    requiredPermissions + Manifest.permission.ACCESS_COARSE_LOCATION

private fun hasPermissions(context: Context) = requiredPermissions.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Checks Bluetooth (and, on Android 11 and below, location services) before starting. */
private fun startIfReady(vm: ScannerViewModel, context: Context) {
    if (!vm.isBluetoothEnabled) {
        vm.showMessage("Turn Bluetooth on, then press Start again")
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        val lm = context.getSystemService(LocationManager::class.java)
        if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
            vm.showMessage("Android 11 and below only return BLE results with Location turned on")
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
    }
    vm.startScan()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerApp(vm: ScannerViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        when {
            requiredPermissions.all { grants[it] == true } -> startIfReady(vm, context)
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ->
                vm.showMessage("Choose \"Precise\" location: Android hides beacon results with approximate location only")
            else -> vm.showMessage("Bluetooth scan and location permissions are needed to scan")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLabelMap(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Label Scanner") },
                actions = { TextButton(onClick = { showSettings = true }) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    when {
                        state.scanning -> vm.stopScan()
                        hasPermissions(context) -> startIfReady(vm, context)
                        else -> permissionLauncher.launch(requestedPermissions)
                    }
                }) { Text(if (state.scanning) "Stop" else "Start scan") }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (state.scanning) "${state.labels.size} labels · ${state.advertsPerSecond} adverts/s"
                    else "Idle",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.message?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = vm::dismissMessage) { Text("OK") }
                    }
                }
            }

            LocationCards(state)

            Text("Labels in range", style = MaterialTheme.typography.titleSmall)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.labels, key = { it.id }) { LabelCard(it) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = state.settings,
            mappingCount = state.mappingCount,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onSave = vm::saveSettings,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun LocationCards(state: UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Nearest label", style = MaterialTheme.typography.labelMedium)
            val nearest = state.nearest
            if (nearest == null) {
                Text("—")
            } else {
                Text("${nearest.deviceId}  (${nearest.meanRssi} dBm)", fontWeight = FontWeight.SemiBold)
                Text("Aisle: ${nearest.aisle ?: if (state.mappingCount == 0) "load a label map" else "not in map"}")
            }

            Spacer(Modifier.padding(2.dp))
            Text("Facing", style = MaterialTheme.typography.labelMedium)
            val front = state.front
            when {
                state.mappingCount == 0 -> Text("Needs a label map (Settings → Import)")
                front == null -> Text("—")
                else -> {
                    Text(
                        "Aisle ${front.aisle} · Modular ${front.modular}" + if (front.lost) "  (lost)" else "",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "via ${front.deviceId} · ${front.meanRssi} dBm · confidence " +
                            String.format(Locale.US, "%.2f", front.confidence)
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelCard(row: LabelRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.linkId ?: row.mac,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text("${row.filteredRssi ?: "—"} dBm", fontWeight = FontWeight.SemiBold)
            }
            Text(
                buildString {
                    append(row.status)
                    append(" · raw ${row.rawRssi}")
                    if (row.linkId != null) append(" · ${row.mac}")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            row.location?.let {
                Text(
                    "Aisle ${it.aisle}" + (it.modular?.let { m -> " · Modular $m" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                row.recordHex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    initial: ScannerSettings,
    mappingCount: Int,
    onImport: () -> Unit,
    onSave: (ScannerSettings) -> String?,
    onDismiss: () -> Unit,
) {
    var keyHex by remember { mutableStateOf(initial.keyHex) }
    var ivHex by remember { mutableStateOf(initial.ivHex) }
    var labelsOnly by remember { mutableStateOf(initial.labelsOnly) }
    var hysteresis by remember { mutableStateOf(initial.hysteresisDb.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Optional AES key and IV (hex) for encrypted label frames. Leave blank to show encrypted frames undecoded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = keyHex, onValueChange = { keyHex = it },
                    label = { Text("AES key (hex)") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = ivHex, onValueChange = { ivHex = it },
                    label = { Text("IV (hex, 16 bytes)") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = hysteresis, onValueChange = { hysteresis = it },
                    label = { Text("Nearest-label hysteresis (dB)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Labels only")
                        Text("Filter to service 0xFC8C. Turn off to see every advert.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = labelsOnly, onCheckedChange = { labelsOnly = it })
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(if (mappingCount == 0) "Import label map (CSV)" else "Replace label map ($mappingCount entries)")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hysteresis.trim().toDoubleOrNull()
                if (h == null || h < 0) {
                    error = "Hysteresis must be a number ≥ 0"
                    return@TextButton
                }
                val result = onSave(ScannerSettings(keyHex.trim(), ivHex.trim(), labelsOnly, h))
                if (result == null) onDismiss() else error = result
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
