package li.joye.yakuyomi.nightread

import org.junit.Test
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 重繪的熱點分析。
 *
 * 真機上一頁 34 秒，其中重繪佔 32 秒——推論只有 1.6 秒。那 32 秒是純 Kotlin 的影像運算，
 * 桌面 Python 同一條管線只要 3.5 秒，差距在 OpenCV 的 SIMD 與我逐像素寫的 Kotlin 之間。
 *
 * 這支測試不驗正確性，只回答「時間花在哪個原語上」。先量原語的單次成本，再量整頁各階段，
 * 兩邊對照就知道該優化誰。JVM 的絕對值與手機不同，但相對比例一致，足以定位熱點。
 */
class ProfileTest {

    private fun readGray(name: String): Gray {
        val stream = javaClass.classLoader!!.getResourceAsStream("page/$name")!!
        val img: BufferedImage = stream.use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        val raster = img.raster
        for (y in 0 until img.height) {
            for (x in 0 until img.width) g.data[y * img.width + x] = raster.getSample(x, y, 0)
        }
        return g
    }

    private fun readMask(name: String): Mask {
        val g = readGray(name)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 })
    }

    private fun readRegions(name: String): List<TextRegion> =
        javaClass.classLoader!!.getResourceAsStream("page/$name")!!
            .bufferedReader().readText().lineSequence()
            .filter { it.isNotBlank() }
            .map { val p = it.trim().split(" ").map(String::toInt); TextRegion(p[0], p[1], p[2], p[3]) }
            .toList()

    private inline fun ms(label: String, body: () -> Unit) {
        val t = System.currentTimeMillis()
        body()
        println("  ${label.padEnd(34)} ${System.currentTimeMillis() - t} ms")
    }

    /** 原語的單次成本：在真實頁面尺寸上，各跑一次要多久。 */
    @Test
    fun primitiveCost() {
        val g = readGray("ch34_011_gray.png")
        val m = g.ge(235)
        println("\n=== 原語單次成本（${g.w}×${g.h} = ${g.data.size / 1_000_000.0} MPx）===")

        ms("distanceL2") { Cv.distanceL2(m) }
        ms("ccStats(8)") { Cv.ccStats(m, 8) }
        ms("dilate ellipse(3)") { Cv.dilate(m, Cv.ellipse(3)) }
        ms("dilate ellipse(9)") { Cv.dilate(m, Cv.ellipse(9)) }
        ms("dilate ellipse(25)") { Cv.dilate(m, Cv.ellipse(25)) }
        ms("dilate ellipse(41)") { Cv.dilate(m, Cv.ellipse(41)) }
        ms("erode ellipse(25)") { Cv.erode(m, Cv.ellipse(25)) }
        ms("open ellipse(25)") { Cv.open(m, Cv.ellipse(25)) }
        ms("medianBlurMask(15)") { Cv.medianBlurMask(m, 15) }
        ms("gaussianBlur(sigma=8)") { Cv.gaussianBlur(g.toF(), 8.0) }
        ms("gaussianBlur(sigma=0.7)") { Cv.gaussianBlur(g.toF(), 0.7) }
        ms("boxBlur(15)") { Cv.boxBlur(g.toF(), 15) }
        ms("blackhat ellipse(7)") { Cv.blackhat(g, Cv.ellipse(7)) }
        ms("holes") { Cv.holes(m) }
        ms("geodesicGrow(10, step=4)") { Cv.geodesicGrow(m, m, 10, 4) }
        ms("geodesicGrow(40, step=5)") { Cv.geodesicGrow(m, m, 40, 5) }
    }

    /**
     * 整頁的階段成本：把 render 的主要步驟在這裡重跑一次，逐段計時。
     *
     * 不用 debug 回呼是因為它只在少數幾處觸發，量不到中間那些昂貴的步驟；這裡直接呼叫
     * internal 的階段函式，順序與 render 一致。
     */
    @Test
    fun stageCost() {
        // 有 fixture 的頁全跑：真機各頁差 8 倍（demo05 3.8s、demo06 31.6s），只量一頁看不到慢頁的病
        for (page in listOf("ch34_011", "demo06", "demo02", "demo04")) {
            if (javaClass.classLoader!!.getResource("page/${page}_gray.png") == null) continue
            stageCostOf(page)
        }
    }

    private fun stageCostOf(page: String) {
        val input = NightReadInput(
            gray = readGray("${page}_gray.png"),
            seg = readMask("${page}_seg.png"),
            regions = readRegions("${page}_regions.txt"),
            charMask = readMask("${page}_char.png"),
            chroma = readGray("${page}_chroma.png"),
        )
        NightRead.render(input)   // 熱身：JIT 編譯過後才是穩定的數字

        println("\n=== 整頁階段成本（$page，${input.gray.w}×${input.gray.h}）===")
        val p = NightReadParams()
        var g = input.gray
        ms("normalizePaper") { g = Regions.normalizePaper(input.gray, input.chroma, p) }
        var snapped = input.charMask
        ms("snapCharMask") { snapped = Regions.snapCharMask(input.charMask, g, p) }
        var charMask = snapped
        ms("smoothCharMask") { charMask = Regions.smoothCharMask(snapped, g, p) }
        var lh = Mask(g.w, g.h)
        var lv = Mask(g.w, g.h)
        ms("frameLineMask") { val r = Regions.frameLineMask(g, p); lh = r.first; lv = r.second }
        var wc: Regions.WhiteComponents? = null
        ms("classifyWhiteComponents") { wc = Regions.classifyWhiteComponents(g, p) }
        // 拆開看 classifyWhite 的 210 ms 在哪
        val whiteM = g.ge(p.whiteTh)
        ms("  ├ ge(whiteTh)") { g.ge(p.whiteTh) }
        ms("  ├ ccStats(white)") { Cv.ccStats(whiteM, 8) }
        ms("  └ distanceL2(white)") { Cv.distanceL2(whiteM) }
        val comps = wc!!
        var bubble: Regions.BubbleResult? = null
        ms("buildBubbleMask") {
            bubble = Regions.buildBubbleMask(
                g, input.regions, input.seg, comps.cc, comps.gutterIds + comps.panelIds, p,
            )
        }
        ms("thickInkAura") { Regions.thickInkAura(g, input.seg, p) }
        ms("plan(sticker)") {
            Sticker.plan(g, input.chroma, comps, false, input.regions, lh or lv, p)
        }
        // bubbleRest 那段的三個嫌疑：maskOfIds、41px 膨脹、andNot
        val cc = comps.cc
        val ids = bubble!!.cored
        ms("  maskOfIds(cored)") {
            val m = Mask(g.w, g.h)
            val want = BooleanArray(cc.n)
            for (id in ids) if (id in 0 until cc.n) want[id] = true
            for (i in m.data.indices) { val l = cc.labels[i]; if (l > 0 && want[l]) m.data[i] = true }
        }
        ms("  dilate(bubble, 41px)") { Cv.dilate(bubble!!.bubble, Cv.ellipse(p.bubbleRestNear * 2 + 1)) }
        println("  ── 分析小計以上；以下是合成階段 ──")
        val marks = ArrayList<Pair<String, Long>>()
        val t0 = System.currentTimeMillis()
        NightRead.render(input) { stage, _ -> marks.add(stage to System.currentTimeMillis()) }
        var prev = t0
        for ((stage, at) in marks) {
            println("  ${"→ $stage".padEnd(34)} 本段 ${at - prev} ms")
            prev = at
        }

        // 單次計時在 JIT 與 GC 下跳動很大（實測同一份程式碼 1300–1500 ms），取中位數才看得出效果
        val runs = LongArray(7) {
            val t = System.currentTimeMillis()
            NightRead.render(input)
            System.currentTimeMillis() - t
        }
        runs.sort()
        println("  render 七次：${runs.joinToString(" ")} ⇒ 中位數 ${runs[3]} ms")
    }
}
