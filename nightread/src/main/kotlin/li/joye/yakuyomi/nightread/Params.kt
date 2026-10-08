package li.joye.yakuyomi.nightread

/**
 * 夜讀重繪的參數面板——逐項對應 `research/nightread.py` 檔頭的參數區。
 *
 * 值的由來與被否決的替代方案見 `docs/PARAMETERS.md` 與 `docs/DECISIONS.md`。
 * 做成 data class 而不是 const 是為了讓上機端能整組換掉（例如之後想做「更保守」的預設），
 * 但**預設值就是研究端的定案值**，改它等於改演算法。
 */
data class NightReadParams(
    // 輸出位準
    val bg: Int = 16,
    val ink: Int = 240,
    /**
     * 描亮邊線（留白邊界、泡框、泡外那圈）的亮度上限；預設＝[ink]（與研究端輸出逐位元同）。
     * 產品端可獨立調低：真機回報「純白到發亮的線條」就是這些 1px 描亮帶——場景墨線最多 glowCap=112，
     * 這裡卻衝到 240，同一頁兩種亮度差太多（2026-09-26）。字的亮度另由 [ink] 管。
     */
    val edgeInk: Int = 240,
    val stroke: Int = 1,
    val strokeObjV: Int = 220,
    val sceneFloor: Int = 8,
    val dimCeil: Int = 140,
    val glowStrength: Float = 55f,
    val glowCap: Int = 112,

    // 輸入與白元件
    val whiteTh: Int = 235,
    val inkDarkTh: Int = 128,
    val bubblePad: Int = 40,
    val gutterMinAreaFrac: Double = 0.0006,

    // 紙白正規化
    val paperPeakLo: Int = 200,
    val paperNormMin: Int = 245,
    val paperNormChromaMax: Double = 8.0,

    // 頁型判別
    val frameLineLDiv: Int = 5,
    val frameDarkTh: Int = 100,
    val frameMinEach: Double = 1.0,
    val frameMinSum: Double = 4.5,
    val framelessMarginDepth: Double = 0.12,

    // 留白與格內白
    val coreR: Int = 26,
    val deepEdgeFrac: Double = 0.07,
    val inPanelCoreFrac: Double = 0.15,
    val inPanelCoreDeep: Double = 0.25,
    val deepInkDeep: Double = 0.5,
    val deepInkRatio: Double = 0.02,
    val holeMaxFrac: Double = 0.01,
    val safeGutterDepth: Double = 0.12,
    val gfcDilate: Int = 4,
    val gfcCloseFrac: Double = 0.30,

    // 線稿密度否決（Texture）：有線稿的白不是留白
    val textureTh: Double = 0.10,
    val textureHi: Double = 0.15,
    val textureWin: Int = 31,
    val texturePad: Int = 4,
    val textureMinArea: Int = 1500,
    val textureFrameDil: Int = 9,
    val textureSegDil: Int = 21,
    val textureBubbleNear: Int = 25,
    val textureBubbleOutline: Int = 8,
    val textureClose: Int = 15,
    val textureRegionMin: Double = 0.002,

    // 氣泡
    val bubbleCompMaxFrac: Double = 0.07,
    val bubbleLocalK: Double = 4.0,
    val bubbleCoreMinFrac: Double = 0.003,
    val bubbleNeckR: Int = 8,
    /**
     * 泡核心面積 ≤ 此 × 字框長邊²（擋「字壓臉」被當成泡）。2.5 → 6.0（2026-09-26）：譯後頁的中文比日文短很多
     * （おはようございます → 早安），字框長邊² 縮 10–20 倍、真泡被拒收成「中間黑、內側一圈白」；6.0 在 fixture 守護框
     * 18/665 不變、真機兩章多救回 13 顆泡（見 docs/DECISIONS.md）。
     */
    val safeBubbleRatio: Double = 6.0,
    val bubbleCleanWins: Double = 0.005,
    val bubbleCleanTextMax: Double = 0.8,
    /**
     * 泡內淺條修法 d（2026-10-02）：乾淨泡判準 [bubbleCleanWins] 的「洞」只算非紙白（原圖 < [whiteTh]）。字欄之間沒被核心
     * 填色收進泡的紙白小縫不再算「泡裡有別的東西」——47 頁判不乾淨的 39 顆真泡有 33 顆洞只有紙白，0.5% 門檻對它們像擲硬幣
     * （縮放／JPEG／手機 NCNN 輸入差一點就翻面）。研究端 `NIGHTREAD_CLEAN_INK_HOLES`；false＝舊行為。見 docs/DECISIONS.md。
     */
    val bubbleCleanInkHoles: Boolean = true,
    /**
     * 泡內淺條修法 c（2026-10-02）：仍判不乾淨、但「字確認」的泡（字佔比 ≤ [bubbleCleanTextMax]，且至少一個字框的完整
     * bbox 面積有 ≥ [bubbleConfirmTextIn] 落在填洞後的泡內），人物修剪只讓開人物模型**原輸出**——收邊／平滑沿泡內紙白
     * 長進去的那條安全邊被泡的黑蓋過。研究端 `NIGHTREAD_GUARD_RAW`；false＝舊行為（修剪看收邊＋平滑後的遮罩）。
     */
    val bubbleGuardRaw: Boolean = true,
    /** 字確認：字框完整 bbox 面積落在填洞後泡內的比例下限（見 [bubbleGuardRaw]）。 */
    val bubbleConfirmTextIn: Double = 0.5,
    /**
     * 漏泡判準（2026-10-02）：乾淨泡（整顆塗黑、不讓開人物）裡壓在人物模型**原輸出**上的連通塊，如果它貼著的泡外緣沒有框線，
     * 就還給人物。沒有框線的泡，泡的白會直接連到人物身上的白（demo04 白髮高光、白襯衫），整顆塗就塗到人物。逐塊判：塊外擴
     * [bubbleLeakRing] px、扣掉泡與乾淨泡的洞的那一圈上，「有線」的像素（[bubbleLeakWin]² 窗內最亮減最暗 ≥ [bubbleLeakRange]）
     * 佔比 < [bubbleLeakEdgeMax] 就算沒有框線。看局部亮度差而不是墨（< [inkDarkTh]）佔比：糊掉的框線亮度會高過墨門檻，
     * 落差還在。研究端 `NIGHTREAD_ELEAK`；false＝只有修法 e。與檔位無關。見 docs/DECISIONS.md。
     */
    val bubbleLeak: Boolean = true,
    /** 漏泡判準：外緣環寬（px，橢圓核 2r+1）。 */
    val bubbleLeakRing: Int = 3,
    /** 漏泡判準：環上一個像素算「有線」的局部亮度差下限（[bubbleLeakWin]² 窗內最亮減最暗，灰階）。 */
    val bubbleLeakRange: Int = 60,
    /** 漏泡判準：量局部亮度差的方窗邊長（px，奇數）。 */
    val bubbleLeakWin: Int = 9,
    /**
     * 漏泡判準：環上「有線」像素佔比 < 此＝這段泡緣沒有框線。19 種輸入（縮放 0.5–1.2、JPEG q50–q85、模糊 σ 1.0–2.0、
     * 黑位抬高、縮小再放回）1,458 塊：demo04 該還給人物的塊最高 75.7%，其他頁最低 92.3%。
     */
    val bubbleLeakEdgeMax: Double = 0.85,
    /** 漏泡判準：環至少這麼多 px 才判（更少＝這塊幾乎被泡包住、沒碰到泡外緣）。 */
    val bubbleLeakRingMin: Int = 100,
    /** 漏泡判準：乾淨泡 ∩ 人物原輸出的連通塊至少這麼多 px 才看。 */
    val bubbleLeakMinArea: Int = 100,
    /** 泡元件的剩餘部分只在泡外此距離內填深（三檔皆同）。 */
    val bubbleRestNear: Int = 20,
    /**
     * 字壓背景閘（2026-09-27）：泡核心的「非字邊界」（core 的 1 px 內邊界、扣掉外擴 7 的筆畫）中，距墨線（< [inkDarkTh]）
     * ≤ [bubbleOutlineDist] px 的比例下限。真泡由自己的框線圍住 ⇒ 邊界幾乎全貼墨（19 頁 105 顆真泡：d=6 實測 ≥ 0.977，
     * d=4 時 ≥ 0.959）；字直接寫在天空／牆面上的白，邊界是網點灰／雲線／別人的線稿 ⇒ 比例低（d=6：c362_010「我想想」的
     * 天空白塊 0.076、c371_008 建築 0.257、c362_013 天空 0.314、ch34_015 手寫字壓格內背景 0.614、demo01 臉 0.748）。
     * 0.85 落在兩群中間（最窄處 demo01 距門檻 0.10），
     * 守護框標準 18/665、三檔 L1/L2/L3 12/12/16 不變。只在核心填色分支判；外圈 1–3 px 平均彩度 > [stickerChromaMax] 不判
     * （彩頁泡框／底可能是淡彩，墨判準不成立）；非字邊界少於 [bubbleOutlineMinPx] px 不判。0＝關。見 docs/DECISIONS.md。
     */
    val bubbleOutlineMin: Double = 0.85,
    /**
     * 「貼墨」距離（px），絕對像素。4 → 6（2026-09-27 審查的解析度探針）：d=4 在頁圖放大 1.5–2× 就破（2× 時 74–79 顆真泡
     * 跌破 0.85）；d=6 從 1× 到 2× 真泡全程 ≥ 0.94，字壓背景 1× 最高 0.748、放大後 ≤ 0.68，兩群仍分得開；1× 的 fixture 11 頁
     * 與真機 8 頁輸出逐像素不變。
     */
    val bubbleOutlineDist: Int = 6,
    /** 非字邊界像素少於此不判（樣本不足 ⇒ 維持原行為）。 */
    val bubbleOutlineMinPx: Int = 100,
    /** 閘門量測窗外擴（距離變換要看得到元件 bbox 外的墨）。 */
    val bubbleOutlinePad: Int = 8,
    /**
     * 漏泡封縫（v3，2026-09-30；研究端 `NIGHTREAD_BUBBLE_SEAL_R` 等）的參數組：字碰到的白元件因「太大」或「列為留白／格內白」
     * 被拒時，只封 ≤ 2R px 的極窄縫，看字所在的白會不會自成一塊；會的話那一塊當成新元件走原本的泡路徑（另加幾道閘）。
     * 獨立成 data class，與 [sep]／[bleed] 一致、給建構子留餘裕（2026-09-30 寫這段時最大的建構子 189／255 槽，平鋪這 6 個也放得下；之後又加了泡內淺條的 4 槽）。
     * 見 [BubbleSealParams] 與 docs/DECISIONS.md。
     */
    val bubbleSeal: BubbleSealParams = BubbleSealParams(),

    // 偽泡
    val pbCovMax: Double = 0.85,
    val pbNeckR: Int = 10,
    val pbGrowFrac: Double = 0.6,
    val pbAuraR: Int = 12,
    val pbAuraThick: Int = 6,
    val pbAuraMinArea: Int = 800,

    // 貼紙式背景
    val strokeObjFrac: Double = 0.0035,
    val strokeObjMin: Int = 4,
    val strokeObjMax: Int = 7,
    val stickerMinFrac: Double = 0.01,
    val figNoiseArea: Int = 40,
    val figNoiseClose: Int = 11,
    val stickerFigMin: Double = 0.1,
    val stickerFigMax: Double = 0.85,
    val stickerThinR: Int = 4,
    val stickerThinMax: Double = 0.45,
    val stickerChromaMax: Double = 6.0,
    val stickerEatenR: Int = 5,
    val stickerEatenDens: Double = 0.35,
    val stickerEatenMax: Double = 0.06,
    val stickerEatenHard: Double = 0.3,
    val stickerTextBgMin: Double = 0.1,
    val stickerTextMax: Double = 0.55,
    val stickerTextOnPad: Int = 8,
    val stickerSmallArea: Double = 0.02,
    val stickerNeckR: Int = 8,
    val stickerCoreMin: Double = 0.03,
    val stickerProtectEatenMin: Double = 0.002,
    val stickerProtectDilate: Int = 6,
    val faintOfFMax: Double = 0.62,
    val faintG: Int = 160,

    // 貼框擢升
    val frameHugDilate: Int = 5,
    val frameHugThick: Double = 3.0,
    val frameHugMin: Double = 0.25,
    val frameHugStrong: Double = 0.4,
    val hugSideMin: Double = 0.5,
    val promotedTextOnMax: Double = 0.3,

    // 背景填黑三檔（產品檔位；2026-09-27 使用者定義「多少白該黑」，取代被打回的「保護畫面」旗標）
    /**
     * 通過貼紙安全網的白元件裡，哪些真的填黑（含貼框擢升的核心填色）：
     * - [StickerMode.ALL]：全填＝研究端完整管線（**預設**；fixture 與守護框基線不動，但不再是產品檔位）
     * - [StickerMode.PLAIN]：只留「無畫面背景」——不碰原始人物遮罩（[NightReadInput.charMask]，未收邊未平滑）、且外圈
     *   （橢圓 (2·[stickerPlainRingR]+1)² 膨脹減元件）非空、外圈上「非格線的墨」佔比 < [stickerPlainArtMax]、「淡線稿」
     *   佔比 < [stickerPlainFaintMax] ⇒ 邊界只碰格線／頁邊、沒碰線稿（L1）。⚠️ 實測（47 頁、2026-09-27）：過安全網的
     *   139 顆元件裡，不碰人物且暗墨 < 0.05 的只有 2 顆（c362_009 有雲的天空、c371_010 天花板淡線的小三角），兩顆淡線稿
     *   都 ≥ 0.167 ⇒ 加了淡線稿判準後 plain 零命中，L1 實際上＝留白＋泡、不填任何貼紙；「無畫面背景」要更寬的判準
     *   （外圈扣掉泡框／字）另案研究。
     * - [StickerMode.SIMPLE]：PLAIN 的那些 ∪ {rough ≤ [stickerRoughMax] 且整頁佔比 ≥ [stickerSimpleMinFrac]}（L2／L3）
     *
     * 落選的擢升元件連核心填色也不做；落選元件回到沒有貼紙層時的待遇（有框頁場景調壓暗、無框頁背景保留），
     * 絕不會比原圖糟。產品三檔一律由 [NightTier.apply] 產生（單一來源）：L1＝PLAIN＋[pseudoBubbles]=false＋[harmonize]=false；
     * L2＝SIMPLE、10／0.005、兩者關；L3＝SIMPLE、20／0.0、兩者關。三檔一律：泡外圈、留白深度、閘門都用標準值。
     *
     * 消融結論：撕裂真凶＝碰到人物、內有線稿的大白元件——它過了貼紙安全網、核心填色卻停在人物邊界 ⇒ 沿人物一圈黑。
     * rough＝周長²/(4π·面積) 分得開：正當大白 1.8–8.3 vs 撕裂元件 17.8–173。守護框 L1 12／L2 12／L3 16 vs 標準 18（/665）；
     * 8 張撕裂頁亮區（≥110 像素佔比）L1 40.7%／L2 40.2%／L3 40.0% vs 標準 35.7%（含字壓背景閘 d=6）。見 docs/DECISIONS.md。
     */
    val stickerMode: StickerMode = StickerMode.ALL,
    /**
     * SIMPLE：rough（周長²/(4π·面積)，周長＝cv2 `findContours(RETR_LIST, CHAIN_APPROX_NONE)` 所有輪廓的閉合折線長）上限。
     * 與研究端同樣拿**四捨五入到小數 1 位**後的值比（python 比的是審計表裡 `round(rough, 1)`）。L2 10、L3 20。
     */
    val stickerRoughMax: Double = 10.0,
    /** SIMPLE：整頁佔比下限（同樣拿四捨五入到小數 4 位後的值比）。L2 0.005、L3 0。 */
    val stickerSimpleMinFrac: Double = 0.005,
    /** PLAIN：元件外圈＝dilate(橢圓 (2r+1)²＝15×15) − 元件，量外圈碰到什麼。 */
    val stickerPlainRingR: Int = 7,
    /** PLAIN：外圈上「非格線的墨」（g < [inkDarkTh] 且不在格線外擴內）佔比 < 此＝只碰格線／頁邊。 */
    val stickerPlainArtMax: Double = 0.05,
    /** PLAIN：格線遮罩外擴的方核邊長（7×7），框線本身不算線稿。 */
    val stickerPlainFrameDil: Int = 7,
    /**
     * PLAIN：外圈上「淡線稿」（[inkDarkTh] ≤ g < [whiteTh]、不在格線外擴內、也不在暗墨暈 [stickerPlainFaintHalo] 內）
     * 佔比 < 此，才算只碰格線／頁邊。雲、效果線、淡網點都比 [inkDarkTh] 亮，只數暗墨會漏掉（c362_009 彩旗下的天空：
     * 暗墨 0.041 過了 0.05 門、淡線稿 0.638 ⇒ 整片有雲的天空被當無畫面背景塗黑）。47 頁能走到這道判準的只有 2 顆
     * （0.167、0.638），門檻取 0.10 兩側都留空間。
     */
    val stickerPlainFaintMax: Double = 0.10,
    /** PLAIN：暗墨（g < [inkDarkTh]）抗鋸齒暈的方核外擴邊長（5×5）；暈裡的淡像素屬於暗線、不算淡線稿。 */
    val stickerPlainFaintHalo: Int = 5,
    /**
     * 「更多」新規則 A2（2026-10-02 使用者拍板）的參數組；[MoreRuleParams.enabled] 預設關（＝加入前逐像素相同），產品「更多」
     * ＝[NightTier.L3] 會打開。獨立成 data class（同 [sep]／[bleed]），不佔建構子的 JVM 參數槽。見 [MoreRuleParams]。
     */
    val more: MoreRuleParams = MoreRuleParams(),
    /** 偽泡開關（三檔＝false；偽泡沿字往背景長，是撕裂黑塊來源之一，守護框 +2）。 */
    val pseudoBubbles: Boolean = true,
    /** 亮島填黑（人頭一致化）開關（三檔＝false；會把格內背景挖成黑塊；守護框對它零敏感）。 */
    val harmonize: Boolean = true,

    // 核心填色
    val coreNeckR: Int = 12,
    val coreRecoverR: Int = 9,
    val geoRatioMax: Double = 1.6,
    val geoSlack: Double = 40.0,
    val coreReleasePad: Int = 16,

    // 人物語意遮罩
    val charSnap: Int = 10,
    val charSnapPad: Int = 1,
    val maskSmoothMedian: Int = 15,
    val edgeFeather: Double = 0.7,

    // 文字
    val textPad: Int = 2,
    val textGamma: Double = 1.4,
    val textKnee: Double = 0.35,
    val textTopPad: Int = 3,
    val textBackingR: Int = 5,

    // 人頭一致化
    val harmonizeZoneCell: Int = 16,
    val harmonizeZoneDark: Double = 0.45,
    val harmonizeInZone: Double = 0.6,
    val harmonizeAreaMax: Double = 0.004,
    val harmonizeCollarInk: Double = 0.3,

    /**
     * 任意角度格溝／頁邊開關（[Separators]；研究端 `NIGHTREAD_SEP`）。開＝合成一開始算 SEP 圖層：留白填深之後、貼紙層之前
     * 用留白待遇塗（填 BG、邊界描亮），人物還原跳過它（**格溝壓過人物**：溝是畫面的外面，人物遮罩收邊後越過框線長進溝
     * 7–15px 才是錯的）；同時給出血過濾當溝／頁邊／任意角度框線的結構證據。關＝[bleedFilter] 的這些證據一律空。
     * 兩個開關都關＝加入前（研究端 d3cfa92）的輸出。三檔一律開。
     */
    val separators: Boolean = true,
    /**
     * 任意角度格溝／頁邊（[Separators] ＝ research/nightread_sep.py）的參數組。獨立成一個 data class：
     * 平鋪進來會讓建構子超過 JVM 的 255 個參數槽上限（Double 佔兩槽），類別載入就 ClassFormatError。
     */
    val sep: SeparatorParams = SeparatorParams(),
    /**
     * 出血格過濾開關（[Bleed]；研究端 `NIGHTREAD_BLEED`）。開＝兩條留白路徑在線稿密度否決之後，把「其實是出血格畫面」
     * 的塊拿掉（只拿掉、不新增）。三檔一律開。
     */
    val bleedFilter: Boolean = true,
    /** 出血格過濾（[Bleed] ＝ research/nightread_bleed.py）的參數組；同 [sep] 獨立成 data class。 */
    val bleed: BleedParams = BleedParams(),
    /**
     * 人物外灰圈收細（[Ring] ＝ research/nightread_ring.py；2026-10-03 使用者拍板「折衷」）的參數組；同 [sep] 獨立成 data class。
     * 各檔一律開（與檔位無關）。
     */
    val ring: RingParams = RingParams(),
    /**
     * 「更多」背景物件規則（2026-10-03 使用者拍板，規則版本 3；研究端 `research/nightread_obj.py`）的參數組；同 [sep] 獨立成
     * data class。只在開了「更多」新規則（[MoreRuleParams.enabled]＝[NightTier.L3]）的檔生效，其餘檔不受影響。見 [ObjectRuleParams]。
     */
    val obj: ObjectRuleParams = ObjectRuleParams(),
)

