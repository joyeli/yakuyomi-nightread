package li.joye.yakuyomi.nightread

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 夜讀重繪的入口與合成階段。
 *
 * 用法：偵測與人物分割在外面做完，把灰階、文字筆畫遮罩、文字區、人物遮罩包成 [NightReadInput]
 * 丟進 [render]，拿回重建好的暗色頁。這個模組不碰任何推論框架也不碰 android.graphics，
 * 所以 JVM 測試可以對 Python 的 fixture 直接比對。
 *
 * 分區的待遇與各階段在判斷什麼，見 `docs/ARCHITECTURE.md`。
 */
/** 各階段的中間量（遮罩面積等），parity 除錯用。正式路徑傳 null。 */
typealias NightReadDebug = (stage: String, value: Int) -> Unit

object NightRead {

    /**
     * 產品輸出的**規則版本**。同一張頁、同一組亮度設定，產品兩檔（標準＝[NightTier.L2]、更多＝[NightTier.L3]）的成品只要會變，
     * 就加 1——不管改的是這個模組的規則或預設參數、engine 的 NightReadRenderer（縮圖上限、偵測／分割配方）、產品端餵進來
     * 的東西（譯後頁的字框），還是人物模型換版。輸出逐位元不變的改動（純加速、只影響研究端的 L1／完整管線）不加。
     * 一次發版累積好幾項改動只要加一次；交給使用者真機測的 debug APK 也算發版。
     *
     * 產品端（Yakuyomi）把它記在每頁夜讀檔旁邊：版本比這裡舊的頁，章節列標「可更新」，設定 › 夜讀 可一鍵重新產生舊版頁，
     * 不會自動重做。亮度偏好不算規則（那是「以目前設定重新產生」管的）。規則與歷史的正本在 DECISIONS「規則版本」一節。
     *
     * 守門：測試 RulesVersionGuardTest 記每個版本的產品兩檔成品摘要，這個模組的輸出變了、版本沒加就失敗。夜讀用到的模型
     * （人物分割、偵測器）只能跟 APK 一起換、換檔名、同時加版本（記號不記模型，見 DECISIONS）。
     *
     * 歷史：
     *  - 1（2026-10-03）：開始記版本。這之前產生的夜讀頁沒有版本記錄，一律當 0（舊版）。同日提出、還沒進產品的三項規則
     *    修改（第 13 頁雲下、ch34_014 牆面、灰圈縮法）進產品時要加到 2：版本 1 的 APK 已交給使用者。
     *  - 2（2026-10-03）：人物外灰圈收細（折衷版，[Ring]；標準與更多都套）。版本 2 的 debug APK 同日交給使用者，之後的規則
     *    修改（含研究中的「更多」背景物件修改：雲下、牆面）一律加到 3。
     *  - 3（2026-10-04，還沒交出）：「更多」背景物件規則（[BgObjects]；只動更多，標準與版本 2 逐像素相同）。效果線 A／C（集中線、
     *    閃光不算物件）若在這版 APK 交出前做完，也算在 3 裡。
     */
    const val RULES_VERSION: Int = 3

    /**
     * 重繪一頁。
     *
     * 前半是分析（頁型、白元件、氣泡、貼紙計畫、場景曲線、格溝／頁邊），後半是合成（貼紙三檔篩選 → 留白（出血格過濾）→
     * 格溝／頁邊 → 貼紙 →（「更多」）背景物件規則（[BgObjects]）→ 氣泡 → 偽泡 → 人頭一致化 → 剩餘填色 → 灰圈收細（[Ring]）→
     * 人物還原（跳過泡與格溝））。內部就是 [analyze] → [keepFor] → [composeTier]，與 [renderTiers] 同一條程式路徑。
     */
    fun render(
        input: NightReadInput,
        p: NightReadParams = NightReadParams(),
        debug: NightReadDebug? = null,
    ): NightReadResult = render(input, p, debug, null)

    /**
     * 同 [render]，另把格溝與出血過濾的中間結果存進 [diag]（parity／除錯用，不影響輸出）：
     * `sep`（[Separators.Layer]）、`band0`／`band_post`／`band_final`（[Mask]：veto 前／veto 後／過濾後的留白帶）、
     * `pieces`（[Bleed.Piece] 清單）、`t_sep`／`t_bleed`（ms）。鍵名同研究端 `compose(diag=)`。
     */
    internal fun render(
        input: NightReadInput,
        p: NightReadParams,
        debug: NightReadDebug?,
        diag: MutableMap<String, Any>?,
    ): NightReadResult {
        val a = analyze(input, p, debug, diag, shared = false)
        return composeTier(a, keepFor(a, p), p, debug, diag)
    }

    /**
     * 一次分析、依序產出多檔（產品三檔＝`NightTier.entries.map { it.apply(base) }`）。
     *
     * 與檔位無關的全部（頁型、白元件、泡與封縫、貼紙計畫、場景曲線、格溝／頁邊、留白帶、人物還原遮罩…）只算一次，每檔
     * 只做貼紙篩選與合成。第 k 檔的輸出與 `render(input, tiers[k]).out` **逐位元相同**（SharedTierTest 守）。
     *
     * **串流**：每檔合成完就呼叫 [sink]（檔位索引, 成品），回傳後才算下一檔——呼叫端在 sink 裡編碼、放掉，同一時間只有
     * 一檔的成品活著（記憶體峰值不高於單檔 [render]）。某檔的**合成鍵**與**前一檔**相同時不合成、傳 null：合成鍵＝keep、
     * 擢升元件、加「更多」新規則前的 keep（無框頁留白層用）、「更多」的兩項繪製開關與參數（[drawKey]）——成品只由這些與共用
     * 分析決定，所以那一檔的成品必定跟前一檔逐位元相同。「更多」的檔即使 keep 與前一檔相同，只要繪製開關不同（描亮邊不蓋
     * 黑、頁緣種子）就照樣合成：成品可能不同。合成了不保證不同（keep 不同、成品逐像素相同的也有），逐像素去重交給呼叫端
     * （engine 的 NightReadRenderer.streamTiers 對上一個交出的檔逐像素比）。第 0 檔不會是 null。
     *
     * 前提（不符就 [IllegalArgumentException]）：各檔之間只差 stickerMode／stickerRoughMax／stickerSimpleMinFrac 與
     * 「更多」新規則（[NightReadParams.more]），且每檔都關偽泡與亮島填黑——這兩項會讓人物還原遮罩、留白跟檔位有關，要開得
     * 先擴充 [composeTier]。[debug] 照單檔的回呼名；每檔合成前另送一次 `("tier", 檔位索引)`（被跳過的檔不送）。
     */
    fun renderTiers(
        input: NightReadInput,
        tiers: List<NightReadParams>,
        debug: NightReadDebug? = null,
        sink: (Int, Gray?) -> Unit,
    ) {
        require(tiers.isNotEmpty()) { "renderTiers：至少要一檔" }
        val p0 = tiers[0]
        for ((k, t) in tiers.withIndex()) {
            require(!t.pseudoBubbles && !t.harmonize) {
                "renderTiers：第 $k 檔開了偽泡或亮島填黑——這兩項讓人物還原／留白跟檔位有關，不能共用分析"
            }
            require(
                t.copy(stickerMode = p0.stickerMode, stickerRoughMax = p0.stickerRoughMax,
                    stickerSimpleMinFrac = p0.stickerSimpleMinFrac, more = p0.more) == p0,
            ) { "renderTiers：第 $k 檔與第 0 檔除了貼紙篩選三欄與「更多」新規則之外還有別的參數不同" }
        }
        val a = analyze(input, p0, debug, null, shared = tiers.size > 1)
        var prev: List<Any?>? = null
        for ((k, t) in tiers.withIndex()) {
            val plan = keepFor(a, t)
            val key = listOf(plan.accept, plan.promoted, plan.baseAccept, drawKey(t, plan))
            if (prev != null && key == prev) {
                sink(k, null)
                continue
            }
            prev = key
            debug?.invoke("tier", k)
            emitTier(a, plan, t, debug, k, sink)
        }
    }

    /**
     * 合成階段會讀、各檔可以不同的繪製參數（「更多」的 P1／P2，只在貼紙層用）；沒開或這一檔沒有要塗的貼紙＝null（貼紙層
     * 根本不跑，兩項不影響成品）。[renderTiers] 的合成鍵用。
     */
    private fun drawKey(p: NightReadParams, plan: Sticker.Plan): Any? {
        val m = p.more
        if (!m.enabled) return null
        // 背景物件規則（[BgObjects]）在貼紙之外也會塗（亮背景區），keep 是空的也要合成
        val obj = if (p.obj.enabled) p.obj else null
        if (plan.accept.isEmpty() && obj == null) return null
        return listOf(m.keepDarkStroke, m.edgeSeedFallback, m.edgeBand, m.edgeReach, obj)
    }

    /** 合成一檔交給 sink。獨立成函式：回傳後這一檔的成品與中間量就沒有任何參照（下一檔合成時不跟它疊高峰值）。 */
    private fun emitTier(
        a: Analysis, plan: Sticker.Plan, p: NightReadParams, debug: NightReadDebug?, k: Int, sink: (Int, Gray?) -> Unit,
    ) {
        sink(k, composeTier(a, plan, p, debug, null).out)
    }

