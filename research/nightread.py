#!/usr/bin/env python3
"""nightread.py — 夜讀重繪：把白底漫畫頁重建成適合夜間閱讀的暗色頁。

不是濾鏡，也不是反相。全域函式在這題上無解：漫畫的**紙白參與構圖**（臉的亮部、
反光、留白都用同一個紙白畫），任何「白→暗」的全域映射都會翻掉「紙白 vs 網點」的
相對關係。唯一的辦法是先認出頁面的語意分區，再逐區重建。

一頁的資料流（`run_page`）：

    頁圖
      ├ 偵測      DBNet → 文字行四邊形 + 逐像素筆畫遮罩 + 區域合併
      ├ 人物遮罩  charmask.py 的輸出（cseg ∪ yoloseg）→ 貼墨收邊 → 中值平滑
      ├ 頁型      長直格框線密度 → 有框頁／無框頁
      ├ 白元件    整頁白連通元件一次算完 → 留白／格內白／其餘
      ├ 氣泡      白元件 ∩ 文字區 → 面積與局部性守門 → 文字種子核心填色
      ├ 格溝      任意角度框線 → 兩線夾白＝分鏡溝、頁緣到框線＝頁邊（nightread_sep.py）
      └ 合成      場景曲線 → 留白填深（出血格過濾，nightread_bleed.py）→ 格溝／頁邊
                  → 貼紙式背景 →（只有「更多」）背景物件規則（nightread_obj.py）→ 氣泡 → 偽泡 → 人頭一致化
                  → 剩餘填色 → 灰圈收細（nightread_ring.py）→ 人物還原（跳過泡與格溝）
    暗色頁

分區的待遇：

    留白（頁邊距／格溝）  填 BG、邊界描亮（任意角度的溝／頁邊另由 nightread_sep.py 補上）
    純白背景             填 BG、前景白描邊抬出立體感
    氣泡內部             填 BG、文字筆畫畫亮到 INK（原圖墨度當 alpha ⇒ 天然抗鋸齒）
    人物                 場景曲線壓暗，任何填色都要讓開
    其餘畫面             場景曲線壓暗（線性、保序）

兩條紅線：
  1. **絕不塗錯**。臉、手、皮膚、白衣、白髮絕不可以被填黑。失敗方向只准「不夠暗」。
     驗收靠 `nightread_guard.py` 的 732 個人工標註框，目視不算數。
  2. **畫面絕不反相**。畫面區只允許單調映射，墨線永遠比紙面暗。

圖層優先權（決定衝突時誰贏）：**字 > 對話框 > 格溝／頁邊 > 人物 > 背景**。格溝是畫面的外面，人物不可能在那裡
（人物遮罩收邊後越過框線長進溝的灰帶才是錯的；見 docs/DECISIONS.md「任意角度格溝＋出血格過濾＋格溝壓過人物」）。

人物語意遮罩是**必要輸入**：守護框證明沒有它紅線不可達（純幾何最好也有 37 框違規，
且要付 14 個百分點的亮區代價）。先跑 `charmask.py`，再把輸出夾給 `NIGHTREAD_CHARMASK`。

偵測路徑＝`export_dbnet_ncnn.build_model`（m-i-t TextDetection @ .upstream-ref，
detect-20241225.ckpt）torch 前向 ＋ m-i-t `SegDetectorRepresenter` 後處理 ＋
`mit_grouping` 兩階段區域合併，與引擎同款前處理（長邊 1024、pad 到 256 倍數、/127.5-1）。

用法：
    NIGHTREAD_CHARMASK=<遮罩夾> python3 nightread.py <頁圖> [-o 輸出夾]
背景填黑三檔（產品檔位，見參數區「背景填黑三檔」與 docs/DECISIONS.md；不設＝完整管線＝library 預設、fixture 基線）：
    L1  NIGHTREAD_STICKER_MODE=plain  NIGHTREAD_PB=0 NIGHTREAD_HM=0
    L2  NIGHTREAD_STICKER_MODE=simple NIGHTREAD_STICKER_ROUGH=10 NIGHTREAD_STICKER_MINFRAC=0.005 NIGHTREAD_PB=0 NIGHTREAD_HM=0
    L3  NIGHTREAD_STICKER_MODE=simple NIGHTREAD_STICKER_ROUGH=20 NIGHTREAD_STICKER_MINFRAC=0     NIGHTREAD_PB=0 NIGHTREAD_HM=0
    更多 L3 的環境變數 ＋ NIGHTREAD_MORE=1（產品「更多」＝L3＋新規則 A2，2026-10-02；不設＝舊 L3，研究對照用；門檻 NIGHTREAD_MORE_*）
    產品兩檔＝「標準」L2、「更多」L3＋A2＋背景物件規則（Kotlin NightTier.L3 已含）。
「更多」背景物件規則（規則版本 3，預設開、只在 NIGHTREAD_MORE=1 時有作用；0＝版本 2 的「更多」，逐像素相同）：
    NIGHTREAD_OBJ=0    整個關掉（常數在 nightread_obj.py 檔頭；見 docs/DECISIONS.md「「更多」背景物件規則」）
    NIGHTREAD_OBJ_VETO=0 ／ NIGHTREAD_OBJ_LT=0   只關否決／只關亮背景區塗黑（消融用）
    NIGHTREAD_OBJ_FXA=0 ／ NIGHTREAD_OBJ_FXC=0   只關效果線（集中線不算物件，nightread_fx.py）／只關閃光（兩個都關＝效果線之前的版本 3）
格溝與出血格過濾各有開關（預設都開；兩個都關＝加入前 d3cfa92 的輸出，逐像素相同）：
    NIGHTREAD_SEP=0     不偵測任意角度格溝／頁邊（nightread_sep.py）
    NIGHTREAD_BLEED=0   不做出血格過濾（nightread_bleed.py）
漏泡封縫（預設開；0＝加入前 f1c2edd 的輸出，逐像素相同）：
    NIGHTREAD_BUBBLE_SEAL_R=0   不封泡框上的極窄縫（BUBBLE_SEAL_*；見 docs/DECISIONS.md「漏泡封縫」）
人物外灰圈收細（預設開，五檔都套；0＝加入前的輸出，逐像素相同）：
    NIGHTREAD_RING=0   人物還原前不認領灰圈（RING_*；見 nightread_ring.py 與 docs/DECISIONS.md「人物外灰圈收細」）
    NIGHTREAD_RING_SEEDCONN=0   開運算後不做「只留與種子相連」（＝研究版 thin_ring/compromise 的 zhe.py）
輸出（皆帶頁名前綴）：_final.png ／ _regions.json ／ _seg.png ／ _bubble.png ／
_gutter.png ／ _cmp.png（三聯：原圖｜成品｜遮罩視覺化）。批次見 nightread_batch.py。

每個參數的由來、以及所有被實測否決的替代方案，見 ../docs/DECISIONS.md。
"""
import argparse
import importlib.util
import json
import os
import sys
import time

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths                                                      # noqa: E402  ← 先：把 engine parity 加進 sys.path
import export_dbnet_ncnn as ex                                    # noqa: E402  （來自 yakuyomi-engine/parity）
from mit_grouping import Quadrilateral, merge_bboxes_text_region  # noqa: E402  （來自 yakuyomi-engine/parity）
import nightread_bleed                                            # noqa: E402  出血格過濾
import nightread_obj                                              # noqa: E402  「更多」背景物件規則
import nightread_ring                                             # noqa: E402  人物外灰圈收細
import nightread_sep                                              # noqa: E402  任意角度格溝／頁邊

OUT_DEFAULT = os.path.join(paths.OUT, "nightread")

# ── 參數（唯一的旋鈕面板；每個值的由來與被否決的替代方案見 docs/DECISIONS.md）──────
# 輸出位準
BG = 16                 # 深底：留白／氣泡內部／背景填色都用它
INK = 240               # 亮字：氣泡內的文字筆畫
EDGE_INK = int(os.environ.get("NIGHTREAD_EDGE_INK", str(INK)))   # 描亮邊線（留白邊界／泡框／泡外圈）上限；預設＝INK
STROKE = 1              # 邊界描亮半徑（泡框、填色區外緣）。3 會在泡外留 6px 白環
STROKE_OBJ_V = 220      # 前景描邊亮度（略低於 INK，與字區分）
SCENE_FLOOR = 8         # 場景曲線：黑 → 8（極暗保護，OLED 黑碎的最小抬升）
DIM_CEIL = 140          # 場景曲線：紙白 → 140（線永遠比紙暗 ⇒ 不反相）
GLOW_STRENGTH = 55      # 自適應墨線增亮強度
GLOW_CAP = 112          # 增亮上限（< DIM_CEIL）

# 輸入與白元件
SEG_TH = 0.12           # DBNet 筆畫遮罩二值化閾（同引擎 segThreshold）
WHITE_TH = 235          # 「白」的灰階下限；整套分區都建立在這份白連通元件上
INK_DARK_TH = 128       # 「墨」的灰階上限（洞內含墨判定）
BUBBLE_PAD = 40         # 文字區 bbox 外擴的白元件搜尋窗
GUTTER_MIN_AREA_FRAC = 0.0006   # 留白元件最小整頁佔比
WHITE_MEASURE_TH = 200  # 白面積統計閾（只用於回報，不進演算法）

# 紙白正規化（色紙／掃描頁）
PAPER_PEAK_LO = 200         # 估紙白峰時只看這之上的亮部
PAPER_NORM_MIN = 245        # 峰低於此才拉亮（乾淨白紙原樣通過）
PAPER_NORM_CHROMA_MAX = 8.0 # 峰值區彩度上限：有彩＝淡彩畫底不是紙，不拉

# 頁型判別（長直格框線密度，px/千像素）
FRAME_LINE_L_DIV = 5    # 線長 = min(W,H)//此（至少 60px）
FRAME_DARK_TH = 100     # 框線「暗」的灰階上限
FRAME_MIN_EACH = 1.0    # 橫、直線各自的下限
FRAME_MIN_SUM = 4.5     # 合計下限；不到＝無框頁，背景只壓暗不填深
FRAMELESS_MARGIN_DEPTH = 0.12   # 無框頁只填深入 ≤ 短邊×此 的真頁邊帶

# 留白 vs 格內白
CORE_R = 26             # 厚芯：距離變換 > 此（真格溝半寬遠小於此）
DEEP_EDGE_FRAC = 0.07   # 頁邊距帶寬 = max(64, 此×min(W,H))
IN_PANEL_CORE_FRAC = 0.15   # 規則A：厚芯佔元件 ≥ 此
IN_PANEL_CORE_DEEP = 0.25   #        且厚芯深入頁內 ≥ 此 ⇒ 格內白
DEEP_INK_DEEP = 0.5     # 規則B：厚芯深入 ≥ 此
DEEP_INK_RATIO = 0.02   #        且小洞內墨/面積 ≥ 此 ⇒ 白包畫，改判畫面
HOLE_MAX_FRAC = 0.01    # 「小洞」整頁佔比上限（大洞＝整格，不算包線稿）
SAFE_GUTTER_DEPTH = 0.12    # 有框頁的留白只填深入 ≤ 短邊×此 的部分
GFC_DILATE = 4          # 格框線切割：框線膨脹半徑（補線稿造成的細缺口）
GFC_CLOSE_FRAC = 0.30   #   沿線方向閉合長度（佔短邊）：橋接被出血人物打斷的框線

# 氣泡
BUBBLE_COMP_MAX_FRAC = 0.07 # 泡元件整頁佔比上限
BUBBLE_LOCAL_K = 4.0        # 泡元件面積 ≤ 此×文字搜尋窗（局部性）
BUBBLE_CORE_MIN_FRAC = 0.003    # ≥ 此頁佔比走文字種子核心填色；小的整顆填
BUBBLE_NECK_R = 8           # 泡的切頸半徑：泡框缺口／下巴縫都是窄頸
SAFE_BUBBLE_RATIO = 6.0     # 泡核心面積 ≤ 此×字框長邊²（擋「字壓臉」被當成泡）；2.5→6.0 見 DECISIONS「譯後頁的泡」
BUBBLE_CLEAN_WINS = 0.005   # 內部非字墨 < 此的泡＝乾淨容器 ⇒ 整顆塗黑、人物不扣
BUBBLE_REQUIRE_CLEAN = os.environ.get("NIGHTREAD_REQUIRE_CLEAN", "0") == "1"   # 研究開關：收泡前就要求乾淨容器（非字墨 < BUBBLE_CLEAN_WINS）；預設關、三檔也不用（守護框零影響）
BUBBLE_CLEAN_TEXT_MAX = 0.8 # 但文字佔比 > 此＝那不是泡（是被誤判的白髮／白手）
# 泡內淺條（2026-10-02，使用者拍板 e）：泡靠人物那側多一條場景灰＝人物遮罩的收邊／平滑沿泡內紙白長進去、被修剪扣掉。
BUBBLE_CLEAN_INK_HOLES = os.environ.get("NIGHTREAD_CLEAN_INK_HOLES", "1") == "1"   # d：乾淨泡判準的洞只算非紙白（< WHITE_TH）；字欄間沒收進泡的紙白小縫不再算「泡裡有別的東西」。0＝關＝舊行為
BUBBLE_GUARD_RAW = os.environ.get("NIGHTREAD_GUARD_RAW", "1") == "1"   # c：仍判不乾淨、但「字確認」的泡，修剪只讓開人物模型原輸出（收邊／平滑長出來的安全邊被泡蓋過）。0＝關＝舊行為
BUBBLE_CONFIRM_TEXT_IN = 0.5    # 字確認：至少一個字框（完整 bbox 面積）有 ≥ 此落在填洞後的泡內（另要字佔比 ≤ BUBBLE_CLEAN_TEXT_MAX）
# 漏泡判準（2026-10-02，使用者拍板）：乾淨泡整顆塗黑的前提是「泡畫在人物之上」。沒有框線的泡，泡的白會直接連到人物身上的白
# （demo04 白髮高光、白襯衫），整顆塗就塗到人物。乾淨泡 ∩ 人物原輸出的連通塊，如果它貼著的泡外緣沒有框線，這一塊還給人物。
BUBBLE_LEAK = os.environ.get("NIGHTREAD_ELEAK", "1") == "1"            # 0＝關＝只有修法 e
BUBBLE_LEAK_MODE = os.environ.get("NIGHTREAD_ELEAK_MODE", "lc")        # lc＝局部亮度差（產品；耐模糊）｜ink＝環上墨佔比（研究對照：模糊 σ ≥ 1.5 會誤觸發）
BUBBLE_LEAK_RING = int(os.environ.get("NIGHTREAD_ELEAK_RING", "3"))    # 外緣環寬（px，橢圓核 2r+1）
BUBBLE_LEAK_RANGE = int(os.environ.get("NIGHTREAD_ELEAK_LC_RANGE", "60"))      # lc：環上一個像素算「有線」＝9×9 窗內最亮減最暗 ≥ 此
BUBBLE_LEAK_WIN = 9                                                             # lc：量局部亮度差的方窗邊長
BUBBLE_LEAK_EDGE_MAX = float(os.environ.get("NIGHTREAD_ELEAK_LC_MAX", "0.85")) # lc：環上「有線」像素佔比 < 此＝這段泡緣沒有框線＝漏
BUBBLE_LEAK_INK_MAX = float(os.environ.get("NIGHTREAD_ELEAK_INK", "0.10"))     # ink：環上墨（< INK_DARK_TH）佔比 < 此＝漏
BUBBLE_LEAK_RING_MIN = int(os.environ.get("NIGHTREAD_ELEAK_RINGMIN", "100"))   # 環至少這麼多 px 才判（更少＝幾乎被泡包住）
BUBBLE_LEAK_MIN_AREA = int(os.environ.get("NIGHTREAD_ELEAK_MINAREA", "100"))   # 乾淨泡 ∩ 人物原輸出的連通塊至少這麼多 px 才看
BUBBLE_REST_NEAR = 20       # 泡元件的剩餘部分只在泡外此距離內填深（三檔皆同）
# 字壓背景閘（2026-09-27）：泡核心（cored 分支的 core）的「非字邊界」（core 的 1px 內邊界、扣掉外擴筆畫 segd_c）要貼著墨線——
# 真泡由自己的框線圍住 ⇒ 邊界幾乎全落在墨線 ≤ BUBBLE_OUTLINE_DIST px 內（19 頁 105 顆真泡：d=6 實測 ≥ 0.977，d=4 時 ≥ 0.959）；
# 字直接寫在天空／牆面上的白，邊界是網點灰／雲線／別人的線稿 ⇒ 比例低（d=6）：c362_010「我想想」的天空白塊 0.076、
# c371_008 建築 0.257、c362_013 天空 0.314、ch34_015 手寫字壓格內背景 0.614、demo01 臉 0.748。0.85 落在兩群中間，
# 最窄處 demo01 距門檻 0.10；
# 守護框標準 18/665、三檔 L1/L2/L3 12/12/16 不變。填洞後的 rough／solidity／ellipse-IoU 都試過分不開（字洞常與外界相通）。
BUBBLE_OUTLINE_MIN = 0.85   # 非字邊界中「距墨線 ≤ BUBBLE_OUTLINE_DIST px」的比例下限；0＝關
BUBBLE_OUTLINE_DIST = 6     # 「貼墨」距離（px），絕對像素。4→6（2026-09-27 審查的解析度探針）：d=4 在頁圖放大 1.5–2× 就破（2× 時 74–79 顆
                            # 真泡跌破 0.85）；d=6 從 1× 到 2× 真泡全程 ≥ 0.94，字壓背景 1× 最高 0.748、放大後 ≤ 0.68，兩群仍分得開；
                            # 1× 的 fixture 11 頁輸出逐像素不變
BUBBLE_OUTLINE_MIN_PX = 100 # 非字邊界像素少於此不判（樣本不足 ⇒ 維持原行為）
BUBBLE_OUTLINE_PAD = 8      # 量測窗外擴（距離變換要看得到元件 bbox 外的墨）
# 漏泡封縫（v3，2026-09-30）：字碰到的白元件因「太大」或「列為留白／格內白」被拒時，只封**極窄**的縫再看字所在的白
# 會不會自成一塊；會的話，把那一塊當成新元件送進原本的泡路徑（所有閘照舊）。不長局部核心（v1／v2 被退回的做法）。
BUBBLE_SEAL_R = int(os.environ.get("NIGHTREAD_BUBBLE_SEAL_R", "1"))   # 封縫半徑；0＝關＝HEAD 行為。
                            # 封法＝元件的白以 (2R+1) 橢圓侵蝕（離墨 ≤R 的白拿掉）後重算連通；封得住「實際白寬 ≤ 2R」的縫，與框線粗細無關
                            # （閉運算在 2 px 細線上完全封不住，見 research/out/bubble_leak/v3/data/seal_synth.json、docs/DECISIONS.md「漏泡封縫」）。R=1 ⇒ ≤2 px 的縫；
                            # R=2 會把雙線框 4 px 夾縫整條封掉（驗證者的雙線框攻擊會變），所以取 1
BUBBLE_SEAL_CHAR_MAX = 0.25 # 封出來的泡 ∩ 人物遮罩（char_raw）佔比上限（同 v2 的人物關；真泡 ≤ 0.06、人物白 ≥ 0.99）
BUBBLE_SEAL_TEXT_IN = 0.5   # 字區筆畫（字框內 seg）落在封出來那塊（含洞）裡的比例下限：泡要「裝著」字；封出來的只是字旁的小口袋就不算
BUBBLE_SEAL_REST_MIN = 50   # 封完後元件在這塊以外必須還剩 ≥ 此 px 的「深白」（侵蝕後仍在的白）：真的切下了一塊，而不是只削掉邊
BUBBLE_SEAL_MAX_GAPS = 8    # 封掉的縫（切口群）個數上限：「泡框上一兩個極窄縫」才封。真泡 c362_005:3／c362_011:5 在 1× 與 20 個擾動
                            # （JPEG q60–95、縮放 0.9／1.1）是 2–5 個；demo02 說明框 c2236 的框線粗糙、四角全是 1 px 漏點，13–23 個
BUBBLE_SEAL_CUT_GEO = 3 * BUBBLE_NECK_R   # 每個縫到「泡身」（單元以 BUBBLE_NECK_R 開運算後含字框的寬闊塊）的測地距離上限（px，在單元∪縫內 8 連通走）：
                            # 縫要在泡自己的框上。真泡縫在泡身外 2–3 px（c362_005:3）、14–16 px（c362_011:5 泡尾尖）；驗證者的雙線框
                            # W_dbl_i12_o5b（內框缺 12 px、外框缺 5 px）在 R=1 會被兩框夾縫的斜向窄點（≤2 px）封住，那個縫在泡身外 36 px

# 偽泡（開口泡／字壓畫面）
PB_COV_MAX = 0.85       # 泡遮罩蓋率低於此的文字區才啟動偽泡
PB_NECK_R = 10          # 偽泡切頸
PB_GROW_FRAC = 0.6      # 生長上限 = 字框邊 × 此
PB_GROW_REF = "max"     # 用字框長邊（短邊會讓橫排標題字之間留白）
PB_AURA_R = 12          # 厚墨灰暈：距厚墨塊此距離內不填（臉旁髮團的保險）
PB_AURA_THICK = 6       # 「厚墨」的距離變換下限（細筆畫≤4px 不算）
PB_AURA_MIN_AREA = 800  # 厚墨塊最小面積

# 貼紙式背景（純白背景填黑＋前景白描邊）
STROKE_OBJ_FRAC = 0.0035    # 前景描邊半徑 = min(W,H)×此，clamp 到下兩行
STROKE_OBJ_MIN = 4
STROKE_OBJ_MAX = 7
STICKER_MIN_FRAC = 0.01     # 無框頁背景白元件的最小整頁佔比
FIG_NOISE_AREA = 40         # 前景小噪點：不描邊、併入背景
FIG_NOISE_CLOSE = 11        # 噪點「在背景內」的閉運算核
STICKER_FIG_MIN = 0.1       # 前景佔 bbox 下限（過低＝整格被當背景）
STICKER_FIG_MAX = 0.85      # 上限（過高＝根本沒分出背景）
STICKER_THIN_R = 4          # 白的細碎判定半徑
STICKER_THIN_MAX = 0.45     # 細碎佔比上限（整體細碎＝背景已破碎）
STICKER_CHROMA_MAX = 6.0    # 元件平均彩度上限：擋淡彩水彩底（彩頁不毀）
STICKER_EATEN_R = 5         # 「被吃前景白」＝細白（dist ≤ 此）…
STICKER_EATEN_DENS = 0.35   # …且局部墨密度 ≥ 此（白鬍／髮絲的縫隙白）
STICKER_EATEN_MAX = 0.06    # eaten 佔比軟上限：超標＝可能有前景白連進背景
STICKER_EATEN_HARD = 0.3    # 硬上限：無論語意證據一律拒
STICKER_TEXT_BG_MIN = 0.1   # 但文字確實壓在這片白上 ≥ 此＝作者當背景用的語意證據
STICKER_TEXT_MAX = 0.55     # 漏併氣泡閘：文字覆蓋率上限
STICKER_TEXTON_PAD = 8      # textOn 用 tight bbox + 此 px（不用 40px 窗，免鄰格字湊假證據）
STICKER_SMALL_AREA = 0.02   # 小元件必須有語意證據才填黑
STICKER_NECK_R = 8          # 區域級保護：開放背景核的侵蝕半徑
STICKER_CORE_MIN = 0.03     # 殘核 ≥ 此×元件面積才算開放背景
STICKER_PROTECT_EATEN_MIN = 0.002   # 附屬白含此比例的 eaten 才整團保護
STICKER_PROTECT_DILATE = 6  # 保護區外擴
FAINT_OF_F_MAX = 0.62       # 前景中淡色佔比上限：群眾／建築淡速寫背景不填
FAINT_G = 160               # 「淡色」的灰階下限

