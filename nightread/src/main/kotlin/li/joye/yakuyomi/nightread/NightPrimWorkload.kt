package li.joye.yakuyomi.nightread

/**
 * 5 族 C++ 原語在**真頁**上的差分與計時：一頁的輸入經這張步驟表跑一遍，每個原語的 Kotlin 版與 C++ 版同一個行程比位元、比時間。
 * 手機探針（引擎 sandbox）、JUnit（CppPrimsParityTest）與錄出（NativeDumpTest 的 primpages，給 nr_dump_check 在 qemu／sanitizer 下重放）
 * 用同一張表。
 *
 * **權重**（[Step.weight]）＝一頁產品兩檔（renderTiers L2＋L3，fork 預設亮度）實際呼叫幾次：2026-11-02 在 Cv 上暫時掛計數器，對 ch34_011、
 * demo06、ch34_015、demo05、demo01、demo02 各跑一次（紀錄不進 repo），取中位數再四捨五入。合計倍數＝Σ 權重·Kotlin 時間 ÷ Σ 權重·C++ 時間，
 * 也就是「重繪裡這 5 族那部分」會快幾倍，不是單次呼叫的平均。
 *  - 整頁呼叫的核與 σ 照實際分布；遮罩用頁面導出的代理（密度跟實際中位數同一量級），因為管線中間的遮罩要跑完前面的模組才有。
 *    二值膨脹的時間幾乎只看核，跟密度關係不大。
 *  - 視窗呼叫（泡、灰圈的小框，一頁數百次）併成 240×320 的裁切，權重＝視窗總像素 ÷ 76,800。
 *  - 灰階形態學的輸入照實際：高斯 σ2、σ1.5 的 Q16 輸出（值遠超過 255）與原灰階。
 *  - 不含 erode／open／close、lineMorph、ccRunsBits、gaussQ16Sparse、packBits 單獨的呼叫：它們不在這 5 族（第 3a／3b 階才搬）。
 */
object NightPrimWorkload {

    /** 步驟表的版本：改了步驟或權重就加 1（探針紀錄會印）。 */
    const val VERSION: Int = 1

    enum class Family { DILATE, CC, DIST, GAUSS, MORPH }

    /**
     * 一步。[op]：準備步驟（不計時、只跑 Kotlin）＝`ge`、`lt`（[args] 門檻）、`not`、`crop`（[args] x, y, w, h）；
     * 原語＝`dilatePacked`（[kernel]）、`ccStats`、`ccRuns`（[args] 連通數）、`distanceL2`、`gaussQ16`（[sigma]）、`morphMax`、`morphMin`（[kernel]）。
     * [inp]＝輸入暫存器（一開始有 gray、seg、char）、[out]＝結果要存的暫存器（null＝不留）。
     */
    class Step(
        @JvmField val id: String,
        @JvmField val op: String,
        @JvmField val inp: String,
        @JvmField val out: String?,
        @JvmField val args: IntArray = IntArray(0),
        @JvmField val sigma: Double = 0.0,
        @JvmField val kernel: Kernel? = null,
        @JvmField val weight: Double = 0.0,
    ) {
        val family: Family?
            get() = when (op) {
                "dilatePacked" -> Family.DILATE
                "ccStats", "ccRuns" -> Family.CC
                "distanceL2" -> Family.DIST
                "gaussQ16" -> Family.GAUSS
                "morphMax", "morphMin" -> Family.MORPH
                else -> null
            }
    }

