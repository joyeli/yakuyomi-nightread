package li.joye.yakuyomi.nightread

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
 * 夜讀 C++ 數值核心（native/core/jmath、tables）在**同一個行程**裡跟 Java／Kotlin 比原始位元：純量函式（StrictMath 對 fdlibm、捨入、轉型、
 * BigDecimal 五成雙、整數與位移語意）、陣列（npSum、npSumChunked、npArgsort）、常數表（高斯核、textLut、inkLut、lineOffsets）。
 *
 * JUnit（CppJmathParityTest、CppNumericProbeTest）跑在 HotSpot；手機探針（引擎 sandbox）跑在 ART，這才驗得到 ART 的 StrictMath 與
 * BigDecimal.doubleValue（libcore 的實作跟 OpenJDK 不同）。案例產生器在 native/test/cases.cpp，所以需要 [NativeTestBridge]：
 * 產品的 .so 沒有它，呼叫前先問 [available]。
 */
object NightNumericProbe {

    /** 參考實作：讀 x[a + k]、寫 out[o + k]（k < 輸出數）。丟例外由呼叫端記成狀態 1。 */
    fun interface Ref {
        fun eval(x: LongArray, a: Int, out: LongArray, o: Int)
    }

    /** C++ 函式表的一項。[outKind]：0 double、1 float、2 int、3 long。 */
    class Spec(@JvmField val fn: Int, @JvmField val name: String, @JvmField val nIn: Int, @JvmField val nOut: Int, @JvmField val outKind: Int)

    /** 一塊案例的結果。[inHash]／[outHash]＝Java 答案的 FNV（golden 檔用；定義同 native/test/golden_check.cpp）。 */
    class ChunkResult(
        @JvmField val fn: Int, @JvmField val start: Long, @JvmField val n: Int, @JvmField val diffs: Int, @JvmField val throws: Int,
        @JvmField val nanOut: Int, @JvmField val examples: List<String>, @JvmField val inHash: Long, @JvmField val outHash: Long,
    )

    /** 一個函式或一族資料的結果。[note]＝不同時的前幾個例子，或族的補充說明。 */
    class Result(
        @JvmField val name: String, @JvmField val cases: Long, @JvmField val diffs: Long, @JvmField val throws: Long,
        @JvmField val millis: Long, @JvmField val note: String = "",
    )

    const val FNV_INIT: Long = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325

    fun fnv(h: Long, v: Long): Long = (h xor v) * 0x100000001b3L