# 貼框擢升（被格框封閉的格內背景 → 貼紙候選）
FRAME_HUG_DILATE = 5    # 元件外擴後與格線遮罩取交集算「貼框」
FRAME_HUG_THICK = 3.0   # 格線名目厚度（交集像素數 → 貼框長度的除數）
FRAME_HUG_MIN = 0.25    # 貼框長度／bbox 周長 下限
FRAME_HUG_STRONG = 0.4  # 強貼框：背景證據夠強 ⇒ 走放寬門
# 截斷偵測：貼框若只發生在**一對相對邊**（另一對的兩邊都不貼），代表元件是被格框
# **截斷**的，不是沿著格框跑——真格背景會轉過格的角落，至少有相鄰的兩邊貼。扁格裡
# 的前景物件正是這型：ch34_011 第 2 格高只有 147 px，那片白布簾上下必然整條貼框
# （上 0.89／下 0.51）、左右幾乎不貼（0.24／0.28），hug 因此爆到 0.42 走放寬門，
# 跳過小面積門 ⇒ 被當格背景填黑。設計註記「前景白只點狀碰框 ⇒ hug 低」在扁格不成立。
HUG_SIDE_MIN = 0.5      # 每邊的貼框覆蓋門檻（低於此＝該邊不算貼）
PROMOTED_TEXTON_MAX = 0.3   # 強貼框仍拒的文字覆蓋上限（真旁白框填黑會糊字）

# 背景填黑三檔（產品檔位；2026-09-27 使用者定義「多少白該黑」，取代被打回的「保護畫面」旗標）
#   L1 最低  ＝ 分鏡溝／頁邊帶（標準留白邏輯）＋ 封閉泡（標準泡邏輯，含泡外圈 BUBBLE_REST_NEAR）
#              ＋「無畫面背景」貼紙：邊界只碰格線／頁邊、不碰線稿、不碰人物（STICKER_MODE=plain）
#              ⚠️ 實測（47 頁、2026-09-27）：過安全網的 139 顆元件裡，不碰人物且暗墨 < 0.05 的只有 2 顆（c362_009 有雲的天空、
#              c371_010 天花板淡線的小三角），淡線稿都 ≥ 0.167 ⇒ 加了淡線稿判準（STICKER_PLAIN_FAINT_MAX）後 plain 零命中，
#              L1 實際上＝留白＋泡、不填任何貼紙；「無畫面背景」要更寬的判準（外圈扣掉泡框／字）另案研究
#   L2 進階  ＝ L1 ＋ 通過貼紙安全網、且 rough ≤ STICKER_ROUGH_MAX、整頁佔比 ≥ STICKER_SIMPLE_MIN_FRAC 的白（simple／10／0.005）
#   L3 更進階＝ L1 ＋ rough ≤ 20、無面積下限（simple／20／0）
#   三檔一律：無偽泡（PSEUDO_BUBBLES=0）、無亮島填黑（HARMONIZE=0）；泡外圈、留白深度、閘門都用標準值。
#   舊的完整管線（所有貼紙＋偽泡＋亮島）仍是本檔／library 的**預設**（fixture 與守護框基線不動），但不再是產品檔位。
# 消融結論：撕裂真凶＝碰到人物、內有線稿的大白元件——它過了貼紙安全網、核心填色卻停在人物邊界 ⇒ 沿人物一圈黑。
#   rough＝周長²/(4π·面積) 分得開：正當大白 1.8–8.3 vs 撕裂元件 17.8–173。偽泡多 2 個守護框；harmonize 與乾淨容器閘
#   對守護框零影響。守護框 L1 12／L2 12／L3 16 vs 標準 18（/665）；8 張撕裂頁亮區（≥110 像素佔比）L1 40.3%／L2 39.8%／
#   L3 39.6% vs 標準 35.6%。
STICKER_MODE = os.environ.get("NIGHTREAD_STICKER_MODE", "all")   # all｜simple｜plain（all＝預設完整管線；plain＝L1；simple＝L2/L3）
assert STICKER_MODE in ("all", "simple", "plain"), f"NIGHTREAD_STICKER_MODE 只能是 all/simple/plain：{STICKER_MODE}"
STICKER_ROUGH_MAX = float(os.environ.get("NIGHTREAD_STICKER_ROUGH", "10"))          # simple：rough 上限（L2 10、L3 20）
STICKER_SIMPLE_MIN_FRAC = float(os.environ.get("NIGHTREAD_STICKER_MINFRAC", "0.005"))  # simple：整頁佔比下限（L2 0.005、L3 0）
STICKER_PLAIN_RING_R = 7    # plain：元件外圈＝dilate(橢圓 (2r+1)²＝15×15) − 元件，量外圈碰到什麼
STICKER_PLAIN_ART_MAX = 0.05    # plain：外圈上「非格線的墨」（g < INK_DARK_TH 且不在格線外擴內）佔比 < 此＝只碰格線／頁邊
STICKER_PLAIN_FRAME_DIL = 7 # plain：格線遮罩外擴的方核邊長（7×7），框線本身不算線稿
STICKER_PLAIN_FAINT_MAX = 0.10  # plain：外圈上「淡線稿」（INK_DARK_TH ≤ g < WHITE_TH、不在格線外擴內、也不是暗墨的抗鋸齒暈）佔比 < 此
                            # 才算只碰格線／頁邊。雲、效果線、淡網點都比 INK_DARK_TH 亮，只數暗墨會漏掉（c362_009 彩旗下的天空：
                            # 暗墨 0.041 過了 0.05 門、淡線稿 0.638 ⇒ 整片有雲的天空被當無畫面背景塗黑）。47 頁能走到這道判準（不碰人物、暗墨 < 0.05）
                            # 的只有 2 顆：淡線稿 0.638、0.16676（c371_010 天花板淡線的小三角），兩顆都是塗錯；0～0.166 之間任何門檻結果都一樣
STICKER_PLAIN_FAINT_HALO = 5   # 暗墨抗鋸齒暈：暗墨（g < INK_DARK_TH）方核外擴邊長（5×5），暈裡的淡像素屬於暗線、不算淡線稿
PSEUDO_BUBBLES = os.environ.get("NIGHTREAD_PB", "1") == "1"   # 偽泡開關（三檔＝0；偽泡沿字往背景長，是撕裂黑塊來源之一）
HARMONIZE = os.environ.get("NIGHTREAD_HM", "1") == "1"        # 亮島填黑開關（三檔＝0；會把格內背景挖成黑塊）

# 「更多」新規則 A2（2026-10-02 使用者拍板；研究 research/out/more_and_strip/more/HARDEN.md，見 docs/DECISIONS.md）
#   產品「更多」＝L3 ＋ 這組規則：在檔位 keep 上只加不減（更多 ⊇ L3 ⊇ 標準）。候選＝安全網收下但檔位沒收（C1）、安全網只卡
#   在「前景太少／字壓太多／文字窗」三道軟門（C2）、有框頁門檻邊上的頁邊留白（C3，格內白判準全放寬 MORE_HYST）；只靠貼框才
#   成為候選的要貼框 ≥ FRAME_HUG_MIN×(1+MORE_HYST)。每個候選要過：外輪廓自由邊界 ≤ MORE_T_OUT、內部記號 ≤ MORE_T_IN、外圈淡色
#   ≤ MORE_FAINT_MAX、軟門元件 rough ≤ MORE_ROUGH_CAP、人物原輸出佔比 < MORE_CHAR_MAX。繪製另有兩項（只在開了的檔）：描亮邊不蓋
#   已經黑的像素（MORE_KEEP_DARK）、頁緣只給沒有格框種子的核心塊當種子（MORE_EDGE_FB）；無框頁留白層仍用加規則前的 keep。
#   研究用：NIGHTREAD_MORE=0（預設）＝舊 L3；產品「更多」＝L3 的環境變數 ＋ NIGHTREAD_MORE=1。門檻預設＝產品值。
MORE_RULE = os.environ.get("NIGHTREAD_MORE", "0") == "1"
MORE_T_OUT = float(os.environ.get("NIGHTREAD_MORE_TOUT", "5"))      # S1 外輪廓自由邊界 L²/(4π·面積) 上限
MORE_T_IN = float(os.environ.get("NIGHTREAD_MORE_TIN", "2"))        # S2 內部記號（真的有畫東西的洞）自由邊界上限
MORE_FAINT_MAX = float(os.environ.get("NIGHTREAD_MORE_FAINT", "0.3"))   # S3 外圈淡色佔比上限
MORE_ROUGH_CAP = float(os.environ.get("NIGHTREAD_MORE_ROUGH", "40"))    # S4 軟門元件的 rough 上限
MORE_CHAR_MAX = float(os.environ.get("NIGHTREAD_MORE_CHAR", "0.67"))    # S5 人物原輸出佔元件 ≥ 此＝人物身上／被人物包住的白
MORE_HYST = float(os.environ.get("NIGHTREAD_MORE_HYST", "0.25"))        # 遲滯：門檻 ×(1±此)
MORE_HOLE_MIN_AREA = 8      # S2：洞面積 ≥ 此 px 才算「有畫東西」…
MORE_HOLE_DARK_MAX = 200    # …或洞裡最暗 < 此（壓縮雜點、淡色噪點兩者都不是）
MORE_MEDIAN = 5             # S1：補洞後中值平滑的核（二值多數決；BORDER_REPLICATE）
MORE_EXPLAIN_DIL = 7        # 交代過：字（seg）與格線的方核外擴邊長（7×7＝外擴 3）
MORE_NEAR_DIL = 9           # 交代過的全部再方核外擴（9×9＝外擴 4），邊界落在裡面的不算自由邊界
MORE_RING_R = 7             # S3 外圈＝dilate(橢圓 (2r+1)²) − 元件
MORE_HALO = 5               # S3 暗墨（< INK_DARK_TH）抗鋸齒暈的方核邊長
MORE_WIN_PAD = 16           # 逐元件量測窗＝bbox 外擴此（夾頁緣）
MORE_EDGE_BAND = 3          # P2 頁緣種子帶寬（距頁緣 ≤ 此−1 px）
MORE_EDGE_REACH = 4         # P2 種子帶只取「缺格框種子的核心塊」外擴 CORE_NECK_R＋此 內的頁緣
MORE_KEEP_DARK = os.environ.get("NIGHTREAD_MORE_KEEPDARK", "1") == "1"   # P1（只在 MORE_RULE 時生效）
MORE_EDGE_FB = os.environ.get("NIGHTREAD_MORE_EDGEFB", "1") == "1"       # P2（只在 MORE_RULE 時生效）
MORE_SOFT_GATES = frozenset(("figlo", "textOnP", "textCov"))             # C2：只卡在這三道門的拒收元件才是候選

# 任意角度格溝＋出血格過濾＋格溝壓過人物（2026-09-27；常數在 nightread_sep.py／nightread_bleed.py，見 docs/DECISIONS.md）
#   SEP：frame_line_mask 只認水平／垂直，斜格溝、被打斷的溝、畫到頁緣的頁邊進不了留白路徑（三檔都灰）。改從像素找任意角度
#        框線，兩線夾住的整條白＝溝、頁緣到框線的白＝頁邊；貼紙層之前用 paint_gutter 塗，人物還原跳過它（格溝壓過人物）。
#   BLEED：留白帶 veto 之後，看每塊外圈碰到的是框線／溝（留）還是畫（拿掉）——出血格的天空／地面不再被切成鋸齒黑塊。
#   47 頁（11 fixture＋8 真機＋28 補充）：溝裡仍灰的原白 314,591 → 554 px；撕口 215 → 83 塊；守護框 標準／L1／L2／L3
#   18／12／12／16 → 19／13／13／17（+1 都是 ch34_006 一個畫在格溝上的標註框；使用者確認後修正標註 ⇒ 18／12／12／16，/664）；
#   8 張撕裂頁 L2 亮區 40.2% → 38.3%。
SEP_ON = os.environ.get("NIGHTREAD_SEP", "1") == "1"        # 任意角度格溝／頁邊（0＝關）
BLEED_ON = os.environ.get("NIGHTREAD_BLEED", "1") == "1"    # 出血格過濾（0＝關）

# 核心填色（從格框種子出發、不擠過窄頸的寬闊背景）
CORE_NECK_R = 12        # 開運算半徑：切斷臉／白衣連進背景的線稿缺口
CORE_RECOVER_R = 9      # 核心確定後往墨線回收的測地半徑（貼線稿、不留白圈）
GEO_RATIO_MAX = 1.6     # 測地距離 ≤ 直線距離×此 才視為背景
GEO_SLACK = 40          # 加法餘裕（px）：近框處比值不穩定的緩衝
CORE_RELEASE_PAD = 16   # 語意放行時人物遮罩的安全外擴（見 docs/DECISIONS.md）

# 人物語意遮罩（必要輸入；沒有它紅線不可達）
CHARMASK_DIR = os.environ.get("NIGHTREAD_CHARMASK", "")   # charmask.py 的輸出夾
CHAR_SNAP = 10          # 貼墨收邊：在非墨區內測地生長到碰輪廓就停（不會讓邊界變粗）
CHAR_SNAP_PAD = 1       # 收邊後納入輪廓線本身的膨脹圈數（邊緣厚度的真正旋鈕）
MASK_SMOOTH_MEDIAN = 15 # 中值平滑：削掉 640 解析度放大造成的方塊階梯
EDGE_FEATHER = 0.7      # 還原邊界的 1–2px 抗鋸齒

# 文字（圖層優先權：字 > 對話框 > 人物 > 背景）
TEXT_PAD = 2            # 泡內畫亮字時筆畫遮罩的外擴
TEXT_GAMMA = 1.4        # 墨度 → 亮度的 gamma
TEXT_KNEE = 0.35        # 低墨度壓黑（消字邊緣殘灰）
TEXT_TOP_PAD = 3        # 字永遠最上層：被人物扣掉的泡區裡，字筆畫的貼身帶
TEXT_BACKING_R = 5      # 字壓在人物身上時的貼身暗襯半徑

# 浮在黑裡的空白人頭一致化
HARMONIZE_ZONE_CELL = 16    # 暗區地圖的降採樣尺度
HARMONIZE_ZONE_DARK = 0.45  # 粗胞暗佔比 ≥ 此 ⇒ 暗區
HARMONIZE_IN_ZONE = 0.6     # 亮島落在暗區內的比例下限
HARMONIZE_AREA_MAX = 0.004  # 亮島整頁佔比上限（主角臉更大 ⇒ 排除）
HARMONIZE_COLLAR_INK = 0.3  # 亮島外環細墨密度上限（鬍鬚／密集髮絲排除）


_model = None
_dbnet_utils = None


def _load_dbnet_utils():
    """m-i-t dbnet_utils 無相對 import，以檔案載入。"""
    p = os.path.join(ex.MIT, "manga_translator/detection/default_utils/dbnet_utils.py")
    spec = importlib.util.spec_from_file_location("mit_dbnet_utils", p)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["mit_dbnet_utils"] = mod
    spec.loader.exec_module(mod)
    return mod


def get_model():
    """DBNet（torch eager）＋後處理模組，模組級快取（批次只載一次）。"""
    global _model, _dbnet_utils
    if _model is None:
        ex.fetch_ckpt()                       # paths.fetch：缺檔下載 + sha256 驗證
        _model = ex.build_model()
        _dbnet_utils = _load_dbnet_utils()
    return _model, _dbnet_utils


# ── 偵測 ────────────────────────────────────────────────────────────

def detect(img_bgr):
    """DBNet 前向 + m-i-t 後處理 + mit_grouping 區域合併。

    回傳 (lines, regions, seg)：文字行 Quadrilateral、區域 dict（bbox/angle/lines）、
    seg 筆畫二值遮罩（bool、原圖解析度）。
    """
    import torch
    model, du = get_model()
    H, W = img_bgr.shape[:2]
    chw, inW, inH, ratio = ex.preprocess(img_bgr)      # 引擎同款前處理
    th_, tw_ = int(round(H * ratio)), int(round(W * ratio))
    with torch.no_grad():
        db, mask = model(torch.from_numpy(chw[None]))
    db = db.sigmoid().numpy()                          # m-i-t default.py：模型外 sigmoid
    mask = mask.numpy()[0, 0]

    rep = du.SegDetectorRepresenter(thresh=0.5, box_thresh=0.7, unclip_ratio=2.3)
    boxes, scores = rep({"shape": [(inH, inW)]}, db)
    boxes, scores = boxes[0], scores[0]
    lines = []
    if boxes.size:
        idx = boxes.reshape(boxes.shape[0], -1).sum(axis=1) > 0
        for pts, sc in zip(boxes[idx].astype(np.float64), np.asarray(scores)[idx]):
            q = pts / ratio                            # pad 在右下 ⇒ 除 ratio 即原圖座標
            q[:, 0] = np.clip(q[:, 0], 0, W - 1)
            q[:, 1] = np.clip(q[:, 1], 0, H - 1)
            if cv2.contourArea(q.astype(np.float32)) > 16:
                lines.append(Quadrilateral(q.astype(int), "", float(sc)))

    regions = []
    for txtlns, _, _ in merge_bboxes_text_region(list(lines), W, H):
        x0 = int(min(t.aabb.x for t in txtlns)); y0 = int(min(t.aabb.y for t in txtlns))
        x1 = int(max(t.aabb.x + t.aabb.w for t in txtlns))
        y1 = int(max(t.aabb.y + t.aabb.h for t in txtlns))
        ang = float(np.degrees(np.mean([t.angle for t in txtlns])) - 90)
        if abs(ang) < 3:
            ang = 0.0
        regions.append({
            "bbox": [x0, y0, x1, y1],
            "angle": round(ang, 1),
            "lines": [{"quad": t.pts.tolist(), "score": round(float(t.prob), 4)}
                      for t in txtlns],
        })

    # seg 筆畫遮罩：半解析 → canvas → 裁 pad → 原圖 → 閾值（對齊 seg_validate/引擎）
    m_canvas = cv2.resize(mask, (inW, inH), interpolation=cv2.INTER_LINEAR)
    m_full = cv2.resize(m_canvas[:th_, :tw_], (W, H), interpolation=cv2.INTER_LINEAR)
    seg = m_full > SEG_TH
    return lines, regions, seg


# ── 遮罩：人物／頁型／白元件／氣泡 ──────────────────────────────────


def load_charmask(page_path, shape):
    """讀 charmask.py 產的人物遮罩（255=人物）。**必要輸入**：沒有語意遮罩時紅線不可達
    （守護框實測最好也有 37 框違規），所以缺檔直接報錯而不是默默降級。"""
    name = os.path.splitext(os.path.basename(page_path))[0]
    fp = os.path.join(CHARMASK_DIR, f"{name}_char.png") if CHARMASK_DIR else ""
    m = cv2.imread(fp, cv2.IMREAD_GRAYSCALE) if fp else None
    if m is None:
        raise SystemExit(f"缺人物遮罩：{fp or '未設 NIGHTREAD_CHARMASK'}\n"
                         f"先跑 charmask.py 產遮罩，再設 NIGHTREAD_CHARMASK=<遮罩夾>。")
    if m.shape != shape:
        m = cv2.resize(m, (shape[1], shape[0]), interpolation=cv2.INTER_NEAREST)
    return m > 127


def smooth_charmask(keep, g):
    """遮罩形狀平滑：中值濾波削掉「方塊階梯」（遮罩在 640 解析度產生、放大後邊界呈直角梯級），
    再用原圖的墨線把平滑後的邊界拉回輪廓（只在非墨區生效，避免把遮罩推過線稿）。"""
    r = MASK_SMOOTH_MEDIAN | 1
    sm = cv2.medianBlur(keep.astype(np.uint8) * 255, r) > 127
    # 平滑只准在非墨區改寫：墨線上的遮罩歸屬維持原判（線稿是可信的邊界）
    return np.where(g < WHITE_TH, keep, sm)


def snap_charmask(keep, g, r=None):
    """貼墨收邊：在「非墨」區內從遮罩測地生長 r 步。遮罩不足處沿角色內部白長到輪廓線就停，
    輪廓外的背景進不來 ⇒ 邊界貼合角色，不再是等寬光暈。再 dilate 1 把輪廓線本身納入。"""
    r = CHAR_SNAP if r is None else r
    allowed = (g >= WHITE_TH) | keep            # 非墨（含網點視為墨、不穿透）
    grown = geodesic_grow(keep, allowed, r, step=4)
    if CHAR_SNAP_PAD <= 0:
        return grown
    k = np.ones((3, 3), np.uint8)
    return (cv2.dilate(grown.astype(np.uint8), k, iterations=CHAR_SNAP_PAD) > 0)


def normalize_paper(g, img_bgr=None):
    """紙白正規化：亮部（≥PAPER_PEAK_LO）眾數當紙白峰；峰 < PAPER_NORM_MIN 且**峰值區近乎無彩**時
    把 g 線性放大到峰=255（clip）。彩度門是關鍵：真掃描色紙的「紙白」是灰白（chroma≈0），水彩淡彩底
    （demo05 峰 223、粉色）是畫不是紙——沒這道門會把整頁淡彩拉成白、輸出反而變亮（b21 實測 +8pt）。
    回傳 (g', peak)。乾淨白紙（峰≥245）原樣回傳＝現有 11 頁零變化。"""
    h = np.bincount(g.ravel(), minlength=256)
    if h[PAPER_PEAK_LO:].sum() == 0:
        return g, 255
    peak = PAPER_PEAK_LO + int(np.argmax(h[PAPER_PEAK_LO:]))
    if peak >= PAPER_NORM_MIN:
        return g, peak
    if img_bgr is not None:
        sel = np.abs(g.astype(np.int16) - peak) <= 4
        if sel.any():
            px = img_bgr[sel].astype(np.int16)
            chroma = float((px.max(axis=1) - px.min(axis=1)).mean())
            if chroma > PAPER_NORM_CHROMA_MAX:
                return g, peak                            # 有彩＝淡彩畫底，不是紙
    gn = np.clip(np.round(g.astype(np.float32) * (255.0 / peak)), 0, 255).astype(np.uint8)
    return gn, peak


def frame_line_mask(g):
    """長直格框線遮罩：暗像素對「長水平/垂直線」形態學開運算＝只留貼直的長線（格框）。
    回傳 (lh, lv) 兩個 0/1 uint8。修法2 的頁型判別與修法5 的貼框擢升共用。"""
    H, W = g.shape
    dark = (g < FRAME_DARK_TH).astype(np.uint8)
    L = max(60, min(W, H) // FRAME_LINE_L_DIV)
    lh = cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (L, 1)))
    lv = cv2.morphologyEx(dark, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, L)))
    return lh, lv


