package com.armsx2.telemetry

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.documentfile.provider.DocumentFile
import com.armsx2.BuildConfig
import com.armsx2.ui.InGameOverlay
import com.armsx3.core.ARMSX3
import com.armsx3.core.EmulatorState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All native sampling, SAF access and serialization is confined to one IO thread. */
object PerformanceTelemetry {
    val enabled = MutableStateFlow(false)
    val folder = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("Choose a folder to enable automatic performance logging.")
    private lateinit var context: Context
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Performance recorder").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    private var initialized = false
    private var session: RecordingSession? = null
    private var failure = false
    private var lastState = -1
    @Volatile private var bootPending = false
    private val preferences get() = context.getSharedPreferences("performance-telemetry", Context.MODE_PRIVATE)

    @Synchronized fun initialize(app: Context) {
        if (initialized) return
        initialized = true
        context = app.applicationContext
        enabled.value = preferences.getBoolean("enabled", false)
        folder.value = preferences.getString("folder", null)
        worker.scheduleAtFixedRate({
            try { tick() } catch (error: Exception) { fail(error) }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun selectFolder(uri: Uri) {
        preferences.edit().putString("folder", uri.toString()).apply()
        folder.value = uri.toString()
        worker.execute {
            try { finish("folder_changed"); failure = false; tick() } catch (error: Exception) { fail(error) }
        }
    }

    fun setEnabled(on: Boolean) {
        preferences.edit().putBoolean("enabled", on).apply()
        enabled.value = on
        worker.execute {
            try { failure = false; tick() } catch (error: Exception) { fail(error) }
        }
    }

    fun reportSelectionError(error: Throwable) { status.value = "Recording failed: ${error.message}" }

    /** Called after the core is initialized, immediately before asking it to boot. */
    fun gameStarting() { bootPending = true }
    fun gameEnded() {
        bootPending = false
        worker.execute {
            try { finish("game_closed") } catch (error: Exception) { fail(error) }
        }
    }

    private fun fail(error: Exception) {
        failure = true
        val message = error.message ?: error.javaClass.simpleName
        runCatching { session?.event("recording_error", JSONObject().put("message", message)) }
        runCatching { finish("recording_error") }
        status.value = "Recording failed: $message. Re-enable logging or choose another folder to retry."
        android.util.Log.e("PerformanceTelemetry", "Recording stopped", error)
    }

    private fun tick() {
        if (failure) return
        if (!enabled.value) {
            finish("disabled")
            status.value = "Performance logging is off."
            return
        }
        val destination = folder.value
        if (destination == null) {
            status.value = "Choose a recording folder; automatic logging is not active yet."
            return
        }
        if (!ARMSX3.initialized) { status.value = "Ready; waiting for emulator initialization."; return }
        // Do not mutate Compose's emulator-state holder from this worker.
        val state = ARMSX3.instance.getState()
        val active = state != EmulatorState.Stopped.ordinal && state != EmulatorState.Stopping.ordinal
        if (!active && !bootPending) {
            finish("game_stopped")
            status.value = "Ready; the next game will be recorded automatically."
            return
        }
        if (session == null) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(destination))
                ?: error("Recording folder cannot be opened")
            check(root.exists() && root.canWrite()) { "Recording folder access was lost" }
            TelemetryFiles.makeRoom(context, root, null, 1024 * 1024)
            check(ARMSX3.instance.telemetryConfigure(true)) { "This core does not support telemetry version 1" }
            try { session = RecordingSession(context, root) }
            catch (error: Exception) { ARMSX3.instance.telemetryConfigure(false); throw error }
            lastState = -1
        }
        val current = session!!
        if (state != lastState) {
            current.event("emulator_state", JSONObject().put("state", EmulatorState.entries.getOrNull(state)?.name ?: state.toString()))
            current.resetWindow()
            lastState = state
        }
        current.sample(state == EmulatorState.Running.ordinal)
        status.value = "Recording: ${current.name} · ${current.framesRecorded} guest frames · ${current.droppedEvents} dropped events"
    }

    private fun finish(reason: String) {
        val old = session ?: return
        session = null
        try {
            // Drain final events before disabling (configure clears the native queue).
            old.sample(false)
        } finally {
            runCatching { ARMSX3.instance.telemetryConfigure(false) }
            old.finish(reason)
        }
    }
}

private class RecordingSession(private val context: Context, private val root: DocumentFile) {
    val name = "telemetry-v1-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().take(8)
    private val directory = root.createDirectory(name) ?: error("Cannot create session directory")
    private val files = ArrayList<SegmentedFile>()
    private fun output(name: String, header: String = "") = SegmentedFile(context, directory, name, header).also { files += it }
    private val frames = output("frames.csv", "monotonic_ns,interval_ms\n")
    private val samples = output("samples.csv", "utc,monotonic_ns,running,guest_fps,frame_p50_ms,frame_p95_ms,frame_p99_ms,frame_max_ms,cpu_percent,ppu_cpu_share_percent,spu_cpu_share_percent,rsx_cpu_share_percent,ppu_threads,spu_threads,gpu_busy_percent,gpu_mhz,cpu_mhz_json,temperatures_c_json,thermal_status,dropped_events,native_log_backlog_bytes\n")
    private val events = output("events.jsonl")
    private val log = output("session.log")
    private var lastSampleNs = System.nanoTime()
    private var lastSettings = ""
    private var settingsTicks = 0
    private var skipFpsWindow = true
    private var nativeLog: RandomAccessFile? = null
    private var nativeLogPath = ""
    private var nativeLogUnavailable = false
    private var lastLoggedDropped = 0L
    private var nextBudgetCheckNs = 0L
    private val hardware = HardwareSamples()
    var framesRecorded = 0L; private set
    var droppedEvents = 0L; private set

