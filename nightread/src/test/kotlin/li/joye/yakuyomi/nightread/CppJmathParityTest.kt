package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 夜讀 C++ 數值核心（native/core/jmath）對 Java／Kotlin 的同 JVM 差分：每個函式數百萬組隨機加邊界值（半數、±0、NaN、
 * 次正規數、飽和邊界、位移量…，產生器在 native/test/cases.cpp），結果比原始位元；Kotlin 會丟例外的輸入兩邊都要標成「丟例外」。
 * 參考答案一律用函式庫實際用的寫法（StrictMath、Math.rint、Kotlin roundToInt、BigDecimal…；npSum／npArgsort／eigh2 直接呼叫
 * 函式庫的 Kotlin 版）。只有 NaN 的位元不比（Java 規格不定義運算結果的 NaN 酬載，兩邊都是 NaN 就算相同）。
 *
 * 需要主機原生庫（`native/README.md`）：沒給 `-PnativeLib` 就略過。案例數：`-PnativeCases=<每個函式幾組>`（預設 200 萬）。
 * 給 `-PgoldenOut=<目錄>` 時另外寫 golden 檔，給 qemu-aarch64 上的 arm64 檢查用（native/tools/arm64_qemu.sh）。
 */
class CppJmathParityTest {

    private val casesPerFn: Long = System.getProperty("nightread.nativeCases")?.toLong() ?: 2_000_000L
    private val chunk = 65536
    private val threads = min(8, Runtime.getRuntime().availableProcessors())

    private class Spec(val fn: Int, val name: String, val nIn: Int, val nOut: Int, val outKind: Int)

    /** 參考實作：讀 args[a + k]、寫 out[o + k]（k < nOut）。丟例外由呼叫端記成狀態 1。 */
    private fun interface Ref {
        fun eval(x: LongArray, a: Int, out: LongArray, o: Int)
    }

    private fun d(x: LongArray, i: Int) = Double.fromBits(x[i])
    private fun f(x: LongArray, i: Int) = Float.fromBits(x[i].toInt())
    private fun i(x: LongArray, k: Int) = x[k].toInt()
    private fun fl(v: Float): Long = v.toRawBits().toLong() and 0xffffffffL

