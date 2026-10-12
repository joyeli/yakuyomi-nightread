package li.joye.yakuyomi.nightread

import li.joye.yakuyomi.nightread.NativeDump.Rec
import org.junit.Assume
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.SplittableRandom
import javax.imageio.ImageIO

/**
 * 錄出器（cpp-eval 計畫階段 1）：用 Kotlin 版跑一遍，把「之後 C++ 要逐位元重現的東西」寫到磁碟，給後面各階段的 C++ 移植重放、
 * 逐位元比——不必等上游模組搬完，就能拿真頁資料驗下游。三樣：
 *  1. **原語的隨機與邊界案例**（prims/）：Cv 的形態學、連通元件、距離、灰階濾波、縮放、繪圖、陣列原語，輸入與輸出原樣（或
 *     Kotlin 丟的例外）。尺寸刻意跨過 64 位元字的邊界（63／64／65／127／129…）、含 1×1、1×N、全空、全滿、只有邊框。
 *  2. **頁級 manifest**（pages/）：每頁的輸入摘要、各跑法用的參數摘要、每種跑法每個成分（分析的中間遮罩、結果遮罩、產品兩檔
 *     成品、diag、除錯回呼序列）的摘要。C++ 之後算出同一個成分就比同一列。
 *  3. **指定項目的原始資料**：頁輸入（NRIN）、攤平參數、任意成分的原始陣列（-PnativeDumpRaw 選）。
 * 格式見 [NativeDump.README]（每份錄出也會寫一份 README.txt）；C++ 端讀取與重放在 native/test/dump_check.cpp（nr_dump_check）。
 *
 * **平常不跑**：沒給 `-PnativeDump=<目錄>` 就略過（Gradle 組 `dump`，fast 組不含它）。其他開關（都可省略）：
 *  - `-PnativeDumpPages=`：逗號分隔。`fixtures`（預設：測試資源裡輸入齊全的頁）、單一 fixture 頁名、NRIN 目錄或檔（本機語料，
 *    例如 research/out 底下的 inputs；**本機語料的錄出不要進 repo**）。
 *  - `-PnativeDumpVariants=`：預設 `analysis,tiers:def,tiers:fork,plain:full,plain:L2,plain:MORE`；`all`＝再加五檔的 plain／diag。
 *  - `-PnativeDumpRaw=`：預設 `input,params`；另可列成分名（`跑法.成分`，`*` 萬用；例如 `analysis.a.frame,tiers:def.*`），`none`＝都不要。
 *  - `-PnativeDumpPrims=`：`false`＝不錄原語；逗號分隔的族名＝只錄那幾族（packbits、kernel、morph、cc、dist、maskmisc、gray、
 *    fimg、draw、arrays）；預設全錄。
 */
class NativeDumpTest {

