package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 夜讀 C++ 數值核心（native/core/jmath）對 Java／Kotlin 的同 JVM 差分：每個函式數百萬組隨機加邊界值（半數、±0、NaN、
 * 次正規數、飽和邊界、位移量…，產生器在 native/test/cases.cpp），結果比原始位元；Kotlin 會丟例外的輸入兩邊都要標成「丟例外」。
 * 參考答案一律用函式庫實際用的寫法（表在 [NightNumericProbe.scalarRefs]，手機探針用同一張：StrictMath、Math.rint、Kotlin roundToInt、
 * BigDecimal…；npSum／npArgsort／eigh2 直接呼叫函式庫的 Kotlin 版）。只有 NaN 的位元不比（Java 規格不定義運算結果的 NaN 酬載，兩邊都是 NaN 就算相同）。
 *
 * 需要主機原生庫（`native/README.md`）：沒給 `-PnativeLib` 就略過。案例數：`-PnativeCases=<每個函式幾組>`（預設 200 萬）。
 * 給 `-PgoldenOut=<目錄>` 時另外寫 golden 檔，給 qemu-aarch64 上的 arm64 檢查用（native/tools/arm64_qemu.sh）。
 */
class CppJmathParityTest {

    private val casesPerFn: Long = System.getProperty("nightread.nativeCases")?.toLong() ?: 2_000_000L
    private val chunk = 65536
    private val threads = min(8, Runtime.getRuntime().availableProcessors())

    private val refs = NightNumericProbe.scalarRefs

    private fun canon(kind: Int, v: Long): Long = NightNumericProbe.canon(kind, v)

    private fun specs(): List<NightNumericProbe.Spec> = NightNumericProbe.specs()

    private fun runChunk(s: NightNumericProbe.Spec, ref: NightNumericProbe.Ref, start: Long, n: Int): NightNumericProbe.ChunkResult =
        NightNumericProbe.runChunk(s, ref, start, n)

    @Test
    fun scalarFunctionsMatchJavaBitForBit() {
        NativeLib.assumeLoaded()
        val specs = specs()
        val missing = specs.map { it.name }.filter { it !in refs }
        assertTrue("C++ 有、Kotlin 沒有參考實作的函式：$missing", missing.isEmpty())
        val unused = refs.keys - specs.map { it.name }.toSet()
        assertTrue("Kotlin 有參考實作、C++ 沒有的函式：$unused", unused.isEmpty())

        val pool = Executors.newFixedThreadPool(threads)
        val t0 = System.nanoTime()
        val results = try {
            val tasks = ArrayList<Callable<NightNumericProbe.ChunkResult>>()
            for (s in specs) {
                var start = 0L
                while (start < casesPerFn) {
                    val n = min(chunk.toLong(), casesPerFn - start).toInt()
                    val st = start
                    tasks.add(Callable { runChunk(s, refs.getValue(s.name), st, n) })
                    start += n
                }
            }
            pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
        }
        val secs = (System.nanoTime() - t0) / 1e9

        var total = 0L
        var totalDiffs = 0L
        val sb = StringBuilder()
        for (s in specs) {
            val rs = results.filter { it.fn == s.fn }
            val n = rs.sumOf { it.n.toLong() }
            val df = rs.sumOf { it.diffs.toLong() }
            total += n
            totalDiffs += df
            sb.append(String.format("  %-14s cases=%,d diffs=%d throws=%,d nanOut=%,d%n", s.name, n, df,
                rs.sumOf { it.throws.toLong() }, rs.sumOf { it.nanOut.toLong() }))
            rs.flatMap { it.examples }.take(5).forEach { sb.append("    ").append(it).append('\n') }
        }
        println("CppJmathParityTest 純量：${specs.size} 個函式、共 ${"%,d".format(total)} 組、差異 $totalDiffs（${"%.1f".format(secs)} s）\n$sb")

        NativeGolden.open("jmath_scalar")?.use { w ->
            w.println("# CppJmathParityTest：Java 答案的分塊雜湊（cases/fn=$casesPerFn, chunk=$chunk）")
            for (r in results.sortedWith(compareBy({ it.fn }, { it.start }))) {
                w.println("S ${specs[r.fn].name} ${r.start} ${r.n} ${NativeGolden.hex(r.inHash)} ${NativeGolden.hex(r.outHash)}")
            }
        }
        assertEquals("C++ 與 Java 不同的案例數", 0L, totalDiffs)
    }

