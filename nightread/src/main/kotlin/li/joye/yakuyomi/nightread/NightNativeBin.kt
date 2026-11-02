package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.NightNativePrims.CcRunsResult
import li.joye.yakuyomi.nightread.NightNativePrims.Timed

/**
 * 夜讀 C++ 核心第 3a 階影像原語（native/core/prims.cpp、prims_mask.cpp、bits.cpp）的 JNI 綁定：[Cv] 的二值部分（結構元素、
 * 一般核與線核的形態學、方核分解、打包格式的侵蝕、ccRunsBits、distanceSq、chamfer、縮放、補洞、輪廓長、多邊形面積、測地生長、
 * 二值中值）與位元的底層小工具（[Cv.packBits] 格式的取反打包／解包／就地取反、bitRuns、anyBits、reflect101、Ring 的整頁位元集合）。
 * 每個都要跟 Kotlin 版逐位元相同（CppBinParityTest、CppCvCallReplayTest、nr_dump_check、手機探針）。
 *
 * **只給差分測試、錄放與手機探針用**，產品的重繪還在 Kotlin。跟 [NightNative] 一樣不自己載入 .so，呼叫前先問 [NightNative.available]。
 * 回 [Timed] 的：結果、原語本身的耗時（不含 JNI 的複製進出）、原生記憶體峰值。細粒度的小工具（[bitRuns]、[anyBits]、[reflect101]、
 * [ringHas]、[ringSet]、[ellipse]、[rect]、[polygonArea]）直接回值。參數不合法（陣列長度與尺寸不合）丟 [IllegalArgumentException]；
 * Kotlin 版會丟例外的輸入，C++ 也丟（[IllegalStateException]）；原生配置失敗丟 [OutOfMemoryError]。可以在不同執行緒同時呼叫。
 */
object NightNativeBin {

    // 運算代碼（與 native/jni/nr_bin_jni.cpp 的 enum 同步）
    private const val OP_DILATE = 1
    private const val OP_ERODE = 2
    private const val OP_OPEN = 3
    private const val OP_CLOSE = 4
    private const val OP_ERODE_PACKED = 5
    private const val OP_OPEN_PACKED = 6
    private const val OP_CLOSE_PACKED = 7
    private const val OP_DILATE_RECT_SEP = 8
    private const val OP_CLOSE_RECT_SEP = 9
    private const val OP_HOLES = 10
    private const val OP_MEDIAN = 11
    private const val OP_RESIZE_NEAREST = 12
    private const val OP_DISTANCE_SQ = 20
    private const val OP_CHAMFER5_PADDED = 21
    private const val OP_PACK = 30
    private const val OP_PACK_NOT = 31
    private const val OP_RING_PACK = 32
    private const val OP_UNPACK = 40
    private const val OP_UNPACK_NOT = 41
    private const val OP_NOT_IN_PLACE = 50
    private const val OP_ERODE_BITS = 51
    private const val OP_RESIZE_AREA = 60
    private const val OP_RESIZE_BILINEAR = 61
    private const val OP_RESIZE_AREA_OF = 62
    private const val OP_ELLIPSE = 70
    private const val OP_RECT = 71

    private val NO_ARGS = IntArray(0)

    private inline fun <T> timed(f: (LongArray) -> T): Timed<T> {
        val s = LongArray(2)
        val v = f(s)
        return Timed(v, s[0], s[1])
    }

    private fun maskOp(op: Int, m: Mask, k: Kernel?, args: IntArray, ow: Int = m.w, oh: Int = m.h): Timed<Mask> = timed { s ->
        Mask(ow, oh, nMask(op, m.data, m.w, m.h, k?.data, k?.w ?: 0, k?.h ?: 0, args, s))
    }

    // ── 結構元素 ─────────────────────────────────────────────────────

    /** [Cv.ellipse]（C++ 算的核）。 */
    @JvmStatic
    fun ellipse(size: Int): Kernel = Kernel(size, size, nKernel(OP_ELLIPSE, size, 0))

    /** [Cv.rect]（C++ 算的核）。 */
    @JvmStatic
    fun rect(kw: Int, kh: Int): Kernel = Kernel(kw, kh, nKernel(OP_RECT, kw, kh))

    // ── 二值形態學 ───────────────────────────────────────────────────

    @JvmStatic @JvmOverloads
    fun dilate(m: Mask, k: Kernel, iterations: Int = 1): Timed<Mask> = maskOp(OP_DILATE, m, k, intArrayOf(iterations))