    private val refs: Map<String, Ref> = mapOf(
        "sin" to Ref { x, a, o, k -> o[k] = StrictMath.sin(d(x, a)).toRawBits() },
        "cos" to Ref { x, a, o, k -> o[k] = StrictMath.cos(d(x, a)).toRawBits() },
        "atan" to Ref { x, a, o, k -> o[k] = StrictMath.atan(d(x, a)).toRawBits() },
        "atan2" to Ref { x, a, o, k -> o[k] = StrictMath.atan2(d(x, a), d(x, a + 1)).toRawBits() },
        "exp" to Ref { x, a, o, k -> o[k] = StrictMath.exp(d(x, a)).toRawBits() },
        "pow" to Ref { x, a, o, k -> o[k] = StrictMath.pow(d(x, a), d(x, a + 1)).toRawBits() },
        "pow_lut" to Ref { x, a, o, k -> o[k] = StrictMath.pow(d(x, a), d(x, a + 1)).toRawBits() },
        "sqrt" to Ref { x, a, o, k -> o[k] = sqrt(d(x, a)).toRawBits() },
        "floor" to Ref { x, a, o, k -> o[k] = floor(d(x, a)).toRawBits() },
        "ceil" to Ref { x, a, o, k -> o[k] = ceil(d(x, a)).toRawBits() },
        "abs_d" to Ref { x, a, o, k -> o[k] = abs(d(x, a)).toRawBits() },
        "rem_d" to Ref { x, a, o, k -> o[k] = (d(x, a) % d(x, a + 1)).toRawBits() },
        "mod_d" to Ref { x, a, o, k -> o[k] = d(x, a).mod(d(x, a + 1)).toRawBits() },
        "signum_d" to Ref { x, a, o, k -> o[k] = d(x, a).sign.toRawBits() },
        "rint" to Ref { x, a, o, k -> o[k] = Math.rint(d(x, a)).toRawBits() },
        "round_d" to Ref { x, a, o, k -> o[k] = Math.round(d(x, a)) },
        "round_f" to Ref { x, a, o, k -> o[k] = Math.round(f(x, a)).toLong() },
        "roundToInt_d" to Ref { x, a, o, k -> o[k] = d(x, a).roundToInt().toLong() },
        "roundToInt_f" to Ref { x, a, o, k -> o[k] = f(x, a).roundToInt().toLong() },
        "d2i" to Ref { x, a, o, k -> o[k] = d(x, a).toInt().toLong() },
        "d2l" to Ref { x, a, o, k -> o[k] = d(x, a).toLong() },
        "f2i" to Ref { x, a, o, k -> o[k] = f(x, a).toInt().toLong() },
        "f2l" to Ref { x, a, o, k -> o[k] = f(x, a).toLong() },
        "d2f" to Ref { x, a, o, k -> o[k] = fl(d(x, a).toFloat()) },
        "l2d" to Ref { x, a, o, k -> o[k] = x[a].toDouble().toRawBits() },
        "l2f" to Ref { x, a, o, k -> o[k] = fl(x[a].toFloat()) },
        "i2f" to Ref { x, a, o, k -> o[k] = fl(i(x, a).toFloat()) },
        "bd_half_even" to Ref { x, a, o, k ->
            o[k] = BigDecimal(d(x, a)).setScale(i(x, a + 1), RoundingMode.HALF_EVEN).toDouble().toRawBits()
        },
        "div_i" to Ref { x, a, o, k -> o[k] = (i(x, a) / i(x, a + 1)).toLong() },
        "rem_i" to Ref { x, a, o, k -> o[k] = (i(x, a) % i(x, a + 1)).toLong() },
        "floorDiv_i" to Ref { x, a, o, k -> o[k] = Math.floorDiv(i(x, a), i(x, a + 1)).toLong() },
        "floorMod_i" to Ref { x, a, o, k -> o[k] = Math.floorMod(i(x, a), i(x, a + 1)).toLong() },
        "div_l" to Ref { x, a, o, k -> o[k] = x[a] / x[a + 1] },
        "rem_l" to Ref { x, a, o, k -> o[k] = x[a] % x[a + 1] },
        "floorDiv_l" to Ref { x, a, o, k -> o[k] = Math.floorDiv(x[a], x[a + 1]) },
        "floorMod_l" to Ref { x, a, o, k -> o[k] = Math.floorMod(x[a], x[a + 1]) },
        "min_d" to Ref { x, a, o, k -> o[k] = min(d(x, a), d(x, a + 1)).toRawBits() },
        "max_d" to Ref { x, a, o, k -> o[k] = max(d(x, a), d(x, a + 1)).toRawBits() },
        "min_f" to Ref { x, a, o, k -> o[k] = fl(min(f(x, a), f(x, a + 1))) },
        "max_f" to Ref { x, a, o, k -> o[k] = fl(max(f(x, a), f(x, a + 1))) },
        "cmp_d" to Ref { x, a, o, k -> o[k] = d(x, a).compareTo(d(x, a + 1)).toLong() },
        "cmp_f" to Ref { x, a, o, k -> o[k] = f(x, a).compareTo(f(x, a + 1)).toLong() },
        "shl_i" to Ref { x, a, o, k -> o[k] = (i(x, a) shl i(x, a + 1)).toLong() },
        "shr_i" to Ref { x, a, o, k -> o[k] = (i(x, a) shr i(x, a + 1)).toLong() },
        "ushr_i" to Ref { x, a, o, k -> o[k] = (i(x, a) ushr i(x, a + 1)).toLong() },
        "shl_l" to Ref { x, a, o, k -> o[k] = x[a] shl i(x, a + 1) },
        "shr_l" to Ref { x, a, o, k -> o[k] = x[a] shr i(x, a + 1) },
        "ushr_l" to Ref { x, a, o, k -> o[k] = x[a] ushr i(x, a + 1) },
        "coerceIn_i" to Ref { x, a, o, k -> o[k] = i(x, a).coerceIn(i(x, a + 1), i(x, a + 2)).toLong() },
        "coerceIn_d" to Ref { x, a, o, k -> o[k] = d(x, a).coerceIn(d(x, a + 1), d(x, a + 2)).toRawBits() },
        "wmul_i" to Ref { x, a, o, k -> o[k] = (i(x, a) * i(x, a + 1)).toLong() },
        "eigh2" to Ref { x, a, o, k ->
            val r = Cv.eigh2(d(x, a), d(x, a + 1), d(x, a + 2))
            for (j in 0 until 6) o[k + j] = r[j].toRawBits()
        },
    )