def gutter_frame_cut(g, gutter):
    """格框線切割：把留白遮罩沿格框線斷開，只留真的是留白的塊。

    病根：格內的淺色背景（牆面／窗／地板）與頁邊留白在像素層連通成**同一個白元件**
    ⇒ 整塊被判留白填黑。元件級別救不了——「深入頁內」量的是距頁邊的距離，緊貼頁面
    上下緣的橫幅格永遠算不上深入（ch34_011 那顆白元件橫跨 y870..1920、深入只有 0.9%）。
    這裡逐像素切：用框線斷開遮罩，只保留「仍碰得到頁邊」或「細得像真格溝」的塊。
    """
    if not gutter.any():
        return gutter
    H, W = gutter.shape
    lh, lv = frame_line_mask(g)
    raw = ((lh | lv) > 0).astype(np.uint8)       # 真的偵測到的框線
    if GFC_CLOSE_FRAC > 0:                       # 沿線方向閉合＝補上被出血人物打斷的框線
        c = max(3, int(round(GFC_CLOSE_FRAC * min(W, H))))
        lh = cv2.morphologyEx(lh, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (c, 1)))
        lv = cv2.morphologyEx(lv, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (1, c)))
    bridge = ((lh | lv) > 0).astype(np.uint8) & (raw == 0)     # 閉合補出來的橋
    if not (raw.any() or bridge.any()):
        return gutter
    # 真框線要膨脹（補缺口），橋不膨脹：橋本來就是實心線、切開就夠；跟著膨脹會吃掉它
    # 順著跑的那條留白（長線開運算在黑髮團裡會誤判出假框線，閉合再把假線接成長橋）。
    kd = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (GFC_DILATE * 2 + 1,) * 2)
    fr = cv2.dilate(raw, kd) | bridge
    cutm = (gutter & (fr == 0)).astype(np.uint8)
    n, lb, st, _ = cv2.connectedComponentsWithStats(cutm, 8)
    if n <= 1:
        return gutter
    dist = cv2.distanceTransform(cutm, cv2.DIST_L2, 5)
    keep = np.zeros((H, W), bool)
    # 「碰得到頁邊」的容差要涵蓋框線膨脹：頁緣本身常是一條長暗線（掃描邊／最外格框），
    # 膨脹後會把貼邊那幾 px 白吃掉 ⇒ 2px 判定會把整片頁邊留白誤判成不碰邊。
    etol = GFC_DILATE + 3
    for i in range(1, n):
        x, y, w, h = st[i, 0], st[i, 1], st[i, 2], st[i, 3]
        if x <= etol or y <= etol or x + w >= W - etol or y + h >= H - etol:
            keep |= (lb == i)                    # 仍碰得到頁邊＝真留白
        else:
            m = lb == i
            if float(dist[m].max()) <= CORE_R:   # 細長＝真格溝（半寬遠小於 CORE_R）
                keep |= m                        # 防閉合把格溝橫切成孤島 ⇒ 該填的反而留灰
    if not keep.any():
        return gutter                            # 全切光＝框線偵測異常，退回不切
    kb = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, ((GFC_DILATE + 2) * 2 + 1,) * 2)
    keep |= gutter & (fr > 0) & (cv2.dilate(keep.astype(np.uint8), kb) > 0)  # 框線帶回填
    return keep


def page_is_frameless(g):
    """修法2：長直格框線存在性。回傳 (frameless, h_px_per_k, v_px_per_k)。
    無框/白背景頁（demo04/05 型）兩向都近零。"""
    lh, lv = frame_line_mask(g)
    hk, vk = 1000.0 * lh.mean(), 1000.0 * lv.mean()
    frameless = not (min(hk, vk) >= FRAME_MIN_EACH and hk + vk >= FRAME_MIN_SUM)
    return frameless, hk, vk


def _hole_ink_ratio(comp_u8, g):
    """元件「小洞內墨」/元件面積：白元件包住的線稿量（修法3 規則B 的訊號）。

    填洞（1px 零邊框 + 從外 floodFill）→ 洞＝沒被外部填到的非元件像素；
    只計小洞（< HOLE_MAX_FRAC 頁面；大洞＝被留白環住的整格，不是包線稿）。
    """
    ff = np.pad(comp_u8, 1)
    m = np.zeros((ff.shape[0] + 2, ff.shape[1] + 2), np.uint8)
    cv2.floodFill(ff, m, (0, 0), 2)
    holes = (ff[1:-1, 1:-1] == 0).astype(np.uint8)     # 非元件且外部填不到＝洞
    hn, hlab, hstats, _ = cv2.connectedComponentsWithStats(holes, 8)
    ink = 0
    for j in range(1, hn):
        if hstats[j, cv2.CC_STAT_AREA] < HOLE_MAX_FRAC * g.size:
            ink += int((g[hlab == j] < INK_DARK_TH).sum())
    return ink / max(int(comp_u8.sum()), 1)


# ★ 三段式（2026-09-17）：原本只有「填黑 16」與「場景灰 140」兩種待遇，於是畫面的白只能二選一
# ——填黑會撕裂（邊界不跟線稿走），留場景灰又不夠暗（源頭切分原型亮了 10pt 被否決）。
# 補上第三種＝**畫面的白給中間調**，對應三個語意層次：
#   純背景白（留白/格內背景）→ BG 16 ｜ 畫面的白（地板/牆面）→ 中間調 ｜ 人物的白 → 場景灰 140


def classify_white_components(g):
    """整頁白（>=WHITE_TH）連通元件一次算完，供留白與氣泡共用。

    回傳 (lab, stats, gutter_ids, panel_ids)：
      gutter_ids＝判定為留白（頁邊距/格溝）的元件 → 填深；
      panel_ids ＝貼頁邊但屬「格內白」的元件（修法3）→ 當畫面壓暗、氣泡也不併。
    """
    H, W = g.shape
    white = (g >= WHITE_TH).astype(np.uint8)
    n, lab, stats, _ = cv2.connectedComponentsWithStats(white, 8)
    dist = cv2.distanceTransform(white, cv2.DIST_L2, 5)   # 白內距最近非白（元件間互不影響）
    deep_px = max(64, int(round(DEEP_EDGE_FRAC * min(W, H))))
    min_area = int(g.size * GUTTER_MIN_AREA_FRAC)

    gutter_ids, panel_ids = set(), set()
    for i in range(1, n):
        a = int(stats[i, cv2.CC_STAT_AREA])
        if a < min_area:
            continue
        x, y, cw, ch = (stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP],
                        stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT])
        if not (x <= 2 or y <= 2 or x + cw >= W - 2 or y + ch >= H - 2):
            continue                                    # 不貼頁邊 ⇒ 非留白候選
        comp = (lab == i)
        core = comp & (dist > CORE_R)                   # 厚芯：比格溝半寬還厚的部分
        core_frac = core.sum() / a
        if core.any():
            ys, xs = np.nonzero(core)
            edge_d = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(ys, H - 1 - ys))
            core_deep = float((edge_d > deep_px).mean())  # 厚芯深入頁內（非頁邊距帶）比例
        else:
            core_deep = 0.0
        # 修法3：規則A＝厚芯大量深入頁內（出血格天空）；規則B＝深入且包住線稿（白包畫）
        in_panel = (core_frac >= IN_PANEL_CORE_FRAC and core_deep >= IN_PANEL_CORE_DEEP)
        if not in_panel and core_deep >= DEEP_INK_DEEP:
            in_panel = _hole_ink_ratio(comp.astype(np.uint8), g) >= DEEP_INK_RATIO
        (panel_ids if in_panel else gutter_ids).add(i)
    return lab, stats, gutter_ids, panel_ids


def _nontext_hole_ink_ratio(comp_u8, g, segd):
    """元件小洞內、**不在（外擴）文字筆畫上**的墨 / 元件面積＝「容器裡除了字還有什麼」。
    真泡是空白容器（0.0–0.3%）；開口泡吃進來的格內背景、字壓在臉上的皮膚白，洞裡有線稿／五官（>1%）。
    與 compose 的 clean 判準同式，但在**收泡之前**用（BUBBLE_REQUIRE_CLEAN）：不乾淨就不當泡，字交偽泡／場景調。"""
    ff = np.pad(comp_u8, 1)
    m = np.zeros((ff.shape[0] + 2, ff.shape[1] + 2), np.uint8)
    cv2.floodFill(ff, m, (0, 0), 2)
    holes = (ff[1:-1, 1:-1] == 0)
    hn, hlab, hstats, _ = cv2.connectedComponentsWithStats(holes.astype(np.uint8), 8)
    ink = 0
    for j in range(1, hn):
        if hstats[j, cv2.CC_STAT_AREA] < HOLE_MAX_FRAC * g.size:
            sel = (hlab == j) & ~segd
            ink += int((g[sel] < INK_DARK_TH).sum())
    return ink / max(int(comp_u8.sum()), 1)


def _fill_holes(m):
    """m 的洞（從窗外框 4 連通走不到的非 m 像素）一併填上。"""
    ff = np.pad(m.astype(np.uint8), 1)
    mm = np.zeros((ff.shape[0] + 2, ff.shape[1] + 2), np.uint8)
    cv2.floodFill(ff, mm, (0, 0), 2)
    return ff[1:-1, 1:-1] != 2


def bubble_leak(g, clean, clean_filled, bubble, char_raw):
    """漏泡判準（BUBBLE_LEAK）：乾淨泡裡壓在人物**原輸出**上的 8 連通塊（≥ BUBBLE_LEAK_MIN_AREA），如果它貼著的泡外緣沒有
    框線，就還給人物（回傳要併回泡的讓開遮罩的那幾塊）。泡的內部一定停在框線上——人物站在泡後面時，泡與人物之間隔著框線；
    泡的白沒被框線擋住、直接流進人物的那一段不是泡，是人物身上的白（白髮高光、白襯衫）。

    每塊外擴 BUBBLE_LEAK_RING px，取落在「泡與乾淨泡的洞」之外的那一圈；圈少於 BUBBLE_LEAK_RING_MIN px 不判（幾乎被泡包住）。
    lc（產品）：圈上「9×9 窗內最亮減最暗 ≥ BUBBLE_LEAK_RANGE」的像素佔比 < BUBBLE_LEAK_EDGE_MAX＝沒有框線。糊掉的 2–3 px 框線
    中心亮度會高過墨門檻，但線與旁邊的落差還在；柔邊（白連白）沒有落差。ink（研究對照）：圈上墨（< INK_DARK_TH）佔比 <
    BUBBLE_LEAK_INK_MAX。"""
    leak = np.zeros_like(bubble)
    cand = clean & char_raw
    if not cand.any():
        return leak
    nl, ll, sl, _ = cv2.connectedComponentsWithStats(cand.astype(np.uint8), 8)
    kr = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * BUBBLE_LEAK_RING + 1,) * 2)
    solid = clean_filled | bubble
    pd = BUBBLE_LEAK_RING + 1
    lc = None
    if BUBBLE_LEAK_MODE == "lc":
        kw = np.ones((BUBBLE_LEAK_WIN, BUBBLE_LEAK_WIN), np.uint8)
        lc = cv2.dilate(g, kw).astype(np.int16) - cv2.erode(g, kw)
    for k in range(1, nl):
        if int(sl[k, cv2.CC_STAT_AREA]) < BUBBLE_LEAK_MIN_AREA:
            continue
        x, y, w, h = (int(sl[k, 0]), int(sl[k, 1]), int(sl[k, 2]), int(sl[k, 3]))
        wy, wx = slice(max(0, y - pd), y + h + pd), slice(max(0, x - pd), x + w + pd)
        comp = ll[wy, wx] == k
        ring = (cv2.dilate(comp.astype(np.uint8), kr) > 0) & ~solid[wy, wx]
        if int(ring.sum()) < BUBBLE_LEAK_RING_MIN:
            continue
        if lc is not None:
            if float((lc[wy, wx][ring] >= BUBBLE_LEAK_RANGE).mean()) < BUBBLE_LEAK_EDGE_MAX:
                leak[wy, wx] |= comp
        elif float((g[wy, wx][ring] < INK_DARK_TH).mean()) < BUBBLE_LEAK_INK_MAX:
            leak[wy, wx] |= comp
    return leak


def confirmed_bubbles(bubble, seg, regions):
    """「字確認的泡」（BUBBLE_GUARD_RAW 用）：泡（人物修剪前）的 8 連通塊裡，字佔比 ≤ BUBBLE_CLEAN_TEXT_MAX（是容器、
    不是只有字筆畫），而且至少一個字框的完整 bbox 面積有 ≥ BUBBLE_CONFIRM_TEXT_IN 落在填洞後的塊內。不限面積。
    窗＝塊 bbox 外擴 2（夾頁緣）；字框與窗的交集外的部分不算在裡面。"""
    confirmed = np.zeros_like(bubble)
    nb, lb, st, _ = cv2.connectedComponentsWithStats(bubble.astype(np.uint8), 8)
    for i in range(1, nb):
        bx_, by_, bw_, bh_ = (int(st[i, 0]), int(st[i, 1]), int(st[i, 2]), int(st[i, 3]))
        sy = slice(max(0, by_ - 2), by_ + bh_ + 2)
        sx = slice(max(0, bx_ - 2), bx_ + bw_ + 2)
        blob = lb[sy, sx] == i
        if float(seg[sy, sx][blob].mean()) > BUBBLE_CLEAN_TEXT_MAX:
            continue
        filled = _fill_holes(blob)
        for r in regions:
            x0, y0, x1, y1 = r["bbox"]
            full = max(0, x1 - x0) * max(0, y1 - y0)
            cx0, cy0, cx1, cy1 = max(x0, sx.start), max(y0, sy.start), min(x1, sx.stop), min(y1, sy.stop)
            if full <= 0 or cx1 <= cx0 or cy1 <= cy0:
                continue
            if int(filled[cy0 - sy.start:cy1 - sy.start, cx0 - sx.start:cx1 - sx.start].sum()) >= BUBBLE_CONFIRM_TEXT_IN * full:
                confirmed[sy, sx] |= blob
                break
    return confirmed


def seal_prep(lab, stats, i, R):
    """漏泡封縫（v3）第一步：元件 i 的白以 (2R+1) 橢圓侵蝕 → 「深白」D（離墨 ≤R 的白拿掉），標號。
    窗＝元件 bbox 外擴 2R+2；頁緣不算墨（borderValue=1），窗的其他邊離元件 ≥ 2R+2，本來就由非元件像素決定。"""
    H, W = lab.shape
    bx, by, bw, bh = [int(v) for v in stats[i, :4]]
    m = 2 * R + 2
    x0, y0, x1, y1 = max(0, bx - m), max(0, by - m), min(W, bx + bw + m), min(H, by + bh + m)
    comp = lab[y0:y1, x0:x1] == i
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * R + 1,) * 2)
    D = cv2.erode(comp.astype(np.uint8), k, borderType=cv2.BORDER_CONSTANT, borderValue=1) > 0
    nD, labD, stD, _ = cv2.connectedComponentsWithStats(D.astype(np.uint8), 8)
    return {"i": i, "R": R, "win": (x0, y0, x1, y1), "comp": comp, "D": D, "labD": labD, "stD": stD,
            "Dtot": int(D.sum()), "k": k, "units": {}}


def seal_unit(prep, t):
    """深白塊 t 還原成一個單元（快取在 prep["units"]）：
      S ＝該塊 ∪（該塊外擴 R 內的元件白，但扣掉「其他深白塊外擴 R」也碰得到的像素＝縫裡兩邊都搆得到的地方，那就是封縫弧）
      U ＝S（只留與該塊 8 連通的）填洞後 ∩ 元件（字筆畫、字間口袋）
    外擴 R 只會落在「以深白像素為圓心、半徑 R 的無墨圓盤」內，不會跨過任何墨線；縫裡兩邊都搆得到的像素歸封縫弧、不給任何一邊。
    單元一定在該塊 bbox 外擴 R 內 ⇒ 只在 bbox 外擴 R+2 的小窗裡算（與整窗算逐像素相同）。
    回傳 {"win": 小窗的整頁座標, "U", "filled", "cut", "rest"（這塊以外的深白 px）, "S0", "cutPx", "cutN", "d"}；
    cut＝元件內、單元（含洞）外、與單元 8 相鄰的白＝封縫弧所在的縫，群數＝縫的個數。"""
    if t in prep["units"]:
        return prep["units"][t]
    R, k = prep["R"], prep["k"]
    x0, y0, x1, y1 = prep["win"]
    bx, by, bw, bh = [int(v) for v in prep["stD"][t, :4]]
    m = R + 2
    cx0, cy0 = max(0, bx - m), max(0, by - m)
    cx1, cy1 = min(x1 - x0, bx + bw + m), min(y1 - y0, by + bh + m)
    comp = prep["comp"][cy0:cy1, cx0:cx1]
    lD = prep["labD"][cy0:cy1, cx0:cx1]
    S0 = lD == t
    oth = (lD > 0) & ~S0
    rin = cv2.dilate(S0.astype(np.uint8), k) > 0
    rout = cv2.dilate(oth.astype(np.uint8), k) > 0
    S = (S0 | (rin & ~rout)) & comp
    n2, l2 = cv2.connectedComponents(S.astype(np.uint8), 8)
    keep = np.unique(l2[S0]); keep = keep[keep > 0]
    S = np.isin(l2, keep)
    filled = _fill_holes(S)
    U = filled & comp
    cut = comp & ~filled & (cv2.dilate(filled.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)
    ncut = cv2.connectedComponents(cut.astype(np.uint8), 8)[0] - 1
    u = {"win": (x0 + cx0, y0 + cy0, x0 + cx1, y0 + cy1), "U": U, "filled": filled, "cut": cut, "d": int(t),
         "rest": prep["Dtot"] - int((prep["D"][cy0:cy1, cx0:cx1] & filled).sum()), "S0": int(S0.sum()),
         "cutPx": int(cut.sum()), "cutN": int(ncut)}
    prep["units"][t] = u
    return u


def seal_labels(prep, touch_px):
    """字（touch_px＝整頁布林：字的外擴筆畫）碰到的深白塊標號，依面積由大到小。"""
    x0, y0, x1, y1 = prep["win"]
    ids = np.unique(prep["labD"][touch_px[y0:y1, x0:x1] & prep["D"]])
    ids = [int(t) for t in ids if t > 0]
    ids.sort(key=lambda t: -int(prep["stD"][t, cv2.CC_STAT_AREA]))
    return ids


def build_bubble_mask(g, regions, seg, lab, stats, excluded_ids, charmask=None, chroma=None, audit=None,
                      local_out=None, char_raw=None):
    """氣泡內部遮罩（修法1）：每文字區 bbox+BUBBLE_PAD 窗內，找「貼著（外擴後）
    文字筆畫」的白色連通元件，通過守門則整顆併入（不裁窗 ⇒ 無截斷方塊，
    原型 regrow 補救移除）。守門（不併＝該區只保留筆畫，安全降級）：
      整頁佔比 ≤ BUBBLE_COMP_MAX_FRAC（格內背景白太大，不是氣泡）
      面積 ≤ BUBBLE_LOCAL_K × 搜尋窗（局部性：氣泡跟它的字同尺度）
      不在 excluded_ids（留白/格內白元件）
      核心的非字邊界貼墨比例 ≥ BUBBLE_OUTLINE_MIN（字壓背景的白不是泡；外圈有彩不判）
    """
    H, W = g.shape
    seg_u8 = seg.astype(np.uint8) * 255
    seg_dil = cv2.dilate(seg_u8, np.ones((9, 9), np.uint8))  # 筆畫外擴→碰得到氣泡白底
    segd_c = cv2.dilate(seg_u8, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))) > 0  # 乾淨判準用（同 compose）
    bubble = np.zeros((H, W), bool)
    local_m = np.zeros((H, W), bool)       # 封縫救回的泡（另存：只進泡的重繪，不進格溝／留白等結構層）
    seal_cache = {}                         # 元件 i → {"prep": 侵蝕＋深白標號（單元逐塊快取）, "accepted": [(窗, 含洞遮罩)]}
    seal_done = {}                          # (i, 深白塊標號) → True 收／False 拒／None 在已收泡裡（黏著，同 HEAD 的 merged／rejected）
    merged, rejected, cored = set(), set(), set()
    # 先數「每個白元件被幾個文字區命中」：相連的雙泡是**同一個白元件**（ch34_015 左下格 4.78% 頁），
    # 用單一字框當分母會讓比值假性超標（2.32/3.96 > 2.0）⇒ 兩顆泡都被拒收、內部留場景灰、
    # 只剩泡框被描亮成粗白環（使用者回報）。分母改成該元件**所有**命中字區的長邊²總和。
    comp_den = {}
    # 泡局部性檢查（面積 ≤ BUBBLE_LOCAL_K × 搜尋窗）要用「該元件**所有**命中字區的最大窗」，不能用當下字區的窗：
    # c362_011 右上雙泡的大泡白元件（102025 px）也被鄰近小字框「當然」（bbox [1153,219,1186,292]，窗 17289、
    # K×窗 69156 < 102025）碰到、先被拒收，而 rejected 是黏的（下一個字區碰到同元件直接 continue），輪到它自己的
    # 大字框（[931,202,1021,433]，K×窗 211480）時已救不回 ⇒ 純迭代順序決定收/拒、整顆泡留白。
    comp_win = {}
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        cx0, cy0 = max(0, x0 - BUBBLE_PAD), max(0, y0 - BUBBLE_PAD)
        cx1, cy1 = min(W, x1 + BUBBLE_PAD), min(H, y1 + BUBBLE_PAD)
        lab_c = lab[cy0:cy1, cx0:cx1]
        for i in np.unique(lab_c[(seg_dil[cy0:cy1, cx0:cx1] > 0) & (lab_c > 0)]):
            comp_den[int(i)] = comp_den.get(int(i), 0) + max(1, max(x1 - x0, y1 - y0) ** 2)
            comp_win[int(i)] = max(comp_win.get(int(i), 0), (cx1 - cx0) * (cy1 - cy0))
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        cx0, cy0 = max(0, x0 - BUBBLE_PAD), max(0, y0 - BUBBLE_PAD)
        cx1, cy1 = min(W, x1 + BUBBLE_PAD), min(H, y1 + BUBBLE_PAD)
        win_area = (cx1 - cx0) * (cy1 - cy0)
        lab_c = lab[cy0:cy1, cx0:cx1]
        touch = np.unique(lab_c[(seg_dil[cy0:cy1, cx0:cx1] > 0) & (lab_c > 0)])
        for i in touch:
            a = int(stats[i, cv2.CC_STAT_AREA])
            big = a > BUBBLE_COMP_MAX_FRAC * g.size or a > BUBBLE_LOCAL_K * comp_win.get(int(i), win_area)
            if BUBBLE_SEAL_R > 0 and (big or i in excluded_ids):
                # 漏泡封縫（v3）：整顆不能當泡，但字所在的白可能只是經 ≤2R px 的縫漏出去 ⇒ 封縫後若自成一塊，走原本的泡路徑
                _seal_region(g, regions, seg, seg_dil, segd_c, lab, stats, int(i), (x0, y0, x1, y1), chroma, char_raw,
                             seal_cache, seal_done, local_m, audit, "size" if big else "excluded")
            if (i in merged or int(i) in rejected) and i not in excluded_ids:
                continue
            if big:
                rejected.add(int(i))
                continue
            if i in excluded_ids:
                continue                    # 留白/格內白元件不當泡（字交偽泡貼身填色）
            # 安全策略：泡元件不得遠大於它的字（真泡字塞 30–50%＝比 2–3.5；「字壓在臉頰/手上」
            # 的元件是整片皮膚白、比 10+）。超過 → 不當泡，字交偽泡貼身袖套。
            # ⚠️ 分母用**字框長邊平方**不是字框面積：單行直排的字框只有一行寬（ch34_006「その通り
            # じゃ」23×167），面積 3841 而泡 38337 ⇒ 比值 9.98 假性爆表、整顆泡被拒收成白底。
            # 長邊² 對方形字框等於面積（保護不變）、只對細長字框放寬，正是要的。
            ratio_den = SAFE_BUBBLE_RATIO * comp_den.get(int(i), max(1, max(x1 - x0, y1 - y0) ** 2))
            # ⚠️ 大元件的比值要等 core fill 算完、用**實際要填的核心**面積判，不能用整個元件：
            # 相連的雙泡是**同一個白元件**（ch34_015 左下格 4.78% 頁），比值 2.32/3.96 假性超標 ⇒
            # 兩顆泡都被拒收、內部留場景灰、只剩泡框被描亮成粗白環（使用者回報）。核心填色本來就
            # 會在頸部把兩泡切開，填的只是字所在那顆。
            if SAFE_BUBBLE_RATIO > 0 and a < BUBBLE_CORE_MIN_FRAC * g.size and a > ratio_den:
                rejected.add(int(i))
                continue
            if a >= BUBBLE_CORE_MIN_FRAC * g.size:
                # 文字種子核心填色（2026-09-08，審查員抓到 demo01 主角臉被當泡填黑後）：不整顆併入，
                # 從「字 bbox 內的白」出發、開運算切窄頸、只留與字連通的寬闊區、再測地回收貼線稿。
                # 泡框缺口漏出的背景（窄頸）與經下巴縫連進來的臉白被切掉；真泡內部照填。
                bx, by, bw, bh = stats[i, :4]
                comp = lab[by:by + bh, bx:bx + bw] == i
                seed = np.zeros_like(comp)
                sx0, sy0 = max(0, x0 - bx), max(0, y0 - by)
                sx1, sy1 = min(bw, x1 - bx), min(bh, y1 - by)
                if sx1 > sx0 and sy1 > sy0:
                    seed[sy0:sy1, sx0:sx1] = True
                core = broad_core_fill(comp, seed & comp, neck_r=BUBBLE_NECK_R, recover_r=BUBBLE_NECK_R)
                if not core.any():
                    rejected.add(int(i))
                    continue
                if SAFE_BUBBLE_RATIO > 0 and int(core.sum()) > ratio_den:
                    rejected.add(int(i))
                    continue
                if BUBBLE_OUTLINE_MIN > 0:
                    # 字壓背景閘（2026-09-27）：core 是「字所在的白」，但字寫在天空／牆面上時那片白不是泡——
                    # 泡由自己的框線圍住（非字邊界幾乎全貼墨），背景白的邊界是網點灰／雲線／別人的線稿。
                    # 彩頁例外：泡框／底可能是淡彩（demo04 淡紫框、爆炸泡的斜線底），墨判準不成立 ⇒ 外圈有彩就不判。
                    px0, py0 = max(0, bx - BUBBLE_OUTLINE_PAD), max(0, by - BUBBLE_OUTLINE_PAD)
                    px1, py1 = min(W, bx + bw + BUBBLE_OUTLINE_PAD), min(H, by + bh + BUBBLE_OUTLINE_PAD)
                    core_w = np.zeros((py1 - py0, px1 - px0), bool)
                    core_w[by - py0:by - py0 + bh, bx - px0:bx - px0 + bw] = core
                    sd_w = segd_c[py0:py1, px0:px1]
                    bnd = core_w & ~(cv2.erode(core_w.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0) & ~sd_w
                    if int(bnd.sum()) >= BUBBLE_OUTLINE_MIN_PX:
                        achromatic = True
                        if chroma is not None:
                            ring = (cv2.dilate(core_w.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))) > 0) & ~core_w & ~sd_w
                            achromatic = (not ring.any()) or float(chroma[py0:py1, px0:px1][ring].mean()) <= STICKER_CHROMA_MAX
                        if achromatic:
                            dd = cv2.distanceTransform((g[py0:py1, px0:px1] >= INK_DARK_TH).astype(np.uint8), cv2.DIST_L2, 3)
                            if float((dd[bnd] <= BUBBLE_OUTLINE_DIST).mean()) < BUBBLE_OUTLINE_MIN:
                                rejected.add(int(i))    # 邊界不貼墨＝沒有框線圍住＝字壓背景，不是泡
                                continue
                if BUBBLE_REQUIRE_CLEAN and _nontext_hole_ink_ratio(
                    core.astype(np.uint8), g[by:by + bh, bx:bx + bw], segd_c[by:by + bh, bx:bx + bw],
                ) >= BUBBLE_CLEAN_WINS:
                    rejected.add(int(i))    # 核心裡除了字還有線稿＝不是容器（開口泡吃進的背景）
                    continue
                bubble[by:by + bh, bx:bx + bw] |= core
                merged.add(int(i))
                cored.add(int(i))
                continue
            else:
                if BUBBLE_REQUIRE_CLEAN:
                    bx, by, bw, bh = stats[i, :4]
                    comp_s = (lab[by:by + bh, bx:bx + bw] == i).astype(np.uint8)
                    if _nontext_hole_ink_ratio(comp_s, g[by:by + bh, bx:bx + bw], segd_c[by:by + bh, bx:bx + bw]) >= BUBBLE_CLEAN_WINS:
                        rejected.add(int(i))
                        continue
                bubble |= lab == i
            merged.add(int(i))
        bubble[y0:y1, x0:x1] |= seg[y0:y1, x0:x1]       # 區內筆畫本身一定算氣泡內容
    if local_out is not None:
        local_out["mask"] = local_m & ~bubble   # 只有封縫救回才新增的部分
    return bubble | local_m, merged, rejected, cored


