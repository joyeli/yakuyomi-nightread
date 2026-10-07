package li.joye.yakuyomi.nightread

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 淡線外圈貼線形（[BgObjects.pfShape]）的「洞」改游程標號（2026-10-07 加速；原本逐塊整頁 BFS）對改寫前（2f98ce4，下面照抄）逐像素
 * 相同：隨機頁（含碰頁緣、寬不是 64 倍數、單列）、隨機淡線（塊、線、點）、隨機交代過、隨機參數（洞的面積門檻大小都有）與淡記號。
 */
class PfShapeHolesTest {

    private fun dil(m: Mask, r: Int): Mask = if (r <= 0) m.copy() else Cv.dilatePacked(m, Cv.ellipse(2 * r + 1))
    private fun has(b: LongArray, i: Int): Boolean = (b[i ushr 6] ushr (i and 63)) and 1L != 0L
    private fun unpack(b: LongArray, w: Int, h: Int): Mask = Mask(w, h, BooleanArray(w * h) { has(b, it) })

        private fun pfShapeOld(
            gf: Mask, xB: LongArray, mark: BgObjects.PixelTest?, p: ObjectRuleParams, diag: MutableMap<String, Any>? = null,
        ): LongArray {
            val w = gf.w
            val h = gf.h
            val n = w * h
            val old = dil(gf, p.pfHalo)
            val out = Mask(w, h)
            val pc = p.pfClose
            val kc = Cv.ellipse(2 * pc + 1)
            val seen = LongArray((n + 63) ushr 6)
            var q = IntArray(256)
            var gx0 = w; var gy0 = h; var gx1 = -1; var gy1 = -1
            // 逐塊：一條線只留 pfMargin
            for (s0 in 0 until n) {
                if (!gf.data[s0] || Ring.has(seen, s0)) continue
                var qe = 0
                q[qe++] = s0
                Ring.set(seen, s0)
                var qs = 0
                var bx0 = s0 % w; var bx1 = bx0; var by0 = s0 / w; var by1 = by0
                while (qs < qe) {
                    val i = q[qs++]
                    val x = i % w
                    val y = i / w
                    if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                    for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val j = yy * w + xx
                        if (gf.data[j] && !Ring.has(seen, j)) {
                            Ring.set(seen, j)
                            if (qe == q.size) q = q.copyOf(q.size * 2)
                            q[qe++] = j
                        }
                    }
                }
                gx0 = min(gx0, bx0); gy0 = min(gy0, by0); gx1 = max(gx1, bx1); gy1 = max(gy1, by1)
                val pd = max(pc, p.pfHalo) + 1
                val x0 = max(0, bx0 - pd); val y0 = max(0, by0 - pd); val x1 = min(w, bx1 + 1 + pd); val y1 = min(h, by1 + 1 + pd)
                val sw = x1 - x0
                val c = Mask(sw, y1 - y0)
                for (k in 0 until qe) { val i = q[k]; c.data[(i / w - y0) * sw + i % w - x0] = true }
                val r = if (max(bx1 - bx0 + 1, by1 - by0 + 1) < p.pfStub) {
                    p.pfHalo                                   // 短截：不算線（修法 c）
                } else {
                    val ca = if (pc > 0) Cv.closePacked(c, kc).count() else qe
                    if (100L * ca <= p.pfLinePct.toLong() * qe) p.pfMargin else p.pfHalo
                }
                val d = dil(c, r)
                for (yy in y0 until y1) {
                    val b = (yy - y0) * sw - x0
                    for (xx in x0 until x1) if (d.data[b + xx]) out.data[yy * w + xx] = true
                }
                if (p.pfPair > 0) {
                    // 兩塊之間（修法 c）：這塊外擴 pfHalo ∩ 窗裡別的塊（gf ∖ 這塊）外擴 pfPair
                    val pq = p.pfHalo + p.pfPair + 1
                    val rx0 = max(0, bx0 - pq); val ry0 = max(0, by0 - pq); val rx1 = min(w, bx1 + 1 + pq); val ry1 = min(h, by1 + 1 + pq)
                    val rw = rx1 - rx0
                    val rc = Mask(rw, ry1 - ry0)
                    for (k in 0 until qe) { val i = q[k]; rc.data[(i / w - ry0) * rw + i % w - rx0] = true }
                    val ro = Mask(rw, ry1 - ry0)
                    var anyO = false
                    for (yy in ry0 until ry1) {
                        val b = (yy - ry0) * rw - rx0
                        for (xx in rx0 until rx1) if (gf.data[yy * w + xx] && !rc.data[b + xx]) { ro.data[b + xx] = true; anyO = true }
                    }
                    if (anyO) {
                        val dc = dil(rc, p.pfHalo)
                        val dO = dil(ro, p.pfPair)
                        for (yy in ry0 until ry1) {
                            val b = (yy - ry0) * rw - rx0
                            for (xx in rx0 until rx1) if (dc.data[b + xx] && dO.data[b + xx]) out.data[yy * w + xx] = true
                        }
                    }
                }
            }
            if (gx1 < 0) return Ring.packBits(out)
            if (diag != null) { diag["obj_gf"] = gf; diag["obj_pf_lines"] = out.copy(); diag["obj_X"] = unpack(xB, w, h) }
            if (p.pfHole > 0) {
                // Gc＝gf 在窗裡閉合（窗內離 gf ≤ pfClose 的像素侵蝕看的鄰居都在窗內 ⇒ 與整頁閉合逐像素相同）
                val qd = 2 * pc + 1
                val wx0 = max(0, gx0 - qd); val wy0 = max(0, gy0 - qd); val wx1 = min(w, gx1 + 1 + qd); val wy1 = min(h, gy1 + 1 + qd)
                val ww = wx1 - wx0; val wh = wy1 - wy0
                val gw = Mask(ww, wh)
                for (yy in 0 until wh) for (xx in 0 until ww) gw.data[yy * ww + xx] = gf.data[(yy + wy0) * w + xx + wx0]
                val gc = if (pc > 0) Cv.closePacked(gw, kc) else gw
                if (diag != null) {
                    val gcf = Mask(w, h)
                    for (yy in 0 until wh) for (xx in 0 until ww) if (gc.data[yy * ww + xx]) gcf.data[(yy + wy0) * w + xx + wx0] = true
                    diag["obj_gc"] = gcf
                }
                // Gc⊕3×3：窗外擴 1（Gc 在窗外是 0）
                val pw = ww + 2
                val gcd = Mask(pw, wh + 2)
                for (yy in 0 until wh) for (xx in 0 until ww) {
                    if (!gc.data[yy * ww + xx]) continue
                    for (dy in 0..2) for (dx in 0..2) gcd.data[(yy + dy) * pw + xx + dx] = true
                }
                fun gcAt(x: Int, y: Int): Boolean = x in wx0 until wx1 && y in wy0 until wy1 && gc.data[(y - wy0) * ww + x - wx0]
                fun free(i: Int): Boolean = !has(xB, i) && !gcAt(i % w, i / w)
                fun nearX(x: Int, y: Int): Boolean {
                    for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) if (has(xB, yy * w + xx)) return true
                    return false
                }
                val hc = min(3840, max(960, h)).toLong()
                val holeLim = p.pfHole.toLong() * hc * hc
                val seenF = LongArray((n + 63) ushr 6)
                var keepPx = IntArray(256)
                for (py in 0 until wh + 2) for (px in 0 until pw) {
                    if (!gcd.data[py * pw + px]) continue
                    val sx = px + wx0 - 1
                    val sy = py + wy0 - 1
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue
                    val s0 = sy * w + sx
                    if (Ring.has(seenF, s0) || !free(s0)) continue
                    // 補集的一塊（8 連通，整頁走）：面積、碰不碰交代過⊕3×3、落在版本 3 外圈的像素
                    var qe = 0
                    q[qe++] = s0
                    Ring.set(seenF, s0)
                    var qs = 0
                    var area = 0L
                    var touchX = false
                    var nk = 0
                    // 佇列當環形用：只留還沒處理的（大塊可以接近整頁）
                    var cap = q.size
                    while (qs != qe) {
                        val i = q[qs]
                        qs = if (qs + 1 == cap) 0 else qs + 1
                        area++
                        val x = i % w
                        val y = i / w
                        if (!touchX && nearX(x, y)) touchX = true
                        if (old.data[i]) {
                            if (nk == keepPx.size) keepPx = keepPx.copyOf(nk * 2)
                            keepPx[nk++] = i
                        }
                        for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                            val j = yy * w + xx
                            if (Ring.has(seenF, j) || !free(j)) continue
                            Ring.set(seenF, j)
                            val next = if (qe + 1 == cap) 0 else qe + 1
                            if (next == qs) {
                                // 環滿：攤平成 [qs..) 再加倍
                                val nq = IntArray(cap * 2)
                                var m = 0
                                var t = qs
                                while (t != qe) { nq[m++] = q[t]; t = if (t + 1 == cap) 0 else t + 1 }
                                q = nq; cap = nq.size; qs = 0; qe = m
                            }
                            q[qe] = j
                            qe = if (qe + 1 == cap) 0 else qe + 1
                        }
                    }
                    val small = area * (1920L * 1920L) <= holeLim
                    if (small || !touchX) for (k in 0 until nk) out.data[keepPx[k]] = true
                }
            }
            if (diag != null) diag["obj_pf_holes"] = out.copy()
            if (mark != null) {
                // 淡小記號：碰到版本 3 外圈的候選塊（8 連通、整頁走），外接框長邊 < dot 的外擴 pfMark ∩ 版本 3 外圈
                val pd = p.pfHalo + p.dot + p.pfMark
                val ox0 = max(0, gx0 - pd); val oy0 = max(0, gy0 - pd); val ox1 = min(w, gx1 + 1 + pd); val oy1 = min(h, gy1 + 1 + pd)
                val ow = ox1 - ox0
                val mk = Mask(ow, oy1 - oy0)
                var anyMk = false
                val seenM = LongArray((n + 63) ushr 6)
                for (sy in max(0, gy0 - p.pfHalo)..min(h - 1, gy1 + p.pfHalo)) for (sx in max(0, gx0 - p.pfHalo)..min(w - 1, gx1 + p.pfHalo)) {
                    val s0 = sy * w + sx
                    if (!old.data[s0] || Ring.has(seenM, s0) || !mark.test(s0)) continue
                    var qe = 0
                    q[qe++] = s0
                    Ring.set(seenM, s0)
                    var qs = 0
                    var bx0 = sx; var bx1 = sx; var by0 = sy; var by1 = sy
                    while (qs < qe) {
                        val i = q[qs++]
                        val x = i % w
                        val y = i / w
                        if (x < bx0) bx0 = x; if (x > bx1) bx1 = x; if (y < by0) by0 = y; if (y > by1) by1 = y
                        for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                            val j = yy * w + xx
                            if (!Ring.has(seenM, j) && mark.test(j)) {
                                Ring.set(seenM, j)
                                if (qe == q.size) q = q.copyOf(q.size * 2)
                                q[qe++] = j
                            }
                        }
                    }
                    if (max(bx1 - bx0 + 1, by1 - by0 + 1) >= p.dot) continue
                    for (k in 0 until qe) { val i = q[k]; mk.data[(i / w - oy0) * ow + i % w - ox0] = true }
                    anyMk = true
                }
                if (anyMk) {
                    val md = dil(mk, p.pfMark)
                    for (yy in oy0 until oy1) {
                        val b = (yy - oy0) * ow - ox0
                        for (xx in ox0 until ox1) { val i = yy * w + xx; if (md.data[b + xx] && old.data[i]) out.data[i] = true }
                    }
                }
            }
            return Ring.packBits(out)
        }

    private fun blobs(rnd: Random, w: Int, h: Int, n: Int, maxR: Int): Mask {
        val m = Mask(w, h)
        repeat(n) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val x0 = rnd.nextInt(w); val y0 = rnd.nextInt(h)
                    val x1 = min(w, x0 + 1 + rnd.nextInt(maxR)); val y1 = min(h, y0 + 1 + rnd.nextInt(maxR))
                    for (y in y0 until y1) for (x in x0 until x1) m.data[y * w + x] = true
                }
                1 -> Cv.line(m, rnd.nextInt(w), rnd.nextInt(h), rnd.nextInt(w), rnd.nextInt(h), 1 + rnd.nextInt(2))
                else -> m.data[rnd.nextInt(w * h)] = true
            }
        }
        return m
    }

    @Test
    fun holesMatchOld() {
        val rnd = Random(97531)
        repeat(160) { t ->
            val w = if (t % 9 == 0) 1 + rnd.nextInt(3) else 20 + rnd.nextInt(170)
            val h = if (t % 11 == 0) 1 + rnd.nextInt(3) else 20 + rnd.nextInt(150)
            val gf = blobs(rnd, w, h, 2 + rnd.nextInt(14), 25)
            val x = blobs(rnd, w, h, 1 + rnd.nextInt(10), 40)
            val xB = Ring.packBits(x)
            val p = ObjectRuleParams(
                pfClose = rnd.nextInt(0, 6), pfHalo = rnd.nextInt(0, 7), pfMargin = rnd.nextInt(0, 4),
                pfHole = if (rnd.nextBoolean()) rnd.nextInt(0, 30) else rnd.nextInt(1, 3_000_000),
                pfLinePct = 90 + rnd.nextInt(30), pfStub = rnd.nextInt(0, 20), pfPair = rnd.nextInt(0, 6), pfMark = rnd.nextInt(0, 4),
                dot = 1 + rnd.nextInt(12),
            )
            val mk = blobs(rnd, w, h, rnd.nextInt(20), 4)
            val mark = if (rnd.nextBoolean()) BgObjects.PixelTest { i -> mk.data[i] } else null
            val want = pfShapeOld(gf, xB, mark, p, null)
            val got = BgObjects.pfShape(gf, xB, mark, p, null)
            assertArrayEquals("外圈 #$t ${w}×$h $p", unpack(want, w, h).data, unpack(got, w, h).data)
            // diag 的中間量也要相同
            val dw = HashMap<String, Any>()
            val dg = HashMap<String, Any>()
            pfShapeOld(gf, xB, mark, p, dw)
            BgObjects.pfShape(gf, xB, mark, p, dg)
            for (k in dw.keys) assertArrayEquals("diag $k #$t", (dw[k] as Mask).data, (dg[k] as Mask).data)
            assertArrayEquals("diag 鍵 #$t", dw.keys.sorted().toTypedArray(), dg.keys.sorted().toTypedArray())
        }
    }
}
