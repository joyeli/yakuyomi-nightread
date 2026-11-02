package li.joye.yakuyomi.nightread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.SplittableRandom

/**
 * 第 3a 階 C++ 影像原語（[NightNativeBin]）對 Kotlin 版（[Cv]、Ring）逐位元比，同一個 JVM。Kotlin 的每一次呼叫（含巢狀：dilate 裡的
 * dilatePacked、packBits、dilateBits…）都由 [CvCallReplay] 當場用 C++ 重算、比位元。
 *  - 寬 1、2、63、64、65、127、128、129、200（＋1351）× 高 1、2、3、7、64、65 × 各種密度與形狀 × 各種核（橢圓、方核、1×n／n×1 線核、
 *    單邊、遠離錨點、空核、偶數尺寸、隨機）；
 *  - 打包格式的輸入含末字超出寬度的雜位元（解包只讀前 w 位、取反要清掉、游程照字讀）；
 *  - 縮放（放大、縮小、同尺寸、到 0、1×1）、測地（BFS／迭代／依成本選、step 0）、中值（r 0–7）；
 *  - 細粒度的小工具（bitRuns、anyBits、reflect101、Ring.set／has、橢圓 0–201、多邊形面積）；
 *  - 0 寬／0 高與其他 Kotlin 會丟例外的輸入：C++ 也要丟。
 * 需要主機原生庫（沒給 `-PnativeLib` 就略過）。
 */
class CppBinParityTest {

    private val edgeWidths = listOf(1, 2, 63, 64, 65, 127, 128, 129, 200)
    private val heights = listOf(1, 2, 3, 7, 64, 65)

    private fun masks(w: Int, h: Int, rnd: SplittableRandom): List<Pair<String, Mask>> {
        val n = w * h
        val out = ArrayList<Pair<String, Mask>>()
        out += "全空" to Mask(w, h)
        out += "全滿" to Mask(w, h, BooleanArray(n) { true })
        for (d in listOf(0.03, 0.3, 0.7, 0.97)) out += "隨機 $d" to Mask(w, h, BooleanArray(n) { rnd.nextDouble() < d })
        out += "邊框" to Mask(w, h, BooleanArray(n) { val x = it % w; val y = it / w; x == 0 || y == 0 || x == w - 1 || y == h - 1 })
        out += "棋盤" to Mask(w, h, BooleanArray(n) { (it % w + it / w) % 2 == 0 })
        out += "右緣一欄" to Mask(w, h, BooleanArray(n) { it % w == w - 1 })
        out += "細線" to Mask(w, h, BooleanArray(n) { val x = it % w; val y = it / w; x % 7 == 3 || (y % 11 == 5 && x % 3 != 0) })
        out += "塊與洞" to blobs(w, h, rnd)
        return out
    }

    private fun blobs(w: Int, h: Int, rnd: SplittableRandom): Mask {
        val m = Mask(w, h)
        repeat(6) {
            val cx = rnd.nextInt(w)
            val cy = rnd.nextInt(h)
            val r = 1 + rnd.nextInt(maxOf(1, minOf(w, h) / 3))
            val hole = rnd.nextInt(2) == 0
            for (y in 0 until h) for (x in 0 until w) {
                val dd = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (dd <= r * r && !(hole && dd <= (r / 2) * (r / 2))) m.data[y * w + x] = true
            }
        }
        return m
    }

    private fun kernel(w: Int, h: Int, f: (Int, Int) -> Boolean) = Kernel(w, h, BooleanArray(w * h) { f(it % w, it / w) })

