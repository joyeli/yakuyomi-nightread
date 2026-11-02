package li.joye.yakuyomi.nightread

/**
 * 第 3b 階 C++ 原語（[NightNativeGray]）在**真頁**上的差分與計時：一頁的輸入經這張步驟表跑一遍，每個原語的 Kotlin 版與 C++ 版同一個行程
 * 比位元、比時間。用法與 [NightBinWorkload]（第 3a 階）相同：手機探針（引擎 sandbox）先 `differential(…, repeat = 1)` 暖機，
 * 再 `differential(…, repeat = 3) { 逐步寫紀錄 }`，多頁的結果接起來給 [summarize]。
 *
 * **權重**（[Step.weight]）＝一頁產品兩檔（renderTiers L2＋L3，fork 預設亮度）實際呼叫幾次：2026-11-03 用 [Cv.tap] 數 ch34_011、demo06、
 * ch34_015、demo05、demo01、demo02（紀錄不進 repo），取中位數。
 *  - 一步做好幾次的（20 條線、2000 個點），權重＝呼叫次數 ÷ 一步的次數。
 *  - 視窗呼叫（貼紙的墨密度方框平均）併成 240×320 的裁切，權重＝視窗總像素 ÷ 76,800。
 *  - 一頁的中位數是 0 的（gaussQ16Sparse、medianRows 只出現在兩頁；細線、medianBlur8、cv2 版灰階形態學不在產品兩檔）權重 0：只比位元。
 *  - 不在表裡：npSum（一頁約 4,400 次、合計約 270 萬個 double）、eigh2（約 2,100 次）、gaussW（約 6 次）——數值核心，第 1 階起探針的數值自測
 *    就整表比過；這裡只比會花時間的影像原語。
 * 合計倍數＝Σ 權重·Kotlin 時間 ÷ Σ 權重·C++ 時間；只當參考（第 3b 階的關卡是「全部相同」）。
 *
 * **FMA 敏感**（[Step.fmaSensitive]）：把 Float 乘加合成 FMA 會不會改變這一步的結果。只有可分離濾波（gaussianBlur、boxBlur）有浮點乘加，
 * 而且都敏感：輸入是 0／1 也一樣（橫向的乘積精確，直向乘的卻是橫向算出的浮點和）。其他步驟是整數運算；繪圖的 dx·dx＋dy·dy 在頁面座標下
 * 是精確整數，也不敏感。CppGrayParityTest 用 Math.fma 版的 Kotlin 逐步核對這個標記。
 */
object NightGrayWorkload {

    /** 步驟表的版本：改了步驟或權重就加 1（探針紀錄會印）。 */
    const val VERSION: Int = 1

    /** GMORPH＝灰階形態學；FILTER＝可分離濾波；EDGE＝Canny 與 uint8 中值；DRAW＝線與多邊形；SPARSE＝整數高斯的單點與取樣版；NUMPY＝最大值濾波與排序。 */
    enum class Family { GMORPH, FILTER, EDGE, DRAW, SPARSE, NUMPY }

    /**
     * 一步。準備步驟（不計時、只跑 Kotlin）＝[NightPrimWorkload] 的 `ge`、`lt`、`not`、`crop`，加 `toF`（灰階 → 浮點）、`maskF`（遮罩 → 0／1 浮點）、
     * `half`（浮點影像 resizeArea 到一半）、`q16`（gaussQ16，[args] σ×10）。原語的 [op] 與參數見 [runKotlin]。
     */
    class Step(
        @JvmField val id: String,
        @JvmField val op: String,
        @JvmField val inp: String,
        @JvmField val out: String?,
        @JvmField val args: IntArray = IntArray(0),
        @JvmField val kernel: Kernel? = null,
        @JvmField val weight: Double = 0.0,
        @JvmField val family: Family? = null,
        @JvmField val fmaSensitive: Boolean = false,
    )

