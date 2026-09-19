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
      └ 合成      場景曲線 → 留白填深 → 貼紙式背景 → 氣泡 → 偽泡
                  → 人頭一致化 → 剩餘填色 → 人物還原
    暗色頁

分區的待遇：

    留白（頁邊距／格溝）  填 BG、邊界描亮
    純白背景             填 BG、前景白描邊抬出立體感
    氣泡內部             填 BG、文字筆畫畫亮到 INK（原圖墨度當 alpha ⇒ 天然抗鋸齒）
    人物                 場景曲線壓暗，任何填色都要讓開
    其餘畫面             場景曲線壓暗（線性、保序）

兩條紅線：
  1. **絕不塗錯**。臉、手、皮膚、白衣、白髮絕不可以被填黑。失敗方向只准「不夠暗」。
     驗收靠 `nightread_guard.py` 的 704 個人工標註框，目視不算數。
  2. **畫面絕不反相**。畫面區只允許單調映射，墨線永遠比紙面暗。

圖層優先權（決定衝突時誰贏）：**字 > 對話框 > 人物 > 背景**。

人物語意遮罩是**必要輸入**：守護框證明沒有它紅線不可達（純幾何最好也有 37 框違規，
且要付 14 個百分點的亮區代價）。先跑 `charmask.py`，再把輸出夾給 `NIGHTREAD_CHARMASK`。

偵測路徑＝`export_dbnet_ncnn.build_model`（m-i-t TextDetection @ .upstream-ref，
detect-20241225.ckpt）torch 前向 ＋ m-i-t `SegDetectorRepresenter` 後處理 ＋
`mit_grouping` 兩階段區域合併，與引擎同款前處理（長邊 1024、pad 到 256 倍數、/127.5-1）。

用法：
    NIGHTREAD_CHARMASK=<遮罩夾> python3 nightread.py <頁圖> [-o 輸出夾]
輸出（皆帶頁名前綴）：_final.png ／ _regions.json ／ _seg.png ／ _bubble.png ／
_gutter.png ／ _cmp.png（三聯：原圖｜成品｜遮罩視覺化）。批次見 nightread_batch.py。

