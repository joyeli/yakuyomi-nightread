package li.joye.yakuyomi.nightread

/**
 * 第 3a 階 C++ 原語（[NightNativeBin]）在**真頁**上的差分與計時：一頁的輸入經這張步驟表跑一遍，每個原語的 Kotlin 版與 C++ 版同一個行程
 * 比位元、比時間。用法與 [NightPrimWorkload]（第 2 階的 5 族）相同：手機探針（引擎 sandbox）先 `differential(…, repeat = 1)` 暖機，
 * 再 `differential(…, repeat = 3) { 逐步寫紀錄 }`，多頁的結果接起來給 [summarize]。
 *
 * **權重**（[Step.weight]）＝一頁產品兩檔（renderTiers L2＋L3，fork 預設亮度）實際呼叫幾次：2026-11-02 用 [Cv.tap] 數 ch34_011、demo06、ch34_015、
 * demo05、demo01、demo02（紀錄不進 repo），取中位數。
 *  - 整頁呼叫的核與參數照實際的呼叫點（格框線的 405／270 長條、方核分解、3×3／5×5 chamfer…）；遮罩用頁面導出的代理（同 [NightPrimWorkload]）。
 *  - 視窗呼叫（泡、貼紙、灰圈的小框）併成 240×320 的裁切，權重＝視窗總像素 ÷ 76,800。
 *  - 巢狀在其他原語裡的（packBits、unpackBits、packBitsNot、unpackBitsNot、notBitsInPlace、erodeBits 單獨的呼叫）與一頁 0–1 次的
 *    （ccRunsBits、chamfer5Padded）權重 0：只比位元、不計入合計（時間已算在外層）。
 *  - 數的是 Cv.tap 回報的呼叫，含巢狀：例如 closePacked 裡的 erodeBits 在這張表只算在 closePacked。
 * 合計倍數＝Σ 權重·Kotlin 時間 ÷ Σ 權重·C++ 時間；只當參考（第 3a 階的關卡是「全部相同」）。
 */
object NightBinWorkload {

    /** 步驟表的版本：改了步驟或權重就加 1（探針紀錄會印）。 */
    const val VERSION: Int = 1

    /** MORPH＝一般核的打包侵蝕／開／閉；LINE＝長條核與方核分解；DIST＝整數距離與 chamfer；MISC＝補洞、輪廓、中值、測地、縮放；BITS＝位元格式。 */
    enum class Family { MORPH, LINE, DIST, MISC, BITS }

    /**
     * 一步。準備步驟（不計時、只跑 Kotlin）＝[NightPrimWorkload] 的 `ge`、`lt`、`not`、`crop`，加 `sparse`（[args] n：只留索引是 n 倍數的前景）、
     * `toF`（灰階 → 浮點影像）。原語的 [op] 與參數見 [runKotlin]。[inp]／[inp2]＝輸入暫存器、[out]＝結果要存的暫存器（null＝不留）。
     */
    class Step(
        @JvmField val id: String,
        @JvmField val op: String,
        @JvmField val inp: String,
        @JvmField val out: String?,
        @JvmField val args: IntArray = IntArray(0),
        @JvmField val kernel: Kernel? = null,
        @JvmField val weight: Double = 0.0,
        @JvmField val inp2: String? = null,
        @JvmField val family: Family? = null,
    )