    /** 尺寸 [w]×[h] 的頁的步驟表（裁切範圍、線、點、累加陣列的大小跟頁的尺寸有關）。 */
    @JvmStatic
    fun program(w: Int, h: Int): List<Step> {
        val s = ArrayList<Step>()
        fun prep(id: String, op: String, inp: String, vararg a: Int) { s += Step(id, op, inp, id, a) }
        fun st(id: String, fam: Family, op: String, inp: String, weight: Double, k: Kernel? = null, a: IntArray = IntArray(0), fma: Boolean = false) {
            s += Step(id, op, inp, null, a, k, weight, fam, fma)
        }
        val cx = w / 3
        val cy = h / 3
        val cw = minOf(240, w - cx)
        val ch = minOf(320, h - cy)
        prep("ink", "lt", "gray", 128)
        prep("inkF", "maskF", "ink")
        prep("inkWin", "crop", "ink", cx, cy, cw, ch)
        prep("inkWinF", "maskF", "inkWin")
        prep("grayF", "toF", "gray")
        prep("halfF", "half", "grayF")
        prep("q20", "q16", "gray", 20)
        prep("q40", "q16", "gray", 40)

        val G = Family.GMORPH
        st("blackhat.e7", G, "blackhat", "gray", 1.0, Cv.ellipse(7))
        st("dilateGray.e7", G, "dilateGray", "gray", 0.0, Cv.ellipse(7))
        st("erodeGray.e7", G, "erodeGray", "gray", 0.0, Cv.ellipse(7))
        st("dilateGrayI.q20.e11", G, "dilateGrayI", "q20", 0.0, Cv.ellipse(11))
        st("erodeGrayI.q20.e11", G, "erodeGrayI", "q20", 0.0, Cv.ellipse(11))

        val F = Family.FILTER
        st("gaussianBlur.inkF.s0.7", F, "gaussianBlur", "inkF", 2.0, a = intArrayOf(7), fma = true)
        st("gaussianBlur.halfF.s4", F, "gaussianBlur", "halfF", 1.0, a = intArrayOf(40), fma = true)
        st("boxBlur.inkWinF.15", F, "boxBlur", "inkWinF", 21.15, a = intArrayOf(15), fma = true)

        val E = Family.EDGE
        st("canny.q20.15.40", E, "canny", "q20", 1.0, a = intArrayOf(16, 15, 40))
        st("canny.q40.10.25", E, "canny", "q40", 1.0, a = intArrayOf(16, 10, 25))
        st("medianBlur8.7", E, "medianBlur8", "gray", 0.0, a = intArrayOf(7))

        val D = Family.DRAW
        st("lines.th3", D, "lines", "", 33.5 / LINES, a = lineSegs(w, h, 3))
        st("lines.th7", D, "lines", "", 71.0 / LINES, a = lineSegs(w, h, 7))
        st("lines.th17", D, "lines", "", 27.5 / LINES, a = lineSegs(w, h, 17))
        st("lines.th1", D, "lines", "", 0.0, a = lineSegs(w, h, 1))
        st("fillPoly.corridor", D, "fillPoly", "", 3.5, a = corridor(w, h))

        val S = Family.SPARSE
        st("gaussAt.s1", S, "gaussAt", "gray", 2006.5 / POINTS, a = intArrayOf(10))
        st("gaussQ16Sparse.gray.s2.5", S, "gaussQ16Sparse", "gray", 0.0, a = intArrayOf(25, 0, cx, cy, cw, ch))
        st("gaussQ16Sparse.median7.s3", S, "gaussQ16Sparse", "gray", 0.0, a = intArrayOf(30, 7, cx, cy, cw, ch))
        st("medianRows.7", S, "medianRows", "gray", 0.0, a = intArrayOf(7, cx, cy, cw, minOf(ch, 64)))

        val N = Family.NUMPY
        st("maxFilterWrapRows.360", N, "maxFilterWrapRows", "gray", 1.0, a = intArrayOf(360, houghCols(w, h), 3, 6))
        st("npArgsort", N, "npArgsort", "gray", 1.0, a = intArrayOf(4565))
        return s
    }

    /** 一步畫幾條線、取幾個點（權重除以這個）。 */
    const val LINES: Int = 20
    const val POINTS: Int = 2000