/**
 * 「更多」背景物件規則（研究端 `research/nightread_obj.py` 的模組常數；預設值＝研究端定案值，規則與數字見 [BgObjects]、
 * docs/DECISIONS.md「「更多」背景物件規則」）。使用者原則（2026-10-03）：「塗黑不用看白不白，以有沒有物件判斷」。
 *
 * 兩個機制，都在貼紙層之後、灰圈收細與泡重繪之前：
 * - V（[veto]）：「更多」比只塗標準（L2）多塗黑的塊，白跨細線閉合成超區、超區證據 ‰ > [vetoEpm] ＝夾在物件之間的白 ⇒ 還原；
 * - L（[lightFill]）：亮背景區（白與淺色調）整區判有沒有物件，過門的從核心塗起、長回線邊。
 *
 * 效果線與閃光（2026-10-03 使用者裁定 2；[fxLines]／[fxSparks]，參數在 [fx]）：A 集中線不算物件、C 閃光不算物件，見 [EffectLines]、
 * [EffectLineParams]、docs/DECISIONS.md「「更多」效果線與閃光」。
 *
 * 生效條件＝[enabled] ∧ [MoreRuleParams.enabled]（研究端 `NIGHTREAD_OBJ`（預設 1）只在 `NIGHTREAD_MORE=1` 時有作用）。
 */
