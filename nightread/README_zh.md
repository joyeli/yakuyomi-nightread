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

只留一個模組是刻意的。`:nightread` 除了 `kotlin.math` 什麼都沒 import，連 `android.graphics` 都不碰：這樣管線才跑得起 JVM 單元測試（拿 Python fixture 對比就是這麼做的），也讓它可以被任何 JVM 專案直接拿走。人物遮罩在別處算：Yakuyomi 是在 [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine) 用 NCNN 跑那兩顆分割模型（`CsegSegmenter` 與 `YoloSegSegmenter`，見[人物遮罩模型](#人物遮罩模型)），這個 repo 只吃算好的遮罩。這裡原本有個 `:nightread-ort` 模組用 ONNX Runtime 跑同兩顆模型，搬到 NCNN 後就拿掉了。

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
val result: NightReadResult = NightRead.render(NightReadInput(gray, seg, regions, charMask, chroma))

// 6. 暗色頁，每像素 0..255，尺寸與輸入相同。
val dark = result.out
val outPixels = IntArray(w * h) {
    val v = dark.data[it]
    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
}
val nightBitmap = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
```

進入點只有一個：

```kotlin
fun render(
    input: NightReadInput,
    p: NightReadParams = NightReadParams(),
    debug: NightReadDebug? = null,
): NightReadResult
```

`NightReadDebug` 是 `(stage: String, value: Int) -> Unit` 的 typealias。這些型別都在 `li.joye.yakuyomi.nightread`。

## 輸入

`NightReadInput` 四樣必要素材加一個選用欄位：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `gray` | `Gray(w, h)` | 頁面灰階 0..255 |
| `seg` | `Mask(w, h)` | 文字遮罩（見格式要求第 2 條：要區域，不是精確筆畫） |
| `regions` | `List<TextRegion>` | 文字區的 bbox（`x0, y0, x1, y1`，原圖像素） |
| `charMask` | `Mask(w, h)` | 人物遮罩，模型原輸出，**必要** |
| `chroma` | `Gray(w, h)?` | 每像素彩度（max−min 通道），彩頁判定用；純灰階頁可傳 `null` |

### 文字偵測不在這個 repo 裡

夜讀不含偵測器。`seg` 與 `regions` 得自己準備。我們自己用的是 manga-image-translator 的 DBNet，透過 yakuyomi-engine 取得，但管線本身不依賴它：任何能輸出「文字區域遮罩 + 文字區 bbox」的來源都行，只要符合下面的格式要求。

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
// yakuyomi-engine；兩個都實作 CharSegmenter { fun segment(page: Bitmap): BooleanArray }
val yolo = YoloSegSegmenter(yoloParamPath, yoloBinPath)
val cseg = CsegSegmenter(csegParamPath, csegBinPath)
val a = yolo.segment(pageBitmap)
val b = cseg.segment(pageBitmap)
val charMask = Mask(w, h, BooleanArray(w * h) { a[it] || b[it] })
```

`segment` 吃頁面 `Bitmap`，回傳與頁面同尺寸的布林陣列，true＝人物；定案配方是兩顆取聯集。每個分割器各持一個 NCNN net，都是 `AutoCloseable`。

| 模型 | 檔案 | 大小（fp16） | 角色 | 授權 |
|---|---|---|---|---|
| YOLO11-seg | `manga_seg_s.ncnn.param` + `.bin` | 20.4 MB | 定案配方的基底，也是單獨跑時最省的一顆 | **AGPL-3.0**（Ultralytics） |
| CartoonSegmentation（RTMDet-Ins） | `cartoonseg.ncnn.param` + `.bin` | 126 MB | 可選，加了更準 | MIT |

量測定了三件事：

- **只用 YOLO11-seg 也能跑**，代價是守護框違規從 18 升到 27。
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

## 生命週期與執行緒

- `NightRead` 是 object 且不持有狀態。除錯回呼是傳入參數（`NightReadDebug`）而不是全域欄位，所以 `render` 可以並發呼叫。
- 產生 `charMask` 的分割器不歸這個模組管。在 yakuyomi-engine 裡它們各持一個 NCNN net、都是 `AutoCloseable`：建一次、跨頁重複用、用完 close。

## 參數

`NightReadParams` 是一個 data class，裝著這個模組的 91 個參數，預設值就是定案值。逐項說明見 [`docs/PARAMETERS_zh.md`](../docs/PARAMETERS_zh.md)，那份涵蓋整條管線的 94 個參數；沒進 `NightReadParams` 的三個在模組外面——偵測遮罩的二值化門檻、只用於回報的白面積統計門檻，以及偽泡生長的參照邊，Kotlin 版固定取長邊。

改它等於改演算法，不是調風格。門檻之間是連動的：分區方案建立在白元件的判斷上，動了前面一段的值，後面每一段都會跟著變。

## 紅線

**絕不塗到臉、手、白衣、白髮。** 唯一可接受的失敗是「不夠暗」。驗收靠 704 個人工標註的前景框，目前 18 框違規。

要達到這條線必須有人物語意遮罩：純幾何最好也只能到 37 框，而且要付 14 個百分點的亮區代價。

## Python 端

`research/nightread.py` 是同一條管線的 Python 版，也是 Kotlin 移植的規格本。進入點是 `run_page(page_path, outdir=OUT_DEFAULT, col_w=1000, regions=None, seg=None)`：`regions` 與 `seg` 傳進去就跳過偵測，形狀與 `NightReadInput` 相同。

研究腳本會 import yakuyomi-engine 的 parity 工具做偵測，靠 `YAKU_ENGINE_CLONE` 環境變數指路。那是研究腳本的便利，不是管線本身的要求。

## 授權

GPL-3.0，與 Yakuyomi 其餘部分相同。重建演算法本身是原創。研究管線透過 yakuyomi-engine 使用 m-i-t 的 DBNet（GPL-3.0），人物遮罩模型各有授權，見上。
