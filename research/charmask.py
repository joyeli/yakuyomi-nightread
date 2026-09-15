#!/usr/bin/env python3
"""charmask.py — 人物前景遮罩探針：三個候選模型各自對 fixture 頁產生「禁填區」。

動機：守護框證明夜讀紅線（絕不塗黑臉/手/白衣/白髮）在**無語意**下不可達——局部幾何與灰階
特徵全部試過（深度、面積比、實心度、貼厚墨、貼框、肥留白），臉頰的白與背景的白在像素層
本來就是同一種白。唯一出路是知道「哪裡是人物」。

本檔只做一件事：把候選模型的輸出轉成 `<page>_char.png`（255=人物前景），交由
`nightread.py` 當禁填區（NIGHTREAD_CHARMASK=<dir>）、再用 `nightread_guard.py` 量違規降多少。
**這是選型探針，不是產品程式碼**——產品要用哪顆、要不要自訓，看這裡跑出來的數字。

候選（授權與調研見 docs/DECISIONS.md）：
  cseg    CartoonSegmentation / AnimeInstanceSegmentation（**MIT 權重**，238MB fp32 / 60MB int8，
          RTMDet-Ins + IS-Net；CVPR2025《Advancing Manga Analysis》在 927 張 Manga109 人工標註頁上
          量到 character body Mask AP **0.923＝全表最高**（注意：該模型訓練用過整個 Manga109 ⇒
          分數樂觀）。**本專案首選**。ONNX: huggingface.co/Jakaline/CartoonSegmentationOnnx
  isnet   SkyTNT anime-segmentation（Apache-2.0，176MB，**彩色動畫訓練**，黑白漫畫零文件佐證）
  yolodet deepghs/manga109_yolo（**黑白漫畫訓練**，10MB ONNX，只有 bbox：body/face/frame/text）
  yoloseg anonimkaq4 manga-page-element-segmentation（**黑白漫畫訓練 + 像素遮罩**，需 ultralytics）

用法：
  python3 charmask.py isnet              # 全 fixture 頁 → out/char_isnet/<page>_char.png
  python3 charmask.py yolodet --kinds body face
  python3 charmask.py yoloseg demo01     # 需 pip install ultralytics（AGPL-3.0，僅研究端）
  python3 charmask.py combine            # 定案配方：cseg ∪ yoloseg ∪ (isnet ∩ 漏抓框) → out/char_combine
"""
import argparse
import glob
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

MODEL_DIR = os.path.join(paths.OUT, "models")
DEFAULT_PAGES = ["demo01", "demo02", "demo03", "demo04", "demo05", "demo06",
                 "ch34_006", "ch34_010", "ch34_011", "ch34_014", "ch34_015"]

# deepghs/manga109_yolo 的類別順序（labels.json）
M109_CLASSES = ["text", "face", "body", "frame"]
BOXES = {}   # yolodet 副產物：逐頁 bbox 清單（供 nightread 的「遮罩漏抓偵測」veto 用）


def resolve(tok):
    if os.path.sep in tok or os.path.exists(tok):
        return tok
    hits = sorted(glob.glob(os.path.join(paths.SANDBOX_TEST, tok + ".*")))
    if not hits:
        raise SystemExit(f"找不到測試頁：{tok}")
    return hits[0]