每個參數的由來、以及所有被實測否決的替代方案，見 ../docs/DECISIONS.md。
"""
import argparse
import importlib.util
import json
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths                                                      # noqa: E402  ← 先：把 engine parity 加進 sys.path
import export_dbnet_ncnn as ex                                    # noqa: E402  （來自 yakuyomi-engine/parity）
from mit_grouping import Quadrilateral, merge_bboxes_text_region  # noqa: E402  （來自 yakuyomi-engine/parity）

OUT_DEFAULT = os.path.join(paths.OUT, "nightread")

# ── 參數（唯一的旋鈕面板；每個值的由來與被否決的替代方案見 docs/DECISIONS.md）──────
# 輸出位準
BG = 16                 # 深底：留白／氣泡內部／背景填色都用它
INK = 240               # 亮字：氣泡內的文字筆畫
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
SAFE_BUBBLE_RATIO = 2.5     # 泡核心面積 ≤ 此×字框長邊²（擋「字壓臉」被當成泡）
BUBBLE_CLEAN_WINS = 0.005   # 內部非字墨 < 此的泡＝乾淨容器 ⇒ 整顆塗黑、人物不扣
BUBBLE_CLEAN_TEXT_MAX = 0.8 # 但文字佔比 > 此＝那不是泡（是被誤判的白髮／白手）
BUBBLE_REST_NEAR = 20       # 泡元件的剩餘部分只在泡外此距離內填深

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


def build_bubble_mask(g, regions, seg, lab, stats, excluded_ids, charmask=None):
    """氣泡內部遮罩（修法1）：每文字區 bbox+BUBBLE_PAD 窗內，找「貼著（外擴後）
    文字筆畫」的白色連通元件，通過守門則整顆併入（不裁窗 ⇒ 無截斷方塊，
    原型 regrow 補救移除）。守門（不併＝該區只保留筆畫，安全降級）：
      整頁佔比 ≤ BUBBLE_COMP_MAX_FRAC（格內背景白太大，不是氣泡）
      面積 ≤ BUBBLE_LOCAL_K × 搜尋窗（局部性：氣泡跟它的字同尺度）
      不在 excluded_ids（留白/格內白元件）
    """
    H, W = g.shape
    seg_u8 = seg.astype(np.uint8) * 255
    seg_dil = cv2.dilate(seg_u8, np.ones((9, 9), np.uint8))  # 筆畫外擴→碰得到氣泡白底
    bubble = np.zeros((H, W), bool)
    merged, rejected, cored = set(), set(), set()
    # 先數「每個白元件被幾個文字區命中」：相連的雙泡是**同一個白元件**（ch34_015 左下格 4.78% 頁），
    # 用單一字框當分母會讓比值假性超標（2.32/3.96 > 2.0）⇒ 兩顆泡都被拒收、內部留場景灰、
    # 只剩泡框被描亮成粗白環（使用者回報）。分母改成該元件**所有**命中字區的長邊²總和。
    comp_den = {}
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        cx0, cy0 = max(0, x0 - BUBBLE_PAD), max(0, y0 - BUBBLE_PAD)
        cx1, cy1 = min(W, x1 + BUBBLE_PAD), min(H, y1 + BUBBLE_PAD)
        lab_c = lab[cy0:cy1, cx0:cx1]
        for i in np.unique(lab_c[(seg_dil[cy0:cy1, cx0:cx1] > 0) & (lab_c > 0)]):
            comp_den[int(i)] = comp_den.get(int(i), 0) + max(1, max(x1 - x0, y1 - y0) ** 2)
    for r in regions:
        x0, y0, x1, y1 = r["bbox"]
        cx0, cy0 = max(0, x0 - BUBBLE_PAD), max(0, y0 - BUBBLE_PAD)
        cx1, cy1 = min(W, x1 + BUBBLE_PAD), min(H, y1 + BUBBLE_PAD)
        win_area = (cx1 - cx0) * (cy1 - cy0)
        lab_c = lab[cy0:cy1, cx0:cx1]
        touch = np.unique(lab_c[(seg_dil[cy0:cy1, cx0:cx1] > 0) & (lab_c > 0)])
        for i in touch:
            if (i in merged or int(i) in rejected) and i not in excluded_ids:
                continue
            a = int(stats[i, cv2.CC_STAT_AREA])
            if a > BUBBLE_COMP_MAX_FRAC * g.size or a > BUBBLE_LOCAL_K * win_area:
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
                bubble[by:by + bh, bx:bx + bw] |= core
                merged.add(int(i))
                cored.add(int(i))
                continue
            else:
                bubble |= lab == i
            merged.add(int(i))
        bubble[y0:y1, x0:x1] |= seg[y0:y1, x0:x1]       # 區內筆畫本身一定算氣泡內容
    return bubble, merged, rejected, cored


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
            if ok and not eaten_mid_ok and not frameless and i in panel_ids:
                # E1：中段 eaten 的 panel 白改走核心填色（格框種子、切窄頸）+ 區域級保護，不整顆拒
                promoted.add(i)
            if ok and i in hug:
                # 弱貼框（0.25–0.40）擢升元件過原門後也走核心填色——當初只給強貼框，弱貼框整顆填，
                # demo01 主角臉（hug 0.327、textOn 0.289 走 eaten 逃生門放行）就是這樣被塗黑的。
                # 擢升元件一律核心填色：它們本來就是「不與留白連通、只靠貼框證據」的不確定背景。
                promoted.add(i)
        met["accept"] = bool(ok)
        audit.append(met)
        if ok:
            accept.add(i)
            if not frameless:
                # 安全策略：panel 白也走核心填色（格框種子、切窄頸、厚墨灰暈），不整顆填。
                # 守護框歸因：貼紙單獨 12 框違規、7 是白髮＝整顆填把連進背景的髮絲白吃掉。
                promoted.add(i)
    return accept, audit, promoted


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


def paint_sticker(out, g, lab, stats, accept, bubble, core_ids=(), frame=None, seg=None,
                  charmask=None):
    """修法4 合成：W 填深、前景描白邊（dilate(F, r) ∩ W）、W 內孤立小噪點吞掉、
    eaten 聚團區域級保護（不填黑、原樣留 D2）。

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
        o[(fill | noise) & ~protect] = BG
        o[band] = STROKE_OBJ_V
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