    /**
     * 與檔位無關的分析結果（[analyze] 產出、[composeTier] 消費）。各欄**不得被合成階段改寫**——三檔共用同一份。
     *
     * [shared]＝要合成不只一檔：這時才快取幾個每檔都要、但單檔用一次就丟的遮罩（泡外 41px 圈、留白帶、人物還原遮罩），
     * 而且一律存成 1 bit/px（[packBits]），用的時候才展開——共用分析多佔的 heap 只有約 0.4 B/px。人物還原的
     * alpha（4 B/px 浮點）刻意不留：每檔由快取的遮罩重做高斯（幾十 ms），否則它會疊在後面幾檔的合成峰值上（桌面量最低
     * heap：留 alpha 比單檔高 2 MB，這樣做持平）。單檔 [render] 不快取。
     */
    internal class Analysis(
        val g: Gray,
        val seg: Mask,
        val regions: List<TextRegion>,
        /** 人物遮罩：模型原輸出（未收邊未平滑）。 */
        val charRaw: Mask,
        /** 人物遮罩：收邊＋平滑後。 */
        val charMask: Mask,
        val lh: Mask,
        val lv: Mask,
        val frame: Mask,
        val frameless: Boolean,
        val wc: Regions.WhiteComponents,
        /** [Regions.buildBubbleMask] 原樣的泡（未經人物修剪、含封縫救回的泡）：剩餘填色扣它。 */
        val bubbleUntrim: Mask,
        /** 大泡走核心填色的元件 id（剩餘填色的來源之一）。 */
        val cored: Set<Int>,
        /** 漏泡封縫救回的泡（[Regions.BubbleResult.sealed]，未經人物修剪）。 */
        val sealed: Mask,
        val anySealed: Boolean,
        /** 經人物修剪後的泡（含封縫救回的）＝結果的 bubble。 */
        val bubble: Mask,
        /** 結構層（格溝、線稿密度否決、出血過濾、貼紙）看的泡＝[bubble] − [sealed]。 */
        val bubbleStruct: Mask,
        /** 封縫救回的泡（修剪後）：從泡重繪起才當泡；沒有＝null。 */
        val bubbleLocal: Mask?,
        /** 被人物修剪掉的泡區（字頂層用）。 */
        val lost: Mask,
        /** 貼紙計畫（安全網之後、三檔篩選之前）。 */
        val plan: Sticker.Plan,
        /** 場景曲線＋墨線增亮（sceneFinal）：每檔合成的底，也是人物區最終還原的場景調。 */
        val scene: FImg,
        /** 任意角度格溝／頁邊圖層；[NightReadParams.separators] 關＝null。 */
        val layer: Separators.Layer?,
        /** 分析用的參數（plain 判準要用；各檔的 plain 參數相同）。 */
        val p: NightReadParams,
        val shared: Boolean,
        /** 每像素彩度（「更多」C3 的留白候選要量安全網的彩度門）。 */
        val chroma: Gray?,
        /**
         * 灰圈收細的頁面級證據（[Ring.evidence]，1 bit/px）：只跟灰階與人物原輸出有關，分析最後算一次、各檔共用；灰圈收細關＝null。
         * 放在分析而不是合成：合成時活著的遮罩多（成品、還原遮罩、灰圈收集的幾張），證據的暫存疊上去會墊高記憶體峰值。
         */
        val ringEvidence: LongArray?,
    ) {
        val sep: Mask? get() = layer?.sep

        /** 「更多」新規則與檔位無關的部分（逐元件特徵、C3 留白候選）；只有開了 [MoreRuleParams.enabled] 的檔才填。 */
        val moreCache = Sticker.MoreCache()

        /** 「泡附近」只看原本的泡：封縫救回的泡不延伸泡外那一圈（剩餘填色用）。 */
        val bubbleS0: Mask by lazy(LazyThreadSafetyMode.NONE) {
            if (anySealed) bubbleUntrim.andNot(sealed) else bubbleUntrim
        }

        /** plain 元件集合（與檔位無關；PLAIN／SIMPLE 檔才需要，ALL 不算）。 */
        val plain: Set<Int> by lazy(LazyThreadSafetyMode.NONE) {
            Sticker.plainSet(g, wc.cc, plan, charRaw, frame, p)
        }

        // ── 只在 shared 時用的快取（1 bit/px）──
        var restNearBits: LongArray? = null
        /** 留白帶快取的鍵（gutterShow）；帶本身 null＝這組留白元件是空的、不塗。 */
        var gutterKey: Set<Int>? = null
        var gutterBandBits: LongArray? = null
        /** 留白帶裡「只因人物自己的墨被線稿否決」的像素（灰圈收細用；與 [gutterBandBits] 同一個鍵）；null＝沒有。 */
        var gutterGvBits: LongArray? = null
        /** 人物還原遮罩（沒有偽泡時與檔位無關；lazy：第一檔合成到這一步才算）。 */
        var restoreBits: LongArray? = null
        /** 灰圈收細只跟頁面有關的兩張遮罩（[Ring.PageParts]：人物原輸出閉運算、人物遮罩外擴）；lazy。 */
        var ringClosedBits: LongArray? = null
        var ringNearBits: LongArray? = null
    }

    /**
     * 分析：與檔位無關的全部。順序與拆分前的 render 相同（輸出逐位元不變）；[diag] 的 `t_sep`／`sep` 在這裡填。
     */
    internal fun analyze(
        input: NightReadInput,
        p: NightReadParams,
        debug: NightReadDebug?,
        diag: MutableMap<String, Any>?,
        shared: Boolean,
    ): Analysis {
        val g = Regions.normalizePaper(input.gray, input.chroma, p)
        val seg = input.seg

        val charRaw = input.charMask
        val charMask = Regions.smoothCharMask(Regions.snapCharMask(charRaw, g, p), g, p)
        debug?.invoke("charRaw", charRaw.count())
        debug?.invoke("charMask", charMask.count())
        val (lh, lv) = Regions.frameLineMask(g, p)
        val frame = lh or lv
        val frameless = Regions.pageIsFrameless(lh, lv, p)
        val wc = Regions.classifyWhiteComponents(g, p)
        debug?.invoke("classifyWhite", 0)
        val excluded = wc.gutterIds + wc.panelIds
        val bubbleRes = Regions.buildBubbleMask(g, input.regions, seg, wc.cc, excluded, p, input.chroma, debug, charRaw)
        // 漏泡封縫救回的泡：只進泡的重繪層（泡重繪、偽泡、亮島、人物還原的泡優先），不進格溝／留白／出血過濾／線稿密度否決／
        // 貼紙／泡外圈這些結構層——泡是它們的隔板或證據，新泡餵進去會改到泡外。
        val sealedOnly = bubbleRes.sealed
        val anySealed = sealedOnly.any()
        debug?.invoke("buildBubble", 0)
        // 貼紙計畫（安全網）；三檔篩選在 keepFor。人物用**原始**遮罩（未收邊）、格線用 lh|lv
        val plan = Sticker.plan(g, input.chroma, wc, frameless, input.regions, frame, p)
        debug?.invoke("stickerPlan", 0)

        // ── 圖層優先權：乾淨泡整顆塗黑、泡遮罩不跨進人物 ─────────────────
        var bubble = bubbleRes.bubble
        var bubbleGuard = charMask
        if (bubble.any()) {
            val bcc = Cv.ccStats(bubble, 8)
            val cb = cleanBubbles(g, bcc, seg, p)
            val clean = cb.clean
            if (p.bubbleGuardRaw) {
                // 泡內淺條修法 c：字確認的泡只讓開人物原輸出（收邊／平滑長出來的安全邊被泡蓋過），其餘照舊讓開收邊後的遮罩
                val confirmed = confirmedBubbles(bcc, g.w, g.h, seg, input.regions, p)
                if (confirmed.any()) bubbleGuard = (charRaw and confirmed) or charMask.andNot(confirmed)
            }
            bubbleGuard = bubbleGuard.andNot(clean)
            if (p.bubbleLeak) {
                // 漏泡判準：乾淨泡裡壓在人物原輸出上、貼著的泡外緣又沒有框線的那一塊，還給人物（無框泡的白直接連到白髮高光／白衣）
                val leak = leakIntoCharacter(g, clean, cb.filled, bubble, charRaw, p)
                if (leak != null) bubbleGuard = bubbleGuard or leak
            }
        }
        debug?.invoke("cleanBubbles", 0)
        val bubbleBeforeTrim = bubble.copy()
        val removed = bubble and bubbleGuard
        if (removed.any()) {
            // 只扣「從泡邊緣伸進來的人物」：遮罩誤蓋到泡中央時粗暴地扣會把泡挖出洞＝白泡
            val rcc = Cv.ccStats(removed, 8)
            // 「碰到泡外緣」＝自己在泡內、但四鄰有一個在泡外。直接看鄰居就好——
            // 原本是 `dilate(bubble.not(), 3×3)`，那會對整頁取反再膨脹兩趟全頁運算。
            val touching = BooleanArray(rcc.n)
            val bw = bubble.w
            val bh = bubble.h
            for (y in 0 until bh) {
                val base = y * bw
                for (x in 0 until bw) {
                    val i = base + x
                    if (!removed.data[i]) continue
                    val l = rcc.labels[i]
                    if (l == 0 || touching[l]) continue
                    val edge = x == 0 || y == 0 || x == bw - 1 || y == bh - 1 ||
                        !bubble.data[i - 1] || !bubble.data[i + 1] ||
                        !bubble.data[i - bw] || !bubble.data[i + bw]
                    if (edge) touching[l] = true
                }
            }
            var anyTouch = false
            for (l in 1 until rcc.n) if (touching[l]) { anyTouch = true; break }
            if (anyTouch) {
                val b2 = bubble.copy()
                for (i in b2.data.indices) {
                    val l = rcc.labels[i]
                    if (l > 0 && touching[l]) b2.data[i] = false
                }
                bubble = b2
            }
        }
        val lost = bubbleBeforeTrim.andNot(bubble)
        // 封縫救回的泡從泡重繪起才當泡；上面的結構層只看 bubbleStruct
        val bubbleStruct = if (anySealed) bubble.andNot(sealedOnly) else bubble
        val bubbleLocal = if (anySealed) bubble and sealedOnly else null

        // ── 場景曲線（各檔合成的底；人物區最終一律還原成它）──────────────────
        val scene = sceneFinal(g, seg, p)
        debug?.invoke("sceneFinal", 0)

        // 任意角度格溝／頁邊（SEP）：先算好——出血過濾要拿它當結構證據，貼紙層之前塗、人物還原跳過它。
        // 圖層規則在 Separators.build 裡：只扣泡 ⊕7（不扣人物）、單條溝被泡吃過半整條丟、碎塊丟。
        val tSep = System.nanoTime()
        val layer = if (p.separators) Separators.build(g, seg, bubbleStruct, frame, p) else null
        if (diag != null) {
            diag["t_sep"] = (System.nanoTime() - tSep) / 1e6
            diag["t_bleed"] = 0.0
            if (layer != null) diag["sep"] = layer
        }
        debug?.invoke("separators", layer?.sep?.count() ?: 0)

        // 灰圈收細的頁面級證據（diag 要有 "ring"＝true 才存中間遮罩與耗時，免得一般的 diag 呼叫多抱幾張整頁遮罩）
        val ringEvidence = if (p.ring.enabled) packBits(Ring.evidence(g, charRaw, p, ringDiag(diag))) else null
        debug?.invoke("ringEvidence", 0)

        return Analysis(
            g = g, seg = seg, regions = input.regions, charRaw = charRaw, charMask = charMask,
            lh = lh, lv = lv, frame = frame, frameless = frameless, wc = wc,
            bubbleUntrim = bubbleRes.bubble, cored = bubbleRes.cored, sealed = sealedOnly, anySealed = anySealed,
            bubble = bubble, bubbleStruct = bubbleStruct, bubbleLocal = bubbleLocal, lost = lost,
            plan = plan, scene = scene, layer = layer, p = p, shared = shared, chroma = input.chroma,
            ringEvidence = ringEvidence,
        )
    }

    /**
     * 貼紙計畫過安全網後再過三檔篩選（[StickerMode]；ALL＝原樣）＝[Sticker.filterPlan] 拆成「plain 集合（與檔位無關、
     * [Analysis.plain] 算一次）＋依檔位挑」；開了「更多」新規則（[MoreRuleParams.enabled]）的檔再接 [Sticker.moreSelect]
     * （只加不減；與檔位無關的特徵存在 [Analysis.moreCache]）。回傳的 accept＝keep、baseAccept＝加新規則前的 keep。
     */
    internal fun keepFor(a: Analysis, p: NightReadParams): Sticker.Plan {
        val base = if (p.stickerMode == StickerMode.ALL) a.plan else Sticker.selectKeep(a.plan, a.plain, p)
        if (!p.more.enabled) return base
        return Sticker.moreSelect(
            a.g, a.chroma, a.wc, a.frameless, a.regions, a.frame, a.charMask, a.charRaw, a.bubbleUntrim, a.seg,
            a.plan, base, p, a.moreCache,
        )
    }