    init {
      try {
        // UTC and monotonic time are recorded together, not inferred from filenames.
        val metadata = JSONObject().put("schema_version", 1).put("recorder", TelemetryFiles.RECORDER)
            .put("started_utc", Instant.now().toString()).put("monotonic_ns", System.nanoTime())
            .put("application_id", context.packageName).put("version", BuildConfig.VERSION_NAME)
            .put("source_revision", BuildConfig.TELEMETRY_REVISION).put("baseline", "b8eea42f52755f0841afc04a23b06125e5274bab")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("game_id", runCatching { ARMSX3.instance.getTitleId() }.getOrDefault(""))
            .put("game_title", runCatching { ARMSX3.instance.getTitle() }.getOrNull())
            .put("system_info", runCatching { ARMSX3.instance.systemInfo() }.getOrDefault("unavailable"))
            .put("metric_definitions", JSONObject()
                .put("guest_frames", "Non-forced guest frame-end hooks; excludes generated/display frames")
                .put("cpu_percent", "Process CPU time divided by elapsed time and online CPU count")
                .put("cpu_shares", "Overlay-compatible estimates: process CPU percent apportioned by sampled PPU/SPU/RSX CPU-time deltas; not independent saturation readings")
                .put("pipeline_duration", "Worker wall time; overlapping calls do not sum to game-stall time")
                .put("long_frame_threshold_ms", 100).put("sample_interval_ms", 1000)
                .put("gpu_execution_time", "unavailable; no blocking GPU queries are added"))
            .put("hardware_sources", hardware.sources())
        TelemetryFiles.writeJson(context, directory, "metadata.json", metadata)
        event("session_started")
        captureSettings()
        flush()
      } catch (error: Exception) {
        files.forEach { runCatching { it.close() } }
        throw error
      }
    }

    fun event(kind: String, detail: JSONObject = JSONObject()) {
        detail.put("kind", kind).put("utc", Instant.now().toString()).put("ts_ns", System.nanoTime())
        events.append(detail.toString() + "\n")
    }

    fun resetWindow() { skipFpsWindow = true }

    private fun captureSettings() {
        val core = ARMSX3.instance.settingsGet("")
        val ui = InGameOverlay.settingsState.value.toJson().toString()
        val combined = core + ui
        if (combined == lastSettings) return
        lastSettings = combined
        val settings = JSONObject().put("utc", Instant.now().toString()).put("monotonic_ns", System.nanoTime())
            .put("core", runCatching { JSONObject(core) }.getOrElse { JSONObject().put("unavailable", true) })
            .put("ui", JSONObject(ui))
        TelemetryFiles.writeJson(context, directory, "settings.json", settings)
        event("settings_snapshot", settings)
    }

    fun sample(running: Boolean) {
        // SAF directory scans are Binder calls. Check every five seconds, reserving
        // enough for bounded drains and at most 1 MB/second of native logs.
        if (System.nanoTime() >= nextBudgetCheckNs) {
            check(root.exists() && root.canWrite()) { "Recording folder access was lost" }
            TelemetryFiles.makeRoom(context, root, directory.uri, 16L * 1024 * 1024)
            nextBudgetCheckNs = System.nanoTime() + 5_000_000_000L
        }
        val snapshot = JSONObject(ARMSX3.instance.telemetrySnapshot())
        check(snapshot.optInt("version") == 1) { "Unsupported native telemetry schema" }
        val native = JSONArray(ARMSX3.instance.telemetryDrain())
        val stats = FrameStatistics()
        for (i in 0 until native.length()) {
            val item = native.getJSONObject(i)
            if (item.getString("kind") == "frame") {
                val interval = item.getLong("duration_ns")
                frames.append("${item.getLong("ts_ns")},${if (interval > 0) interval / 1e6 else ""}\n")
                stats.add(interval)
                framesRecorded++
                if (interval >= 100_000_000L) events.append(item.put("kind", "long_frame").toString() + "\n")
            } else events.append(item.toString() + "\n")
        }
        droppedEvents = snapshot.getLong("dropped_events")
        if (droppedEvents != lastLoggedDropped) {
            event("events_dropped", JSONObject().put("total", droppedEvents))
            lastLoggedDropped = droppedEvents
        }
        val backlog = copyNativeLog(snapshot.optString("native_log_path"))
        val now = snapshot.getLong("ts_ns")
        val hw = hardware.read(context)
        val values: List<Any?> = listOf(Instant.now().toString(), now, running,
            if (running && !skipFpsWindow) stats.fps(now - lastSampleNs) else null,
            stats.percentile(50.0), stats.percentile(95.0), stats.percentile(99.0), stats.percentile(100.0),
            snapshot.opt("cpu_percent"), snapshot.opt("ppu_cpu_share_percent"), snapshot.opt("spu_cpu_share_percent"),
            snapshot.opt("rsx_cpu_share_percent"), snapshot.opt("ppu_threads"), snapshot.opt("spu_threads"),
            hw.opt("gpu_busy_percent"), hw.opt("gpu_mhz"), hw.opt("cpu_mhz"), hw.opt("temperatures_c"),
            hw.opt("thermal_status"), droppedEvents, backlog)
        samples.append(values.joinToString(",") { csv(it) } + "\n")
        lastSampleNs = now
        skipFpsWindow = false
        if (++settingsTicks % 10 == 0) captureSettings()
        flush()
    }