    /** 尺寸 [w]×[h] 的頁的步驟表（只有裁切範圍與縮放尺寸跟頁的尺寸有關）。 */
    @JvmStatic
    fun program(w: Int, h: Int): List<Step> {
        val s = ArrayList<Step>()
        fun prep(id: String, op: String, inp: String, vararg a: Int) { s += Step(id, op, inp, id, a) }
        fun st(id: String, fam: Family?, op: String, inp: String, weight: Double, k: Kernel? = null, inp2: String? = null, a: IntArray = IntArray(0)) {
            s += Step(id, op, inp, null, a, k, weight, inp2, fam)
        }
        fun e(n: Int) = Cv.ellipse(n)

        prep("white", "ge", "gray", 235)
        prep("ink", "lt", "gray", 128)
        prep("paper", "ge", "gray", 128)
        prep("notSeg", "not", "seg")
        val cx = w / 3
        val cy = h / 3
        val cw = minOf(240, w - cx)
        val ch = minOf(320, h - cy)
        prep("whiteWin", "crop", "white", cx, cy, cw, ch)
        prep("inkWin", "crop", "ink", cx, cy, cw, ch)
        prep("seedWin", "sparse", "whiteWin", 997)
        prep("seedPage", "sparse", "char", 31)
        prep("grayF", "toF", "gray")

        val M = Family.MORPH
        st("erodePacked.win.e3", M, "erodePacked", "whiteWin", 25.0, e(3))
        st("erodePacked.win.e5", M, "erodePacked", "whiteWin", 16.3, e(5))
        st("erodePacked.win.e7", M, "erodePacked", "whiteWin", 16.3, e(7))
        st("erodePacked.win.e9", M, "erodePacked", "whiteWin", 1.6, e(9))
        st("closePacked.e11", M, "closePacked", "seg", 1.0, e(11))
        st("closePacked.win.e11", M, "closePacked", "whiteWin", 6.5, e(11))
        st("openPacked.e5", M, "openPacked", "white", 1.0, e(5))
        st("openPacked.win.e17", M, "openPacked", "whiteWin", 10.0, e(17))
        st("openPacked.win.e25", M, "openPacked", "whiteWin", 12.9, e(25))
        st("erodeBits.e11", M, "erodeBits", "seg", 0.0, e(11))

        val L = Family.LINE
        st("close.h405", L, "close", "ink", 0.5, Cv.rect(405, 1))
        st("close.v405", L, "close", "ink", 0.5, Cv.rect(1, 405))
        st("open.h270", L, "open", "ink", 0.5, Cv.rect(270, 1))
        st("open.v270", L, "open", "ink", 0.5, Cv.rect(1, 270))
        st("dilateRectSep.5", L, "dilateRectSep", "ink", 9.0, a = intArrayOf(5, 5))
        st("dilateRectSep.9", L, "dilateRectSep", "ink", 9.0, a = intArrayOf(9, 9))
        st("closeRectSep.7", L, "closeRectSep", "ink", 2.0, a = intArrayOf(7))

        val D = Family.DIST
        st("distanceSq.notSeg", D, "distanceSq", "notSeg", 2.0)
        st("distanceChamfer3.paper", D, "distanceChamfer", "paper", 2.0, a = intArrayOf(3))
        st("distanceChamfer5.win", D, "distanceChamfer", "whiteWin", 1.9, a = intArrayOf(5))
        st("chamfer5Padded.win", D, "chamfer5Padded", "whiteWin", 0.0)

        val X = Family.MISC
        st("holes.win", X, "holes", "inkWin", 36.6)
        st("totalContourLength.win", X, "totalContourLength", "whiteWin", 13.4)
        st("medianBlurMask.seg.5", X, "medianBlurMask", "seg", 1.0, a = intArrayOf(5))
        st("medianBlurMask.win.5", X, "medianBlurMask", "whiteWin", 6.4, a = intArrayOf(5))
        st("geodesicGrow.page", X, "geodesicGrow", "seedPage", 1.0, inp2 = "paper", a = intArrayOf(12, 4))
        st("geodesicGrow.win", X, "geodesicGrow", "seedWin", 14.4, inp2 = "whiteWin", a = intArrayOf(60, 4))
        st("geodesicDistance.win", X, "geodesicDistance", "seedWin", 8.1, inp2 = "whiteWin", a = intArrayOf(6, 4002))
        st("resizeNearest.win.half", X, "resizeNearest", "whiteWin", 6.6, a = intArrayOf(maxOf(1, cw / 2), maxOf(1, ch / 2)))
        st("resizeNearest.win.up", X, "resizeNearest", "whiteWin", 6.6, a = intArrayOf(cw * 2, ch * 2))
        st("resizeAreaOf.half", X, "resizeAreaOf", "grayF", 1.0, a = intArrayOf(maxOf(1, w / 2), maxOf(1, h / 2)))

        val B = Family.BITS
        st("ring.packBits.seg", B, "ring.packBits", "seg", 14.0)
        st("packBits.white", B, "packBits", "white", 0.0)
        st("packBitsNot.white", B, "packBitsNot", "white", 0.0)
        st("unpackBits.white", B, "unpackBits", "white", 0.0)
        st("unpackBitsNot.white", B, "unpackBitsNot", "white", 0.0)
        st("notBits.white", B, "notBits", "white", 0.0)
        st("ccRunsBits.white", B, "ccRunsBits", "white", 0.0, a = intArrayOf(8))
        return s
    }

    /** 跑準備步驟（不是準備步驟回 false）。 */
    @JvmStatic
    fun runPrep(st: Step, r: NightPrimWorkload.Regs): Boolean {
        val out: Any = when (st.op) {
            "sparse" -> {
                val m = r.mask(st.inp)
                Mask(m.w, m.h, BooleanArray(m.data.size) { m.data[it] && it % st.args[0] == 0 })
            }
            "toF" -> r.gray(st.inp).toF()
            "ge", "lt", "not", "crop" -> {
                NightPrimWorkload.runPrep(NightPrimWorkload.Step(st.id, st.op, st.inp, st.out, st.args), r)
                return true
            }
            else -> return false
        }
        r.map[st.out!!] = out
        return true
    }