# ── isnet（SkyTNT anime-segmentation）────────────────────────────────
def run_isnet(pages, outdir):
    """固定 1024² 輸入、只 /255（無 mean/std，照 HF Space 的 app.py）；輸出單通道 mask。"""
    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, "isnetis.onnx"),
                                providers=["CPUExecutionProvider"])
    for p in pages:
        img = cv2.imread(p)
        h, w = img.shape[:2]
        # 等比縮到 1024 長邊 + 置中 pad（不變形，否則遮罩對不回原圖）
        s = 1024 / max(h, w)
        nh, nw = int(round(h * s)), int(round(w * s))
        r = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
        cv = np.zeros((1024, 1024, 3), np.uint8)
        oy, ox = (1024 - nh) // 2, (1024 - nw) // 2
        cv[oy:oy + nh, ox:ox + nw] = r
        x = cv2.cvtColor(cv, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        x = np.transpose(x, (2, 0, 1))[None]
        m = sess.run(None, {"img": x})[0][0, 0]
        m = m[oy:oy + nh, ox:ox + nw]
        m = cv2.resize(m, (w, h), interpolation=cv2.INTER_LINEAR)
        yield p, (m > 0.5).astype(np.uint8) * 255


# ── yolodet（deepghs/manga109_yolo，bbox）─────────────────────────────
def run_yolodet(pages, outdir, kinds=("body", "face"), conf=0.25, size=1024):
    """YOLO11 偵測輸出 [1, 4+nc, N]；取指定類別的框，框內全填當禁填區（bbox 不是遮罩、
    會連背景一起保護 ⇒ 覆蓋率代價大，但臉框幾乎全是臉，是最穩的保險）。"""
    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, "manga109_n.onnx"),
                                providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    want = {M109_CLASSES.index(k) for k in kinds}
    for p in pages:
        img = cv2.imread(p)
        h, w = img.shape[:2]
        s = size / max(h, w)
        nh, nw = int(round(h * s)) // 32 * 32, int(round(w * s)) // 32 * 32
        r = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
        x = cv2.cvtColor(r, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        x = np.transpose(x, (2, 0, 1))[None]
        out = sess.run(None, {iname: x})[0][0]          # [4+nc, N]
        out = out.T
        mask = np.zeros((h, w), np.uint8)
        for row in out:
            cx, cy, bw, bh = row[:4]
            scores = row[4:]
            c = int(np.argmax(scores))
            if scores[c] < conf or c not in want:
                continue
            x0 = int((cx - bw / 2) / nw * w); x1 = int((cx + bw / 2) / nw * w)
            y0 = int((cy - bh / 2) / nh * h); y1 = int((cy + bh / 2) / nh * h)
            cv2.rectangle(mask, (max(0, x0), max(0, y0)), (min(w - 1, x1), min(h - 1, y1)), 255, -1)
            BOXES.setdefault(os.path.splitext(os.path.basename(p))[0], []).append(
                [max(0, x0), max(0, y0), min(w - 1, x1), min(h - 1, y1)])
        yield p, mask


# ── yoloseg（anonimkaq4，像素遮罩）────────────────────────────────────
def run_yoloseg(pages, outdir, conf=0.25):
    """ultralytics 直跑 .pt（研究端才裝，AGPL-3.0 不進產品）。取 character 類的遮罩聯集。"""
    from ultralytics import YOLO
    model = YOLO(os.path.join(MODEL_DIR, "manga_seg_s.pt"))
    for p in pages:
        img = cv2.imread(p)
        h, w = img.shape[:2]
        res = model.predict(p, imgsz=1024, conf=conf, verbose=False)[0]
        mask = np.zeros((h, w), np.uint8)
        names = res.names
        if res.masks is not None:
            for mm, cls in zip(res.masks.data.cpu().numpy(), res.boxes.cls.cpu().numpy()):
                if "character" not in str(names[int(cls)]).lower():
                    continue
                m = cv2.resize(mm.astype(np.uint8), (w, h), interpolation=cv2.INTER_NEAREST)
                mask[m > 0] = 255
        yield p, mask


# ── cseg（CartoonSegmentation / AnimeInstanceSegmentation，RTMDet-Ins + IS-Net refiner）──
CSEG_MEAN = np.array([103.53, 116.28, 123.675], np.float32)     # BGR，mmdet RTMDet 標準
CSEG_STD = np.array([57.375, 57.12, 58.395], np.float32)


def run_cseg(pages, outdir, size=640, score=0.3, model="cartoonseg.onnx"):
    """ONNX 直跑（圖內含 NMS，不需 mmdet/mmcv）。輸出 dets[N,5] / labels / masks[N,H,W]。

    ★ size=640 是訓練解析度、也是實測最佳：640 臉覆蓋 98.3% ＞ 800 97.9% ＞ **1024 89.6%（變差）**
    ——別照抄 DBNet 的 1024。前處理＝等比縮放後**右下角** pad（mmdet 慣例，非置中）。
    """
    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, model), providers=["CPUExecutionProvider"])
    for p in pages:
        img = cv2.imread(p)
        h, w = img.shape[:2]
        s = size / max(h, w)
        nh, nw = int(round(h * s)), int(round(w * s))
        canvas = np.full((size, size, 3), 114, np.uint8)
        canvas[:nh, :nw] = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
        x = ((canvas.astype(np.float32) - CSEG_MEAN) / CSEG_STD).transpose(2, 0, 1)[None]
        dets, labels, masks = sess.run(None, {"input": x})
        dets, masks = dets[0], masks[0]
        keep = dets[:, 4] > score
        mask = np.zeros((h, w), np.uint8)
        for m in masks[keep]:
            # 遮罩輸出是**機率**（0..1）不是 logit ⇒ 門檻 0.5；用 >0 會整頁前景（實測 99%）。
            mm = (m[:nh, :nw] > 0.5).astype(np.uint8)   # 遮罩已是輸入解析度，先裁掉 pad
            mask[cv2.resize(mm, (w, h), interpolation=cv2.INTER_NEAREST) > 0] = 255
        yield p, mask


