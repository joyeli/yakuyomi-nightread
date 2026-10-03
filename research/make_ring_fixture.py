#!/usr/bin/env python3
"""make_ring_fixture.py — 人物外灰圈收細（nightread_ring.py）的 Kotlin parity fixture。

頁面級證據只跟「紙白正規化後的灰階」與人物原輸出有關，兩邊輸入逐像素相同 ⇒ 輸出要逐像素相同。這裡對測試資源裡已有人物遮罩的
頁算一次，存 `<頁>_ring_ev.png`（可以收細＝背景側 ∧ 空白紙）與 `<頁>_ring_conf.png`（有輪廓的人物邊界點），給 Kotlin
`RingParityTest` 比對。灰階與彩度從 fixtures/pages 的原圖讀（同 make_page_fixture.py），人物遮罩讀測試資源的 `<頁>_char.png`。

生長＋收尾另存一組真實資料：demo01 以標準檔（L2）的環境變數跑 run_page（輸入＝測試資源的灰階／文字遮罩／文字區／人物遮罩），
取人物還原前的 `D ∧ 證據`、種子與認領，存 `demo01_ring_allowed.png`／`demo01_ring_seed.png`／`demo01_ring_claim.png`。這頁的認領
有兩塊開運算後與種子斷開的孤立黑塊（40＋134 px），守「只留與種子相連」。

用法：python3 make_ring_fixture.py [頁名...] [-o 輸出夾]（預設＝測試資源夾裡所有有 `_char.png` 的頁）
"""
import argparse
import contextlib
import glob
import io
import os
import shutil
import sys
import tempfile

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PAGES = os.path.join(os.path.dirname(HERE), "fixtures", "pages")
RES = os.path.join(os.path.dirname(HERE), "nightread", "src", "test", "resources", "page")
L2_ENV = dict(NIGHTREAD_STICKER_MODE="simple", NIGHTREAD_STICKER_ROUGH="10", NIGHTREAD_STICKER_MINFRAC="0.005",
              NIGHTREAD_PB="0", NIGHTREAD_HM="0")


def main():
    ap = argparse.ArgumentParser(description="產灰圈收細的證據 fixture")
    ap.add_argument("pages", nargs="*")
    ap.add_argument("-o", "--outdir", default=RES)
    a = ap.parse_args()
    for k in list(os.environ):
        if k.startswith("NIGHTREAD_"):
            sys.exit(f"{k} 有設：fixture 要用預設參數產")
    os.environ.update(L2_ENV)                    # 證據與檔位無關；生長那組要標準檔
    sys.path.insert(0, HERE)
    import nightread as N
    import nightread_ring as R
    pages = a.pages or sorted(os.path.basename(p)[:-9] for p in glob.glob(os.path.join(RES, "*_char.png")))
    for name in pages:
        src = glob.glob(os.path.join(PAGES, name + ".*"))[0]
        bgr = cv2.imread(src, cv2.IMREAD_COLOR)
        g = cv2.imread(src, cv2.IMREAD_GRAYSCALE)
        gn, _ = N.normalize_paper(g, bgr)
        raw = cv2.imread(os.path.join(RES, f"{name}_char.png"), cv2.IMREAD_GRAYSCALE) > 127
        dbg = {}
        ev = R.evidence(gn, raw, dbg)
        cv2.imwrite(os.path.join(a.outdir, f"{name}_ring_ev.png"), ev.astype(np.uint8) * 255, [cv2.IMWRITE_PNG_COMPRESSION, 9])
        cv2.imwrite(os.path.join(a.outdir, f"{name}_ring_conf.png"), dbg["ring_conf"].astype(np.uint8) * 255,
                    [cv2.IMWRITE_PNG_COMPRESSION, 9])
        print(f"{name}: 可以收細 {int(ev.sum())} px、有輪廓邊界點 {int(dbg['ring_conf'].sum())} px")
    grow_fixture(N, a.outdir)


def grow_fixture(N, outdir, name="demo01"):
    """demo01 標準檔：人物還原前的 D ∧ 證據、種子、認領。"""
    seg = cv2.imread(os.path.join(RES, f"{name}_seg.png"), cv2.IMREAD_GRAYSCALE) > 127
    with open(os.path.join(RES, f"{name}_regions.txt"), encoding="utf-8") as f:
        regions = [{"bbox": [int(v) for v in line.split()]} for line in f if line.strip()]
    src = glob.glob(os.path.join(PAGES, name + ".*"))[0]
    diag = {}
    with tempfile.TemporaryDirectory() as tmp:
        shutil.copy(os.path.join(RES, f"{name}_char.png"), os.path.join(tmp, f"{name}_char.png"))
        N.CHARMASK_DIR = tmp
        with contextlib.redirect_stdout(io.StringIO()):
            N.run_page(src, outdir=os.path.join(tmp, "out"), regions=regions, seg=seg, diag=diag)
    allowed = diag["ring_D"] & diag["ring_side"] & diag["ring_paper"]
    for k, m in (("allowed", allowed), ("seed", diag["ring_seed"]), ("claim", diag["ring_claim"])):
        cv2.imwrite(os.path.join(outdir, f"{name}_ring_{k}.png"), m.astype(np.uint8) * 255, [cv2.IMWRITE_PNG_COMPRESSION, 9])
    print(f"{name}（標準）生長：可認領 {int(allowed.sum())} px、種子 {int(diag['ring_seed'].sum())} px、認領 {int(diag['ring_claim'].sum())} px")


if __name__ == "__main__":
    main()