def texture_veto2(fill, g, frame, seg, bubble):
    """把「有線稿」的塊從留白填色裡剔掉。回傳新的 fill。"""
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
            kc = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (GT2_CLOSE * 2 + 1,) * 2)
            veto = (cv2.morphologyEx(veto.astype(np.uint8), cv2.MORPH_CLOSE, kc) > 0) & cands
    if veto.any():
        kk = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (GT2_PAD * 2 + 1,) * 2)
        veto = (cv2.dilate(veto.astype(np.uint8), kk) > 0) & ~bubnear[ry0:ry1, rx0:rx1]
        # 泡附近不算密度（泡輪廓會污染），但要**跟著外圈走**：外圈被否決就一起否決，
        # 外圈留黑就一起留黑——否則背景變灰時那 25px 會浮成一圈黑環。
        kb = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (GT2_BUBDIL * 2 + 1,) * 2)
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
    out[band] = np.maximum(out[band], BG + a[band] * (INK - BG))
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
    out[band] = np.maximum(out[band], BG + ink_alpha(g, 1.6)[band] * (INK - BG))
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
            bubble_rest=None, lost_bubble=None):
    """整頁合成：場景曲線 →（有框頁才）留白填深 → 貼紙式背景 → 氣泡重繪 → 人物還原。"""
    out = scene_final(g, seg).astype(np.float32)
    scene_keep = out.copy()                     # 人物區最終一律還原成場景調
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
        keep = texture_veto2(keep, g, frame, seg, bubble)   # 有線稿的白不是留白
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
        band = texture_veto2(band, g, frame, seg, bubble)   # 有線稿的白不是留白
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
    if sticker:                                         # 貼紙式背景：純白背景填黑＋前景白描邊
        out = paint_sticker(out, g, lab, stats, sticker, bubble,
                            core_ids=core_ids, frame=frame, seg=seg, charmask=charmask)
    out = paint_bubbles(out, g, bubble, seg)
    pb = build_pseudo_bubbles(g, regions, bubble, seg=seg)   # 偽泡：開口泡/字壓背景/字壓留白救回
    if pb.any():
        out = paint_bubbles(out, g, pb, seg)
    out = harmonize_enclosed_whites(out, g, lab, stats, bubble | pb | gutter)
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
        out[band] = np.maximum(out[band], BG + a2[band] * (INK - BG))
    # 語意禁填：人物區（含描邊外擴）一律還原場景調。放最後＝不必逐機制改，任何新填色
    # 機制自動受保護。**只扣氣泡/偽泡**（人物身上的對話框仍該深底亮字）——
    # ⚠️ 不能扣 gutter：白衣被塗黑正是 gutter 幹的（出血人物與頁白同元件），
    # 扣了等於把最大宗的違規排除在保護外（實測 55→52 框、几乎沒救到）。
    restore = charmask.copy()
    # ★ 真氣泡永遠贏過人物保護：氣泡是**畫在畫面之上**的圖層，它遮住後面的人物——該處根本看不到
    # 人物，把它還原成「人物的場景調」等於讓對話框變成淺色底（使用者 2026-09-15 回報）。
    # CHAR_OVER_BUBBLE 只該管**偽泡**（字直接寫在畫面上、人物在字周圍仍看得見）。
    restore &= ~bubble
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


