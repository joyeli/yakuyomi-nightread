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
 * 逐位元要求：結構元素、形態學、連通元件、洞、最近鄰縮放、中值、測地生長。
 * 容差比對：距離變換（cv2 是 chamfer 近似、我們是精確歐氏）、濾波與 INTER_AREA（浮點）。
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
    fun contourLengthIsCloseToOpenCv() {
        val text = javaClass.classLoader!!.getResourceAsStream("cv/scalars.txt")!!
            .bufferedReader().readText()
        val want = text.lineSequence().first { it.startsWith("contourLength") }
            .split(" ")[1].toDouble()
        val got = Cv.totalContourLength(mask("mask_in.bin"))
        // Moore 追蹤與 cv2 的輪廓抽取在單像素細節上有差；rough 只當排序用，10% 容差夠
        val rel = abs(got - want) / want
        assertTrue("contourLength：cv2=$want kotlin=$got（相對差 ${"%.3f".format(rel)}）", rel < 0.10)
    }
}
