package com.shelf.reader.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldLibraryLaunch() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.res("shelf-book")), WAIT)) { SAMPLE_REQUIRED }
    }

    @Test fun libraryScrolling() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = { pressHome(); startActivityAndWait() },
    ) {
        check(device.wait(Until.hasObject(By.res("shelf-book")), WAIT)) { SAMPLE_REQUIRED }
        val grid = device.wait(Until.findObject(By.res("shelf-library-grid")), WAIT) ?: error(SAMPLE_REQUIRED)
        val bounds = grid.visibleBounds
        val x = bounds.centerX()
        val inset = bounds.height() / 4
        val top = bounds.top + inset
        val bottom = bounds.bottom - inset
        repeat(3) { check(device.swipe(x, bottom, x, top, 15)) }
        repeat(3) { check(device.swipe(x, top, x, bottom, 15)) }
        device.waitForIdle()
    }

    @Test fun firstReadableChapter() {
        val times = mutableListOf<Long>()
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                device.findObject(By.desc("Back to the library"))?.click()
                val book = device.wait(Until.findObject(By.res("shelf-book")), WAIT) ?: error(SAMPLE_REQUIRED)
                book.click()
                check(device.wait(Until.hasObject(By.desc("Read this book")), WAIT)) { "Book overview did not open" }
            },
        ) {
            val open = device.findObject(By.desc("Read this book")) ?: error("Reading button is missing")
            val start = SystemClock.elapsedRealtime()
            open.click()
            check(device.wait(Until.hasObject(By.res("shelf-chapter-ready")), WAIT)) { "Chapter did not render" }
            times += SystemClock.elapsedRealtime() - start
            device.pressBack()
        }
        Log.i("ShelfBenchmark", "First readable chapter median=${times.sorted()[times.size / 2]} ms; samples=$times")
    }

    private companion object {
        const val PACKAGE = "com.shelf.reader.benchmark"
        const val WAIT = 15_000L
        const val SAMPLE_REQUIRED = "Seed All Books with an EPUB folder in the benchmark build before running this test."
    }
}