# ── combine（定案配方）────────────────────────────────────────────────
def run_combine(pages, outdir, base=("cseg_tiled", "yoloseg"), gap="isnet", boxes_dir="char_yolodet", min_cov=0.15):
    """定案遮罩 = 精準遮罩（cseg ∪ yoloseg）∪（isnet ∩ 漏抓框）。

    isnet 是肥遮罩（彩色動畫訓練、會把整顆對話泡當人物 ⇒ 泡被人物保護還原成灰＝使用者回報的
    「白色泡泡」），但它漏的跟另外兩顆不同（ch34_006 那隻手：cseg 0% / yoloseg 0% / isnet 100%）。
    ⇒ 只在「manga109 偵測框內、精準遮罩覆蓋 < min_cov」的漏抓框裡採用 isnet。
    base 的 cseg 用**切塊版**（cseg_tiled 2×2）：遮罩覆蓋隨物體變小而單調下降（原圖短邊 15–25px
    只有 79%、120px 以上 96%），切塊提高有效解析度後整體覆蓋 85%→89%、小物<40px 74%→78%。
    接管線實測：違規 31→27、亮區只動 0.1pt、白泡不變＝**純賺**。3×3+門檻0.15 覆蓋更高（小物 87%）
    但遮罩肥到 47.6%（單次 35.8%）⇒ 違規 19 卻亮區 37.2→41.6%、白泡暴增 2.5 倍，不划算。
    實測（A 模式）：三合一全聯集 違規 30 / 白泡 31 萬 px；本配方 違規 27 / 白泡 19 萬（無遮罩底線 21 萬）。
    需先跑：charmask.py cseg / yoloseg / isnet / yolodet --kinds body face（產 boxes.json）。"""
    import json
    with open(os.path.join(paths.OUT, boxes_dir, "boxes.json"), encoding="utf-8") as f:
        boxes = json.load(f)
    for p in pages:
        name = os.path.splitext(os.path.basename(p))[0]
        ms = [cv2.imread(os.path.join(paths.OUT, f"char_{b}", f"{name}_char.png"), 0) > 127 for b in base]
        prec = np.logical_or.reduce(ms)
        g = cv2.imread(os.path.join(paths.OUT, f"char_{gap}", f"{name}_char.png"), 0) > 127
        veto = np.zeros_like(prec)
        for x0, y0, x1, y1 in boxes.get(name, []):
            if x1 > x0 and y1 > y0 and prec[y0:y1, x0:x1].mean() < min_cov:
                veto[y0:y1, x0:x1] = True
        # 精準遮罩另存一份：nightread 用它修剪泡遮罩（combine 含 isnet、會把整顆泡當人物）
        cv2.imwrite(os.path.join(outdir, f"{name}_precise.png"), prec.astype(np.uint8) * 255)
        yield p, ((prec | (g & veto)).astype(np.uint8) * 255)