def _seal_region(g, regions, seg, seg_dil, segd_c, lab, stats, i, rbox, chroma, char_raw,
                 seal_cache, seal_done, local_m, audit, cause):
    """漏泡封縫（v3）的逐（字區, 元件）處理：元件 i 封縫後，字區 rbox 碰到的深白塊依面積由大到小各自還原成單元，
    第一次遇到就把它當成新元件照 build_bubble_mask 的泡路徑判一次（黏著：之後別的字區碰到同一單元直接沿用結果）。
    已收下的單元（含洞）裡的深白塊不再另判（泡裡的字間口袋）；被拒的單元不蓋住別人（大單元被拒，它洞裡被封起來的泡照判）。
    收下的畫進 local_m。泡路徑的閘全部照舊（整頁佔比、局部性 K×窗、比值、大元件的核心填色＋核心比值、貼墨閘、REQUIRE_CLEAN），另加：
      edge    ：單元碰頁緣＝照 classify_white_components 會被列為留白／格內白 ⇒ 不當泡（v1 的 EDGE 例外不採用）
      split   ：單元以外還剩 ≥ BUBBLE_SEAL_REST_MIN px 深白＝真的切下了一塊（否則只是把整個大元件削一圈邊）
      porous  ：封掉的縫 ≤ BUBBLE_SEAL_MAX_GAPS 個（框線四處漏的粗糙框不算「一個極窄的縫」）
      cut-far ：每個縫到泡身（開運算 BUBBLE_NECK_R 後含字的寬闊塊）的測地距離 ≤ BUBBLE_SEAL_CUT_GEO（縫要在泡自己的框上，
                不是在別處夾縫的窄點；雙線框：內框大缺口 + 兩框夾縫的斜向窄點）
      textIn  ：字框內筆畫 ≥ BUBBLE_SEAL_TEXT_IN 落在單元（含洞）裡
      outline ：小單元也要過貼墨閘；外圈有彩一律不收（HEAD 對有彩外圈是跳過不判，這裡從嚴）
      char    ：單元（實際要填的）∩ char_raw ≤ BUBBLE_SEAL_CHAR_MAX；沒給 char_raw 就不收"""
    H, W = g.shape
    x0, y0, x1, y1 = rbox
    if i not in seal_cache:
        seal_cache[i] = {"prep": seal_prep(lab, stats, i, BUBBLE_SEAL_R), "accepted": []}
    ent = seal_cache[i]
    prep = ent["prep"]
    c0, d0 = max(0, x0 - BUBBLE_PAD), max(0, y0 - BUBBLE_PAD)
    c1, d1 = min(W, x1 + BUBBLE_PAD), min(H, y1 + BUBBLE_PAD)
    tp = np.zeros((H, W), bool)
    tp[d0:d1, c0:c1] = seg_dil[d0:d1, c0:c1] > 0
    for t in seal_labels(prep, tp):
        key = (i, t)
        if key in seal_done:
            continue
        # 已收下單元（含洞）裡的深白塊＝泡裡的口袋，不另判
        px0, py0 = prep["win"][0], prep["win"][1]
        bx, by = int(prep["stD"][t, 0]) + px0, int(prep["stD"][t, 1]) + py0
        inside = False
        for (aw, af) in ent["accepted"]:
            ax0, ay0, ax1, ay1 = aw
            if ax0 <= bx < ax1 and ay0 <= by < ay1:
                sub = prep["labD"][ay0 - py0:ay1 - py0, ax0 - px0:ax1 - px0] == t
                if (sub & af).any():
                    inside = True
                    break
        if inside:
            seal_done[key] = None
            continue
        u = seal_unit(prep, t)
        if "den" not in u:
            # 單元的局部性／比值分母：碰到它的所有字區（同 HEAD 的 comp_win／comp_den）
            wx0, wy0, wx1, wy1 = u["win"]
            den, cw = 0, 0
            for r in regions:
                a0, b0, a1, b1 = r["bbox"]
                e0, f0 = max(0, a0 - BUBBLE_PAD), max(0, b0 - BUBBLE_PAD)
                e1, f1 = min(W, a1 + BUBBLE_PAD), min(H, b1 + BUBBLE_PAD)
                ix0, iy0, ix1, iy1 = max(e0, wx0), max(f0, wy0), min(e1, wx1), min(f1, wy1)
                if ix1 <= ix0 or iy1 <= iy0:
                    continue
                if (u["U"][iy0 - wy0:iy1 - wy0, ix0 - wx0:ix1 - wx0] & (seg_dil[iy0:iy1, ix0:ix1] > 0)).any():
                    den += max(1, max(a1 - a0, b1 - b0) ** 2)
                    cw = max(cw, (e1 - e0) * (f1 - f0))
            u["den"], u["cw"] = den, cw
        ok, paint, met = _seal_judge(g, seg, segd_c, stats, u, u["win"], (x0, y0, x1, y1), chroma, char_raw)
        seal_done[key] = ok
        if audit is not None:
            met.update(comp=int(i), label=int(t), region=[int(x0), int(y0), int(x1), int(y1)], cause=cause,
                       compArea=int(stats[i, cv2.CC_STAT_AREA]), R=BUBBLE_SEAL_R, win=[int(v) for v in u["win"]])
            audit.append(met)
        if ok:
            wx0, wy0, wx1, wy1 = u["win"]
            local_m[wy0:wy1, wx0:wx1] |= paint
            ent["accepted"].append((u["win"], u["filled"]))


def _seal_judge(g, seg, segd_c, stats, u, win, rbox, chroma, char_raw, full=False):
    """一個封縫單元走 HEAD 的泡路徑＋v3 附加閘。回傳 (收否, 要畫的窗內遮罩, 量測)。[full]＝研究用，全部量完（判定不變）。"""
    H, W = g.shape
    wx0, wy0, wx1, wy1 = win
    x0, y0, x1, y1 = rbox
    U = u["U"]
    aU = int(U.sum())
    met = {"area": aU, "S0": u["S0"], "rest": u["rest"], "den": u["den"], "cw": u["cw"], "cutPx": u["cutPx"], "cutN": u["cutN"]}
    why = []
    def fail(w):
        why.append(w)
        return not full
    def done(paint):
        met["why"] = why[0] if why else "ok"
        if full:
            met["fails"] = list(why)
        return (not why), paint, met
    z = np.zeros_like(U)
    if aU == 0:
        why.append("empty")
        return done(z)
    ys, xs = np.nonzero(U)
    ux0, uy0, ux1, uy1 = xs.min() + wx0, ys.min() + wy0, xs.max() + wx0 + 1, ys.max() + wy0 + 1
    met["bbox"] = [int(ux0), int(uy0), int(ux1), int(uy1)]
    # edge：碰頁緣（同 classify_white_components 的留白候選判準）
    if (ux0 <= 2 or uy0 <= 2 or ux1 >= W - 2 or uy1 >= H - 2) and fail("edge"):
        return done(z)
    if u["rest"] < BUBBLE_SEAL_REST_MIN and fail("no-split"):
        return done(z)
    if u["cutN"] > BUBBLE_SEAL_MAX_GAPS and fail("porous"):
        return done(z)
    # textIn：字框內的筆畫落在單元（含洞）裡的比例
    sx0, sy0, sx1, sy1 = max(x0, wx0), max(y0, wy0), min(x1, wx1), min(y1, wy1)
    segb = seg[y0:y1, x0:x1]
    ns = int(segb.sum())
    inside = int((seg[sy0:sy1, sx0:sx1] & u["filled"][sy0 - wy0:sy1 - wy0, sx0 - wx0:sx1 - wx0]).sum()) if (sx1 > sx0 and sy1 > sy0) else 0
    met["textIn"] = round(inside / ns, 4) if ns else 0.0
    if met["textIn"] < BUBBLE_SEAL_TEXT_IN and fail("text-outside"):
        return done(z)
    # HEAD：整頁佔比、局部性
    if aU > BUBBLE_COMP_MAX_FRAC * g.size and fail("size-frac"):
        return done(z)
    if aU > BUBBLE_LOCAL_K * max(u["cw"], 1) and fail("size-local"):
        return done(z)
    # cutGeo：每個縫到泡身的測地距離（泡身＝U 以 BUBBLE_NECK_R 開運算後、含字框像素的塊；同 broad_core_fill 的開運算）
    ko = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * BUBBLE_NECK_R + 1,) * 2)
    op = cv2.morphologyEx(U.astype(np.uint8), cv2.MORPH_OPEN, ko)
    nop, lop = cv2.connectedComponents(op, 8)
    sb = np.zeros_like(U)
    sbx0, sby0 = max(0, x0 - wx0), max(0, y0 - wy0)
    sbx1, sby1 = min(U.shape[1], x1 - wx0), min(U.shape[0], y1 - wy0)
    if sbx1 > sbx0 and sby1 > sby0:
        sb[sby0:sby1, sbx0:sbx1] = True
    ids_ = np.unique(lop[sb & (op > 0)]); ids_ = ids_[ids_ > 0]
    body = np.isin(lop, ids_)
    lim = 60 if full else BUBBLE_SEAL_CUT_GEO
    ncg, lcg = cv2.connectedComponents(u["cut"].astype(np.uint8), 8)
    if not body.any():
        met["cutGeo"] = None
    elif ncg <= 1:
        met["cutGeo"] = 0
    else:
        within = (U | u["cut"]).astype(np.uint8)
        k3 = np.ones((3, 3), np.uint8)
        cur = body.astype(np.uint8)
        geo = np.full(U.shape, -1, np.int32); geo[body] = 0
        for step in range(1, lim + 2):
            nxt = cv2.dilate(cur, k3) & within
            nw = (nxt > 0) & (geo < 0)
            if not nw.any():
                break
            geo[nw] = step; cur = nxt
        gd = []
        for q in range(1, ncg):
            v = geo[lcg == q]; v = v[v >= 0]
            gd.append(int(v.min()) if v.size else lim + 1)
        met["cutGeo"] = max(gd)
    if (met["cutGeo"] is None or met["cutGeo"] > BUBBLE_SEAL_CUT_GEO) and fail("cut-far"):
        return done(z)
    den = SAFE_BUBBLE_RATIO * max(u["den"], 1)
    paint = U
    if aU < BUBBLE_CORE_MIN_FRAC * g.size:
        met["ratio"] = round(aU / max(u["den"], 1), 3)
        if SAFE_BUBBLE_RATIO > 0 and aU > den and fail("small-ratio"):
            return done(z)
    else:
        seed = np.zeros_like(U)
        sx0_, sy0_ = max(0, x0 - wx0), max(0, y0 - wy0)
        sx1_, sy1_ = min(U.shape[1], x1 - wx0), min(U.shape[0], y1 - wy0)
        if sx1_ > sx0_ and sy1_ > sy0_:
            seed[sy0_:sy1_, sx0_:sx1_] = True
        core = broad_core_fill(U, seed & U, neck_r=BUBBLE_NECK_R, recover_r=BUBBLE_NECK_R)
        met["core"] = int(core.sum())
        if not core.any():
            why.append("core-empty")
            return done(z)
        met["ratio"] = round(int(core.sum()) / max(u["den"], 1), 3)
        if SAFE_BUBBLE_RATIO > 0 and int(core.sum()) > den and fail("core-ratio"):
            return done(z)
        paint = core
    # 貼墨閘（大小單元都判）；外圈有彩一律不收
    pc = paint
    px0, py0 = max(0, wx0 - BUBBLE_OUTLINE_PAD), max(0, wy0 - BUBBLE_OUTLINE_PAD)
    px1, py1 = min(W, wx1 + BUBBLE_OUTLINE_PAD), min(H, wy1 + BUBBLE_OUTLINE_PAD)
    core_w = np.zeros((py1 - py0, px1 - px0), bool)
    core_w[wy0 - py0:wy1 - py0, wx0 - px0:wx1 - px0] = pc
    sd_w = segd_c[py0:py1, px0:px1]
    bnd = core_w & ~(cv2.erode(core_w.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0) & ~sd_w
    met["bndPx"] = int(bnd.sum())
    ring = (cv2.dilate(core_w.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))) > 0) & ~core_w & ~sd_w
    met["ringChroma"] = None if (chroma is None or not ring.any()) else round(float(chroma[py0:py1, px0:px1][ring].mean()), 2)
    if (met["ringChroma"] is None or met["ringChroma"] > STICKER_CHROMA_MAX) and fail("chromatic"):
        return done(z)
    if bnd.sum() >= BUBBLE_OUTLINE_MIN_PX:
        dd = cv2.distanceTransform((g[py0:py1, px0:px1] >= INK_DARK_TH).astype(np.uint8), cv2.DIST_L2, 3)
        met["outline"] = round(float((dd[bnd] <= BUBBLE_OUTLINE_DIST).mean()), 4)
        if met["outline"] < BUBBLE_OUTLINE_MIN and fail("outline"):
            return done(z)
    else:
        met["outline"] = None
    # 人物遮罩佔比
    met["charRaw"] = None if char_raw is None else round(float(char_raw[wy0:wy1, wx0:wx1][pc].mean()), 4)
    if (met["charRaw"] is None or met["charRaw"] > BUBBLE_SEAL_CHAR_MAX) and fail("character"):
        return done(z)
    if BUBBLE_REQUIRE_CLEAN and _nontext_hole_ink_ratio(
            pc.astype(np.uint8), g[wy0:wy1, wx0:wx1], segd_c[wy0:wy1, wx0:wx1]) >= BUBBLE_CLEAN_WINS:
        fail("not-clean")
    return done(paint)


# ── 貼紙式背景：純白背景填黑＋前景白描邊 ────────────────────────────

def _comp_window(g, lab, stats, i, margin):
    """元件 bbox 外擴 margin 的工作窗：回傳 (x0,y0,x1,y1, sub_g, comp_bool)。"""
    H, W_ = g.shape
    x, y, cw, ch = (stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP],
                    stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT])
    x0, y0 = max(0, x - margin), max(0, y - margin)
    x1, y1 = min(W_, x + cw + margin), min(H, y + ch + margin)
    return x0, y0, x1, y1, g[y0:y1, x0:x1], (lab[y0:y1, x0:x1] == i)


def _sticker_eaten(sub, comp):
    """「疑似被吃前景白」遮罩：細白（dist ≤ EATEN_R）且局部墨密度 ≥ EATEN_DENS。

    白鬍/髮絲這類前景白透過筆畫縫隙連進背景 W 時，就長這個樣（密集筆畫的
    縫隙白）；背景密細節（教堂花窗速寫）也會命中——兩者統計上分不開，
    後續一律走區域級保護（不填黑、留 D2），見 _sticker_protect。
    """
    dist = cv2.distanceTransform(comp.astype(np.uint8), cv2.DIST_L2, 5)
    dens = cv2.blur((sub < WHITE_TH).astype(np.float32), (15, 15))
    return (comp & (dist <= STICKER_EATEN_R) & (dens >= STICKER_EATEN_DENS))


def _sticker_protect(eaten, comp):
    """區域級保護遮罩＝「窄頸附屬白 ∧ 含 eaten」（真 figure/ground 分離）。

    開放背景核＝erode(W, NECK_R) 後的大殘核（≥ CORE_MIN × W；白臉額頭殘核小、
    不算背景）；由核作 3×3 遮罩膨脹的 geodesic 重建（小核不會跳過 ≥1px 墨線）
    → 重建到不了的 W ＝只能經寬 < 2×NECK_R 窄縫抵達的「物件附屬白」（白鬍臉
    整片，含不算 eaten 的寬白叢——之前純形態學聚團蓋不住的就是這塊）。
    附屬白連通區含 eaten 夠多才保護（乾淨格的窄框縫無 eaten ⇒ 照填）。
    半解析度重建（數百次 3×3 迭代，省 4 倍）。
    """
    area = max(int(comp.sum()), 1)
    h, w = comp.shape
    hh, hw = max(1, h // 2), max(1, w // 2)
    comp_h = cv2.resize(comp.astype(np.uint8), (hw, hh), interpolation=cv2.INTER_NEAREST)
    eaten_h = cv2.resize(eaten.astype(np.uint8), (hw, hh), interpolation=cv2.INTER_NEAREST)
    r = max(2, STICKER_NECK_R // 2)
    ke = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1,) * 2)
    core = cv2.erode(comp_h, ke)
    nn, ll, ss, _ = cv2.connectedComponentsWithStats(core, 8)
    area_h = max(int(comp_h.sum()), 1)
    seed = np.zeros_like(comp_h)
    for j in range(1, nn):
        if ss[j, cv2.CC_STAT_AREA] >= STICKER_CORE_MIN * area_h:
            seed[ll == j] = 1
    if not seed.any():                                  # 沒有開放背景核＝全窄碎
        return np.ones((h, w), bool)                    # 全保護（極端保守）
    k3 = np.ones((3, 3), np.uint8)
    recon, prev = seed, -1
    for _ in range(4000):
        recon = cv2.dilate(recon, k3) & comp_h
        cnt = int(cv2.countNonZero(recon))
        if cnt == prev:
            break
        prev = cnt
    appendage = (comp_h > 0) & (recon == 0)
    nn, ll, _, _ = cv2.connectedComponentsWithStats(appendage.astype(np.uint8), 8)
    need = max(100, STICKER_PROTECT_EATEN_MIN * area) / 4.0   # 半解析度像素數 ÷4
    protect_h = np.zeros_like(comp_h)
    for j in range(1, nn):
        blob = ll == j
        if int(eaten_h[blob].sum()) >= need:
            protect_h[blob] = 1
    if not protect_h.any():
        return np.zeros((h, w), bool)
    protect = cv2.resize(protect_h, (w, h), interpolation=cv2.INTER_NEAREST)
    kd = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * STICKER_PROTECT_DILATE + 1,) * 2)
    return cv2.dilate(protect, kd) > 0