    private fun packed(r: NightPrimWorkload.Regs, n: String): LongArray =
        r.map.getOrPut("$n#packed") { Cv.packBits(r.mask(n)) } as LongArray

    /** 吃列打包輸入的原語：輸入先打包好放進暫存器（不算進任何一邊的時間）。 */
    private val packedInput = setOf("erodeBits", "unpackBits", "unpackBitsNot", "notBits", "ccRunsBits")

    /** Kotlin 版跑一次原語。位元格式的輸入（列打包）先在暫存器裡打包好，不算進時間。 */
    @JvmStatic
    fun runKotlin(st: Step, r: NightPrimWorkload.Regs): Any {
        val m = { r.mask(st.inp) }
        return when (st.op) {
            "erodePacked" -> Cv.erodePacked(m(), st.kernel!!)
            "closePacked" -> Cv.closePacked(m(), st.kernel!!)
            "openPacked" -> Cv.openPacked(m(), st.kernel!!)
            "erodeBits" -> m().let { x -> Cv.erodeBits(packed(r, st.inp), x.w, x.h, st.kernel!!) }
            "close" -> Cv.close(m(), st.kernel!!)
            "open" -> Cv.open(m(), st.kernel!!)
            "dilateRectSep" -> Cv.dilateRectSep(m(), st.args[0], st.args[1])
            "closeRectSep" -> Cv.closeRectSep(m(), st.args[0])
            "distanceSq" -> Cv.distanceSq(m())
            "distanceChamfer" -> Cv.distanceChamfer(m(), st.args[0])
            "chamfer5Padded" -> Cv.chamfer5Padded(m())
            "holes" -> Cv.holes(m())
            "totalContourLength" -> Cv.totalContourLength(m())
            "medianBlurMask" -> Cv.medianBlurMask(m(), st.args[0])
            "geodesicGrow" -> Cv.geodesicGrow(m(), r.mask(st.inp2!!), st.args[0], st.args[1])
            "geodesicDistance" -> Cv.geodesicDistance(m(), r.mask(st.inp2!!), st.args[0], st.args[1])
            "resizeNearest" -> Cv.resizeNearest(m(), st.args[0], st.args[1])
            "resizeAreaOf" -> (r.map.getValue(st.inp) as FImg).let { f -> Cv.resizeAreaOf(f.w, f.h, st.args[0], st.args[1]) { f.data[it] } }
            "ring.packBits" -> Ring.packBits(m())
            "packBits" -> Cv.packBits(m())
            "packBitsNot" -> Cv.packBitsNot(m())
            "unpackBits" -> m().let { x -> Cv.unpackBits(packed(r, st.inp), x.w, x.h) }
            "unpackBitsNot" -> m().let { x -> Cv.unpackBitsNot(packed(r, st.inp), x.w, x.h) }
            "notBits" -> m().let { x -> packed(r, st.inp).copyOf().also { Cv.notBitsInPlace(it, x.w, x.h) } }
            "ccRunsBits" -> m().let { x -> NightNativeBin.ccRunsBitsKotlin(packed(r, st.inp), x.w, x.h, st.args[0]) }
            else -> error("不是原語：${st.op}")
        }
    }

