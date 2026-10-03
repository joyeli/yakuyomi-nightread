#!/usr/bin/env python3
"""make_obj_fixture.py — 「更多」背景物件規則（nightread_obj.py）的 Kotlin parity fixture（`BgObjectsParityTest`）。

一、原語（nightread/src/test/resources/obj/）：
    gauss_w.txt         σ 1.5／2／2.5／3／4／5 的整數高斯核（gauss_w）
    line_offsets.txt    17 px 與 11 px 線核 8 方向的位置（line_offsets）
    text_lut.txt        字筆畫亮度查表（text_lut，repr）
    prim_gray.png       測試灰階：ch34_011 一塊 192×160 ＋ 右邊接 64×160 的隨機方塊（Canny 各方向、邊界）
    prim_canny_15_40.png／prim_canny_10_25.png   cv2.Canny（關 IPP）
    prim_median7.png    cv2.medianBlur 7
    prim_gauss_<σ>.bin  gauss_q16（σ 1.5 與 5；int32 little-endian、w×h）
    prim_chamfer5.bin   5×5 chamfer（關 IPP）的 float32，輸入＝prim_gray ≥ 128
    quadfit.txt         兩組點的 quadfit（取樣步長 1 與 2）：每組一行「n0 s 期望值(repr)」再接取樣後的 gq ys xs
    fx_lut.txt／spark_lut.txt   效果線區與閃光的亮度查表（ink_lut，repr）
    fx_half_dir.txt     nightread_fx.half_dir 的 (d, b) → (ux, uy)（repr；含零向量、負 d、b＝0 的邊界）
    fx_thin_in.png／fx_thin.png   ch34_015 細暗線（扣 X3）一塊 320×320 的 Zhang–Suen 細化
    fx_segs.txt         同一塊的直分支（nightread_fx.segments：ax ay bx by sd，repr）
二、整頁（resources/page/<頁>_*）：
    ch34_010：同時有亮背景區塗黑（裁定 3 的 A3 白地板）與否決（A4 牆板窄條、A1 牆），也有字幕塊；沒有效果線族。
    ch34_015：有效果線族（裁定 2 A：上排右格頂的集中線塗黑、線留亮），否決（A1 壁燈）走效果線的例外判斷。
    輸入＝研究端 47 頁 parity 用的同一份（原圖灰階、DBNet 字遮罩、字區、人物遮罩、彩度）；期望＝研究端「更多」
    （MORE=1）跑 run_page 時 nightread_obj 的整頁量測遮罩（含閃光、效果墨、地盤、成員線）、否決的輸入（extra／std_dark）與
    輸出、亮背景區的輸入（dark）與輸出（fill／band／txt／效果線區 fxpaint），加上逐區特徵（obj_rows.txt，fit 以 repr 存；
    效果線區的量測另存 obj_fxrows.txt）。

用法：python3 make_obj_fixture.py（不要設任何 NIGHTREAD_ 環境變數）
"""
import contextlib
import io
import json
import math
import os
import struct
import sys
import tempfile

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PAGES = os.path.join(ROOT, "fixtures", "pages")
RES = os.path.join(ROOT, "nightread", "src", "test", "resources")
OUT = os.path.join(HERE, "out")
MORE_ENV = dict(NIGHTREAD_STICKER_MODE="simple", NIGHTREAD_STICKER_ROUGH="20", NIGHTREAD_STICKER_MINFRAC="0",
                NIGHTREAD_HM="0", NIGHTREAD_PB="0", NIGHTREAD_MORE="1")
PNG9 = [cv2.IMWRITE_PNG_COMPRESSION, 9]


def noipp(fn, *a):
    prev = cv2.ipp.useIPP()
    cv2.ipp.setUseIPP(False)
    try:
        return fn(*a)
    finally:
        cv2.ipp.setUseIPP(prev)


