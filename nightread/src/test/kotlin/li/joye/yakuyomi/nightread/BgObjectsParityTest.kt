package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * 「更多」背景物件規則（[BgObjects]，規則版本 3）與研究端 `research/nightread_obj.py` 逐位元相同。fixture 由
 * `research/make_obj_fixture.py` 產：
 *
 * - **原語**（resources/obj/）：整數高斯核與 Q16 模糊、8 方向線核位置、字筆畫／效果線區／閃光的亮度查表、cv2.Canny（關 IPP）、
 *   cv2.medianBlur 7、5×5 chamfer（關 IPP）、二次曲面殘差（正規方程＋固定順序消去，取樣步長 1 與 2）；效果線（[EffectLines]）的
 *   半角方向、Zhang–Suen 細化與直分支（ch34_015 一塊）。
 * - **整頁 ch34_010**（resources/page/ch34_010_*）：這頁同時有亮背景區塗黑（裁定 3 的 A3 白地板維持黑）與否決（A4 牆板窄條、
 *   A1 牆回灰）；有閃光、沒有效果線族。**整頁 ch34_015**：有效果線族（裁定 2 A：上排右格頂的集中線塗黑、線留亮），否決（A1 壁燈）
 *   走效果線的例外判斷。整頁量測只吃分析的遮罩（人物、泡、字、格框線），與研究端逐像素比；否決與亮背景區是**函式層**：餵研究端
 *   那一邊的輸入（多塗的塊與只塗標準時的「已經黑」、亮背景區的「已經黑」）——兩邊的貼紙層本來就有已知差異（核心填色的距離；
 *   版本 2 起就有），47 頁裡只有 c371_015 一頁因此差 20 px（見 docs/DECISIONS.md「「更多」背景物件規則」）。
 *
 * 47 頁（含真機頁）的整頁 parity 在研究端 scratch harness 跑（同 DECISIONS）。
 */
class BgObjectsParityTest {

    private fun stream(name: String) = javaClass.classLoader!!.getResourceAsStream(name)
        ?: error("缺 fixture：$name — 先跑 research/make_obj_fixture.py")

    private fun text(name: String) = stream(name).bufferedReader().readText()

