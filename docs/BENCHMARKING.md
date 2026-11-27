# Measure Shelf on Android

Use a physical Android 10+ device and a representative EPUB folder. For results
you can compare over time, use the same device, library, screen settings, and
benchmark build type on each run.

1. Build and install the isolated, profileable Shelf build.

   ```powershell
   .\gradlew.bat -PincludeBenchmark=true :app:installBenchmark
   ```

2. Open **Shelf** with package `com.shelf.reader.benchmark`. Add the EPUB folder,
   wait for indexing, and leave **All Books** selected. Use enough books to fill
   several screens for the scrolling test. The benchmark has a separate library.
3. Run the benchmark suite.

   ```powershell
   .\gradlew.bat -PincludeBenchmark=true :benchmark:connectedBenchmarkAndroidTest
   ```

The tests measure cold startup through the populated library and frame timing
while scrolling. The first-chapter test logs the median from tapping the reading
button to the WebView's visual-state callback under `ShelfBenchmark` in Logcat.
Macrobenchmark also writes JSON measurements and Perfetto traces below
`benchmark/build/outputs`. Treat emulator runs as checks of the test paths.

[Android Macrobenchmark documentation](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