data class ObjectRuleParams(
    /** 總開關；false＝規則版本 2 的「更多」（逐像素相同）。只在 [MoreRuleParams.enabled] 的檔才看。 */
    val enabled: Boolean = true,
    /** V 開關（研究端 NIGHTREAD_OBJ_VETO；消融用）。 */
    val veto: Boolean = true,
    /** L 開關（研究端 NIGHTREAD_OBJ_LT；消融用）。 */
    val lightFill: Boolean = true,
    /** 效果線 A（集中線不算物件；研究端 NIGHTREAD_OBJ_FXA）。[fxLines] 與 [fxSparks] 都關＝效果線之前的規則版本 3（逐像素相同）。 */
    val fxLines: Boolean = true,
    /** 閃光 C（孤立亮記號不算物件、尺畫直線不給長線帶；研究端 NIGHTREAD_OBJ_FXC）。 */
    val fxSparks: Boolean = true,
    /** 效果線與閃光的參數組（獨立成 data class：建構子參數槽）。 */
    val fx: EffectLineParams = EffectLineParams(),
    /**
     * 人物旁的淡線外圈不塗（2026-10-04 複核 1；研究端 NIGHTREAD_OBJ_PF）：人物模型漏掉的手、筆多半是淡的點狀線，不算物件證據，
     * 亮背景區塗黑會蓋過去。從離人物 [pfTouch] px 內的淡線起、沿外擴 [pfBridge] 的淡線測地長 [pfReach] px，長到的再外擴 [pfHalo]
     * ＝不塗的圈。
     */
    val personFaint: Boolean = true,
    val pfTouch: Int = 5,
    val pfBridge: Int = 2,
    val pfReach: Int = 64,
    val pfHalo: Int = 10,
    /** 字畫亮只限碰到字框（[TextRegion]）的字塊（2026-10-04 複核 2；研究端 NIGHTREAD_OBJ_TXTREG）：DBNet 誤當字的樹叢不反相。 */
    val textNeedsRegion: Boolean = true,
    /**
     * 規則版本 4 q3（2026-10-05 使用者拍板；研究端 NIGHTREAD_OBJ_PFSHAPE）：人物旁的淡線外圈貼線形。長到的淡線逐塊看是不是
     * 「一條線」（在外接框外擴 [pfClose]＋1 的窗裡橢圓閉合 [pfClose]，閉合後面積 ×100 ≤ 原面積 ×[pfLinePct]）：線只外擴
     * [pfMargin]，不是線（手、網點、擠在一起的好幾筆）照版本 3 外擴 [pfHalo]；被淡線（閉合後）與交代過的遮罩圍住的小塊
     * （面積 ×1920² ≤ [pfHole] ×clamp(頁高, 960, 3840)²）或只被淡線自己圍住的塊，在版本 3 外圈之內的部分也不塗；碰到版本 3
     * 外圈的淡小記號（細暗線不經線核｜σ2 Canny 在去網點之前、外接框長邊 < [dot]）外擴 [pfMark] 也不塗。結果 ⊆ 版本 3 的外圈。
     * 交出前複核的修法 c（c362_001 揮手女孩袖口下方的前臂）：外接框長邊 < [pfStub] 的短截不算線、照版本 3 外擴 [pfHalo]；
     * 兩塊淡線之間（一塊外擴 [pfHalo] ∩ 另一塊外擴 [pfPair]）也不塗。
     * 只在 [personFaint] 開著時有作用。
     */
    val pfShape: Boolean = true,
    val pfClose: Int = 10,
    val pfMargin: Int = 3,
    val pfHole: Int = 2500,
    val pfLinePct: Int = 104,
    /** 淡小記號（P 類：情緒記號、短畫）的保護半徑；0＝不保護（＝合併研究的原樣）。 */
    val pfMark: Int = 3,
    /** 外接框長邊 < 此 px 的淡線塊（髮梢、從人物伸出來的一小截）不算一條線、照版本 3 外擴 [pfHalo]；0＝不做。 */
    val pfStub: Int = 16,
    /** 一塊淡線的版本 3 外圈裡、離別的淡線塊 ≤ 此 px（橢圓外擴）的也不塗（兩塊之間）；0＝不做。 */
    val pfPair: Int = 20,
    /**
     * 規則版本 4 q2（研究端 NIGHTREAD_OBJ_TXTSTROKE）：四周大多被塗黑、碰不到字框的字塊，像粗墨筆畫的照樣畫亮（手寫字）：
     * 墨（灰階 < [tsInk]）≥ [tsMin] px、灰階 ≤ [tsG] 的過半、輪廓平滑又不細（墨在外接框外補 2 px 0、3×3 方核開再閉，變動的 px
     * ×100 ≤ 墨的邊界 px（3×3 侵蝕掉的）×[tsRough]）。這些字旁、只由它們的墨引起的描亮邊塗回 BG（[tsBandClean]；有字框的字旁照
     * 版本 3 不動）。只在 [textNeedsRegion] 開著時有作用（關著的話所有字塊本來就畫亮）。
     */
    val textStroke: Boolean = true,
    val tsInk: Int = 128,
    val tsMin: Int = 5,
    val tsG: Int = 30,
    val tsRough: Int = 12,
    val tsBandClean: Boolean = true,
    /**
     * 規則版本 4 q1（研究端 NIGHTREAD_OBJ_SEAM）：塗黑區裡的灰虛線（兩階光影的交界被 σ4 Canny 當成調子邊）補黑：塗黑區橢圓閉合
     * [seamR] 補得起來、不是交代過／已經黑／人物旁淡線外圈的細縫，8 連通塊裡沒有細暗線、亮記號、谷（σ2 blackhat > [bhTh]）、
     * 原圖 < [seamG]、閃光外擴 [seamSpark] 的整塊補黑。
     */
    val seam: Boolean = true,
    val seamR: Int = 3,
    val seamSpark: Int = 4,
    val seamG: Int = 200,
    /**
     * 規則版本 4 q4（研究端 NIGHTREAD_OBJ_INPAINT）：譯後頁的小塊（< [islandMax] 整頁）有 ≥ [islandInpPct] % 在去字遮罩
     * （[NightReadInput.inpaintMask]）橢圓外擴 [islandInpD] 內就不塗（日文字去字後才變乾淨的白，例：泡與譯文之間的黑楔）。
     * 沒有去字遮罩＝版本 3。
     */
    val inpaintIslands: Boolean = true,
    val islandInpD: Int = 6,
    val islandInpPct: Int = 30,
    /** V：超區證據 ‰ 上限（原型 15；查核改 25：救回無物件的紙白與字幕框，有物件的 b 類最低 26.7）。 */
    val vetoEpm: Double = 25.0,
    /** V／L 脈絡：白跨細線閉合的橢圓半徑。 */
    val vetoRc: Int = 5,
    /** V：多塗的連通塊 ≥ 此 px 才看。 */
    val vetoMinArea: Int = 300,
    /** V：超區外擴此 px 內的證據。 */
    val vetoEvDil: Int = 2,
    /** V：否決時還原的帶＝否決塊方核外擴（描亮邊半徑＋此）。 */
    val vetoBackPad: Int = 2,
    /**
     * 「已經黑」＝這時已經塗成 [NightReadParams.bg] 的像素 ∪ 原圖灰階 ≤ 此（預設場景曲線 ≤ 40 的原圖上限）。原型用成品 ≤ 40——
     * 吃場景曲線與墨線增亮（兩邊浮點差 1 階）、也隨亮度偏好變；改成只看結構與原圖（V 與 L 都用）。
     */
    val darkG: Int = 63,
    /** L：σ5 後亮度下限（Q16 比）。 */
    val lightTh: Int = 150,
    /** L：區最小整頁佔比。 */
    val areaMin: Double = 0.002,
    /** L：區內細暗線＋亮記號 ‰ 上限。 */
    val lineMax: Double = 20.0,
    /** L：區內調子邊界（σ4 Canny）‰ 上限。 */
    val can4Max: Double = 12.0,
    /** L：二次曲面殘差（σ2.5 亮度，灰階單位）上限。 */
    val fitMax: Double = 9.0,
    /** L：調子邊緣 ‰ 上限。 */
    val toneMax: Double = 215.0,
    /** L：調子邊緣的梯度門檻（灰階／px；除以頁面尺度 clip(H/1920, 0.5, 2)）。 */
    val toneGrad: Double = 0.8,
    /** L：平均彩度上限（彩頁不動）。 */
    val chromaMax: Double = 6.0,
    /** L：區的最大內切半徑（5×5 chamfer）下限。 */
    val thickMin: Double = 24.0,
    /** L：孤立亮記號 ≥ 此個… */
    val marks: Int = 4,
    /** …且每 10 萬 px ≥ 此 ⇒ 整區留灰。 */
    val marksDen: Double = 4.0,
    /** L：核心＝離證據 > 此 px（chamfer）。 */
    val rLoc: Int = 24,
    /** L：含核心 ≥ 此整頁佔比的塊才塗。 */
    val coreMin: Double = 0.0008,
    /** L：證據封縫閉合半徑。 */
    val seal: Int = 4,
    /** L 脈絡：白塊（σ2.5 中位亮度 ≥ [ctxWhite]）的超區證據 ‰ 上限。 */
    val ctxEpm: Double = 25.0,
    val ctxWhite: Int = 230,
    /** L：孤島／貼人物檢查只對整頁佔比 < 此的塊。 */
    val islandMax: Double = 0.015,
    /** L：碰黑的距離（px）。 */
    val islandTouch: Int = 12,
    /** L：碰黑像素 < 此＝孤島。 */
    val islandTouchMin: Int = 20,
    /** L：外緣貼人物遮罩的比例上限。 */
    val hugMax: Double = 0.6,
    /** L：長線（外接框長邊 ≥ 此 px）四周 [longR] px 不當核心。 */
    val longLen: Int = 80,
    val longR: Int = 40,
    /** 證據：σ2 blackhat（橢圓半徑 5）> 此… */
    val bhTh: Int = 20,
    /** …且 8 方向 17 px 線核平均 > 此＝細暗線。 */
    val lineR: Int = 12,
    /** 證據：σ1.5 tophat > 此… */
    val brightTh: Int = 12,
    /** …且 8 方向 11 px 線核平均 > [brightLrNum]／[brightLrDen]（7.2）＝亮記號。 */
    val brightLrNum: Int = 36,
    val brightLrDen: Int = 5,
    /** 證據：網點＝連通塊外接框長邊 < 此 px（亮記號用 [dotBright]）。 */
    val dot: Int = 10,
    val dotBright: Int = 8,
    /** 證據連通塊面積下限。 */
    val evMinArea: Int = 15,
    /** σ2 Canny（否決證據）。 */
    val cannyLo: Int = 15,
    val cannyHi: Int = 40,
    /** σ4 Canny（調子邊界）。 */
    val canny4Lo: Int = 10,
    val canny4Hi: Int = 25,
)

