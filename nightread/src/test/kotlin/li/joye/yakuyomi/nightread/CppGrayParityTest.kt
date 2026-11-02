package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.SplittableRandom

/**
 * 第 3b 階 C++ 影像原語（[NightNativeGray]）對 Kotlin 版（[Cv]）逐位元比，同一個 JVM。Kotlin 的每一次呼叫（含巢狀：blackhat 裡的
 * morphGrayGather、gaussQ16Sparse 裡每一列的 medianRows…）都由 [CvCallReplay] 當場用 C++ 重算、比位元（逐點的 gaussAt、medianRows 併批）。
 *  - 寬 1、2、63、64、65、127、128、129、200 × 高 1、2、3、7、64、65：灰階形態學（cv2 版與不限 8 位元版）、blackhat、uint8 中值、Canny、
 *    可分離濾波（σ 0–10、方框 0–15，0／1、整數、亂數浮點、負零）、單點與取樣版整數高斯（灰階與中值兩種來源、各種 need 密度與列範圍）；
 *  - 繪圖：粗細 −1–17、端點在影像外與遠在外（clipLine）、多邊形頂點在外、退化（重合點、水平邊）；
 *  - numpy 相容：最大值濾波（nt 大於列數、nr 0）、argsort（重複值、逼 heapsort 的長度）、npSum（off／n）、eigh2（0、相等、極值）、gaussW；
 *  - 0 寬／0 高與 Kotlin 會丟例外的輸入：C++ 也要丟；
 *  - FMA：Math.fma 版的 Kotlin 可分離濾波跟原版不同的輸出有多少（證明測試抓得到合成的 FMA）；[NightGrayWorkload] 的 fmaSensitive 標記逐步核對；
 *  - 步驟表（手機探針用）在 10 張 fixture 頁上全部相同、並行。
 * 需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppGrayParityTest {

    private val edgeWidths = listOf(1, 2, 63, 64, 65, 127, 128, 129, 200)
    private val heights = listOf(1, 2, 3, 7, 64, 65)

    private fun grays(w: Int, h: Int, rnd: SplittableRandom): List<Pair<String, Gray>> {
        val n = w * h
        return listOf(
            "隨機" to Gray(w, h, IntArray(n) { rnd.nextInt(256) }),
            "0" to Gray(w, h),
            "255" to Gray(w, h, IntArray(n) { 255 }),
            "漸層" to Gray(w, h, IntArray(n) { ((it % w) * 255 / maxOf(1, w - 1) + (it / w) * 3) and 0xff }),
            "椒鹽" to Gray(w, h, IntArray(n) { if (rnd.nextInt(5) == 0) (if (rnd.nextBoolean()) 0 else 255) else 200 }),
            "格線" to Gray(w, h, IntArray(n) { if ((it % w + it / w) % 9 < 2) 20 else 240 }),
        )
    }

    private fun kernels(rnd: SplittableRandom): List<Kernel> {
        val out = ArrayList<Kernel>()
        for (s in listOf(1, 3, 7, 11)) out += Cv.ellipse(s)
        for ((w, h) in listOf(1 to 1, 3 to 1, 1 to 5, 5 to 3, 65 to 1, 2 to 4)) out += Cv.rect(w, h)
        out += Kernel(9, 3, BooleanArray(27) { it % 9 >= 6 })
        out += Kernel(9, 3, BooleanArray(27) { it % 9 <= 2 })
        out += Kernel(141, 1, BooleanArray(141) { it >= 135 })
        out += Kernel(5, 5, BooleanArray(25))
        out += Kernel(7, 5, BooleanArray(35) { val x = it % 7; val y = it / 7; if (y == 0) x <= 2 else if (y == 4) x >= 4 else y == 2 })
        repeat(2) {
            val kw = 1 + rnd.nextInt(9)
            val kh = 1 + rnd.nextInt(9)
            out += Kernel(kw, kh, BooleanArray(kw * kh) { rnd.nextInt(3) == 0 })
        }
        return out
    }

    private fun assertClean(stats: CvCallReplay.Stats, what: String) {
        if (stats.failed > 0) fail("$what：${stats.failed} 個不同，例如\n" + stats.failures.take(10).joinToString("\n"))
    }

    private fun requireOps(stats: CvCallReplay.Stats, vararg ops: String) {
        for (op in ops) assertTrue("$op 要真的比到", (stats.counts[op]?.get() ?: 0L) > 0)
    }

    /** Kotlin 丟不丟例外，C++ 也要一樣；都沒丟就比結果。 */
    private fun both(what: String, kotlin: () -> Any?, native: () -> Any?) {
        val want = runCatching(kotlin)
        val got = runCatching(native)
        assertEquals("$what：丟不丟例外（Kotlin ${want.exceptionOrNull()}、C++ ${got.exceptionOrNull()}）", want.isFailure, got.isFailure)
        if (want.isSuccess && !CvCallReplay.same(CvCallReplay.normalize(want.getOrNull()), got.getOrNull())) fail("$what：C++ 與 Kotlin 不同")
    }

    private fun sparse(w: Int, h: Int, k: IntArray, need: LongArray, ny0: Int, nrows: Int, src: Cv.RowSrc): IntArray {
        val rec = Cv.SparseRec()
        Cv.gaussQ16Sparse(w, h, k, need, ny0, nrows, src) { y, v, rs, re, n -> rec.add(y, v, rs, re, n) }
        return rec.toArray()
    }

    @Test
    fun grayMorphologyMedianCanny() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(20261103L)
        val ks = kernels(rnd)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "gray") {
            for (w in edgeWidths) for (h in heights) for ((_, g) in grays(w, h, rnd)) {
                for (k in ks) {
                    Cv.dilateGray(g, k)
                    Cv.erodeGray(g, k)
                    Cv.blackhat(g, k)
                }
                // 不限 8 位元（Q16、負值、任意 Int）：I 版與 blackhat 的 cv2 路徑
                val wide = Gray(w, h, IntArray(w * h) { if (it % 3 == 0) rnd.nextInt() else g.data[it] * 65536 - 40000 })
                for (k in ks.take(6)) {
                    Cv.dilateGrayI(wide, k)
                    Cv.erodeGrayI(wide, k)
                    Cv.blackhat(wide, k)
                }
                for (ks8 in listOf(1, 3, 5, 7)) Cv.medianBlur8(g, ks8)
                for ((lo, hi) in listOf(15 to 40, 0 to 1, 50 to 150)) Cv.canny(g.data, w, h, 0, lo, hi)
                val q = Cv.gaussQ16(g, 2.0)
                Cv.canny(q.data, w, h, 16, 15, 40)
                Cv.canny(wide.data, w, h, 7, 100, 3000)
            }
        }
        assertClean(stats, "灰階形態學、中值、Canny")
        println("CppGrayParityTest 灰階：${stats.total} 次呼叫相同（${stats.summary()}）")
        requireOps(stats, "dilateGray", "erodeGray", "dilateGrayI", "erodeGrayI", "blackhat", "morphGrayGather", "medianBlur8", "canny")
    }

    @Test
    fun separableFilters() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(7L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "filter") {
            for (w in edgeWidths) for (h in heights) {
                val n = w * h
                val inputs = listOf(
                    FImg(w, h, FloatArray(n) { if (rnd.nextInt(3) == 0) 1f else 0f }),
                    FImg(w, h, FloatArray(n) { rnd.nextInt(256).toFloat() }),
                    FImg(w, h, FloatArray(n) { if (rnd.nextInt(9) == 0) -0.0f else (rnd.nextDouble() * 300 - 20).toFloat() }),
                )
                for (f in inputs) {
                    for (s in listOf(0.0, -1.0, 0.3, 0.7, 1.0, 2.0, 4.0, 10.0)) Cv.gaussianBlur(f, s)
                    for (k in listOf(0, 1, 2, 3, 15, 40)) Cv.boxBlur(f, k)
                }
            }
        }
        assertClean(stats, "可分離濾波")
        println("CppGrayParityTest 濾波：${stats.total} 次呼叫相同（${stats.summary()}）")
        requireOps(stats, "gaussianBlur", "boxBlur")
    }

    @Test
    fun sparseGaussAndMedianRows() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(11L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "sparse") {
            for (w in edgeWidths) for (h in heights) for ((_, g) in grays(w, h, rnd).take(4)) {
                for (sigma in listOf(0.3, 1.0, 2.5, 3.0)) {
                    val k = Cv.gaussW(sigma)
                    // 單點：小頁每一點，大頁 300 點
                    val np = minOf(w * h, 300)
                    for (i in 0 until np) {
                        val x = if (w * h <= 300) i % w else rnd.nextInt(w)
                        val y = if (w * h <= 300) i / w else rnd.nextInt(h)
                        Cv.gaussAt(g, k, x, y)
                    }
                    for (dens in listOf(0.0, 0.05, 0.5, 1.0)) for ((ny0, nrows) in listOf(0 to h, h / 2 to maxOf(1, h - h / 2), h - 1 to 1)) {
                        val nm = Mask(w, nrows, BooleanArray(w * nrows) { rnd.nextDouble() < dens })
                        val need = Cv.packBits(nm)
                        sparse(w, h, k, need, ny0, nrows, Cv.grayRows(g))
                        sparse(w, h, k, need, ny0, nrows, Cv.MedianRows(g, if (sigma < 1.5) 3 else 7))
                    }
                }
            }
        }
        assertClean(stats, "整數高斯（單點、取樣版）與中值來源")
        println("CppGrayParityTest 取樣：${stats.total} 次呼叫相同（${stats.summary()}）")
        requireOps(stats, "gaussAt", "gaussQ16Sparse", "medianRows", "gaussW", "packBits")
    }

    @Test
    fun drawing() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(1702L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "draw") {
            for (w in edgeWidths + 1351) for (h in heights + 37) {
                val m = Mask(w, h)
                repeat(12) { t ->
                    val th = listOf(-1, 0, 1, 2, 3, 7, 17)[t % 7]
                    // 很遠的端點只給細線：粗線的 FillConvexPoly 從 ymin 逐列走到 ymax（頁外的列也走），兩邊都慢
                    val far = when (t % 4) { 0 -> 1; 1 -> 3; 2 -> 100; else -> if (th <= 1) 1 shl 20 else 100 }
                    val x0 = rnd.nextInt(-w * far - 2, w * far + 3)
                    val y0 = rnd.nextInt(-h * far - 2, h * far + 3)
                    val x1 = if (t == 5) x0 else rnd.nextInt(-w * far - 2, w * far + 3)
                    val y1 = if (t == 5) y0 else rnd.nextInt(-h * far - 2, h * far + 3)
                    Cv.line(m, x0, y0, x1, y1, th)
                }
                repeat(6) { t ->
                    val n = 1 + rnd.nextInt(8)
                    val xs = IntArray(n) { rnd.nextInt(-w, 2 * w + 1) }
                    val ys = IntArray(n) { if (t == 2) 3 else rnd.nextInt(-h, 2 * h + 1) }   // t＝2：全部同一列（水平邊）
                    Cv.fillPoly(m, xs, ys)
                }
                Cv.fillPoly(m, intArrayOf(0, w - 1, w - 1, 0), intArrayOf(0, 0, h - 1, h - 1))
            }
        }
        assertClean(stats, "繪圖")
        println("CppGrayParityTest 繪圖：${stats.total} 次呼叫相同（${stats.summary()}）")
        requireOps(stats, "line", "fillPoly")
    }

    @Test
    fun numpyCompat() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(360L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "numpy") {
            for ((rows, cols) in listOf(1 to 1, 1 to 9, 9 to 1, 2 to 63, 7 to 64, 3 to 65, 5 to 127, 4 to 128, 6 to 129, 360 to 41)) {
                for ((nt, nr) in listOf(0 to 0, 1 to 2, 3 to 6, 20 to 1, 0 to 70)) {
                    Cv.maxFilterWrapRows(IntArray(rows * cols) { if (rnd.nextInt(4) == 0) rnd.nextInt(1000) else 0 }, rows, cols, nt, nr)
                }
            }
            for (t in 0 until 300) {
                val n = if (t < 40) t else rnd.nextInt(1, 6000)
                Cv.npArgsort(IntArray(n) { if (t % 3 == 0) rnd.nextInt(5) else rnd.nextInt() })
                val dv = DoubleArray(n) { (rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(-5, 6).toDouble()) }
                Cv.npSum(dv)
                if (n > 2) Cv.npSum(dv, 1, n - 2)
                Cv.npSum(dv, n, 0)
            }
            val special = doubleArrayOf(0.0, -0.0, 1.0, -1.0, 1e-300, 1e300, 4.9e-324, Double.MIN_VALUE, 3.0, 1e-16)
            for (a in special) for (b in special) for (c in special) Cv.eigh2(a, b, c)
            repeat(2000) { Cv.eigh2((rnd.nextDouble() - 0.5) * 1e4, (rnd.nextDouble() - 0.5) * 1e4, (rnd.nextDouble() - 0.5) * 1e4) }
            for (s in listOf(0.3, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 10.0)) Cv.gaussW(s)
        }
        assertClean(stats, "numpy 相容")
        println("CppGrayParityTest numpy：${stats.total} 次呼叫相同（${stats.summary()}）")
        requireOps(stats, "maxFilterWrapRows", "npArgsort", "npSum", "eigh2", "gaussW")
    }

    /** 0 寬／0 高與 Kotlin 會丟例外的輸入：C++ 丟不丟要一樣，沒丟就比值。 */
    @Test
    fun emptySizesAndThrows() {
        NativeLib.assumeLoaded()
        val N = NightNativeGray
        val ks = kernels(SplittableRandom(5L))
        for ((w, h) in listOf(0 to 0, 0 to 5, 5 to 0, 0 to 1, 1 to 0)) {
            val g = Gray(w, h)
            val f = FImg(w, h)
            val m = Mask(w, h)
            val tag = "${w}x$h"
            for (k in ks) {
                both("dilateGray $tag ${k.w}x${k.h}", { Cv.dilateGray(g, k) }, { N.dilateGray(g, k).value })
                both("erodeGrayI $tag ${k.w}x${k.h}", { Cv.erodeGrayI(g, k) }, { N.erodeGrayI(g, k).value })
                both("blackhat $tag ${k.w}x${k.h}", { Cv.blackhat(g, k) }, { N.blackhat(g, k).value })
            }
            for (ksz in listOf(-1, 0, 1, 3, 4)) both("medianBlur8 $tag / $ksz", { Cv.medianBlur8(g, ksz) }, { N.medianBlur8(g, ksz).value })
            both("canny $tag", { Cv.canny(g.data, w, h, 0, 10, 20) }, { N.canny(g.data, w, h, 0, 10, 20).value })
            for (s in listOf(0.0, 0.7, 3.0)) both("gaussianBlur $tag / $s", { Cv.gaussianBlur(f, s) }, { N.gaussianBlur(f, s).value })
            for (k in listOf(0, 1, 3)) both("boxBlur $tag / $k", { Cv.boxBlur(f, k) }, { N.boxBlur(f, k).value })
            both("line $tag", { m.copy().also { Cv.line(it, -3, -3, 9, 9, 5) } }, { N.line(m, -3, -3, 9, 9, 5).value })
            both("line $tag 細", { m.copy().also { Cv.line(it, 0, 0, 3, 3, 1) } }, { N.line(m, 0, 0, 3, 3, 1).value })
            both("fillPoly $tag", { m.copy().also { Cv.fillPoly(it, intArrayOf(-2, 5, 3), intArrayOf(-1, 2, 9)) } }, { N.fillPoly(m, intArrayOf(-2, 5, 3), intArrayOf(-1, 2, 9)).value })
            both("gaussQ16Sparse $tag", { sparse(w, h, Cv.gaussW(1.0), LongArray(((w + 63) ushr 6) * h) { -1L }, 0, h, Cv.grayRows(g)) },
                { N.gaussQ16Sparse(w, h, Cv.gaussW(1.0), LongArray(((w + 63) ushr 6) * h) { -1L }, 0, h, g, 0).value })
        }
        // 非空影像上 Kotlin 會丟的
        val rnd = SplittableRandom(9L)
        val g = Gray(37, 41, IntArray(37 * 41) { rnd.nextInt(256) })
        val f = g.toF()
        val k1 = Cv.gaussW(1.0)
        both("boxBlur -1", { Cv.boxBlur(f, -1) }, { N.boxBlur(f, -1).value })
        both("gaussianBlur NaN", { Cv.gaussianBlur(f, Double.NaN) }, { N.gaussianBlur(f, Double.NaN).value })
        for (ksz in listOf(-1, 0, 2, 4, 6)) both("medianBlur8 / $ksz", { Cv.medianBlur8(g, ksz) }, { N.medianBlur8(g, ksz).value })
        val bad = Gray(37, 41, g.data.copyOf().also { it[700] = 256 })
        both("medianBlur8 值 256", { Cv.medianBlur8(bad, 3) }, { N.medianBlur8(bad, 3).value })
        val neg = Gray(37, 41, g.data.copyOf().also { it[5] = -1 })
        both("medianBlur8 值 -1", { Cv.medianBlur8(neg, 5) }, { N.medianBlur8(neg, 5).value })
        // medianRows：ksize 0、偶數、負的；值超出 0..255；段在頁外
        for (ksz in listOf(-1, 0, 2, 4, 1, 3, 7)) {
            both("medianRows / $ksz", {
                val dst = IntArray(37)
                Cv.MedianRows(g, ksz).fill(5, intArrayOf(2, 20), intArrayOf(9, 37), 2, dst)
                IntArray(7 + 17) { if (it < 7) dst[2 + it] else dst[20 + it - 7] }
            }, { N.medianRows(g, ksz, 5, intArrayOf(2, 20), intArrayOf(9, 37)) })
        }
        both("medianRows 值 256", { val dst = IntArray(37); Cv.MedianRows(bad, 3).fill(18, intArrayOf(0), intArrayOf(37), 1, dst); dst },
            { N.medianRows(bad, 3, 18, intArrayOf(0), intArrayOf(37)) })
        both("medianRows 段在頁外", { val dst = IntArray(37); Cv.MedianRows(g, 3).fill(5, intArrayOf(30), intArrayOf(40), 1, dst); dst },
            { N.medianRows(g, 3, 5, intArrayOf(30), intArrayOf(40)) })
        // gaussAt：點在頁外（平坦索引越界才丟；欄越界但平坦索引在頁內的照算）、偶數長的核、空核
        for ((x, y) in listOf(-1 to 0, 0 to 0, 36 to 40, 37 to 5, -1 to 7, 5 to -40, 5 to 41, -1000 to 3, 3 to 1000, 40 to 40)) {
            both("gaussAt ($x, $y)", { Cv.gaussAt(g, k1, x, y) }, { N.gaussAt(g, k1, x, y) })
        }
        for (k in listOf(intArrayOf(1, 2), intArrayOf(0, 0), IntArray(0), intArrayOf(65536), intArrayOf(0, 7, 0, 0))) {
            both("gaussAt 核 ${k.toList()}", { Cv.gaussAt(g, k, 3, 4) }, { N.gaussAt(g, k, 3, 4) })
        }
        // gaussQ16Sparse：need 在頁寬外有位元（Kotlin 越界）、偶數長的核、列範圍在頁外
        val nw = 1
        val need = LongArray(nw * 41) { if (it == 7) 1L shl 40 else 0L }
        both("gaussQ16Sparse need 在頁寬外", { sparse(37, 41, k1, need, 0, 41, Cv.grayRows(g)) }, { N.gaussQ16Sparse(37, 41, k1, need, 0, 41, g, 0).value })
        val needOk = LongArray(nw * 41) { if (it % 5 == 0) 0x1F0L else 0L }
        both("gaussQ16Sparse 偶數核", { sparse(37, 41, intArrayOf(1, 2), needOk, 0, 41, Cv.grayRows(g)) }, { N.gaussQ16Sparse(37, 41, intArrayOf(1, 2), needOk, 0, 41, g, 0).value })
        both("gaussQ16Sparse 空核", { sparse(37, 41, IntArray(0), needOk, 0, 41, Cv.grayRows(g)) }, { N.gaussQ16Sparse(37, 41, IntArray(0), needOk, 0, 41, g, 0).value })
        for ((ny0, nrows) in listOf(-3 to 41, 5 to 41, 30 to 20, 41 to 3)) {
            val nd = LongArray(nw * nrows) { 0x3L }
            both("gaussQ16Sparse 列 $ny0+$nrows", { sparse(37, 41, k1, nd, ny0, nrows, Cv.grayRows(g)) }, { N.gaussQ16Sparse(37, 41, k1, nd, ny0, nrows, g, 0).value })
            both("gaussQ16Sparse 中值 列 $ny0+$nrows", { sparse(37, 41, k1, nd, ny0, nrows, Cv.MedianRows(g, 5)) }, { N.gaussQ16Sparse(37, 41, k1, nd, ny0, nrows, g, 5).value })
        }
        // 繪圖：空的多邊形、ys 比 xs 短
        val m = Mask(37, 41)
        both("fillPoly 空", { m.copy().also { Cv.fillPoly(it, IntArray(0), IntArray(0)) } }, { N.fillPoly(m, IntArray(0), IntArray(0)).value })
        both("fillPoly ys 短", { m.copy().also { Cv.fillPoly(it, intArrayOf(1, 5, 9), intArrayOf(1, 5)) } }, { N.fillPoly(m, intArrayOf(1, 5, 9), intArrayOf(1, 5)).value })
        both("fillPoly 一點", { m.copy().also { Cv.fillPoly(it, intArrayOf(3), intArrayOf(4)) } }, { N.fillPoly(m, intArrayOf(3), intArrayOf(4)).value })
        // 最大值濾波：nt 比列數大很多
        val s = IntArray(5 * 7) { rnd.nextInt(100) }
        both("maxFilterWrapRows nt 13", { Cv.maxFilterWrapRows(s, 5, 7, 13, 2) }, { N.maxFilterWrapRows(s, 5, 7, 13, 2).value })
        // npSum 的 off／n
        val d = DoubleArray(20) { it * 0.1 }
        for ((off, n) in listOf(0 to 20, 5 to 15, 20 to 0, 25 to 0, -3 to 0, 3 to -2, 19 to 1)) both("npSum $off,$n", { Cv.npSum(d, off, n) }, { N.npSum(d, off, n) })
    }

    // ── FMA ─────────────────────────────────────────────────────────────

    private val gaussianKernel = Cv::class.java.getDeclaredMethod("gaussianKernel", Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
        .also { it.isAccessible = true }

    /** Cv.sepFilter 照抄，但每一個 Float 乘加都合成 FMA（Math.fma）：C++ 沒關掉合成時會算出的東西。 */
    private fun sepFilterFma(f: FImg, k: DoubleArray): FImg {
        val w = f.w
        val h = f.h
        val r = k.size / 2
        val kf = FloatArray(k.size) { k[it].toFloat() }
        val mid = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until minOf(r, w)) {
                var acc = 0f
                for (t in kf.indices) acc = Math.fma(kf[t], f.data[base + Cv.reflect101(x + t - r, w)], acc)
                mid[base + x] = acc
            }
            val hiX = w - r
            for (x in r until hiX) {
                var acc = 0f
                var idx = base + x - r
                for (t in kf.indices) { acc = Math.fma(kf[t], f.data[idx], acc); idx++ }
                mid[base + x] = acc
            }
            for (x in maxOf(r, hiX) until w) {
                var acc = 0f
                for (t in kf.indices) acc = Math.fma(kf[t], f.data[base + Cv.reflect101(x + t - r, w)], acc)
                mid[base + x] = acc
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val obase = y * w
            if (y < r || y >= h - r) {
                for (x in 0 until w) {
                    var acc = 0f
                    for (t in kf.indices) acc = Math.fma(kf[t], mid[Cv.reflect101(y + t - r, h) * w + x], acc)
                    out[obase + x] = acc
                }
            } else {
                for (t in kf.indices) {
                    val c = kf[t]
                    val sbase = (y + t - r) * w
                    if (t == 0) for (x in 0 until w) out[obase + x] = c * mid[sbase + x]
                    else for (x in 0 until w) out[obase + x] = Math.fma(c, mid[sbase + x], out[obase + x])
                }
            }
        }
        return FImg(w, h, out)
    }

    private fun gaussianFma(f: FImg, sigma: Double): FImg {
        var ks = Math.round(sigma * 4.0 * 2.0 + 1.0).toInt() or 1
        if (ks < 3) ks = 3
        return sepFilterFma(f, gaussianKernel.invoke(Cv, ks, sigma) as DoubleArray)
    }

    private fun boxFma(f: FImg, k: Int): FImg = sepFilterFma(f, DoubleArray(k) { 1.0 / k })

    private fun diffCount(a: FImg, b: FImg): Int {
        var n = 0
        for (i in a.data.indices) if (a.data[i].toRawBits() != b.data[i].toRawBits()) n++
        return n
    }

    /**
     * FMA 敏感的案例真的存在：Math.fma 版跟 Kotlin 版（＝C++，上面幾個測試逐位元比過）不同的輸出有多少。不同的越多，「C++ 跟 Kotlin
     * 相同」就越能證明 C++ 沒有把乘加合成 FMA（arm64 的 FMA 掃描之外的第二道證明；手機探針的 fmaSensitive 步驟同理）。
     */
    @Test
    fun fmaSensitiveCasesExist() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(4L)
        var cases = 0
        var sensitive = 0
        var pixels = 0L
        for ((w, h) in listOf(63 to 7, 64 to 64, 129 to 65, 200 to 3)) {
            // 0／1 也算：橫向的乘積精確，直向乘的卻是橫向算出的浮點和（產品的 restoreBlur、墨密度就是 0／1）
            val inputs = listOf(
                FImg(w, h, FloatArray(w * h) { if (rnd.nextInt(3) == 0) 1f else 0f }),
                FImg(w, h, FloatArray(w * h) { rnd.nextInt(256).toFloat() }),
                FImg(w, h, FloatArray(w * h) { (rnd.nextDouble() * 255).toFloat() }),
            )
            for (f in inputs) {
                for (s in listOf(0.7, 2.0, 4.0)) {
                    val c = NightNativeGray.gaussianBlur(f, s).value
                    assertTrue("gaussianBlur σ$s：C++ 與 Kotlin 不同", CvCallReplay.same(Cv.gaussianBlur(f, s), c))
                    val d = diffCount(gaussianFma(f, s), c)
                    cases++; if (d > 0) sensitive++; pixels += d
                }
                for (k in listOf(3, 15)) {
                    val c = NightNativeGray.boxBlur(f, k).value
                    assertTrue("boxBlur $k：C++ 與 Kotlin 不同", CvCallReplay.same(Cv.boxBlur(f, k), c))
                    val d = diffCount(boxFma(f, k), c)
                    cases++; if (d > 0) sensitive++; pixels += d
                }
            }
        }
        println("CppGrayParityTest FMA：$cases 組可分離濾波裡 $sensitive 組合成 FMA 會不同（合計 $pixels 個像素）；C++ 全部與 Kotlin 相同")
        assertEquals("每一組都要是 FMA 敏感（不然這個測試證明不了什麼）", cases, sensitive)
    }

    // ── 步驟表（手機探針）───────────────────────────────────────────────

    private fun res(n: String) = javaClass.classLoader!!.getResource("page/$n")

    private fun readGray(name: String): Gray {
        val img = res(name)!!.openStream().use { javax.imageio.ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = img.raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String) = readGray(name).let { g -> Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 }) }

    private val pages = listOf("ch34_010", "ch34_011", "ch34_015", "demo01", "demo02", "demo04", "demo05", "demo06", "syn_more", "syn_v4")

    /** 手機探針用的步驟表（[NightGrayWorkload]）在全部 fixture 頁上：每一步 C++ 與 Kotlin 相同；印每族的倍數（桌面 JVM 只是參考）。 */
    @Test
    fun workloadRealPages() {
        NativeLib.assumeLoaded()
        val all = ArrayList<NightGrayWorkload.StepResult>()
        for (p in pages) {
            val r = NightGrayWorkload.differential(readGray("${p}_gray.png"), readMask("${p}_seg.png"), readMask("${p}_char.png"), repeat = 1)
            val bad = r.filter { !it.identical }.map { it.step.id }
            assertEquals("$p：這些步驟 C++ 與 Kotlin 不同", emptyList<String>(), bad)
            all += r
        }
        println("CppGrayParityTest 步驟表：${pages.size} 頁、${all.size} 步全部相同（第 ${NightGrayWorkload.VERSION} 版）")
        for (s in NightGrayWorkload.summarize(all)) {
            println("  %-6s Kotlin %8.1f ms  C++ %8.1f ms  倍數 %.2f".format(s.family?.name ?: "合計", s.kotlinWeightedNs / 1e6, s.nativeWeightedNs / 1e6, s.ratio))
        }
        val steps = NightGrayWorkload.program(1351, 1920)
        assertEquals("每族都要有計入權重的步驟", NightGrayWorkload.Family.values().toSet(), steps.filter { it.weight > 0 }.mapNotNull { it.family }.toSet())
        assertEquals("步驟 id 不重複", steps.size, steps.map { it.id }.toSet().size)
        assertTrue("要有 FMA 敏感的步驟", steps.any { it.fmaSensitive })
    }

    /**
     * [NightGrayWorkload.Step.fmaSensitive] 的標記對不對：在 fixture 頁上，標了的步驟 Math.fma 版要真的跟原版不同，沒標的濾波步驟要一樣；
     * 不是濾波的步驟沒有浮點乘加，不該標。
     */
    @Test
    fun workloadFmaFlags() {
        NativeLib.assumeLoaded()
        for (p in listOf("demo05", "ch34_011")) {
            val regs = NightPrimWorkload.Regs(readGray("${p}_gray.png"), readMask("${p}_seg.png"), readMask("${p}_char.png"))
            val g = regs.gray("gray")
            for (st in NightGrayWorkload.program(g.w, g.h)) {
                if (NightGrayWorkload.runPrep(st, regs)) continue
                if (st.op != "gaussianBlur" && st.op != "boxBlur") {
                    assertEquals("${st.id} 沒有浮點乘加，不該標 FMA 敏感", false, st.fmaSensitive)
                    continue
                }
                val f = regs.map.getValue(st.inp) as FImg
                val (k, fma) = if (st.op == "gaussianBlur") Cv.gaussianBlur(f, st.args[0] / 10.0) to gaussianFma(f, st.args[0] / 10.0)
                else Cv.boxBlur(f, st.args[0]) to boxFma(f, st.args[0])
                val d = diffCount(k, fma)
                println("CppGrayParityTest FMA 標記 $p ${st.id}：合成 FMA 會不同的像素 $d")
                assertEquals("$p ${st.id} 的 fmaSensitive 標記（合成 FMA 不同的像素 $d）", d > 0, st.fmaSensitive)
            }
        }
    }

    /** 多條執行緒同時跑步驟表（C++ 沒有全域狀態；結果都要跟 Kotlin 相同）。 */
    @Test
    fun workloadConcurrent() {
        NativeLib.assumeLoaded()
        val g = readGray("demo05_gray.png")
        val seg = readMask("demo05_seg.png")
        val chr = readMask("demo05_char.png")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val fs = List(4) { pool.submit<List<NightGrayWorkload.StepResult>> { NightGrayWorkload.differential(g, seg, chr, repeat = 1) } }
        for (f in fs) assertTrue(f.get().all { it.identical })
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES))
    }

    @Test
    fun statsAndErrors() {
        NativeLib.assumeLoaded()
        val N = NightNativeGray
        val g = Gray(129, 7, IntArray(129 * 7) { it % 251 })
        val t = N.blackhat(g, Cv.ellipse(5))
        assertTrue("耗時要有值", t.nanos > 0)
        assertTrue("峰值至少有輸出：${t.peakBytes}", t.peakBytes >= 129 * 7 * 4)
        val k = Cv.ellipse(3)
        expect<IllegalArgumentException> { N.nGray(1, IntArray(5), 129, 7, k.data, 3, 3, 0, null) }
        expect<IllegalArgumentException> { N.nGray(1, g.data, 129, 7, null, 0, 0, 0, null) }
        expect<IllegalArgumentException> { N.nGray(99, g.data, 129, 7, k.data, 3, 3, 0, null) }
        expect<IllegalArgumentException> { N.nFilter(12, FloatArray(129 * 7), 129, 7, 1.0, 0, null) }
        expect<IllegalArgumentException> { N.nDraw(20, BooleanArray(10), 5, 2, intArrayOf(1, 2, 3, 4), null, null, null) }
        expect<IllegalArgumentException> { N.nCanny(IntArray(10), 5, 3, 0, 1, 2, null) }
        expect<IllegalArgumentException> { N.nMaxFilterWrapRows(IntArray(10), 3, 4, 1, 1, null) }
        expect<IllegalArgumentException> { N.nMaxFilterWrapRows(IntArray(12), 3, 4, 1, -1, null) }
        expect<IllegalArgumentException> { N.nGaussAt(g.data, 129, 7, k.data.map { 1 }.toIntArray(), intArrayOf(1, 2), intArrayOf(1), null) }
        expect<IllegalArgumentException> { N.nMedianRows(g.data, 129, 7, 3, intArrayOf(1, 2, 0, 5), null) }
        expect<IllegalArgumentException> { N.nNpSum(DoubleArray(5), 3, 4) }
        expect<IllegalArgumentException> { N.nFilter(10, FloatArray(129 * 7), 129, 7, 1.0, 0, LongArray(1)) }
        // stats 可以是 null
        assertTrue(Cv.medianBlur8(g, 3).data.contentEquals(N.nGray(6, g.data, 129, 7, null, 0, 0, 3, null)))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            fail("預期 ${T::class.java.simpleName}，卻是 $e")
        }
        fail("預期 ${T::class.java.simpleName}，卻沒有丟")
    }
}