    /** 這個 .so 有沒有編進測試橋（引擎的 YAKUYOMI_NIGHT_PROBE／本 repo 的 NR_PROBE_JNI，或主機的 libnightread_host.so）。 */
    @JvmStatic
    fun available(): Boolean = try {
        NativeTestBridge.fnCount() > 0
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    private fun d(x: LongArray, i: Int) = Double.fromBits(x[i])
    private fun f(x: LongArray, i: Int) = Float.fromBits(x[i].toInt())
    private fun i(x: LongArray, k: Int) = x[k].toInt()
    private fun fl(v: Float): Long = v.toRawBits().toLong() and 0xffffffffL

    /** 函式名 → 參考答案（函式庫實際用的寫法）。 */
    @JvmField
    val scalarRefs: Map<String, Ref> = mapOf(
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

    /** NaN 一律換成標準位元（Java 規格不定義運算結果的 NaN 酬載，兩邊都是 NaN 就算相同）。 */
    @JvmStatic
    fun canon(kind: Int, v: Long): Long = when {
        kind == 0 && Double.fromBits(v).isNaN() -> 0x7ff8000000000000L
        kind == 1 && Float.fromBits(v.toInt()).isNaN() -> 0x7fc00000L
        else -> v
    }

    @JvmStatic
    fun specs(): List<Spec> = (0 until NativeTestBridge.fnCount()).map { fn ->
        val ar = NativeTestBridge.fnArity(fn)
        Spec(fn, NativeTestBridge.fnName(fn), ar ushr 16, (ar ushr 8) and 0xff, ar and 0xff)
    }

    /** 第 [start] 起 [n] 個案例：C++ 產生引數，Java 與 C++ 各算一次，比位元（Kotlin 丟例外的，C++ 要標成「丟例外」）。 */
    @JvmStatic
    fun runChunk(s: Spec, ref: Ref, start: Long, n: Int): ChunkResult {
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
        var hin = FNV_INIT
        for (v in args) hin = fnv(hin, v)
        var hout = FNV_INIT
        for (c in 0 until n) {
            val o = c * stride
            var same = want[o + s.nOut] == got[o + s.nOut]
            if (want[o + s.nOut] == 1L) throws++
            for (j in 0 until s.nOut) {
                val w = canon(s.outKind, want[o + j])
                if ((s.outKind == 0 && w == 0x7ff8000000000000L) || (s.outKind == 1 && w == 0x7fc00000L)) nan++
                if (w != canon(s.outKind, got[o + j])) same = false
                hout = fnv(hout, w)
            }
            hout = fnv(hout, want[o + s.nOut])
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

    /**
     * 所有純量函式各 [casesPerFn] 組，[threads] 條執行緒；每個函式做完呼叫一次 [onResult]（探針逐項寫紀錄）。
     * C++ 有、參考表沒有的函式（或反過來）丟 [IllegalStateException]：表對不上時不能算通過。
     */
    @JvmStatic
    fun runScalars(casesPerFn: Long, threads: Int, onResult: (Result) -> Unit) {
        val chunk = 65536
        val specs = specs()
        val missing = specs.map { it.name }.filter { it !in scalarRefs }
        check(missing.isEmpty()) { "C++ 有、Kotlin 沒有參考實作的函式：$missing" }
        val unused = scalarRefs.keys - specs.map { it.name }.toSet()
        check(unused.isEmpty()) { "Kotlin 有參考實作、C++ 沒有的函式：$unused" }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (s in specs) {
                val t0 = System.nanoTime()
                val tasks = ArrayList<Callable<ChunkResult>>()
                var start = 0L
                while (start < casesPerFn) {
                    val n = min(chunk.toLong(), casesPerFn - start).toInt()
                    val st = start
                    tasks.add(Callable { runChunk(s, scalarRefs.getValue(s.name), st, n) })
                    start += n
                }
                val rs = pool.invokeAll(tasks).map { it.get() }
                onResult(
                    Result(
                        s.name, rs.sumOf { it.n.toLong() }, rs.sumOf { it.diffs.toLong() }, rs.sumOf { it.throws.toLong() },
                        (System.nanoTime() - t0) / 1_000_000, rs.flatMap { it.examples }.take(5).joinToString(" | "),
                    ),
                )
            }
        } finally {
            pool.shutdown()
        }
    }

    /** npSum、npSumChunked、npArgsort（一般與對手輸入）。[scale]＝案例數比例，JUnit 的 CppJmathParityTest 是 1 倍（40,000／2,000／20,000 個陣列）。 */
    @JvmStatic
    fun runArrays(scale: Double, onResult: (Result) -> Unit) {
        val cNaN = 0
        for ((kind, full) in listOf("npSum" to 40_000L, "npSumChunked" to 2_000L)) {
            val count = (full * scale).toLong().coerceAtLeast(1)
            val chunked = kind == "npSumChunked"
            val t0 = System.nanoTime()
            var diffs = 0L
            var note = ""
            for (idx in 0 until count) {
                val a = NativeTestBridge.genSumArray(chunked, idx)
                val want = if (chunked) BgObjects.npSumChunked(a) else Cv.npSum(a)
                val got = if (chunked) NativeTestBridge.npSumChunked(a) else NativeTestBridge.npSum(a)
                if (canon(cNaN, want.toRawBits()) != canon(cNaN, got.toRawBits())) {
                    if (diffs++ == 0L) note = "#$idx n=${a.size} kotlin=$want c++=$got"
                }
            }
            onResult(Result(kind, count, diffs, 0, (System.nanoTime() - t0) / 1_000_000, note))
        }
        val count = (20_000L * scale).toLong().coerceAtLeast(1)
        val t0 = System.nanoTime()
        var diffs = 0L
        var note = ""
        for (idx in 0 until count) {
            val v = NativeTestBridge.genArgsortArray(idx)
            if (!Cv.npArgsort(v).contentEquals(NativeTestBridge.npArgsort(v))) {
                if (diffs++ == 0L) note = "#$idx n=${v.size}"
            }
        }
        val sizes = listOf(17, 64, 200, 1000, 5000, 20000)
        for (n in sizes) {
            val v = NativeTestBridge.antiqsort(n)
            if (!Cv.npArgsort(v).contentEquals(NativeTestBridge.npArgsort(v))) {
                if (diffs++ == 0L) note = "antiqsort n=$n"
            }
        }
        onResult(Result("npArgsort", count + sizes.size, diffs, 0, (System.nanoTime() - t0) / 1_000_000, note))
    }

    private val gaussianKernelMethod by lazy {
        Cv::class.java.getDeclaredMethod("gaussianKernel", Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }

    /** Cv.gaussianBlur 裡算核長的那兩行（照抄，同 CppTablesParityTest）。 */
    private fun blurKsize(sigma: Double): Int {
        var ks = (sigma * 4.0 * 2.0 + 1.0).roundToInt() or 1
        if (ks < 3) ks = 3
        return ks
    }

    private fun sameBits(a: DoubleArray, b: DoubleArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) return false
        return true
    }

    private fun sameBits(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) return false
        return true
    }

    /**
     * 常數表整表比（同 CppTablesParityTest）：高斯核三族 σ＝0.30..10.00（步長 0.01）、textLut 與 inkLut 全部、lineOffsets 0..100。
     * [lutStride]＝查表的 bg 步長（1＝整表；JUnit 用較大的值省時間）；[threads] 條執行緒分 bg。
     */
    @JvmStatic
    fun runTables(threads: Int, lutStride: Int, onResult: (Result) -> Unit) {
        run {
            val t0 = System.nanoTime()
            var bad = 0L
            var note = ""
            val sigmas = (30..1000).map { it / 100.0 }
            for (s in sigmas) {
                var row = ""
                if (!Cv.gaussW(s).contentEquals(NativeTestBridge.gaussW(s))) row = "gaussW"
                val ks = blurKsize(s)
                if (ks != NativeTestBridge.gaussianBlurKsize(s)) row = "ksize"
                val k = gaussianKernelMethod.invoke(Cv, ks, s) as DoubleArray
                if (!sameBits(k, NativeTestBridge.gaussianKernel(ks, s))) row = "gaussianKernel"
                if (!sameBits(Ring.gaussKernel(s), NativeTestBridge.ringGaussKernel(s))) row = "ringGaussKernel"
                if (row.isNotEmpty() && bad++ == 0L) note = "$row σ=$s"
            }
            onResult(Result("tables.gauss", sigmas.size * 3L, bad, 0, (System.nanoTime() - t0) / 1_000_000, note))
        }
        val pool = Executors.newFixedThreadPool(threads)
        try {
            fun lutRun(name: String, perBg: (Int) -> Pair<Long, Long>) {
                val t0 = System.nanoTime()
                val rs = pool.invokeAll((0..255 step lutStride).map { bg -> Callable { perBg(bg) } }).map { it.get() }
                onResult(Result(name, rs.sumOf { it.first }, rs.sumOf { it.second }, 0, (System.nanoTime() - t0) / 1_000_000))
            }
            lutRun("tables.textLut") { bg ->
                var bad = 0L
                for (ink in 0..255) if (!sameBits(BgObjects.textLut(bg, ink), NativeTestBridge.textLut(bg, ink))) bad++
                256L to bad
            }
            lutRun("tables.inkLut") { bg ->
                var bad = 0L
                for (inv in listOf(false, true)) for (top in 0..255) {
                    if (!sameBits(BgObjects.inkLut(bg, top, inv), NativeTestBridge.inkLut(bg, top, inv))) bad++
                }
                512L to bad
            }
        } finally {
            pool.shutdown()
        }
        run {
            val t0 = System.nanoTime()
            var bad = 0L
            var note = ""
            for (len in 0..100) {
                val want: List<IntArray>? = try {
                    BgObjects.lineOffsets(len)
                } catch (e: IndexOutOfBoundsException) {
                    null
                } catch (e: NegativeArraySizeException) {
                    null
                }
                val got = NativeTestBridge.lineOffsets(len)
                val same = if (want == null) got == null else got != null && got.size == want.size && want.indices.all { want[it].contentEquals(got[it]) }
                if (!same && bad++ == 0L) note = "len=$len"
            }
            onResult(Result("tables.lineOffsets", 101, bad, 0, (System.nanoTime() - t0) / 1_000_000, note))
        }
    }
}