/**
 * 「更多」效果線（A）與閃光（C）的參數（2026-10-03 使用者裁定 2；研究端 `research/nightread_fx.py` 與 `research/nightread_obj.py`
 * 的 `FX_*`／`FXC_*`／`LONG_STRAIGHT`），預設值＝研究端定案值（原型 FX.md）。規則見 [EffectLines]、docs/DECISIONS.md。
 * 角度門檻存成 cos 值的字面值（研究端同一個 double；不在執行時算三角函數）。
 */
data class EffectLineParams(
    // ── A1 整頁找效果線族（[EffectLines.field]）──
    /** 直分支：骨架點到主軸的均方根距離上限（px）。 */
    val segSd: Double = 1.2,
    /** 分支至少幾個骨架點。 */
    val segMin: Int = 8,
    /** 串接：方向差上限（cos 6°）。 */
    val cosJoin: Double = 0.9945218953682733,
    /** 串接：互相垂距上限（px）。 */
    val perp: Double = 2.5,
    /** 串接：沿線間隙上限（px）。 */
    val gap: Double = 20.0,
    /** 長線（px，×頁面尺度）。 */
    val lineMin: Double = 30.0,
    /** 線族：線方向與「中點→匯聚點」夾角上限（cos 3°）。 */
    val cosVp: Double = 0.9986295347545738,
    /** 候選交點只用最長的這麼多條線。 */
    val vpTop: Int = 120,
    /** 最多找幾族。 */
    val maxFamilies: Int = 4,
    /** 效果線族成員下限。 */
    val minMembers: Int = 8,
    /** 角展下限（cos 30°）：只收放射狀；平行族（牆板、欄杆）這一輪不收。 */
    val cosSpread: Double = 0.8660254037844387,
    /** 漸細比（外 40% ÷ 內 40% 帶內墨量）中位數下限。 */
    val taper: Double = 1.3,
    /** 漸細比 > 1.3 的成員佔比下限。 */
    val taperFrac: Double = 0.45,
    /** 自由端（線尾消失在紙白裡）至少幾個… */
    val freeMin: Int = 2,
    /** …且佔（自由＋擋住）的比例下限。 */
    val freeFrac: Double = 0.1,
    /** 自由端：沿線往外看 t＝[ray0]..[ray1] px。 */
    val ray0: Int = 4,
    val ray1: Int = 16,
    /** 自由端：σ1 灰階 < 此＝擋住。 */
    val rayG: Int = 215,
    /** 地盤：成員線橢圓外擴（px，×頁面尺度）。 */
    val terr: Int = 48,
    /** 效果墨：線方向對匯聚點夾角 ≤ 10°（二倍角 cos 20°）。 */
    val cosAtol2: Double = 0.9396926207859084,
    /** 效果墨：結構張量一致度下限。 */
    val coh: Double = 0.5,
    /** 一致度分母的 1e−6（灰階²）換到張量單位（×65536）。 */
    val epsJ: Double = 0.065536,
    /** 結構張量積分 σ。 */
    val si: Double = 4.0,
    /** 離匯聚點這麼近（px）的墨方向不可靠，不算效果墨。 */
    val vpNear: Int = 24,
    /** 小記號：墨連通塊外接框長邊 ≤ 此（px，×頁面尺度）。 */
    val mark: Double = 16.0,
    /** 成員線外擴這麼多 px 內的墨算效果墨。 */
    val lineR: Int = 3,
    // ── A2–A4（[BgObjects]）──
    /** 效果墨外擴：否決的超區證據扣掉這圈。 */
    val excl: Int = 4,
    /** 效果墨外擴：區的調子邊／擬合殘差量測、塗法的殘量證據扣掉這圈。 */
    val exclT: Int = 14,
    /** 區內墨裡效果墨的比例下限。 */
    val agree: Double = 0.85,
    /** 區內非效果墨 ‰ 上限。 */
    val resid: Double = 25.0,
    /** 區（補洞後）裡至少幾條成員線；否決的例外也用。 */
    val nMem: Int = 4,
    /** 效果線區的畫法：BG ＋ ((255−g)/255)^1.4 ×(此 − BG)。 */
    val lineV: Int = 170,
    /** 效果線區往外長最多幾 px。 */
    val grow: Int = 120,
    /** 殘量證據外擴：這圈不塗。 */
    val halo: Int = 8,
    /** 殘量證據連通塊至少這麼大才留灰圈。 */
    val erMin: Int = 30,
    /** 塗的塊至少這麼大。 */
    val minPart: Int = 400,
    // ── C 閃光 ──
    /** 孤立：亮記號橢圓外擴此 px 碰不到暗（< 128）或細暗線。 */
    val sparkIso: Int = 4,
    /** 閃光大小（外接框長邊 px，×頁面尺度）下限與上限。 */
    val sparkMin: Double = 5.0,
    val sparkMax: Double = 60.0,
    /** 閃光外擴此 px：調子邊（σ4 Canny）不算。 */
    val sparkDil: Int = 3,
    /** 塗黑區裡的閃光（外擴 1）畫成 max(現值, BG ＋ (g/255)^1.4 ×(此 − BG))。 */
    val sparkV: Int = 170,
    /** 長線帶不給「直的長線」（PCA 垂距均方根 ≤ 此 px）。 */
    val longStraight: Double = 2.0,
)

