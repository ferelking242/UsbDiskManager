package com.usbdiskmanager.usb.impl

import com.usbdiskmanager.core.model.BenchmarkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * Manages disk read/write benchmark operations.
 * Uses sequential I/O to measure real-world disk performance.
 */
@Singleton
class UsbBenchmarkManager @Inject constructor() {

    companion object {
        private const val TEST_FILE_SIZE_MB = 32
        private const val BUFFER_SIZE = 4 * 1024 * 1024 // 4MB buffer
        private const val TEST_FILE_NAME = ".benchmark_test_usb.tmp"
    }

    /**
     * Run a full benchmark (write then read) on the given mount point.
     * Emits progress messages and finally the result.
     */
    fun runBenchmark(
        deviceId: String,
        mountPoint: String
    ): Flow<Pair<String, BenchmarkResult?>> = flow {
        val testFile = File(mountPoint, TEST_FILE_NAME)

        try {
            emit(Pair("Preparing benchmark (${TEST_FILE_SIZE_MB}MB test)...", null))

            // ── Write test ──────────────────────────────────────────────────
            emit(Pair("Running write speed test...", null))
            val writeSpeedMBps = withContext(Dispatchers.IO) {
                val data = ByteArray(BUFFER_SIZE) { it.toByte() }
                val totalBytes = TEST_FILE_SIZE_MB.toLong() * 1024 * 1024
                var written = 0L

                val durationMs = measureTimeMillis {
                    RandomAccessFile(testFile, "rwd").use { raf ->
                        while (written < totalBytes) {
                            val toWrite = minOf(BUFFER_SIZE.toLong(), totalBytes - written).toInt()
                            raf.write(data, 0, toWrite)
                            written += toWrite
                        }
                        raf.channel.force(true) // Flush to disk
                    }
                }
                Timber.d("Write: ${written / (1024 * 1024)}MB in ${durationMs}ms")
                if (durationMs > 0) (written.toDouble() / (1024 * 1024)) / (durationMs.toDouble() / 1000)
                else 0.0
            }

            emit(Pair("Write: %.1f MB/s ✓  Running read test...".format(writeSpeedMBps), null))

            // ── Read test ───────────────────────────────────────────────────
            val readSpeedMBps = withContext(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = 0L

                val durationMs = measureTimeMillis {
                    RandomAccessFile(testFile, "r").use { raf ->
                        var read: Int
                        while (raf.read(buffer).also { read = it } != -1) {
                            totalRead += read
                        }
                    }
                }
                Timber.d("Read: ${totalRead / (1024 * 1024)}MB in ${durationMs}ms")
                if (durationMs > 0) (totalRead.toDouble() / (1024 * 1024)) / (durationMs.toDouble() / 1000)
                else 0.0
            }

            val result = BenchmarkResult(
                deviceId = deviceId,
                readSpeedMBps = readSpeedMBps,
                writeSpeedMBps = writeSpeedMBps,
                testFileSizeMB = TEST_FILE_SIZE_MB,
                durationMs = 0L
            )

            emit(Pair("Benchmark complete!", result))

        } catch (e: Exception) {
            Timber.e(e, "Benchmark failed")
            emit(Pair("Benchmark failed: ${e.message}", null))
        } finally {
            withContext(Dispatchers.IO) {
                testFile.delete()
            }
        }
    }
}
