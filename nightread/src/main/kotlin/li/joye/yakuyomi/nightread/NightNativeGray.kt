package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.NightNativePrims.Timed

/**
 * 夜讀 C++ 核心第 3b 階影像原語（native/core/prims_gray.cpp、prims_draw.cpp；numpy 相容的在 jmath）的 JNI 綁定：[Cv] 的灰階形態學與
 * blackhat、高斯／均值濾波、cv2.line／fillPoly、最大值濾波（列環狀）、單點與取樣版的整數高斯（[Cv.gaussAt]、[Cv.gaussQ16Sparse] 與它的
 * 來源 [Cv.GrayRows]／[Cv.MedianRows]）、uint8 中值、Canny、npSum／npArgsort／eigh2／gaussW。每個都要跟 Kotlin 版逐位元相同
 * （CppGrayParityTest、CppCvCallReplayTest、nr_dump_check、手機探針）。
 *
 * **只給差分測試、錄放與手機探針用**，產品的重繪還在 Kotlin。跟 [NightNative] 一樣不自己載入 .so，呼叫前先問 [NightNative.available]。
 * 回 [Timed] 的：結果、原語本身的耗時（不含 JNI 的複製進出）、原生記憶體峰值；細粒度的（[gaussAt] 單點、[medianRows] 單次、[npSum]、
 * [npArgsort]、[eigh2]、[gaussW]）直接回值。就地改輸入的 Kotlin 原語（[Cv.line]、[Cv.fillPoly]）在這裡回畫好的新遮罩、不動輸入。
 * 參數不合法（陣列長度與尺寸不合）丟 [IllegalArgumentException]；Kotlin 版會丟例外的輸入，C++ 也丟（[IllegalStateException]）；
 * 原生配置失敗丟 [OutOfMemoryError]。可以在不同執行緒同時呼叫。
 */
object NightNativeGray {

    // 運算代碼（與 native/jni/nr_gray_jni.cpp 的 enum 同步）
    private const val OP_DILATE_GRAY = 1
    private const val OP_ERODE_GRAY = 2
    private const val OP_DILATE_GRAY_I = 3
    private const val OP_ERODE_GRAY_I = 4
    private const val OP_BLACKHAT = 5
    private const val OP_MEDIAN8 = 6
    private const val OP_GAUSSIAN_BLUR = 10
    private const val OP_BOX_BLUR = 11
    private const val OP_LINES = 20
    private const val OP_FILL_POLY = 21

    private inline fun <T> timed(f: (LongArray) -> T): Timed<T> {
        val s = LongArray(2)
        val v = f(s)
        return Timed(v, s[0], s[1])
    }

    private fun grayOp(op: Int, g: Gray, k: Kernel?, iarg: Int = 0): Timed<Gray> = timed { s ->
        Gray(g.w, g.h, nGray(op, g.data, g.w, g.h, k?.data, k?.w ?: 0, k?.h ?: 0, iarg, s))
    }

    // ── 灰階形態學 ───────────────────────────────────────────────────

    @JvmStatic fun dilateGray(g: Gray, k: Kernel): Timed<Gray> = grayOp(OP_DILATE_GRAY, g, k)
    @JvmStatic fun erodeGray(g: Gray, k: Kernel): Timed<Gray> = grayOp(OP_ERODE_GRAY, g, k)
    @JvmStatic fun dilateGrayI(g: Gray, k: Kernel): Timed<Gray> = grayOp(OP_DILATE_GRAY_I, g, k)
    @JvmStatic fun erodeGrayI(g: Gray, k: Kernel): Timed<Gray> = grayOp(OP_ERODE_GRAY_I, g, k)
    @JvmStatic fun blackhat(g: Gray, k: Kernel): Timed<Gray> = grayOp(OP_BLACKHAT, g, k)

    /** [Cv.medianBlur8]。 */
    @JvmStatic fun medianBlur8(g: Gray, ksize: Int): Timed<Gray> = grayOp(OP_MEDIAN8, g, null, ksize)

    // ── 濾波 ─────────────────────────────────────────────────────────

    @JvmStatic fun gaussianBlur(f: FImg, sigma: Double): Timed<FImg> = timed { s -> FImg(f.w, f.h, nFilter(OP_GAUSSIAN_BLUR, f.data, f.w, f.h, sigma, 0, s)) }
    @JvmStatic fun boxBlur(f: FImg, k: Int): Timed<FImg> = timed { s -> FImg(f.w, f.h, nFilter(OP_BOX_BLUR, f.data, f.w, f.h, 0.0, k, s)) }

    // ── 繪圖 ─────────────────────────────────────────────────────────

    /** [Cv.line] 畫在 [m] 的複本上（不改 [m]）。 */
    @JvmStatic fun line(m: Mask, x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int): Timed<Mask> =
        lines(m, intArrayOf(x0, y0, x1, y1, thickness))

    /** [Cv.line] 依序畫多段（[segs]＝每段 x0, y0, x1, y1, thickness）在 [m] 的複本上。 */
    @JvmStatic fun lines(m: Mask, segs: IntArray): Timed<Mask> = timed { s -> Mask(m.w, m.h, nDraw(OP_LINES, m.data, m.w, m.h, segs, null, null, s)) }

    /** [Cv.fillPoly] 填在 [m] 的複本上（不改 [m]）。 */
    @JvmStatic fun fillPoly(m: Mask, xs: IntArray, ys: IntArray): Timed<Mask> = timed { s -> Mask(m.w, m.h, nDraw(OP_FILL_POLY, m.data, m.w, m.h, null, xs, ys, s)) }

    // ── 其他 ─────────────────────────────────────────────────────────

