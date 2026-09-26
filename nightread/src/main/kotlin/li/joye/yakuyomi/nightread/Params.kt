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
    val bubbleRestNear: Int = 20,

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
