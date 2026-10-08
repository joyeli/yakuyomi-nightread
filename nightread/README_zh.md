# `:nightread` — 夜讀重繪 library

[English](README.md) ｜ 中文

漫畫夜讀模式的重繪核心。給它一頁灰階，加上三份分析素材（文字遮罩、文字區 bbox、人物遮罩），回一頁重建過的暗色頁：對話框變成深底亮字、留白填黑、人物用亮邊抬出來，其餘部分經過色調映射讓黑線維持黑。

這個模組自成一件。它不含 reader、不含文字偵測，也不綁任何推論框架：唯一做的事是 `NightRead.render(input) -> NightReadResult`，素材從哪裡來由呼叫端決定。Yakuyomi 是拿 [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) 的 DBNet 偵測器產生的，那是其中一種接法，不是硬性要求，見[文字偵測不在這個 repo 裡](#文字偵測不在這個-repo-裡)。

> **這頁是整合指南。** 演算法在每個分區判斷什麼見 [`docs/ARCHITECTURE_zh.md`](../docs/ARCHITECTURE_zh.md)，參數逐項見 [`docs/PARAMETERS_zh.md`](../docs/PARAMETERS_zh.md)，每個值的實測由來與被否決的替代方案見 [`docs/DECISIONS.md`](../docs/DECISIONS.md)。

## 模組

| 模組 | Gradle 座標 | 依賴 | 做什麼 |
|---|---|---|---|
| `:nightread` | `li.joye.yakuyomi:nightread:0.1.0` | 只有一行 `testImplementation junit` | 分區重繪管線 |

Android library，minSdk 26、compileSdk 37、Java 17，也設了 group 與 version，所以呼叫端用 Gradle composite build（`includeBuild`）就能接，Yakuyomi fork 就是這樣接的。

只留一個模組是刻意的。`:nightread` 只用 Kotlin 與 JDK 標準庫（`kotlin.math`、`java.math`、`java.util.concurrent`），連 `android.graphics` 都不碰：這樣管線才跑得起 JVM 單元測試（拿 Python fixture 對比就是這麼做的），也讓它可以被任何 JVM 專案直接拿走。人物遮罩在別處算：Yakuyomi 是在 [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) 的 `:nightread-android` 模組用 NCNN 跑那兩顆分割模型（`CsegSegmenter` 與 `YoloSegSegmenter`，見[人物遮罩模型](#人物遮罩模型)），這個 repo 只吃算好的遮罩。這裡原本有個 `:nightread-ort` 模組用 ONNX Runtime 跑同兩顆模型，搬到 NCNN 後就拿掉了。

## 快速開始

```kotlin
dependencies {
    implementation("li.joye.yakuyomi:nightread:0.1.0")
}
```

```kotlin
// 1. 頁面像素，就是 Bitmap.getPixels 填的那種 ARGB int 陣列。
val argb = IntArray(w * h)
pageBitmap.getPixels(argb, 0, w, 0, 0, w, h)

// 2. 灰階與彩度。灰階公式是固定的（見格式要求第 1 條）。
val gray = Gray(w, h)
val chroma = Gray(w, h)                 // 選用；純灰階頁可以傳 null
for (i in argb.indices) {
    val px = argb[i]
    val r = (px shr 16) and 0xFF
    val g = (px shr 8) and 0xFF
    val b = px and 0xFF
    gray.data[i] = (r * 299 + g * 587 + b * 114 + 500) / 1000
    chroma.data[i] = maxOf(r, g, b) - minOf(r, g, b)
}

// 3. 文字遮罩與文字區 bbox，來自你的偵測器（見格式要求第 2、3 條）。
val seg: Mask = yourTextMask(w, h)                      // Mask(w, h)，true＝在文字區內
val regions: List<TextRegion> = yourTextBoxes()         // TextRegion(x0, y0, x1, y1)，原圖像素

// 4. 人物遮罩，來自你的分割器，要模型原輸出（見格式要求第 4 條）。
//    接 yakuyomi-engine 的話是 Mask(w, h, yolo.segment(pageBitmap)) 再與 cseg 那份取 OR，見下。
val charMask: Mask = yourCharacterMask(w, h)            // Mask(w, h)，true＝人物

// 5. 重繪。
val result: NightReadResult = NightRead.render(
    NightReadInput(gray, seg, regions, charMask, chroma),
    NightTier.L2.apply(),   // 標準；NightTier.L3.apply() 是更多。預設的 NightReadParams() 是研究端完整管線。
)

// 6. 暗色頁，每像素 0..255，尺寸與輸入相同。
val dark = result.out
val outPixels = IntArray(w * h) {
    val v = dark.data[it]
    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
val nightBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
```

入口都在無狀態的 `NightRead` 上：`render(input, p, debug)` 產一檔；`render(input, p, debug, parallel)` 多一個選用的 `Executor`（見「生命週期與執行緒」）；`renderTiers(input, tiers, debug, sink)` 與 `renderTiers(input, tiers, debug, parallel, sink)` 分析一次、依序把多檔交給 `sink`（確定會跟前一檔相同的傳 `null`；見 `docs/PARAMETERS_zh.md`「Kotlin：`NightTier` 與 `renderTiers`」）。各檔參數用 `NightTier.L1/L2/L3.apply(base)` 產生；規則版本是 `NightRead.RULES_VERSION`（見[規則版本](#規則版本)）。

```kotlin
fun render(
    input: NightReadInput,
    p: NightReadParams = NightReadParams(),
    debug: NightReadDebug? = null,
): NightReadResult
```

`NightReadDebug` 是 `(stage: String, value: Int) -> Unit` 的 typealias。這些型別都在 `li.joye.yakuyomi.nightread`。

## 輸入

`NightReadInput` 四樣必要素材加兩個選用欄位：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `gray` | `Gray(w, h)` | 頁面灰階 0..255 |
| `seg` | `Mask(w, h)` | 文字遮罩（見格式要求第 2 條：要區域，不是精確筆畫） |
| `regions` | `List<TextRegion>` | 文字區的 bbox（`x0, y0, x1, y1`，原圖像素） |
| `charMask` | `Mask(w, h)` | 人物遮罩，模型原輸出，**必要** |
| `chroma` | `Gray(w, h)?` | 每像素彩度（max−min 通道），彩頁判定用；純灰階頁可傳 `null` |
| `inpaintMask` | `Mask(w, h)?` | 只有譯後頁：翻譯素材的去字遮罩（`.yakuyomi/<頁>.mask.png`，與頁同尺寸，`true`＝去字區）。給「更多」（規則版本 4）用：去字後留在譯文旁的乾淨白小塊不塗。日文頁傳 `null`（全空的遮罩等於 `null`） |

### 文字偵測不在這個 repo 裡

夜讀不含偵測器。`seg` 與 `regions` 得自己準備。我們自己用的是 manga-image-translator 的 DBNet，透過 yakuyomi-engine 取得，但管線本身不依賴它：任何能輸出「文字區域遮罩 + 文字區 bbox」的來源都行，只要符合下面的格式要求。在 yakuyomi-engine 裡，DBNet 的 `Detector` 在 `:inference-core` 模組，`:nightread-android` 的 `NightReadRenderer` 拿它產生 `seg` 與 `regions`（人物遮罩來自分割器），見[在 Android 上使用夜讀](../README_zh.md#在-android-上使用夜讀)。這條路不需要翻譯引擎。

## 格式要求

四條。踩了不會直接失敗，而是輸出默默變糟，所以先檢查這四條。

**1. 灰階必須是 BT.601。**

```
gray = (R * 299 + G * 587 + B * 114 + 500) / 1000
```

跟 `cv2.imread(IMREAD_GRAYSCALE)` 同一條公式。管線所有門檻（白 235、墨 128 等等）都是在這個灰階空間量出來的，換公式門檻就全歪。

**2. `seg` 要是文字「區域」而不是精確筆畫。** 這是最容易踩的一條，而且是量出來的：拿排版器輸出的精確筆畫（只有真正的字、不含字周圍的白）餵進來，氣泡會填不滿，因為核心填色拿 `seg` 當種子。某顆泡的區域遮罩覆蓋 90%，精確筆畫只有 32% 是黑，種子太小填不動。DBNet 的第二個輸出正好是區域遮罩，所以合用。自備偵測器的人要確認輸出的是哪一種，二值化也是呼叫端的事（research 管線用 0.12）。

**3. `seg` 要涵蓋所有文字，包含不打算處理的裝飾字。** 同一組實測裡，一顆裝飾性手寫字的泡因為不在任何文字區內而整顆漏掉沒填。

**4. `charMask` 要傳模型原輸出**，不要先收邊或平滑。管線自己會做貼墨收邊與中值平滑，而且偽泡那一段要用未加工的版本判定像素屬於邊界哪一側。

## 人物遮罩模型

人物遮罩是必要輸入，但這個 repo 不算它。Yakuyomi 是在 yakuyomi-engine 算的，兩顆模型都跑 NCNN——與引擎的偵測、去字同一個後端：

```kotlin
// yakuyomi-engine 的 :nightread-android；兩個都實作 CharSegmenter { fun segment(page: Bitmap): BooleanArray }
val yolo = YoloSegSegmenter(yoloParamPath, yoloBinPath)
val cseg = CsegSegmenter(csegParamPath, csegBinPath)
val a = yolo.segment(pageBitmap)
val b = cseg.segment(pageBitmap)
val charMask = Mask(w, h, BooleanArray(w * h) { a[it] || b[it] })
```

`segment` 吃頁面 `Bitmap`，回傳與頁面同尺寸的布林陣列，true＝人物；定案配方是兩顆取聯集。每個分割器各持一個 NCNN net，都是 `AutoCloseable`。

| 模型 | 檔案 | 大小（fp16） | 角色 | 授權 |
|---|---|---|---|---|
| YOLO11-seg | `manga_seg_s.ncnn.param` + `.bin` | 20.4 MB | 定案配方的基底，也是單獨跑時最省的一顆 | 模型卡寫 `other`；以 Ultralytics YOLO11 訓練，**AGPL-3.0** |
| CartoonSegmentation（RTMDet-Ins） | `cartoonseg.ncnn.param` + `.bin` | 126 MB | 可選，加了更準 | 沒寫明：repo 沒有 LICENSE 檔；原始權重的 Hugging Face 模型卡寫 MIT，ONNX 轉檔版沒寫 |

Yakuyomi 以研究與非商業用途散布兩顆的 NCNN 轉檔，權利人要求即下架；見[引擎的模型說明](https://github.com/joyeli/yakuyomi-engine/blob/main/docs/MODELS_zh.md#夜讀模型)。

量測定了三件事：

- **只用 YOLO11-seg 也能跑**，代價是守護框違規變多：當時量的是 18 → 25（2026-09-21，那時 665 框）。
- **全 fp16、不用 int8**：CartoonSegmentation 過 `ncnn2int8` 後零實例（是工具鏈在這張圖上壞掉，不是校準問題）；YOLO11-seg int8 只在本來就 0.4 s 的遮罩上快 7%。兩者都是真機量的。先前「CartoonSegmentation 的 ONNX int8 版會過度覆蓋、把泡吃掉」那條，在裝置上已不跑 ONNX 之後就無關了。
- **聯集一頁 1.2～1.4 s**（測試機 Snapdragon 8 Gen 3），權重 146 MB。

同兩顆模型的 `.onnx` 匯出檔仍是桌面 `research/charmask.py` 用 Python `onnxruntime` 跑的版本，那是 NCNN 移植的對照基準（聯集 IoU ≥ 0.996），不是裝置用的。

要散布的人請注意 YOLO11-seg 權重的 AGPL-3.0，這條授權會傳染。它與本專案的 GPL-3.0 相容（GPLv3 §13），但相容不等於沒有義務。

## 輸出

`NightReadResult` 除了成品頁還帶著中間結果，呼叫端不必自己重算就能除錯或跑驗收：

| 欄位 | 型別 | 內容 |
|---|---|---|
| `out` | `Gray` | 暗色頁 |
| `gutter`、`bubble`、`charMask` | `Mask` | 中間遮罩，除錯與驗收用 |
| `frameless` | `Boolean` | 頁型判別結果 |
| `stickerAccept`、`stickerPromoted` | `Set<Int>` | 貼紙計畫，parity 檢查用 |
| `sep` | `Mask?` | 實際塗的任意角度格溝／頁邊（`separators` 關時為 `null`），parity 檢查用 |

## 生命週期與執行緒

- `NightRead` 是 object 且不持有狀態。除錯回呼是傳入參數（`NightReadDebug`）而不是全域欄位，所以 `render` 可以並發呼叫。
- 只要分段計時的呼叫端讓回呼實作 `NightReadStageTimer`：段名與順序不變，遮罩計數那幾段的值送 0（不算那十來次整頁掃描）。
- 頁內並行（2026-10-06）：`render(input, p, debug, parallel)`、`renderTiers(input, tiers, debug, parallel, sink)` 多一個 `Executor`。分析裡彼此獨立的分支（人物收邊平滑、場景曲線、灰圈證據、貼紙計畫）與「更多」的背景物件量測丟給它跑，主執行緒用到時才等（2026-10-07 起，量測裡只有部分頁用得到的物件證據、長線帶、人物旁淡線外圈、調子邊緣改成合成時在主執行緒上、只在用得到的地方算）；分支還沒開始就由主執行緒自己跑，所以池子小、滿或拒收都不會乾等。輸出與依序版逐位元相同；`null`（舊的多載）＝依序。中斷跟依序版一樣不理會：主執行緒等分支時被中斷照等，回傳前把中斷旗標補回去。代價是 heap 尖峰：2.6 MPx 頁約多 11–27 MB（最多約 10.4 B/px，跟排程有關），所以多頁並行、heap 預算緊的時候通常不划算；開了每頁至少估 70 B/px（見 `docs/DECISIONS.md`「加速四批」）。
- 產生 `charMask` 的分割器不歸這個模組管。在 yakuyomi-engine 裡它們各持一個 NCNN net、都是 `AutoCloseable`：建一次、跨頁重複用、用完 close。
- 耗時：一次分析產兩檔，桌面 JVM 單緒每頁約 1.3 s（65 頁平均），Snapdragon 8 Gen 3 的 debug 版約 7 s（由桌面 debug 代理估算）；人物遮罩在手機上另加 1.2～1.4 s。各批數字見 `docs/DECISIONS.md`「加速第五批」。

## 參數

`NightReadParams` 是一個 data class，本體 132 個參數，外加六組巢狀：任意角度格溝 `sep: SeparatorParams`（57）、出血格過濾 `bleed: BleedParams`（28）、漏泡封縫 `bubbleSeal: BubbleSealParams`（6）、「更多」新規則 A2 `more: MoreRuleParams`（19）、人物外灰圈收細 `ring: RingParams`（23）、「更多」背景物件規則 `obj: ObjectRuleParams`（72，裡面再巢狀效果線 `fx: EffectLineParams`，42），合計 379 個。預設值就是定案值。巢狀是不得已：平鋪進來建構子會超過 JVM 的 255 個參數槽（`Double` 佔兩槽），類別載入就 `ClassFormatError`。逐項說明見 [`docs/PARAMETERS_zh.md`](../docs/PARAMETERS_zh.md)，那份涵蓋整條管線；沒進 `NightReadParams` 的在模組外面——偵測遮罩的二值化門檻、只用於回報的白面積統計門檻、偽泡生長的參照邊（Kotlin 版固定取長邊），以及純研究開關 `BUBBLE_REQUIRE_CLEAN`。

改它等於改演算法，不是調風格。門檻之間是連動的：分區方案建立在白元件的判斷上，動了前面一段的值，後面每一段都會跟著變。

設計成給呼叫端設的有兩組。一是輸出亮度（`bg`、`ink`、`edgeInk`、`strokeObjV`、`dimCeil`、`glowCap`；見 `docs/PARAMETERS_zh.md`「輸出位準」）：Yakuyomi 的亮度預設與滑桿就是設在 base `NightReadParams` 上。二是**背景填黑檔位**（多少白該黑；`docs/PARAMETERS_zh.md`「背景填黑三檔」）：`stickerMode`（`StickerMode.ALL`／`SIMPLE`／`PLAIN`）、`stickerRoughMax`、`stickerSimpleMinFrac`、`stickerPlainRingR`、`stickerPlainArtMax`、`stickerPlainFrameDil`、`stickerPlainFaintMax`、`stickerPlainFaintHalo`、`pseudoBubbles`、`harmonize`。預設（`ALL`、兩個開關都開）＝研究端完整管線，fixture 與守護框基線釘在這上面；三檔是呼叫端傳進來的參數組：

| 檔 | `NightReadParams(...)` |
|---|---|
| L1 | `stickerMode = StickerMode.PLAIN, pseudoBubbles = false, harmonize = false` |
| L2 | `stickerMode = StickerMode.SIMPLE, stickerRoughMax = 10.0, stickerSimpleMinFrac = 0.005, pseudoBubbles = false, harmonize = false` |
| L3 | `stickerMode = StickerMode.SIMPLE, stickerRoughMax = 20.0, stickerSimpleMinFrac = 0.0, more = MoreRuleParams(enabled = true), pseudoBubbles = false, harmonize = false` |

`NightTier.L1/L2/L3.apply(base)` 產生的就是這三組。產品出兩檔：「標準」＝L2、「更多」＝L3。L3 含「更多」新規則 A2（`MoreRuleParams`；
`docs/PARAMETERS_zh.md`「「更多」新規則 A2」），只在舊 L3 上加元件；舊 L3＝同一列但 `more` 留預設（關）。

`TierParityTest` 拿三張 fixture 頁跑 L1、L2、舊 L3 與更多（demo02 只跑更多），對 `fixtures/baseline/tiers/` 比；`MoreRuleParityTest`
逐元件比 A2 的特徵與判定；`BubbleLeakParityTest` 用 demo04 守漏泡判準（`bubbleLeak`：泡遮罩與研究端逐像素相同）；`RingParityTest`
守人物外灰圈收細（`ring`／`Ring.kt`，研究端 `nightread_ring.py`）：六頁的頁面級證據與 demo01 的生長＋收尾都與研究端逐像素相同；
`BgObjectsParityTest` 守「更多」背景物件規則（`obj`／`BgObjects.kt`，研究端 `nightread_obj.py`）：整數高斯、Canny、中值、chamfer、
二次曲面殘差這些原語、效果線（`EffectLines.kt`，研究端 `nightread_fx.py`）的細化與直分支，與 ch34_010、ch34_015 兩頁的整頁量測
（含閃光、效果墨、地盤、成員線）、否決、亮背景區塗黑、效果線區都與研究端逐像素（逐位元）相同；合成頁 syn_v4（帶去字遮罩）守規則
版本 4 的四項（補縫、手寫字、淡線外圈貼線形與淡小記號、短截與兩條淡線之間、去字區旁的小塊）。

人物外灰圈收細（`ring`，預設開、兩檔都套；研究端 `NIGHTREAD_RING`）：只在有畫出來的輪廓線把背景跟人物隔開的地方，讓已經塗黑的
背景長到輪廓線，其餘維持原本那圈灰。規則與數字見 `docs/PARAMETERS_zh.md`「人物外灰圈收細」與 `docs/DECISIONS.md`。

「更多」背景物件規則（`obj`，預設開、只在開了 `more` 的檔生效＝產品「更多」；研究端 `NIGHTREAD_OBJ`）：「更多」的背景塗黑看有沒有
物件——貼紙多塗、夾在物件之間的白還原成「標準」的樣子，無物件的亮背景（白或淺色調）塗黑。規則版本 4（開關 `seam`、`textStroke`、
`pfShape`、`inpaintIslands`）：灰虛線補黑、沒有字框但像粗墨筆畫的手寫字畫亮、一條淡線只留 3 像素外圈（短截與兩條淡線之間照舊）、譯文去字遮罩
（`NightReadInput.inpaintMask`）旁的小塊不塗。「標準」不受影響。規則與數字見
`docs/PARAMETERS_zh.md`「「更多」背景物件規則」與 `docs/DECISIONS.md`。

另有兩個開關，預設都開、三檔也一律開：`separators`（任意角度格溝／頁邊，`Separators.kt`；研究端 `NIGHTREAD_SEP`）與
`bleedFilter`（出血格過濾，`Bleed.kt`；研究端 `NIGHTREAD_BLEED`）。格溝在貼紙層之前用留白待遇塗，而且壓過人物遮罩（圖層：
字 > 對話框 > 格溝／頁邊 > 人物 > 背景）；出血格過濾在兩條留白路徑的線稿密度否決之後，把其實是出血格畫面的塊拿掉。兩個都關＝
加入前的輸出。見 `docs/PARAMETERS_zh.md`「任意角度格溝」「出血格過濾」與 `docs/DECISIONS.md`。

漏泡封縫（`bubbleSeal`，半徑 `r = 1`；研究端 `NIGHTREAD_BUBBLE_SEAL_R`）三檔也一律開：泡只因框上 1–2 px 的縫與背景連在一起而被拒時，
把縫封起來、再走一次泡路徑，救回的只進泡的重繪層。`BubbleSealParams(r = 0)`＝加入前的輸出。見 `docs/PARAMETERS_zh.md`「漏泡封縫」。

## 規則版本

`NightRead.RULES_VERSION`（現在是 4）：同一頁、同一組亮度，產品兩檔的成品只要會變就加 1。呼叫端把它跟每頁夜讀檔一起記下，版本較舊的頁就是舊版、可重新產生。`RulesVersionGuardTest` 每個版本記一個 SHA-256 摘要，涵蓋產品兩檔在一組 fixture 頁上的成品（版本 4 起八頁，其中兩頁是合成頁），輸出變了卻沒加版本就失敗；已交出的版本凍結。歷史與規則見 `docs/DECISIONS.md`「規則版本」。

## 測試

`./gradlew :nightread:testDebugUnitTest` 跑全部 JVM 測試：原語與整頁對 Python fixture 的 parity、三檔基線、規則版本守門、頁內並行、熱點計時。`-PtestGroup=fast|guard|parallel|profile` 只跑一組；CI（`.github/workflows/ci.yml`）在 push 到 `main` 與每個 PR 時四組平行跑。測試 JVM 要 1 GB heap（設在 `build.gradle.kts`）。

## 紅線

**絕不塗到臉、手、白衣、白髮。** 唯一可接受的失敗是「不夠暗」。驗收靠 732 個人工標註的前景框；在 688 框的 fixture 子集上，目前（python 參考實作）完整管線 11 框違規、產品「標準」（L2）10 框、「更多」（L3＋新規則 A2）10 框，這個 Kotlin 函式庫是 11／9／9（ch34_006 有一框其實畫在斜格溝上、另有三框的框邊伸進對話泡，四框都已修正標註；人物外灰圈收細之後，框邊伸進背景的 19 框重畫成 47 個貼身框，見 `docs/DECISIONS.md`）。

要達到這條線必須有人物語意遮罩：純幾何最好也只能到 37 框（當時的 665 框），而且要付 14 個百分點的亮區代價。

## Python 端

`research/nightread.py` 是同一條管線的 Python 版，也是 Kotlin 移植的規格本。進入點是 `run_page(page_path, outdir=OUT_DEFAULT, col_w=1000, regions=None, seg=None, diag=None, inpaint=None)`：`regions` 與 `seg` 傳進去就跳過偵測，形狀與 `NightReadInput` 相同；譯後頁另傳 `inpaint=`（與頁同尺寸的布林），對應 `inpaintMask`。

研究腳本會 import yakuyomi-engine 的 parity 工具做偵測，靠 `YAKU_ENGINE_CLONE` 環境變數指路。那是研究腳本的便利，不是管線本身的要求。

## 授權

GPL-3.0，與 Yakuyomi 其餘部分相同。重建演算法本身是原創。研究管線透過 yakuyomi-engine 使用 m-i-t 的 DBNet（GPL-3.0），人物遮罩模型各有授權，見上。