    /** C++ 版跑一次原語。 */
    @JvmStatic
    fun runNative(st: Step, r: NightPrimWorkload.Regs): NightNativePrims.Timed<out Any> {
        val m = { r.mask(st.inp) }
        val B = NightNativeBin
        return when (st.op) {
            "erodePacked" -> B.erodePacked(m(), st.kernel!!)
            "closePacked" -> B.closePacked(m(), st.kernel!!)
            "openPacked" -> B.openPacked(m(), st.kernel!!)
            "erodeBits" -> m().let { x -> B.erodeBits(packed(r, st.inp), x.w, x.h, st.kernel!!) }
            "close" -> B.close(m(), st.kernel!!)
            "open" -> B.open(m(), st.kernel!!)
            "dilateRectSep" -> B.dilateRectSep(m(), st.args[0], st.args[1])
            "closeRectSep" -> B.closeRectSep(m(), st.args[0])
            "distanceSq" -> B.distanceSq(m())
            "distanceChamfer" -> B.distanceChamfer(m(), st.args[0])
            "chamfer5Padded" -> B.chamfer5Padded(m())
            "holes" -> B.holes(m())
            "totalContourLength" -> B.totalContourLength(m())
            "medianBlurMask" -> B.medianBlurMask(m(), st.args[0])
            "geodesicGrow" -> B.geodesicGrow(m(), r.mask(st.inp2!!), st.args[0], st.args[1])
            "geodesicDistance" -> B.geodesicDistance(m(), r.mask(st.inp2!!), st.args[0], st.args[1])
            "resizeNearest" -> B.resizeNearest(m(), st.args[0], st.args[1])
            "resizeAreaOf" -> B.resizeAreaOf(r.map.getValue(st.inp) as FImg, st.args[0], st.args[1])
            "ring.packBits" -> B.ringPackBits(m())
            "packBits" -> B.packBits(m())
            "packBitsNot" -> B.packBitsNot(m())
            "unpackBits" -> m().let { x -> B.unpackBits(packed(r, st.inp), x.w, x.h) }
            "unpackBitsNot" -> m().let { x -> B.unpackBitsNot(packed(r, st.inp), x.w, x.h) }
            "notBits" -> m().let { x -> B.notBits(packed(r, st.inp), x.w, x.h) }
            "ccRunsBits" -> m().let { x -> B.ccRunsBits(packed(r, st.inp), x.w, x.h, st.args[0]) }
            else -> error("不是原語：${st.op}")
        }
    }

    /** 兩個結果逐位元相同（Float／Double 比原始位元；陣列逐值）。 */
    @JvmStatic
    fun same(a: Any, b: Any): Boolean = when {
        a is LongArray && b is LongArray -> a.contentEquals(b)
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is Double && b is Double -> a.toRawBits() == b.toRawBits()
        else -> NightPrimWorkload.same(a, b)
    }

    /** 一步的結果：[kotlinNs]／[nativeNs]＝各 [repeat] 次的中位數；[identical]＝每一次都逐位元相同。 */
    class StepResult(
        @JvmField val step: Step,
        @JvmField val kotlinNs: Long,
        @JvmField val nativeNs: Long,
        @JvmField val identical: Boolean,
        @JvmField val nativePeakBytes: Long,
    )

    /** 跑整張表（Kotlin 與 C++ 交錯各跑 [repeat] 次，比位元、取時間中位數）；[onStep] 每步跑完呼叫一次。同 [NightPrimWorkload.differential]。 */
    @JvmStatic
    @JvmOverloads
    fun differential(gray: Gray, seg: Mask, charMask: Mask, repeat: Int = 3, onStep: ((StepResult) -> Unit)? = null): List<StepResult> {
        val regs = NightPrimWorkload.Regs(gray, seg, charMask)
        val out = ArrayList<StepResult>()
        for (st in program(gray.w, gray.h)) {
            if (runPrep(st, regs)) continue
            if (st.op in packedInput) packed(regs, st.inp)
            val tk = LongArray(repeat)
            val tn = LongArray(repeat)
            var identical = true
            var peak = 0L
            for (i in 0 until repeat) {
                val t0 = System.nanoTime()
                val a = runKotlin(st, regs)
                tk[i] = System.nanoTime() - t0
                val b = runNative(st, regs)
                tn[i] = b.nanos
                peak = maxOf(peak, b.peakBytes)
                if (!same(a, b.value)) identical = false
            }
            tk.sort()
            tn.sort()
            val res = StepResult(st, tk[repeat / 2], tn[repeat / 2], identical, peak)
            out += res
            onStep?.invoke(res)
        }
        return out
    }

    /** 一族（[family] null＝合計）的加權總和與倍數。 */
    class Summary(
        @JvmField val family: Family?,
        @JvmField val kotlinWeightedNs: Double,
        @JvmField val nativeWeightedNs: Double,
        @JvmField val identical: Boolean,
    ) {
        /** Kotlin ÷ C++；大於 1＝C++ 比較快。 */
        val ratio: Double get() = kotlinWeightedNs / nativeWeightedNs
    }

    /** 每族一列，最後一列是合計（[identical] 看全部步驟，含權重 0 的）。 */
    @JvmStatic
    fun summarize(results: List<StepResult>): List<Summary> {
        fun sum(fam: Family?): Summary {
            var k = 0.0
            var n = 0.0
            var same = true
            for (r in results) {
                if (fam != null && r.step.family != fam) continue
                k += r.step.weight * r.kotlinNs
                n += r.step.weight * r.nativeNs
                same = same && r.identical
            }
            return Summary(fam, k, n, same)
        }
        return Family.values().map { sum(it) } + sum(null)
    }
}
