#!/usr/bin/env python3
"""nightread_batch.py — 夜讀重繪批次：跑一組頁、產三聯對照（nightread.run_page 落的
<頁名>_cmp.png：原圖｜成品｜遮罩視覺化）＋白面積表（stdout 對齊表 + nightread_stats.json）。

用法：
  python3 nightread_batch.py                 # 預設跑 app-sandbox 的 11 張測試頁
  python3 nightread_batch.py demo01 ch34_006 # 頁名（在 sandbox test 夾裡找副檔名）
  python3 nightread_batch.py /path/to/x.jpg  # 也吃完整路徑
  python3 nightread_batch.py -o 別的輸出夾

序列跑（torch 記憶體）、DBNet 只載一次；單張爆掉記錄後續跑。
設計常數/三修法說明見 nightread.py 檔頭。
"""
import argparse
import glob
import json
import os
import sys
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nightread   # noqa: E402
import paths       # noqa: E402

# 預設頁組：sandbox 測試資產全 11 張（含三修法各自的病灶頁）
DEFAULT_PAGES = ["demo01", "demo02", "demo03", "demo04", "demo05", "demo06",
                 "ch34_006", "ch34_010", "ch34_011", "ch34_014", "ch34_015"]


def resolve(token):
    """頁名 → sandbox test 夾找檔；完整路徑原樣回。"""
    if os.path.sep in token or os.path.exists(token):
        return token
    hits = sorted(glob.glob(os.path.join(paths.SANDBOX_TEST, token + ".*")))
    if not hits:
        raise SystemExit(f"找不到測試頁：{token}（{paths.SANDBOX_TEST}）")
    return hits[0]


def main():
    ap = argparse.ArgumentParser(description="夜讀重繪批次")
    ap.add_argument("pages", nargs="*", default=DEFAULT_PAGES,
                    help="頁名（sandbox test 夾）或完整路徑；預設 11 張")
    ap.add_argument("-o", "--outdir", default=nightread.OUT_DEFAULT)
    a = ap.parse_args()

    results = []
    for tok in a.pages:
        try:
            results.append(nightread.run_page(resolve(tok), a.outdir))
        except Exception:
            traceback.print_exc()
            results.append({"page": tok, "error": traceback.format_exc(limit=3)})

    # 白面積表（白＝gray >= WHITE_MEASURE_TH）
    print(f"\n{'page':10s} {'type':10s} {'reg':>4s} {'whiteB':>7s} {'whiteA':>7s} "
          f"{'gutter':>7s} {'panelW':>7s} {'bubble':>7s} {'comps':>7s}")
    for r in results:
        if "error" in r:
            print(f"{r['page']:10s} ERROR")
            continue
        print(f"{r['page']:10s} {r['pageType']:10s} {r['regions']:4d} "
              f"{r['whiteBefore']:7.3f} {r['whiteAfter']:7.3f} {r['gutterFrac']:7.3f} "
              f"{r['panelWhiteFrac']:7.3f} {r['bubbleFrac']:7.3f} "
              f"{r['bubbleCompsMerged']:3d}/{r['bubbleCompsRejected']:<3d}")
    out_json = os.path.join(a.outdir, "nightread_stats.json")
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    print(f"\n→ {out_json}")


if __name__ == "__main__":
    main()