/**
 * 人物外灰圈收細（折衷版；2026-10-03 使用者拍板；研究端 `research/nightread_ring.py` 的 `RING_*`），預設值＝研究端定案值。
 *
 * 背景塗黑原本停在人物安全邊外，人物與黑之間留一圈 15–20 px 的灰。只在「真的有畫出來的輪廓線把背景跟人物隔開」的地方，
 * 讓已經塗黑的背景長到輪廓線；沒有輪廓線的地方維持現在的寬度。規則與數字見 [Ring]、docs/DECISIONS.md「人物外灰圈收細」。
 */
data class RingParams(
    /** 總開關；false＝與加入前逐像素相同（研究端 `NIGHTREAD_RING=0`）。 */
    val enabled: Boolean = true,
    /** 可認領的「被線稿否決的留白」只看人物遮罩（收邊後）外擴此 px 內（coreReleasePad 16 ＋ 2）。 */
    val rOut: Int = 18,
    /** 種子＝留得下來的背景黑，8 連通塊面積 ≥ 此 px（零星黑點不當種子；8 連通見 [Ring.fillSmallHoles]）。 */
    val seedMin: Int = 200,
    /** 「被線稿否決的留白」要夾在黑與人物之間：到種子 ＋ 到人物原輸出（5×5 chamfer）≤ 此 px。 */
    val gvGap: Int = 24,
    /** 人物原輸出閉運算（橢圓半徑）多出來的窄凹口不認領（寬 < 2·此 px）。 */
    val rawClose: Int = 6,
    /** 從種子最多長幾步（4 連通）。 */
    val steps: Int = 64,
    /** 深墨：方窗邊長。 */
    val inkBox: Int = 5,
    /** 深墨：窗內 (255 − 灰階) 總和下限＝ceil(0.15·5·5·255)（平均暗度 0.15；模糊與 JPEG 只把墨攤開、總量不變）。 */
    val inkSum: Int = 957,
    /** 深墨：中心灰階上限。 */
    val inkMaxGray: Int = 200,
    /** 人物原輸出補洞（只給證據用；洞＝不碰頁緣的 8 連通塊）的面積上限：洞的內緣不是人物的外輪廓。 */
    val holeMax: Int = 256,
    /** 輪廓：深墨落在人物原輸出內此 px … */
    val evIn: Int = 8,
    /** … 或原輸出外此 px 的帶裡，邊界點離它在橢圓半徑 max(evIn, evOut) 內＝有輪廓。 */
    val evOut: Int = 3,
    /** 開闊背景：外法向射線從第幾 px 開始看。 */
    val rayFrom: Int = 3,
    /** 開闊背景：射線走到第幾 px（又碰到人物原輸出＝口袋／夾縫，不算）。 */
    val rayTo: Int = 24,
    /** 外法向：人物原輸出高斯模糊的 σ（float64 逐點算；改了要重新確認核值與研究端逐位元相同）。 */
    val raySigma: Double = 2.0,
    /** 連續：有輪廓邊界點的閉運算半徑（補回短缺口）。 */
    val gapClose: Int = 6,
    /** 連續：沒輪廓的邊界點外擴半徑，範圍內的邊界點也算沒輪廓。 */
    val breakPad: Int = 12,
    /** 空白紙：淡筆觸的灰階上限。 */
    val faintGray: Int = 225,
    /** 空白紙：淡筆觸要離深墨 > 此 px（橢圓；深墨的抗鋸齒與模糊裙邊不算）。 */
    val faintInkPad: Int = 2,
    /** 空白紙：計數方窗邊長（邊界複製）。 */
    val faintWin: Int = 21,
    /** 空白紙：窗內淡筆觸 ≥ 此個的地方不認領（＝ceil(0.015·21·21)）。 */
    val faintMin: Int = 7,
    /** 收尾：黑（種子 ∪ 認領）開運算的橢圓半徑，認領裡放不進圓盤的細指頭不要。 */
    val open: Int = 3,
    /**
     * 收尾：開運算後只留經認領像素與種子 4 連通相連的（開運算削斷細頸後剩下的孤立黑塊回到現在的灰）。整合時加的（研究版沒有）：
     * 47 頁 × 五檔共 14 個不同的孤立黑塊（標準 5 塊 1,065 px、更多 7 塊 1,201 px），守護框不變；研究端 `NIGHTREAD_RING_SEEDCONN`。
     */
    val seedConnected: Boolean = true,
)

/**
 * 「更多」新規則 A2（2026-10-02，使用者拍板；研究端 `research/nightread.py` 的 `MORE_*`、`more_rule`），預設值＝研究端定案值。
 *
 * 在檔位（貼紙篩選）的 keep 上**只加不減**（更多 ⊇ L3 ⊇ 標準）。候選有三種：
 * - C1：安全網收下、檔位沒收的元件；
 * - C2：安全網拒收、而且只卡在「前景太少 figlo／字壓太多 textOnP／文字窗 textCov」三道門的（看 [Sticker.Metrics.gates]，
 *   安全網實際沒過的門）；
 * - C3：有框頁的頁邊留白元件，若把格內白的判準全部放寬 [hyst]（厚芯半徑 [NightReadParams.coreR]×(1−[hyst])、厚芯佔比、深入、
 *   包墨深入與包墨比都 ×(1−[hyst])）後算格內白（包含式遲滯：厚芯半徑是絕對 px、深入的頁邊帶是相對值，縮放會翻）。
 *
 * 只靠「貼框」才成為候選的（不是格內白、不是留白、不在無框頁）貼框分數要 ≥ [NightReadParams.frameHugMin]×(1+[hyst])。
 * 每個候選要過：外輪廓自由邊界 ≤ [tOut]、內部記號 ≤ [tIn]、外圈淡色 ≤ [faintMax]、軟門元件 rough ≤ [roughCap]、人物原輸出佔比
 * < [charMax]。軟門元件＝C2／C3 有沒過的門，或 C1 在貼框 [NightReadParams.frameHugStrong]×(1±[hyst]) 這一帶碰到強或弱貼框任一
 * 條路的字量門（強：字壓 > [NightReadParams.promotedTextOnMax]；弱：文字窗 > [NightReadParams.stickerTextMax] 且整頁佔比 < 0.02）。
 * 繪製（只在 [enabled] 的檔）：[keepDarkStroke]、[edgeSeedFallback]；無框頁的留白層仍用加規則前的 keep（[Sticker.Plan.baseAccept]）。
 *
 * 47 頁 × 7 種輸入（原尺寸、縮 0.9／0.95、放 1.05／1.1、JPEG q75／q85）：沒有 ≥ 2,000 px 的「規則自己翻面」、禁塗區（雲天空、
 * 線稿背景、待判物件）0 px；守護框 L3 18 → 20（/664）。值的由來見 docs/PARAMETERS_zh.md「「更多」新規則 A2」與 docs/DECISIONS.md。
 */