    @JvmStatic @JvmOverloads
    fun erode(m: Mask, k: Kernel, iterations: Int = 1): Timed<Mask> = maskOp(OP_ERODE, m, k, intArrayOf(iterations))

    @JvmStatic
    fun open(m: Mask, k: Kernel): Timed<Mask> = maskOp(OP_OPEN, m, k, NO_ARGS)

    @JvmStatic
    fun close(m: Mask, k: Kernel): Timed<Mask> = maskOp(OP_CLOSE, m, k, NO_ARGS)

    @JvmStatic
    fun erodePacked(m: Mask, k: Kernel): Timed<Mask> = maskOp(OP_ERODE_PACKED, m, k, NO_ARGS)

    @JvmStatic
    fun openPacked(m: Mask, k: Kernel): Timed<Mask> = maskOp(OP_OPEN_PACKED, m, k, NO_ARGS)

    @JvmStatic
    fun closePacked(m: Mask, k: Kernel): Timed<Mask> = maskOp(OP_CLOSE_PACKED, m, k, NO_ARGS)

    @JvmStatic
    fun dilateRectSep(m: Mask, kw: Int, kh: Int): Timed<Mask> = maskOp(OP_DILATE_RECT_SEP, m, null, intArrayOf(kw, kh))

    @JvmStatic
    fun closeRectSep(m: Mask, n: Int): Timed<Mask> = maskOp(OP_CLOSE_RECT_SEP, m, null, intArrayOf(n))

    // ── 打包位元 ─────────────────────────────────────────────────────

    /** [Cv.packBits]。 */
    @JvmStatic
    fun packBits(m: Mask): Timed<LongArray> = timed { s -> nPack(OP_PACK, m.data, m.w, m.h, s) }

    /** [Cv.packBitsNot]。 */
    @JvmStatic
    fun packBitsNot(m: Mask): Timed<LongArray> = timed { s -> nPack(OP_PACK_NOT, m.data, m.w, m.h, s) }

    /** Ring.packBits（整頁位元集合：第 i 像素在第 i ushr 6 字的第 i and 63 位，不分列）。 */
    @JvmStatic
    fun ringPackBits(m: Mask): Timed<LongArray> = timed { s -> nPack(OP_RING_PACK, m.data, m.w, m.h, s) }

    /** [Cv.unpackBits]（[a]＝[Cv.packBits] 格式）。 */
    @JvmStatic
    fun unpackBits(a: LongArray, w: Int, h: Int): Timed<Mask> = timed { s -> Mask(w, h, nUnpack(OP_UNPACK, a, w, h, s)) }

    /** [Cv.unpackBitsNot]。 */
    @JvmStatic
    fun unpackBitsNot(a: LongArray, w: Int, h: Int): Timed<Mask> = timed { s -> Mask(w, h, nUnpack(OP_UNPACK_NOT, a, w, h, s)) }

    /** [Cv.notBitsInPlace] 的結果（回新陣列，不改 [a]）。 */
    @JvmStatic
    fun notBits(a: LongArray, w: Int, h: Int): Timed<LongArray> = timed { s -> nBits(OP_NOT_IN_PLACE, a, w, h, null, 0, 0, s) }

    /** [Cv.erodeBits]。 */
    @JvmStatic
    fun erodeBits(src: LongArray, w: Int, h: Int, k: Kernel): Timed<LongArray> = timed { s -> nBits(OP_ERODE_BITS, src, w, h, k.data, k.w, k.h, s) }

    /** [Cv.bitRuns] 的結果：[rs]／[re]＝游程 [s, e)，只有段數那麼長。 */
    class Runs(@JvmField val rs: IntArray, @JvmField val re: IntArray)

    /** [Cv.bitRuns]：[bits] 第 [off] 字起 [nw] 字的一列。 */
    @JvmStatic
    fun bitRuns(bits: LongArray, off: Int, nw: Int): Runs = nBitRuns(bits, off, nw).let { Runs(it[0], it[1]) }

    /** [Cv.anyBits]。 */
    @JvmStatic
    fun anyBits(bits: LongArray, off: Int, s: Int, e: Int): Boolean = nAnyBits(bits, off, s, e)

    /** [Cv.reflect101]（[n] ≥ 1）。 */
    @JvmStatic
    fun reflect101(i: Int, n: Int): Int = nReflect101(i, n)

