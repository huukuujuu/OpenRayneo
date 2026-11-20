// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The app log on disk, so a whole device test survives app restarts and can be shared or fetched by the PC.
 * Two files of at most 1 MB each (current + previous). Writes, rotation and reads all run on one thread.
 * They live in the app's own external folder so a USB-connected PC can pull them without a share step:
 * `adb pull /sdcard/Android/data/com.openrayneo.android/files/logs` (other apps cannot read it; removed on uninstall).
 */
internal class AppLogFile(context: Context) {
    private val folder = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }
    private val current = File(folder, "app.log")
    private val previous = File(folder, "app.log.1")
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "app-log").apply { isDaemon = true } }

    /** Any thread; silently dropped once the service has shut the log down. */
    fun append(text: String) {
        val line = "${LocalDateTime.now().withNano(0)} $text\n"
        runCatching {
            writer.execute {
                runCatching {
                    if (current.length() > MAX_BYTES) { previous.delete(); current.renameTo(previous) }
                    current.appendText(line)
                }
            }
        }
    }

    /** The newest [maxBytes] of the log (previous file first), starting at a line. Blocks; never call on main. */
    fun tail(maxBytes: Int): String = writer.submit(Callable {
        var left = maxBytes.toLong()
        val parts = ArrayDeque<ByteArray>()
        for (file in listOf(current, previous)) {
            if (left <= 0 || !file.exists()) continue
            RandomAccessFile(file, "r").use { input ->
                val size = minOf(left, input.length())
                input.seek(input.length() - size)
                parts.addFirst(ByteArray(size.toInt()).also { input.readFully(it) })
                left -= size
            }
        }
        val first = parts.firstOrNull() ?: return@Callable ""
        // Dropped the start of a line only if we stopped reading mid-file.
        if (left <= 0) parts[0] = first.indexOf('\n'.code.toByte()).let { if (it >= 0) first.copyOfRange(it + 1, first.size) else first }
        parts.joinToString("") { String(it, Charsets.UTF_8) }
    }).get()

    fun close() = writer.shutdown()

    companion object { const val MAX_BYTES = 1_000_000 }
}
