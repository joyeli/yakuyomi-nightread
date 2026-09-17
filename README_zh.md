# yakuyomi-nightread

漫畫夜讀模式——變暗的是**頁面本身**，不只是介面。

[English](README.md) ｜ 中文

市面上的 reader 夜間只做兩件事：把介面塗黑、讓頁面繼續刺眼地白，或是把整張圖反相、毀掉畫面。
兩者都沒碰頁面內容。這個專案碰：它**重建**頁面。對話框變成深底亮字，空白背景填黑、人物用亮邊
抬出來，其餘部分經過色調映射，讓黑線維持黑。

它是 [yakuyomi-engine](https://github.com/joyeli/yakuyomi-engine)（翻譯引擎）的姊妹專案，
只吃引擎的文字偵測輸出。

![重建的六個階段](docs/img/showcase.webp)

**現況：桌面研究。** 還沒有東西上機。管線已經收斂——1351 行 Python、665 個守護框裡 18 框違規
——剩下的是 Kotlin 移植。所有決策與當前數字見 [`docs/DECISIONS.md`](docs/DECISIONS.md)。

## 為什麼是重繪，不是濾鏡

漫畫的紙白參與構圖：臉的亮部、金屬反光、留白，全用同一個白畫。任何「白變暗」的全域映射都會翻掉
「紙白 vs 網點」的相對關係，除非連調子一起翻，那就是負片。對話框內部是唯一反相完美的地方，
而濾鏡永遠拿不到它——泡的白和紙的白是同一個像素值。

所以必須先把頁面拆成語意分區，再逐區重建。

![管線各階段](docs/img/pipeline.webp)

完整說明見 [`docs/ARCHITECTURE_zh.md`](docs/ARCHITECTURE_zh.md)，每個參數見
[`docs/PARAMETERS_zh.md`](docs/PARAMETERS_zh.md)，11 張測試頁的完整對照見
[`docs/SHOWCASE_zh.md`](docs/SHOWCASE_zh.md)。

## 在產品裡怎麼用

夜讀接在翻譯之後，吃的是已經貼好譯文的成品頁；在翻譯前算，夜讀看到的是原文，譯文貼上去就成了
黑字壓在黑底上。因此 OCR、翻譯、去字、排版這些翻譯的大宗成本全部省掉，成品頁只要重跑偵測，
再加一份夜讀專屬的人物遮罩（只用量化的 YOLO11-seg 是 10.5 MB，加上 CartoonSegmentation 則是
238 MB）。偵測省不掉是量過才定的：連偵測一起省、改用排版器自己畫的精確筆畫，指標反而最好
（字 239.3、對比 +223.2），卻被看圖否決。精確筆畫當不了氣泡核心
填色的種子（某顆泡的偵測文字區覆蓋 90%，精確筆畫只有 32% 是黑），泡會填不滿、右下角留一塊灰；
裝飾性的手寫字又完全不在任何文字區內，排版器只知道自己畫了什麼，看不到沒被翻譯的字，整顆泡會
漏掉。夜讀版與正常版都是事先算好的圖，切換只是換檔案指標，零計算。三種配方的完整量測與定案的
產品形狀見 [`docs/ARCHITECTURE_zh.md`](docs/ARCHITECTURE_zh.md)。

## 紅線

**絕不塗到臉、手、白衣、白髮。** 唯一可接受的失敗是「不夠暗」。這條線用量的，不用看的：
`nightread_guard.py` 檢查 704 個人工標註的前景框，任何輸出只要把某框內原本是白的像素塗黑
超過 15%，就算違規。

這套測試存在的理由是**目視驗證被證明不可靠**——看過裁圖說「完整」的區域，逐框量測後全被推翻。

要達到紅線必須有人物語意遮罩。純幾何最好也只能到 37 框違規，還要付 14 個百分點的亮區代價；
有遮罩之後同一條管線是 18 框。

## 佈局

| 路徑 | 內容 |
|---|---|
| `research/nightread.py` | 整條管線，一次一頁。所有參數集中在檔頭一個區塊。 |
| `research/charmask.py` | 人物遮罩探針（CartoonSegmentation、YOLO11-seg、兩者聯集）。它的輸出是管線的**必要輸入**。 |
| `research/nightread_batch.py` | 跑 11 張 fixture、印亮區表。 |
| `research/nightread_guard.py` + `nightread_guard.json` | 紅線測試：704 個人工標註前景框。 |
| `research/nightread_translated.py` | 譯文頁的素材共用驗證：對翻譯引擎的成品頁跑夜讀，比較三種偵測素材共用配方。 |
| `research/make_showcase.py` | 六階段成果展示圖。 |
| `research/pipeline_diagram.py` | 本頁上方那張管線階段圖。 |
| `fixtures/pages/` | 11 張測試頁。`fixtures/baseline/` 是回歸用的參考輸出。 |
| `nightread/` | 未來的 Kotlin library（Android library，**不依賴 `android.graphics`**，這樣 JVM 測試才能對 Python fixture 逐位元比對）。目前只有 `Cv.kt` 的 API 契約。 |
| `docs/` | 架構、參數表、決策記錄。 |

## 執行

研究腳本需要引擎的 parity 工具做文字偵測（DBNet checkpoint 載入器與 m-i-t 的 grouping 規格）。
把 `YAKU_ENGINE_CLONE` 指向 yakuyomi-engine 的 checkout（預設 `/mnt/d/Gits/Yakuyomi`），
用它 `parity/` 的同一套 Python 環境即可。

```bash
cd research

# 1. 人物遮罩（必要輸入，沒有它管線會直接報錯）
python3 charmask.py combine -o out/char_combine

# 2. 管線
NIGHTREAD_CHARMASK=$PWD/out/char_combine python3 nightread_batch.py -o out/run

# 3. 紅線測試
python3 nightread_guard.py out/run
```

`NIGHTREAD_CHARMASK` 是唯一的環境變數。其餘參數都在檔案裡改，這樣每次跑都能從原始碼重現。

## 授權

GPL-3.0，與 Yakuyomi 其餘部分相同。研究管線透過 yakuyomi-engine 使用 m-i-t 的 DBNet 偵測器
（GPL-3.0）；人物遮罩模型各有授權（CartoonSegmentation 是 MIT，YOLO11-seg 是 AGPL-3.0）；
重建演算法本身是原創。
