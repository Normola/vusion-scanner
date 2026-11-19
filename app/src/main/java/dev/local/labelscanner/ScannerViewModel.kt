package dev.local.labelscanner

import android.app.Application
import android.bluetooth.le.ScanResult
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.local.labelscanner.ble.LabelBleScanner
import dev.local.labelscanner.decode.BeaconPayloadDecoder
import dev.local.labelscanner.decode.DecodeResult
import dev.local.labelscanner.decode.FrameFormat
import dev.local.labelscanner.mapping.LabelLocation
import dev.local.labelscanner.mapping.LabelMap
import dev.local.labelscanner.signal.FrontEvent
import dev.local.labelscanner.signal.FrontModularConfig
import dev.local.labelscanner.signal.FrontModularDetector
import dev.local.labelscanner.signal.NearestDeviceDetector
import dev.local.labelscanner.signal.RssiPipeline
import dev.local.labelscanner.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

data class LabelRow(
    val id: String,
    val linkId: String?,
    val mac: String,
    val status: String,
    val rawRssi: Int,
    val filteredRssi: Int?,
    val lastSeenMs: Long,
    val recordHex: String,
    val location: LabelLocation?,
)

data class NearestUi(val deviceId: String, val aisle: String?, val meanRssi: Int)

data class FrontUi(
    val deviceId: String,
    val aisle: String,
    val modular: String,
    val meanRssi: Int,
    val confidence: Float,
    val lost: Boolean,
)

data class UiState(
    val scanning: Boolean = false,
    val labels: List<LabelRow> = emptyList(),
    val nearest: NearestUi? = null,
    val front: FrontUi? = null,
    val message: String? = null,
    val mappingCount: Int = 0,
    val settings: ScannerSettings = ScannerSettings(),
    val advertsPerSecond: Int = 0,
)