def run_page(page_path, outdir=OUT_DEFAULT, col_w=1000, regions=None, seg=None):
    """單頁一條龍：偵測 → 遮罩 → 合成 → 落檔。回傳統計 dict（批次表用）。

    [regions] 與 [seg] 可以由外部提供，跳過偵測——**產品路徑就是這樣走的**：頁面先經過翻譯，
    文字區早就算過（存在翻譯素材裡），譯文的筆畫位置則由排版器自己知道，都不必重測一次。
    只給其中一個也行，另一個仍走偵測。
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
    bubble, merged, rejected, cored = build_bubble_mask(
        g, regions, seg, lab, stats, gutter_ids | panel_ids, charmask=charmask)
    sticker, audit, promoted = sticker_plan(g, img, lab, stats, gutter_ids, panel_ids,
                                            frameless, regions)
    gutter_show = gutter_ids - sticker if frameless else gutter_ids
    gutter = np.isin(lab, sorted(gutter_show)) if gutter_show else np.zeros((H, W), bool)
    panel_show = panel_ids - sticker
    panel_scene = np.isin(lab, sorted(panel_show)) if panel_show else np.zeros((H, W), bool)
    sticker_mask = np.isin(lab, sorted(sticker)) if sticker else np.zeros((H, W), bool)
    lhm, lvm = frame_line_mask(g)
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
        if bubble.any():
            # ⚠️ 只填**泡框周圍**這一圈：貼紙的核心填色保護是為了留住「被吃的前景白」（白鬍老人的
            # 鬍子/髮絲），全部取消會把它們吃掉（實測違規 28→52）。使用者抱怨的是泡外那一圈，
            # 限制在泡附近即可兩全。
            kn = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (BUBBLE_REST_NEAR * 2 + 1,) * 2)
            rest &= cv2.dilate(bubble.astype(np.uint8), kn) > 0
        rest &= ~charmask           # 背景填色一律讓開人物
        bubble_rest = rest
    else:
        bubble_rest = None
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
    for i in range(1, nb):
        a_b = int(st_b[i, cv2.CC_STAT_AREA])
        if a_b < 4000:
            continue
        bx_, by_, bw_, bh_ = (int(st_b[i, 0]), int(st_b[i, 1]), int(st_b[i, 2]), int(st_b[i, 3]))
        sy = slice(max(0, by_ - 2), by_ + bh_ + 2)
        sx = slice(max(0, bx_ - 2), bx_ + bw_ + 2)
        blob = lb_b[sy, sx] == i
        hh, ww = blob.shape
        ffm = np.zeros((hh + 2, ww + 2), np.uint8)
        tmp = blob.astype(np.uint8).copy()
        cv2.floodFill(tmp, ffm, (0, 0), 2)
        holes = (tmp != 2) & ~blob & ~segd_c[sy, sx]
        if float(holes.sum()) / a_b >= BUBBLE_CLEAN_WINS:
            continue
        # 保險②：**文字佔比**。真泡是容器，字只佔一部分（實測 8.7–50.7%）；
        # 被誤判的白髮/白手區塊幾乎全是字筆畫本身（demo04 垂髮 94.7%、demo01 那些 99–100%），
        # 因為 build_bubble_mask 最後會把字區內的筆畫一律併進泡。⇒ 文字佔比過高＝不是泡。
        if float(seg[sy, sx][blob].mean()) > BUBBLE_CLEAN_TEXT_MAX:
            continue
        clean[sy, sx] |= blob
    bubble_guard = bubble_guard & ~clean
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
    final = compose(g, gutter, bubble, seg, frameless, lab, stats, sticker,
                    core_ids=promoted, frame=(lhm | lvm), regions=regions, charmask=charmask,
                    char_raw=char_raw, bubble_rest=bubble_rest, lost_bubble=lost)

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
              f"-> {'ACCEPT' if a['accept'] else 'fallback'}", flush=True)
    return st


def main():
    ap = argparse.ArgumentParser(description="夜讀重繪（單頁）")
    ap.add_argument("page", help="頁圖路徑")
    ap.add_argument("-o", "--outdir", default=OUT_DEFAULT)
    a = ap.parse_args()
    run_page(a.page, a.outdir)


if __name__ == "__main__":
    main()