    @Test
    fun npSumMatchesKotlin() {
        NativeLib.assumeLoaded()
        val golden = NativeGolden.open("jmath_arrays")
        var elems = 0L
        for ((kind, count) in listOf("sum" to 40_000L, "sumc" to 2_000L)) {
            val chunked = kind == "sumc"
            var diffs = 0
            var idx0 = 0L
            while (idx0 < count) {
                val cnt = min(1000L, count - idx0)
                var hin = NativeGolden.FNV_INIT
                var hout = NativeGolden.FNV_INIT
                for (idx in idx0 until idx0 + cnt) {
                    val a = NativeTestBridge.genSumArray(chunked, idx)
                    elems += a.size
                    val want = if (chunked) BgObjects.npSumChunked(a) else Cv.npSum(a)
                    val got = if (chunked) NativeTestBridge.npSumChunked(a) else NativeTestBridge.npSum(a)
                    if (canon(0, want.toRawBits()) != canon(0, got.toRawBits())) {
                        if (diffs++ < 5) println("  $kind #$idx n=${a.size}: kotlin=$want c++=$got")
                    }
                    hin = NativeGolden.fnv(hin, a.size.toLong())
                    for (v in a) hin = NativeGolden.fnv(hin, v.toRawBits())
                    hout = NativeGolden.fnv(hout, canon(0, want.toRawBits()))
                }
                golden?.println("A $kind $idx0 $cnt ${NativeGolden.hex(hin)} ${NativeGolden.hex(hout)}")
                idx0 += cnt
            }
            println("CppJmathParityTest $kind：$count 個陣列、差異 $diffs")
            assertEquals("$kind 不同的陣列數", 0, diffs)
        }

        var diffs = 0
        val count = 20_000L
        var idx0 = 0L
        while (idx0 < count) {
            val cnt = min(1000L, count - idx0)
            var hin = NativeGolden.FNV_INIT
            var hout = NativeGolden.FNV_INIT
            for (idx in idx0 until idx0 + cnt) {
                val v = NativeTestBridge.genArgsortArray(idx)
                elems += v.size
                val want = Cv.npArgsort(v)
                val got = NativeTestBridge.npArgsort(v)
                if (!want.contentEquals(got)) {
                    if (diffs++ < 5) println("  argsort #$idx n=${v.size} 不同")
                }
                hin = NativeGolden.fnv(hin, v.size.toLong())
                for (x in v) hin = NativeGolden.fnv(hin, x.toLong())
                hout = NativeGolden.fnv(hout, want.size.toLong())
                for (x in want) hout = NativeGolden.fnv(hout, x.toLong())
            }
            golden?.println("A argsort $idx0 $cnt ${NativeGolden.hex(hin)} ${NativeGolden.hex(hout)}")
            idx0 += cnt
        }
        golden?.close()
        println("CppJmathParityTest argsort：$count 個陣列、差異 $diffs；三項陣列共 ${"%,d".format(elems)} 個元素")
        assertEquals("npArgsort 不同的陣列數", 0, diffs)
    }

    /** McIlroy 對手產生的輸入（逼 numpy introsort 進 heapsort 的那條路）：Kotlin 與 C++ 的排序結果相同。 */
    @Test
    fun npArgsortAdversarialInputsMatch() {
        NativeLib.assumeLoaded()
        for (n in listOf(17, 64, 200, 1000, 5000, 20000)) {
            val v = NativeTestBridge.antiqsort(n)
            assertTrue("antiqsort($n) 的結果要是 Kotlin 與 C++ 相同的排序", Cv.npArgsort(v).contentEquals(NativeTestBridge.npArgsort(v)))
        }
    }
}