    /**
     * 合成一檔：只由 [plan]（keep 與其擢升元件）和 [a] 決定；[p] 只讀繪製參數（各檔相同）。
     */
    internal fun composeTier(
        a: Analysis,
        plan: Sticker.Plan,
        p: NightReadParams,
        debug: NightReadDebug?,
        diag: MutableMap<String, Any>?,
    ): NightReadResult {
        val w = a.g.w
        val h = a.g.h
        // 「更多」背景物件規則（[BgObjects]；只在開了「更多」新規則的檔）的整頁量測：在合成配置成品之前算（它的暫存不疊在合成的峰值上）
        val objCtx = ObjHolder(if (p.more.enabled && p.obj.enabled) objContext(a, p, diag) else null)
        debug?.invoke("objContext", 0)
        // 無框頁的留白層用加「更多」新規則之前的 keep（新收的元件整顆當貼紙塗、留白帶照舊；研究端 P3）
        val gutterShow = if (a.frameless) a.wc.gutterIds - plan.baseAccept else a.wc.gutterIds
        val gutter = maskOfIds(a.wc.cc, gutterShow, w, h)
        val rest = bubbleRest(a, plan, p, debug)
        val out = compose(a, gutter, gutterShow, plan, rest?.rest, rest?.pre, objCtx, p, debug, diag)
        return NightReadResult(out, gutter, a.bubble, a.charMask, a.frameless, plan.accept, plan.promoted, a.sep)
    }

    /** [bubbleRest] 的結果：要填的剩餘部分，與讓開人物之前的範圍（灰圈收細的可認領像素之一）。 */
    private class BubbleRest(val rest: Mask, val pre: LongArray?)

    /**
     * 剩餘填色：泡元件減掉核心＝泡框外的背景白，沒有別的機制會接手（null＝沒有來源元件）。
     * 獨立成函式：中間的 41px 圈遮罩只活在這裡，不陪著整個合成。
     */
    private fun bubbleRest(a: Analysis, plan: Sticker.Plan, p: NightReadParams, debug: NightReadDebug?): BubbleRest? {
        val restIds = a.cored + plan.promoted
        if (restIds.isEmpty()) return null
        var rest = maskOfIds(a.wc.cc, restIds, a.g.w, a.g.h).andNot(a.bubbleUntrim)
        // 只填泡框周圍這一圈：全部取消會吃掉白鬍老人的鬍鬚（「泡附近」只看原本的泡，見 Analysis.bubbleS0）
        val near = restNear(a, p)
        if (near != null) rest = rest and near
        val pre = if (p.ring.enabled) packBits(rest) else null     // 1 bit/px：合成的峰值時它還用不到
        rest = rest.andNot(a.charMask)          // 背景填色一律讓開人物（andNot 配新遮罩：pre 保持讓開之前）
        debug?.invoke("bubbleRest", rest.count())
        return BubbleRest(rest, pre)
    }

    /** 泡外 [NightReadParams.bubbleRestNear] 那一圈（泡是空的＝null＝不限）；shared 時快取。 */
    private fun restNear(a: Analysis, p: NightReadParams): Mask? {
        a.restNearBits?.let { return unpackBits(it, a.g.w, a.g.h) }
        val s0 = a.bubbleS0
        if (!s0.any()) return null
        val near = Cv.dilate(s0, Cv.ellipse(p.bubbleRestNear * 2 + 1))
        if (a.shared) a.restNearBits = packBits(near)
        return near
    }

    /** 遮罩 → 1 bit/px（共用分析的快取用；逐位元可逆）。 */
    private fun packBits(m: Mask): LongArray {
        val d = m.data
        val bits = LongArray((d.size + 63) ushr 6)
        for (i in d.indices) if (d[i]) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
        return bits
    }

    /** [packBits] 的反向：每次都展開成新的 [Mask]（呼叫端可以隨意改它，不會動到快取）。 */
    private fun unpackBits(bits: LongArray, w: Int, h: Int): Mask {
        val m = Mask(w, h)
        val d = m.data
        for (i in d.indices) d[i] = (bits[i ushr 6] ushr (i and 63)) and 1L != 0L
        return m
    }