    /** [Cv.canny]（[src] 至少 w·h 個）。 */
    @JvmStatic fun canny(src: IntArray, w: Int, h: Int, shift: Int, lo: Int, hi: Int): Timed<Mask> =
        timed { s -> Mask(w, h, nCanny(src, w, h, shift, lo, hi, s)) }

    /** [Cv.maxFilterWrapRows]。 */
    @JvmStatic fun maxFilterWrapRows(s: IntArray, rows: Int, cols: Int, nt: Int, nr: Int): Timed<IntArray> =
        timed { st -> nMaxFilterWrapRows(s, rows, cols, nt, nr, st) }

    /** [Cv.gaussAt] 單點。 */
    @JvmStatic fun gaussAt(g: Gray, k: IntArray, x: Int, y: Int): Int = nGaussAt(g.data, g.w, g.h, k, intArrayOf(x), intArrayOf(y), null)[0]

    /** [Cv.gaussAt] 在 ([xs], [ys]) 每一點的值（同一張 [g]、同一個核；錄放與探針把一頁的逐點呼叫併成一次）。 */
    @JvmStatic fun gaussAtMany(g: Gray, k: IntArray, xs: IntArray, ys: IntArray): Timed<IntArray> =
        timed { s -> nGaussAt(g.data, g.w, g.h, k, xs, ys, s) }

    /**
     * [Cv.gaussQ16Sparse]，來源＝[src] 的列（[ksize] 0＝[Cv.GrayRows]、否則 [Cv.MedianRows]([src], [ksize])）。回交給 sink 的列，
     * 格式同 [Cv.SparseRec]。
     */
    @JvmStatic fun gaussQ16Sparse(w: Int, h: Int, k: IntArray, need: LongArray, ny0: Int, nrows: Int, src: Gray, ksize: Int): Timed<IntArray> =
        timed { s -> nGaussQ16Sparse(w, h, k, need, ny0, nrows, src.data, src.w, src.h, ksize, s) }

    /** [Cv.MedianRows]([g], [ksize]).fill 一次：回各段的值依段序串起來。 */
    @JvmStatic fun medianRows(g: Gray, ksize: Int, r: Int, rs: IntArray, re: IntArray): IntArray {
        require(rs.size == re.size) { "rs、re 長度不同" }
        val spec = IntArray(2 + 2 * rs.size)
        spec[0] = r
        spec[1] = rs.size
        System.arraycopy(rs, 0, spec, 2, rs.size)
        System.arraycopy(re, 0, spec, 2 + rs.size, re.size)
        return nMedianRows(g.data, g.w, g.h, ksize, spec, null)
    }

    /** [Cv.MedianRows]([g], [ksize]).fill 連續多次（同一個來源物件，直方圖等工作緩衝沿用）：[spec]＝每次 r、n、n 個 rs、n 個 re。 */
    @JvmStatic fun medianRowsMany(g: Gray, ksize: Int, spec: IntArray): Timed<IntArray> = timed { s -> nMedianRows(g.data, g.w, g.h, ksize, spec, s) }

    @JvmStatic @JvmOverloads fun npSum(a: DoubleArray, off: Int = 0, n: Int = a.size - off): Double = nNpSum(a, off, n)
    @JvmStatic fun npArgsort(v: IntArray): IntArray = nNpArgsort(v)
    @JvmStatic fun eigh2(a: Double, b: Double, c: Double): DoubleArray = nEigh2(a, b, c)
    @JvmStatic fun gaussW(sigma: Double): IntArray = nGaussW(sigma)

    // 實作在 native/jni/nr_gray_jni.cpp；stats：長度 ≥ 2 或 null（stats[0]＝耗時奈秒、stats[1]＝原生峰值位元組）。
    @JvmStatic external fun nGray(op: Int, g: IntArray, w: Int, h: Int, kd: BooleanArray?, kw: Int, kh: Int, iarg: Int, stats: LongArray?): IntArray
    @JvmStatic external fun nFilter(op: Int, f: FloatArray, w: Int, h: Int, darg: Double, iarg: Int, stats: LongArray?): FloatArray
    @JvmStatic external fun nDraw(op: Int, m: BooleanArray, w: Int, h: Int, segs: IntArray?, xs: IntArray?, ys: IntArray?, stats: LongArray?): BooleanArray
    @JvmStatic external fun nCanny(src: IntArray, w: Int, h: Int, shift: Int, lo: Int, hi: Int, stats: LongArray?): BooleanArray
    @JvmStatic external fun nMaxFilterWrapRows(s: IntArray, rows: Int, cols: Int, nt: Int, nr: Int, stats: LongArray?): IntArray
    @JvmStatic external fun nGaussAt(g: IntArray, w: Int, h: Int, k: IntArray, xs: IntArray, ys: IntArray, stats: LongArray?): IntArray
    @JvmStatic external fun nGaussQ16Sparse(
        w: Int, h: Int, k: IntArray, need: LongArray, ny0: Int, nrows: Int, g: IntArray, gw: Int, gh: Int, ksize: Int, stats: LongArray?,
    ): IntArray
    @JvmStatic external fun nMedianRows(g: IntArray, gw: Int, gh: Int, ksize: Int, spec: IntArray, stats: LongArray?): IntArray
    @JvmStatic external fun nNpSum(a: DoubleArray, off: Int, n: Int): Double
    @JvmStatic external fun nNpArgsort(v: IntArray): IntArray
    @JvmStatic external fun nEigh2(a: Double, b: Double, c: Double): DoubleArray
    @JvmStatic external fun nGaussW(sigma: Double): IntArray
}