    /** Ring.has。 */
    @JvmStatic
    fun ringHas(bits: LongArray?, i: Int): Boolean = nRingHas(bits, i)

    /** Ring.set 的結果（回新陣列，不改 [bits]）。 */
    @JvmStatic
    fun ringSet(bits: LongArray, i: Int): LongArray = nRingSet(bits, i)

    // ── 連通元件 ─────────────────────────────────────────────────────

    /** [Cv.ccRunsBits]（[bits]＝[Cv.packBits] 格式）。 */
    @JvmStatic @JvmOverloads
    fun ccRunsBits(bits: LongArray, w: Int, h: Int, connectivity: Int = 8): Timed<CcRunsResult> {
        val s = LongArray(2)
        val a = nCcRunsBits(bits, w, h, connectivity, s)
        val meta = a[0]
        val fg = (meta[5].toLong() shl 32) or (meta[6].toLong() and 0xFFFFFFFFL)
        return Timed(CcRunsResult(meta[0], a[1], a[2], a[3], a[4], fg, meta[1], meta[2], meta[3], meta[4]), s[0], s[1])
    }

    /** Kotlin 版的 [Cv.ccRunsBits]，轉成跨模組可用的型別（探針要比、要計時）。 */
    @JvmStatic @JvmOverloads
    fun ccRunsBitsKotlin(bits: LongArray, w: Int, h: Int, connectivity: Int = 8): CcRunsResult {
        val r = Cv.ccRunsBits(bits, w, h, connectivity)
        return CcRunsResult(r.n, r.rs, r.re, r.rowFirst, r.lab, r.fg, r.bgLeft, r.bgTop, r.bgRight, r.bgBottom)
    }

    // ── 距離 ─────────────────────────────────────────────────────────

    /** [Cv.distanceSq]（長 w·h）。 */
    @JvmStatic
    fun distanceSq(m: Mask): Timed<IntArray> = timed { s -> nMaskInts(OP_DISTANCE_SQ, m.data, m.w, m.h, s) }

    /** [Cv.chamfer5Padded]（長 (w+4)·(h+4)）。 */
    @JvmStatic
    fun chamfer5Padded(m: Mask): Timed<IntArray> = timed { s -> nMaskInts(OP_CHAMFER5_PADDED, m.data, m.w, m.h, s) }

    /** [Cv.distanceChamfer]（[maskSize] 3 或 5）。 */
    @JvmStatic
    fun distanceChamfer(m: Mask, maskSize: Int): Timed<FImg> = timed { s -> FImg(m.w, m.h, nDistanceChamfer(m.data, m.w, m.h, maskSize, s)) }

    // ── 縮放 ─────────────────────────────────────────────────────────

    @JvmStatic
    fun resizeArea(f: FImg, nw: Int, nh: Int): Timed<FImg> = timed { s -> FImg(nw, nh, nResizeF(OP_RESIZE_AREA, f.data, f.w, f.h, nw, nh, s)) }

    /** Cv.resizeAreaOf 的本體（來源＝[f] 的像素；同尺寸也照算，不走 [resizeArea] 的複本捷徑）。 */
    @JvmStatic
    fun resizeAreaOf(f: FImg, nw: Int, nh: Int): Timed<FImg> = timed { s -> FImg(nw, nh, nResizeF(OP_RESIZE_AREA_OF, f.data, f.w, f.h, nw, nh, s)) }

    @JvmStatic
    fun resizeBilinear(f: FImg, nw: Int, nh: Int): Timed<FImg> = timed { s -> FImg(nw, nh, nResizeF(OP_RESIZE_BILINEAR, f.data, f.w, f.h, nw, nh, s)) }

    @JvmStatic
    fun resizeNearest(m: Mask, nw: Int, nh: Int): Timed<Mask> = maskOp(OP_RESIZE_NEAREST, m, null, intArrayOf(nw, nh), nw, nh)

    @JvmStatic
    fun resizeNearestGray(g: Gray, nw: Int, nh: Int): Timed<Gray> = timed { s -> Gray(nw, nh, nResizeNearestGray(g.data, g.w, g.h, nw, nh, s)) }

    // ── 洞／輪廓／面積／測地／中值 ──────────────────────────────────────

    @JvmStatic
    fun holes(m: Mask): Timed<Mask> = maskOp(OP_HOLES, m, null, NO_ARGS)