    /**
     * 把一組元件 id 攤成遮罩。
     *
     * ⚠️ 用 BooleanArray 查表而不是 `cc.labels[i] in ids`：後者每個像素都做一次 HashSet 查詢
     * 並把 Int 裝箱，2.6 MPx 上是實打實的成本，而這個函式在合成階段被呼叫數次。
     */
    private fun maskOfIds(cc: CC, ids: Set<Int>, w: Int, h: Int): Mask {
        val m = Mask(w, h)
        if (ids.isEmpty()) return m
        val want = BooleanArray(cc.n)
        for (id in ids) if (id in 0 until cc.n) want[id] = true
        for (i in m.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && want[l]) m.data[i] = true
        }
        return m
    }

    /**
     * 「乾淨泡」＝真正的空白容器：填洞後內部的非字墨只有 0 到 0.3%，被誤判成泡的臉則有五官
     * 和陰影、超過 1%。判定為真泡的整顆塗黑、不被人物遮罩扣。
     *
     * 第二道保險是文字佔比：被誤判的白髮、白手區塊幾乎全是字筆畫本身。
     *
     * [NightReadParams.bubbleCleanInkHoles]（預設開）時洞只算非紙白：字欄之間沒收進泡的紙白小縫不算「泡裡有別的東西」。
     * [cc]＝泡遮罩的 8 連通標號（與 [confirmedBubbles] 共用）。
     *
     * 回傳 [CleanBubbles]：`clean`＝乾淨泡本身；`filled`＝乾淨泡加上它們的洞（補洞後的實心塊，洞含字筆畫附近的；
     * [leakIntoCharacter] 拿它判「泡外」——泡的洞不算泡外）。
     */
    private fun cleanBubbles(g: Gray, cc: CC, seg: Mask, p: NightReadParams): CleanBubbles {
        val clean = Mask(g.w, g.h)
        val filled = Mask(g.w, g.h)
        val segD = Cv.dilate(seg, Cv.ellipse(7))
        for (i in 1 until cc.n) {
            val a = cc.area[i]
            if (a < 4000) continue
            val bx = max(0, cc.left[i] - 2)
            val by = max(0, cc.top[i] - 2)
            val bx1 = min(g.w, cc.left[i] + cc.width[i] + 2)
            val by1 = min(g.h, cc.top[i] + cc.height[i] + 2)
            val sw = bx1 - bx
            val sh = by1 - by
            val blob = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (by + y) * g.w + bx
                for (x in 0 until sw) blob.data[y * sw + x] = cc.labels[src + x] == i
            }
            val holes = Cv.holes(blob)
            var holeInk = 0
            var textN = 0
            var blobN = 0
            for (y in 0 until sh) {
                val src = (by + y) * g.w + bx
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if (holes.data[idx] && !segD.data[src + x] &&
                        (!p.bubbleCleanInkHoles || g.data[src + x] < p.whiteTh)
                    ) holeInk++
                    if (blob.data[idx]) { blobN++; if (seg.data[src + x]) textN++ }
                }
            }
            if (holeInk.toDouble() / a >= p.bubbleCleanWins) continue
            if (blobN > 0 && textN.toDouble() / blobN > p.bubbleCleanTextMax) continue
            for (y in 0 until sh) {
                val dst = (by + y) * g.w + bx
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if (blob.data[idx]) { clean.data[dst + x] = true; filled.data[dst + x] = true }
                    else if (holes.data[idx]) filled.data[dst + x] = true
                }
            }
        }
        return CleanBubbles(clean, filled)
    }

    /** [cleanBubbles] 的結果：乾淨泡，與乾淨泡加上它們的洞。 */
    private class CleanBubbles(val clean: Mask, val filled: Mask)

    /**
     * 漏泡判準（[NightReadParams.bubbleLeak]；研究端 `BUBBLE_LEAK`）：乾淨泡整顆塗黑的前提是「泡畫在人物之上」。沒有框線的泡，
     * 泡的白會直接連到人物身上的白（白髮高光、白襯衫），整顆塗就把人物一起塗掉。泡的內部一定停在框線上——人物站在泡後面時，
     * 泡與人物之間隔著框線；泡的白沒被框線擋住、直接流進人物原輸出的那一段不是泡，是人物身上的白。
     *
     * 做法：乾淨泡 ∩ 人物**原輸出** [charRaw] 的 8 連通塊（面積 ≥ [NightReadParams.bubbleLeakMinArea]）逐塊看它貼著的
     * 泡外緣——塊外擴 [NightReadParams.bubbleLeakRing] px（橢圓核）、扣掉「泡與乾淨泡的洞」[filled]∪[bubble] 的那一圈。
     * 圈少於 [NightReadParams.bubbleLeakRingMin] px＝這塊幾乎被泡包住，不判。圈上「有線」的像素（以它為中心
     * [NightReadParams.bubbleLeakWin]² 的窗裡最亮減最暗 ≥ [NightReadParams.bubbleLeakRange]）佔比 <
     * [NightReadParams.bubbleLeakEdgeMax]＝這段泡緣沒有框線＝這一塊還給人物（回傳的遮罩併回泡的讓開遮罩，後面的修剪照舊）。
     *
     * 看局部亮度差而不是「墨（< inkDarkTh）佔比」：2–3 px 的框線糊掉以後中心亮度會高過墨門檻，但線與旁邊的落差還在；
     * 柔邊（白連白）沒有落差。窗在頁緣截掉（同 cv2 的 dilate／erode 預設邊界）。沒有任何一塊觸發＝回 null。
     */
    private fun leakIntoCharacter(g: Gray, clean: Mask, filled: Mask, bubble: Mask, charRaw: Mask, p: NightReadParams): Mask? {
        val w = g.w
        val h = g.h
        val cand = clean and charRaw
        if (!cand.any()) return null
        val cc = Cv.ccStats(cand, 8)
        val kr = Cv.ellipse(2 * p.bubbleLeakRing + 1)
        val pad = p.bubbleLeakRing + 1
        val half = p.bubbleLeakWin / 2
        var leak: Mask? = null
        for (k in 1 until cc.n) {
            if (cc.area[k] < p.bubbleLeakMinArea) continue
            val x0 = max(0, cc.left[k] - pad)
            val y0 = max(0, cc.top[k] - pad)
            val x1 = min(w, cc.left[k] + cc.width[k] + pad)
            val y1 = min(h, cc.top[k] + cc.height[k] + pad)
            val sw = x1 - x0
            val sh = y1 - y0
            val comp = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until sw) comp.data[y * sw + x] = cc.labels[src + x] == k
            }
            val dil = Cv.dilate(comp, kr)
            var ringN = 0
            var edgeN = 0
            for (y in 0 until sh) {
                val py = y0 + y
                val src = py * w + x0
                for (x in 0 until sw) {
                    if (!dil.data[y * sw + x]) continue
                    val i = src + x
                    if (filled.data[i] || bubble.data[i]) continue
                    ringN++
                    // 局部亮度差：窗在頁緣截掉
                    val px = x0 + x
                    var lo = 255
                    var hi = 0
                    for (yy in max(0, py - half)..min(h - 1, py + half)) {
                        val row = yy * w
                        for (xx in max(0, px - half)..min(w - 1, px + half)) {
                            val v = g.data[row + xx]
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                    }
                    if (hi - lo >= p.bubbleLeakRange) edgeN++
                }
            }
            if (ringN < p.bubbleLeakRingMin) continue
            if (edgeN.toDouble() / ringN >= p.bubbleLeakEdgeMax) continue
            val out = leak ?: Mask(w, h).also { leak = it }
            for (y in 0 until sh) {
                val dst = (y0 + y) * w + x0
                for (x in 0 until sw) if (comp.data[y * sw + x]) out.data[dst + x] = true
            }
        }
        return leak
    }

    /**
     * 「字確認的泡」（[NightReadParams.bubbleGuardRaw] 用；研究端 `confirmed_bubbles`）：泡（人物修剪前）的 8 連通塊裡，
     * 字佔比 ≤ [NightReadParams.bubbleCleanTextMax]（是容器、不是只有字筆畫），而且至少一個字框的**完整** bbox 面積有
     * ≥ [NightReadParams.bubbleConfirmTextIn] 落在填洞後的塊內。不限面積。窗＝塊 bbox 外擴 2（夾頁緣），字框只數與窗的交集。
     *
     * 先用 bbox 粗篩（交集面積本身就不到門檻的字框不可能過），沒有候選字框的塊不配置窗遮罩、不填洞——小碎塊很多。
     */
    private fun confirmedBubbles(cc: CC, w: Int, h: Int, seg: Mask, regions: List<TextRegion>, p: NightReadParams): Mask {
        val confirmed = Mask(w, h)
        if (regions.isEmpty()) return confirmed
        val cand = ArrayList<TextRegion>()
        for (i in 1 until cc.n) {
            val bx = max(0, cc.left[i] - 2)
            val by = max(0, cc.top[i] - 2)
            val bx1 = min(w, cc.left[i] + cc.width[i] + 2)
            val by1 = min(h, cc.top[i] + cc.height[i] + 2)
            cand.clear()
            for (r in regions) {
                val full = max(0, r.x1 - r.x0).toLong() * max(0, r.y1 - r.y0)
                if (full <= 0) continue
                val ix = min(r.x1, bx1) - max(r.x0, bx)
                val iy = min(r.y1, by1) - max(r.y0, by)
                if (ix <= 0 || iy <= 0) continue
                if (ix.toDouble() * iy < p.bubbleConfirmTextIn * full) continue
                cand.add(r)
            }
            if (cand.isEmpty()) continue
            val sw = bx1 - bx
            val sh = by1 - by
            val blob = Mask(sw, sh)
            var textN = 0
            for (y in 0 until sh) {
                val src = (by + y) * w + bx
                for (x in 0 until sw) {
                    if (cc.labels[src + x] == i) {
                        blob.data[y * sw + x] = true
                        if (seg.data[src + x]) textN++
                    }
                }
            }
            if (textN.toDouble() / cc.area[i] > p.bubbleCleanTextMax) continue
            val holes = Cv.holes(blob)
            var ok = false
            for (r in cand) {
                val full = (r.x1 - r.x0).toLong() * (r.y1 - r.y0)
                val cx0 = max(r.x0, bx) - bx
                val cy0 = max(r.y0, by) - by
                val cx1 = min(r.x1, bx1) - bx
                val cy1 = min(r.y1, by1) - by
                var n = 0L
                for (y in cy0 until cy1) {
                    val row = y * sw
                    for (x in cx0 until cx1) if (blob.data[row + x] || holes.data[row + x]) n++
                }
                if (n.toDouble() >= p.bubbleConfirmTextIn * full) { ok = true; break }
            }
            if (!ok) continue
            for (y in 0 until sh) {
                val dst = (by + y) * w + bx
                for (x in 0 until sw) if (blob.data[y * sw + x]) confirmed.data[dst + x] = true
            }
        }
        return confirmed
    }

    // ── 場景曲線 ─────────────────────────────────────────────────────

    /** 全線性場景曲線：黑→floor、紙白→ceil。線性＝保序，畫面不會反相。 */
    private fun lutScene(p: NightReadParams): IntArray = IntArray(256) {
        (p.sceneFloor + (p.dimCeil - p.sceneFloor) * it / 255.0).roundToInt().coerceIn(0, 255)
    }

    /** 原圖墨度（1−亮度）×gain 夾 [0,1]：把墨線轉亮時的 alpha，邊緣天然抗鋸齒。 */
    private fun inkAlpha(g: Gray, gain: Double): FloatArray =
        FloatArray(g.data.size) { ((1.0 - g.data[it] / 255.0) * gain).coerceIn(0.0, 1.0).toFloat() }

    /**
     * 軟性墨線遮罩：blackhat（細暗線構）乘暗度權重，再與文字筆畫取聯集。
     * 實心黑塊內部是 0，所以增亮不會把大塊黑的對比拉掉。
     */
    private fun inkLineMask(g: Gray, seg: Mask): FloatArray {
        val bh = Cv.blackhat(g, Cv.ellipse(7))
        val soft = FloatArray(g.data.size)
        // 暗度權重在 g >= 185 時是 0，那些像素的 blackhat 值再大也乘成 0——紙面佔了頁面大半，
        // 先判斷就跳過後面的除法與夾取。⚠️ blackhat 本身不能省：它抓的是細墨線（高頻），
        // 降解析度或先篩輸入都會讓線斷掉。
        for (i in soft.indices) {
            val gv = g.data[i]
            if (seg.data[i]) { soft[i] = 1f; continue }
            if (gv >= 185) continue
            val w = if (gv <= 40) 1.0 else (185.0 - gv) / 145.0
            val v = (bh.data[i] / 45.0).coerceAtMost(1.0) * w
            soft[i] = v.toFloat()
        }
        return soft
    }

    /**
     * 畫面區最終處理：場景曲線加自適應墨線增亮。增亮只在局部背景偏暗處生效，
     * 且夾在 `glowCap` 之下（低於紙白位準 ⇒ 線永遠比紙暗）。
     */
    private fun sceneFinal(g: Gray, seg: Mask, p: NightReadParams): FImg {
        val lut = lutScene(p)
        val dimmed = Gray(g.w, g.h, IntArray(g.data.size) { lut[g.data[it]] })
        val soft = inkLineMask(g, seg)
        // 這個高斯只是估「局部背景亮度」，用來判斷筆畫增亮要不要生效。sigma=8 的大模糊本來就
        // 把細節抹光了，在半解析度算再放大，數值差不到 1 階，成本卻只剩四分之一（99→28 ms）。
        val half = Cv.resizeArea(dimmed.toF(), max(1, g.w / 2), max(1, g.h / 2))
        val bgHalf = Cv.gaussianBlur(half, 4.0)
        val bg = Cv.resizeBilinear(bgHalf, g.w, g.h)
        val out = FImg(g.w, g.h)
        for (i in out.data.indices) {
            var gain = p.glowStrength * soft[i]
            gain *= ((95.0 - bg.data[i]) / 95.0).coerceIn(0.0, 1.0).toFloat()
            val base = dimmed.data[i].toFloat()
            val lifted = min(base + gain, max(base, p.glowCap.toFloat()))
            out.data[i] = lifted.coerceIn(0f, 255f)
        }
        return out
    }

    // ── 繪製 ─────────────────────────────────────────────────────────

    /** 留白填深 + 邊界描亮。 */
    private fun paintGutter(out: FImg, g: Gray, fill: Mask, p: NightReadParams) {
        for (i in fill.data.indices) if (fill.data[i]) out.data[i] = p.bg.toFloat()
        val band = Cv.dilate(fill, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(fill)
        val a = inkAlpha(g, 1.6)
        for (i in band.data.indices) {
            if (band.data[i]) out.data[i] = max(out.data[i], p.bg + a[i] * (p.edgeInk - p.bg))
        }
    }

    /** 氣泡重繪：內部填深、文字畫亮（墨度當 alpha）、輪廓描亮。 */
    private fun paintBubbles(out: FImg, g: Gray, bubble: Mask, seg: Mask, p: NightReadParams) {
        if (!bubble.any()) return
        for (i in bubble.data.indices) if (bubble.data[i]) out.data[i] = p.bg.toFloat()
        val text = Cv.dilate(seg and bubble, Cv.rect(p.textPad * 2 + 1, p.textPad * 2 + 1)) and bubble
        val a = inkAlpha(g, p.textGamma)
        for (i in a.indices) {
            // knee：把低墨度（字的抗鋸齒過渡）壓成純黑，字本體不受影響
            a[i] = (((a[i] - p.textKnee) / (1.0 - p.textKnee)).coerceIn(0.0, 1.0)).toFloat()
        }
        for (i in text.data.indices) {
            if (text.data[i]) out.data[i] = max(out.data[i], p.bg + a[i] * (p.ink - p.bg))
        }
        val band = Cv.dilate(bubble, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(bubble)
        val a2 = inkAlpha(g, 1.6)
        for (i in band.data.indices) {
            if (band.data[i]) out.data[i] = max(out.data[i], p.bg + a2[i] * (p.edgeInk - p.bg))
        }
    }

    /**
     * 偽泡：開口泡、泡尾缺口、字直接寫在畫面上時沒有封閉白元件可用，改從字底的白輕切頸
     * 生長出一圈貼身襯底。生長上限用字框長邊，用短邊會讓橫排標題字之間留白。
     */
    private fun buildPseudoBubbles(
        g: Gray, regions: List<TextRegion>, bubble: Mask, seg: Mask, p: NightReadParams,
    ): Mask {
        val w = g.w
        val h = g.h
        // 先看有沒有區需要偽泡：泡遮罩蓋率夠高的區直接跳過。全部都夠高就完全不必算 wCut，
        // 而 wCut 是三個重活（21px 開運算、測地生長、厚墨灰暈的距離變換+連通元件+25px 膨脹）。
        val needs = regions.filter { r ->
            val x0 = max(0, r.x0)
            val y0 = max(0, r.y0)
            val x1 = min(w, r.x1)
            val y1 = min(h, r.y1)
            if (x1 <= x0 || y1 <= y0) return@filter false
            var cov = 0
            for (y in y0 until y1) {
                val base = y * w
                for (x in x0 until x1) if (bubble.data[base + x]) cov++
            }
            cov.toDouble() / ((x1 - x0) * (y1 - y0)) < p.pbCovMax
        }
        if (needs.isEmpty()) return Mask(w, h)

        val white = g.ge(p.whiteTh)
        var wCut = Cv.open(white, Cv.ellipse(2 * p.pbNeckR + 1))
        wCut = Cv.geodesicGrow(wCut, white, p.pbNeckR, step = 3)
        wCut = wCut.andNot(Regions.thickInkAura(g, seg, p))
        val pb = Mask(w, h)
        for (r in needs) {
            val x0 = max(0, r.x0)
            val y0 = max(0, r.y0)
            val x1 = min(w, r.x1)
            val y1 = min(h, r.y1)
            val ref = max(x1 - x0, y1 - y0)
            val cap = (p.pbGrowFrac * ref).toInt()
            val pad = cap + p.pbNeckR + 2
            val wx0 = max(0, x0 - pad)
            val wy0 = max(0, y0 - pad)
            val wx1 = min(w, x1 + pad)
            val wy1 = min(h, y1 + pad)
            val sw = wx1 - wx0
            val sh = wy1 - wy0
            val within = Mask(sw, sh)
            val seed = Mask(sw, sh)
            for (y in 0 until sh) {
                val src = (wy0 + y) * w + wx0
                for (x in 0 until sw) {
                    val inWin = within.data
                    inWin[y * sw + x] = wCut.data[src + x]
                }
            }
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val idx = (y - wy0) * sw + (x - wx0)
                    if (within.data[idx]) seed.data[idx] = true
                }
            }
            val grown = Cv.geodesicGrow(seed, within, cap, step = 5)
            for (y in 0 until sh) {
                val dst = (wy0 + y) * w + wx0
                for (x in 0 until sw) if (grown.data[y * sw + x]) pb.data[dst + x] = true
            }
        }
        return pb.andNot(bubble)
    }

    /**
     * 浮在黑色區域裡的空白人頭一致化：暗區地圖內的殘餘亮島填深、內緣描亮。
     *
     * 亮島不是白元件——群眾的人頭白常與背景白同元件，元件級的跳過會把整顆略過。
     * 防護是面積上限（主角的臉更大）加外環細墨密度（鬍鬚與密髮排除）。
     */
    private fun harmonize(out: FImg, g: Gray, skip: Mask, p: NightReadParams) {
        val w = g.w
        val h = g.h
        val dark = FImg(w, h, FloatArray(w * h) { if (out.data[it] < 60f) 1f else 0f })
        val cw = max(1, w / p.harmonizeZoneCell)
        val ch = max(1, h / p.harmonizeZoneCell)
        var coarse = Cv.resizeArea(dark, cw, ch)
        coarse = Cv.gaussianBlur(coarse, 2.0)
        val coarseMask = Mask(cw, ch, BooleanArray(cw * ch) { coarse.data[it] >= p.harmonizeZoneDark })
        if (!coarseMask.any()) return
        val zone = Cv.resizeNearest(coarseMask, w, h)

        val resid = Mask(w, h)
        for (i in resid.data.indices) {
            resid.data[i] = g.data[i] >= p.whiteTh && out.data[i] >= 110f && !skip.data[i]
        }
        if (!resid.any()) return
        val cc = Cv.ccStats(resid, 8)
        val kc = Cv.ellipse(13)
        for (i in 1 until cc.n) {
            val a = cc.area[i]
            if (a.toDouble() / g.data.size > p.harmonizeAreaMax || a < 150) continue
            val x0 = max(0, cc.left[i] - 8)
            val y0 = max(0, cc.top[i] - 8)
            val x1 = min(w, cc.left[i] + cc.width[i] + 8)
            val y1 = min(h, cc.top[i] + cc.height[i] + 8)
            val sw = x1 - x0
            val sh = y1 - y0
            val isl = Mask(sw, sh)
            var inZone = 0
            var islN = 0
            for (y in 0 until sh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    val on = cc.labels[src + x] == i
                    isl.data[y * sw + x] = on
                    if (on) { islN++; if (zone.data[src + x]) inZone++ }
                }
            }
            if (islN == 0 || inZone.toDouble() / islN < p.harmonizeInZone) continue
            val collar = Cv.dilate(isl, kc).andNot(isl)
            var collarN = 0
            var collarInk = 0
            for (y in 0 until sh) {
                val src = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    if (!collar.data[y * sw + x]) continue
                    collarN++
                    if (g.data[src + x] < 200) collarInk++
                }
            }
            if (collarN > 0 && collarInk.toDouble() / collarN > p.harmonizeCollarInk) continue
            val edge = isl.andNot(Cv.erode(isl, Cv.rect(5, 5)))
            for (y in 0 until sh) {
                val dst = (y0 + y) * w + x0
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    if (isl.data[idx]) out.data[dst + x] = p.bg.toFloat()
                    if (edge.data[idx]) out.data[dst + x] = max(out.data[dst + x], p.strokeObjV.toFloat())
                }
            }
        }
    }

    /**
     * 貼紙式背景：白背景填深、前景加白描邊。
     *
     * 擢升的元件走核心填色（從格框種子出發、不擠過窄頸），再經兩道幾何保護（測地比刪填、
     * 厚墨灰暈）。**語意放行**：核心區裡明確不是人物的部分一律放行去填黑——那兩道幾何保護
     * 是人物遮罩出現前的粗略替代品，會把背景楔形誤判成人物附屬白。
     */
    private fun paintSticker(
        out: FImg, g: Gray, cc: CC, accept: Set<Int>, bubble: Mask, coreIds: Set<Int>,
        frame: Mask, seg: Mask, charMask: Mask, p: NightReadParams, ring: Ring.Collect?,
    ) {
        val r = (p.strokeObjFrac * min(g.h, g.w)).roundToInt().coerceIn(p.strokeObjMin, p.strokeObjMax)
        val k = Cv.ellipse(2 * r + 1)
        val kc = Cv.ellipse(p.figNoiseClose)
        // 「更多」新規則的兩項繪製（只在開了的檔）：P1 描亮邊不蓋已經黑的像素、P2 缺格框種子的核心塊拿頁緣當種子
        val keepDark = p.more.enabled && p.more.keepDarkStroke
        val edgeFb = p.more.enabled && p.more.edgeSeedFallback
        for (i in accept.sorted()) {
            val win = Sticker.window(g, cc, i, r + 2)
            val sw = win.w
            val sh = win.h
            val fill: Mask
            var protectEarly: Mask? = null
            if (i in coreIds) {
                // 灰圈收細：核心區裡只因人物安全邊（coreReleasePad）而沒填的，扣掉保護區後記下（填色本身照舊）
                val withheld = if (ring != null) Mask(sw, sh) else null
                fill = coreFill(win, g, frame, seg, charMask, edgeFb, p, withheld)
                if (withheld != null && withheld.any()) {
                    val pr = Sticker.protect(Sticker.eaten(win.sub, win.comp, p), win.comp, p)
                    protectEarly = pr
                    for (y in 0 until sh) {
                        val dst = (win.y0 + y) * g.w + win.x0
                        for (x in 0 until sw) {
                            val idx = y * sw + x
                            if (withheld.data[idx] && !pr.data[idx]) Ring.set(ring!!.withheld, dst + x)
                        }
                    }
                }
                if (!fill.any()) continue
            } else {
                fill = win.comp
            }

            // 區域級保護先算：eaten 內部是幾張窗大小的浮點圖，算完只留一張遮罩，之後才配前景的連通元件標號
            // （兩者是各自獨立的純函式，順序不影響結果，只是不讓它們的中間量同時活著）
            val protect = protectEarly ?: Sticker.protect(Sticker.eaten(win.sub, win.comp, p), win.comp, p)
            // 前景＝窗內非白且非氣泡的內容；小噪點不描邊、直接併入背景
            val fg = stickerForeground(win, g.w, bubble, p)
            val fMain = fg.first
            val noise = fg.second and Cv.close(fill, kc)
            val band = (Cv.dilate(fMain, k) and fill).andNot(protect)

            for (y in 0 until sh) {
                val dst = (win.y0 + y) * g.w + win.x0
                for (x in 0 until sw) {
                    val idx = y * sw + x
                    // P1：此時已經 ≤ bg 的（留白／格溝／前一顆貼紙塗黑的）不描亮邊——要在本元件填色之前看
                    val wasDark = keepDark && out.data[dst + x] <= p.bg
                    if ((fill.data[idx] || noise.data[idx]) && !protect.data[idx]) {
                        out.data[dst + x] = p.bg.toFloat()
                    }
                    if (band.data[idx] && !wasDark) {
                        out.data[dst + x] = p.strokeObjV.toFloat()
                        if (ring != null) Ring.set(ring.stkBand, dst + x)      // 灰圈收細：實際畫上的前景描亮邊
                    }
                }
            }
        }
    }

    /**
     * 貼紙的前景（窗內非白且非氣泡的內容）依 8 連通面積分成兩份：first＝夠大的（≥ [NightReadParams.figNoiseArea]，描邊來源）、
     * second＝小噪點（落在填色區閉運算內的併入背景）。獨立成函式：連通元件標號（4 B/px）只活在這裡。
     */
    private fun stickerForeground(win: Sticker.Window, pageW: Int, bubble: Mask, p: NightReadParams): Pair<Mask, Mask> {
        val sw = win.w
        val sh = win.h
        val fRaw = Mask(sw, sh)
        for (y in 0 until sh) {
            val src = (win.y0 + y) * pageW + win.x0
            for (x in 0 until sw) {
                val idx = y * sw + x
                fRaw.data[idx] = win.sub.data[idx] < p.whiteTh && !bubble.data[src + x]
            }
        }
        val fcc = Cv.ccStats(fRaw, 8)
        val keep = BooleanArray(fcc.n)
        for (j in 1 until fcc.n) keep[j] = fcc.area[j] >= p.figNoiseArea
        val fMain = Mask(sw, sh)
        val noiseRaw = Mask(sw, sh)
        for (idx in fMain.data.indices) {
            val l = fcc.labels[idx]
            if (l > 0) { if (keep[l]) fMain.data[idx] = true else noiseRaw.data[idx] = true }
        }
        return Pair(fMain, noiseRaw)
    }

    /**
     * 擢升元件的核心填色：從格框種子出發、不擠過窄頸的寬闊區（[Regions.broadCoreFill]），過兩道幾何保護（[coreStrict]），
     * 再做語意放行（人物遮罩外擴 [NightReadParams.coreReleasePad] 當安全邊界，非人物的核心區照填）。回傳要填的遮罩（可能全空）。
     *
     * 獨立成函式（連同 [coreStrict]、[subMask]）是為了記憶體：這裡的中間量都是窗大小，大元件的窗接近整頁
     * （c362_013 一顆 68 萬 px 的格內白），回傳後只剩一張遮罩活著，不陪著後面的前景描邊一起疊高峰值。
     */
    private fun coreFill(
        win: Sticker.Window, g: Gray, frame: Mask, seg: Mask, charMask: Mask, edgeFb: Boolean, p: NightReadParams,
        withheld: Mask? = null,
    ): Mask {
        var fr = subMask(frame, win, g.w)
        val kd = Cv.ellipse(p.frameHugDilate * 2 + 1)
        if (edgeFb) fr = fr.orInPlace(edgeSeedFallback(win, fr, kd, g.w, g.h, p))
        val seeds = Cv.dilate(fr, kd) and win.comp
        val fill = Regions.broadCoreFill(win.comp, seeds, p.coreNeckR, p.coreRecoverR)
        if (!fill.any()) return fill
        val released = coreStrict(win, g, seg, fr, seeds, fill, p)
        // 語意放行：把人物遮罩外擴當安全邊界，非人物的核心區照填
        val guard = Cv.dilate(subMask(charMask, win, g.w), Cv.ellipse(p.coreReleasePad * 2 + 1))
        for (idx in released.data.indices) {
            // [withheld]（灰圈收細）：核心區裡只因安全邊（guard）沒填的＝fill ∧ ¬strict ∧ guard（要在放行之前看 strict）
            if (withheld != null && fill.data[idx] && !released.data[idx] && guard.data[idx]) withheld.data[idx] = true
            released.data[idx] = released.data[idx] || (fill.data[idx] && !guard.data[idx])
        }
        return released
    }

    /**
     * 核心填色的兩道幾何保護：測地比刪填（背景從格框直直就到、比值≈1，衣料與皮膚要繞過人物墨線、比值高）與厚墨灰暈。
     * Python 的測地距離是「膨脹 6 次才交集、d < 4000 才再跑一批」；BFS 版的批次節奏相同，上限 4002＝最後一批在 d=3996 時
     * 整批跑完。未到達＝-1。
     *
     * 厚墨灰暈先算：它內部的距離變換與連通元件標號都是窗大小的大陣列，算完只留一張遮罩，之後才配測地距離與直線距離
     * （各 4 B/px）——三者是各自獨立的純函式，順序不影響結果，只是不讓它們的中間量同時活著。
     */
    private fun coreStrict(
        win: Sticker.Window, g: Gray, seg: Mask, fr: Mask, seeds: Mask, fill: Mask, p: NightReadParams,
    ): Mask {
        val aura = Regions.thickInkAura(win.sub, subMask(seg, win, g.w), p)
        val geo = Cv.geodesicDistance(seeds and win.comp, win.comp, 6, 4002)
        val euc = Cv.distanceL2(fr.not())
        val strict = Mask(win.w, win.h)
        for (idx in strict.data.indices) {
            strict.data[idx] = fill.data[idx] && geo[idx] >= 0 &&
                geo[idx].toFloat() <= p.geoRatioMax * euc.data[idx] + p.geoSlack &&
                !aura.data[idx]
        }
        return strict
    }

    /** 整頁遮罩 [m] 在 [win] 窗內的複本。 */
    private fun subMask(m: Mask, win: Sticker.Window, pageW: Int): Mask {
        val sw = win.w
        val sh = win.h
        val out = Mask(sw, sh)
        for (y in 0 until sh) {
            val src = (win.y0 + y) * pageW + win.x0
            for (x in 0 until sw) out.data[y * sw + x] = m.data[src + x]
        }
        return out
    }

    /**
     * 「更多」P2（研究端 `_edge_seed_fallback`）：窗內元件以 coreNeckR 開運算後的 8 連通核心塊裡，碰不到格框種子（[fr] 以 [kd]
     * 外擴 ∩ 元件）的塊，其外擴 coreNeckR＋[MoreRuleParams.edgeReach] 內的頁緣帶（距頁緣 < [MoreRuleParams.edgeBand]）。
     * 有格框種子的塊不加。回傳窗內遮罩（可能全空）。
     */
    private fun edgeSeedFallback(win: Sticker.Window, fr: Mask, kd: Kernel, pageW: Int, pageH: Int, p: NightReadParams): Mask {
        val comp = win.comp
        val sw = comp.w
        val sh = comp.h
        val out = Mask(sw, sh)
        val core = Cv.open(comp, Cv.ellipse(2 * p.coreNeckR + 1))
        if (!core.any()) return out
        val cc = Cv.ccStats(core, 8)
        val sf = Cv.dilate(fr, kd)
        val has = BooleanArray(cc.n)
        for (i in sf.data.indices) if (sf.data[i] && comp.data[i] && core.data[i]) has[cc.labels[i]] = true
        val lack = Mask(sw, sh)
        var any = false
        for (i in lack.data.indices) {
            val l = cc.labels[i]
            if (l > 0 && !has[l]) { lack.data[i] = true; any = true }
        }
        if (!any) return out
        val reach = Cv.dilate(lack, Cv.ellipse(2 * (p.coreNeckR + p.more.edgeReach) + 1))
        val b = p.more.edgeBand
        for (y in 0 until sh) {
            val py = win.y0 + y
            val rowEdge = py < b || py >= pageH - b
            for (x in 0 until sw) {
                val px = win.x0 + x
                if ((rowEdge || px < b || px >= pageW - b) && reach.data[y * sw + x]) out.data[y * sw + x] = true
            }
        }
        return out
    }

    // ── 合成 ─────────────────────────────────────────────────────────

    /**
     * 整頁合成（一檔）：[Analysis.scene] 的複本 → 留白填深（出血格過濾）→ 格溝／頁邊 → 貼紙式背景 →（「更多」）背景物件規則
     * （[BgObjects]：貼紙層接否決、之後亮背景區塗黑）→ 氣泡重繪 → 灰圈收細 → 人物還原。
     * 檔位相依的只有 [gutterIn]（無框頁＝留白元件扣掉 keep）、[plan]、[bubbleRestIn]／[bubbleRestPreIn]、[objHolder]；其餘全讀 [a]、
     * 不改寫它（灰圈收細的頁面級證據在 [Analysis.ringEvidence]，各檔共用）。
     *
     * 封縫救回的泡（[Analysis.bubbleLocal]）從泡重繪起（泡重繪、偽泡、亮島、人物還原）才當泡，
     * 上面的結構層（格溝、線稿密度否決、出血過濾、貼紙）只看 [Analysis.bubbleStruct]。
     */
    private fun compose(
        a: Analysis, gutterIn: Mask, gutterShow: Set<Int>, plan: Sticker.Plan, bubbleRestIn: Mask?, bubbleRestPreIn: LongArray?,
        objHolder: ObjHolder, p: NightReadParams, debug: NightReadDebug?, diag: MutableMap<String, Any>?,
    ): Gray {
        val objCtx = objHolder.ctx
        var bubbleRest = bubbleRestIn
        var bubbleRestPre = bubbleRestPreIn
        val g = a.g
        val seg = a.seg
        val w = g.w
        val h = g.h
        val out = a.scene.copy()
        val sceneKeep = a.scene                 // 人物區最終一律還原成場景調（各檔共用，不改寫）
        val sep = a.sep
        // 灰圈收細（[Ring]）：合成途中記下「只因人物安全邊而沒黑」的像素，人物還原前認領
        val ring = if (p.ring.enabled) Ring.Collect(w, h) else null

        // 留白：有框頁只填「深入不超過短邊 12%」的部分；無框頁只填真頁邊帶
        val gbb = paintGutterBand(out, a, gutterIn, gutterShow, p, debug, diag, keepBand = objCtx != null && p.obj.veto)
        val gvWh = gbb.gv

        // 任意角度格溝／頁邊：同留白待遇（填 BG、邊界描亮），在貼紙層之前
        if (sep != null && sep.any()) paintGutter(out, g, sep, p)

        // 「更多」背景物件規則（[BgObjects]；[objCtx] 非 null＝這一檔開了）：V 否決、L 亮背景區
        if (plan.accept.isNotEmpty()) {
            if (objCtx != null && p.obj.veto) {
                val vz = paintStickerVeto(out, a, plan, objCtx, gbb.band, p, ring, diag)
                if (vz != null) {
                    // 否決元件：泡外圈不塗、灰圈收細的可認領像素（泡外圈讓開人物前的範圍）也拿掉
                    bubbleRest = bubbleRest?.andNot(vz)
                    bubbleRestPre = bubbleRestPre?.let { b ->
                        val c = b.copyOf()
                        for (i in vz.data.indices) if (vz.data[i]) c[i ushr 6] = c[i ushr 6] and (1L shl (i and 63)).inv()
                        c
                    }
                }
            } else {
                paintSticker(out, g, a.wc.cc, plan.accept, a.bubbleStruct, plan.promoted, a.frame, seg, a.charMask, p, ring)
            }
        }
        debug?.invoke("paintSticker", 0)
        if (objCtx != null && p.obj.lightFill) {
            BgObjects.lightFill(out, g, objCtx, a.charMask, a.charRaw, a.bubbleUntrim, a.frame, seg, a.chroma, p.obj, p, diag)
            debug?.invoke("objLightFill", 0)
        }
        objHolder.ctx = null                    // 量測到此用完：不陪著後面的泡重繪／灰圈收細（那裡是合成的峰值）
        // 灰圈收細：背景填黑（留白／格溝／貼紙／亮背景區）到此為止塗成 BG 的像素（1 bit/px，到人物還原前才攤開）
        val ringBg = if (ring == null) null else {
            val bgf = p.bg.toFloat()
            val bits = LongArray((w * h + 63) ushr 6)
            for (i in 0 until w * h) if (out.data[i] == bgf && sceneKeep.data[i] != bgf) Ring.set(bits, i)
            bits
        }
        // 封縫救回的泡從這裡以後（泡重繪、偽泡、亮島、人物還原）才當泡；上面的結構層只看 bubbleStruct
        val bubAll = if (a.bubbleLocal == null) a.bubbleStruct else a.bubbleStruct or a.bubbleLocal
        paintBubbles(out, g, bubAll, seg, p)
        debug?.invoke("paintBubbles", 0)
        // 偽泡：開口泡／字壓背景／字壓留白救回（三檔一律關：偽泡沿字往背景長，是撕裂黑塊來源之一，守護框 +2）
        val pb = if (p.pseudoBubbles) buildPseudoBubbles(g, a.regions, bubAll, seg, p) else null
        debug?.invoke("pseudoBubble", pb?.count() ?: 0)
        if (pb != null && pb.any()) paintBubbles(out, g, pb, seg, p)
        // 亮島填黑（三檔一律關：會把格內背景挖成黑塊；守護框對它零敏感）
        if (p.harmonize) harmonize(out, g, if (pb != null) bubAll or pb or gutterIn else bubAll or gutterIn, p)
        debug?.invoke("harmonize", 0)

        // 字永遠在最上層：被人物扣掉的泡區裡，字筆畫及其貼身帶維持深底亮字
        val lost = a.lost
        if (lost.any()) {
            val txt = Cv.dilate(seg and lost, Cv.rect(p.textTopPad * 2 + 1, p.textTopPad * 2 + 1)) and lost
            if (txt.any()) {
                val al = inkAlpha(g, p.textGamma)
                for (i in al.indices) al[i] = (((al[i] - p.textKnee) / (1.0 - p.textKnee)).coerceIn(0.0, 1.0)).toFloat()
                for (i in txt.data.indices) {
                    if (!txt.data[i]) continue
                    out.data[i] = p.bg.toFloat()
                    out.data[i] = max(out.data[i], p.bg + al[i] * (p.ink - p.bg))
                }
            }
        }

        if (bubbleRest != null && bubbleRest.any()) {
            for (i in bubbleRest.data.indices) if (bubbleRest.data[i]) out.data[i] = p.bg.toFloat()
            val rb = Cv.dilate(bubbleRest, Cv.rect(p.stroke * 2 + 1, p.stroke * 2 + 1)).andNot(bubbleRest)
            val a2 = inkAlpha(g, 1.6)
            for (i in rb.data.indices) {
                if (rb.data[i]) out.data[i] = max(out.data[i], p.bg + a2[i] * (p.edgeInk - p.bg))
            }
        }

        // ── 人物還原（放最後 ⇒ 任何新填色機制自動受保護）──────────────────
        var restore = if (pb != null && pb.any()) {
            var restore = restoreBase(a, bubAll, p)
            // 收邊生長出來的邊緣不得壓過偽泡：那些像素是加工長出來的，屬於畫面不屬於人物
            restore = restore.andNot(pb.andNot(a.charRaw))
            val textOnChar = pb and a.charMask and seg
            if (textOnChar.any()) {
                val kb = Cv.ellipse(p.textBackingR * 2 + 1)
                restore = restore.andNot(Cv.dilate(textOnChar, kb))
            }
            debug?.invoke("restore", restore.count())
            restore
        } else {
            restoreMaskNoPseudo(a, bubAll, p, debug)
        }
        if (ring != null && ringBg != null) {
            // 灰圈收細（使用者 2026-10-03 拍板「折衷」）：有畫出來的輪廓線把背景跟人物隔開的地方，已經塗黑的背景長到輪廓線；
            // 其餘維持現在的寬度。認領的像素填 BG、從還原遮罩拿掉（規則見 Ring）。
            val bub = if (pb != null) bubAll or pb or a.lost else bubAll or a.lost
            val bgPaint = unpackBits(ringBg, w, h)
            if (bubbleRest != null) bgPaint.orInPlace(bubbleRest)
            restore = ringClaim(a, out, sceneKeep, restore, bub, bgPaint, ring, bubbleRestPre, gvWh, p, debug, diag)
        }
        val alpha = restoreBlur(restore, p)
        for (i in out.data.indices) {
            val al = alpha.data[i]
            out.data[i] = out.data[i] * (1f - al) + sceneKeep.data[i] * al
        }
        return Gray(w, h, IntArray(w * h) { out.data[it].roundToInt().coerceIn(0, 255) })
    }

    /** [compose] 用完就放掉的整頁量測（參數或區域變數會被編譯後的框架一直抱到函式結束）。 */
    private class ObjHolder(var ctx: BgObjects.Context?)

    /**
     * 背景物件規則的整頁量測。不快取在 [Analysis]：產品只有「更多」一檔用它，快取（約 1.25 B/px）會一路活到人物還原（灰圈收細的
     * 連通元件是那時的峰值）；每個開了規則的檔各算一次。
     */
    private fun objContext(a: Analysis, p: NightReadParams, diag: MutableMap<String, Any>?): BgObjects.Context =
        BgObjects.context(a.g, a.charMask, a.charRaw, a.bubbleUntrim, a.seg, a.frame, p.obj, diag)

    /**
     * 背景物件規則 V（研究端 compose 的否決分支）：先只塗標準（[NightTier.L2] 的 keep ∩ 這一檔的 keep）、再塗全部；多塗的連通塊
     * 逐塊看超區證據（[BgObjects.vetoBlocks]），否決的塊（方核外擴描亮邊半徑＋[ObjectRuleParams.vetoBackPad]）還原成只塗標準的
     * 樣子。回傳否決元件的遮罩（沒有＝null）。
     *
     * 灰圈收細的收集：研究端在否決元件上改用只塗標準那一趟的收集；標準那一趟塗的是別的元件，而「核心沒填」與「描亮邊」都落在
     * 塗它的元件自己的像素裡，所以在否決元件上那一趟的收集恆空——這裡直接把否決元件上的收集清掉（逐位元相同、不必多收一份）。
     */
    private fun paintStickerVeto(
        out: FImg, a: Analysis, plan: Sticker.Plan, ctx: BgObjects.Context, gutterBand: LongArray?, p: NightReadParams,
        ring: Ring.Collect?, diag: MutableMap<String, Any>?,
    ): Mask? {
        val g = a.g
        val w = g.w
        val h = g.h
        val n = w * h
        val stdKeep = Sticker.selectKeep(a.plan, a.plain, NightTier.L2.apply(p)).accept.intersect(plan.accept)
        if (stdKeep.size == plan.accept.size) {
            // 沒有多收的元件：兩趟相同、沒有塊可否決（研究端照樣塗兩趟，結果相同）
            paintSticker(out, g, a.wc.cc, plan.accept, a.bubbleStruct, plan.promoted, a.frame, a.seg, a.charMask, p, ring)
            if (diag != null) diag["obj_vetomask"] = Mask(w, h)
            return null
        }
        // 只塗標準是空的（常見：大頁的貼紙全是「更多」才收）＝只塗標準的成品就是貼紙層之前的成品：不必複製整頁，
        // 「已經黑」先記、還原時由場景曲線與留白／格溝遮罩重建（[prePaintValue]）
        val st = if (stdKeep.isEmpty()) {
            val preBits = packBits(BgObjects.blackish(out, g, p.obj, p))      // 1 bit/px：貼紙層（核心填色）是合成的峰值
            paintSticker(out, g, a.wc.cc, plan.accept, a.bubbleStruct, plan.promoted, a.frame, a.seg, a.charMask, p, ring)
            val pre = unpackBits(preBits, w, h)
            TwoPasses(pre, BgObjects.blackish(out, g, p.obj, p).andNot(pre), null, null)
        } else {
            paintTwoPasses(out, a, plan, stdKeep, p, ring)
        }
        val vet = BgObjects.vetoBlocks(g, st.extra, st.stdDark, ctx, a.charMask, a.charRaw, a.bubbleUntrim, a.frame, a.regions,
            p.obj, diag)
        if (diag != null) diag["obj_vetomask"] = vet
        if (!vet.any()) return null
        val r = Math.rint(p.strokeObjFrac * min(h, w)).toInt().coerceIn(p.strokeObjMin, p.strokeObjMax) + p.obj.vetoBackPad
        val back = Cv.dilatePacked(vet, Cv.rect(2 * r + 1, 2 * r + 1))
        val di = st.diffIdx
        val dv = st.diffStd
        if (di != null && dv != null) {
            for (k in di.indices) { val i = di[k]; if (back.data[i]) out.data[i] = dv[k] }
        } else {
            for (i in 0 until n) if (back.data[i]) out.data[i] = prePaintValue(i, a, gutterBand, p)
        }
        val vids = HashSet<Int>()
        for (i in 0 until n) if (vet.data[i]) { val l = a.wc.cc.labels[i]; if (l > 0 && l !in stdKeep) vids.add(l) }
        if (vids.isEmpty()) return null
        val vz = maskOfIds(a.wc.cc, vids, w, h)
        if (ring != null) {
            for (i in 0 until n) {
                if (!vz.data[i]) continue
                val bit = (1L shl (i and 63)).inv()
                ring.withheld[i ushr 6] = ring.withheld[i ushr 6] and bit
                ring.stkBand[i ushr 6] = ring.stkBand[i ushr 6] and bit
            }
        }
        return vz
    }

    /** [paintTwoPasses] 的結果：只塗標準時「已經黑」、多塗的「已經黑」、兩趟不同的像素（位置與只塗標準時的值）。 */
    private class TwoPasses(val stdDark: Mask, val extra: Mask, val diffIdx: IntArray?, val diffStd: FloatArray?)

    /**
     * 只塗標準（複本上）與塗全部（[out] 上，收灰圈）兩趟。獨立成函式：只塗標準的整頁複本只活在這裡，回傳後只剩兩趟不同的像素
     * （稀疏），不陪著否決的整頁標號一起疊高峰值。
     *
     * 貼紙層逐像素獨立（每顆元件只寫自己的窗；某像素的結果只由它原本的值與蓋到它的元件依序決定），而兩趟只差「更多」多收的元件：
     * 兩趟只可能在那些元件的窗的聯集 U 裡不同。所以只塗標準那一趟只塗窗碰到 U 的標準元件，U 外直接當成與塗全部相同（研究端
     * 兩趟都塗全部的標準元件；結果逐位元相同）。
     */
    private fun paintTwoPasses(
        out: FImg, a: Analysis, plan: Sticker.Plan, stdKeep: Set<Int>, p: NightReadParams, ring: Ring.Collect?,
    ): TwoPasses {
        val g = a.g
        val w = g.w
        val h = g.h
        val n = w * h
        val cc = a.wc.cc
        // 窗＝paintSticker 的 Sticker.window(…, r + 2)
        val m = (p.strokeObjFrac * min(g.h, g.w)).roundToInt().coerceIn(p.strokeObjMin, p.strokeObjMax) + 2
        fun rect(id: Int) = intArrayOf(max(0, cc.left[id] - m), max(0, cc.top[id] - m),
            min(w, cc.left[id] + cc.width[id] + m), min(h, cc.top[id] + cc.height[id] + m))
        val extraRects = (plan.accept - stdKeep).map(::rect)
        val u = Mask(w, h)
        for (r in extraRects) for (y in r[1] until r[3]) java.util.Arrays.fill(u.data, y * w + r[0], y * w + r[2], true)
        val stdHit = stdKeep.filter { id ->
            val r = rect(id)
            extraRects.any { e -> r[0] < e[2] && e[0] < r[2] && r[1] < e[3] && e[1] < r[3] }
        }.toSet()
        val outStd = out.copy()
        if (stdHit.isNotEmpty()) {
            paintSticker(outStd, g, cc, stdHit, a.bubbleStruct, plan.promoted.intersect(stdHit), a.frame, a.seg, a.charMask, p, null)
        }
        paintSticker(out, g, cc, plan.accept, a.bubbleStruct, plan.promoted, a.frame, a.seg, a.charMask, p, ring)
        val bgf = p.bg.toFloat()
        val dg = p.obj.darkG
        val stdDark = Mask(w, h, BooleanArray(n) { (if (u.data[it]) outStd.data[it] else out.data[it]) == bgf || g.data[it] <= dg })
        val extra = BgObjects.blackish(out, g, p.obj, p).andNot(stdDark)
        var nd = 0
        for (i in 0 until n) if (u.data[i] && out.data[i] != outStd.data[i]) nd++
        val idx = IntArray(nd)
        val v = FloatArray(nd)
        var k = 0
        for (i in 0 until n) if (u.data[i] && out.data[i] != outStd.data[i]) { idx[k] = i; v[k] = outStd.data[i]; k++ }
        return TwoPasses(stdDark, extra, idx, v)
    }

    /**
     * 人物還原遮罩裡與檔位無關的部分：人物（收邊後）扣掉泡（真泡畫在人物之上）、格溝／頁邊（溝也贏過人物：溝是畫面的
     * 外面，人物遮罩經收邊＋平滑會越過格框線長進溝 7–15px ⇒ 溝邊灰帶、窄溝被吃過半整條不塗；框線被出血人物打斷的地方
     * 本來就不成溝，Separators 不收）、字頂層。偽泡那兩項由 [compose] 另扣（集合差，順序不影響結果）。
     */
    private fun restoreBase(a: Analysis, bubAll: Mask, p: NightReadParams): Mask {
        var restore = a.charMask.copy()
        restore = restore.andNot(bubAll)
        val sep = a.sep
        if (sep != null) restore = restore.andNot(sep)
        if (a.lost.any()) {
            val kt = Cv.ellipse(p.textTopPad * 2 + 1)
            restore = restore.andNot(Cv.dilate(a.seg and a.lost, kt) and a.lost)
        }
        return restore
    }

    /** 邊界抗鋸齒：遮罩是二值又是放大來的，用小半徑高斯軟化成 alpha 混合。 */
    private fun restoreBlur(restore: Mask, p: NightReadParams): FImg =
        Cv.gaussianBlur(FImg(restore.w, restore.h, FloatArray(restore.data.size) { if (restore.data[it]) 1f else 0f }),
            p.edgeFeather)

    /**
     * 沒有偽泡時的人物還原遮罩：與檔位無關，shared 時第一檔算完就以 1 bit/px 留給後面的檔（lazy）；回傳的是新的遮罩（呼叫端可改）。
     * 高斯每檔重做（見 [Analysis]：留浮點 alpha 會墊高後面幾檔的峰值；灰圈收細的認領也跟檔位有關）。
     */
    private fun restoreMaskNoPseudo(a: Analysis, bubAll: Mask, p: NightReadParams, debug: NightReadDebug?): Mask {
        val cached = a.restoreBits
        val restore = if (cached != null) {
            unpackBits(cached, a.g.w, a.g.h)
        } else {
            restoreBase(a, bubAll, p).also { if (a.shared) a.restoreBits = packBits(it) }
        }
        debug?.invoke("restore", restore.count())
        return restore
    }

    /**
     * 要存灰圈收細的中間遮罩（`ring_*`）與證據耗時（`t_paper`…）才回傳 [diag]：diag 要有 `"ring"`＝true（再加 `"ring_state"`＝true
     * 另存合成狀態）。
     */
    private fun ringDiag(diag: MutableMap<String, Any>?): MutableMap<String, Any>? = if (diag?.get("ring") == true) diag else null

    /**
     * 灰圈收細的認領（[Ring]）：可認領像素 D 與種子 → ∧ 頁面級證據（[Analysis.ringEvidence]）→ 生長＋收尾。認領的像素在 [out] 填 BG，
     * 回傳拿掉認領後的還原遮罩（沒有認領＝原樣）。[diag] 有 `"ring"`＝true 才存 `ring_D`／`ring_seed`／`ring_claim`（[ringDiag]）。
     */
    private fun ringClaim(
        a: Analysis, out: FImg, scene: FImg, restore: Mask, bub: Mask, bgPaint: Mask, ring: Ring.Collect,
        restPre: LongArray?, gvWh: LongArray?, p: NightReadParams, debug: NightReadDebug?, diag: MutableMap<String, Any>?,
    ): Mask {
        val w = a.g.w
        val h = a.g.h
        val parts = Ring.PageParts(
            rawClosed = {
                a.ringClosedBits?.let { unpackBits(it, w, h) }
                    ?: Ring.rawClosedOf(a.charRaw, p.ring).also { if (a.shared) a.ringClosedBits = packBits(it) }
            },
            near = {
                a.ringNearBits?.let { unpackBits(it, w, h) }
                    ?: Ring.nearOf(a.charMask, p.ring).also { if (a.shared) a.ringNearBits = packBits(it) }
            },
        )
        val cl = Ring.claimable(a.g, out, scene, restore, a.charRaw, a.charMask, bub, bgPaint, ring.withheld, restPre, gvWh,
            ring.stkBand, p, parts)
        var claim: Mask? = null
        val rd = ringDiag(diag)
        val dRaw = if (rd != null) cl.d.copy() else null
        if (cl.any) {
            // 分析時算好的證據；分析的灰圈參數跟這一檔不同（或分析時灰圈關著）就照這一檔的參數現算（render／renderTiers 不會走到）
            val ev = a.ringEvidence?.takeIf { a.p.ring == p.ring } ?: packBits(Ring.evidence(a.g, a.charRaw, p, null))
            val allowed = cl.d
            for (i in allowed.data.indices) {
                if (allowed.data[i] && (ev[i ushr 6] ushr (i and 63)) and 1L == 0L) allowed.data[i] = false
            }
            claim = Ring.grow(allowed, cl.seed, p.ring)
        }
        if (rd != null) {
            rd["ring_D"] = dRaw!!
            rd["ring_seed"] = cl.seed
            rd["ring_claim"] = claim ?: Mask(a.g.w, a.g.h)
            if (rd["ring_state"] == true) {
                // 合成狀態（研究端 thin_ring 的 bg_paint／restore／withheld／rest_pre／gv_wh／stk_band），分段 parity 用
                rd["ring_bg"] = bgPaint
                rd["ring_restore"] = restore.copy()
                rd["ring_withheld"] = unpackBits(ring.withheld, w, h)
                restPre?.let { rd["ring_restpre"] = unpackBits(it, w, h) }
                gvWh?.let { rd["ring_gv"] = unpackBits(it, w, h) }
                rd["ring_stk"] = unpackBits(ring.stkBand, w, h)
            }
        }
        var n = 0
        if (claim != null) {
            val bgf = p.bg.toFloat()
            for (i in claim.data.indices) if (claim.data[i]) { out.data[i] = bgf; restore.data[i] = false; n++ }
        }
        debug?.invoke("ringClaim", n)          // 沒有認領也送（0）：分段計時才不會把這段算進下一段
        return restore
    }

    /**
     * 塗留白帶（獨立成函式：帶遮罩只活在這裡，不陪著後面的貼紙／泡／人物還原）。回傳留白帶裡「只因人物自己的墨被線稿否決」的
     * 像素（灰圈收細用；關掉或沒有＝null）。
     */
    private fun paintGutterBand(
        out: FImg, a: Analysis, gutterIn: Mask, gutterShow: Set<Int>, p: NightReadParams,
        debug: NightReadDebug?, diag: MutableMap<String, Any>?, keepBand: Boolean = false,
    ): GutterBandBits {
        val gb = gutterBand(a, gutterIn, gutterShow, p, debug, diag)
        val band = gb.band
        if (band != null && band.any()) paintGutter(out, a.g, band, p)
        return GutterBandBits(gb.gv, if (keepBand && band != null && band.any()) packBits(band) else null)
    }

    /** [paintGutterBand] 的結果：灰圈收細用的「只因人物自己的墨被否決」、塗了的留白帶（[keepBand] 才留；背景物件規則重建貼紙前的值用）。 */
    private class GutterBandBits(val gv: LongArray?, val band: LongArray?)

    /**
     * 貼紙層之前的成品值（[compose] 的「場景曲線 → 留白帶 → 格溝／頁邊」）在像素 [i] 的值，由場景曲線與兩層遮罩重建（逐個浮點運算
     * 照 [paintGutter]：填 bg、外擴方核 2·stroke+1 的邊帶取 max(原值, bg ＋ 墨度 ×(edgeInk − bg))）。
     */
    private fun prePaintValue(i: Int, a: Analysis, band: LongArray?, p: NightReadParams): Float {
        val w = a.g.w
        val h = a.g.h
        var v = a.scene.data[i]
        val x = i % w
        val y = i / w
        val s = p.stroke
        fun edge(isFill: (Int) -> Boolean): Boolean {
            if (isFill(i)) return false
            for (yy in max(0, y - s)..min(h - 1, y + s)) for (xx in max(0, x - s)..min(w - 1, x + s)) if (isFill(yy * w + xx)) return true
            return false
        }
        val ink = ((1.0 - a.g.data[i] / 255.0) * 1.6).coerceIn(0.0, 1.0).toFloat()
        if (band != null) {
            val f = { j: Int -> (band[j ushr 6] ushr (j and 63)) and 1L != 0L }
            if (f(i)) v = p.bg.toFloat()
            if (edge(f)) v = max(v, p.bg + ink * (p.edgeInk - p.bg))
        }
        val sep = a.sep
        if (sep != null && sep.any()) {
            val f = { j: Int -> sep.data[j] }
            if (f(i)) v = p.bg.toFloat()
            if (edge(f)) v = max(v, p.bg + ink * (p.edgeInk - p.bg))
        }
        return v
    }

    /** [gutterBand] 的結果：要塗的留白帶（null＝不塗），與只因人物自己的墨被線稿否決的像素（null＝沒算或沒有）。 */
    private class GutterBand(val band: Mask?, val gv: LongArray?)

    /**
     * 要塗的留白帶（[gutterIn] 空＝null）：有框頁沿格框線切開、深度 ≤ 短邊 12%；無框頁只留真頁邊帶；兩條都過線稿密度否決
     * 與出血格過濾。只由 [gutterShow] 決定（其餘全是分析結果），shared 時依它快取一份（有框頁三檔同一份）。
     */
    private fun gutterBand(
        a: Analysis, gutterIn: Mask, gutterShow: Set<Int>, p: NightReadParams,
        debug: NightReadDebug?, diag: MutableMap<String, Any>?,
    ): GutterBand {
        if (a.shared && a.gutterKey == gutterShow) {
            val gv = a.gutterGvBits
            val bits = a.gutterBandBits ?: return GutterBand(null, gv)
            val cached = unpackBits(bits, a.g.w, a.g.h)
            if (!a.frameless) debug?.invoke("gutterBand", cached.count())
            return GutterBand(cached, gv)
        }
        val gb = computeGutterBand(a, gutterIn, p, debug, diag)
        if (a.shared) {
            a.gutterKey = gutterShow
            a.gutterBandBits = gb.band?.let { packBits(it) }
            a.gutterGvBits = gb.gv
        }
        return gb
    }

    /**
     * 「不把人物遮罩裡的墨算線稿」重算一次的線稿密度否決（灰圈收細關＝null），1 bit/px。要在一般的否決**之前**算：兩次否決的
     * 暫存不重疊，一般否決（合成的記憶體峰值）旁邊只多這 1 bit/px。
     */
    private fun vetoExcludingCharacters(a: Analysis, fill: Mask, p: NightReadParams): LongArray? =
        if (!p.ring.enabled) null else packBits(Texture.veto(fill, a.g, a.frame, a.seg, a.bubbleStruct, p, exclude = a.charMask))

    /**
     * 線稿密度否決 [v1] 拿掉、但「不把人物遮罩裡的墨算線稿」重算（[v2]）就不會被拿掉的留白像素（研究端 `gv_wh`；沒有＝null）。
     */
    private fun gutterGv(fill: Mask, v1: Mask, v2: LongArray?): LongArray? {
        if (v2 == null || v1 === fill) return null
        val gv = LongArray(v2.size)
        var any = false
        for (i in fill.data.indices) {
            if (fill.data[i] && !v1.data[i] && Ring.has(v2, i)) { Ring.set(gv, i); any = true }
        }
        return if (any) gv else null
    }

    private fun computeGutterBand(
        a: Analysis, gutterIn: Mask, p: NightReadParams, debug: NightReadDebug?, diag: MutableMap<String, Any>?,
    ): GutterBand {
        if (!gutterIn.any()) return GutterBand(null, null)
        val g = a.g
        val w = g.w
        val h = g.h

        // 出血格過濾：只拿掉留白帶裡的畫面塊、不新增（SEP 之後照塗，拿不掉溝與頁邊）
        fun bleed(band: Mask, band0: Mask): Mask {
            if (!p.bleedFilter) return band
            val t0 = System.nanoTime()
            val res = Bleed.filter(band, band0, g, a.frame, a.seg, a.bubbleStruct, a.charMask, a.regions, gutterIn, a.layer, p)
            if (diag != null) {
                diag["t_bleed"] = (diag["t_bleed"] as Double) + (System.nanoTime() - t0) / 1e6
                diag["band0"] = band0
                diag["band_post"] = band
                diag["band_final"] = res.band
                diag["pieces"] = res.pieces
            }
            return res.band
        }

        val bd = borderDistance(g, a.frame, includeFrame = !a.frameless)
        if (a.frameless) {
            val cc = Cv.ccStats(gutterIn, 8)
            val lim = p.framelessMarginDepth * min(h, w)
            val maxDepth = FloatArray(cc.n)
            for (i in gutterIn.data.indices) {
                val l = cc.labels[i]
                if (l > 0 && bd[i] > maxDepth[l]) maxDepth[l] = bd[i]
            }
            val keep = Mask(w, h)
            for (i in keep.data.indices) {
                val l = cc.labels[i]
                if (l > 0 && maxDepth[l] <= lim) keep.data[i] = true
            }
            // 有線稿的白不是留白；出血格畫面拿掉（灰圈收細的 gv 在出血過濾之前取，同研究端）
            val v2 = vetoExcludingCharacters(a, keep, p)
            val v1 = Texture.veto(keep, g, a.frame, a.seg, a.bubbleStruct, p)
            val gv = gutterGv(keep, v1, v2)
            return GutterBand(bleed(v1, keep), gv)
        }
        val lim = p.safeGutterDepth * min(h, w)
        // 格內背景與頁邊留白在像素層連通 ⇒ 先沿格框線切開，只留真的留白
        val cut = Regions.gutterFrameCut(gutterIn, a.lh, a.lv, p)
        val band = Mask(w, h)
        for (i in band.data.indices) band.data[i] = cut.data[i] && bd[i] <= lim
        // 有線稿的白不是留白；出血格畫面拿掉（灰圈收細的 gv 在出血過濾之前取，同研究端）
        val v2 = vetoExcludingCharacters(a, band, p)
        val v1 = Texture.veto(band, g, a.frame, a.seg, a.bubbleStruct, p)
        val gv = gutterGv(band, v1, v2)
        val band2 = bleed(v1, band)
        debug?.invoke("gutterBand", band2.count())
        return GutterBand(band2, gv)
    }

    /** 每像素到頁邊（有框頁再併入格線）的距離，決定留白填到多深。 */
    private fun borderDistance(g: Gray, frame: Mask, includeFrame: Boolean): FloatArray {
        val w = g.w
        val h = g.h
        val bd = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                bd[base + x] = min(min(x, w - 1 - x), min(y, h - 1 - y)).toFloat()
            }
        }
        if (includeFrame && frame.any()) {
            // ⚠️ 這裡的距離變換試過半解析度：只省 10 ms 但 MAE 0.57→0.59。距離門檻雖然有餘裕，
            // 但 `bd` 同時決定留白填色的邊界，那是逐像素的視覺邊界，±1 px 會沿著整條格溝顯現。
            val fd = Cv.distanceL2(frame.not())
            for (i in bd.indices) if (fd.data[i] < bd[i]) bd[i] = fd.data[i]
        }
        return bd
    }
}
