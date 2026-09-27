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
     *   （橢圓 (2·[stickerPlainRingR]+1)² 膨脹減元件）非空、外圈上「非格線的墨」佔比 < [stickerPlainArtMax]、「淡線稿」
     *   佔比 < [stickerPlainFaintMax] ⇒ 邊界只碰格線／頁邊、沒碰線稿（L1）。⚠️ 實測（47 頁、2026-09-27）：過安全網的
     *   139 顆元件裡，不碰人物且暗墨 < 0.05 的只有 2 顆（c362_009 有雲的天空、c371_010 天花板淡線的小三角），兩顆淡線稿
     *   都 ≥ 0.167 ⇒ 加了淡線稿判準後 plain 零命中，L1 實際上＝留白＋泡、不填任何貼紙；「無畫面背景」要更寬的判準
     *   （外圈扣掉泡框／字）另案研究。
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
    /**
     * PLAIN：外圈上「淡線稿」（[inkDarkTh] ≤ g < [whiteTh]、不在格線外擴內、也不在暗墨暈 [stickerPlainFaintHalo] 內）
     * 佔比 < 此，才算只碰格線／頁邊。雲、效果線、淡網點都比 [inkDarkTh] 亮，只數暗墨會漏掉（c362_009 彩旗下的天空：
     * 暗墨 0.041 過了 0.05 門、淡線稿 0.638 ⇒ 整片有雲的天空被當無畫面背景塗黑）。47 頁能走到這道判準的只有 2 顆
     * （0.167、0.638），門檻取 0.10 兩側都留空間。
     */
    val stickerPlainFaintMax: Double = 0.10,
    /** PLAIN：暗墨（g < [inkDarkTh]）抗鋸齒暈的方核外擴邊長（5×5）；暈裡的淡像素屬於暗線、不算淡線稿。 */
    val stickerPlainFaintHalo: Int = 5,
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
    /** 最多取這麼多個峰（票數由高到低，numpy 不穩定排序的同票順序）。 */
    val peakMax: Int = 400,
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
    /**
     * 實際塗的任意角度格溝／頁邊（[Separators] 圖層的 sep：泡 ⊕7 扣掉、被泡吃過半的溝丟掉、碎塊丟掉）；
     * [NightReadParams.separators] 關時為 null。
     */
    val sep: Mask? = null,
)
