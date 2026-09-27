package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Cv.kt 對 OpenCV 的 parity 測試。
 *
 * fixture 由 `research/make_cv_fixtures.py` 產出（真實頁面的 128×128 裁切跑過每個 cv2 原語），
 * 所以這裡比的是「Kotlin 實作 vs cv2 的實際輸出」，不是比對我自己的期望值。
 *
 * 逐位元要求：結構元素、形態學（含位元打包版膨脹）、連通元件、洞、最近鄰縮放、中值、測地生長；格溝偵測用的 cv2.line／fillPoly、
 * chamfer 距離（IPP 關時）、θ 環狀最大值濾波、numpy argsort／成對加總、LAPACK 2×2 特徵分解。
 * 容差比對：精確歐氏距離變換（cv2 是 chamfer 近似）、濾波與 INTER_AREA（浮點）、IPP 版 chamfer。
 */
class CvParityTest {

    private data class Fx(val w: Int, val h: Int, val kind: Int, val bytes: ByteArray)

    private fun load(name: String): Fx {
        val stream = javaClass.classLoader!!.getResourceAsStream("cv/$name")
            ?: error("缺 fixture：cv/$name — 先跑 research/make_cv_fixtures.py")
        val all = stream.use { DataInputStream(it).readBytes() }
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.int
        val h = bb.int
        val kind = bb.int
        val payload = ByteArray(all.size - 12)
        bb.get(payload)
        return Fx(w, h, kind, payload)
    }

    private fun mask(name: String): Mask {
        val f = load(name)
        require(f.kind == 0) { "$name 不是 mask" }
        return Mask(f.w, f.h, BooleanArray(f.w * f.h) { f.bytes[it].toInt() != 0 })
    }

    private fun gray(name: String): Gray {
        val f = load(name)
        require(f.kind == 1) { "$name 不是 gray" }
        return Gray(f.w, f.h, IntArray(f.w * f.h) { f.bytes[it].toInt() and 0xFF })
    }