def primitives(O, ob):
    os.makedirs(O, exist_ok=True)
    with open(os.path.join(O, "gauss_w.txt"), "w") as f:
        for s in (1.5, 2.0, 2.5, 3.0, 4.0, 5.0):
            f.write(f"{s} " + " ".join(str(v) for v in ob.gauss_w(s)) + "\n")
    with open(os.path.join(O, "line_offsets.txt"), "w") as f:
        for L, offs in ((17, ob._OFF17), (11, ob._OFF11)):
            for o in offs:
                f.write(f"{L} " + " ".join(f"{dy},{dx}" for dy, dx in o) + "\n")
    with open(os.path.join(O, "text_lut.txt"), "w") as f:
        f.write(" ".join(repr(float(v)) for v in ob.text_lut()) + "\n")
    with open(os.path.join(O, "fx_lut.txt"), "w") as f:
        f.write(" ".join(repr(float(v)) for v in ob.ink_lut(ob.FX_LINE_V, True)) + "\n")
    with open(os.path.join(O, "spark_lut.txt"), "w") as f:
        f.write(" ".join(repr(float(v)) for v in ob.ink_lut(ob.SPARK_V, False)) + "\n")
    import nightread_fx as fxm
    with open(os.path.join(O, "fx_half_dir.txt"), "w") as f:
        rng_ = np.random.default_rng(20261005)
        cases = [(0.0, 0.0), (1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0), (-3.0, 1e-12), (-3.0, -1e-12), (2.5, -7.0)]
        cases += [(float(a), float(b)) for a, b in rng_.normal(0, 50, (40, 2))]
        for d, b in cases:
            ux, uy = fxm.half_dir(d, b)
            f.write(f"{d!r} {b!r} {ux!r} {uy!r}\n")
    g = cv2.imread(os.path.join(PAGES, "ch34_011.jpg"), cv2.IMREAD_GRAYSCALE)[300:460, 250:442]
    rng = np.random.default_rng(20261004)
    blk = rng.integers(0, 256, (20, 8)).astype(np.uint8)
    blk = cv2.resize(blk, (64, 160), interpolation=cv2.INTER_NEAREST)
    blk = cv2.GaussianBlur(blk, (0, 0), 1.0)
    prim = np.ascontiguousarray(np.hstack([g, blk]))
    cv2.imwrite(os.path.join(O, "prim_gray.png"), prim)
    for lo, hi in ((15, 40), (10, 25)):
        cv2.imwrite(os.path.join(O, f"prim_canny_{lo}_{hi}.png"), noipp(cv2.Canny, prim, lo, hi), PNG9)
    cv2.imwrite(os.path.join(O, "prim_median7.png"), noipp(cv2.medianBlur, prim, 7), PNG9)
    for s in (1.5, 5.0):                         # 核最短與最長；其餘 σ 由整頁 fixture 間接守
        q = ob.gauss_q16(prim, s)
        with open(os.path.join(O, f"prim_gauss_{s}.bin"), "wb") as f:
            f.write(struct.pack("<ii", q.shape[1], q.shape[0]))
            f.write(q.astype("<i4").tobytes())
    ch = ob.chamfer5(prim >= 128)
    with open(os.path.join(O, "prim_chamfer5.bin"), "wb") as f:
        f.write(struct.pack("<ii", ch.shape[1], ch.shape[0]))
        f.write(ch.astype("<f4").tobytes())
    # quadfit：二次曲面 ＋ 雜訊（整數 Q16），取樣步長 1 與 2
    with open(os.path.join(O, "quadfit.txt"), "w") as f:
        for n0, w_ in ((1500, 50), (40100, 230)):
            ys, xs = np.divmod(np.arange(n0), w_)
            v = 180 + 0.01 * (xs - 20) ** 2 - 0.02 * xs * ys + 0.3 * ys + rng.normal(0, 4, n0)
            gq = np.clip(np.round(v * 65536), 0, 255 * 65536).astype(np.int32)
            s = max(1, n0 // 20000)
            fit = ob.quadfit(gq, ys, xs)
            f.write(f"{n0} {s} {fit!r}\n")
            f.write(" ".join(str(int(x)) for x in gq[::s]) + "\n")
            f.write(" ".join(str(int(x)) for x in ys[::s]) + "\n")
            f.write(" ".join(str(int(x)) for x in xs[::s]) + "\n")
    print("原語 fixture →", O)


def fx_crop(O, N, ob):
    """ch34_015 細暗線（扣 X3）一塊的細化與直分支（整頁量測照 run_page 的輸入算）。"""
    import nightread_fx as fxm
    jobs = {j["name"]: j for j in json.load(open(os.path.join(OUT, "more_and_strip/more/harden/pert/orig/jobs.json")))}
    j = jobs["ch34_015"]
    CAP = {}
    c0 = ob.context

    def context(*a, **kw):
        CAP["ctx"] = c0(*a, **kw)
        return CAP["ctx"]
    ob.context = context
    regions = json.load(open(j["det_json"]))
    regions = regions["regions"] if isinstance(regions, dict) else regions
    seg = np.load(j["det_npz"])["seg"].astype(bool)
    N.CHARMASK_DIR = j["char_dir"]
    try:
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            N.run_page(j["page"], outdir=tmp, regions=regions, seg=seg, diag={})
    finally:
        ob.context = c0
    c = CAP["ctx"]
    inkx = (c["ink"] & ~c["X3"])[60:380, 700:1020]
    cv2.imwrite(os.path.join(O, "fx_thin_in.png"), inkx.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(O, "fx_thin.png"), fxm.thin(inkx).astype(np.uint8) * 255, PNG9)
    with open(os.path.join(O, "fx_segs.txt"), "w") as f:
        for sg in fxm.segments(inkx):
            f.write(" ".join(repr(float(v)) for v in sg) + "\n")
    print("效果線原語 fixture：細化", int(fxm.thin(inkx).sum()), "px、直分支", len(fxm.segments(inkx)), "段")


def page(name, PO, N, ob):
    jobs = {j["name"]: j for j in json.load(open(os.path.join(OUT, "more_and_strip/more/harden/pert/orig/jobs.json")))}
    j = jobs[name]
    bgr = cv2.imread(j["page"], cv2.IMREAD_COLOR)
    g = cv2.imread(j["page"], cv2.IMREAD_GRAYSCALE)
    chroma = (bgr.max(axis=2).astype(np.int16) - bgr.min(axis=2)).clip(0, 255).astype(np.uint8)
    regions = json.load(open(j["det_json"]))
    regions = regions["regions"] if isinstance(regions, dict) else regions
    seg = np.load(j["det_npz"])["seg"].astype(bool)
    N.CHARMASK_DIR = j["char_dir"]
    char_raw = N.load_charmask(j["page"], g.shape)
    cv2.imwrite(os.path.join(PO, f"{name}_gray.png"), g)
    cv2.imwrite(os.path.join(PO, f"{name}_chroma.png"), chroma, PNG9)
    cv2.imwrite(os.path.join(PO, f"{name}_seg.png"), seg.astype(np.uint8) * 255, PNG9)
    cv2.imwrite(os.path.join(PO, f"{name}_char.png"), char_raw.astype(np.uint8) * 255, PNG9)
    with open(os.path.join(PO, f"{name}_regions.txt"), "w", encoding="utf-8") as f:
        for r in regions:
            f.write(" ".join(str(int(v)) for v in r["bbox"]) + "\n")
    CAP = {}
    c0, v0, l0 = ob.context, ob.veto_blocks, ob.light_fill

    def context(*a, **kw):
        CAP["ctx"] = c0(*a, **kw)
        return CAP["ctx"]

    def veto_blocks(g_, extra, std_dark, ctx, diag=None):
        CAP["extra"], CAP["std_dark"] = extra.copy(), std_dark.copy()
        r = v0(g_, extra, std_dark, ctx, diag)
        CAP["veto"] = r.copy()
        return r

    def light_fill(out, g_, ctx, diag=None, dark=None):
        CAP["dark"] = ob.blackish(out, g_)
        return l0(out, g_, ctx, diag, dark)
    ob.context, ob.veto_blocks, ob.light_fill = context, veto_blocks, light_fill
    diag = {}
    try:
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(io.StringIO()):
            N.run_page(j["page"], outdir=tmp, regions=regions, seg=seg, diag=diag)
    finally:
        ob.context, ob.veto_blocks, ob.light_fill = c0, v0, l0
    c = CAP["ctx"]
    empty = np.zeros(g.shape, bool)
    fx = c.get("fx")
    masks = dict(ink=c["ink"], bright=c["bright"], can4=c["tone_e"], tone=c["tone"], light=c["light"], E=c["E"],
                 Ev2=c["Ev2"], longz=c["longz"], X3=c["X3"], extra=CAP["extra"], stddark=CAP["std_dark"],
                 veto=CAP["veto"], dark=CAP["dark"], fill=diag.get("obj_fill", empty), band=diag.get("obj_band", empty),
                 txt=diag.get("obj_txt", empty), spark=c["spark"], fxpaint=diag["obj_fxpaint"])
    if fx is not None:
        masks.update(fxe=fx["fxe"], fxe_t=fx["fxe_t"], terr=fx["terr"], memline=fx["memline"])
    for k, m in masks.items():
        cv2.imwrite(os.path.join(PO, f"{name}_obj_{k}.png"), np.asarray(m).astype(np.uint8) * 255, PNG9)
    with open(os.path.join(PO, f"{name}_obj_rows.txt"), "w") as f:
        for r in diag["obj_rows"]:
            nl, nc, nt, ae, fit, thick, csum, marks = r["raw"]
            f.write(" ".join(str(v) for v in r["bbox"]) + f" {r['area']} {nl} {nc} {nt} {ae} {fit!r} {thick!r} {csum} {marks} "
                    f"{str(r['ok']).lower()}\n")
    fxr = [r for r in diag["obj_rows"] if "fxa" in r]
    if fxr:
        with open(os.path.join(PO, f"{name}_obj_fxrows.txt"), "w") as f:
            for r in fxr:
                f.write(" ".join(str(v) for v in r["bbox"]) + f" {r['fx_ink']} {r['fx_fe']} {r['fx_nmem']} "
                        f"{len(r['fx_fams'])} {str(r['fxa']).lower()} {r.get('fx_nc', -1)} {r.get('fx_ae', -1)} "
                        f"{r.get('fx_fit', -1.0)!r}\n")
    print(f"{name}：fill {int(masks['fill'].sum())} px、否決 {int(CAP['veto'].sum())} px、描亮邊 {int(masks['band'].sum())} px、"
          f"字 {int(masks['txt'].sum())} px、效果線區 {int(masks['fxpaint'].sum())} px、閃光 {int(masks['spark'].sum())} px、"
          f"逐區 {len(diag['obj_rows'])} 區（效果線區判定 {len(fxr)} 區）")


def main():
    for k in list(os.environ):
        if k.startswith("NIGHTREAD_"):
            sys.exit(f"{k} 有設：fixture 要用預設參數產")
    os.environ.update(MORE_ENV)
    sys.path.insert(0, HERE)
    cv2.setNumThreads(1)
    with contextlib.redirect_stdout(io.StringIO()):
        import nightread as N
    ob = N.nightread_obj
    primitives(os.path.join(RES, "obj"), ob)
    fx_crop(os.path.join(RES, "obj"), N, ob)
    page("ch34_010", os.path.join(RES, "page"), N, ob)
    page("ch34_015", os.path.join(RES, "page"), N, ob)


if __name__ == "__main__":
    main()
