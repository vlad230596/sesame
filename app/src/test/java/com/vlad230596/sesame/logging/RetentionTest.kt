package com.vlad230596.sesame.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RetentionTest {

    private val ms = 1_000_000L

    @Test
    fun decimatorKeepsOneSamplePerInterval() {
        val d = SampleDecimator(SampleDecimator.intervalNanosForHz(5)) // 180 мс
        // 50 Гц на протяжении 10 с → около 5 строк в секунду (допуск 10 % на дрожание)
        val kept = (0 until 500).count { d.accept(it * 20 * ms) }
        assertTrue("kept=$kept", kept in 50..56)
    }

    @Test
    fun decimatorRestartsWhenClockGoesBack() {
        val d = SampleDecimator(200 * ms)
        assertTrue(d.accept(10_000 * ms))
        assertFalse(d.accept(10_050 * ms))
        assertTrue(d.accept(5 * ms)) // перезагрузка: elapsedRealtime с нуля
        assertFalse(d.accept(100 * ms))
    }

    @Test
    fun thinnerKeepsHeaderAndUnparsableRows() {
        val rows = buildString {
            append("elapsed_realtime_nanos,accuracy,x,y,z\n")
            for (i in 0 until 100) append("${i * 20 * ms},3,0.1,0.2,9.8\n")
            append("broken,row\n")
        }
        val out = ByteArrayOutputStream()
        val result = CsvThinner.thinGzip(ByteArrayInputStream(gzip(rows)), out, 200 * ms)
        val lines = GZIPInputStream(ByteArrayInputStream(out.toByteArray())).bufferedReader().readLines()
        assertEquals("elapsed_realtime_nanos,accuracy,x,y,z", lines.first())
        assertEquals(101L, result.rowsIn)
        assertEquals(11L, result.rowsOut) // 0, 200, … 1800 мс + битая строка
        assertEquals("broken,row", lines.last())
    }

    @Test
    fun thinnerSurvivesTruncatedFile() {
        val rows = buildString {
            append("t,a\n")
            // Много строк и шумные значения: обрыв должен прийтись далеко от начала.
            for (i in 0 until 200_000) append("${i * 10 * ms},$i,${i * 7919 % 1000}\n")
        }
        val full = gzip(rows)
        val cut = full.copyOf(full.size * 2 / 3)
        val result = CsvThinner.thinGzip(ByteArrayInputStream(cut), ByteArrayOutputStream(), 200 * ms)
        assertTrue(result.rowsIn > 0)
        assertTrue(result.rowsOut < result.rowsIn)
    }

    @Test
    fun policyThinsOnlyAgedDirs() {
        val today = LocalDate.of(2026, 11, 23)
        val root = GzipCsvWriter.ROOT_RELATIVE_PATH
        val dirs = listOf(
            dir("$root/passive/2026-11-23", "accel.csv.gz" to 50),
            dir("$root/passive/2026-11-22", "accel.csv.gz" to 50),
            dir("$root/passive/2026-11-21", "accel.csv.gz" to 50, "location.csv.gz" to 1),
            dir("$root/passive/2026-11-20", "accel.csv.gz" to 10, RetentionPolicy.THINNED_MARKER to 1),
            dir("$root/sessions/7", "gyro.csv.gz" to 30),
            dir("$root/journal/2026-11-01", "events.csv.gz" to 1),
        )
        val thin = RetentionPolicy.dirsToThin(dirs, today).map { it.path }
        assertEquals(listOf("$root/sessions/7", "$root/passive/2026-11-21"), thin)
        assertNull(RetentionPolicy.thinIntervalNanos("$root/passive/2026-11-21", "location.csv.gz"))
        assertNull(RetentionPolicy.thinIntervalNanos("$root/passive/2026-11-21", "steps.csv.gz"))
    }

    @Test
    fun policyDeletesOldestUntilUnderTarget() {
        val today = LocalDate.of(2026, 11, 23)
        val root = GzipCsvWriter.ROOT_RELATIVE_PATH
        val dirs = listOf(
            dir("$root/passive/2026-11-23", "accel.csv.gz" to 400),
            dir("$root/passive/2026-11-10", "accel.csv.gz" to 300),
            dir("$root/passive/2026-11-01", "accel.csv.gz" to 300),
            dir("$root/journal/2026-11-01", "events.csv.gz" to 100),
        )
        val deleted = RetentionPolicy.dirsToDelete(dirs, today, limitBytes = 1_000).map { it.path }
        // 1100 > 1000 → удаляем до 900: одного старейшего хватает, журнал и сегодня — никогда
        assertEquals(listOf("$root/passive/2026-11-01"), deleted)
        assertTrue(RetentionPolicy.dirsToDelete(dirs, today, limitBytes = 2_000).isEmpty())
    }

    private fun dir(path: String, vararg files: Pair<String, Int>) =
        StoredDir(path, files.map { (name, size) -> StoredFile(path, name, size.toLong(), 0) })

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(text.toByteArray()) } }.toByteArray()
}