def sticker_metrics(g, img_bgr, lab, stats, i, text_rects, text_rects_on):
    """單顆白背景元件的 figure/ground 診斷（修法4 安全網用）。

    在元件 bbox（外擴 8px）內量：
      figFrac ＝前景佔比（暗像素 ∪ 被 W 封閉的白＝臉/衣服）；
      thinFrac＝W 細碎佔比（距離變換 ≤ STICKER_THIN_R；整體破碎會爆高）；
      chroma  ＝W 平均彩度（max−min 通道）：淡彩水彩底非「純白」的鐵證；
      eaten   ＝疑似被吃前景白佔 W 比例（_sticker_eaten）；
      protect ＝區域級保護區佔 W 比例（審計/報表用，實際遮罩 paint 時重算）；
      textCov ＝文字窗（bbox+BUBBLE_PAD）蓋住 W 的比例（過高＝漏併的氣泡）；
      textOn  ＝tight 文字 bbox（+STICKER_TEXTON_PAD）∩ W 的比例＝文字「真的
                壓在這個白元件上」的語意證據（TEXT_BG_MIN 放行只認這個；40px
                窗會把鄰格文字掃進相鄰元件湊假證據，ch34_010 披肩案）；
      rough   ＝周長²/(4π·面積)（圓=1；輪廓破碎度，審計參考）。
    """
    H, W_ = g.shape
    x0, y0, x1, y1, sub, comp = _comp_window(g, lab, stats, i, 8)
    comp_u8 = comp.astype(np.uint8)
    area = max(int(comp.sum()), 1)

    ff = np.pad(comp_u8, 1)                             # 零邊框 → 外部一定連通
    mk = np.zeros((ff.shape[0] + 2, ff.shape[1] + 2), np.uint8)
    cv2.floodFill(ff, mk, (0, 0), 2)
    enclosed = ff[1:-1, 1:-1] == 0                      # 非 W 且外部到不了＝被 W 封閉
    fig = (sub < WHITE_TH) | enclosed
    fig_frac = float(fig.mean())

    dist = cv2.distanceTransform(comp_u8, cv2.DIST_L2, 5)
    thin_frac = float(((dist <= STICKER_THIN_R) & comp).sum() / area)

    sub_c = img_bgr[y0:y1, x0:x1].astype(np.int16)
    chroma = float((sub_c.max(axis=2) - sub_c.min(axis=2))[comp].mean())

    eaten = _sticker_eaten(sub, comp)
    eaten_frac = float(eaten.sum() / area)
    protect_frac = float((_sticker_protect(eaten, comp) & comp).sum() / area)

    tmask = np.zeros((H, W_), np.uint8)
    for rx0, ry0, rx1, ry1 in text_rects:
        cv2.rectangle(tmask, (rx0, ry0), (rx1, ry1), 1, -1)
    text_cov = float(tmask[y0:y1, x0:x1][comp].mean()) if area else 0.0

    tmask_on = np.zeros((H, W_), np.uint8)
    for rx0, ry0, rx1, ry1 in text_rects_on:
        cv2.rectangle(tmask_on, (rx0, ry0), (rx1, ry1), 1, -1)
    text_on = float(tmask_on[y0:y1, x0:x1][comp].mean()) if area else 0.0

    cnts, _ = cv2.findContours(comp_u8, cv2.RETR_LIST, cv2.CHAIN_APPROX_NONE)
    perim = float(sum(cv2.arcLength(c, True) for c in cnts))
    rough = perim * perim / (4.0 * np.pi * area)

    bx, by = int(stats[i, cv2.CC_STAT_LEFT]), int(stats[i, cv2.CC_STAT_TOP])
    return {"comp": int(i),
            "bbox": [bx, by, bx + int(stats[i, cv2.CC_STAT_WIDTH]),
                     by + int(stats[i, cv2.CC_STAT_HEIGHT])],
            "areaFrac": round(area / g.size, 4), "figFrac": round(fig_frac, 3),
            "thinFrac": round(thin_frac, 3), "chroma": round(chroma, 1),
            "eatenFrac": round(eaten_frac, 4), "protectFrac": round(protect_frac, 4),
            "textCov": round(text_cov, 3), "textOn": round(text_on, 3),
            "rough": round(rough, 1)}


def _fig_gates(met):
    """安全網兩條路共用的硬門裡沒過的（前景太少 figlo／太多 fighi、細碎 thin、彩度 chroma、被吃前景白硬上限 eatenH）。"""
    gates = []
    if met["figFrac"] < STICKER_FIG_MIN:
        gates.append("figlo")
    if met["figFrac"] > STICKER_FIG_MAX:
        gates.append("fighi")
    if met["thinFrac"] > STICKER_THIN_MAX:
        gates.append("thin")
    if met["chroma"] > STICKER_CHROMA_MAX:
        gates.append("chroma")
    if met["eatenFrac"] > STICKER_EATEN_HARD:
        gates.append("eatenH")
    return gates


def _weak_gates(met):
    """弱貼框／不貼框那條路（含無框頁與「更多」C3 的留白候選）沒過的門：硬門 ＋ 文字窗 textCov ＋ 小元件無語意證據 small。"""
    gates = _fig_gates(met)
    if not (met["textCov"] <= STICKER_TEXT_MAX or met["areaFrac"] >= 0.02):
        gates.append("textCov")
    if not (met["areaFrac"] >= STICKER_SMALL_AREA or met["textOn"] >= STICKER_TEXT_BG_MIN):
        gates.append("small")
    return gates


def sticker_plan(g, img_bgr, lab, stats, gutter_ids, panel_ids, frameless, regions):
    """挑修法4 目標元件並過安全網。回傳 (accept_ids, audit)。

    目標＝「不承載調子的純白背景」：有框頁＝修法3 的格內白（panel_ids）；
    frameless 頁＝貼頁邊白元件（修法3 只在乎有框頁的 gutter/panel 之分，這裡
    兩類都收）中整頁佔比 ≥ STICKER_MIN_FRAC 的大面積背景。
    安全網（任一不過＝不進 accept ⇒ 該元件維持上輪行為：有框頁 panelwhite＝
    D2 壓暗；frameless＝背景保留，絕不毀畫面）：
      chroma  > CHROMA_MAX ＝淡彩/彩頁底（灰階 ≥235 但非純白）→ 不動；
      textCov > TEXT_MAX   ＝其實是漏併的氣泡（40px 窗量氣泡構形）；
      figFrac 出界 / thinFrac 過高 ＝ 沒分出前景 或 背景本身破碎；
      eaten   > EATEN_HARD ＝細碎過半、分離無意義，一律退回；
      eaten   > EATEN_MAX 且 textOn < TEXT_BG_MIN ＝疑有前景白連進背景、又無
              「作者把它當背景寫字」的語意證據（ch34_006 白鬍老人格）→ 退回；
              textOn ≥ TEXT_BG_MIN 放行（demo06 教堂速寫底 0.167）；
      areaFrac < SMALL_AREA 且 textOn < TEXT_BG_MIN ＝小元件又無文字語意證據
              → 退回（真格背景白都大；平滑的前景衣料白 eaten 量不到，
              ch34_010 右下格披肩 0.0105/textOn 0.023 走這條退回）。
    語意證據一律用 textOn（tight bbox+TEXTON_PAD）：文字要「真的壓在這個白
    元件上」才算數；textCov 的 40px 窗會把鄰格文字掃進相鄰元件湊假證據。
    審計每筆另記 path（strong／weak：走哪條路）與 gates（實際沒過的門，空＝收下；「更多」A2 的 C2 候選看這個）。
    eaten 沒爆但局部聚團（白鬍臉這型）＝ paint_sticker 的區域級保護處理
    （該團塊不填黑、留 D2），不整顆退回——見 _sticker_protect 常數註記。
    """
    H, W_ = g.shape
    def _rects(pad):
        return [(max(0, r["bbox"][0] - pad), max(0, r["bbox"][1] - pad),
                 min(W_ - 1, r["bbox"][2] + pad), min(H - 1, r["bbox"][3] + pad))
                for r in regions]
    text_rects = _rects(BUBBLE_PAD)            # textCov：漏併氣泡閘（窗語意）
    text_rects_on = _rects(STICKER_TEXTON_PAD)  # textOn：語意證據（壓在元件上）
    if frameless:
        cand = {i for i in (gutter_ids | panel_ids)
                if stats[i, cv2.CC_STAT_AREA] >= STICKER_MIN_FRAC * g.size}
        hug = {}
        hug_cut = {}
    else:
        cand = set(panel_ids)
        # 修法5：閉合格背景擢升——未列管（非 gutter 非 panel）的大白元件，若「貼格線長度 /
        # bbox 周長」夠高＝背景沿格框跑（閉合格內的天空/空白背景被格線封住、永遠進不了
        # 修法3 的 gutter/panel 分類），擢升進候選、照走下面同一套安全網。前景白（臉/白衣）
        # 是獨立元件、只點狀碰框 → hug 低、天然不擢升。ch34_014 型（22% 頁面積）的主修。
        lh, lv = frame_line_mask(g)
        frame = (lh | lv).astype(np.uint8)
        kd = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (FRAME_HUG_DILATE * 2 + 1,) * 2)
        hug = {}
        hug_cut = {}
        min_area = GUTTER_MIN_AREA_FRAC * g.size
        listed = gutter_ids | panel_ids
        for i in range(1, stats.shape[0]):
            if i in listed or stats[i, cv2.CC_STAT_AREA] < min_area:
                continue
            x, y, w_, h_ = (stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP],
                            stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT])
            pad = FRAME_HUG_DILATE + 1
            x0, y0 = max(0, x - pad), max(0, y - pad)
            x1, y1 = min(g.shape[1], x + w_ + pad), min(g.shape[0], y + h_ + pad)
            comp = (lab[y0:y1, x0:x1] == i).astype(np.uint8)
            touch = (cv2.dilate(comp, kd) & frame[y0:y1, x0:x1]) > 0
            contact = int(touch.sum())
            hug_len = contact / FRAME_HUG_THICK
            frac = hug_len / max(1.0, 2.0 * (w_ + h_))
            hug[i] = round(float(frac), 3)
            if True:                    # 四邊各自的貼框覆蓋（用來認出「被格框截斷」）
                cy, cx = np.nonzero(touch)
                ry = (cy + y0 - y) / max(1, h_)
                rx = (cx + x0 - x) / max(1, w_)
                t = float((ry < 0.15).sum()) / FRAME_HUG_THICK / max(1, w_)
                bt = float((ry > 0.85).sum()) / FRAME_HUG_THICK / max(1, w_)
                lf = float((rx < 0.15).sum()) / FRAME_HUG_THICK / max(1, h_)
                rt = float((rx > 0.85).sum()) / FRAME_HUG_THICK / max(1, h_)
                hug_cut[i] = (max(t, bt) < HUG_SIDE_MIN or max(lf, rt) < HUG_SIDE_MIN)
            if frac >= FRAME_HUG_MIN:
                cand.add(i)
    accept, audit, promoted = set(), [], set()
    for i in sorted(cand):
        met = sticker_metrics(g, img_bgr, lab, stats, i, text_rects, text_rects_on)
        if i in hug:
            met["frameHug"] = hug[i]
        if hug.get(i, 0.0) >= FRAME_HUG_STRONG:
            # 修法5 兩級制——強貼框（背景證據極強）走放寬門：
            # · 跳過 textCov（pad40 窗對大背景是鄰泡污染；真文字壓上用 textOn 擋）
            # · 跳過 eaten 中段門與小面積門（窄頸類危險交給批1 核心填色的幾何保護）
            # · 保留 fig/thin/chroma/eatenHARD 四道硬底線
            # · 批1 淡色門：F 像素中淡色佔比高＝群眾/建築淡速寫背景，填黑會變漂浮碎片
            #   → 推遲批2（ch34_014 底格群眾實測 faintOfF 高、中排乾淨背景低）
            x_, y_, w2, h2 = (stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_TOP],
                              stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT])
            sub_g = g[y_:y_ + h2, x_:x_ + w2]
            fpx = sub_g[sub_g < WHITE_TH]
            met["faintOfF"] = round(float((fpx > FAINT_G).mean()), 3) if fpx.size else 0.0
            ok = (STICKER_FIG_MIN <= met["figFrac"] <= STICKER_FIG_MAX
                  and met["thinFrac"] <= STICKER_THIN_MAX
                  and met["chroma"] <= STICKER_CHROMA_MAX
                  and met["eatenFrac"] <= STICKER_EATEN_HARD
                  and met["textOn"] <= PROMOTED_TEXTON_MAX
                  and met["faintOfF"] <= FAINT_OF_F_MAX
                  # 截斷型（只貼一對相對邊）的小元件＝被格框切斷的前景物件，不是格背景
                  and not (hug_cut.get(i, False)
                           and met["areaFrac"] < STICKER_SMALL_AREA
                           and met["textOn"] < STICKER_TEXT_BG_MIN)
                  )
            gates = _fig_gates(met)
            if met["textOn"] > PROMOTED_TEXTON_MAX:
                gates.append("textOnP")
            if met["faintOfF"] > FAINT_OF_F_MAX:
                gates.append("faintF")
            if hug_cut.get(i, False) and met["areaFrac"] < STICKER_SMALL_AREA and met["textOn"] < STICKER_TEXT_BG_MIN:
                gates.append("hugCut")
            met["path"] = "strong"
            if ok:
                promoted.add(i)
        else:
            textcov_ok = (met["textCov"] <= STICKER_TEXT_MAX
                          or met["areaFrac"] >= 0.02)
            eaten_mid_ok = (met["eatenFrac"] <= STICKER_EATEN_MAX
                            or met["textOn"] >= STICKER_TEXT_BG_MIN)
            ok = (STICKER_FIG_MIN <= met["figFrac"] <= STICKER_FIG_MAX
                  and met["thinFrac"] <= STICKER_THIN_MAX
                  and met["chroma"] <= STICKER_CHROMA_MAX
                  and textcov_ok
                  and met["eatenFrac"] <= STICKER_EATEN_HARD

                  and (met["areaFrac"] >= STICKER_SMALL_AREA
                       or met["textOn"] >= STICKER_TEXT_BG_MIN))
            gates = _weak_gates(met)
            met["path"] = "weak"
            if ok and not eaten_mid_ok and not frameless and i in panel_ids:
                # E1：中段 eaten 的 panel 白改走核心填色（格框種子、切窄頸）+ 區域級保護，不整顆拒
                promoted.add(i)
            if ok and i in hug:
                # 弱貼框（0.25–0.40）擢升元件過原門後也走核心填色——當初只給強貼框，弱貼框整顆填，
                # demo01 主角臉（hug 0.327、textOn 0.289 走 eaten 逃生門放行）就是這樣被塗黑的。
                # 擢升元件一律核心填色：它們本來就是「不與留白連通、只靠貼框證據」的不確定背景。
                promoted.add(i)
        # 實際沒過的門（「更多」A2 的候選看這個；與 ok 同一份判定，斷言守著兩者不分家）
        assert ok == (not gates), (i, ok, gates)
        met["gates"] = sorted(gates)
        met["accept"] = bool(ok)
        audit.append(met)
        if ok:
            accept.add(i)
            if not frameless:
                # 安全策略：panel 白也走核心填色（格框種子、切窄頸、厚墨灰暈），不整顆填。
                # 守護框歸因：貼紙單獨 12 框違規、7 是白髮＝整顆填把連進背景的髮絲白吃掉。
                promoted.add(i)
    return accept, audit, promoted


def filter_sticker_plan(g, lab, stats, accept, audit, promoted, char_raw, frame, mode=None, rough_max=None,
                        min_frac=None, mark_audit=True):
    """背景填黑三檔（STICKER_MODE）：在 sticker_plan 的安全網之後再挑一次，決定哪些白元件真的填黑。
    回傳 (accept, promoted)；promoted 只留仍在 accept 內的（擢升元件落選＝連核心填色也不做）。

    plain(i)＝「無畫面背景」：元件不碰 char_raw（模型原輸出人物遮罩、未收邊——三檔實驗就是這樣量的）、且外圈
    （dilate 橢圓 (2·STICKER_PLAIN_RING_R+1)² − 元件）非空、且外圈上「非格線的墨」（g < INK_DARK_TH 且不在格線
    外擴 STICKER_PLAIN_FRAME_DIL² 內）的佔比 < STICKER_PLAIN_ART_MAX、「淡線稿」（INK_DARK_TH ≤ g < WHITE_TH、不在
    格線外擴內、不在暗墨 STICKER_PLAIN_FAINT_HALO² 外擴內）的佔比 < STICKER_PLAIN_FAINT_MAX ⇒ 邊界只碰格線／頁邊、
    沒碰線稿（雲、效果線這類淡線也是線稿）。
      all   ：keep＝accept（預設完整管線，不動）
      plain ：keep＝{plain}（L1）
      simple：keep＝{plain} ∪ {audit 的 rough ≤ STICKER_ROUGH_MAX 且 areaFrac ≥ STICKER_SIMPLE_MIN_FRAC}（L2／L3）
    落選的元件回到 sticker_plan 落選時的待遇（有框頁 panel 白＝場景調壓暗；frameless＝背景保留），絕不會比原圖糟。
    audit 每筆加 keep 欄（accept 是安全網的判定、keep 是檔位的最終決定）。
    """
    accept = set(accept)
    mode = STICKER_MODE if mode is None else mode
    rough_max = STICKER_ROUGH_MAX if rough_max is None else rough_max
    min_frac = STICKER_SIMPLE_MIN_FRAC if min_frac is None else min_frac
    if mode == "all":
        keep = accept
    else:
        met = {int(a["comp"]): a for a in audit}
        kr = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * STICKER_PLAIN_RING_R + 1,) * 2)
        fd = cv2.dilate(np.asarray(frame).astype(np.uint8),
                        np.ones((STICKER_PLAIN_FRAME_DIL,) * 2, np.uint8)) > 0
        art = (g < INK_DARK_TH) & ~fd                       # 非格線的墨＝線稿
        halo = cv2.dilate((g < INK_DARK_TH).astype(np.uint8),
                          np.ones((STICKER_PLAIN_FAINT_HALO,) * 2, np.uint8)) > 0
        faint = (g >= INK_DARK_TH) & (g < WHITE_TH) & ~fd & ~halo   # 淡線稿：雲／效果線／淡網點（不是暗線的暈）
        keep = set()
        for i in accept:
            comp = lab == i
            if not comp[char_raw].any():                    # 不碰人物
                ring = (cv2.dilate(comp.astype(np.uint8), kr) > 0) & ~comp
                if (ring.any() and float(art[ring].mean()) < STICKER_PLAIN_ART_MAX
                        and float(faint[ring].mean()) < STICKER_PLAIN_FAINT_MAX):
                    keep.add(i)                             # plain：外圈只碰格線／頁邊（暗墨、淡線稿都沒碰）
                    continue
            if mode == "simple":
                a = met.get(int(i))
                if a is not None and a["rough"] <= rough_max and a["areaFrac"] >= min_frac:
                    keep.add(i)
    if mark_audit:
        for a in audit:
            a["keep"] = int(a["comp"]) in keep
    return keep, set(promoted) & keep


# ── 「更多」新規則 A2（MORE_RULE；在檔位 keep 上只加不減）────────────────────

def more_explained(g, bubble, seg, frame):
    """A2 的「交代過」遮罩（整頁、與檔位無關）。回傳 (fd, bs, bs0)：
    fd ＝格線方核外擴 MORE_EXPLAIN_DIL；bs ＝泡 ∪ 泡框線（泡外 BUBBLE_OUTLINE_DIST 內的非白）∪ 字（seg 方核外擴）；
    bs0 ＝同 bs 但不含泡框線（S3 外圈淡色用）。泡＝人物修剪前的泡（含封縫救回的）。"""
    k = np.ones((MORE_EXPLAIN_DIL,) * 2, np.uint8)
    fd = cv2.dilate(np.asarray(frame).astype(np.uint8), k) > 0
    ko = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * BUBBLE_OUTLINE_DIST + 1,) * 2)
    bub = bubble | ((cv2.dilate(bubble.astype(np.uint8), ko) > 0) & (g < WHITE_TH))
    segd = cv2.dilate(seg.astype(np.uint8), k) > 0
    return fd, bub | segd, bubble | segd


def more_gutter_candidates(g, img_bgr, lab, stats, gutter_ids, skip, regions):
    """C3：有框頁的頁邊留白元件，若把格內白的判準全部放寬 MORE_HYST（厚芯半徑 CORE_R×(1−H)、厚芯佔比、深入、
    包墨深入與包墨比都 ×(1−H)）後算格內白，也當候選（包含式遲滯：厚芯半徑是絕對 px、深入的頁邊帶是相對值，縮放會翻）。
    回傳 {元件: 安全網量測}（sticker_metrics ＋ gates／path／gut）；skip＝已在 keep 或已是候選的元件。"""
    H, W = g.shape
    min_area = GUTTER_MIN_AREA_FRAC * g.size
    todo = [i for i in sorted(gutter_ids) if i not in skip and stats[i, cv2.CC_STAT_AREA] >= min_area]
    if not todo:
        return {}
    def _rects(pad):
        return [(max(0, r["bbox"][0] - pad), max(0, r["bbox"][1] - pad),
                 min(W - 1, r["bbox"][2] + pad), min(H - 1, r["bbox"][3] + pad)) for r in regions]
    tr, tro = _rects(BUBBLE_PAD), _rects(STICKER_TEXTON_PAD)
    deep_px = max(64, int(round(DEEP_EDGE_FRAC * min(W, H))))
    dist = cv2.distanceTransform((g >= WHITE_TH).astype(np.uint8), cv2.DIST_L2, 5)   # 同 classify_white_components
    lo = 1 - MORE_HYST
    out = {}
    for i in todo:
        x, y, w, h = [int(v) for v in stats[i, :4]]
        comp = lab[y:y + h, x:x + w] == i
        core = comp & (dist[y:y + h, x:x + w] > CORE_R * lo)
        if not core.any():
            continue
        cf = core.sum() / max(1, comp.sum())
        ys, xs = np.nonzero(core)
        xs = xs + x
        ys = ys + y
        ed = np.minimum(np.minimum(xs, W - 1 - xs), np.minimum(ys, H - 1 - ys))
        cdeep = float((ed > deep_px).mean())
        inp = cf >= IN_PANEL_CORE_FRAC * lo and cdeep >= IN_PANEL_CORE_DEEP * lo
        if not inp and cdeep >= DEEP_INK_DEEP * lo:
            inp = _hole_ink_ratio((lab == i).astype(np.uint8), g) >= DEEP_INK_RATIO * lo
        if not inp:
            continue
        m = sticker_metrics(g, img_bgr, lab, stats, i, tr, tro)
        m.update(gates=sorted(_weak_gates(m)), path="weak", gut=True, accept=False, keep=False)
        out[i] = m
    return out


def more_features(g, lab, stats, i, charmask, char_raw, bs, bs0, fd):
    """單一候選元件的 A2 特徵（都在元件 bbox 外擴 MORE_WIN_PAD 的窗內量；窗外的像素碰不到量測）：
      rfs  ＝外輪廓自由邊界：補洞、中值 MORE_MEDIAN 平滑後的 1 px 內邊界，扣掉交代過的（人物、泡＋泡框線、字、格線，
             再方核外擴 MORE_NEAR_DIL）剩下的長度 L，L²/(4π·面積)（像素級鋸齒與 JPEG 雜點不算）
      rfi  ＝內部記號：元件的洞（8 連通塊）裡「真的有畫東西」的（面積 ≥ MORE_HOLE_MIN_AREA 或最暗 < MORE_HOLE_DARK_MAX），
             其 1 px 內邊界扣掉交代過的長度，同式
      fnc  ＝外圈淡色：外圈（dilate 橢圓 (2·MORE_RING_R+1)² − 元件）扣人物（收邊後）之後，不在泡（不含框線）／字／格線、
             不在暗墨暈裡、INK_DARK_TH ≤ g < WHITE_TH 的佔比
      charf＝人物原輸出（char_raw）佔元件
    rfs／rfi 四捨五入到 2 位、fnc 到 3 位（拿四捨五入後的值比門檻）。回傳 dict。"""
    H, W = g.shape
    x, y, w, h = [int(v) for v in stats[i, :4]]
    pad = MORE_WIN_PAD
    x0, y0, x1, y1 = max(0, x - pad), max(0, y - pad), min(W, x + w + pad), min(H, y + h + pad)
    sl = (slice(y0, y1), slice(x0, x1))
    unit = lab[sl] == i
    gs = g[sl]
    area = max(1, int(unit.sum()))
    k3 = np.ones((3, 3), np.uint8)
    near = cv2.dilate((charmask[sl] | bs[sl] | fd[sl]).astype(np.uint8), np.ones((MORE_NEAR_DIL,) * 2, np.uint8)) > 0
    filled = _fill_holes(unit)
    holes = filled & ~unit
    sm = cv2.medianBlur(filled.astype(np.uint8) * 255, MORE_MEDIAN) > 127
    ob = sm & ~(cv2.erode(sm.astype(np.uint8), k3) > 0)
    l_out = int((ob & ~near).sum())
    n, lb, st, _ = cv2.connectedComponentsWithStats(holes.astype(np.uint8), 8)
    l_in = 0
    if n > 1:
        mn = np.full(n, 255, np.int32)
        np.minimum.at(mn, lb.ravel(), gs.ravel().astype(np.int32))
        real = np.zeros(n, bool)
        real[1:] = (st[1:, cv2.CC_STAT_AREA] >= MORE_HOLE_MIN_AREA) | (mn[1:] < MORE_HOLE_DARK_MAX)
        hm = real[lb]
        hb = hm & ~(cv2.erode(hm.astype(np.uint8), k3) > 0)
        l_in = int((hb & ~near).sum())
    rfs = l_out ** 2 / (4 * np.pi * area)
    rfi = l_in ** 2 / (4 * np.pi * area)
    kr = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * MORE_RING_R + 1,) * 2)
    ring = (cv2.dilate(unit.astype(np.uint8), kr) > 0) & ~unit
    nc = ring & ~charmask[sl]
    left = nc & ~bs0[sl] & ~fd[sl]
    halo = cv2.dilate((gs < INK_DARK_TH).astype(np.uint8), np.ones((MORE_HALO,) * 2, np.uint8)) > 0
    faint = left & ~halo & (gs >= INK_DARK_TH) & (gs < WHITE_TH)
    fnc = float(faint.sum()) / max(1, int(nc.sum()))
    charf = float(char_raw[sl][unit].mean())
    return dict(rfs=round(rfs, 2), rfi=round(rfi, 2), fnc=round(fnc, 3), charf=charf,
                lOut=l_out, lIn=l_in, area=area)