class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val scanner = LabelBleScanner(app)
    private val pipeline = RssiPipeline()
    private val frontConfig = FrontModularConfig()
    private val frontDetector = FrontModularDetector(frontConfig)

    private var settings = store.load()
    private var decoder = BeaconPayloadDecoder(keyOrNull(settings))
    private var labelMap = store.loadLabelMap()
    private val nearestDetector = NearestDeviceDetector(settings.hysteresisDb)

    private val rows = ConcurrentHashMap<String, LabelRow>()
    private val decodeCache = ConcurrentHashMap<String, Pair<ByteArray, DecodeResult>>()
    private val advertCount = AtomicInteger()
    @Volatile private var currentAisle: String? = null
    private var loops: List<Job> = emptyList()

    private val _state = MutableStateFlow(UiState(mappingCount = labelMap.size, settings = settings))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val isBluetoothEnabled: Boolean get() = scanner.isBluetoothEnabled

    fun startScan() {
        if (_state.value.scanning) return
        if (!scanner.isBluetoothAvailable) {
            _state.update { it.copy(message = "This device has no Bluetooth adapter") }
            return
        }
        val started = scanner.start(settings.labelsOnly, ::onScanResult) { msg ->
            viewModelScope.launch {
                stopLoops()
                _state.update { it.copy(scanning = false, message = msg) }
            }
        }
        if (!started) return

        _state.update { it.copy(scanning = true, message = null) }
        loops = listOf(
            viewModelScope.launch {
                while (isActive) {
                    publishRows()
                    delay(PUBLISH_INTERVAL_MS)
                }
            },
            viewModelScope.launch {
                while (isActive) {
                    delay(NEAREST_INTERVAL_MS)
                    evaluateNearest()
                }
            },
            viewModelScope.launch {
                while (isActive) {
                    delay(frontConfig.evaluationIntervalMs)
                    evaluateFront()
                }
            },
        )
    }

    fun stopScan() {
        scanner.stop()
        stopLoops()
        _state.update { it.copy(scanning = false) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    /** Returns an error message, or null on success. */
    fun saveSettings(new: ScannerSettings): String? {
        val key = try {
            SettingsStore.parseKey(new.keyHex, new.ivHex)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        val wasScanning = _state.value.scanning
        val restartScanner = wasScanning && new.labelsOnly != settings.labelsOnly

        settings = new
        store.save(new)
        decoder = BeaconPayloadDecoder(key)
        nearestDetector.hysteresisDb = new.hysteresisDb
        resetResults()
        _state.update { it.copy(settings = new) }

        if (restartScanner) {
            stopScan()
            startScan()
        }
        return null
    }

    fun importLabelMap(uri: Uri) {
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open file")
                    val parsed = LabelMap.parseCsv(text)
                    store.saveLabelMapCsv(text)
                    parsed
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Import failed: ${e.message}") }
                return@launch
            }
            labelMap = result.map
            for ((id, row) in rows) rows[id] = row.copy(location = labelMap.lookup(row.linkId, row.mac))
            val skipped = if (result.skippedLines.isEmpty()) "" else
                " (skipped lines ${result.skippedLines.take(10).joinToString()})"
            _state.update {
                it.copy(mappingCount = labelMap.size, message = "Loaded ${labelMap.size} label locations$skipped")
            }
        }
    }

    override fun onCleared() {
        scanner.stop()
        super.onCleared()
    }

    private fun onScanResult(result: ScanResult) {
        val record = result.scanRecord?.bytes ?: return
        advertCount.incrementAndGet()
        val mac = result.device.address

        val cached = decodeCache[mac]
        val decoded = if (cached != null && cached.first.contentEquals(record)) cached.second
        else decoder.decode(record).also { decodeCache[mac] = record to it }

        val linkId = (decoded as? DecodeResult.Decoded)?.linkId
        val id = linkId ?: mac
        val status = when (decoded) {
            is DecodeResult.Decoded -> when (decoded.format) {
                FrameFormat.LEGACY_BEACON -> "legacy beacon"
                FrameFormat.PLAIN -> "plain frame"
                FrameFormat.DECRYPTED -> "decrypted"
            }
            is DecodeResult.Encrypted -> "encrypted (no key set)"
            is DecodeResult.Invalid -> decoded.reason
        }

        val filtered = pipeline.ingest(id, result.rssi)
        val previous = rows[id]
        rows[id] = LabelRow(
            id = id,
            linkId = linkId,
            mac = mac,
            status = status,
            rawRssi = result.rssi,
            filteredRssi = filtered ?: previous?.filteredRssi,
            lastSeenMs = System.currentTimeMillis(),
            recordHex = trimTrailingZeros(record).toHex(),
            location = previous?.location?.takeIf { previous.linkId == linkId } ?: labelMap.lookup(linkId, mac),
        )
    }

    private fun publishRows() {
        val now = System.currentTimeMillis()
        rows.entries.removeIf { now - it.value.lastSeenMs > ROW_EXPIRY_MS }
        val sorted = rows.values.sortedByDescending { it.filteredRssi ?: it.rawRssi }
        val perSecond = (advertCount.getAndSet(0) * 1000 / PUBLISH_INTERVAL_MS).toInt()
        _state.update { it.copy(labels = sorted, advertsPerSecond = perSecond) }
    }

    private fun evaluateNearest() {
        val snapshot = pipeline.snapshot(System.currentTimeMillis() - NEAREST_WINDOW_MS)
        if (snapshot.isEmpty()) return
        val nearest = nearestDetector.evaluate(snapshot) ?: return
        val aisle = rows[nearest.deviceId]?.location?.aisle
        currentAisle = aisle
        _state.update { it.copy(nearest = NearestUi(nearest.deviceId, aisle, nearest.meanRssi.roundToInt())) }
    }

    private fun evaluateFront() {
        val snapshot = pipeline.snapshot(System.currentTimeMillis() - frontConfig.windowMs)
        val hint = if (frontConfig.restrictToLastKnownAisle) currentAisle else null
        when (val event = frontDetector.evaluate(
            snapshot,
            hint,
            aisleOf = { rows[it]?.location?.aisle },
            modularOf = { rows[it]?.location?.modular },
        )) {
            is FrontEvent.Updated -> _state.update {
                it.copy(front = FrontUi(event.deviceId, event.aisle, event.modular, event.meanRssi, event.confidence, lost = false))
            }
            is FrontEvent.Lost -> _state.update { it.copy(front = it.front?.copy(lost = true)) }
            null -> Unit
        }
    }

    private fun stopLoops() {
        loops.forEach { it.cancel() }
        loops = emptyList()
    }

    private fun resetResults() {
        rows.clear()
        decodeCache.clear()
        pipeline.clear()
        nearestDetector.reset()
        frontDetector.reset()
        currentAisle = null
        _state.update { it.copy(labels = emptyList(), nearest = null, front = null) }
    }

    private fun keyOrNull(s: ScannerSettings) = try {
        SettingsStore.parseKey(s.keyHex, s.ivHex)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun trimTrailingZeros(bytes: ByteArray): ByteArray {
        var end = bytes.size
        while (end > 0 && bytes[end - 1] == 0.toByte()) end--
        return bytes.copyOf(end)
    }

    companion object {
        private const val PUBLISH_INTERVAL_MS = 500L
        private const val NEAREST_INTERVAL_MS = 1_000L
        private const val NEAREST_WINDOW_MS = 5_000L
        private const val ROW_EXPIRY_MS = 30_000L
    }
}