def _cseg_one(sess, img, size=640, score=0.3):
    """對單張影像跑一次 cseg，回傳原尺寸的 bool 遮罩。"""
    h, w = img.shape[:2]
    s = size / max(h, w)
    nh, nw = max(1, int(round(h * s))), max(1, int(round(w * s)))
    canvas = np.full((size, size, 3), 114, np.uint8)
    canvas[:nh, :nw] = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    x = ((canvas.astype(np.float32) - CSEG_MEAN) / CSEG_STD).transpose(2, 0, 1)[None]
    dets, labels, masks = sess.run(None, {"input": x})
    dets, masks = dets[0], masks[0]
    out = np.zeros((h, w), bool)
    for m in masks[dets[:, 4] > score]:
        mm = (m[:nh, :nw] > 0.5).astype(np.uint8)
        out |= cv2.resize(mm, (w, h), interpolation=cv2.INTER_NEAREST) > 0
    return out


def run_cseg_tiled(pages, outdir, size=640, score=0.3, grid=2, overlap=0.25,
                   model="cartoonseg.onnx"):
    """切塊推論：頁面切 grid×grid（含重疊）各跑一次 cseg，再與全頁一次的結果聯集。

    動機（2026-09-16 量測）：遮罩覆蓋隨物體尺寸單調下降——原圖短邊 15–25px 的前景只有 79%
    覆蓋、120px 以上有 96%。cseg 輸入是 640，遠景小人物縮完只剩 7–11px，模型抓不到。
    直接加大輸入無效（1024 反而掉到 89.6% 臉覆蓋，訓練解析度就是 640），切塊才真正提高
    **有效解析度**：2×2 讓每塊的物體放大約 2 倍。全頁那次保留，負責大實例與跨塊的人物。
    """
    import onnxruntime as ort
    sess = ort.InferenceSession(os.path.join(MODEL_DIR, model), providers=["CPUExecutionProvider"])
    for p in pages:
        img = cv2.imread(p)
        h, w = img.shape[:2]
        mask = _cseg_one(sess, img, size, score)          # 全頁一次（大實例）
        th, tw = int(h / grid * (1 + overlap)), int(w / grid * (1 + overlap))
        for gy in range(grid):
            for gx in range(grid):
                y0 = min(h - th, max(0, int(gy * h / grid - th * overlap / 2)))
                x0 = min(w - tw, max(0, int(gx * w / grid - tw * overlap / 2)))
                sub = img[y0:y0 + th, x0:x0 + tw]
                if sub.size == 0:
                    continue
                mask[y0:y0 + th, x0:x0 + tw] |= _cseg_one(sess, sub, size, score)
        yield p, (mask.astype(np.uint8) * 255)


RUNNERS = {"isnet": run_isnet, "cseg_tiled": run_cseg_tiled, "yolodet": run_yolodet, "yoloseg": run_yoloseg, "cseg": run_cseg,
           "combine": run_combine}


def main():
    ap = argparse.ArgumentParser(description="人物前景遮罩探針")
    ap.add_argument("model", choices=sorted(RUNNERS))
    ap.add_argument("pages", nargs="*", default=DEFAULT_PAGES)
    ap.add_argument("-o", "--outdir")
    ap.add_argument("--kinds", nargs="*", default=["body", "face"], help="yolodet 用")
    ap.add_argument("--grid", type=int, default=2, help="cseg_tiled 切塊數")
    ap.add_argument("--score", type=float, default=0.3, help="cseg_tiled 分數門檻")
    a = ap.parse_args()
    outdir = a.outdir or os.path.join(paths.OUT, f"char_{a.model}")
    os.makedirs(outdir, exist_ok=True)
    pages = [resolve(t) for t in a.pages]
    kw = {"kinds": tuple(a.kinds)} if a.model == "yolodet" else {}
    if a.model == "cseg_tiled":
        kw = {"grid": a.grid, "score": a.score}
    for p, mask in RUNNERS[a.model](pages, outdir, **kw):
        name = os.path.splitext(os.path.basename(p))[0]
        cv2.imwrite(os.path.join(outdir, f"{name}_char.png"), mask)
        print(f"{name:10s} 前景 {mask.mean() / 255 * 100:5.1f}%")
    if BOXES:
        import json
        with open(os.path.join(outdir, "boxes.json"), "w", encoding="utf-8") as f:
            json.dump(BOXES, f)
    print(f"→ {outdir}")


if __name__ == "__main__":
    main()
