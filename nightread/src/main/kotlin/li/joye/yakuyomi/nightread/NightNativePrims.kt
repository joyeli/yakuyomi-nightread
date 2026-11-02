package li.joye.yakuyomi.nightread

/**
 * 夜讀 C++ 核心第一批影像原語（native/core/prims.cpp）的 JNI 綁定：[Cv.dilatePacked]、[Cv.dilateBits]（含 PackedRuns）、[Cv.ccRuns]、
 * [Cv.ccStats]、[Cv.distanceL2]、[Cv.gaussQ16]、[Cv.morphGrayGather]。每個都要跟 Kotlin 版逐位元相同（CppPrimsParityTest、手機探針）。
 *
 * **只給差分測試與手機探針用**，產品的重繪還在 Kotlin；之後的產品路徑是整頁的 handle（[NightNative]），不會每個原語都過一次 JNI。
 * 跟 [NightNative] 一樣不自己載入 .so，呼叫前先問 [NightNative.available]。
 *
 * 每個呼叫回 [Timed]：結果、原語本身的耗時（不含 JNI 的複製進出）、原生記憶體峰值。參數不合法丟 [IllegalArgumentException]，
 * 原生配置失敗丟 [OutOfMemoryError]。可以在不同執行緒同時呼叫。
 */
object NightNativePrims {

    /** [value]＝結果；[nanos]＝C++ 原語的耗時（CLOCK_MONOTONIC，輸入已在原生記憶體、含輸出的配置）；[peakBytes]＝這次呼叫的原生峰值。 */
    class Timed<T>(@JvmField val value: T, @JvmField val nanos: Long, @JvmField val peakBytes: Long)

    /** [Cv.ccRuns] 的結果（Cv.CcRuns 是模組內部的型別，探針在別的模組）。陣列長度與 Kotlin 版相同，[rs]／[re] 含多配的容量。 */
    class CcRunsResult(
        @JvmField val n: Int,
        @JvmField val rs: IntArray,
        @JvmField val re: IntArray,
        @JvmField val rowFirst: IntArray,
        @JvmField val lab: IntArray,
        @JvmField val fg: Long,
        @JvmField val bgLeft: Int,
        @JvmField val bgTop: Int,
        @JvmField val bgRight: Int,
        @JvmField val bgBottom: Int,
    ) {
        /** 所有欄位逐值相同。 */
        fun sameAs(o: CcRunsResult): Boolean = n == o.n && fg == o.fg && bgLeft == o.bgLeft && bgTop == o.bgTop && bgRight == o.bgRight &&
            bgBottom == o.bgBottom && rs.contentEquals(o.rs) && re.contentEquals(o.re) && rowFirst.contentEquals(o.rowFirst) && lab.contentEquals(o.lab)
    }

    /** Kotlin 版的 [Cv.ccRuns]，轉成跨模組可用的型別（探針要比、要計時）。 */
    @JvmStatic
    fun ccRunsKotlin(m: Mask, connectivity: Int = 8): CcRunsResult {
        val r = Cv.ccRuns(m, connectivity)
        return CcRunsResult(r.n, r.rs, r.re, r.rowFirst, r.lab, r.fg, r.bgLeft, r.bgTop, r.bgRight, r.bgBottom)
    }

    @JvmStatic
    fun dilatePacked(m: Mask, k: Kernel): Timed<Mask> {
        val s = LongArray(2)
        val d = nDilatePacked(m.data, m.w, m.h, k.data, k.w, k.h, s)
        return Timed(Mask(m.w, m.h, d), s[0], s[1])
    }

    /** [src]＝[Cv.packBits] 的格式。 */
    @JvmStatic
    fun dilateBits(src: LongArray, w: Int, h: Int, k: Kernel): Timed<LongArray> {
        val s = LongArray(2)
        val d = nDilateBits(src, w, h, k.data, k.w, k.h, s)
        return Timed(d, s[0], s[1])
    }

    @JvmStatic
    fun ccStats(m: Mask, connectivity: Int = 8): Timed<CC> {
        val s = LongArray(2)
        val a = nCcStats(m.data, m.w, m.h, connectivity, s)
        return Timed(CC(a[0][0], a[1], a[2], a[3], a[4], a[5], a[6]), s[0], s[1])
    }

    @JvmStatic
    fun ccRuns(m: Mask, connectivity: Int = 8): Timed<CcRunsResult> {
        val s = LongArray(2)
        val a = nCcRuns(m.data, m.w, m.h, connectivity, s)
        val meta = a[0]
        val fg = (meta[5].toLong() shl 32) or (meta[6].toLong() and 0xFFFFFFFFL)
        return Timed(CcRunsResult(meta[0], a[1], a[2], a[3], a[4], fg, meta[1], meta[2], meta[3], meta[4]), s[0], s[1])
    }

    @JvmStatic
    fun distanceL2(m: Mask): Timed<FImg> {
        val s = LongArray(2)
        val d = nDistanceL2(m.data, m.w, m.h, s)
        return Timed(FImg(m.w, m.h, d), s[0], s[1])
    }

    @JvmStatic
    fun gaussQ16(g: Gray, sigma: Double): Timed<Gray> {
        val s = LongArray(2)
        val d = nGaussQ16(g.data, g.w, g.h, sigma, s)
        return Timed(Gray(g.w, g.h, d), s[0], s[1])
    }

    @JvmStatic
    fun morphGrayGather(g: Gray, k: Kernel, wantMax: Boolean): Timed<Gray> {
        val s = LongArray(2)
        val d = nMorphGrayGather(g.data, g.w, g.h, k.data, k.w, k.h, wantMax, s)
        return Timed(Gray(g.w, g.h, d), s[0], s[1])
    }

    // stats：長度 ≥ 2 或 null；stats[0]＝耗時（奈秒）、stats[1]＝原生峰值（位元組）。實作在 native/jni/nr_prims_jni.cpp。
    @JvmStatic external fun nDilatePacked(m: BooleanArray, w: Int, h: Int, kd: BooleanArray, kw: Int, kh: Int, stats: LongArray?): BooleanArray
    @JvmStatic external fun nDilateBits(src: LongArray, w: Int, h: Int, kd: BooleanArray, kw: Int, kh: Int, stats: LongArray?): LongArray

    /** { {n}, labels, left, top, width, height, area } */
    @JvmStatic external fun nCcStats(m: BooleanArray, w: Int, h: Int, connectivity: Int, stats: LongArray?): Array<IntArray>

    /** { {n, bgLeft, bgTop, bgRight, bgBottom, fg 高 32 位, fg 低 32 位}, rs, re, rowFirst, lab } */
    @JvmStatic external fun nCcRuns(m: BooleanArray, w: Int, h: Int, connectivity: Int, stats: LongArray?): Array<IntArray>
    @JvmStatic external fun nDistanceL2(m: BooleanArray, w: Int, h: Int, stats: LongArray?): FloatArray
    @JvmStatic external fun nGaussQ16(g: IntArray, w: Int, h: Int, sigma: Double, stats: LongArray?): IntArray
    @JvmStatic external fun nMorphGrayGather(g: IntArray, w: Int, h: Int, kd: BooleanArray, kw: Int, kh: Int, wantMax: Boolean, stats: LongArray?): IntArray
}