    private val outDir: File? = System.getProperty("nightread.nativeDump")?.takeIf { it.isNotBlank() }?.let { File(it) }
    private val pageSpec = System.getProperty("nightread.nativeDumpPages")?.takeIf { it.isNotBlank() } ?: "fixtures"
    private val variantSpec = System.getProperty("nightread.nativeDumpVariants")?.takeIf { it.isNotBlank() }
        ?: "analysis,tiers:def,tiers:fork,plain:full,plain:L2,plain:MORE"
    private val rawSpec = (System.getProperty("nightread.nativeDumpRaw")?.takeIf { it.isNotBlank() } ?: "input,params")
        .split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }
    /**
     * 錄哪幾族原語：沒給或 true＝全部、false＝都不錄、否則逗號分隔的族名（例如 CI 只錄 C++ 已能重放的 packbits,kernel）。
     * 族名要在 [knownFamilies] 裡，打錯直接失敗（不然那一族默默不錄，重放那一步也就等於沒做）。
     */
    private val primFamilies: Set<String>? = System.getProperty("nightread.nativeDumpPrims")?.takeIf { it.isNotBlank() && it != "true" }
        ?.let { v -> if (v == "false") emptySet() else v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet() }

    /** [dumpPrims] 錄的族（順序同錄出）；新增一族時兩邊一起改，[dumpPrims] 結尾會檢查每一族都登記過。 */
    private val knownFamilies = listOf("packbits", "kernel", "morph", "cc", "dist", "maskmisc", "gray", "fimg", "draw", "arrays")

    @Test
    fun dump() {
        val dir = outDir
        Assume.assumeTrue("沒給 -PnativeDump=<目錄>，略過錄出", dir != null)
        val unknown = primFamilies.orEmpty() - knownFamilies.toSet()
        require(unknown.isEmpty()) { "NativeDumpTest：不認得的原語族 $unknown（-PnativeDumpPrims；可用 ${knownFamilies.joinToString(",")}、true、false）" }
        dir!!.mkdirs()
        File(dir, "README.txt").writeText(NativeDump.README + "\n")
        val t0 = System.nanoTime()
        if (primFamilies?.isEmpty() != true) dumpPrims(File(dir, "prims"))
        val pages = resolvePages()
        val variants = resolveVariants()
        val index = StringBuilder("set\tpage\tw\th\tmanifest\n")
        for ((set, name, load) in pages) {
            val t1 = System.nanoTime()
            val input = load()
            val pdir = File(dir, "pages/$set/$name")
            pdir.mkdirs()
            val rows = dumpPage(input, variants, pdir)
            File(pdir, "manifest.tsv").writeText(rows.joinToString("\n", postfix = "\n") { it.joinToString("\t") })
            index.append("$set\t$name\t${input.gray.w}\t${input.gray.h}\tpages/$set/$name/manifest.tsv\n")
            println("NativeDumpTest：$set/$name ${input.gray.w}×${input.gray.h} ${rows.size} 列 ${"%.1f".format((System.nanoTime() - t1) / 1e9)} s")
        }
        File(dir, "pages").mkdirs()
        File(dir, "pages/index.tsv").writeText(index.toString())
        println("NativeDumpTest：${pages.size} 頁、跑法 ${variants.joinToString(",")}、原始資料 ${rawSpec.ifEmpty { listOf("none") }}，" +
            "共 ${"%.1f".format((System.nanoTime() - t0) / 1e9)} s → $dir")
    }

    // ── 頁 ─────────────────────────────────────────────────────────────

    private data class PageSrc(val set: String, val name: String, val load: () -> NightReadInput)

    private val fixturePages = listOf("ch34_010", "ch34_011", "ch34_015", "demo01", "demo02", "demo04", "demo05", "demo06", "syn_more", "syn_v4")

    private fun resolvePages(): List<PageSrc> {
        val out = ArrayList<PageSrc>()
        for (item in pageSpec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                item == "fixtures" -> fixturePages.forEach { p -> out += PageSrc("fixtures", p) { fixtureInput(p) } }
                item in fixturePages -> out += PageSrc("fixtures", item) { fixtureInput(item) }
                File(item).isDirectory -> {
                    val d = File(item)
                    d.listFiles { f -> f.name.endsWith(".bin") && !f.name.endsWith(".inp.bin") }!!.sortedBy { it.name }
                        .forEach { f -> out += PageSrc(d.name, f.name.removeSuffix(".bin")) { NativeDump.readNrin(f) } }
                }
                File(item).isFile -> {
                    val f = File(item)
                    out += PageSrc(f.absoluteFile.parentFile?.name ?: "pages", f.name.removeSuffix(".bin")) { NativeDump.readNrin(f) }
                }
                else -> error("NativeDumpTest：看不懂的頁 $item（fixtures、fixture 頁名、NRIN 目錄或檔）")
            }
        }
        return out
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResource("page/$name")

    private fun readGray(name: String): Gray {
        val img: BufferedImage = resource(name)!!.openStream().use { ImageIO.read(it) }
        val g = Gray(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) g.data[y * img.width + x] = img.raster.getSample(x, y, 0)
        return g
    }

    private fun readMask(name: String): Mask = readGray(name).let { g -> Mask(g.w, g.h, BooleanArray(g.data.size) { g.data[it] > 127 }) }

    /** 與 RulesVersionGuardTest／PageParityTest 讀 fixture 的方式相同。 */
    private fun fixtureInput(p: String) = NightReadInput(
        gray = readGray("${p}_gray.png"),
        seg = readMask("${p}_seg.png"),
        regions = resource("${p}_regions.txt")!!.readText().lineSequence().filter { it.isNotBlank() }.map {
            val v = it.trim().split(" ").map(String::toInt)
            TextRegion(v[0], v[1], v[2], v[3])
        }.toList(),
        charMask = readMask("${p}_char.png"),
        chroma = readGray("${p}_chroma.png"),
        inpaintMask = if (resource("${p}_inpaint.png") != null) readMask("${p}_inpaint.png") else null,
    )

    private val allTiers = listOf("full", "L1", "L2", "L3old", "MORE")

    private fun resolveVariants(): List<String> {
        if (variantSpec == "all") return listOf("analysis", "tiers:def", "tiers:fork") + allTiers.flatMap { listOf("plain:$it", "diag:$it") }
        val v = variantSpec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for (x in v) {
            val ok = x == "analysis" || x == "tiers:def" || x == "tiers:fork" ||
                ((x.startsWith("plain:") || x.startsWith("diag:")) && x.substringAfter(':') in allTiers)
            require(ok) { "NativeDumpTest：看不懂的跑法 $x" }
        }
        return v
    }

    /** fork 預設亮度（產品偏好「柔和」，DECISIONS「夜讀亮度」；Ident 的 forkBase 同值）。 */
    private fun forkBase() = NightReadParams(ink = 205, edgeInk = 170, strokeObjV = 190)

    private fun tierParams(t: String, base: NightReadParams = NightReadParams()): NightReadParams = when (t) {
        "full" -> base
        "L1" -> NightTier.L1.apply(base)
        "L2" -> NightTier.L2.apply(base)
        "MORE" -> NightTier.L3.apply(base)
        "L3old" -> NightTier.L3.apply(base).let { it.copy(more = it.more.copy(enabled = false)) }
        else -> error(t)
    }

    private fun rawWanted(name: String): Boolean = rawSpec.any { pat ->
        pat != "input" && pat != "params" && Regex(pat.split('*').joinToString(".*") { Regex.escape(it) }).matches(name)
    }

    private fun dumpPage(input: NightReadInput, variants: List<String>, pdir: File): List<List<String>> {
        val rows = ArrayList<List<String>>()
        fun row(v: String, c: String, x: String) { rows += listOf(v, c, x) }
        row("page", "format", "nightread-dump/1")
        row("page", "size", "${input.gray.w}x${input.gray.h}")
        row("page", "regions", input.regions.size.toString())
        row("page", "rules_version", NightRead.RULES_VERSION.toString())
        row("page", "params_schema", "%016x".format(ParamsFlat.SCHEMA_HASH))
        row("input", "gray", NativeDump.hash(input.gray))
        row("input", "chroma", NativeDump.hash(input.chroma))
        row("input", "seg", NativeDump.hash(input.seg))
        row("input", "charMask", NativeDump.hash(input.charMask))
        row("input", "inpaintMask", NativeDump.hash(input.inpaintMask))
        row("input", "regions", NativeDump.hash(input.regions.map { intArrayOf(it.x0, it.y0, it.x1, it.y1) }))
        if ("input" in rawSpec) NativeDump.writeNrin(input, File(pdir, "input.bin"))

        val params = LinkedHashMap<String, NightReadParams>()
        val raw = if (rawSpec.any { it != "input" && it != "params" }) NativeDump.Writer(File(pdir, "raw.nrd")) else null
        try {
            for (v in variants) {
                val dbg = ArrayList<String>()
                val rec: NightReadDebug = { s, x -> synchronized(dbg) { dbg += "$s=$x" } }
                val comps = ArrayList<Pair<String, Any?>>()
                fun comp(prefix: String, o: Any?, split: Int) = NativeDump.components(prefix, o, split) { n, x -> comps += n to x }
                when {
                    v == "analysis" -> {
                        val p = tierParams("L2")
                        params[v] = p
                        val a = NightRead.analyze(input, p, rec, null, shared = true)
                        for (f in analysisFields) {
                            val fld = NightRead.Analysis::class.java.getDeclaredField(f).also { it.isAccessible = true }
                            comp("a.$f", fld.get(a), 2)
                        }
                        comp("a.plain", a.plain, 0)
                        comp("a.bubbleS0", a.bubbleS0, 0)
                    }
                    v.startsWith("tiers:") -> {
                        val base = if (v == "tiers:fork") forkBase() else NightReadParams()
                        val ts = listOf(NightTier.L2.apply(base), NightTier.L3.apply(base))
                        params["$v.k0"] = ts[0]
                        params["$v.k1"] = ts[1]
                        val got = arrayOfNulls<Any>(2)
                        NightRead.renderTiers(input, ts, rec) { k, g -> got[k] = g ?: "dup" }
                        comp("k0", got[0], 0)
                        comp("k1", got[1], 0)
                    }
                    v.startsWith("plain:") -> {
                        val p = tierParams(v.substringAfter(':'))
                        params[v] = p
                        val r = NightRead.render(input, p, rec)
                        resultComponents(r).forEach { (n, x) -> comp(n, x, 0) }
                    }
                    v.startsWith("diag:") -> {
                        val p = tierParams(v.substringAfter(':'))
                        params[v] = p
                        val diag = HashMap<String, Any>()
                        diag["ring"] = true
                        diag["ring_state"] = true
                        diag["obj"] = true
                        val r = NightRead.render(input, p, rec, diag as MutableMap<String, Any>?)
                        resultComponents(r).forEach { (n, x) -> comp(n, x, 0) }
                        for (k in diag.keys.sorted()) if (!k.startsWith("t_")) comp("diag.$k", diag[k], 1)
                    }
                    else -> error(v)
                }
                comps += "dbgnames" to dbg.map { it.substringBeforeLast('=') }
                comps += "dbg" to dbg.toList()
                for ((n, x) in comps.sortedBy { it.first }) {
                    row(v, n, NativeDump.hash(x))
                    if (raw != null && rawWanted("$v.$n")) NativeDump.rawRecord("$v.$n", x)?.let { raw.write(it) }
                }
            }
        } finally {
            raw?.close()
        }
        for ((v, p) in params) {
            val (i, d) = ParamsFlat.toFlat(p)
            row("params", v, NativeDump.hash(listOf(i, d)))
        }
        if ("params" in rawSpec) {
            NativeDump.Writer(File(pdir, "params.nrd")).use { w ->
                for ((v, p) in params) {
                    val (i, d) = ParamsFlat.toFlat(p)
                    w.write(Rec().str("variant", v).i32("ints", i).f64("doubles", d).i64("schema", longArrayOf(ParamsFlat.SCHEMA_HASH)))
                }
            }
        }
        return rows
    }

    /** 分析的成分（NightRead.Analysis 的建構子欄位；參數另記、moreCache 與合成時才填的快取不算）。 */
    private val analysisFields = listOf(
        "g", "seg", "charRaw", "charMask", "lh", "lv", "frame", "frameless", "wc", "bubbleUntrim", "cored", "sealed", "anySealed",
        "bubble", "bubbleStruct", "bubbleLocal", "lost", "plan", "scene", "layer", "chroma", "ringEvidence", "inpaint",
    )

    private fun resultComponents(r: NightReadResult) = listOf(
        "out" to r.out, "gutter" to r.gutter, "bubble" to r.bubble, "charMask" to r.charMask, "frameless" to r.frameless,
        "stickerAccept" to r.stickerAccept, "stickerPromoted" to r.stickerPromoted, "sep" to r.sep,
    )

    // ── 原語 ───────────────────────────────────────────────────────────

    private class Fam(dir: File, val name: String) {
        val w = NativeDump.Writer(File(dir, "$name.nrd"))
        var throws = 0
    }

    /** 跑一個案例：成功把輸出加進紀錄；Kotlin 丟例外就記 throws。 */
    private fun case(f: Fam, op: String, desc: String, inputs: Rec.() -> Unit, run: Rec.() -> Unit) {
        val r = Rec().str("op", op).str("case", desc)
        r.inputs()
        val out = Rec()
        try {
            out.run()
            r.entries += out.entries
        } catch (e: Exception) {
            r.str("throws", e.javaClass.name)
            f.throws++
        }
        f.w.write(r)
    }

    private val sizes = listOf(1 to 1, 1 to 9, 9 to 1, 2 to 2, 3 to 5, 63 to 3, 64 to 5, 65 to 4, 127 to 2, 129 to 3, 37 to 41, 96 to 80)

    private fun masks(): List<Pair<String, Mask>> {
        val rnd = SplittableRandom(20261012L)
        val out = ArrayList<Pair<String, Mask>>()
        for ((w, h) in sizes) {
            out += "${w}x${h} 全空" to Mask(w, h)
            out += "${w}x${h} 全滿" to Mask(w, h, BooleanArray(w * h) { true })
            for (d in listOf(0.03, 0.3, 0.7)) out += "${w}x${h} 隨機 $d" to Mask(w, h, BooleanArray(w * h) { rnd.nextDouble() < d })
        }
        for ((w, h) in listOf(37 to 41, 96 to 80, 129 to 3, 65 to 66)) {
            out += "${w}x${h} 只有邊框" to Mask(w, h, BooleanArray(w * h) { val x = it % w; val y = it / w; x == 0 || y == 0 || x == w - 1 || y == h - 1 })
            out += "${w}x${h} 四角各一點" to Mask(w, h, BooleanArray(w * h) { val x = it % w; val y = it / w; (x == 0 || x == w - 1) && (y == 0 || y == h - 1) })
            out += "${w}x${h} 棋盤" to Mask(w, h, BooleanArray(w * h) { ((it % w) + (it / w)) % 2 == 0 })
            out += "${w}x${h} 色塊" to blobs(w, h, rnd)
            out += "${w}x${h} 細線" to Mask(w, h, BooleanArray(w * h) { val x = it % w; val y = it / w; x % 7 == 3 || (y % 11 == 5 && x % 3 != 0) })
        }
        return out
    }

    private fun blobs(w: Int, h: Int, rnd: SplittableRandom): Mask {
        val m = Mask(w, h)
        repeat(6) {
            val cx = rnd.nextInt(w)
            val cy = rnd.nextInt(h)
            val r = 1 + rnd.nextInt(maxOf(1, minOf(w, h) / 3))
            val hole = rnd.nextInt(3) == 0
            for (y in 0 until h) for (x in 0 until w) {
                val dd = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (dd <= r * r && !(hole && dd <= (r / 2) * (r / 2))) m.data[y * w + x] = true
            }
        }
        return m
    }

    private fun grays(): List<Pair<String, Gray>> {
        val rnd = SplittableRandom(7L)
        val out = ArrayList<Pair<String, Gray>>()
        for ((w, h) in listOf(1 to 1, 1 to 9, 9 to 1, 3 to 5, 64 to 5, 65 to 4, 37 to 41, 96 to 80)) {
            out += "${w}x${h} 隨機" to Gray(w, h, IntArray(w * h) { rnd.nextInt(256) })
            out += "${w}x${h} 0" to Gray(w, h)
            out += "${w}x${h} 255" to Gray(w, h, IntArray(w * h) { 255 })
            out += "${w}x${h} 漸層" to Gray(w, h, IntArray(w * h) { ((it % w) * 255 / maxOf(1, w - 1) + (it / w) * 3) and 0xff })
            out += "${w}x${h} 椒鹽" to Gray(w, h, IntArray(w * h) { if (rnd.nextInt(5) == 0) (if (rnd.nextBoolean()) 0 else 255) else 200 })
        }
        // 平滑的大塊（像頁面：紙白、墨線、網點）
        for ((w, h) in listOf(96 to 80, 130 to 67)) {
            val g = Gray(w, h, IntArray(w * h) { 240 })
            for (y in 0 until h) for (x in 0 until w) {
                if ((x + y) % 23 < 2) g.data[y * w + x] = 20
                if (x in w / 3 until 2 * w / 3 && y in h / 4 until h / 2 && (x + 2 * y) % 4 == 0) g.data[y * w + x] = 120
            }
            out += "${w}x$h 頁面樣" to g
        }
        return out
    }

    private fun kernels(): List<Pair<String, Kernel>> {
        val rnd = SplittableRandom(3L)
        val out = ArrayList<Pair<String, Kernel>>()
        for (s in listOf(1, 3, 5, 9, 15)) out += "ellipse $s" to Cv.ellipse(s)
        out += "rect 3x1" to Cv.rect(3, 1)
        out += "rect 1x5" to Cv.rect(1, 5)
        out += "rect 7x3" to Cv.rect(7, 3)
        out += "隨機 4x6" to Kernel(4, 6, BooleanArray(24) { rnd.nextInt(3) != 0 })
        return out
    }

    private fun Rec.kernel(k: Kernel) = bools("kernel", k.data, k.h, k.w)

    private fun dumpPrims(dir: File) {
        dir.mkdirs()
        val index = StringBuilder("family\trecords\tthrows\tbytes\n")
        val ms = masks()
        val gs = grays()
        val ks = kernels()
        val rnd = SplittableRandom(99L)
        val ran = ArrayList<String>()
        fun fam(name: String, body: (Fam) -> Unit) {
            check(name in knownFamilies) { "NativeDumpTest：族 $name 沒登記在 knownFamilies" }
            ran += name
            if (primFamilies != null && name !in primFamilies) return
            val f = Fam(dir, name)
            try {
                body(f)
            } finally {
                f.w.close()
            }
            index.append("$name\t${f.w.records}\t${f.throws}\t${File(dir, "$name.nrd").length()}\n")
        }

        // 列打包與核（階段 1 的 C++ 已有：nr_dump_check 直接重放）
        fam("packbits") { f ->
            for ((d, m) in ms) case(f, "packBits", d, { mask("in", m) }) { i64("out", Cv.packBits(m)) }
        }
        fam("kernel") { f ->
            val all = ks + (1..41 step 2).map { "ellipse $it" to Cv.ellipse(it) } +
                List(40) { val kw = 1 + rnd.nextInt(15); val kh = 1 + rnd.nextInt(15); "隨機 ${kw}x$kh" to Kernel(kw, kh, BooleanArray(kw * kh) { rnd.nextInt(3) == 0 }) }
            for ((d, k) in all) case(f, "kernel", d, { kernel(k) }) { int("ax", k.ax); int("ay", k.ay); i32("runStart", k.runStart); i32("runEnd", k.runEnd) }
        }
        // 二值形態學（一般版與打包版，階段 3a）
        fam("morph") { f ->
            for ((d, m) in ms) for ((kd, k) in ks) {
                val ins: Rec.() -> Unit = { mask("in", m); kernel(k) }
                case(f, "dilate", "$d / $kd", ins) { mask("out", Cv.dilate(m, k)) }
                case(f, "erode", "$d / $kd", ins) { mask("out", Cv.erode(m, k)) }
                case(f, "open", "$d / $kd", ins) { mask("out", Cv.open(m, k)) }
                case(f, "close", "$d / $kd", ins) { mask("out", Cv.close(m, k)) }
                case(f, "dilatePacked", "$d / $kd", ins) { mask("out", Cv.dilatePacked(m, k)) }
                case(f, "erodePacked", "$d / $kd", ins) { mask("out", Cv.erodePacked(m, k)) }
                case(f, "openPacked", "$d / $kd", ins) { mask("out", Cv.openPacked(m, k)) }
                case(f, "closePacked", "$d / $kd", ins) { mask("out", Cv.closePacked(m, k)) }
            }
            for ((d, m) in ms.filterIndexed { i, _ -> i % 3 == 0 }) {
                case(f, "dilate", "$d / ellipse 3 ×2", { mask("in", m); kernel(Cv.ellipse(3)); int("iterations", 2) }) { mask("out", Cv.dilate(m, Cv.ellipse(3), 2)) }
                case(f, "erode", "$d / ellipse 3 ×3", { mask("in", m); kernel(Cv.ellipse(3)); int("iterations", 3) }) { mask("out", Cv.erode(m, Cv.ellipse(3), 3)) }
                for ((kw, kh) in listOf(3 to 1, 1 to 5, 7 to 7)) {
                    case(f, "dilateRectSep", "$d / ${kw}x$kh", { mask("in", m); i32("args", intArrayOf(kw, kh)) }) { mask("out", Cv.dilateRectSep(m, kw, kh)) }
                }
                for (n in listOf(3, 9)) case(f, "closeRectSep", "$d / $n", { mask("in", m); int("n", n) }) { mask("out", Cv.closeRectSep(m, n)) }
            }
        }
        // 連通元件（階段 3a）
        fam("cc") { f ->
            for ((d, m) in ms) for (conn in listOf(4, 8)) {
                case(f, "ccStats", "$d / $conn", { mask("in", m); int("connectivity", conn) }) {
                    val c = Cv.ccStats(m, conn)
                    int("n", c.n); i32("labels", c.labels, m.h, m.w); i32("left", c.left); i32("top", c.top)
                    i32("width", c.width); i32("height", c.height); i32("area", c.area)
                }
                case(f, "ccRuns", "$d / $conn", { mask("in", m); int("connectivity", conn) }) {
                    val c = Cv.ccRuns(m, conn)
                    int("n", c.n); i32("rs", c.rs); i32("re", c.re); i32("rowFirst", c.rowFirst); i32("lab", c.lab)
                    i64("fg", longArrayOf(c.fg)); i32("bg", intArrayOf(c.bgLeft, c.bgTop, c.bgRight, c.bgBottom))
                }
            }
        }
        // 距離（階段 3a）
        fam("dist") { f ->
            for ((d, m) in ms) {
                case(f, "distanceL2", d, { mask("in", m) }) { fimg("out", Cv.distanceL2(m)) }
                case(f, "distanceSq", d, { mask("in", m) }) { i32("out", Cv.distanceSq(m), m.h, m.w) }
                for (ms5 in listOf(3, 5)) case(f, "distanceChamfer", "$d / $ms5", { mask("in", m); int("maskSize", ms5) }) { fimg("out", Cv.distanceChamfer(m, ms5)) }
                case(f, "chamfer5Padded", d, { mask("in", m) }) { i32("out", Cv.chamfer5Padded(m)) }
            }
        }
        // 其他二值原語：補洞、中值、輪廓長、測地、縮放（階段 3a／3b）
        fam("maskmisc") { f ->
            for ((d, m) in ms) {
                case(f, "holes", d, { mask("in", m) }) { mask("out", Cv.holes(m)) }
                for (r in listOf(0, 1, 3, 5, 7)) case(f, "medianBlurMask", "$d / $r", { mask("in", m); int("r", r) }) { mask("out", Cv.medianBlurMask(m, r)) }
                case(f, "totalContourLength", d, { mask("in", m) }) { dbl("out", Cv.totalContourLength(m)) }
                for ((nw, nh) in listOf(0 to 0, 1 to 1, m.w * 2 to m.h, maxOf(1, m.w / 3) to maxOf(1, m.h / 2), m.w + 7 to m.h * 3 / 2 + 1)) {
                    case(f, "resizeNearest", "$d → ${nw}x$nh", { mask("in", m); i32("args", intArrayOf(nw, nh)) }) { mask("out", Cv.resizeNearest(m, nw, nh)) }
                }
            }
            val seeds = ms.filter { it.first.contains("隨機 0.03") || it.first.contains("四角") }
            val withins = ms.filter { it.first.contains("隨機 0.7") || it.first.contains("色塊") || it.first.contains("細線") || it.first.contains("全滿") }
            for ((sd, s) in seeds) for ((wd, wm) in withins) {
                if (s.w != wm.w || s.h != wm.h) continue
                for ((step, maxIt) in listOf(1 to 5, 3 to 50, 5 to 7)) {
                    case(f, "geodesicDistance", "$sd ∩ $wd / $step,$maxIt", { mask("seed", s); mask("within", wm); i32("args", intArrayOf(step, maxIt)) }) {
                        i32("out", Cv.geodesicDistance(s, wm, step, maxIt), s.h, s.w)
                    }
                }
                for ((iters, step) in listOf(3 to 1, 20 to 5, 7 to 3)) for (bfs in listOf<Boolean?>(null, true, false)) {
                    case(f, "geodesicGrow", "$sd ∩ $wd / $iters,$step,$bfs", {
                        mask("seed", s); mask("within", wm); i32("args", intArrayOf(iters, step, if (bfs == null) -1 else if (bfs) 1 else 0))
                    }) { mask("out", Cv.geodesicGrow(s, wm, iters, step, bfs)) }
                }
            }
        }
        // 灰階形態學與濾波（階段 3b）
        fam("gray") { f ->
            val gk = listOf(3, 5, 11).map { Cv.ellipse(it) } + Cv.rect(5, 3)
            for ((d, g) in gs) {
                for (k in gk) {
                    val ins: Rec.() -> Unit = { gray("in", g); kernel(k) }
                    case(f, "dilateGray", "$d / ${k.w}x${k.h}", ins) { gray("out", Cv.dilateGray(g, k)) }
                    case(f, "erodeGray", "$d / ${k.w}x${k.h}", ins) { gray("out", Cv.erodeGray(g, k)) }
                    case(f, "blackhat", "$d / ${k.w}x${k.h}", ins) { gray("out", Cv.blackhat(g, k)) }
                    case(f, "morphGrayGather.max", "$d / ${k.w}x${k.h}", ins) { gray("out", Cv.morphGrayGather(g, k, true)) }
                    case(f, "morphGrayGather.min", "$d / ${k.w}x${k.h}", ins) { gray("out", Cv.morphGrayGather(g, k, false)) }
                }
                // 1、偶數：呼叫端不會給，記下 Kotlin 的行為（丟例外或照算）當邊界規格
                for (ks in listOf(1, 3, 4, 5, 7)) case(f, "medianBlur8", "$d / $ks", { gray("in", g); int("ksize", ks) }) { gray("out", Cv.medianBlur8(g, ks)) }
                for (s in listOf(0.7, 1.0, 1.5, 2.0, 2.5, 4.0)) case(f, "gaussQ16", "$d / σ$s", { gray("in", g); dbl("sigma", s) }) { gray("out", Cv.gaussQ16(g, s)) }
                for ((lo, hi) in listOf(15 to 40, 10 to 25, 50 to 150)) {
                    case(f, "canny", "$d / $lo,$hi", { gray("in", g); i32("args", intArrayOf(0, lo, hi)) }) { mask("out", Cv.canny(g.data, g.w, g.h, 0, lo, hi)) }
                }
                val q = Cv.gaussQ16(g, 2.0)
                case(f, "canny", "$d / gaussQ16 σ2 右移 16 / 15,40", { gray("in", q); i32("args", intArrayOf(16, 15, 40)) }) { mask("out", Cv.canny(q.data, q.w, q.h, 16, 15, 40)) }
                for ((nw, nh) in listOf(1 to 1, g.w * 2 to g.h, maxOf(1, g.w / 3) to maxOf(1, g.h / 2))) {
                    case(f, "resizeNearestGray", "$d → ${nw}x$nh", { gray("in", g); i32("args", intArrayOf(nw, nh)) }) { gray("out", Cv.resizeNearestGray(g, nw, nh)) }
                }
            }
        }
        // 浮點影像（階段 3b）
        fam("fimg") { f ->
            for ((d, g) in gs) {
                val fi = g.toF()
                for (s in listOf(0.0, 0.7, 1.0, 2.0, 4.0, 5.0)) case(f, "gaussianBlur", "$d / σ$s", { fimg("in", fi); dbl("sigma", s) }) { fimg("out", Cv.gaussianBlur(fi, s)) }
                for (k in listOf(3, 5, 9)) case(f, "boxBlur", "$d / $k", { fimg("in", fi); int("k", k) }) { fimg("out", Cv.boxBlur(fi, k)) }
                for ((nw, nh) in listOf(1 to 1, g.w * 2 to g.h, maxOf(1, g.w / 3) to maxOf(1, g.h / 2), g.w + 5 to g.h + 3)) {
                    case(f, "resizeArea", "$d → ${nw}x$nh", { fimg("in", fi); i32("args", intArrayOf(nw, nh)) }) { fimg("out", Cv.resizeArea(fi, nw, nh)) }
                    case(f, "resizeBilinear", "$d → ${nw}x$nh", { fimg("in", fi); i32("args", intArrayOf(nw, nh)) }) { fimg("out", Cv.resizeBilinear(fi, nw, nh)) }
                }
            }
        }
        // 繪圖（階段 3b）：端點可在影像外
        fam("draw") { f ->
            for ((w, h) in listOf(1 to 1, 9 to 1, 64 to 5, 37 to 41, 96 to 80)) {
                repeat(40) { t ->
                    val x0 = rnd.nextInt(-w, 2 * w + 1)
                    val y0 = rnd.nextInt(-h, 2 * h + 1)
                    val x1 = rnd.nextInt(-w, 2 * w + 1)
                    val y1 = rnd.nextInt(-h, 2 * h + 1)
                    val th = 1 + t % 7
                    case(f, "line", "${w}x$h #$t", { int("w", w); int("h", h); i32("args", intArrayOf(x0, y0, x1, y1, th)) }) {
                        val m = Mask(w, h); Cv.line(m, x0, y0, x1, y1, th); mask("out", m)
                    }
                }
                repeat(30) { t ->
                    val n = 3 + rnd.nextInt(6)
                    val xs = IntArray(n) { rnd.nextInt(-w / 2 - 1, w + w / 2 + 2) }
                    val ys = IntArray(n) { rnd.nextInt(-h / 2 - 1, h + h / 2 + 2) }
                    case(f, "fillPoly", "${w}x$h #$t", { int("w", w); int("h", h); i32("xs", xs); i32("ys", ys) }) {
                        val m = Mask(w, h); Cv.fillPoly(m, xs, ys); mask("out", m)
                    }
                    val fx = FloatArray(n) { xs[it] + rnd.nextInt(8) / 8f }
                    val fy = FloatArray(n) { ys[it] + rnd.nextInt(8) / 8f }
                    case(f, "polygonArea", "${w}x$h #$t", { f32("xs", fx); f32("ys", fy) }) { dbl("out", Cv.polygonArea(fx, fy)) }
                }
            }
        }
        // 陣列原語（階段 3b）
        fam("arrays") { f ->
            for ((rows, cols) in listOf(1 to 1, 1 to 9, 9 to 1, 7 to 13, 360 to 41)) for ((nt, nr) in listOf(0 to 0, 1 to 2, 3 to 6, 20 to 1)) {
                val s = IntArray(rows * cols) { if (rnd.nextInt(4) == 0) rnd.nextInt(1000) else 0 }
                case(f, "maxFilterWrapRows", "${rows}x$cols / $nt,$nr", { i32("in", s, rows, cols); i32("args", intArrayOf(nt, nr)) }) {
                    i32("out", Cv.maxFilterWrapRows(s, rows, cols, nt, nr), rows, cols)
                }
            }
            for (t in 0 until 200) {
                val n = if (t < 20) t else rnd.nextInt(1, 3000)
                val v = IntArray(n) { if (t % 3 == 0) rnd.nextInt(5) else rnd.nextInt() }
                case(f, "npArgsort", "#$t n=$n", { i32("in", v) }) { i32("out", Cv.npArgsort(v)) }
                val dv = DoubleArray(n) { (rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(-5, 6).toDouble()) }
                case(f, "npSum", "#$t n=$n", { f64("in", dv) }) { dbl("out", Cv.npSum(dv)) }
            }
            repeat(300) { t ->
                val a = (rnd.nextDouble() - 0.5) * 1e4
                val b = if (t % 5 == 0) 0.0 else (rnd.nextDouble() - 0.5) * 1e4
                val c = if (t % 7 == 0) a else (rnd.nextDouble() - 0.5) * 1e4
                case(f, "eigh2", "#$t", { f64("in", doubleArrayOf(a, b, c)) }) { f64("out", Cv.eigh2(a, b, c)) }
            }
        }
        check(ran == knownFamilies) { "NativeDumpTest：knownFamilies 與實際錄的族不一致：$ran" }
        File(dir, "index.tsv").writeText(index.toString())
        println("NativeDumpTest 原語：\n$index")
    }
}
