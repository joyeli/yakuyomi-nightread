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


RUNNERS = {"isnet": run_isnet, "yolodet": run_yolodet, "yoloseg": run_yoloseg, "cseg": run_cseg}


def main():
    ap = argparse.ArgumentParser(description="人物前景遮罩探針")
    ap.add_argument("model", choices=sorted(RUNNERS))
    ap.add_argument("pages", nargs="*", default=DEFAULT_PAGES)
    ap.add_argument("-o", "--outdir")
    ap.add_argument("--kinds", nargs="*", default=["body", "face"], help="yolodet 用")
    a = ap.parse_args()
    outdir = a.outdir or os.path.join(paths.OUT, f"char_{a.model}")
    os.makedirs(outdir, exist_ok=True)
    pages = [resolve(t) for t in a.pages]
    kw = {"kinds": tuple(a.kinds)} if a.model == "yolodet" else {}
    for p, mask in RUNNERS[a.model](pages, outdir, **kw):
        name = os.path.splitext(os.path.basename(p))[0]
        cv2.imwrite(os.path.join(outdir, f"{name}_char.png"), mask)
        print(f"{name:10s} 前景 {mask.mean() / 255 * 100:5.1f}%")
    print(f"→ {outdir}")


if __name__ == "__main__":
    main()
