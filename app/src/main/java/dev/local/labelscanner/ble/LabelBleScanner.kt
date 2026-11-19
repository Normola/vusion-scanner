package dev.local.labelscanner.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/**
 * Thin wrapper around BluetoothLeScanner. Callers must hold the scan permissions
 * (see MainActivity) before calling [start].
 */
class LabelBleScanner(context: Context) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    val isBluetoothAvailable: Boolean get() = adapter != null
    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Starts scanning. With [labelsOnly], matches advertisements that list the label
     * service UUID or carry Service Data for it; otherwise reports every advertisement.
     */
    @SuppressLint("MissingPermission")
    fun start(labelsOnly: Boolean, onResult: (ScanResult) -> Unit, onFailed: (String) -> Unit): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onFailed("Bluetooth LE scanner unavailable. Is Bluetooth on?")
            return false
        }
        stop()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(onResult)
            override fun onScanFailed(errorCode: Int) = onFailed(describeError(errorCode))
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .setLegacy(true)
            .build()

        // Filters are OR'd: match either a UUID list entry or a Service Data entry.
        val filters = if (labelsOnly) listOf(
            ScanFilter.Builder().setServiceUuid(LABEL_SERVICE_UUID).build(),
            ScanFilter.Builder().setServiceData(LABEL_SERVICE_UUID, ByteArray(0)).build(),
        ) else emptyList()

        return try {
            scanner.startScan(filters, settings, cb)
            callback = cb
            true
        } catch (e: SecurityException) {
            onFailed("Missing Bluetooth scan permission")
            false
        } catch (e: IllegalStateException) {
            onFailed("Bluetooth is off")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
            // Adapter turned off or permission revoked; nothing left to stop.
        }
    }

    private fun describeError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "Scan registration failed. Android limits apps to 5 scan starts per 30 s; wait and retry."
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning not supported on this device"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal Bluetooth error"
        else -> "Scan failed (error $code)"
    }

    companion object {
        /** 16-bit service UUID 0xFC8C used by the labels. */
        val LABEL_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000fc8c-0000-1000-8000-00805f9b34fb")
    }
}