data class MoreRuleParams(
    /** 總開關；false＝與加入前逐像素相同（[NightTier.L3] 打開）。 */
    val enabled: Boolean = false,
    /**
     * S1 外輪廓自由邊界上限：元件補洞、[median] 中值平滑後的 1 px 內邊界，扣掉「交代過」的（人物（收邊後）、泡＋泡框線、字、
     * 格線，再方核外擴 [nearDil]）剩下的長度 L，L²/(4π·面積)（四捨五入到 2 位再比）。結果相同的區間 3.69–7.7。
     */
    val tOut: Double = 5.0,
    /**
     * S2 內部記號上限：元件的洞（8 連通塊）裡「真的有畫東西」的（面積 ≥ [holeMinArea] 或最暗 < [holeDarkMax]），其 1 px 內邊界
     * 扣掉交代過的長度，同式。結果相同的區間 0.52–3.79。
     */
    val tIn: Double = 2.0,
    /** S3 外圈淡色佔比上限（外圈扣人物後，非泡／字／格線、非暗墨暈、[NightReadParams.inkDarkTh] ≤ g < whiteTh）。區間 0.276–0.324。 */
    val faintMax: Double = 0.3,
    /** S4 軟門元件的 rough 上限（四捨五入到 1 位再比）。區間 34.9–40.4。 */
    val roughCap: Double = 40.0,
    /** S5 人物原輸出（[NightReadInput.charMask]）佔元件 ≥ 此＝人物身上／被人物包住的白，不收。區間 0.578–0.739。 */
    val charMax: Double = 0.67,
    /** 遲滯：C3 的格內白判準 ×(1−此)、只靠貼框的候選門檻 ×(1+此)、S4 的強弱貼框遲滯帶 ×(1±此)。 */
    val hyst: Double = 0.25,
    /** S2：洞面積 ≥ 此 px 才算「有畫東西」… */
    val holeMinArea: Int = 8,
    /** …或洞裡最暗 < 此（壓縮雜點、淡色噪點兩者都不是）。 */
    val holeDarkMax: Int = 200,
    /** S1：補洞後中值平滑的核邊長（二值多數決、BORDER_REPLICATE，同 cv2.medianBlur）。 */
    val median: Int = 5,
    /** 交代過：字（seg）與格線的方核外擴邊長（7＝外擴 3）。 */
    val explainDil: Int = 7,
    /** 交代過的全部再方核外擴的邊長（9＝外擴 4）。 */
    val nearDil: Int = 9,
    /** S3 外圈＝dilate(橢圓 (2r+1)²) − 元件。 */
    val ringR: Int = 7,
    /** S3 暗墨抗鋸齒暈的方核邊長。 */
    val halo: Int = 5,
    /** 逐元件量測窗＝bbox 外擴此 px（夾頁緣）。 */
    val winPad: Int = 16,
    /** P1：貼紙的前景描亮邊不蓋掉此時已經 ≤ bg 的像素（留白／格溝／前一顆貼紙塗黑的）。 */
    val keepDarkStroke: Boolean = true,
    /**
     * P2：核心填色裡沒有格框種子的核心塊（[NightReadParams.coreNeckR] 開運算後的 8 連通塊），頁緣（距頁緣 < [edgeBand]）落在該塊
     * 外擴 coreNeckR＋[edgeReach] 內的部分也當種子（出血格沒有格框線可長）。有格框種子的塊不加：加了測地比的直線切邊會往下移
     * （c362_017 肩旁階梯小黑楔）。
     */
    val edgeSeedFallback: Boolean = true,
    /** P2 頁緣種子帶寬（px）。 */
    val edgeBand: Int = 3,
    /** P2 種子帶只取缺種子核心塊外擴 coreNeckR＋此 內的頁緣。 */
    val edgeReach: Int = 4,
)

/**
 * 漏泡封縫（v3，2026-09-30，使用者採用；研究端 `research/nightread.py` 的 `BUBBLE_SEAL_*`）的參數，預設值＝研究端定案值。
 *
 * 病根：泡框上 1–2 px 的縫讓泡內的白與外面的大片白（格內背景、留白）連成同一個白元件 ⇒ 元件太大或被列為留白／格內白 ⇒
 * 整顆泡被拒、內部留場景灰（c362_005:3 使用者回報）。修法：這種元件只把元件的白以 (2[r]+1) 橢圓侵蝕（[r]=1 是 3×3 十字）
 * 得到「深白」，字碰到的每一塊深白依面積由大到小還原成一個單元（外擴 [r] 內的元件白、扣掉別塊深白也搆得到的封縫弧、填洞），
 * 單元當成新元件走 HEAD 的泡路徑（整頁佔比、局部性、比值、核心填色、貼墨閘都照舊），另加：碰頁緣拒、單元外剩的深白
 * ≥ [restMin]、縫 ≤ [maxGaps] 個、字框內筆畫 ≥ [textIn] 落在單元裡、每個縫到泡身的測地距離 ≤ [cutGeo]、小單元也過貼墨閘、
 * 外圈有彩拒、人物遮罩（原始）佔比 ≤ [charMax]。收下的只進泡的重繪層（泡重繪、偽泡、亮島、人物還原的泡優先），不進格溝／
 * 留白／出血過濾／線稿密度否決／貼紙／泡外圈這些結構層。47 頁 × 4 檔只改 c362_004／005／011 三頁、守護框不變。
 * 值的由來見 docs/PARAMETERS_zh.md「漏泡封縫」（英文版 “Bubble-leak sealing”）與 docs/DECISIONS.md。
 */
data class BubbleSealParams(
    /**
     * 封縫半徑（侵蝕核 (2r+1)² 橢圓）；0＝關（與加入前逐像素相同）。侵蝕封得住「實際白寬 ≤ 2r」的縫，與框線粗細無關
     * （閉運算在 2 px 細線上完全封不住）。1 ⇒ ≤ 2 px 的縫；2 會把雙線框 4 px 夾縫整條封掉。
     */
    val r: Int = 1,
    /** 單元（實際要填的）∩ 原始人物遮罩的佔比上限（真泡 ≤ 0.06、人物白 ≥ 0.99）。 */
    val charMax: Double = 0.25,
    /** 字區筆畫（字框內 seg）落在單元（含洞）裡的比例下限：泡要「裝著」字；只封出字旁的小口袋不算。 */
    val textIn: Double = 0.5,
    /** 封完後元件在這個單元以外必須還剩這麼多 px 深白：真的切下了一塊，不是把大元件削掉一圈邊。 */
    val restMin: Int = 50,
    /**
     * 封掉的縫（切口群）個數上限：「泡框上一兩個極窄縫」才封。真泡 c362_005:3／c362_011:5 在 1× 與擾動是 2–5 個、
     * c362_004:5 是 6–12 個；demo02 說明框（框線粗糙、四角全是 1 px 漏點）13–23 個。
     */
    val maxGaps: Int = 8,
    /**
     * 每個縫到「泡身」（單元以 [NightReadParams.bubbleNeckR] 開運算後含字框的寬闊塊）的測地距離上限（px，在單元∪縫內
     * 8 連通、每步 1 px）：縫要在泡自己的框上。真泡 2–16 px；雙線框夾縫的斜向窄點 36 px。
     * null（預設）＝3 × [NightReadParams.bubbleNeckR]（預設 24），同研究端 `BUBBLE_SEAL_CUT_GEO = 3 * BUBBLE_NECK_R`：
     * 調切頸半徑時上限跟著走；給值＝固定 px。
     */
    val cutGeo: Int? = null,
)

/**
 * 出血格過濾（[Bleed] ＝ `research/nightread_bleed.py`，2026-09-27）的參數，預設值＝研究端定案值（決策樹 sb1）。
 * 「白」沿用 [NightReadParams.whiteTh]。
 *
 * 留白帶（線稿密度否決之後）的每個 8 連通塊看它的**外圈**（橢圓膨脹 − 塊、扣掉頁緣）碰到什麼：框線／溝＝留、畫＝拿掉。
 * 47 頁實測撕口 215 → 83 塊；veto 後共 417 塊，拿掉 248（孤島 145＋畫 103）、留 169。值的由來見
 * docs/PARAMETERS_zh.md「出血格過濾」（英文版 “Bleed-panel filter”）與 docs/DECISIONS.md。
 */