    private fun copyNativeLog(path: String): Long? {
        if (path.isBlank()) return null
        if (nativeLogPath != path) {
            nativeLog?.close(); nativeLog = null; nativeLogPath = path
        }
        if (nativeLog == null) {
            val source = File(path)
            if (!source.isFile || !source.canRead()) {
                if (!nativeLogUnavailable) event("native_log_unavailable")
                nativeLogUnavailable = true
                return null
            }
            nativeLog = RandomAccessFile(source, "r")
            // Copy the initialization tail as context, bounded to 1 MB.
            nativeLog!!.seek((source.length() - 1024 * 1024).coerceAtLeast(0))
        }
        val source = nativeLog!!
        if (source.length() < source.filePointer) { source.seek(0); event("native_log_truncated") }
        val amount = minOf(source.length() - source.filePointer, 1024L * 1024).toInt()
        if (amount > 0) {
            val bytes = ByteArray(amount)
            val count = source.read(bytes)
            if (count > 0) log.append(if (count == bytes.size) bytes else bytes.copyOf(count))
        }
        return (source.length() - source.filePointer).coerceAtLeast(0)
    }

    private fun flush() { files.forEach { it.flush() } }

    fun finish(reason: String) {
        try {
            event("session_finished", JSONObject().put("reason", reason))
            flush()
            TelemetryFiles.writeJson(context, directory, "completed.json", JSONObject()
                .put("ended_utc", Instant.now().toString()).put("reason", reason)
                .put("frames", framesRecorded).put("dropped_events", droppedEvents))
        } finally {
            runCatching { nativeLog?.close() }
            files.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private fun csv(value: Any?): String {
            if (value == null || value == JSONObject.NULL) return ""
            val text = value.toString()
            return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${text.replace("\"", "\"\"") }\"" else text
        }
    }
}

private class HardwareSamples {
    private fun text(file: File): String? = runCatching { file.readText().trim() }.getOrNull()
    private fun number(file: File, divisor: Double): Double? = text(file)?.toDoubleOrNull()?.div(divisor)
    private val cpu = runCatching { File("/sys/devices/system/cpu").listFiles() }.getOrNull().orEmpty()
        .filter { it.name.matches(Regex("cpu[0-9]+")) }
        .sortedBy { it.name.removePrefix("cpu").toInt() }
        .associate { it.name to File(it, "cpufreq/scaling_cur_freq") }
    private val temperatures = runCatching { File("/sys/class/thermal").listFiles() }.getOrNull().orEmpty()
        .filter { it.name.startsWith("thermal_zone") }.mapNotNull { zone ->
            val type = text(File(zone, "type")) ?: return@mapNotNull null
            if (listOf("cpu", "gpu", "battery", "skin").none { type.contains(it, true) }) return@mapNotNull null
            "${zone.name}:$type" to File(zone, "temp")
        }.toMap()
    private val gpu = File("/sys/class/kgsl/kgsl-3d0")
    fun sources() = JSONObject().put("cpu_frequency_khz", JSONObject(cpu.mapValues { it.value.path }))
        .put("temperatures_millidegrees", JSONObject(temperatures.mapValues { it.value.path }))
        .put("gpu_busy", File(gpu, "gpubusy").path).put("gpu_frequency_hz", File(gpu, "devfreq/cur_freq").path)

    fun read(context: Context): JSONObject {
        val busy = text(File(gpu, "gpubusy"))?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
        val busyPercent = if (busy?.size == 2 && busy[1] > 0 && busy[0] in 0..busy[1]) busy[0] * 100.0 / busy[1] else null
        val temp = JSONObject()
        temperatures.forEach { (name, file) ->
            number(file, 1000.0)?.takeIf { it in -20.0..150.0 }?.let { temp.put(name, it) }
        }
        return JSONObject().put("gpu_busy_percent", busyPercent ?: JSONObject.NULL)
            .put("gpu_mhz", number(File(gpu, "devfreq/cur_freq"), 1e6) ?: JSONObject.NULL)
            .put("cpu_mhz", JSONObject(cpu.mapValues { number(it.value, 1000.0) ?: JSONObject.NULL }))
            .put("temperatures_c", temp)
            .put("thermal_status", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
    }
}