def more_rule(g, img_bgr, lab, stats, accept0, promoted0, keep, audit, charmask, char_raw, bubble, seg, frame,
              frameless, regions, gutter_ids, panel_ids):
    """「更多」A2：在檔位 keep 上只加不減。回傳 (keep2, promoted2)；audit 每個候選加 more 欄（src／特徵／ok／why），
    C3 的留白候選併進 audit（gut=True）。promoted2＝(安全網的擢升 ∪ 新加且走核心填色的) ∩ keep2：有框頁新加的一律
    核心填色；無框頁只有 frameHug／留白候選會（實際上無框頁兩者都不會出現 ⇒ 整顆填）。"""
    keep = set(keep)
    met = {int(a["comp"]): a for a in audit}
    cand = {}
    for i in sorted(set(accept0) - keep):
        cand[int(i)] = "acc"                       # C1：安全網收下、檔位沒收
    for a in audit:
        i = int(a["comp"])
        if a["accept"] or i in keep or i in cand:
            continue
        cand[i] = "rej"                            # C2：安全網拒收（只卡在軟門的才繼續）
    if not frameless:
        for i, m in more_gutter_candidates(g, img_bgr, lab, stats, gutter_ids, keep | set(cand), regions).items():
            audit.append(m)
            met[i] = m
            cand[i] = "gut"                        # C3：門檻邊上的頁邊留白
    if not cand:
        return keep, set(promoted0) & keep
    fd, bs, bs0 = more_explained(g, bubble, seg, frame)
    hug_lo = FRAME_HUG_MIN * (1 + MORE_HYST)
    strong_lo, strong_hi = FRAME_HUG_STRONG * (1 - MORE_HYST), FRAME_HUG_STRONG * (1 + MORE_HYST)
    add, prom = set(), set(promoted0)
    for i, src in sorted(cand.items()):
        a = met[i]
        hug = a.get("frameHug")
        listed = (i in panel_ids) or (i in gutter_ids) or frameless
        if hug is not None and not listed and hug < hug_lo:
            a["more"] = dict(src=src, skip="hug")  # 只靠貼框才成為候選的：貼框要穩穩過門檻（保守式遲滯）
            continue
        gs = set(a["gates"]) if src in ("rej", "gut") else set()
        if src == "rej" and not gs:
            continue
        if gs and not gs <= MORE_SOFT_GATES:
            a["more"] = dict(src=src, skip="gates")
            continue
        if gs:
            soft = True
        else:
            # 強／弱貼框兩條路的軟門不同（強：字壓 > 0.3 拒；弱：文字窗 > 0.55 且小 拒），貼框在 0.4 上下會換路。
            # 遲滯帶內兩條路的軟門都要看（保守式）：碰到任一道就當「軟門元件」，要過 rough 上限。
            h_ = hug if hug is not None else 0.0
            soft = ((h_ >= strong_lo and a["textOn"] > PROMOTED_TEXTON_MAX)
                    or (h_ < strong_hi and a["textCov"] > STICKER_TEXT_MAX and a["areaFrac"] < 0.02))
        f = more_features(g, lab, stats, i, charmask, char_raw, bs, bs0, fd)
        why = []
        if f["rfs"] > MORE_T_OUT:
            why.append("out")
        if f["rfi"] > MORE_T_IN:
            why.append("in")
        if f["fnc"] > MORE_FAINT_MAX:
            why.append("faint")
        if soft and a["rough"] > MORE_ROUGH_CAP:
            why.append("rough")
        if f["charf"] >= MORE_CHAR_MAX:
            why.append("char")
        ok = not why
        a["more"] = dict(src=src, soft=bool(soft), ok=ok, why="+".join(why), **f)
        if ok:
            add.add(i)
            if not frameless or "frameHug" in a or src == "gut":
                prom.add(i)
    keep2 = keep | add
    return keep2, prom & keep2


def thick_ink_aura(g, r=PB_AURA_R, thick=PB_AURA_THICK, min_area=PB_AURA_MIN_AREA, seg=None):
    """厚墨灰暈遮罩：髮團/臉部深色特徵這類「厚」墨塊（距離變換最大值 ≥ thick、面積 ≥ min_area）
    周圍 r px。字框/氣泡輪廓/格線是細筆畫、不算厚墨。偽泡與貼紙核心填色共用＝臉旁的白不准填。

    ⚠️ [seg]＝文字筆畫遮罩，**必須傳**（能拿到時）：粗體字的筆畫本身就厚（距離變換 ≥6px），
    不扣掉會被當成髮團 → 在每個字周圍挖出 r px 的洞、洞停在場景灰 ⇒ 深底氣泡裡出現貼著字的
    灰直條/矩形（使用者 2026-09-14 回報「文字之間出現灰底」的真凶）。字不是人物，扣掉才對。
    """
    ink = (g < WHITE_TH).astype(np.uint8)
    if seg is not None:
        ink[seg] = 0                                   # 文字筆畫不算「墨團」
    dt = cv2.distanceTransform(ink, cv2.DIST_L2, 3)
    n_i, lb_i, st_i, _ = cv2.connectedComponentsWithStats(ink, 8)
    thick_ids = np.zeros(n_i, bool)
    for i in range(1, n_i):
        if st_i[i, cv2.CC_STAT_AREA] >= min_area:
            x, y, w2, h2 = st_i[i, :4]
            if dt[y:y + h2, x:x + w2][lb_i[y:y + h2, x:x + w2] == i].max() >= thick:
                thick_ids[i] = True
    thick_m = thick_ids[lb_i]
    if not thick_m.any():
        return np.zeros_like(thick_m)
    ka = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (r * 2 + 1,) * 2)
    return cv2.dilate(thick_m.astype(np.uint8), ka) > 0


def geodesic_grow(seed, within, iters, step=5):
    """測地生長：seed 在 within 內反覆 3×3 膨脹 iters 次（≈ 距離 px）。step 批次化省時。"""
    k = np.ones((3, 3), np.uint8)
    cur = (seed & within).astype(np.uint8)
    w8 = within.astype(np.uint8)
    done = 0
    while done < iters:
        n = min(step, iters - done)
        cur = cv2.dilate(cur, k, iterations=n) & w8
        done += n
    return cur > 0


def broad_core_fill(comp, seeds, neck_r=CORE_NECK_R, recover_r=CORE_RECOVER_R):
    """批1 核心填色：comp（白元件）先開運算切窄頸 → 只留寬闊區；由 seeds 所在的
    寬闊連通塊出發（臉/白衣經細縫連入背景 → 在頸口被切開、到不了）；最後往墨線邊
    做小半徑測地回收（貼合線稿、不留白圈）。回傳實際要填的遮罩。"""
    ku = comp.astype(np.uint8)
    ko = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * neck_r + 1,) * 2)
    core = cv2.morphologyEx(ku, cv2.MORPH_OPEN, ko)
    n, lb = cv2.connectedComponents(core, 8)
    ids = np.unique(lb[seeds & (core > 0)])
    ids = ids[ids > 0]
    if ids.size == 0:
        return np.zeros_like(comp, bool)
    filled = np.isin(lb, ids)
    return geodesic_grow(filled, comp, recover_r, step=3)


def _edge_seed_fallback(comp, fr, kd, x0, y0, H, W):
    """P2：comp（窗內）以 CORE_NECK_R 開運算後的 8 連通核心塊裡，碰不到格框種子（fr 橢圓外擴 FRAME_HUG_DILATE ∩ comp）
    的塊，其外擴 CORE_NECK_R+MORE_EDGE_REACH 內的頁緣帶（距頁緣 < MORE_EDGE_BAND）。回傳窗內遮罩（可能全空）。"""
    ko = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * CORE_NECK_R + 1,) * 2)
    core = cv2.morphologyEx(comp.astype(np.uint8), cv2.MORPH_OPEN, ko)
    n, lb = cv2.connectedComponents(core, 8)
    sf = (cv2.dilate(fr.astype(np.uint8), kd) > 0) & comp
    has = np.zeros(n, bool)
    has[np.unique(lb[sf & (core > 0)])] = True
    lack = (lb > 0) & ~has[lb]
    if not lack.any():
        return np.zeros_like(comp)
    yy, xx = np.mgrid[y0:y0 + comp.shape[0], x0:x0 + comp.shape[1]]
    b = MORE_EDGE_BAND
    edge = (yy < b) | (xx < b) | (yy >= H - b) | (xx >= W - b)
    kn = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * (CORE_NECK_R + MORE_EDGE_REACH) + 1,) * 2)
    return edge & (cv2.dilate(lack.astype(np.uint8), kn) > 0)


def paint_sticker(out, g, lab, stats, accept, bubble, core_ids=(), frame=None, seg=None,
                  charmask=None, keep_dark=False, edge_fb=False, ring=None):
    """修法4 合成：W 填深、前景描白邊（dilate(F, r) ∩ W）、W 內孤立小噪點吞掉、
    eaten 聚團區域級保護（不填黑、原樣留 D2）。

    [ring]（灰圈收細，nightread_ring.py）給 dict 就把兩樣累加進去（整頁座標）：`withheld`＝核心填色裡只因人物安全邊
    （CORE_RELEASE_PAD）而沒填的（扣掉保護區）；`stk_band`＝實際畫上的前景描亮邊。不影響這裡的輸出。

    「更多」A2 的兩項繪製（MORE_RULE 的檔才開）：
      keep_dark：前景描亮邊不蓋掉此時已經 ≤ BG 的像素（留白／格溝／前一顆貼紙塗黑的）——P1；
      edge_fb  ：核心填色裡沒有格框種子的核心塊（開運算 CORE_NECK_R 後的 8 連通塊），頁緣（距頁緣 < MORE_EDGE_BAND）
                 落在該塊外擴 CORE_NECK_R+MORE_EDGE_REACH 內的部分也當種子（出血格沒有格框線可長）——P2；
                 有格框種子的塊不加（加了測地比的直線切邊會往下移，c362_017 肩旁階梯小黑楔）。

    F＝bbox 內非白（< WHITE_TH）且非氣泡的內容（墨線/調子/SFX）；被墨線封閉
    的白（臉/衣服）不與 W 連通、照 D2 保留，其輪廓墨線屬 F ⇒ 描邊自然沿輪廓。
    小噪點（面積 < FIG_NOISE_AREA 且被 comp 閉運算覆蓋＝孤懸 W 中）不描邊、
    直接併入背景填深；氣泡輪廓由 paint_bubbles 自己描，不在此重複。
    保護區（_sticker_protect：白鬍臉/密集髮絲/花窗速寫這型）整團不填不描，
    維持 D2 壓暗 ⇒ 前景白物件連進背景也吃不掉（ch34_006 白鬍老人格）。
    """
    H, W_ = g.shape
    r = int(np.clip(round(STROKE_OBJ_FRAC * min(H, W_)), STROKE_OBJ_MIN, STROKE_OBJ_MAX))
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    kc = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (FIG_NOISE_CLOSE,) * 2)
    for i in sorted(accept):
        x0, y0, x1, y1, sub, comp = _comp_window(g, lab, stats, i, r + 2)
        if i in core_ids and frame is not None:
            # 批1 核心填色（擢升元件）：只填「從格框種子出發、不擠過窄頸」的寬闊區。
            # 臉/白衣即使因線稿缺口與背景同元件，也在頸口被切斷 ⇒ 幾何保護、非門檻保護。
            fr = frame[y0:y1, x0:x1] > 0
            kd = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (FRAME_HUG_DILATE * 2 + 1,) * 2)
            if edge_fb:
                fr = fr | _edge_seed_fallback(comp, fr, kd, x0, y0, H, W_)
            seeds = (cv2.dilate(fr.astype(np.uint8), kd) > 0) & comp
            fill = broad_core_fill(comp, seeds)
            if fill.any():
                # 批1.5 測地比刪填：geo＝從格框種子在白域內走的距離；euc＝到格線的直線距離。
                # 背景 geo≈euc；衣料/皮膚要繞過人物墨線/氣泡障礙才到 → geo≫euc → 刪。
                # （人物殼 closing 版蓋不住寬開的外套白、已廢棄。）
                geo = np.full(comp.shape, np.float32(1e9))
                cur = (seeds & comp)
                k3 = np.ones((3, 3), np.uint8)
                d, STEP = 0, 6
                geo[cur] = 0
                while cur.any() and d < 4000:
                    grown = (cv2.dilate(cur.astype(np.uint8), k3, iterations=STEP) > 0) & comp
                    new_px = grown & (geo == 1e9)
                    d += STEP
                    if not new_px.any():
                        break
                    geo[new_px] = d
                    cur = grown
                fr8 = (~fr).astype(np.uint8)
                euc = cv2.distanceTransform(fr8, cv2.DIST_L2, 3)
                sub_seg = seg[y0:y1, x0:x1] if seg is not None else None
                strict = (fill & (geo <= GEO_RATIO_MAX * euc + GEO_SLACK)
                          & ~thick_ink_aura(sub, seg=sub_seg))   # 臉旁（髮團/深色特徵周圍）的白不填
                kg = cv2.getStructuringElement(cv2.MORPH_ELLIPSE,
                                               (CORE_RELEASE_PAD * 2 + 1,) * 2)
                guard = cv2.dilate(charmask[y0:y1, x0:x1].astype(np.uint8), kg) > 0
                if ring is not None:                    # 灰圈收細：核心區裡只因人物安全邊（guard）而沒填的
                    wh = fill & ~strict & guard
                    if wh.any():
                        wh &= ~_sticker_protect(_sticker_eaten(sub, comp), comp)
                        ring["withheld"][y0:y1, x0:x1] |= wh
                fill = strict | (fill & ~guard)   # 見 CORE_RELEASE_PAD：非人物的核心區照填
            if not fill.any():
                continue
        else:
            fill = comp
        f_raw = ((sub < WHITE_TH) & ~bubble[y0:y1, x0:x1]).astype(np.uint8)
        nn, ll, ss, _ = cv2.connectedComponentsWithStats(f_raw, 8)
        keep = np.zeros(nn, bool)
        if nn > 1:
            keep[1:] = ss[1:, cv2.CC_STAT_AREA] >= FIG_NOISE_AREA
        f_main = keep[ll]                               # 清完噪點的前景（描邊來源）
        fill_closed = cv2.morphologyEx(fill.astype(np.uint8), cv2.MORPH_CLOSE, kc) > 0
        noise = (ll > 0) & ~keep[ll] & fill_closed      # 孤懸填色區內的小噪點 → 併入背景
        protect = _sticker_protect(_sticker_eaten(sub, comp), comp)
        band = (cv2.dilate(f_main.astype(np.uint8), k) > 0) & fill & ~protect
        o = out[y0:y1, x0:x1]
        if keep_dark:
            band &= ~(o <= BG)                          # P1：描亮邊不蓋掉已經塗黑的（留白／格溝／前一顆貼紙）
        o[(fill | noise) & ~protect] = BG
        o[band] = STROKE_OBJ_V
        if ring is not None:                            # 灰圈收細：實際畫上的前景描亮邊
            ring["stk_band"][y0:y1, x0:x1] |= band
    return out


# ── 合成：場景曲線／留白／貼紙／氣泡／人物還原 ──────────────────────


# ── 場景曲線 ────────────────────────────────────────────────────────
# 使用者對 D2 的回饋＝「說不出的怪」：floor=30 全域抬黑 + g=0.55 凹曲線把暗部抬得特別兇
# （原墨 26 → 61），整頁發灰=「濁」。以下變體共同原則：黑保持黑（或近黑）、白仍壓 140，
# 動態範圍 110 → ~140。全部嚴格單調（保序鐵則不動）。

def lut_linear(floor=SCENE_FLOOR, ceil=DIM_CEIL):
    """全線性：y = floor + (ceil-floor)·x/255。黑→floor、白→ceil，相對關係完全保留。"""
    x = np.arange(256, dtype=np.float32) / 255.0
    return np.clip(floor + (ceil - floor) * x, 0, 255).astype(np.uint8)


def lut_scene():
    return lut_linear(SCENE_FLOOR)


def ink_line_mask(g, seg=None, bh_ksize=7, bh_gain=45.0, dark_lo=40, dark_hi=185):
    """軟性墨線遮罩 0..1：blackhat（細暗線構）×暗度權重 ∪ DBNet seg。
    實心黑塊內部為 0（只認邊緣細線 ⇒ 增亮不掉大塊黑的對比）。"""
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (bh_ksize, bh_ksize))
    bh = cv2.morphologyEx(g, cv2.MORPH_BLACKHAT, k).astype(np.float32)
    soft = np.clip(bh / bh_gain, 0.0, 1.0)
    soft *= np.clip((dark_hi - g.astype(np.float32)) / (dark_hi - dark_lo), 0.0, 1.0)
    if seg is not None:
        soft = np.maximum(soft, seg.astype(np.float32))
    return soft


def ink_glow(dimmed, ink_soft, strength=GLOW_STRENGTH, cap=GLOW_CAP, bg_thr=95, bg_sigma=8.0):
    """自適應墨線增亮：只在「局部背景偏暗」處把筆畫往亮拉，夾在 cap 之下
    （cap < 紙白位準 DIM_CEIL ⇒ 線永遠比紙暗、不反相）。"""
    out = dimmed.astype(np.float32)
    gain = np.float32(strength) * ink_soft
    bg = cv2.GaussianBlur(dimmed, (0, 0), bg_sigma).astype(np.float32)
    gain *= np.clip((bg_thr - bg) / bg_thr, 0.0, 1.0)
    lifted = np.minimum(out + gain, np.maximum(out, np.float32(cap)))
    return np.clip(lifted, 0, 255).astype(np.uint8)


def scene_final(g, seg):
    """畫面區最終處理：場景曲線 LUT（NIGHTREAD_CURVE 選、預設 d2）+ 自適應墨線增亮。"""
    return ink_glow(lut_scene()[g], ink_line_mask(g, seg))


def ink_alpha(g, gain):
    """原圖墨度（1-亮度）×gain 夾 [0,1]：把墨線「轉亮」時的 alpha（邊緣抗鋸齒）。"""
    return np.clip((1.0 - g.astype(np.float32) / 255.0) * gain, 0.0, 1.0)


# ── 線稿密度否決：有線稿的白不是留白 ───────────────────────────────────────
# 判準＝真留白是空的，格內背景有窗格線／牆面陰影／網點；這是唯一能分開「頁邊白」與「有畫的
# 背景剛好連到頁邊」的訊號（幾何方法對 demo01 第 1 格拱門 53.3% → 53.3%，一個像素動不了）。
# 與第一版（experiments/fixes_ABC_20260918.patch）的差別，三條都是衝著它的病來的：
#   ① 只在「要填的像素」外擴一個窗的範圍內算，不掃全頁（第一版 +1s/頁）；
#   ② 離氣泡 GT2_BUBDIL 內的像素**永不否決**（第一版是把泡從線稿裡扣掉，仍有三處灰暈殘留）；
#   ③ 每個格框區域只在（區 ∩ 要填像素）的 bbox 內算，不是整個區域的 bbox。
GT2_TH = 0.10       # 逐像素密度 ≥ 此進候選塊
GT2_WIN = 31        # 量測窗邊長
GT2_PAD = 4         # 否決區外擴（密度在紋路邊緣先衰減，不外擴會沿線稿殘留黑條）
GT2_MIN = 1500      # 否決塊最小面積（碎塊不否決）
GT2_FRDIL = 9       # 格框線外擴（框線本身不算線稿）
GT2_SEGDIL = 21     # 文字筆畫外擴（字不算線稿）
GT2_BUBDIL = 25     # 泡外這麼多 px 內永不否決
GT2_REGMIN = 0.002  # 參與量測的格框區域最小頁佔比
GT2_HI = 0.15       # 候選塊的平均密度 ≥ 此才整塊否決（逐像素會在門檻附近斑掉）
GT2_CLOSE = 15      # 否決塊閉合半徑（補洞，去斑）
GT2_BUBCONTENT = 8  # 泡輪廓外擴這麼多 px 不算線稿，且泡當區域隔板