data class BleedParams(
    /** 塊的外圈＝橢圓 (2r+1)² 膨脹 − 塊本身。 */
    val ringR: Int = 5,
    /** 外圈扣掉頁緣這麼多 px（頁緣外沒有證據，不能算成「沒碰到框線」）。 */
    val ringEdge: Int = 4,
    /** 逐塊運算的 bbox 外擴（≥ [ringR]，外圈才不會被裁掉）。 */
    val piecePad: Int = 8,
    /** FR：框線（水平垂直 ∪ 任意角度）方核外擴邊長。 */
    val frDil: Int = 9,
    /** SP：溝／頁邊（SEP 扣泡之前的 sepPre）方核外擴邊長。 */
    val spDil: Int = 5,
    /** BB：泡 橢圓外擴半徑（中性物）。 */
    val bbR: Int = 6,
    /** TX：文字筆畫 橢圓外擴半徑（中性物）。 */
    val txR: Int = 6,
    /** CH：人物 橢圓外擴半徑（中性物）。 */
    val chR: Int = 8,
    /** 文字區 bbox 外擴（txt 重疊比例用）。 */
    val textBoxPad: Int = 10,
    /** 塊的 bbox 距頁緣 ≤ 此 px ＝ 碰頁緣。 */
    val touchPx: Int = 2,
    /** 面積 ≥ 此×頁 ＝ 溝網本體，留。 */
    val netFrac: Double = 0.03,
    /** 與文字區重疊 ≥ 此，留（字旁的留白）。 */
    val txtKeep: Double = 0.15,
    /** frameInf＝(FR+SP)/inf ≥ 此，留（邊界多半是格框／溝）。 */
    val frameKeep: Double = 0.6,
    /** 孤島規則（不碰頁緣 ∧ FR+SP＝0 ⇒ 拿掉）的泡比例上限：被泡圍住一半以上的不是孤島（泡外圈的白）。 */
    val islandBbMax: Double = 0.3,
    /** inf（外圈扣掉中性物的比例）< 此 ＝ 被人物／泡／字包住、證據不足，留。 */
    val enclosedMax: Double = 0.15,
    /** artInf＝(VT+DC+AR)/inf ≥ 此，拿掉（邊界近半是畫）。 */
    val artDrop: Double = 0.45,
    /** 頁邊條檢驗：停點＝第一個比此暗的像素（或中性物）。 */
    val marginOk: Int = 200,
    /** 頁邊條檢驗：最大深度＝短邊×此（同 [NightReadParams.safeGutterDepth]）。 */
    val marginFrac: Double = 0.12,
    /** 頁邊條檢驗：途中亮而非白（[marginOk]–234：淡網點／漸層／雲）≤ max([marginLightMin], 此×深度)。 */
    val marginLightMaxFrac: Double = 0.02,
    /** …至少容許這麼多顆（頁緣掃描雜訊）。 */
    val marginLightMin: Int = 3,
    /** 頁邊條檢驗：資訊列（停在暗點上的列）至少這麼多；停點直線的跨度也要 ≥ 此。 */
    val marginMinRows: Int = 30,
    /** 頁邊條檢驗：停點往內這麼多 px 內碰到中性物也算停在中性物上（泡框／人物輪廓常在外擴遮罩外）。 */
    val marginNeuLook: Int = 8,
    /** 頁邊條檢驗：頁邊深度（停點中位數）下限。 */
    val marginMinDepth: Int = 4,
    /** 頁邊條檢驗：停點落在擬合直線 ±2px 內的列 ≥ 此比例。 */
    val marginCover: Double = 0.7,
    /** 頁邊條檢驗：停點直線與頁緣夾角上限（度）。 */
    val marginAng: Double = 5.0,
    /** 頁邊條檢驗：停點亮度中位數上限（是線不是漸層）。 */
    val marginDark: Int = 190,
    /**
     * 頁邊條框線本體：通過那邊每個在線上的停點，往內連續非白（< [NightReadParams.whiteTh]）這麼多 px 內要走回白才畫
     * （兩側都是紙的細線；走不回白＝框線跟畫黏在一起）。
     */
    val marginLineMax: Int = 12,
    /**
     * 頁邊條框線當 FR 證據的接觸長度下限：碰頁緣的塊沿這段框線（上下邊框線數不同的 x、左右邊框線數不同的 y）≥ 此，
     * 外圈落在它上面的像素才改記 FR（同 [marginMinRows]：頁邊條自己也要這麼多列才算一條線）。
     */
    val mlineMinRun: Int = 30,
)

/**
 * 任意角度格溝／頁邊（[Separators] ＝ `research/nightread_sep.py`，2026-09-27）的參數，預設值＝研究端定案值。
 * 「暗」沿用 [NightReadParams.frameDarkTh]、「白」沿用 [NightReadParams.whiteTh]。
 *
 * frame_line_mask 只認水平／垂直，斜格溝、被出血畫面打斷的溝、畫到頁緣的頁邊都進不了留白路徑；這組參數從像素找任意角度的
 * 格框線，兩線夾住的整條白＝溝、頁緣到框線的白＝頁邊。值的由來見 docs/PARAMETERS_zh.md「任意角度格溝」
 * （英文版 “Any-angle separators”）與 docs/DECISIONS.md。
 */
data class SeparatorParams(
    /** 框線最短長度＝短邊×此（至少 60px）：格框線是尺畫長線，效果線／線稿多半短於此。 */
    val lenFrac: Double = 0.15,
    /** 候選框線像素＝暗 ∧ 距白 ≤ 此（方核）：框線至少一側鄰白溝／白頁邊，線稿內部的暗不進 Hough。 */
    val nearWhiteR: Int = 3,
    /** Hough 角度解析度（度）。 */
    val thStep: Double = 0.5,
    /** 初走訪與 PCA 精修的法向半窗（px）：容 Hough 1° 內的偏角。 */
    val walkWin0: Int = 4,
    /** 精修後定端點的法向半窗（px）。 */
    val walkWin: Int = 2,
    /** 走訪容許的斷口（px）：框線被網點／抗鋸齒打出的小缺口。 */
    val gap: Int = 8,
    /** 定稿段的命中率下限（初切段只要 0.6；尺畫框線 ≈1.0）。 */
    val fillMin: Double = 0.85,
    /** 取峰 NMS 半窗：θ ±此格（±1.5°）。 */
    val peakNmsT: Int = 3,
    /** 取峰 NMS 半窗：ρ ±此 px。 */
    val peakNmsR: Int = 6,
    /** 最多取這麼多個峰（票數由高到低，numpy 不穩定排序的同票順序）。400 時 47 頁全數截到上限、漏掉真溝（2026-10-01 改 800）。 */
    val peakMax: Int = 800,
    /** 峰的票數下限＝此×最短框線長。 */
    val peakVoteFrac: Double = 0.8,
    /** 去重：夾角 ≤ 此（度）… */
    val dedupeAng: Double = 2.0,
    /** …且端點到對方直線 ≤ 此 px、沿線重疊 ⇒ 同一條線的兩次偵測，併成聯集。 */
    val dedupeOff: Double = 6.0,
    /** 共線分組：夾角 ≤ 此（度）… */
    val groupAng: Double = 1.0,
    /** …且端點到群組直線 ≤ 此 px ⇒ 同一條被打斷的框線（多段區間）。 */
    val groupOff: Double = 6.0,
    /** 溝：兩線夾角上限（度）。 */
    val pairAng: Double = 5.0,
    /** 溝：兩線法向距下限（px；太近＝同一條粗框線的兩緣）。 */
    val gapMin: Int = 6,
    /** 剖面白度只看離兩線各 > 此 px 的內部（框線本身＋抗鋸齒）；窄溝自動縮到 ¼ 溝寬。 */
    val edgeSkip: Int = 4,
    /** 單一剖面內部白佔比下限。 */
    val profWhite: Double = 0.9,
    /** 補橋段（至少一側沒有框線證據）的剖面：幾乎全白才算（出血人物／白衣跨溝時擋下）。 */
    val profWhiteBridge: Double = 0.98,
    /** 核心內 ≤ 此站數的補橋段視為框線偵測小斷口（用 [profWhite]）。 */
    val shortBridge: Int = 24,
    /** 剖面連續性：≤ 此站數的失敗小缺口（前後都過、不含字）補起來。 */
    val profGapFill: Int = 2,
    /** 有證據段裡通過的剖面比例下限（整帶多半是乾淨白＝溝，不是畫）。 */
    val passMin: Double = 0.6,
    /** 溝帶端延伸上限（短邊×此）：斜溝通到頁邊的楔形尖端、溝口。 */
    val stripExtFrac: Double = 0.15,
    /** 共線斷口補橋上限（短邊×此；同 [NightReadParams.gfcCloseFrac]）。 */
    val bridgeFrac: Double = 0.30,
    /** 白緣直線度（溝兩側白緣法向位置的 MAD·1.4826，逐段去線性趨勢）上限（px）。 */
    val edgeMadMax: Double = 1.0,
    /** 文字筆畫外擴（方核半徑）。 */
    val textDil: Int = 3,
    /** 含字剖面比例上限（超過＝字框／說明框，不是溝）。 */
    val textProfMax: Double = 0.2,
    /** 第三條近平行長線的夾角上限（效果線／排線家族；放射狀效果線相鄰夾角可達數度）。 */
    val familyAng: Double = 10.0,
    /** 在 a 外側或 b 外側 ≤ 此×溝寬內有第三條平行長線 ⇒ 排線，不是溝。 */
    val familyReach: Double = 1.5,
    /**
     * 溝寬上限（短邊×此）：實測真溝 1.0–1.3%（直）、2.5–3.2%（橫）、斜向寬帶 6.3%／7.9%；
     * 次寬的候選（demo01 10.7%）是畫面內的平行線、已被剖面白度擋下。
     */
    val wmaxFrac: Double = 0.09,
    /** 兩線重疊長度下限＝此×最短框線長。 */
    val ovlFrac: Double = 0.5,
    /** 端延伸／框線延長：先跨過端點處的格角暗像素（≤ 此 px）。 */
    val extDarkSkip: Int = 8,
    /** 框線貼泡判定：泡遮罩外擴此 px（方核半徑）。 */
    val bubLineR: Int = 7,
    /**
     * 線上取樣點（每 2px）落在外擴泡遮罩內的比例 ≥ 此 ⇒ 泡／說明框外框，不當格框
     * （c371_009：方形說明框左緣曾被當頁邊框線 → 效果線之間被塗成條紋）。
     */
    val bubLineMax: Double = 0.5,
    /** 溝網：碰到頁邊帶／頁緣／已收的溝帶（外擴此 px）才算接上網。 */
    val netTouch: Int = 2,
    /** 溝網粗黑塊判定：暗像素 chamfer 距離（cv2 DIST_L2 3×3）≥ 此（筆畫寬 ≥ 10px：狀聲詞、實心黑可通行）。 */
    val netThick: Int = 5,
    /** 溝帶可沿自身走廊（兩框線之間、沿線延伸 ≤ 短邊×此）經可通行像素接上網。 */
    val netExtFrac: Double = 0.15,
    /** 頁邊行程必須連成 ≥ 短邊×此 的列／行段（效果線之間的細白縫＝畫，不是頁邊）。 */
    val marginRunFrac: Double = 0.015,
    /** 頁邊行程內「亮但不白」像素比例上限（淡網點／漸層＝畫）。 */
    val marginLightFrac: Double = 0.02,
    /** …至少容許這麼多顆（頁緣掃描雜訊）。 */
    val marginLightMin: Int = 3,
    /** 撞到溝框線後往前探這麼多 px 看另一側是不是溝（是 ⇒ 這段白在出血格內，不是頁邊）。 */
    val farProbe: Int = 14,
    /** 頁邊行程的可通行亮度下限（塗色仍只塗 ≥ [NightReadParams.whiteTh]）。 */
    val marginOkTh: Int = 200,
    /** 頁邊最大深度（短邊×此；同 [NightReadParams.safeGutterDepth]）。 */
    val marginFrac: Double = 0.12,
    /** 框線點陣化的半寬（給線稿密度否決當格線遮罩、給出血過濾當框線結構）。 */
    val flR: Int = 3,
    /** 頁邊「撞到框線」判定的半寬：擬合線落在框線鄰白那一緣，粗框線（≤ 8px）另一緣也要算。 */
    val flHitR: Int = 8,
    /** 已成溝的框線當頁邊框線：與頁緣方向夾角上限（度）。 */
    val marginEdgeAng: Double = 30.0,
    /** 其餘框線當頁邊框線：必須幾乎平行頁緣（度；格子外框沿版心，斜的排線／效果線不是）。 */
    val marginAxisAng: Double = 5.0,
    /** 其餘框線當頁邊框線的命中率下限（尺畫框線 ≈1.0）。 */
    val marginFillMin: Double = 0.95,
    /**
     * 頁邊斷框線：被狀聲詞／出血物蓋斷、每截都短於最短框線長的框線，由第二趟短段偵測＋聯合擬合分組＋共線串接補回
     * （只給頁邊與頁邊否決的格線遮罩，不進溝帶／出血過濾）。c371_001 第二排左框被「ザ」蓋斷成 150／159px 兩截（最短框線長 203）；
     * 分組不比各截角度（短段方向誤差 ∝ 1／段長），改看兩截命中像素聯集的 PCA 直線上、每截命中率是否都 ≥ fillMin。
     */
    val marginOcc: Boolean = true,
    /** 補列：伸進頁邊的物件擋住的列（兩側都是頁邊行程、缺口 ≤ [occGapFrac]）也算頁邊，物件後面與頁邊白相連的白一起塗。 */
    val marginClose: Boolean = true,
    /** 斷框線的每一截 ≥ 最短框線長×此（整條的證據量仍要 ≥ 最短框線長）。 */
    val occPieceFrac: Double = 0.5,
    /** 斷口上限（短邊×此，rint）：斷框線相鄰兩截之間、補列的缺口長度。 */
    val occGapFrac: Double = 0.05,
    /** 補列的淡網點判準扣掉暗像素（< frameDarkTh）外擴此 px（方核半徑）內的暈：暈屬於擋路的物件。 */
    val marginHaloR: Int = 2,
    /** 圖層：泡遮罩外擴（方核邊長）——SEP 讓開泡與泡框。 */
    val bubDil: Int = 7,
    /** 圖層：單條溝被外擴泡遮罩吃掉 > 此比例 ⇒ 整條不塗（剩下的會是梯子狀碎段）；頁邊不受影響。 */
    val pairSubMax: Double = 0.5,
    /** 圖層：扣掉泡之後小於此 px 的連通塊不塗（避免斑點）。 */
    val minCc: Int = 150,
)