    @JvmStatic
    fun totalContourLength(m: Mask): Timed<Double> = timed { s -> nTotalContourLength(m.data, m.w, m.h, s) }

    /** [Cv.polygonArea]（[xs]、[ys] 同長）。 */
    @JvmStatic
    fun polygonArea(xs: FloatArray, ys: FloatArray): Double = nPolygonArea(xs, ys)

    /** [Cv.geodesicDistance]（[seed]、[within] 同尺寸）。 */
    @JvmStatic
    fun geodesicDistance(seed: Mask, within: Mask, step: Int, maxIters: Int): Timed<IntArray> =
        timed { s -> nGeodesicDistance(seed.data, within.data, seed.w, seed.h, step, maxIters, s) }

    /** [Cv.geodesicGrow]（[seed]、[within] 同尺寸；[bfs] null＝依成本選）。 */
    @JvmStatic @JvmOverloads
    fun geodesicGrow(seed: Mask, within: Mask, iters: Int, step: Int = 5, bfs: Boolean? = null): Timed<Mask> = timed { s ->
        Mask(seed.w, seed.h, nGeodesicGrow(seed.data, within.data, seed.w, seed.h, iters, step, if (bfs == null) -1 else if (bfs) 1 else 0, s))
    }

    @JvmStatic
    fun medianBlurMask(m: Mask, r: Int): Timed<Mask> = maskOp(OP_MEDIAN, m, null, intArrayOf(r))

    // 實作在 native/jni/nr_bin_jni.cpp；stats：長度 ≥ 2 或 null（stats[0]＝耗時奈秒、stats[1]＝原生峰值位元組）。
    @JvmStatic external fun nMask(op: Int, m: BooleanArray, w: Int, h: Int, kd: BooleanArray?, kw: Int, kh: Int, args: IntArray?, stats: LongArray?): BooleanArray
    @JvmStatic external fun nMaskInts(op: Int, m: BooleanArray, w: Int, h: Int, stats: LongArray?): IntArray
    @JvmStatic external fun nDistanceChamfer(m: BooleanArray, w: Int, h: Int, maskSize: Int, stats: LongArray?): FloatArray
    @JvmStatic external fun nTotalContourLength(m: BooleanArray, w: Int, h: Int, stats: LongArray?): Double
    @JvmStatic external fun nGeodesicGrow(seed: BooleanArray, within: BooleanArray, w: Int, h: Int, iters: Int, step: Int, bfs: Int, stats: LongArray?): BooleanArray
    @JvmStatic external fun nGeodesicDistance(seed: BooleanArray, within: BooleanArray, w: Int, h: Int, step: Int, maxIters: Int, stats: LongArray?): IntArray
    @JvmStatic external fun nPack(op: Int, m: BooleanArray, w: Int, h: Int, stats: LongArray?): LongArray
    @JvmStatic external fun nUnpack(op: Int, a: LongArray, w: Int, h: Int, stats: LongArray?): BooleanArray
    @JvmStatic external fun nBits(op: Int, a: LongArray, w: Int, h: Int, kd: BooleanArray?, kw: Int, kh: Int, stats: LongArray?): LongArray

    /** { {n, bgLeft, bgTop, bgRight, bgBottom, fg 高 32 位, fg 低 32 位}, rs, re, rowFirst, lab } */
    @JvmStatic external fun nCcRunsBits(a: LongArray, w: Int, h: Int, connectivity: Int, stats: LongArray?): Array<IntArray>

    /** { rs, re } */
    @JvmStatic external fun nBitRuns(a: LongArray, off: Int, nw: Int): Array<IntArray>
    @JvmStatic external fun nAnyBits(a: LongArray, off: Int, s: Int, e: Int): Boolean
    @JvmStatic external fun nReflect101(i: Int, n: Int): Int
    @JvmStatic external fun nRingHas(a: LongArray?, i: Int): Boolean
    @JvmStatic external fun nRingSet(a: LongArray, i: Int): LongArray
    @JvmStatic external fun nKernel(op: Int, a: Int, b: Int): BooleanArray
    @JvmStatic external fun nResizeF(op: Int, f: FloatArray, w: Int, h: Int, nw: Int, nh: Int, stats: LongArray?): FloatArray
    @JvmStatic external fun nResizeNearestGray(g: IntArray, w: Int, h: Int, nw: Int, nh: Int, stats: LongArray?): IntArray
    @JvmStatic external fun nPolygonArea(xs: FloatArray, ys: FloatArray): Double
}