def texture_veto2(fill, g, frame, seg, bubble, exclude=None):
    """把「有線稿」的塊從留白填色裡剔掉。回傳新的 fill。[exclude]＝不算線稿的像素（灰圈收細拿人物遮罩重算一次用）。"""
    if not np.any(fill):
        return fill
    H, W = g.shape
    k = GT2_WIN
    fr = np.zeros((H, W), np.uint8) if frame is None else (np.asarray(frame) > 0).astype(np.uint8)
    if fr.any():
        fr = cv2.dilate(fr, np.ones((GT2_FRDIL,) * 2, np.uint8))
    # 候選＝要填、且離泡夠遠。泡附近永不否決 ⇒ 泡輪廓的密度再高也製造不出灰暈。
    bubnear = np.zeros((H, W), bool)
    if bubble is not None and np.any(bubble):
        bubnear = cv2.dilate(np.asarray(bubble).astype(np.uint8), np.ones((GT2_BUBDIL,) * 2, np.uint8)) > 0
    cand = fill & ~bubnear
    if not cand.any():
        return fill
    ys, xs = np.nonzero(cand)
    ry0, ry1 = max(0, int(ys.min()) - k), min(H, int(ys.max()) + k + 1)
    rx0, rx1 = max(0, int(xs.min()) - k), min(W, int(xs.max()) + k + 1)
    # ── 以下全在 ROI 內 ──
    gs = g[ry0:ry1, rx0:rx1]
    frbs = fr[ry0:ry1, rx0:rx1] > 0
    cands = cand[ry0:ry1, rx0:rx1]
    content = (gs < WHITE_TH) & ~frbs
    if seg is not None:
        content &= ~(cv2.dilate(seg[ry0:ry1, rx0:rx1].astype(np.uint8),
                                np.ones((GT2_SEGDIL,) * 2, np.uint8)) > 0)
    if exclude is not None:
        content &= ~exclude[ry0:ry1, rx0:rx1]
    barrier = frbs
    if bubble is not None and np.any(bubble):
        # 泡的輪廓線不算線稿：不扣的話它會從泡外 25～40px 的窗裡被看到，把頁邊窄條／泡尾旁的
        # 空白判成「有畫」——ch34_014 左頁邊那條灰帶、右下大泡尾巴的灰楔都是它。
        bub_d = cv2.dilate(np.asarray(bubble)[ry0:ry1, rx0:rx1].astype(np.uint8),
                           np.ones((GT2_BUBCONTENT * 2 + 1,) * 2, np.uint8)) > 0
        content &= ~bub_d
        # 泡壓在框線上會把框線斷開一個泡那麼寬的缺口，頁邊條和格子內部就連成同一區，格內
        # 網點的密度滲到頁邊條上（ch34_014 右下泡尾下方那塊）。泡裡沒有線稿，當隔板無損。
        barrier = frbs | bub_d
    cf = content.astype(np.float32)
    n, rlab, rst, _ = cv2.connectedComponentsWithStats((~barrier).astype(np.uint8), 8)
    dens = np.zeros(cands.shape, np.float32)
    cy, cx = np.nonzero(cands)
    cl = rlab[cy, cx]
    min_area = GT2_REGMIN * g.size
    for i in np.unique(cl):
        if i == 0 or int(rst[i, cv2.CC_STAT_AREA]) < min_area:
            continue
        sel = cl == i
        y0, y1 = max(0, int(cy[sel].min()) - k), min(dens.shape[0], int(cy[sel].max()) + k + 1)
        x0, x1 = max(0, int(cx[sel].min()) - k), min(dens.shape[1], int(cx[sel].max()) + k + 1)
        reg = (rlab[y0:y1, x0:x1] == i).astype(np.float32)
        num = cv2.boxFilter(cf[y0:y1, x0:x1] * reg, -1, (k, k), normalize=True,
                            borderType=cv2.BORDER_CONSTANT)
        den = cv2.boxFilter(reg, -1, (k, k), normalize=True, borderType=cv2.BORDER_CONSTANT)
        np.copyto(dens[y0:y1, x0:x1], num / np.maximum(den, 1e-3), where=reg > 0)
    veto = cands & (dens >= GT2_TH)
    if veto.any():
        nv, vlab, vst, _ = cv2.connectedComponentsWithStats(veto.astype(np.uint8), 8)
        # 塊級決定：低門檻抓出候選塊，整塊平均密度過高門檻才否決——逐像素會在門檻附近斑掉
        lab_px = vlab[veto]; d_px = dens[veto]
        ssum = np.bincount(lab_px, weights=d_px, minlength=nv); cnt = np.bincount(lab_px, minlength=nv)
        mean = ssum / np.maximum(cnt, 1)
        big = [j for j in range(1, nv) if int(vst[j, cv2.CC_STAT_AREA]) >= GT2_MIN and mean[j] >= GT2_HI]
        veto = np.isin(vlab, big) if big else np.zeros_like(veto)
        if veto.any():
            # 方形核（不用橢圓）：這三個核只是補洞／外擴／跟隨，形狀沒有語意；方形可拆成橫線＋直線，
            # Kotlin 端走 van Herk 快路，橢圓核要 O(W·H·kh)——實測差 200 ms。
            kc = np.ones((GT2_CLOSE * 2 + 1,) * 2, np.uint8)
            veto = (cv2.morphologyEx(veto.astype(np.uint8), cv2.MORPH_CLOSE, kc) > 0) & cands
    if veto.any():
        kk = np.ones((GT2_PAD * 2 + 1,) * 2, np.uint8)
        veto = (cv2.dilate(veto.astype(np.uint8), kk) > 0) & ~bubnear[ry0:ry1, rx0:rx1]
        # 泡附近不算密度（泡輪廓會污染），但要**跟著外圈走**：外圈被否決就一起否決，
        # 外圈留黑就一起留黑——否則背景變灰時那 25px 會浮成一圈黑環。
        kb = np.ones((GT2_BUBDIL * 2 + 1,) * 2, np.uint8)
        veto |= (cv2.dilate(veto.astype(np.uint8), kb) > 0) & bubnear[ry0:ry1, rx0:rx1] & fill[ry0:ry1, rx0:rx1]
    out = fill.copy()
    out[ry0:ry1, rx0:rx1] &= ~veto
    return out


def paint_gutter(out, g, gutter, frame=None, bubble=None):
    """留白（頁邊距／格溝）填深 + 邊界描亮。"""
    fill = gutter.copy()
    out[fill] = BG
    k = np.ones((STROKE * 2 + 1,) * 2, np.uint8)
    band = (cv2.dilate(fill.astype(np.uint8), k) > 0) & ~fill
    a = ink_alpha(g, 1.6)
    out[band] = np.maximum(out[band], BG + a[band] * (EDGE_INK - BG))
    return out


TEXT_PAD = int(os.environ.get("NIGHTREAD_TEXT_PAD", "2"))   # 泡內文字描亮外擴
TEXT_GAMMA = float(os.environ.get("NIGHTREAD_TEXT_GAMMA", "1.4"))   # 泡內字的墨度增益
TEXT_KNEE = float(os.environ.get("NIGHTREAD_TEXT_KNEE", "0.35"))   # 低墨度壓黑的門檻


def paint_bubbles(out, g, bubble, seg, text_pad=None):
    """氣泡重繪：內部填深、文字畫亮（墨度 alpha）、輪廓描亮。"""
    out[bubble] = BG
    if text_pad is None:
        text_pad = TEXT_PAD
    kt = np.ones((text_pad * 2 + 1,) * 2, np.uint8)
    text = (cv2.dilate((seg & bubble).astype(np.uint8), kt) > 0) & bubble
    # gamma 越大＝字邊緣過渡越陡：泡內除了亮字之外應該全黑，1.4 會讓抗鋸齒邊緣留一圈中灰
    # （泡內 5.7% 像素落在 40–180，使用者感知為「文字間有白底」）。
    a = ink_alpha(g, TEXT_GAMMA)
    if TEXT_KNEE > 0:
        # knee：把低墨度（字的抗鋸齒過渡）壓成 0＝純黑，字本體不受影響。gain 加大沒用——它是線性
        # 放大、只會讓更多像素變亮（實測殘灰 6.9→5.4% 但亮區反升）。
        a = np.clip((a - TEXT_KNEE) / (1.0 - TEXT_KNEE), 0.0, 1.0)
    out[text] = np.maximum(out[text], BG + a[text] * (INK - BG))
    ko = np.ones((STROKE * 2 + 1,) * 2, np.uint8)
    band = (cv2.dilate(bubble.astype(np.uint8), ko) > 0) & ~bubble
    out[band] = np.maximum(out[band], BG + ink_alpha(g, 1.6)[band] * (EDGE_INK - BG))
    return out


def build_pseudo_bubbles(g, regions, bubble, seg=None):
    """偽泡（demo02 型救回）：氣泡遮罩蓋率 < PB_COV_MAX 的 text region（開口氣泡＝泡內白
    流出去與留白/背景連通而被氣泡遮罩拒收；或字直接寫在背景/留白上），從「字底下的白」
    出發在輕切頸後的白域內測地生長（距離上限＝bbox 尺度）→ 得到泡狀填色遮罩，交
    paint_bubbles 同工法（填深＋亮字＋描邊）。輕切頸擋下巴縫這類小缺口，免得長進臉。"""
    H, W_ = g.shape
    white = (g >= WHITE_TH)
    ko = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * PB_NECK_R + 1,) * 2)
    w_cut = cv2.morphologyEx(white.astype(np.uint8), cv2.MORPH_OPEN, ko)
    w_cut = geodesic_grow(w_cut > 0, white, PB_NECK_R, step=3)   # 回收切掉的邊緣
    # 厚墨灰暈：髮團/臉部深色特徵這類「厚」墨塊周圍 PB_AURA_R 內的白不准偽泡長進去；
    # 字框/氣泡輪廓是細筆畫（距離變換最大值 < PB_AURA_THICK）、不算厚墨 ⇒ 開口泡仍可填到框邊。
    w_cut &= ~thick_ink_aura(g, seg=seg)
    pb = np.zeros((H, W_), bool)
    for r_ in regions:
        x0, y0, x1, y1 = r_["bbox"]
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(W_, x1), min(H, y1)
        if x1 <= x0 or y1 <= y0:
            continue
        win = bubble[y0:y1, x0:x1]
        if win.size == 0 or win.mean() >= PB_COV_MAX:
            continue
        ref = max(x1 - x0, y1 - y0) if PB_GROW_REF == "max" else min(x1 - x0, y1 - y0)
        cap = int(PB_GROW_FRAC * ref)
        pad = cap + PB_NECK_R + 2
        wx0, wy0 = max(0, x0 - pad), max(0, y0 - pad)
        wx1, wy1 = min(W_, x1 + pad), min(H, y1 + pad)
        seed = np.zeros((wy1 - wy0, wx1 - wx0), bool)
        seed[y0 - wy0:y1 - wy0, x0 - wx0:x1 - wx0] = True
        grown = geodesic_grow(seed & w_cut[wy0:wy1, wx0:wx1],
                              w_cut[wy0:wy1, wx0:wx1], cap)
        pb[wy0:wy1, wx0:wx1] |= grown
    return pb & ~bubble


def harmonize_enclosed_whites(out, g, lab, stats, skip_mask):
    """批1.5 人頭一致化（demo02 案，構圖層）：暗區地圖內的「殘餘亮島」→ 填深＋內緣描亮。
    亮島＝白(原圖)∧仍亮(成品)∧非已處理——**不是**白元件：群眾人頭白常與背景白同元件、
    背景被偽泡塗過後元件級 skip 會整顆跳過（b15/b16 失效原因），殘餘亮島把已塗部分
    切掉後獨立評估。防護：面積上限（主角臉大）＋外環細墨密度（鬍鬚/密髮 → 排除）。"""
    H, W_ = g.shape
    dark = (out < 60).astype(np.float32)
    ch, cw = max(1, H // HARMONIZE_ZONE_CELL), max(1, W_ // HARMONIZE_ZONE_CELL)
    coarse = cv2.resize(dark, (cw, ch), interpolation=cv2.INTER_AREA)
    coarse = cv2.GaussianBlur(coarse, (0, 0), 2.0)
    zone = cv2.resize((coarse >= HARMONIZE_ZONE_DARK).astype(np.uint8),
                      (W_, H), interpolation=cv2.INTER_NEAREST) > 0
    if not zone.any():
        return out
    resid = ((g >= WHITE_TH) & (out >= 110) & ~skip_mask).astype(np.uint8)
    n, lb, st, _ = cv2.connectedComponentsWithStats(resid, 8)
    kc = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (13, 13))
    for i in range(1, n):
        if st[i, cv2.CC_STAT_AREA] / g.size > HARMONIZE_AREA_MAX or st[i, cv2.CC_STAT_AREA] < 150:
            continue
        x, y, w2, h2 = st[i, :4]
        x0, y0 = max(0, x - 8), max(0, y - 8)
        x1, y1 = min(W_, x + w2 + 8), min(H, y + h2 + 8)
        isl = lb[y0:y1, x0:x1] == i
        if (zone[y0:y1, x0:x1] & isl).mean() < HARMONIZE_IN_ZONE:
            continue
        cu = isl.astype(np.uint8)
        collar = (cv2.dilate(cu, kc) > 0) & ~isl
        gw = g[y0:y1, x0:x1]
        fine = (gw < 200)                       # 墨（含純黑筆畫：排線間隙的白會被當亮島填黑成斑馬紋——
                                                #   ch34_010 後腦排線案；排除純黑反而放行了它）
        if collar.any() and float(fine[collar].mean()) > HARMONIZE_COLLAR_INK:
            continue
        o = out[y0:y1, x0:x1]
        o[isl] = BG
        edge = isl & ~(cv2.erode(cu, np.ones((5, 5), np.uint8)) > 0)
        o[edge] = np.maximum(o[edge], np.float32(STROKE_OBJ_V))
    return out


def compose(g, gutter, bubble, seg, frameless, lab=None, stats=None, sticker=(),
            core_ids=(), frame=None, regions=None, charmask=None, char_raw=None,
            bubble_rest=None, lost_bubble=None, diag=None, bubble_local=None, more_paint=False,
            bubble_rest_pre=None, obj_ctx=None):
    """整頁合成：場景曲線 → 留白填深（出血格過濾）→ 格溝／頁邊 → 貼紙式背景 →（[obj_ctx]）背景物件規則 → 氣泡重繪 → 灰圈收細
    → 人物還原。

    [more_paint]＝「更多」A2 的檔：貼紙層開 keep_dark（MORE_KEEP_DARK）與 edge_fb（MORE_EDGE_FB）。
    [obj_ctx]＝「更多」背景物件規則的整頁量測（nightread_obj.context ＋ std_keep；None＝不跑）。
    [bubble_rest_pre]＝泡外圈在讓開人物之前的範圍（灰圈收細的可認領像素之一；None＝沒有）。

    [diag] 給 dict 就把格溝與出血過濾的中間遮罩、逐塊決策、耗時存進去（parity／除錯用，不影響輸出）；灰圈收細另存
    ring_D／ring_seed／ring_claim（與證據 ring_conf／ring_side／ring_paper，有算才有）。"""
    out = scene_final(g, seg).astype(np.float32)
    scene_keep = out.copy()                     # 人物區最終一律還原成場景調
    # 灰圈收細（nightread_ring.py）：合成途中記下四種「只因人物安全邊而沒黑」的像素，人物還原前認領
    ring = None
    if nightread_ring.RING_ON:
        ring = dict(withheld=np.zeros(g.shape, bool), stk_band=np.zeros(g.shape, bool), gv_wh=np.zeros(g.shape, bool))
    # 任意角度格溝／頁邊（SEP）：先算好——出血過濾要拿它當結構證據，貼紙層之前塗、人物還原跳過它
    t_ = time.perf_counter()
    sep_layer = nightread_sep.build_sep(g, seg, bubble, frame, veto=texture_veto2) if SEP_ON else None
    sepm = sep_layer["sep"] if sep_layer is not None else None
    if diag is not None:
        diag["t_sep"] = time.perf_counter() - t_
        diag["t_bleed"] = 0.0
        # 這兩層的輸入（Kotlin 分段 parity：同一份輸入單獨比 SEP／出血過濾）
        diag.update(in_g=g, in_frame=frame, in_bubble=bubble, in_seg=seg, in_gutter=gutter, in_charmask=charmask)
        if sep_layer is not None:
            for k_ in ("sep", "sep_pre", "strip", "margin", "frame_arb"):
                diag[k_] = sep_layer[k_]
            diag["sep_dropped_pairs"] = sep_layer["dropped"]

    def _bleed(band, band0):
        # 出血格過濾：只拿掉留白帶裡的畫面塊、不新增（SEP 之後照塗，拿不掉溝與頁邊）
        if not BLEED_ON:
            return band
        t0_ = time.perf_counter()
        res, pcs = nightread_bleed.bleed_filter(band, band0, g, frame, seg, bubble, charmask, regions, gutter, sep_layer)
        if diag is not None:
            diag["t_bleed"] += time.perf_counter() - t0_
            diag["band0"], diag["band_post"], diag["band_final"] = band0, band, res
            diag["pieces"] = pcs
        return res
    if frameless and gutter.any():
        # 無框頁只填「真頁邊帶」：留白元件深入頁內的最大距離 ≤ 短邊×FRAMELESS_MARGIN_DEPTH 才是
        # 貼邊薄帶（開放背景會深入頁心、不符）。
        H2, W2 = g.shape
        yy, xx = np.mgrid[0:H2, 0:W2]
        bd = np.minimum(np.minimum(yy, H2 - 1 - yy), np.minimum(xx, W2 - 1 - xx))
        n_, lb_, st_, _ = cv2.connectedComponentsWithStats(gutter.astype(np.uint8), 8)
        keep = np.zeros_like(gutter)
        lim = FRAMELESS_MARGIN_DEPTH * min(H2, W2)
        for i in range(1, n_):
            m = lb_ == i
            if bd[m].max() <= lim:
                keep |= m
        v1 = texture_veto2(keep, g, frame, seg, bubble)
        if ring is not None:                            # 灰圈收細：只因人物自己的墨而被線稿否決的留白
            ring["gv_wh"] = keep & ~v1 & texture_veto2(keep, g, frame, seg, bubble, exclude=charmask)
        keep = _bleed(v1, keep)   # 有線稿的白不是留白；出血格畫面拿掉
        if keep.any():
            out = paint_gutter(out, g, keep, frame=frame, bubble=bubble)
    elif not frameless and gutter.any():
        # 安全策略：留白元件「深入格內」的部分不填。出血特寫的臉/白衣與頁白同元件、只有細線稿、
        # 沒有墨團可觸發灰暈（demo01 臉頰 98% 黑、ch34_015 肩、demo02 貼頁緣的臉皆此型）。
        # 頁邊帶（距頁邊/格線 ≤ 短邊×SAFE_GUTTER_DEPTH）照填；更深處留灰＝失敗方向安全。
        H2, W2 = g.shape
        yy, xx = np.mgrid[0:H2, 0:W2]
        bd = np.minimum(np.minimum(yy, H2 - 1 - yy), np.minimum(xx, W2 - 1 - xx)).astype(np.float32)
        if frame is not None and frame.any():
            fd = cv2.distanceTransform((frame == 0).astype(np.uint8), cv2.DIST_L2, 3)
            bd = np.minimum(bd, fd)
        lim = SAFE_GUTTER_DEPTH * min(H2, W2)
        band = gutter_frame_cut(g, gutter) & (bd <= lim)   # 先沿格框線切開再取頁邊帶
        v1 = texture_veto2(band, g, frame, seg, bubble)
        if ring is not None:                            # 灰圈收細：只因人物自己的墨而被線稿否決的留白
            ring["gv_wh"] = band & ~v1 & texture_veto2(band, g, frame, seg, bubble, exclude=charmask)
        band = _bleed(v1, band)   # 有線稿的白不是留白；出血格畫面拿掉
        if band.any():
            out = paint_gutter(out, g, band, frame=frame, bubble=bubble)
    elif gutter.any():
        # 無框頁只填「真頁邊帶」：留白元件深入頁內的最大距離 ≤ 短邊×FRAMELESS_MARGIN_DEPTH 才是
        # 貼邊薄帶（開放背景會深入頁心、不符）；仍套人物灰暈（出血人物碰邊的保護）。
        H2, W2 = g.shape
        yy, xx = np.mgrid[0:H2, 0:W2]
        bd = np.minimum(np.minimum(yy, H2 - 1 - yy), np.minimum(xx, W2 - 1 - xx))
        n_, lb_, st_, _ = cv2.connectedComponentsWithStats(gutter.astype(np.uint8), 8)
        keep = np.zeros_like(gutter)
        lim = FRAMELESS_MARGIN_DEPTH * min(H2, W2)
        for i in range(1, n_):
            m = lb_ == i
            if bd[m].max() <= lim:
                keep |= m
        if keep.any():
            out = paint_gutter(out, g, keep, frame=frame if frame is not None else np.zeros_like(gutter, np.uint8), bubble=bubble)
    if sepm is not None and sepm.any():                 # 任意角度格溝／頁邊：同留白待遇（填 BG、邊界描亮）
        out = paint_gutter(out, g, sepm, frame=frame, bubble=bubble)
    if sticker and obj_ctx is not None and nightread_obj.OBJ_VETO:
        # 「更多」背景物件規則 V（nightread_obj.py）：先只塗標準的那幾顆、再塗全部；多塗的連通塊逐塊看超區物件證據，
        # 否決的塊還原成只塗標準的樣子。灰圈收細的收集在否決元件上改用只塗標準那一趟的。
        sk = obj_ctx["std_keep"]
        ring_std = None if ring is None else dict(withheld=np.zeros(g.shape, bool), stk_band=np.zeros(g.shape, bool))
        out_std = paint_sticker(out.copy(), g, lab, stats, sk, bubble,
                                core_ids=set(core_ids) & sk, frame=frame, seg=seg, charmask=charmask,
                                keep_dark=more_paint and MORE_KEEP_DARK, edge_fb=more_paint and MORE_EDGE_FB, ring=ring_std)
        out = paint_sticker(out, g, lab, stats, sticker, bubble,
                            core_ids=core_ids, frame=frame, seg=seg, charmask=charmask,
                            keep_dark=more_paint and MORE_KEEP_DARK, edge_fb=more_paint and MORE_EDGE_FB, ring=ring)
        std_dark = nightread_obj.blackish(out_std, g)
        vet = nightread_obj.veto_blocks(g, nightread_obj.blackish(out, g) & ~std_dark, std_dark, obj_ctx, diag)
        if vet.any():
            r_ = int(np.clip(round(STROKE_OBJ_FRAC * min(g.shape)), STROKE_OBJ_MIN, STROKE_OBJ_MAX)) + 2
            back = cv2.dilate(vet.astype(np.uint8), np.ones((2 * r_ + 1,) * 2, np.uint8)) > 0
            back &= out != out_std
            out[back] = out_std[back]
            vids = set(int(v) for v in np.unique(lab[vet])) - set(sk) - {0}
            if vids:
                vz = np.isin(lab, sorted(vids))
                if bubble_rest is not None:
                    bubble_rest = bubble_rest & ~vz
                if bubble_rest_pre is not None:
                    bubble_rest_pre = bubble_rest_pre & ~vz
                if ring is not None:
                    for k_ in ("withheld", "stk_band"):
                        ring[k_] = (ring[k_] & ~vz) | (ring_std[k_] & vz)
        if diag is not None:
            diag["obj_vetomask"] = vet
        del out_std
    elif sticker:                                       # 貼紙式背景：純白背景填黑＋前景白描邊
        out = paint_sticker(out, g, lab, stats, sticker, bubble,
                            core_ids=core_ids, frame=frame, seg=seg, charmask=charmask,
                            keep_dark=more_paint and MORE_KEEP_DARK, edge_fb=more_paint and MORE_EDGE_FB, ring=ring)
    if obj_ctx is not None and nightread_obj.OBJ_LT:  # 「更多」背景物件規則 L：無物件的亮背景（白與淺色調）塗黑
        t_ = time.perf_counter()
        out = nightread_obj.light_fill(out, g, obj_ctx, diag)
        if diag is not None:
            diag["t_obj_lt"] = time.perf_counter() - t_
    if ring is not None:                                # 灰圈收細：背景填黑（留白／格溝／貼紙／亮背景區）到此為止塗成 BG 的像素
        ring["bg"] = (out == BG) & (scene_keep != BG)
    # [bubble_local] 封縫救回的泡：只在這裡以後（泡重繪、偽泡、亮島、人物還原）當泡；上面的結構層只看 bubble
    bub_all = bubble if bubble_local is None else (bubble | bubble_local)
    out = paint_bubbles(out, g, bub_all, seg)
    # 偽泡：開口泡/字壓背景/字壓留白救回（三檔一律關：偽泡沿字往背景長，是撕裂黑塊來源之一，守護框 +2）
    pb = build_pseudo_bubbles(g, regions, bub_all, seg=seg) if PSEUDO_BUBBLES else np.zeros_like(bubble)
    if pb.any():
        out = paint_bubbles(out, g, pb, seg)
    if HARMONIZE:   # 亮島填黑（三檔一律關：會把格內背景挖成黑塊；守護框對它零敏感）
        out = harmonize_enclosed_whites(out, g, lab, stats, bub_all | pb | gutter)
    if lost_bubble.any():
        txt = (cv2.dilate((seg & lost_bubble).astype(np.uint8),
                          np.ones((TEXT_TOP_PAD * 2 + 1,) * 2, np.uint8)) > 0) & lost_bubble
        if txt.any():
            out[txt] = BG
            a3 = ink_alpha(g, TEXT_GAMMA)
            if TEXT_KNEE > 0:
                a3 = np.clip((a3 - TEXT_KNEE) / (1.0 - TEXT_KNEE), 0.0, 1.0)
            out[txt] = np.maximum(out[txt], BG + a3[txt] * (INK - BG))
    if bubble_rest is not None and bubble_rest.any():
        out[bubble_rest] = BG
        kk = np.ones((STROKE * 2 + 1,) * 2, np.uint8)
        band = (cv2.dilate(bubble_rest.astype(np.uint8), kk) > 0) & ~bubble_rest
        a2 = ink_alpha(g, 1.6)
        out[band] = np.maximum(out[band], BG + a2[band] * (EDGE_INK - BG))
    # 語意禁填：人物區（含描邊外擴）一律還原場景調。放最後＝不必逐機制改，任何新填色
    # 機制自動受保護。**只扣氣泡/偽泡**（人物身上的對話框仍該深底亮字）——
    # ⚠️ 不能扣 gutter：白衣被塗黑正是 gutter 幹的（出血人物與頁白同元件），
    # 扣了等於把最大宗的違規排除在保護外（實測 55→52 框、几乎沒救到）。
    restore = charmask.copy()
    # ★ 真氣泡永遠贏過人物保護：氣泡是**畫在畫面之上**的圖層，它遮住後面的人物——該處根本看不到
    # 人物，把它還原成「人物的場景調」等於讓對話框變成淺色底（使用者 2026-09-15 回報）。
    # CHAR_OVER_BUBBLE 只該管**偽泡**（字直接寫在畫面上、人物在字周圍仍看得見）。
    restore &= ~bub_all
    # ★ 格溝／頁邊也贏過人物（使用者 2026-09-27：「格溝畫在人物遮罩上面」）：溝是畫面的外面，人物遮罩經收邊＋平滑會越過
    # 格框線長進溝 7–15px ⇒ 溝邊灰帶、窄溝被吃過半整條不塗。框線被出血人物打斷的地方本來就不成溝（build_sep 不收）。
    if sepm is not None:
        restore &= ~sepm
    # ★ 收邊生長出來的邊緣不得壓過偽泡：restore 用的是**加工後**的遮罩（測地收邊 + 中值平滑
    # 把邊界推到輪廓線上），偽泡內那些「模型原輸出沒蓋到、是加工長出來的」像素屬於畫面不屬於
    # 人物 ⇒ 偽泡贏。判準用未加工的 char_raw。
    if pb.any():
        restore &= ~(pb & ~char_raw)
    # 偽泡（字直接寫在畫面上）則是人物優先，只保留字的貼身暗襯（見 TEXT_BACKING_R）
    text_on_char = pb & charmask & seg
    if text_on_char.any():
        kb = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (TEXT_BACKING_R * 2 + 1,) * 2)
        restore &= ~(cv2.dilate(text_on_char.astype(np.uint8), kb) > 0)
    if lost_bubble.any():
        # ★ 字永遠在最上層（使用者 2026-09-17 的圖層優先權原則）：泡遮罩被人物扣掉後，那塊
        # 區域的字失去「泡內亮字」待遇 ⇒ 暗字疊在灰底上，實測對比 **-6（字比底還暗、讀不出來）**。
        # 修法不是讓整顆泡贏（會把臉填黑、弄壞 17 框），而是**只讓字本身贏**：被扣掉的泡區裡，
        # 字筆畫及其貼身帶不還原 ⇒ 維持深底亮字，人物的其餘部分照樣受保護。
        kt2 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (TEXT_TOP_PAD * 2 + 1,) * 2)
        keep_txt = (cv2.dilate((seg & lost_bubble).astype(np.uint8), kt2) > 0) & lost_bubble
        restore &= ~keep_txt
    if ring is not None:
        # 灰圈收細（使用者 2026-10-03 拍板「折衷」）：有畫出來的輪廓線把背景跟人物隔開的地方，已經塗黑的背景長到輪廓線；
        # 其餘維持現在的寬度。認領的像素填 BG、從還原遮罩拿掉（規則見 nightread_ring.py）。
        bg_paint = ring["bg"] if bubble_rest is None else (ring["bg"] | bubble_rest)
        rest_pre = bubble_rest_pre if bubble_rest_pre is not None else np.zeros(g.shape, bool)
        restore = nightread_ring.apply(out, restore, g, scene_keep, char_raw, charmask, bub_all | pb | lost_bubble, bg_paint,
                                       ring["withheld"], rest_pre, ring["gv_wh"], ring["stk_band"], diag=diag)
    # 邊界抗鋸齒：遮罩是二值的、又是從 640 解析度放大來的 ⇒ 邊界呈階梯狀。用小半徑高斯
    # 把還原遮罩軟化成 0..1 alpha 做混合，只在 1–2px 內過渡。
    a_e = cv2.GaussianBlur(restore.astype(np.float32), (0, 0), EDGE_FEATHER)
    out = out * (1.0 - a_e) + scene_keep * a_e
    return np.clip(out, 0, 255).astype(np.uint8)