    /** 20 條線（橫、直、斜、短斜，端點有的在頁外，像格框線與延長線），每段 x0, y0, x1, y1, thickness。 */
    @JvmStatic
    fun lineSegs(w: Int, h: Int, th: Int): IntArray {
        val a = IntArray(5 * LINES)
        for (i in 0 until LINES) {
            val o = 5 * i
            when (i % 4) {
                0 -> { a[o] = -20; a[o + 1] = h * (i + 1) / (LINES + 1); a[o + 2] = w + 20; a[o + 3] = h * (i + 1) / (LINES + 1) + i % 3 }
                1 -> { a[o] = w * (i + 1) / (LINES + 1); a[o + 1] = -20; a[o + 2] = w * (i + 1) / (LINES + 1) - i % 5; a[o + 3] = h + 20 }
                2 -> { a[o] = 0; a[o + 1] = h * i / LINES; a[o + 2] = w - 1; a[o + 3] = h * (LINES - i) / LINES }
                else -> { a[o] = w * i / LINES; a[o + 1] = h / 2; a[o + 2] = w * i / LINES + w / 7; a[o + 3] = h / 2 + h / 9 }
            }
            a[o + 4] = th
        }
        return a
    }

    /** 走廊（格溝那種細長四邊形，橫跨頁寬）：xs 四個、ys 四個。 */
    @JvmStatic
    fun corridor(w: Int, h: Int): IntArray =
        intArrayOf(w / 10, w * 9 / 10, w * 9 / 10, w / 10, h / 3, h / 3 + 5, h / 3 + 25, h / 3 + 20)

    /** 霍夫累加的欄數（ρ 的範圍：兩倍頁對角線＋1）。 */
    @JvmStatic
    fun houghCols(w: Int, h: Int): Int = 2 * Math.ceil(Math.sqrt(w.toDouble() * w + h.toDouble() * h)).toInt() + 1

    /** 跑準備步驟（不是準備步驟回 false）。 */
    @JvmStatic
    fun runPrep(st: Step, r: NightPrimWorkload.Regs): Boolean {
        val out: Any = when (st.op) {
            "toF" -> r.gray(st.inp).toF()
            "maskF" -> r.mask(st.inp).let { m -> FImg(m.w, m.h, FloatArray(m.data.size) { if (m.data[it]) 1f else 0f }) }
            "half" -> (r.map.getValue(st.inp) as FImg).let { f -> Cv.resizeArea(f, maxOf(1, f.w / 2), maxOf(1, f.h / 2)) }
            "q16" -> Cv.gaussQ16(r.gray(st.inp), st.args[0] / 10.0)
            "ge", "lt", "not", "crop" -> {
                NightPrimWorkload.runPrep(NightPrimWorkload.Step(st.id, st.op, st.inp, st.out, st.args), r)
                return true
            }
            else -> return false
        }
        r.map[st.out!!] = out
        return true
    }

    /** 這一步的輸入（不計時，兩邊共用）：畫圖的空白頁、取樣點、need 位元、累加陣列、排序的值。 */
    private class Input(@JvmField val v: Any?, @JvmField val a: IntArray? = null, @JvmField val b: IntArray? = null, @JvmField val need: LongArray? = null)