    private fun kernels(rnd: SplittableRandom): List<Pair<String, Kernel>> {
        val out = ArrayList<Pair<String, Kernel>>()
        for (s in listOf(1, 3, 5, 9, 15, 41)) out += "ellipse $s" to Cv.ellipse(s)
        // 1×n／n×1 實心＝線核（lineMorph），含 1×1、長度跨過一個字
        for ((w, h) in listOf(1 to 1, 2 to 1, 3 to 1, 1 to 5, 65 to 1, 1 to 66, 130 to 1, 4 to 1, 1 to 4)) out += "rect ${w}x$h" to Cv.rect(w, h)
        for ((w, h) in listOf(7 to 3, 3 to 3, 2 to 4)) out += "rect ${w}x$h" to Cv.rect(w, h)
        out += "右側單邊" to kernel(9, 3) { x, _ -> x >= 6 }
        out += "左側單邊" to kernel(9, 3) { x, _ -> x <= 2 }
        out += "遠右" to kernel(141, 1) { x, _ -> x >= 135 }
        out += "遠左" to kernel(141, 1) { x, _ -> x <= 5 }
        out += "1×n 有洞（不是線核）" to kernel(7, 1) { x, _ -> x != 3 }
        out += "n×1 只有一段（不是線核）" to kernel(1, 7) { _, y -> y in 2..4 }
        out += "空核" to kernel(5, 5) { _, _ -> false }
        out += "上下同寬異位" to kernel(7, 5) { x, y -> if (y == 0) x <= 2 else if (y == 4) x >= 4 else y == 2 }
        repeat(3) {
            val kw = 1 + rnd.nextInt(12)
            val kh = 1 + rnd.nextInt(12)
            out += "隨機 ${kw}x$kh" to Kernel(kw, kh, BooleanArray(kw * kh) { rnd.nextInt(3) == 0 })
        }
        return out
    }

    /** 末字超出寬度的位元也是隨機的打包陣列（Kotlin 的 packBits 不會產生，但打包位元的運算要照樣處理）。 */
    private fun dirtyPacked(w: Int, h: Int, rnd: SplittableRandom): LongArray = LongArray(((w + 63) ushr 6) * h) { rnd.nextLong() }

    private fun assertClean(stats: CvCallReplay.Stats, what: String) {
        if (stats.failed > 0) fail("$what：${stats.failed} 個不同，例如\n" + stats.failures.take(10).joinToString("\n"))
    }

    /** Kotlin 丟不丟例外，C++ 也要一樣；都沒丟就比結果。 */
    private fun both(what: String, kotlin: () -> Any?, native: () -> Any?) {
        val want = runCatching(kotlin)
        val got = runCatching(native)
        assertEquals("$what：丟不丟例外（Kotlin ${want.exceptionOrNull()}、C++ ${got.exceptionOrNull()}）", want.isFailure, got.isFailure)
        if (want.isSuccess && !CvCallReplay.same(CvCallReplay.normalize(want.getOrNull()), got.getOrNull())) fail("$what：C++ 與 Kotlin 不同")
    }