/**
 * 背景填黑三檔的貼紙篩選模式（[NightReadParams.stickerMode]）；對應研究端 `STICKER_MODE` 的 all／simple／plain。
 */
enum class StickerMode {
    /** 通過安全網的全填（研究端完整管線，library 預設）。 */
    ALL,
    /** 只留「無畫面背景」加上 rough／面積門檻放行的元件（L2／L3）。 */
    SIMPLE,
    /** 只留「無畫面背景」：不碰人物、外圈只碰格線／頁邊（L1）。 */
    PLAIN,
}

/**
 * 產品的背景填黑三檔：**三檔參數的單一來源**（engine／fork 不再自己拼 stickerMode／門檻）。
 *
 * [apply] 只改貼紙篩選三欄（[NightReadParams.stickerMode]／[NightReadParams.stickerRoughMax]／
 * [NightReadParams.stickerSimpleMinFrac]）與「更多」新規則的開關（[MoreRuleParams.enabled]），並一律關偽泡與亮島填黑；
 * 其餘（亮度等）照 base。這正是 [NightRead.renderTiers] 的前提——同一個 base 套出來的三檔可以共用一次分析。
 *
 *   L1＝PLAIN（留白＋封閉泡＋無畫面背景）；L2＝SIMPLE rough ≤ 10 且整頁佔比 ≥ 0.5%（產品「標準」）；
 *   L3＝SIMPLE rough ≤ 20、無面積下限，再加「更多」新規則 A2（產品「更多」，2026-10-02 起）。
 * keep 集合在結構上巢狀（L1 ⊆ L2 ⊆ L3：[Sticker.filterPlan]，A2 只加不減）；值的由來見 docs/DECISIONS.md「背景填黑三檔」
 * 與「「更多」新規則 A2」。
 * [key] 是 fork 偏好 `nightread_fill_level` 用的字串（產品只出 l2／l3；夜讀檔名是 `.night.std.webp`／`.night.more.webp`，
 * 舊版的 `.night.l1.webp`…只供相容讀取）。
 */
enum class NightTier(
    val key: String,
    private val mode: StickerMode,
    private val roughMax: Double,
    private val minFrac: Double,
    private val moreRule: Boolean,
) {
    L1("l1", StickerMode.PLAIN, 10.0, 0.005, false),
    L2("l2", StickerMode.SIMPLE, 10.0, 0.005, false),
    /** 產品「更多」（2026-10-02 起）：SIMPLE rough ≤ 20、無面積下限，再加「更多」新規則 A2（[MoreRuleParams]）。 */
    L3("l3", StickerMode.SIMPLE, 20.0, 0.0, true),
    ;

    /**
     * 這一檔的完整參數：[base] 只換貼紙篩選三欄與 [MoreRuleParams.enabled]（其餘「更多」參數照 base）、關偽泡與亮島填黑。
     * 舊的 L3（沒有 A2，研究對照用）＝`L3.apply(base).copy(more = base.more.copy(enabled = false))`。
     */
    fun apply(base: NightReadParams = NightReadParams()): NightReadParams = base.copy(
        stickerMode = mode,
        stickerRoughMax = roughMax,
        stickerSimpleMinFrac = minFrac,
        more = base.more.copy(enabled = moreRule),
        pseudoBubbles = false,
        harmonize = false,
    )

    companion object {
        /** [key] → 檔位；不認得（含 null）回 null，舊值對應（protect／aggressive）由呼叫端決定。 */
        fun fromKey(key: String?): NightTier? = entries.firstOrNull { it.key == key }
    }
}

/** 文字區（偵測器的輸出經區域合併後的結果）。座標是原圖像素。 */
data class TextRegion(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/**
 * 夜讀的輸入。偵測與人物分割都在模組外面做完再送進來——這個模組只負責分區重繪，
 * 不綁任何推論框架，JVM 測試才跑得起來。
 *
 * @param gray 頁面灰階（0..255）
 * @param seg 文字遮罩。**要的是文字「區域」不是精確筆畫**：氣泡的核心填色拿它當種子，
 *            換成只有筆畫的精確遮罩會讓種子太小、泡填不滿（實測某顆泡區域覆蓋 90%、精確筆畫
 *            只有 32% 是黑）。而且它要涵蓋所有文字，包括不打算處理的裝飾字——漏掉的那顆泡
 *            會整顆沒填。DBNet 的第二個輸出正好是區域，所以合用。
 * @param regions 文字區
 * @param charMask 人物遮罩（模型原輸出，未收邊未平滑）。**必要**：沒有它紅線不可達。
 * @param chroma 每像素彩度（max−min 通道），彩頁判定用；純灰階頁可傳 null。
 * @param inpaintMask 譯後頁的去字遮罩（翻譯素材 `.yakuyomi/<頁>.mask.png`，**與 [gray] 同尺寸**，true＝去字區）。只給「更多」
 *            背景物件規則的孤島判斷用（規則版本 4，[ObjectRuleParams.inpaintIslands]）：去字區旁的小塊不塗。日文頁、沒有素材＝null
 *            （與規則版本 3 相同）；全空的遮罩也等於 null。「標準」不看它。
 */
data class NightReadInput(
    val gray: Gray,
    val seg: Mask,
    val regions: List<TextRegion>,
    val charMask: Mask,
    val chroma: Gray? = null,
    val inpaintMask: Mask? = null,
)

/** 夜讀的輸出，附上供除錯與驗收用的中間遮罩。 */
data class NightReadResult(
    val out: Gray,
    val gutter: Mask,
    val bubble: Mask,
    val charMask: Mask,
    val frameless: Boolean,
    /** 貼紙計畫（除錯／parity 用）：哪些白元件被接受、其中哪些走核心填色。 */
    val stickerAccept: Set<Int> = emptySet(),
    val stickerPromoted: Set<Int> = emptySet(),
    /**
     * 實際塗的任意角度格溝／頁邊（[Separators] 圖層的 sep：泡 ⊕7 扣掉、被泡吃過半的溝丟掉、碎塊丟掉）；
     * [NightReadParams.separators] 關時為 null。
     */
    val sep: Mask? = null,
)