    private fun input(st: Step, r: NightPrimWorkload.Regs): Input {
        val g = r.gray("gray")
        return when (st.op) {
            "lines", "fillPoly" -> Input(Mask(g.w, g.h))
            "gaussAt" -> {
                val xs = IntArray(POINTS) { (it * 37 + it / 11) % g.w }
                val ys = IntArray(POINTS) { (it * 61 + it / 7) % g.h }
                Input(Cv.gaussW(st.args[0] / 10.0), xs, ys)
            }
            "gaussQ16Sparse" -> {
                val (_, _, x0, y0, cw, ch) = st.args.toList()
                val nw = (g.w + 63) ushr 6
                val need = LongArray(nw * ch)
                for (yy in 0 until ch) for (xx in 0 until cw) {
                    if ((xx + yy) % 5 != 0) continue
                    val x = x0 + xx
                    val o = yy * nw + (x ushr 6)
                    need[o] = need[o] or (1L shl (x and 63))
                }
                Input(Cv.gaussW(st.args[0] / 10.0), need = need)
            }
            "medianRows" -> {
                val (_, x0, y0, cw, nr) = st.args.toList()
                val spec = IntArray(nr * 4)
                for (i in 0 until nr) { spec[4 * i] = y0 + i; spec[4 * i + 1] = 1; spec[4 * i + 2] = x0; spec[4 * i + 3] = x0 + cw }
                Input(null, spec)
            }
            "maxFilterWrapRows" -> {
                val rows = st.args[0]
                val cols = st.args[1]
                val n = g.data.size
                Input(null, IntArray(rows * cols) { ((g.data[((it.toLong() * 31) % n).toInt()] xor (it % 97)) and 0x3ff) })
            }
            "npArgsort" -> {
                val n = g.data.size
                Input(null, IntArray(st.args[0]) { -((g.data[((it.toLong() * 131) % n).toInt()] / 4) + it % 3) })
            }
            else -> Input(null)
        }
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    /** Kotlin 版跑一次（[inp] 是 [input] 準備好的；畫圖的在 [inp] 的空白頁複本上畫）。 */
    private fun runKotlin(st: Step, r: NightPrimWorkload.Regs, inp: Input, fresh: Mask?): Any {
        val g = { r.gray(st.inp) }
        return when (st.op) {
            "blackhat" -> Cv.blackhat(g(), st.kernel!!)
            "dilateGray" -> Cv.dilateGray(g(), st.kernel!!)
            "erodeGray" -> Cv.erodeGray(g(), st.kernel!!)
            "dilateGrayI" -> Cv.dilateGrayI(g(), st.kernel!!)
            "erodeGrayI" -> Cv.erodeGrayI(g(), st.kernel!!)
            "gaussianBlur" -> Cv.gaussianBlur(r.map.getValue(st.inp) as FImg, st.args[0] / 10.0)
            "boxBlur" -> Cv.boxBlur(r.map.getValue(st.inp) as FImg, st.args[0])
            "canny" -> g().let { q -> Cv.canny(q.data, q.w, q.h, st.args[0], st.args[1], st.args[2]) }
            "medianBlur8" -> Cv.medianBlur8(g(), st.args[0])
            "lines" -> fresh!!.also { m -> val a = st.args; var i = 0; while (i < a.size) { Cv.line(m, a[i], a[i + 1], a[i + 2], a[i + 3], a[i + 4]); i += 5 } }
            "fillPoly" -> fresh!!.also { m -> Cv.fillPoly(m, st.args.copyOfRange(0, 4), st.args.copyOfRange(4, 8)) }
            "gaussAt" -> {
                val gg = g()
                val k = inp.v as IntArray
                val xs = inp.a!!
                val ys = inp.b!!
                IntArray(xs.size) { Cv.gaussAt(gg, k, xs[it], ys[it]) }
            }
            "gaussQ16Sparse" -> {
                val gg = g()
                val src = if (st.args[1] == 0) Cv.GrayRows(gg) else Cv.MedianRows(gg, st.args[1])
                val rec = Cv.SparseRec()
                Cv.gaussQ16Sparse(gg.w, gg.h, inp.v as IntArray, inp.need!!, st.args[3], st.args[5], src) { y, v, rs, re, n -> rec.add(y, v, rs, re, n) }
                rec.toArray()
            }
            "medianRows" -> {
                val gg = g()
                val mr = Cv.MedianRows(gg, st.args[0])
                val spec = inp.a!!
                val dst = IntArray(gg.w)
                val out = IntArray(spec.size / 4 * st.args[3])
                var o = 0
                var p = 0
                while (p < spec.size) {
                    mr.fill(spec[p], intArrayOf(spec[p + 2]), intArrayOf(spec[p + 3]), 1, dst)
                    for (x in spec[p + 2] until spec[p + 3]) out[o++] = dst[x]
                    p += 4
                }
                out
            }
            "maxFilterWrapRows" -> Cv.maxFilterWrapRows(inp.a!!, st.args[0], st.args[1], st.args[2], st.args[3])
            "npArgsort" -> Cv.npArgsort(inp.a!!)
            else -> error("不是原語：${st.op}")
        }
    }

    /** C++ 版跑一次（畫圖的 C++ 自己複製空白頁，不計時）。 */
    private fun runNative(st: Step, r: NightPrimWorkload.Regs, inp: Input): NightNativePrims.Timed<out Any> {
        val g = { r.gray(st.inp) }
        val N = NightNativeGray
        return when (st.op) {
            "blackhat" -> N.blackhat(g(), st.kernel!!)
            "dilateGray" -> N.dilateGray(g(), st.kernel!!)
            "erodeGray" -> N.erodeGray(g(), st.kernel!!)
            "dilateGrayI" -> N.dilateGrayI(g(), st.kernel!!)
            "erodeGrayI" -> N.erodeGrayI(g(), st.kernel!!)
            "gaussianBlur" -> N.gaussianBlur(r.map.getValue(st.inp) as FImg, st.args[0] / 10.0)
            "boxBlur" -> N.boxBlur(r.map.getValue(st.inp) as FImg, st.args[0])
            "canny" -> g().let { q -> N.canny(q.data, q.w, q.h, st.args[0], st.args[1], st.args[2]) }
            "medianBlur8" -> N.medianBlur8(g(), st.args[0])
            "lines" -> N.lines(inp.v as Mask, st.args)
            "fillPoly" -> N.fillPoly(inp.v as Mask, st.args.copyOfRange(0, 4), st.args.copyOfRange(4, 8))
            "gaussAt" -> N.gaussAtMany(g(), inp.v as IntArray, inp.a!!, inp.b!!)
            "gaussQ16Sparse" -> g().let { gg -> N.gaussQ16Sparse(gg.w, gg.h, inp.v as IntArray, inp.need!!, st.args[3], st.args[5], gg, st.args[1]) }
            "medianRows" -> N.medianRowsMany(g(), st.args[0], inp.a!!)
            "maxFilterWrapRows" -> N.maxFilterWrapRows(inp.a!!, st.args[0], st.args[1], st.args[2], st.args[3])
            "npArgsort" -> {
                val t0 = System.nanoTime()
                val v = N.npArgsort(inp.a!!)
                NightNativePrims.Timed(v, System.nanoTime() - t0, 0L)   // 沒有原生計時；含 JNI 的複製（陣列很小）
            }
            else -> error("不是原語：${st.op}")
        }
    }

    /** 兩個結果逐位元相同（Float 比原始位元；陣列逐值）。 */
    @JvmStatic
    fun same(a: Any, b: Any): Boolean = when {
        a is IntArray && b is IntArray -> a.contentEquals(b)
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

    /**
     * 跑整張表（Kotlin 與 C++ 交錯各跑 [repeat] 次，比位元、取時間中位數）；[onStep] 每步跑完呼叫一次。同 [NightBinWorkload.differential]。
     * 畫圖的步驟：Kotlin 在計時前先複製一張空白頁、只計畫的時間；C++ 的複製在 JNI 裡、也不計時。
     */
    @JvmStatic
    @JvmOverloads
    fun differential(gray: Gray, seg: Mask, charMask: Mask, repeat: Int = 3, onStep: ((StepResult) -> Unit)? = null): List<StepResult> {
        val regs = NightPrimWorkload.Regs(gray, seg, charMask)
        val out = ArrayList<StepResult>()
        for (st in program(gray.w, gray.h)) {
            if (runPrep(st, regs)) continue
            val inp = input(st, regs)
            val tk = LongArray(repeat)
            val tn = LongArray(repeat)
            var identical = true
            var peak = 0L
            for (i in 0 until repeat) {
                val fresh = (inp.v as? Mask)?.copy()
                val t0 = System.nanoTime()
                val a = runKotlin(st, regs, inp, fresh)
                tk[i] = System.nanoTime() - t0
                val b = runNative(st, regs, inp)
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