    @Test
    fun morphologyEdgeWidths() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(20261102L)
        val ks = kernels(rnd)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "morph") {
            for (w in edgeWidths) for (h in heights) for ((_, m) in masks(w, h, rnd)) {
                for ((_, k) in ks) {
                    Cv.dilate(m, k)
                    Cv.erode(m, k)
                    Cv.open(m, k)
                    Cv.close(m, k)
                    Cv.erodePacked(m, k)
                    Cv.openPacked(m, k)
                    Cv.closePacked(m, k)
                    Cv.erodeBits(Cv.packBits(m), w, h, k)
                }
                Cv.dilate(m, Cv.ellipse(3), 2)
                Cv.erode(m, Cv.rect(1, 3), 3)
                Cv.dilate(m, Cv.ellipse(5), 0)
                for ((kw, kh) in listOf(1 to 1, 3 to 1, 1 to 5, 7 to 7, 2 to 4, 64 to 3, 130 to 1, 0 to 2)) Cv.dilateRectSep(m, kw, kh)
                for (n in listOf(0, 1, 2, 3, 9, 65)) Cv.closeRectSep(m, n)
            }
        }
        assertClean(stats, "二值形態學")
        println("CppBinParityTest 形態學：${stats.total} 次呼叫相同（${stats.summary()}）")
        for (op in listOf("dilate", "erode", "open", "close", "erodePacked", "openPacked", "closePacked", "erodeBits", "notBitsInPlace",
                "packBitsNot", "unpackBitsNot", "dilateRectSep", "closeRectSep")) {
            assertTrue("$op 要真的比到", (stats.counts[op]?.get() ?: 0L) > 0)
        }
    }

    @Test
    fun packedAndRuns() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(77L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "bits") {
            for (w in edgeWidths + 1351) for (h in heights) {
                for ((_, m) in masks(w, h, rnd)) {
                    val p = Cv.packBits(m)
                    Cv.packBitsNot(m)
                    Ring.packBits(m)
                    Cv.unpackBits(p, w, h)
                    Cv.unpackBitsNot(p, w, h)
                    for (conn in listOf(8, 4)) Cv.ccRunsBits(p, w, h, conn)
                }
                // 末字的雜位元：解包只讀前 w 位、取反清掉、游程照字讀（ccRunsBits 會看到寬度外的段）
                repeat(3) {
                    val d = dirtyPacked(w, h, rnd)
                    Cv.unpackBits(d, w, h)
                    Cv.unpackBitsNot(d, w, h)
                    Cv.notBitsInPlace(d.copyOf(), w, h)
                    Cv.erodeBits(d, w, h, Cv.ellipse(3))
                    Cv.dilateBits(d, w, h, Cv.ellipse(5))
                    for (conn in listOf(8, 4)) Cv.ccRunsBits(d, w, h, conn)
                }
            }
        }
        assertClean(stats, "打包位元")
        println("CppBinParityTest 打包位元：${stats.total} 次呼叫相同（${stats.summary()}）")
        // 細粒度的小工具：直接比
        var n = 0
        for (t in 0 until 4000) {
            val nw = 1 + rnd.nextInt(6)
            val off = rnd.nextInt(3)
            val bits = LongArray(off + nw + rnd.nextInt(2)) {
                when (rnd.nextInt(4)) { 0 -> 0L; 1 -> -1L; 2 -> rnd.nextLong(); else -> rnd.nextLong() and rnd.nextLong() and rnd.nextLong() }
            }
            val cap = (64 * nw + 1) / 2 + 1
            val rs = IntArray(cap)
            val re = IntArray(cap)
            val cnt = Cv.bitRuns(bits, off, nw, rs, re)
            val r = NightNativeBin.bitRuns(bits, off, nw)
            if (!rs.copyOf(cnt).contentEquals(r.rs) || !re.copyOf(cnt).contentEquals(r.re)) fail("bitRuns #$t：不同")
            repeat(8) {
                val s = rnd.nextInt(64 * nw + 1)
                val e = rnd.nextInt(64 * nw + 1)
                if (Cv.anyBits(bits, off, s, e) != NightNativeBin.anyBits(bits, off, s, e)) fail("anyBits #$t [$s, $e)：不同")
                val i = rnd.nextInt(64 * bits.size)
                if (Ring.has(bits, i) != NightNativeBin.ringHas(bits, i)) fail("Ring.has #$t $i：不同")
                val want = bits.copyOf().also { Ring.set(it, i) }
                if (!want.contentEquals(NightNativeBin.ringSet(bits, i))) fail("Ring.set #$t $i：不同")
                n += 4
            }
            n++
        }
        assertEquals(false, NightNativeBin.ringHas(null, 5))
        for (nn in 1..70) for (i in -300..300) {
            if (Cv.reflect101(i, nn) != NightNativeBin.reflect101(i, nn)) fail("reflect101($i, $nn)：不同")
            n++
        }
        println("CppBinParityTest 小工具：$n 組相同")
    }

    @Test
    fun ccDistanceAndMisc() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(4242L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "misc") {
            for (w in edgeWidths) for (h in heights) for ((_, m) in masks(w, h, rnd)) {
                Cv.distanceSq(m)
                Cv.distanceChamfer(m, 3)
                Cv.distanceChamfer(m, 5)
                Cv.chamfer5Padded(m)
                Cv.holes(m)
                Cv.totalContourLength(m)
                for (r in 0..7) Cv.medianBlurMask(m, r)
                for ((nw, nh) in listOf(0 to 0, 1 to 1, w * 2 to h, maxOf(1, w / 3) to maxOf(1, h / 2), w + 7 to h * 3 / 2 + 1, w to h)) {
                    Cv.resizeNearest(m, nw, nh)
                }
            }
            // 距離平方超過 2²⁴、DIST_SQ_SAT 的頁很大，這裡只測細長條與沒有背景的列
            for ((w, h) in listOf(20011 to 2, 2 to 20011, 40001 to 1, 1 to 40001)) for (per in listOf(5000, 12)) {
                val m = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(per) != 0 })
                Cv.distanceSq(m)
                Cv.distanceChamfer(m, 5)
                Cv.chamfer5Padded(m)
            }
        }
        assertClean(stats, "距離與其他二值原語")
        println("CppBinParityTest 距離與其他：${stats.total} 次呼叫相同（${stats.summary()}）")
    }

    @Test
    fun geodesic() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(2026L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "geo") {
            for (w in edgeWidths) for (h in listOf(1, 3, 64, 65)) repeat(6) { t ->
                val within = Mask(w, h, BooleanArray(w * h) { rnd.nextInt(10) < 5 + t % 5 })
                val seed = Mask(w, h)
                repeat(1 + rnd.nextInt(4)) { seed.data[rnd.nextInt(w * h)] = true }
                for ((iters, step) in listOf(0 to 5, 1 to 1, 3 to 1, 7 to 3, 20 to 5, 40 to 2, 5 to 0, 9 to 9)) {
                    for (bfs in listOf<Boolean?>(null, true, false)) Cv.geodesicGrow(seed, within, iters, step, bfs)
                    Cv.geodesicDistance(seed, within, maxOf(step, 0), iters)
                }
            }
        }
        assertClean(stats, "測地")
        println("CppBinParityTest 測地：${stats.total} 次呼叫相同（${stats.summary()}）")
    }

    @Test
    fun resizeAndKernels() {
        NativeLib.assumeLoaded()
        val rnd = SplittableRandom(3L)
        val stats = CvCallReplay.Stats()
        CvCallReplay.run(stats, "resize") {
            for ((w, h) in listOf(1 to 1, 1 to 9, 9 to 1, 3 to 5, 63 to 4, 64 to 5, 65 to 4, 127 to 3, 128 to 3, 129 to 3, 37 to 41, 96 to 80)) {
                val f = FImg(w, h, FloatArray(w * h) { if (rnd.nextInt(7) == 0) -0.0f else (rnd.nextDouble() * 300 - 20).toFloat() })
                val g = Gray(w, h, IntArray(w * h) { rnd.nextInt() })
                for ((nw, nh) in listOf(0 to 0, 1 to 1, w * 2 to h, maxOf(1, w / 3) to maxOf(1, h / 2), w + 5 to h + 3, w to h, 7 to 300)) {
                    Cv.resizeArea(f, nw, nh)
                    Cv.resizeAreaOf(w, h, nw, nh) { f.data[it] }
                    Cv.resizeBilinear(f, nw, nh)
                    Cv.resizeNearestGray(g, nw, nh)
                }
                // 多邊形面積
                repeat(20) {
                    val n = rnd.nextInt(9)
                    Cv.polygonArea(FloatArray(n) { rnd.nextInt(-w, 2 * w + 1) + rnd.nextInt(8) / 8f }, FloatArray(n) { rnd.nextInt(-h, 2 * h + 1) + rnd.nextInt(8) / 8f })
                }
            }
        }
        assertClean(stats, "縮放")
        println("CppBinParityTest 縮放：${stats.total} 次呼叫相同（${stats.summary()}）")
        for (s in 0..201) assertEquals("ellipse $s", Cv.ellipse(s).data.toList(), NightNativeBin.ellipse(s).data.toList())
        for (kw in 0..9) for (kh in 0..9) assertEquals("rect $kw×$kh", Cv.rect(kw, kh).data.toList(), NightNativeBin.rect(kw, kh).data.toList())
    }

    /** 0 寬／0 高與 Kotlin 會丟例外的輸入：C++ 丟不丟要一樣，沒丟就比值。 */
    @Test
    fun emptySizesAndThrows() {
        NativeLib.assumeLoaded()
        val ks = kernels(SplittableRandom(5L))
        for ((w, h) in listOf(0 to 0, 0 to 5, 5 to 0, 0 to 1, 1 to 0)) {
            val m = Mask(w, h)
            val f = FImg(w, h)
            val g = Gray(w, h)
            val p = LongArray(((w + 63) ushr 6) * h)
            for ((kd, k) in ks) {
                val tag = "${w}x$h / $kd"
                both("dilate $tag", { Cv.dilate(m, k) }, { NightNativeBin.dilate(m, k).value })
                both("erode $tag", { Cv.erode(m, k) }, { NightNativeBin.erode(m, k).value })
                both("open $tag", { Cv.open(m, k) }, { NightNativeBin.open(m, k).value })
                both("close $tag", { Cv.close(m, k) }, { NightNativeBin.close(m, k).value })
                both("erodePacked $tag", { Cv.erodePacked(m, k) }, { NightNativeBin.erodePacked(m, k).value })
                both("openPacked $tag", { Cv.openPacked(m, k) }, { NightNativeBin.openPacked(m, k).value })
                both("closePacked $tag", { Cv.closePacked(m, k) }, { NightNativeBin.closePacked(m, k).value })
                both("erodeBits $tag", { Cv.erodeBits(p, w, h, k) }, { NightNativeBin.erodeBits(p, w, h, k).value })
            }
            val tag = "${w}x$h"
            both("notBitsInPlace $tag", { p.copyOf().also { Cv.notBitsInPlace(it, w, h) } }, { NightNativeBin.notBits(p, w, h).value })
            both("packBitsNot $tag", { Cv.packBitsNot(m) }, { NightNativeBin.packBitsNot(m).value })
            both("unpackBitsNot $tag", { Cv.unpackBitsNot(p, w, h) }, { NightNativeBin.unpackBitsNot(p, w, h).value })
            both("ring.packBits $tag", { Ring.packBits(m) }, { NightNativeBin.ringPackBits(m).value })
            for (n in listOf(1, 2, 3)) both("closeRectSep $tag / $n", { Cv.closeRectSep(m, n) }, { NightNativeBin.closeRectSep(m, n).value })
            for ((kw, kh) in listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)) both("dilateRectSep $tag / $kw×$kh", { Cv.dilateRectSep(m, kw, kh) }, { NightNativeBin.dilateRectSep(m, kw, kh).value })
            for (conn in listOf(8, 4)) both("ccRunsBits $tag", { Cv.ccRunsBits(p, w, h, conn) }, { NightNativeBin.ccRunsBits(p, w, h, conn).value })
            both("distanceSq $tag", { Cv.distanceSq(m) }, { NightNativeBin.distanceSq(m).value })
            for (s in listOf(3, 5)) both("distanceChamfer $tag / $s", { Cv.distanceChamfer(m, s) }, { NightNativeBin.distanceChamfer(m, s).value })
            both("chamfer5Padded $tag", { Cv.chamfer5Padded(m) }, { NightNativeBin.chamfer5Padded(m).value })
            both("holes $tag", { Cv.holes(m) }, { NightNativeBin.holes(m).value })
            both("totalContourLength $tag", { Cv.totalContourLength(m) }, { NightNativeBin.totalContourLength(m).value })
            for (r in -2..5) both("medianBlurMask $tag / $r", { Cv.medianBlurMask(m, r) }, { NightNativeBin.medianBlurMask(m, r).value })
            for ((nw, nh) in listOf(0 to 0, 1 to 1, 3 to 0, 0 to 3, 4 to 2, w to h)) {
                both("resizeNearest $tag → $nw×$nh", { Cv.resizeNearest(m, nw, nh) }, { NightNativeBin.resizeNearest(m, nw, nh).value })
                both("resizeNearestGray $tag → $nw×$nh", { Cv.resizeNearestGray(g, nw, nh) }, { NightNativeBin.resizeNearestGray(g, nw, nh).value })
                both("resizeArea $tag → $nw×$nh", { Cv.resizeArea(f, nw, nh) }, { NightNativeBin.resizeArea(f, nw, nh).value })
                both("resizeAreaOf $tag → $nw×$nh", { Cv.resizeAreaOf(w, h, nw, nh) { f.data[it] } }, { NightNativeBin.resizeAreaOf(f, nw, nh).value })
                both("resizeBilinear $tag → $nw×$nh", { Cv.resizeBilinear(f, nw, nh) }, { NightNativeBin.resizeBilinear(f, nw, nh).value })
            }
            for ((it, st) in listOf(0 to 5, 3 to 1, 4 to 2, 30 to 1)) for (bfs in listOf<Boolean?>(null, true, false)) {
                both("geodesicGrow $tag / $it,$st,$bfs", { Cv.geodesicGrow(m, m, it, st, bfs) }, { NightNativeBin.geodesicGrow(m, m, it, st, bfs).value })
            }
            both("geodesicDistance $tag", { Cv.geodesicDistance(m, m, 2, 9) }, { NightNativeBin.geodesicDistance(m, m, 2, 9).value })
        }
        // 非空影像上 Kotlin 會丟的：r < 0 的中值、maskSize 不是 3／5、負的核尺寸
        val m = Mask(9, 7, BooleanArray(63) { it % 3 == 0 })
        for (r in listOf(-1, -2, -5)) both("medianBlurMask 9x7 / $r", { Cv.medianBlurMask(m, r) }, { NightNativeBin.medianBlurMask(m, r).value })
        for (s in listOf(0, 1, 4, 7)) both("distanceChamfer / $s", { Cv.distanceChamfer(m, s) }, { NightNativeBin.distanceChamfer(m, s).value })
        both("ellipse -3", { Cv.ellipse(-3) }, { NightNativeBin.ellipse(-3) })
        both("rect -2×3", { Cv.rect(-2, 3) }, { NightNativeBin.rect(-2, 3) })
    }

    private fun res(n: String) = javaClass.classLoader!!.getResource("page/$n")

    private fun readGray(name: String): Gray {
        val img = res(name)!!.openStream().use { javax.imageio.ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = img.raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String) = readGray(name).let { g -> Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 }) }

    /** 手機探針用的步驟表（[NightBinWorkload]）在全部 fixture 頁上：每一步 C++ 與 Kotlin 相同；印每族的倍數（桌面 JVM 只是參考）。 */
    @Test
    fun workloadRealPages() {
        NativeLib.assumeLoaded()
        val pages = listOf("ch34_010", "ch34_011", "ch34_015", "demo01", "demo02", "demo04", "demo05", "demo06", "syn_more", "syn_v4")
        val all = ArrayList<NightBinWorkload.StepResult>()
        for (p in pages) {
            val r = NightBinWorkload.differential(readGray("${p}_gray.png"), readMask("${p}_seg.png"), readMask("${p}_char.png"), repeat = 1)
            val bad = r.filter { !it.identical }.map { it.step.id }
            assertEquals("$p：這些步驟 C++ 與 Kotlin 不同", emptyList<String>(), bad)
            all += r
        }
        println("CppBinParityTest 步驟表：${pages.size} 頁、${all.size} 步全部相同（第 ${NightBinWorkload.VERSION} 版）")
        for (s in NightBinWorkload.summarize(all)) {
            println("  %-6s Kotlin %8.1f ms  C++ %8.1f ms  倍數 %.2f".format(s.family?.name ?: "合計", s.kotlinWeightedNs / 1e6, s.nativeWeightedNs / 1e6, s.ratio))
        }
        val steps = NightBinWorkload.program(1351, 1920)
        assertEquals("每族都要有計入權重的步驟", NightBinWorkload.Family.values().toSet(), steps.filter { it.weight > 0 }.mapNotNull { it.family }.toSet())
        assertEquals("步驟 id 不重複", steps.size, steps.map { it.id }.toSet().size)
    }

    /** 多條執行緒同時跑步驟表（C++ 沒有全域狀態；結果都要跟 Kotlin 相同）。 */
    @Test
    fun workloadConcurrent() {
        NativeLib.assumeLoaded()
        val g = readGray("demo05_gray.png")
        val seg = readMask("demo05_seg.png")
        val chr = readMask("demo05_char.png")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val fs = List(4) { pool.submit<List<NightBinWorkload.StepResult>> { NightBinWorkload.differential(g, seg, chr, repeat = 1) } }
        for (f in fs) assertTrue(f.get().all { it.identical })
        pool.shutdown()
        assertTrue(pool.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES))
    }

    @Test
    fun statsAndErrors() {
        NativeLib.assumeLoaded()
        val m = Mask(129, 7, BooleanArray(129 * 7) { it % 3 == 0 })
        val t = NightNativeBin.erode(m, Cv.ellipse(5))
        assertTrue("耗時要有值", t.nanos > 0)
        assertTrue("峰值至少有打包與輸出：${t.peakBytes}", t.peakBytes >= 129 * 7)
        val k = Cv.ellipse(3)
        expect<IllegalArgumentException> { NightNativeBin.nMask(1, BooleanArray(5), 129, 7, k.data, 3, 3, intArrayOf(1), null) }
        expect<IllegalArgumentException> { NightNativeBin.nMask(1, m.data, 129, 7, null, 0, 0, intArrayOf(1), null) }
        expect<IllegalArgumentException> { NightNativeBin.nMask(1, m.data, 129, 7, k.data, 3, 3, IntArray(0), null) }
        expect<IllegalArgumentException> { NightNativeBin.nMask(99, m.data, 129, 7, k.data, 3, 3, intArrayOf(1), null) }
        expect<IllegalArgumentException> { NightNativeBin.nUnpack(40, LongArray(5), 129, 7, null) }
        expect<IllegalArgumentException> { NightNativeBin.nBits(51, LongArray(21), 129, 7, null, 0, 0, null) }
        expect<IllegalArgumentException> { NightNativeBin.nCcRunsBits(LongArray(3), 129, 7, 8, null) }
        expect<IllegalArgumentException> { NightNativeBin.nGeodesicGrow(m.data, BooleanArray(3), 129, 7, 3, 1, -1, null) }
        expect<IllegalArgumentException> { NightNativeBin.nBitRuns(LongArray(3), 2, 2) }
        expect<IllegalArgumentException> { NightNativeBin.nAnyBits(LongArray(3), 1, 0, 200) }
        expect<IllegalArgumentException> { NightNativeBin.nReflect101(3, 0) }
        expect<IllegalArgumentException> { NightNativeBin.nRingSet(LongArray(2), 128) }
        expect<IllegalArgumentException> { NightNativeBin.nPolygonArea(FloatArray(3), FloatArray(2)) }
        expect<IllegalArgumentException> { NightNativeBin.nResizeF(60, FloatArray(4), 3, 3, 2, 2, null) }
        expect<IllegalArgumentException> { NightNativeBin.nMaskInts(20, m.data, 129, 7, LongArray(1)) }
        // stats 可以是 null
        assertTrue(Cv.distanceSq(m).contentEquals(NightNativeBin.nMaskInts(20, m.data, 129, 7, null)))
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            fail("預期 ${T::class.java.simpleName}，卻是 $e")
        }
        fail("預期 ${T::class.java.simpleName}，卻沒有丟")
    }
}