    /** 尺寸 [w]×[h] 的頁的步驟表（只有裁切範圍跟尺寸有關）。 */
    @JvmStatic
    fun program(w: Int, h: Int): List<Step> {
        val s = ArrayList<Step>()
        fun prep(id: String, op: String, inp: String, vararg a: Int) { s += Step(id, op, inp, id, a) }
        fun dil(id: String, inp: String, k: Kernel, weight: Double) { s += Step(id, "dilatePacked", inp, null, kernel = k, weight = weight) }
        fun e(n: Int) = Cv.ellipse(n)
        fun r(n: Int) = Cv.rect(n, n)

        prep("white", "ge", "gray", 235)
        prep("ink", "lt", "gray", 128)
        prep("notSeg", "not", "seg")
        val cx = w / 3
        val cy = h / 3
        val cw = minOf(240, w - cx)
        val ch = minOf(320, h - cy)
        prep("whiteWin", "crop", "white", cx, cy, cw, ch)

        dil("dilate.r3", "ink", r(3), 5.0)
        dil("dilate.e3", "ink", e(3), 4.0)
        dil("dilate.e5", "ink", e(5), 10.0)
        dil("dilate.e7", "seg", e(7), 12.0)
        dil("dilate.r9", "seg", r(9), 3.0)
        dil("dilate.e9", "char", e(9), 3.0)
        dil("dilate.e11", "white", e(11), 3.0)
        dil("dilate.e13", "seg", e(13), 9.0)
        dil("dilate.e15", "ink", e(15), 1.0)
        dil("dilate.e17", "white", e(17), 3.0)
        dil("dilate.e25", "char", e(25), 1.0)
        dil("dilate.e37", "seg", e(37), 1.0)
        dil("dilate.e41", "white", e(41), 1.0)
        dil("dilate.win.e11", "whiteWin", e(11), 320.0)

        fun cc(id: String, op: String, inp: String, conn: Int, weight: Double) { s += Step(id, op, inp, null, intArrayOf(conn), weight = weight) }
        cc("ccStats.white", "ccStats", "white", 8, 4.0)
        cc("ccStats.ink", "ccStats", "ink", 8, 4.0)
        cc("ccStats.seg", "ccStats", "seg", 8, 3.0)
        cc("ccStats.win", "ccStats", "whiteWin", 8, 60.0)
        cc("ccRuns.ink", "ccRuns", "ink", 8, 4.0)
        cc("ccRuns.white", "ccRuns", "white", 8, 3.0)
        cc("ccStats.ink4", "ccStats", "ink", 4, 0.0)   // 4 連通：產品沒用到，權重 0，只比位元

        s += Step("dist.white", "distanceL2", "white", null, weight = 1.0)
        s += Step("dist.notSeg", "distanceL2", "notSeg", null, weight = 1.0)
        s += Step("dist.win", "distanceL2", "whiteWin", null, weight = 60.0)

        s += Step("gauss.2", "gaussQ16", "gray", "gb2", sigma = 2.0, weight = 1.0)
        s += Step("gauss.1.5", "gaussQ16", "gray", "gb15", sigma = 1.5, weight = 1.0)
        s += Step("gauss.4", "gaussQ16", "gray", null, sigma = 4.0, weight = 1.0)
        s += Step("gauss.5", "gaussQ16", "gray", null, sigma = 5.0, weight = 1.0)

        val e11 = e(11)
        val e7 = e(7)
        s += Step("morph.close11.max", "morphMax", "gb2", "cl11", kernel = e11, weight = 1.0)
        s += Step("morph.close11.min", "morphMin", "cl11", null, kernel = e11, weight = 1.0)
        s += Step("morph.open11.min", "morphMin", "gb15", "op11", kernel = e11, weight = 1.0)
        s += Step("morph.open11.max", "morphMax", "op11", null, kernel = e11, weight = 1.0)
        s += Step("morph.close7.max", "morphMax", "gray", "cl7", kernel = e7, weight = 1.0)
        s += Step("morph.close7.min", "morphMin", "cl7", null, kernel = e7, weight = 1.0)
        return s
    }

    /** 暫存器：一開始放頁輸入，步驟的結果照 [Step.out] 存進來。 */
    class Regs(gray: Gray, seg: Mask, charMask: Mask) {
        @JvmField val map = HashMap<String, Any>().apply { put("gray", gray); put("seg", seg); put("char", charMask) }
        fun mask(n: String) = map.getValue(n) as Mask
        fun gray(n: String) = map.getValue(n) as Gray
    }

    /** 跑準備步驟（不是準備步驟回 false）。 */
    @JvmStatic
    fun runPrep(st: Step, r: Regs): Boolean {
        val out: Any = when (st.op) {
            "ge" -> r.gray(st.inp).ge(st.args[0])
            "lt" -> r.gray(st.inp).lt(st.args[0])
            "not" -> r.mask(st.inp).not()
            "crop" -> {
                val m = r.mask(st.inp)
                val (x, y, w, h) = st.args.toList()
                val d = BooleanArray(w * h)
                for (j in 0 until h) System.arraycopy(m.data, (y + j) * m.w + x, d, j * w, w)
                Mask(w, h, d)
            }
            else -> return false
        }
        r.map[st.out!!] = out
        return true
    }

    /** Kotlin 版跑一次原語。 */
    @JvmStatic
    fun runKotlin(st: Step, r: Regs): Any = when (st.op) {
        "dilatePacked" -> Cv.dilatePacked(r.mask(st.inp), st.kernel!!)
        "ccStats" -> Cv.ccStats(r.mask(st.inp), st.args[0])
        "ccRuns" -> NightNativePrims.ccRunsKotlin(r.mask(st.inp), st.args[0])
        "distanceL2" -> Cv.distanceL2(r.mask(st.inp))
        "gaussQ16" -> Cv.gaussQ16(r.gray(st.inp), st.sigma)
        "morphMax" -> Cv.morphGrayGather(r.gray(st.inp), st.kernel!!, true)
        "morphMin" -> Cv.morphGrayGather(r.gray(st.inp), st.kernel!!, false)
        else -> error("不是原語：${st.op}")
    }