    private fun floats(name: String): FImg {
        val f = load(name)
        require(f.kind == 2) { "$name 不是 float" }
        val bb = ByteBuffer.wrap(f.bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FImg(f.w, f.h, FloatArray(f.w * f.h) { bb.getFloat(it * 4) })
    }

    private fun assertMaskEquals(tag: String, want: Mask, got: Mask) {
        assertEquals("$tag 尺寸", want.w to want.h, got.w to got.h)
        var diff = 0
        var firstX = -1
        var firstY = -1
        for (i in want.data.indices) {
            if (want.data[i] != got.data[i]) {
                diff++
                if (firstX < 0) { firstX = i % want.w; firstY = i / want.w }
            }
        }
        assertTrue("$tag：$diff/${want.data.size} px 不同，第一處 ($firstX,$firstY)", diff == 0)
    }

    private fun assertFloatsClose(tag: String, want: FImg, got: FImg, tol: Float, maxBadFrac: Double = 0.0) {
        assertEquals("$tag 尺寸", want.w to want.h, got.w to got.h)
        var bad = 0
        var worst = 0f
        for (i in want.data.indices) {
            val d = abs(want.data[i] - got.data[i])
            if (d > worst) worst = d
            if (d > tol) bad++
        }
        val frac = bad.toDouble() / want.data.size
        assertTrue("$tag：$bad/${want.data.size} 超出容差 $tol（最大差 $worst）", frac <= maxBadFrac)
    }

    // ── 結構元素 ─────────────────────────────────────────────────────

    @Test
    fun ellipseMatchesOpenCvRasterisation() {
        for (s in intArrayOf(3, 5, 7, 11, 15, 21, 33)) {
            val want = mask("ellipse_$s.bin")
            val k = Cv.ellipse(s)
            val got = Mask(k.w, k.h, k.data)
            assertMaskEquals("ellipse($s)", want, got)
        }
    }

    // ── 形態學 ───────────────────────────────────────────────────────

    @Test
    fun dilateMatchesOpenCv() {
        val m = mask("mask_in.bin")
        for (s in intArrayOf(3, 7, 15, 25)) {
            assertMaskEquals("dilate($s)", mask("dilate_$s.bin"), Cv.dilate(m, Cv.ellipse(s)))
        }
    }

    /**
     * 位元打包版膨脹（出血格過濾用）：對 cv2 fixture 逐位元，另在各種寬度（不是 64 的倍數、比一字窄、跨多字）與
     * 核（橢圓、方核、長方核、單列／單行、不對稱 run、比影像寬的 run）的亂數遮罩上與 [Cv.dilate] 逐像素相同。
     */
    @Test
    fun dilatePackedMatchesDilate() {
        val m = mask("mask_in.bin")
        for (s in intArrayOf(3, 7, 15, 25)) {
            assertMaskEquals("dilatePacked($s)", mask("dilate_$s.bin"), Cv.dilatePacked(m, Cv.ellipse(s)))
        }
        val rnd = java.util.Random(20260927)
        val kernels = listOf(Cv.ellipse(3), Cv.ellipse(11), Cv.ellipse(13), Cv.ellipse(17), Cv.ellipse(41),
            Cv.rect(5, 5), Cv.rect(9, 9), Cv.rect(7, 1), Cv.rect(1, 7), Cv.rect(3, 5), Cv.rect(129, 3),
            // 不對稱的 run（整段在錨點左側／右側／跨錨點）：區間 OR 的三條分支都要走到
            Kernel(7, 3, BooleanArray(21) { val x = it % 7; val y = it / 7; if (y == 0) x < 2 else if (y == 1) x >= 5 else x in 1..6 }),
            Kernel(141, 1, BooleanArray(141) { it >= 136 }), Kernel(141, 1, BooleanArray(141) { it in 2..8 }))
        for (w in intArrayOf(1, 5, 63, 64, 65, 127, 130, 200)) {
            for (density in doubleArrayOf(0.002, 0.05, 0.5)) {
                val h = 1 + rnd.nextInt(90)
                val r = Mask(w, h, BooleanArray(w * h) { rnd.nextDouble() < density })
                for ((ki, k) in kernels.withIndex()) {
                    assertMaskEquals("dilatePacked(w=$w h=$h d=$density k#$ki)", Cv.dilate(r, k), Cv.dilatePacked(r, k))
                }
            }
        }
    }

    @Test
    fun erodeMatchesOpenCv() {
        val m = mask("mask_in.bin")
        for (s in intArrayOf(3, 7, 15, 25)) {
            assertMaskEquals("erode($s)", mask("erode_$s.bin"), Cv.erode(m, Cv.ellipse(s)))
        }
    }

    @Test
    fun openCloseMatchOpenCv() {
        val m = mask("mask_in.bin")
        for (s in intArrayOf(3, 7, 15, 25)) {
            assertMaskEquals("open($s)", mask("open_$s.bin"), Cv.open(m, Cv.ellipse(s)))
            assertMaskEquals("close($s)", mask("close_$s.bin"), Cv.close(m, Cv.ellipse(s)))
        }
    }

    @Test
    fun dilateIterationsMatchOpenCv() {
        val m = mask("mask_in.bin")
        assertMaskEquals("dilate(rect3, it=2)", mask("dilate_rect3_it2.bin"),
            Cv.dilate(m, Cv.rect(3, 3), iterations = 2))
    }

    @Test
    fun blackhatMatchesOpenCv() {
        val g = gray("gray_in.bin")
        val want = gray("blackhat_7.bin")
        val got = Cv.blackhat(g, Cv.ellipse(7))
        var diff = 0
        for (i in want.data.indices) if (want.data[i] != got.data[i]) diff++
        assertTrue("blackhat(7)：$diff/${want.data.size} px 不同", diff == 0)
    }

    // ── 連通元件 ─────────────────────────────────────────────────────

    @Test
    fun connectedComponentsMatchOpenCv() {
        val m = mask("mask_in.bin")
        for (conn in intArrayOf(4, 8)) {
            val wantLab = floats("cc${conn}_labels.bin")
            val wantSt = floats("cc${conn}_stats.bin")
            val cc = Cv.ccStats(m, conn)
            assertEquals("cc$conn 元件數", wantSt.h, cc.n)
            var diff = 0
            for (i in wantLab.data.indices) {
                if (wantLab.data[i].toInt() != cc.labels[i]) diff++
            }
            assertTrue("cc$conn 標號：$diff px 不同（順序也要一致）", diff == 0)
            for (i in 0 until cc.n) {
                assertEquals("cc$conn[$i].left", wantSt[0, i].toInt(), cc.left[i])
                assertEquals("cc$conn[$i].top", wantSt[1, i].toInt(), cc.top[i])
                assertEquals("cc$conn[$i].width", wantSt[2, i].toInt(), cc.width[i])
                assertEquals("cc$conn[$i].height", wantSt[3, i].toInt(), cc.height[i])
                assertEquals("cc$conn[$i].area", wantSt[4, i].toInt(), cc.area[i])
            }
        }
    }

    // ── 距離變換（cv2 是 chamfer 近似，我們精確 ⇒ 容差）────────────────

    @Test
    fun distanceTransformIsCloseToOpenCv() {
        val m = mask("mask_in.bin")
        val want = floats("dist_l2.bin")
        val got = Cv.distanceL2(m)
        // cv2 的 5×5 chamfer 誤差約 2%；門檻都在 4px 以上，1px 容差綽綽有餘
        assertFloatsClose("distanceL2", want, got, tol = 1.0f, maxBadFrac = 0.002)
    }

    // ── 濾波 ─────────────────────────────────────────────────────────

    @Test
    fun gaussianBlurMatchesOpenCv() {
        val g = gray("gray_in.bin").toF()
        for (sig in doubleArrayOf(0.7, 1.5, 3.0)) {
            val name = "gauss_$sig.bin"
            assertFloatsClose("gaussian($sig)", floats(name), Cv.gaussianBlur(g, sig), tol = 0.02f)
        }
    }

    @Test
    fun boxBlurMatchesOpenCv() {
        val g = gray("gray_in.bin").toF()
        for (k in intArrayOf(3, 15)) {
            assertFloatsClose("box($k)", floats("box_$k.bin"), Cv.boxBlur(g, k), tol = 0.02f)
        }
    }

    // ── 縮放 ─────────────────────────────────────────────────────────

    @Test
    fun resizeAreaMatchesOpenCv() {
        val g = gray("gray_in.bin").toF()
        val want = floats("area_half.bin")
        assertFloatsClose("resizeArea", want, Cv.resizeArea(g, want.w, want.h), tol = 0.02f)
    }

    @Test
    fun resizeNearestMatchesOpenCv() {
        val m = mask("mask_in.bin")
        val want = mask("nearest_double.bin")
        assertMaskEquals("resizeNearest", want, Cv.resizeNearest(m, want.w, want.h))
    }

    // ── 洞 / 中值 / 測地生長 ──────────────────────────────────────────

    @Test
    fun holesMatchOpenCvFloodFill() {
        assertMaskEquals("holes", mask("holes.bin"), Cv.holes(mask("mask_in.bin")))
    }

    @Test
    fun medianBlurMatchesOpenCv() {
        assertMaskEquals("median(15)", mask("median_15.bin"), Cv.medianBlurMask(mask("mask_in.bin"), 15))
    }

    @Test
    fun geodesicGrowMatchesPython() {
        val seed = mask("geodesic_seed.bin")
        val within = mask("mask_in.bin")
        assertMaskEquals("geodesicGrow", mask("geodesic.bin"), Cv.geodesicGrow(seed, within, 10, 4))
        // 迭代與前沿 BFS 兩條實作都要對上 Python
        assertMaskEquals("geodesicGrow(iter)", mask("geodesic.bin"), Cv.geodesicGrow(seed, within, 10, 4, bfs = false))
        assertMaskEquals("geodesicGrow(bfs)", mask("geodesic.bin"), Cv.geodesicGrow(seed, within, 10, 4, bfs = true))
    }

    // ── 純量 ─────────────────────────────────────────────────────────

    @Test
    fun contourLengthMatchesOpenCv() {
        val text = javaClass.classLoader!!.getResourceAsStream("cv/scalars.txt")!!
            .bufferedReader().readText()
        val want = text.lineSequence().first { it.startsWith("contourLength") }
            .split(" ")[1].toDouble()
        val got = Cv.totalContourLength(mask("mask_in.bin"))
        // 逐點復刻 cv2 的 Suzuki 邊界追蹤（RETR_LIST + CHAIN_APPROX_NONE + arcLength 閉合）：輪廓點序列全同，
        // 剩下的差只有 cv2 arcLength 的 float32 累加（~1e-8）。rough 是三檔的決策量，不能再用 10% 容差
        val rel = abs(got - want) / want
        assertTrue("contourLength：cv2=$want kotlin=$got（相對差 ${"%.2e".format(rel)}）", rel < 1e-6)
    }

    // ── 格溝偵測（Separators）用的 cv2／numpy／scipy 原語 ────────────────────────

    private fun text(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("cv/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    /** 疊成一張的多個 S×S 案例切出第 i 個。 */
    private fun slice(big: Mask, i: Int, s: Int): Mask = Mask(s, s, big.data.copyOfRange(i * s * s, (i + 1) * s * s))

    @Test
    fun lineMatchesOpenCv() {
        // cv2.line（LINE_8）：thickness 1 走 LineIterator；>1 是定點平行四邊形＋兩端實心圓；端點可在影像外
        val big = mask("line.bin")
        val s = big.w
        for ((i, l) in text("line_cases.txt").withIndex()) {
            val v = l.trim().split(" ").map(String::toInt)
            val got = Mask(s, s)
            Cv.line(got, v[0], v[1], v[2], v[3], v[4])
            assertMaskEquals("line#$i $l", slice(big, i, s), got)
        }
    }

    @Test
    fun fillPolyMatchesOpenCv() {
        val big = mask("fillpoly.bin")
        val s = big.w
        for ((i, l) in text("fillpoly_cases.txt").withIndex()) {
            val v = l.trim().split(" ").map(String::toInt)
            val got = Mask(s, s)
            Cv.fillPoly(got, IntArray(v.size / 2) { v[2 * it] }, IntArray(v.size / 2) { v[2 * it + 1] })
            assertMaskEquals("fillPoly#$i $l", slice(big, i, s), got)
        }
    }

    @Test
    fun chamferDistanceMatchesOpenCv() {
        val m = mask("mask_in.bin")
        for (k in intArrayOf(3, 5)) {
            val got = Cv.distanceChamfer(m, k)
            // OpenCV 自己的定點 chamfer（IPP 關）：逐位元
            assertFloatsClose("chamfer$k", floats("chamfer$k.bin"), got, tol = 0f)
            // pip 版 opencv 預設走 IPP（浮點權重、路徑不同）：定點權重 62587/65536、89738/65536 對 0.955、1.3693 的
            // 相對誤差 ≤ 5e-6，隨距離累積 ⇒ 用相對容差；門檻決策（格溝的 ≥ 5）附近最近的值是 4.775／5.0629，不受影響
            val ipp = floats("chamfer${k}_ipp.bin")
            var bad = 0
            for (i in ipp.data.indices) if (abs(ipp.data[i] - got.data[i]) > 1e-5f * ipp.data[i] + 1e-6f) bad++
            assertTrue("chamfer$k(ipp)：$bad/${ipp.data.size} 超出相對容差 1e-5", bad == 0)
        }
    }

    @Test
    fun maxFilterWrapMatchesScipy() {
        val a = floats("maxfilt_in.bin")
        val want = floats("maxfilt_out.bin")
        val got = Cv.maxFilterWrapRows(IntArray(a.data.size) { a.data[it].toInt() }, a.h, a.w, 3, 6)
        var diff = 0
        for (i in got.indices) if (got[i] != want.data[i].toInt()) diff++
        assertTrue("maxFilterWrapRows：$diff/${got.size} 不同", diff == 0)
    }

    @Test
    fun argsortMatchesNumpyQuicksort() {
        // numpy 的 introsort 不穩定：同值的順序也要一樣（格溝取峰的截斷吃這個順序）
        val v = floats("argsort_in.bin")
        val want = floats("argsort_out.bin")
        val got = Cv.npArgsort(IntArray(v.data.size) { v.data[it].toInt() })
        var diff = 0
        for (i in got.indices) if (got[i] != want.data[i].toInt()) diff++
        assertTrue("npArgsort：$diff/${got.size} 位置不同", diff == 0)
    }

    @Test
    fun pairwiseSumMatchesNumpy() {
        for (l in text("npsum.txt")) {
            val v = l.trim().split(" ").map(String::toDouble)
            val a = v.drop(1).toDoubleArray()
            assertEquals("np.sum(n=${a.size})", v[0], Cv.npSum(a), 0.0)
        }
    }

    @Test
    fun eigh2MatchesLapack() {
        for (l in text("eigh2.txt")) {
            val v = l.trim().split(" ").map(String::toDouble)
            val got = Cv.eigh2(v[0], v[1], v[2])
            for (k in 0 until 6) assertEquals("eigh2(${v[0]}, ${v[1]}, ${v[2]})[$k]", v[3 + k], got[k], 0.0)
        }
    }
}