    private fun canon(kind: Int, v: Long): Long = when {
        kind == 0 && Double.fromBits(v).isNaN() -> 0x7ff8000000000000L
        kind == 1 && Float.fromBits(v.toInt()).isNaN() -> 0x7fc00000L
        else -> v
    }

    private fun specs(): List<Spec> = (0 until NativeTestBridge.fnCount()).map { fn ->
        val ar = NativeTestBridge.fnArity(fn)
        Spec(fn, NativeTestBridge.fnName(fn), ar ushr 16, (ar ushr 8) and 0xff, ar and 0xff)
    }

    private class ChunkResult(
        val fn: Int, val start: Long, val n: Int, val diffs: Int, val throws: Int, val nanOut: Int,
        val examples: List<String>, val inHash: Long, val outHash: Long,
    )

    private fun runChunk(s: Spec, ref: Ref, start: Long, n: Int): ChunkResult {
        val args = LongArray(n * s.nIn)
        NativeTestBridge.genScalar(s.fn, start, n, args)
        val stride = s.nOut + 1
        val want = LongArray(n * stride)
        val got = LongArray(n * stride)
        for (c in 0 until n) {
            val o = c * stride
            try {
                ref.eval(args, c * s.nIn, want, o)
            } catch (e: ArithmeticException) {
                for (j in 0 until s.nOut) want[o + j] = 0
                want[o + s.nOut] = 1
            } catch (e: IllegalArgumentException) {   // roundToInt(NaN)、BigDecimal(非有限值)（NumberFormatException）
                for (j in 0 until s.nOut) want[o + j] = 0
                want[o + s.nOut] = 1
            }
        }
        NativeTestBridge.evalScalar(s.fn, args, n, got)
        var diffs = 0
        var throws = 0
        var nan = 0
        val ex = ArrayList<String>()
        var hin = NativeGolden.FNV_INIT
        for (v in args) hin = NativeGolden.fnv(hin, v)
        var hout = NativeGolden.FNV_INIT
        for (c in 0 until n) {
            val o = c * stride
            var same = want[o + s.nOut] == got[o + s.nOut]
            if (want[o + s.nOut] == 1L) throws++
            for (j in 0 until s.nOut) {
                val w = canon(s.outKind, want[o + j])
                if ((s.outKind == 0 && w == 0x7ff8000000000000L) || (s.outKind == 1 && w == 0x7fc00000L)) nan++
                if (w != canon(s.outKind, got[o + j])) same = false
                hout = NativeGolden.fnv(hout, w)
            }
            hout = NativeGolden.fnv(hout, want[o + s.nOut])
            if (!same) {
                diffs++
                if (ex.size < 5) {
                    val ins = (0 until s.nIn).joinToString(",") { "0x" + java.lang.Long.toHexString(args[c * s.nIn + it]) }
                    val ws = (0 until stride).joinToString(",") { "0x" + java.lang.Long.toHexString(want[o + it]) }
                    val gs = (0 until stride).joinToString(",") { "0x" + java.lang.Long.toHexString(got[o + it]) }
                    ex.add("${s.name}(#${start + c}: $ins) java=[$ws] c++=[$gs]")
                }
            }
        }
        return ChunkResult(s.fn, start, n, diffs, throws, nan, ex, hin, hout)
    }

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
            val tasks = ArrayList<Callable<ChunkResult>>()
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
