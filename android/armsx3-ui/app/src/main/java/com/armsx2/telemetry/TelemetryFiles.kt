package com.armsx2.telemetry

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream

/** Only directories carrying our marker are eligible for retention cleanup. */
internal object TelemetryFiles {
    const val RECORDER = "armsx3-telemetry-v1"
    const val BUDGET = 512L * 1024 * 1024
    const val SEGMENT = 16L * 1024 * 1024

    fun owned(context: Context, directory: DocumentFile): Boolean = runCatching {
        if (!directory.isDirectory || directory.name?.startsWith("telemetry-v1-") != true) return false
        val marker = directory.findFile("metadata.json") ?: return false
        context.contentResolver.openInputStream(marker.uri)?.bufferedReader()?.use {
            JSONObject(it.readText()).optString("recorder") == RECORDER
        } ?: false
    }.getOrDefault(false)

    fun bytes(directory: DocumentFile): Long = directory.listFiles().sumOf { if (it.isFile) it.length() else 0L }

    fun makeRoom(context: Context, root: DocumentFile, active: Uri?, additional: Long) {
        val sessions = root.listFiles().filter { owned(context, it) }
        var total = sessions.sumOf { bytes(it) }
        if (total + additional <= BUDGET) return
        for (old in sessions.filter { it.uri != active && it.findFile("completed.json") != null }.sortedBy { it.name }) {
            val size = bytes(old)
            if (old.delete()) total -= size
            if (total + additional <= BUDGET) return
        }
        check(total + additional <= BUDGET) { "Telemetry reached its 512 MB budget; free space or select another folder." }
    }

    fun writeJson(context: Context, directory: DocumentFile, name: String, value: JSONObject) {
        val file = directory.findFile(name) ?: directory.createFile("application/json", name)
            ?: error("Cannot create $name")
        context.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter()?.use {
            it.write(value.toString(2)); it.write("\n")
        } ?: error("Cannot write $name")
    }
}

/** A stream stays open for live readers, with a maximum 16 MB per segment. */
internal class SegmentedFile(
    private val context: Context,
    private val directory: DocumentFile,
    private val name: String,
    private val header: String = "",
) : AutoCloseable {
    private var stream: OutputStream? = null
    private var size = 0L
    private var segment = 0
    var written = 0L; private set

    private fun open() {
        val extension = name.substringAfterLast('.', "log")
        val base = name.substringBeforeLast('.')
        val filename = if (segment == 0) name else "$base.${segment.toString().padStart(3, '0')}.$extension"
        val mime = if (extension == "csv") "text/csv" else "text/plain"
        val file = directory.createFile(mime, filename) ?: error("Cannot create $filename")
        stream = BufferedOutputStream(context.contentResolver.openOutputStream(file.uri, "wt")
            ?: error("Cannot open $filename"), 64 * 1024)
        size = 0
        if (header.isNotEmpty()) append(header.toByteArray(Charsets.UTF_8))
    }

    fun append(text: String) = append(text.toByteArray(Charsets.UTF_8))
    fun append(bytes: ByteArray) {
        if (stream == null) open()
        if (size + bytes.size > TelemetryFiles.SEGMENT && size > header.length) {
            stream!!.close(); stream = null; segment++; open()
        }
        stream!!.write(bytes)
        size += bytes.size
        written += bytes.size
    }
    fun flush() { stream?.flush() }
    override fun close() { stream?.close(); stream = null }
}