    private fun readGray(name: String): Gray {
        val img: BufferedImage = stream(name).use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        val raster = img.raster
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String): Mask {
        val g = readGray(name)
        return Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 })
    }

    private fun readBin(name: String): Pair<IntArray, ByteBuffer> {
        val b = ByteBuffer.wrap(stream(name).use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN)
        return intArrayOf(b.int, b.int) to b
    }

    private fun assertMask(tag: String, want: Mask, got: Mask) {
        assertEquals("$tag 尺寸", want.w, got.w)
        var diff = 0
        for (i in want.data.indices) if (want.data[i] != got.data[i]) diff++
        println("  $tag：研究端 ${want.count()} px、Kotlin ${got.count()} px、不同 $diff px")
        assertEquals("$tag 逐像素", 0, diff)
    }

    @Test
    fun gaussKernelsAndBlur() {
        for (line in text("obj/gauss_w.txt").lines().filter { it.isNotBlank() }) {
            val p = line.trim().split(" ")
            val want = p.drop(1).map(String::toInt).toIntArray()
            assertArrayEquals("σ ${p[0]} 的整數核", want, Cv.gaussW(p[0].toDouble()))
        }
        val g = readGray("obj/prim_gray.png")
        for (s in listOf(1.5, 5.0)) {
            val (wh, b) = readBin("obj/prim_gauss_$s.bin")
            assertEquals(g.w, wh[0])
            val want = IntArray(wh[0] * wh[1]) { b.int }
            assertArrayEquals("σ $s 的 Q16 模糊", want, Cv.gaussQ16(g, s).data)
        }
    }

    @Test
    fun lineOffsetsAndTextLut() {
        val rows = text("obj/line_offsets.txt").lines().filter { it.isNotBlank() }
        for (len in listOf(17, 11)) {
            val want = rows.filter { it.startsWith("$len ") }.map { r ->
                r.trim().split(" ").drop(1).flatMap { it.split(",").map(String::toInt) }.toIntArray()
            }
            val got = BgObjects.lineOffsets(len)
            assertEquals(8, got.size)
            for (d in 0 until 8) assertArrayEquals("$len px 線核方向 $d", want[d], got[d])
        }
        val want = text("obj/text_lut.txt").trim().split(" ").map(String::toDouble)
        val got = BgObjects.textLut(16, 240)
        for (v in 0 until 256) assertEquals("字亮度查表 g=$v", want[v], got[v].toDouble(), 0.0)
    }

    @Test
    fun effectLutsAndHalfDir() {
        for ((file, lut) in listOf("obj/fx_lut.txt" to BgObjects.inkLut(16, 170, true), "obj/spark_lut.txt" to BgObjects.inkLut(16, 170, false))) {
            val want = text(file).trim().split(" ").map(String::toDouble)
            for (v in 0 until 256) assertEquals("$file g=$v", want[v], lut[v].toDouble(), 0.0)
        }
        for (line in text("obj/fx_half_dir.txt").lines().filter { it.isNotBlank() }) {
            val f = line.trim().split(" ").map(String::toDouble)
            val u = EffectLines.halfDir(f[0], f[1])
            assertEquals("half_dir(${f[0]}, ${f[1]}) x", f[2].toRawBits(), u[0].toRawBits())
            assertEquals("half_dir(${f[0]}, ${f[1]}) y", f[3].toRawBits(), u[1].toRawBits())
        }
    }

    /** Zhang–Suen 細化與直分支（研究端 nightread_fx.thin／segments）在 ch34_015 細暗線一塊上逐位元相同。 */
    @Test
    fun effectLineThinAndSegments() {
        val inkx = readMask("obj/fx_thin_in.png")
        assertMask("Zhang–Suen 細化", readMask("obj/fx_thin.png"), EffectLines.thin(inkx))
        val want = text("obj/fx_segs.txt").lines().filter { it.isNotBlank() }.map { l -> l.trim().split(" ").map(String::toDouble) }
        val got = EffectLines.segments(inkx, EffectLineParams())
        assertEquals("直分支段數", want.size, got.size)
        for ((k, w) in want.withIndex()) for (c in 0 until 5) assertEquals("第 $k 段欄 $c", w[c].toRawBits(), got[k][c].toRawBits())
        assertTrue("要有直分支", got.isNotEmpty())
    }

    @Test
    fun cannyMedianChamferMatchOpenCv() {
        val g = readGray("obj/prim_gray.png")
        for ((lo, hi) in listOf(15 to 40, 10 to 25)) {
            assertMask("Canny($lo, $hi)", readMask("obj/prim_canny_${lo}_$hi.png"), Cv.canny(g.data, g.w, g.h, 0, lo, hi))
        }
        assertArrayEquals("medianBlur 7", readGray("obj/prim_median7.png").data, Cv.medianBlur8(g, 7).data)
        val (wh, b) = readBin("obj/prim_chamfer5.bin")
        val want = FloatArray(wh[0] * wh[1]) { b.float }
        val t = Cv.chamfer5Padded(g.ge(128))
        val tw = g.w + 4
        for (y in 0 until g.h) for (x in 0 until g.w) {
            assertEquals("chamfer5 ($x, $y)", want[y * g.w + x], t[(y + 2) * tw + x + 2].toFloat() * (1f / 65536f), 0f)
        }
    }

    /** 背景物件規則用的快路與既有的參考實作逐位元相同（隨機影像，含 Q16 量級與邊界）。 */
    @Test
    fun fastMorphologyMatchesReference() {
        val rnd = java.util.Random(20261004)
        for ((w, h) in listOf(97 to 61, 13 to 40, 256 to 9)) {
            val g = Gray(w, h, IntArray(w * h) { rnd.nextInt(255 * 65536 + 1) })
            val lab = Gray(w, h, IntArray(w * h) { if (rnd.nextInt(5) == 0) rnd.nextInt(40) else 0 })
            for (size in listOf(5, 11, 13)) {
                val k = Cv.ellipse(size)
                assertArrayEquals("dilate ${w}x$h e$size", Cv.dilateGrayI(g, k).data, Cv.morphGrayGather(g, k, wantMax = true).data)
                assertArrayEquals("erode ${w}x$h e$size", Cv.erodeGrayI(g, k).data, Cv.morphGrayGather(g, k, wantMax = false).data)
                assertArrayEquals("label dilate ${w}x$h e$size", Cv.dilateGrayI(lab, k).data, Cv.morphGrayGather(lab, k, wantMax = true).data)
                val m = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(3) != 0 })
                assertArrayEquals("erode mask e$size", Cv.erode(m, k).data, Cv.erodePacked(m, k).data)
                assertArrayEquals("close mask e$size", Cv.close(m, k).data, Cv.closePacked(m, k).data)
                assertArrayEquals("open mask e$size", Cv.open(m, k).data, Cv.openPacked(m, k).data)
            }
        }
    }

    @Test
    fun quadfitMatchesResearch() {
        val l = text("obj/quadfit.txt").lines().filter { it.isNotBlank() }
        for (c in 0 until l.size / 4) {
            val h = l[4 * c].split(" ")
            val n0 = h[0].toInt()
            val s = h[1].toInt()
            val want = h[2].toDouble()
            val gq = l[4 * c + 1].split(" ").map(String::toInt).toIntArray()
            val ys = l[4 * c + 2].split(" ").map(String::toInt).toIntArray()
            val xs = l[4 * c + 3].split(" ").map(String::toInt).toIntArray()
            val got = BgObjects.quadfit(gq, ys, xs, n0, s)
            println("  quadfit n0=$n0 s=$s：研究端 $want、Kotlin $got")
            assertEquals("quadfit n0=$n0 逐位元", want.toRawBits(), got.toRawBits())
        }
    }

    private fun input(page: String): NightReadInput {
        val regions = text("page/${page}_regions.txt").lineSequence().filter { it.isNotBlank() }.map {
            val p = it.trim().split(" ").map(String::toInt)
            TextRegion(p[0], p[1], p[2], p[3])
        }.toList()
        return NightReadInput(
            gray = readGray("page/${page}_gray.png"), seg = readMask("page/${page}_seg.png"), regions = regions,
            charMask = readMask("page/${page}_char.png"), chroma = readGray("page/${page}_chroma.png"),
        )
    }

    /** ch34_010「更多」：整頁量測逐像素、否決與亮背景區函式層逐像素、逐區特徵逐值（有閃光、沒有效果線族）。 */
    @Test
    fun pageCh34010() {
        val (veto, fill, fx) = checkPage("ch34_010", expectFx = false)
        assertTrue("ch34_010 要有否決（A4 牆板窄條、A1 牆）", veto.any())
        assertTrue("ch34_010 要有亮背景區塗黑（A3 白地板）", fill.any())
        assertTrue("ch34_010 沒有效果線區", !fx.any())
    }

    /** ch34_015「更多」：效果線族（上排右格頂的集中線）→ 效果墨、地盤、成員線、效果線區塗法逐像素；否決走效果線的例外判斷。 */
    @Test
    fun pageCh34015EffectLines() {
        val (veto, _, fx) = checkPage("ch34_015", expectFx = true)
        assertTrue("ch34_015 要有否決（A1 壁燈）", veto.any())
        assertTrue("ch34_015 要有效果線區塗黑", fx.any())
    }

    /** 回傳（否決、亮背景區塗黑、效果線區）。 */
    private fun checkPage(page: String, expectFx: Boolean): Triple<Mask, Mask, Mask> {
        val p = NightTier.L3.apply()
        val a = NightRead.analyze(input(page), p, null, null, shared = false)
        val diag = HashMap<String, Any>()
        diag["obj"] = true
        val ctx = BgObjects.context(a.g, a.charMask, a.charRaw, a.bubbleUntrim, a.seg, a.frame, p.obj, diag)
        val keys = mutableListOf("obj_ink" to "ink", "obj_bright" to "bright", "obj_can4" to "can4", "obj_tone" to "tone",
            "obj_light" to "light", "obj_E" to "E", "obj_Ev2" to "Ev2", "obj_longz" to "longz", "obj_X3" to "X3", "obj_spark" to "spark")
        if (expectFx) keys += listOf("obj_fxe" to "fxe", "obj_fxe_t" to "fxe_t", "obj_terr" to "terr", "obj_memline" to "memline")
        for ((key, file) in keys) assertMask("$page 量測 $file", readMask("page/${page}_obj_$file.png"), diag[key] as Mask)
        assertEquals("$page 效果線場", expectFx, ctx.fx != null)
        // 否決（函式層）
        val veto = BgObjects.vetoBlocks(a.g, readMask("page/${page}_obj_extra.png"), readMask("page/${page}_obj_stddark.png"),
            ctx, a.charMask, a.charRaw, a.bubbleUntrim, a.frame, a.regions, p.obj, null)
        assertMask("$page 否決", readMask("page/${page}_obj_veto.png"), veto)
        // 亮背景區（函式層：餵研究端的「已經黑」）
        val out = FImg(a.g.w, a.g.h, FloatArray(a.g.data.size) { 128f })
        val d2 = HashMap<String, Any>()
        BgObjects.lightFill(out, a.g, ctx, a.charMask, a.charRaw, a.bubbleUntrim, a.frame, a.seg, a.chroma, p.obj, p, d2,
            darkOverride = readMask("page/${page}_obj_dark.png"))
        val empty = Mask(a.g.w, a.g.h)
        val fill = d2["obj_fill"] as Mask? ?: empty
        assertMask("$page 亮背景區塗黑", readMask("page/${page}_obj_fill.png"), fill)
        assertMask("$page 描亮邊", readMask("page/${page}_obj_band.png"), d2["obj_band"] as Mask? ?: empty)
        assertMask("$page 字", readMask("page/${page}_obj_txt.png"), d2["obj_txt"] as Mask? ?: empty)
        val fxp = d2["obj_fxpaint"] as Mask
        assertMask("$page 效果線區", readMask("page/${page}_obj_fxpaint.png"), fxp)
        // 逐區特徵（研究端 raw：nl nc nt ae fit thick csum marks ok）
        @Suppress("UNCHECKED_CAST")
        val rows = d2["obj_rows"] as List<String>
        val want = text("page/${page}_obj_rows.txt").lines().filter { it.isNotBlank() }
        assertEquals("$page 區數", want.size, rows.size)
        val re = Regex("""\[(\d+),(\d+),(\d+),(\d+)] area=(\d+) nl=(\d+) nc=(\d+) nt=(\d+) ae=(\d+) fit=(\S+) thick=(\S+) csum=(\d+) marks=(\d+) ok=(\w+)(.*)""")
        val got = rows.map { re.matchEntire(it)!!.groupValues.drop(1) }.associateBy { it.take(4).joinToString(" ") }
        for (line in want) {
            val f = line.trim().split(" ")
            val k = got[f.take(4).joinToString(" ")] ?: error("$page 少了區 ${f.take(4)}")
            for (j in listOf(4, 5, 6, 7, 8, 11, 12, 13)) assertEquals("$page 區 ${f.take(4)} 欄 $j", f[j], k[j])
            assertEquals("$page 區 ${f.take(4)} fit 逐位元", f[9].toDouble().toRawBits(), k[9].toDouble().toRawBits())
            assertEquals("$page 區 ${f.take(4)} thick", f[10].toDouble().toFloat(), k[10].toFloat(), 0f)
        }
        // 效果線區的判定（研究端 _fx_region：ink fe nmem 場數 fxa nc ae fit）
        val fxRe = Regex(""" fxink=(\d+) fxfe=(\d+) fxnmem=(\d+) fams=\[([^\]]*)] fxa=(\w+)(?: fxnc=(\d+) fxae=(\d+) fxfit=(\S+))?""")
        val gotFx = got.mapNotNull { (key, g) -> fxRe.matchEntire(g[14])?.let { key to it.groupValues.drop(1) } }.toMap()
        val wantFx = if (expectFx) text("page/${page}_obj_fxrows.txt").lines().filter { it.isNotBlank() } else emptyList()
        assertEquals("$page 效果線區判定的區數", wantFx.size, gotFx.size)
        for (line in wantFx) {
            val f = line.trim().split(" ")
            val k = gotFx[f.take(4).joinToString(" ")] ?: error("$page 少了效果線區判定 ${f.take(4)}")
            assertEquals("$page 區 ${f.take(4)} 效果墨", listOf(f[4], f[5], f[6]), listOf(k[0], k[1], k[2]))
            assertEquals("$page 區 ${f.take(4)} 場數", f[7].toInt(), if (k[3].isBlank()) 0 else k[3].split(",").size)
            assertEquals("$page 區 ${f.take(4)} fxa", f[8], k[4])
            if (f[9] != "-1") {
                assertEquals("$page 區 ${f.take(4)} 調子邊", listOf(f[9], f[10]), listOf(k[5], k[6]))
                assertEquals("$page 區 ${f.take(4)} fit 逐位元", f[11].toDouble().toRawBits(), k[7].toDouble().toRawBits())
            } else {
                assertEquals("$page 區 ${f.take(4)} 沒過效果墨門就不量調子邊", "", k[5])
            }
        }
        return Triple(veto, fill, fxp)
    }
}
