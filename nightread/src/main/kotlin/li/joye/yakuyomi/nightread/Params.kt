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
     *   （橢圓 (2·[stickerPlainRingR]+1)² 膨脹減元件）非空、外圈上「非格線的墨」佔比 < [stickerPlainArtMax]
     *   ⇒ 邊界只碰格線／頁邊、沒碰線稿（L1）。⚠️ 實測（19 頁）：過安全網的 55 顆元件外圈線稿佔比最低 0.110（泡框與字也算墨）> 0.05 ⇒ plain 零命中，L1 實際上＝留白＋泡、不填任何貼紙；「無畫面背景」要更寬的判準（外圈扣掉泡框／字）另案研究。
     * - [StickerMode.SIMPLE]：PLAIN 的那些 ∪ {rough ≤ [stickerRoughMax] 且整頁佔比 ≥ [stickerSimpleMinFrac]}（L2／L3）
     *
     * 落選的擢升元件連核心填色也不做；落選元件回到沒有貼紙層時的待遇（有框頁場景調壓暗、無框頁背景保留），
     * 絕不會比原圖糟。產品三檔由呼叫端整組設：L1＝PLAIN＋[pseudoBubbles]=false＋[harmonize]=false；
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
 */
data class NightReadInput(
    val gray: Gray,
    val seg: Mask,
    val regions: List<TextRegion>,
    val charMask: Mask,
    val chroma: Gray? = null,
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
)