# ── 出圖/IO ─────────────────────────────────────────────────────────

def _label(img, text, bar_h=48, scale=0.9):
    """圖上加白底黑字標籤列（cv2.putText 無 CJK ⇒ 英文標籤）。"""
    im = cv2.cvtColor(img, cv2.COLOR_GRAY2BGR) if img.ndim == 2 else img.copy()
    bar = np.full((bar_h, im.shape[1], 3), 255, np.uint8)
    cv2.putText(bar, text, (10, int(bar_h * 0.7)), cv2.FONT_HERSHEY_SIMPLEX,
                scale, (0, 0, 0), 2, cv2.LINE_AA)
    return np.vstack([bar, im])


def _hcat(cols, sep_w=6, sep_v=128):
    h = max(c.shape[0] for c in cols)
    padded = []
    for c in cols:
        if c.shape[0] < h:
            c = cv2.copyMakeBorder(c, 0, h - c.shape[0], 0, 0,
                                   cv2.BORDER_CONSTANT, value=(255, 255, 255))
        padded.append(c)
    sep = np.full((h, sep_w, 3), sep_v, np.uint8)
    row = padded[0]
    for c in padded[1:]:
        row = np.hstack([row, sep, c])
    return row


def mask_viz(img_bgr, gutter, panel_scene, bubble, seg, regions, sticker_mask=None):
    """遮罩視覺化：留白=黃、修法3格內白(降級)=橘、修法4貼紙背景=藍、氣泡=綠、
    筆畫=紅、區域框=洋紅。"""
    viz = img_bgr.copy()
    layers = [(gutter, (0, 200, 200)), (panel_scene, (0, 128, 255)),
              (bubble, (0, 160, 0))]
    if sticker_mask is not None:
        layers.append((sticker_mask, (255, 120, 40)))
    for m, col in layers:
        viz[m] = (viz[m] * 0.5 + np.array(col) * 0.5).astype(np.uint8)
    viz[seg] = (0, 0, 255)
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        cv2.rectangle(viz, (x0, y0), (x1, y1), (255, 0, 255), 2)
    return viz


def run_page(page_path, outdir=OUT_DEFAULT, col_w=1000, regions=None, seg=None, diag=None, inpaint=None):
    """單頁一條龍：偵測 → 遮罩 → 合成 → 落檔。回傳統計 dict（批次表用）。

    [regions] 與 [seg] 可以由外部提供，跳過偵測——**產品路徑就是這樣走的**：頁面先經過翻譯，
    文字區早就算過（存在翻譯素材裡），譯文的筆畫位置則由排版器自己知道，都不必重測一次。
    只給其中一個也行，另一個仍走偵測。[diag] 透傳給 compose（中間遮罩／耗時，parity 用）。
    [inpaint]＝譯後頁的去字遮罩（翻譯素材 `.yakuyomi/<頁>.mask.png`，與頁同尺寸的布林）：只給「更多」背景物件規則的
    孤島判斷用（規則版本 4，nightread_obj）；None＝日文頁或沒有素材（與版本 3 相同）。
    """
    name = os.path.splitext(os.path.basename(page_path))[0]
    os.makedirs(outdir, exist_ok=True)
    img = cv2.imread(page_path)                        # 彩頁也吃（偵測吃 BGR）
    assert img is not None, page_path
    g = cv2.imread(page_path, cv2.IMREAD_GRAYSCALE)
    g, paper_peak = normalize_paper(g, img)           # 色紙/掃描頁：亮部拉到 255（見 PAPER_NORM_MIN；淡彩底不動）
    H, W = g.shape

    if regions is None or seg is None:
        lines, det_regions, det_seg = detect(img)
        regions = det_regions if regions is None else regions
        seg = det_seg if seg is None else seg
    else:
        lines = []                                     # 兩者都給了就完全不跑偵測
    frameless, hk, vk = page_is_frameless(g)
    lab, stats, gutter_ids, panel_ids = classify_white_components(g)
    char_raw = load_charmask(page_path, g.shape)      # 模型原輸出（未收邊、未平滑）
    charmask = smooth_charmask(snap_charmask(char_raw, g), g)
    loc_out = {}
    bubble, merged, rejected, cored = build_bubble_mask(
        g, regions, seg, lab, stats, gutter_ids | panel_ids, charmask=charmask,
        chroma=(img.max(axis=2).astype(np.int16) - img.min(axis=2).astype(np.int16)).astype(np.uint8),
        local_out=loc_out, char_raw=char_raw)
    # 封縫救回的泡只進「泡的重繪」（填深、亮字、人物還原的泡優先），不進格溝／留白／出血／貼紙這些結構層
    # （同 v2 的隔法：texture_veto2 在泡附近不否決、泡是 SEP 的隔板——新泡餵給它們會改到泡外）。
    local_only = loc_out.get("mask", np.zeros((H, W), bool))
    sticker, audit, promoted = sticker_plan(g, img, lab, stats, gutter_ids, panel_ids,
                                            frameless, regions)
    lhm, lvm = frame_line_mask(g)
    accept0, promoted0 = set(sticker), set(promoted)    # 安全網收下的全部（檔位篩選前；「更多」A2 的候選從這裡挑）
    sticker, promoted = filter_sticker_plan(g, lab, stats, sticker, audit, promoted,   # 三檔（STICKER_MODE）
                                            char_raw, (lhm | lvm) > 0)
    base_keep = set(sticker)
    if MORE_RULE:
        # 「更多」A2：在檔位 keep 上只加不減。泡＝修剪前（含封縫救回的）；人物＝收邊後（交代過）與原輸出（佔比）
        sticker, promoted = more_rule(g, img, lab, stats, accept0, promoted0, sticker, audit, charmask, char_raw,
                                      bubble, seg, (lhm | lvm) > 0, frameless, regions, gutter_ids, panel_ids)
        for a in audit:
            a["keep"] = int(a["comp"]) in sticker
    obj_ctx = None
    if MORE_RULE and nightread_obj.OBJ_ON:
        # 「更多」背景物件規則（nightread_obj.py）：整頁量測一次；否決要「只塗標準（L2）」的 keep（∩ 這一檔的 keep）
        t_obj = time.perf_counter()
        obj_ctx = nightread_obj.context(g, (img.max(axis=2).astype(np.int16) - img.min(axis=2).astype(np.int16)).astype(np.uint8),
                                        charmask, char_raw, bubble, seg, (lhm | lvm) > 0, regions, inpaint=inpaint)
        std_keep, _ = filter_sticker_plan(g, lab, stats, accept0, audit, promoted0, char_raw, (lhm | lvm) > 0,
                                          mode="simple", rough_max=10.0, min_frac=0.005, mark_audit=False)
        obj_ctx["std_keep"] = set(std_keep) & set(sticker)
        if diag is not None:
            diag["t_obj_ctx"] = time.perf_counter() - t_obj
    # 無框頁的留白層用加規則前的 keep（P3）：新收的元件整顆當貼紙塗，留白帶照舊
    gutter_show = gutter_ids - base_keep if frameless else gutter_ids
    gutter = np.isin(lab, sorted(gutter_show)) if gutter_show else np.zeros((H, W), bool)
    panel_show = panel_ids - sticker
    panel_scene = np.isin(lab, sorted(panel_show)) if panel_show else np.zeros((H, W), bool)
    sticker_mask = np.isin(lab, sorted(sticker)) if sticker else np.zeros((H, W), bool)
    rest_ids = set(cored) | set(promoted)
    if rest_ids:
        # 泡元件的**剩餘部分**（元件 − 核心）＝泡框外的背景白。它與泡是同一個白元件，核心填色只填
        # 了泡內部，剩下的既不是 gutter 也不是 panel（未列管）⇒ 沒有任何機制接手 ⇒ 維持場景灰 140，
        # 看起來就是「泡泡外面那一圈」（使用者 2026-09-15 兩次回報；實測泡外 6px 起原 255→成品 140）。
        # 判定有語意根據：這個元件已被判為「含氣泡的白區」，氣泡以外就是氣泡外的背景。
        # 同理推廣到**貼紙擢升**的元件（promoted，走 paint_sticker 的核心填色）：ch34_015 左下格的
        # 格內背景 comp3125（9.91% 頁）就是這樣，核心沒涵蓋泡框外那一圈 ⇒ 留場景灰。
        # 核心填色原本是為了保護「被吃的前景白」（白鬍老人），那是**人物遮罩出現前**的粗略替代品；
        # 有語意遮罩後扣掉 charmask 即可，剩餘一律填深。
        # 扣人物遮罩（背景填色一律讓開人物）。
        rest = np.isin(lab, sorted(rest_ids)) & ~bubble
        bubble_s0 = bubble & ~local_only        # 「泡附近」只看原本的泡（封縫救回的泡不延伸泡外那一圈）
        if bubble_s0.any():
            # ⚠️ 只填**泡框周圍**這一圈：貼紙的核心填色保護是為了留住「被吃的前景白」（白鬍老人的
            # 鬍子/髮絲），全部取消會把它們吃掉（實測違規 28→52）。使用者抱怨的是泡外那一圈，
            # 限制在泡附近即可兩全。
            kn = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (BUBBLE_REST_NEAR * 2 + 1,) * 2)
            rest &= cv2.dilate(bubble_s0.astype(np.uint8), kn) > 0
        bubble_rest_pre = rest.copy()   # 灰圈收細：泡外圈在讓開人物之前的範圍
        rest &= ~charmask           # 背景填色一律讓開人物
        bubble_rest = rest
    else:
        bubble_rest = None
        bubble_rest_pre = None
    bubble_guard = charmask
    # ★★ 圖層優先權的正確實作（使用者 2026-09-17：「要塗黑的泡直接全部塗黑，文字再補上去」）。
    # 難點是分辨「真泡蓋住人物」與「字寫在臉上被誤判成泡」。判準＝**泡內部的非字內容**：
    #   ・真泡是**空白容器**，裡面除了字什麼都沒有 ⇒ 填洞後的內部非字墨 0.0–0.3%
    #   ・臉被誤判成泡：裡面有五官、陰影 ⇒ demo01 那張 2.7%、demo04 1.6%
    # （正常泡 97 個的中位 0.0%、P90 0.7% ⇒ 門檻 1% 分得開。）
    # 判定為真泡的：**整顆塗黑、不被人物遮罩扣**；判定為臉的：人物贏，照舊保護。
    segd_c = cv2.dilate(seg.astype(np.uint8),
                        cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7))) > 0
    nb, lb_b, st_b, _ = cv2.connectedComponentsWithStats(bubble.astype(np.uint8), 8)
    clean = np.zeros_like(bubble)
    clean_filled = np.zeros_like(bubble)     # 乾淨泡加上它們的洞（漏泡判準的「泡外」不含泡的洞）
    for i in range(1, nb):
        a_b = int(st_b[i, cv2.CC_STAT_AREA])
        if a_b < 4000:
            continue
        bx_, by_, bw_, bh_ = (int(st_b[i, 0]), int(st_b[i, 1]), int(st_b[i, 2]), int(st_b[i, 3]))
        sy = slice(max(0, by_ - 2), by_ + bh_ + 2)
        sx = slice(max(0, bx_ - 2), bx_ + bw_ + 2)
        blob = lb_b[sy, sx] == i
        hh, ww = blob.shape
        # 外圍補 1 px 的 0 再從角落灌水＝從裁窗四邊灌水（同 _hole_ink_ratio 與 Kotlin 的 Cv.holes）。泡貼著頁緣時裁窗
        # 那一側沒有 2 px 的邊：只從裁窗的 (0,0) 灌，泡與頁緣圍住的泡外口袋會被當成洞（泡蓋住左上角時連泡本身都被灌到、
        # 整片泡外都算洞）。47 頁沒有這種泡，改前改後逐像素相同。
        tmp = np.zeros((hh + 2, ww + 2), np.uint8)
        tmp[1:-1, 1:-1] = blob
        ffm = np.zeros((hh + 4, ww + 4), np.uint8)
        cv2.floodFill(tmp, ffm, (0, 0), 2)
        tmp = tmp[1:-1, 1:-1]
        holes = (tmp != 2) & ~blob & ~segd_c[sy, sx]
        if BUBBLE_CLEAN_INK_HOLES:
            # d：洞只算非紙白。字欄之間沒被核心填色收進泡的紙白小縫（47 頁判不乾淨的 39 顆真泡有 33 顆洞只有紙白）
            # 不再讓泡被判不乾淨；0.5% 門檻對這種泡像擲硬幣（縮放／JPEG 就翻面），手機 NCNN 輸入差一點就翻。
            holes &= g[sy, sx] < WHITE_TH
        if float(holes.sum()) / a_b >= BUBBLE_CLEAN_WINS:
            continue
        # 保險②：**文字佔比**。真泡是容器，字只佔一部分（實測 8.7–50.7%）；
        # 被誤判的白髮/白手區塊幾乎全是字筆畫本身（demo04 垂髮 94.7%、demo01 那些 99–100%），
        # 因為 build_bubble_mask 最後會把字區內的筆畫一律併進泡。⇒ 文字佔比過高＝不是泡。
        if float(seg[sy, sx][blob].mean()) > BUBBLE_CLEAN_TEXT_MAX:
            continue
        clean[sy, sx] |= blob
        clean_filled[sy, sx] |= tmp != 2
    if BUBBLE_GUARD_RAW:
        # c：仍判不乾淨的泡，只要是「字確認」的（是容器、且有字框落在裡面），修剪只讓開人物模型原輸出——
        # 收邊（snap）／平滑沿泡內紙白長進去的那條安全邊（淺條的 67–100%）一律被泡的黑蓋過；原輸出直接畫到的照舊保護。
        # 不照字面「泡一律贏」：那會塗到被誤當成泡的白襯衫（demo04 44.5%）、摸頭的手（demo05 88.4%）。
        confirmed = confirmed_bubbles(bubble, seg, regions)
        bubble_guard = (char_raw & confirmed) | (charmask & ~confirmed)
    bubble_guard = bubble_guard & ~clean
    if BUBBLE_LEAK:
        bubble_guard = bubble_guard | bubble_leak(g, clean, clean_filled, bubble, char_raw)
    # 泡遮罩不得跨進人物：氣泡是**畫在人物之上**的圖層 ⇒ 泡內部不可能是人物；反過來，泡的白
    # 元件常與人物白（髮/衣）連通（泡框有缺口、髮壓在泡邊），整顆填就把髮吃掉
    # （demo04 第2格垂髮 0→75%）。
    # ⚠️ 用**精準遮罩**（cseg∪yoloseg）扣、不用 combine：combine 含 isnet（會把整顆泡當人物）
    # ⇒ 泡被挖成白泡。精準遮罩是實例分割、不會把泡判成人物。
    # ⚠️ 只扣「從泡邊緣伸進來的人物」：遮罩誤蓋到泡中央時，粗暴地扣會把泡挖出洞＝白泡
    # （實測白泡 21.9→29.4 萬 px）。判準＝被扣掉的連通塊有沒有碰到泡的外緣：碰到＝髮/衣從外面
    # 連進來（扣），完全被泡包住＝遮罩誤判泡內部（還原）。
    bubble_before_trim = bubble.copy()
    removed = bubble & bubble_guard
    if removed.any():
        outside = ~bubble
        nrm, lbrm = cv2.connectedComponents(removed.astype(np.uint8), 8)
        touch = np.unique(lbrm[(cv2.dilate(outside.astype(np.uint8),
                                           np.ones((3, 3), np.uint8)) > 0) & removed])
        touch = touch[touch > 0]
        bubble = bubble & ~np.isin(lbrm, touch)
    lost = bubble_before_trim & ~bubble
    final = compose(g, gutter, bubble & ~local_only, seg, frameless, lab, stats, sticker,
                    core_ids=promoted, frame=(lhm | lvm), regions=regions, charmask=charmask,
                    char_raw=char_raw, bubble_rest=bubble_rest, lost_bubble=lost, diag=diag,
                    bubble_local=bubble & local_only, more_paint=MORE_RULE, bubble_rest_pre=bubble_rest_pre,
                    obj_ctx=obj_ctx)

    pref = os.path.join(outdir, name)
    with open(f"{pref}_regions.json", "w", encoding="utf-8") as f:
        json.dump({
            "image": page_path, "width": W, "height": H,
            "pageType": "frameless" if frameless else "framed",
            "paperPeak": int(paper_peak),
            "frameLines": {"h": round(hk, 3), "v": round(vk, 3)},
            "detector": "DBNet (m-i-t default @ .upstream-ref, detect-20241225.ckpt) "
                        "torch eager; text_th=0.5 box_th=0.7 unclip=2.3; "
                        "regions=mit_grouping.merge_bboxes_text_region",
            "sticker": audit,                          # 修法4 逐元件審計（含安全網量測）
            "lines": [{"quad": t.pts.tolist(), "score": round(float(t.prob), 4)}
                      for t in lines],
            "regions": regions,
        }, f, ensure_ascii=False, indent=1)
    cv2.imwrite(f"{pref}_seg.png", seg.astype(np.uint8) * 255)
    cv2.imwrite(f"{pref}_bubble.png", bubble.astype(np.uint8) * 255)
    cv2.imwrite(f"{pref}_gutter.png", gutter.astype(np.uint8) * 255)
    cv2.imwrite(f"{pref}_final.png", final)

    wb = float((g >= WHITE_MEASURE_TH).mean())
    wa = float((final >= WHITE_MEASURE_TH).mean())
    cols = []
    for im, t in ((img, f"{name} original"),
                  (final, f"night rebuild ({'FRAMELESS: bubbles only' if frameless else 'framed'})"),
                  (mask_viz(img, gutter, panel_scene, bubble, seg, regions, sticker_mask),
                   "masks: gutter=y panelwhite=o sticker=b bubble=g seg=r")):
        s = col_w / im.shape[1]
        im2 = cv2.resize(im, (col_w, int(im.shape[0] * s)), interpolation=cv2.INTER_AREA)
        cols.append(_label(im2, t, bar_h=46, scale=0.8))
    cv2.imwrite(f"{pref}_cmp.png", _hcat(cols))

    st = {"page": name, "pageType": "frameless" if frameless else "framed",
          "regions": len(regions), "whiteBefore": round(wb, 4), "whiteAfter": round(wa, 4),
          "gutterFrac": round(float(gutter.mean()), 4),
          "panelWhiteFrac": round(float(panel_scene.mean()), 4),
          "stickerFrac": round(float(sticker_mask.mean()), 4),
          "stickerComps": len(sticker), "stickerFallback": len(audit) - len(sticker),
          "stickerAudit": audit,
          "bubbleFrac": round(float(bubble.mean()), 4),
          "bubbleCompsMerged": len(merged), "bubbleCompsRejected": len(rejected),
          "cmp": f"{pref}_cmp.png"}
    print(f"[{name}] {st['pageType']}  regions={st['regions']}  "
          f"white {wb:.3f}->{wa:.3f}  gutter={st['gutterFrac']:.3f}  "
          f"panelWhite={st['panelWhiteFrac']:.3f}  sticker={st['stickerFrac']:.3f}"
          f"({len(sticker)}/{len(audit)})  bubble={st['bubbleFrac']:.3f}  "
          f"comps merged/rejected={len(merged)}/{len(rejected)}", flush=True)
    for a in audit:
        print(f"    sticker comp={a['comp']:4d} bbox={a['bbox']} area={a['areaFrac']:.3f} "
              f"fig={a['figFrac']:.3f} thin={a['thinFrac']:.3f} chroma={a['chroma']:5.1f} "
              f"eaten={a['eatenFrac']:.4f} protect={a['protectFrac']:.4f} "
              f"textCov={a['textCov']:.3f} textOn={a['textOn']:.3f} rough={a['rough']:6.1f} "
              f"-> {'ACCEPT' if a['accept'] else 'fallback'}"
              f"{'' if a.get('keep', a['accept']) == a['accept'] else ' (tier: dropped)'}", flush=True)
    return st


def main():
    ap = argparse.ArgumentParser(description="夜讀重繪（單頁）")
    ap.add_argument("page", help="頁圖路徑")
    ap.add_argument("-o", "--outdir", default=OUT_DEFAULT)
    a = ap.parse_args()
    run_page(a.page, a.outdir)


if __name__ == "__main__":
    main()
