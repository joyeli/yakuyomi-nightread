"""yakuyomi-nightread 研究腳本的路徑設定。

夜讀研究只吃「偵測輸出」（DBNet 行框 + 筆畫遮罩 + m-i-t 分組），這些載入器住在
yakuyomi-engine 的 parity/（export_dbnet_ncnn.py、mit_grouping.py），不在此重複——
以 YAKU_ENGINE_CLONE 指向 engine clone，把它的 parity/ 加進 sys.path。
engine parity 的 paths.py 會被那些載入器 `import paths` 用到，而此檔同名會遮住它，
所以這裡把 engine paths 的公開名稱全部轉出，再覆蓋本 repo 自己的 OUT / SANDBOX_TEST。

環境變數：
  YAKU_ENGINE_CLONE  yakuyomi-engine clone（預設 /mnt/d/Gits/Yakuyomi）
  其餘（YAKU_MIT_CLONE / YAKU_CKPT_DIR …）沿用 engine parity 的定義。
"""
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ENGINE_CLONE = os.environ.get("YAKU_ENGINE_CLONE", "/mnt/d/Gits/Yakuyomi")
ENGINE_PARITY = os.path.join(ENGINE_CLONE, "parity")
if not os.path.isdir(ENGINE_PARITY):
    raise SystemExit(f"找不到 yakuyomi-engine 的 parity/：{ENGINE_PARITY}（設 YAKU_ENGINE_CLONE）")
if ENGINE_PARITY not in sys.path:
    sys.path.append(ENGINE_PARITY)          # append：本 repo 的 research/ 仍優先

_spec = importlib.util.spec_from_file_location("_engine_paths", os.path.join(ENGINE_PARITY, "paths.py"))
_ep = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_ep)
for _k in dir(_ep):
    if not _k.startswith("_") and _k not in ("HERE", "ROOT"):   # HERE/ROOT 保留本 repo 的
        globals()[_k] = getattr(_ep, _k)
ENGINE_ROOT = _ep.ROOT

# 本 repo 自己的位置（覆蓋 engine 的）
OUT = os.path.join(HERE, "out")                          # research/out（gitignore）
SANDBOX_TEST = os.path.join(ROOT, "fixtures", "pages")   # 11 張測試頁
FIXTURE_BASELINE = os.path.join(ROOT, "fixtures", "baseline")
os.makedirs(OUT, exist_ok=True)