    /** C++ 版跑一次原語。 */
    @JvmStatic
    fun runNative(st: Step, r: Regs): NightNativePrims.Timed<out Any> = when (st.op) {
        "dilatePacked" -> NightNativePrims.dilatePacked(r.mask(st.inp), st.kernel!!)
        "ccStats" -> NightNativePrims.ccStats(r.mask(st.inp), st.args[0])
        "ccRuns" -> NightNativePrims.ccRuns(r.mask(st.inp), st.args[0])
        "distanceL2" -> NightNativePrims.distanceL2(r.mask(st.inp))
        "gaussQ16" -> NightNativePrims.gaussQ16(r.gray(st.inp), st.sigma)
        "morphMax" -> NightNativePrims.morphGrayGather(r.gray(st.inp), st.kernel!!, true)
        "morphMin" -> NightNativePrims.morphGrayGather(r.gray(st.inp), st.kernel!!, false)
        else -> error("不是原語：${st.op}")
    }

    /** 兩個結果逐位元相同（Float 比原始位元）。 */
    @JvmStatic
    fun same(a: Any, b: Any): Boolean = when {
        a is Mask && b is Mask -> a.w == b.w && a.h == b.h && a.data.contentEquals(b.data)
        a is Gray && b is Gray -> a.w == b.w && a.h == b.h && a.data.contentEquals(b.data)
        a is FImg && b is FImg -> a.w == b.w && a.h == b.h && sameBits(a.data, b.data)
        a is CC && b is CC -> a.n == b.n && a.labels.contentEquals(b.labels) && a.left.contentEquals(b.left) && a.top.contentEquals(b.top) &&
            a.width.contentEquals(b.width) && a.height.contentEquals(b.height) && a.area.contentEquals(b.area)
        a is NightNativePrims.CcRunsResult && b is NightNativePrims.CcRunsResult -> a.sameAs(b)
        else -> false
    }

    private fun sameBits(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].toRawBits() != b[i].toRawBits()) return false
        return true
    }

    /** 一步的結果：[kotlinNs]／[nativeNs]＝各 [repeat] 次的中位數；[identical]＝每一次都逐位元相同。 */
    class StepResult(
        @JvmField val step: Step,
        @JvmField val kotlinNs: Long,
        @JvmField val nativeNs: Long,
        @JvmField val identical: Boolean,
        @JvmField val nativePeakBytes: Long,
    )

    /**
     * 跑整張表：每個原語 Kotlin 與 C++ 交錯各跑 [repeat] 次（交錯才不會有一邊全落在溫控之後），比位元、取時間中位數。
     * 串接的步驟（高斯 → 形態學）用 Kotlin 的輸出往下接。[onStep] 每步跑完呼叫一次（探針逐步寫紀錄，閃退時才留得下）。
     * 要量穩定的時間，先用 [repeat]＝1 空跑一兩輪暖機（ART 的 JIT），再跑一次正式的。
     */
    @JvmStatic
    @JvmOverloads
    fun differential(gray: Gray, seg: Mask, charMask: Mask, repeat: Int = 3, onStep: ((StepResult) -> Unit)? = null): List<StepResult> {
        val regs = Regs(gray, seg, charMask)
        val out = ArrayList<StepResult>()
        for (st in program(gray.w, gray.h)) {
            if (runPrep(st, regs)) continue
            val tk = LongArray(repeat)
            val tn = LongArray(repeat)
            var identical = true
            var peak = 0L
            var keep: Any? = null
            for (i in 0 until repeat) {
                val t0 = System.nanoTime()
                val a = runKotlin(st, regs)
                tk[i] = System.nanoTime() - t0
                val b = runNative(st, regs)
                tn[i] = b.nanos
                peak = maxOf(peak, b.peakBytes)
                if (!same(a, b.value)) identical = false
                keep = a
            }
            st.out?.let { regs.map[it] = keep!! }
            tk.sort()
            tn.sort()
            val res = StepResult(st, tk[repeat / 2], tn[repeat / 2], identical, peak)
            out += res
            onStep?.invoke(res)
        }
        return out
    }

    /** 一族（[family] null＝5 族合計）的加權總和與倍數。 */
    class Summary(
        @JvmField val family: Family?,
        @JvmField val kotlinWeightedNs: Double,
        @JvmField val nativeWeightedNs: Double,
        @JvmField val identical: Boolean,
    ) {
        /** Kotlin ÷ C++；大於 1＝C++ 比較快。 */
        val ratio: Double get() = kotlinWeightedNs / nativeWeightedNs
    }

    /** 每族一列，最後一列是合計（一頁或多頁的結果都可以，多頁就把清單接起來）。 */
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
