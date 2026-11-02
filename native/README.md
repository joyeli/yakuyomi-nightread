# 夜讀 C++ 核心（native/）

夜讀重繪的 C++ 版。目標是跟 Kotlin 版（`nightread/`）**逐位元相同**，所以規則版本不變、已產生的夜讀頁不用重做。
現在有地基（數值核心、型別、頁 handle 骨架、JNI 與測試工具）和第一批 5 族影像原語（階段 2，給手機探針比位元、比時間）；重繪本身還在 Kotlin。

## 目錄

| 路徑 | 內容 |
|---|---|
| `include/nightread/nr.h` | C API（以「頁」為單位的 handle）：`nr_abi`、`nr_create`／`nr_free`、`nr_put_rows`／`nr_get_rows`、`nr_peak_bytes`、`nr_cancel` |
| `core/jmath.*` | Java／Kotlin 語意的數值原語：StrictMath（fdlibm）、`Math.rint`／`round`、Kotlin `roundToInt`、飽和轉型、BigDecimal 五成雙、整數除法／位移／`floorDiv`、`Math.min`／`max`、`Double.compare`、`npSum`、`npSumChunked`、`npArgsort`、`eigh2` |
| `core/types.*` | `Mask`、`ImgI32`（＝Kotlin `Gray`）、`GrayU8`、`ImgF32`、`Kernel`、`CC`、`CcRuns`、`PackedBits`（列打包位元）、`Buf`／`Budget`（零初始化緩衝與記憶體帳） |
| `core/tables.*` | 常數表：`gaussW`、`gaussianKernel`、`Ring.gaussKernel`、`textLut`、`inkLut`、`lineOffsets` |
| `core/convert.*` | ARGB → 灰階（BT.601）／彩度、灰階 → 灰 ARGB（引擎 `NightReadRenderer` 的轉換） |
| `core/params_gen.h` | **產生的**（`tools/gen_params.py`）：參數結構（＝Kotlin `NightReadParams`，379 欄）、攤平／還原、逐欄存取、schema 雜湊、規則版本與摘要表 |
| `core/params.cpp` | `nr_params_schema` 與規則版本的編譯期檢查 |
| `core/page.cpp` | `nr.h` 的實作 |
| `core/prims.*` | 第一批影像原語：`dilatePacked`／`dilateBits`（含 `PackedRuns`）、`ccRuns`／`ccStats`、`distanceL2`、`gaussQ16`、`morphGrayGather`（`Cv.kt` 的逐步移植，包括 `ccRuns` 陣列的容量） |
| `core/fdlibm_rename.h` | 編 fdlibm 時強制引入：小端、`nr_fd_*` 改名 |
| `third_party/fdlibm/` | netlib fdlibm 5.3 原檔（一字不改；授權見該目錄 `LICENSE`） |
| `jni/nr_jni.cpp` | 產品 JNI（`li.joye.yakuyomi.nightread.NightNative`）；只用 Java 陣列，`std::bad_alloc` → `OutOfMemoryError` |
| `jni/nr_prims_jni.cpp` | 原語的 JNI（`NightNativePrims`，只給差分測試與手機探針）：輸入輸出都複製、不持有 critical，另外回傳原語本身的耗時與原生峰值 |
| `test/` | 原生測試（`test_main.cpp`）、golden 檢查（`golden_check.cpp`）、測試案例產生器（`cases.*`）、JNI 測試橋（`test_jni.cpp`：主機的 `.so`，以及引擎 sandbox 探針開 `NR_PROBE_JNI` 的 `.so`；產品沒有）、錄出的讀取與檢查（`nrd.h`、`sha256.h`、`dump_check.cpp`） |
| `tools/` | 建置與檢查腳本（下面） |

## 規則（為什麼要這些旗標）

- `-O2 -ffp-contract=off -fno-fast-math -fwrapv -fno-strict-aliasing -fno-builtin`，排在 CMake／AGP 的預設旗標之後
  （Debug 的 `-O0` 被蓋掉）。最重要的是 `-ffp-contract=off`：NDK clang 預設會把 `a*b+c` 合成 FMA，arm64 上結果差 1 ulp。
- arm64 只用 `-march=armv8-a`；不開執行緒、不用 OpenMP；沒有全域可變狀態；不遞迴、不在堆疊放大陣列（手機工作執行緒約 1 MB 堆疊）。
- 超越函數一律 fdlibm（Kotlin 端是 `StrictMath`）；捨入、轉型、整數運算一律經 `nr::jm`，不用 C++ 內建語意。
- Kotlin 會丟例外的輸入（NaN 的 `roundToInt`、除以 0、BigDecimal 的非有限值）在 `jmath` 是前置條件，呼叫端要自己檢查。

## 建置與測試

需要：cmake ≥ 3.22.1、gcc 或 clang（C++17）、JDK（只用 `jni.h`，`JAVA_HOME`）；arm64 另需 NDK 28.2.13676358 與 qemu-aarch64。

1. **主機原生測試**（ctest；在 256 KB 堆疊的執行緒上跑）並產生 JUnit 用的 `.so`：

   ```
   native/tools/build_host.sh                      # → native/build/host/libnightread_host.so
   ```

2. **同 JVM 差分**（JUnit：`CppJmathParityTest`、`CppTablesParityTest`、`CppPageParityTest`、`CppParamsParityTest`、`CppPrimsParityTest`、`CppNumericProbeTest`）：

   ```
   ./gradlew :nightread:testDebugUnitTest -PtestGroup=native -PnativeLib=native/build/host/libnightread_host.so
   ```

   - 每個純量函式 200 萬組（`-PnativeCases=` 可改）、陣列約 2 億個元素、常數表整表、2,593,920 個像素＋2²⁴ 種 RGB、參數 379 欄來回。
   - 5 族原語（`CppPrimsParityTest`）：寬 1／2／63／64／65／127／128／129／200／1351 × 各種高度、密度、形狀 × 26 種核（橢圓、方核、長條、單邊、
     遠離錨點、有洞、空核、隨機）；非 8 位元的灰階（Q16、負值、會環繞的任意 Int）、σ 0–10；距離平方超過 2²⁴ 的細長條（Float 開始捨入）；
     0 寬／0 高（Kotlin 丟例外 C++ 也要丟）；
     10 張 fixture 頁跑 `NightPrimWorkload`；多條執行緒同時用同一份輸入。
   - 沒給 `-PnativeLib` 時這些測試略過（平常的 `./gradlew test` 不需要 C++ 工具鏈）；`-PtestGroup=native` 則一定要有，否則失敗。
   - **新的差分測試類別一律命名 `Cpp*`**：native 組用萬用字元收、fast 組排除。用到原生庫卻沒取這個名字的類別，在其他組會直接失敗
     （不是略過）；native 組跑完也會檢查上面六個類別真的跑到了。
   - `.so` 登記成測試任務的輸入、golden 目錄登記成輸出：改了 C++ 重建 `.so`、或刪掉 golden 目錄之後，同一行指令會重跑，不會被判成 UP-TO-DATE。
   - 加 `-PgoldenOut=<目錄>` 會另外寫 golden 檔（Java 答案的分塊雜湊），給下一步用。
   - Windows 上的 Gradle 載不了 Linux 的 `.so`：在 Windows 開發時，用 Gradle 編好測試類別後，以 WSL 的 JDK 直接跑 JUnit（類別路徑同 Gradle）。

3. **手機晶片模擬**（NDK arm64 靜態測試檔在 qemu-aarch64 上跑，CPU 用 armv8.0 的 cortex-a57）：

   ```
   native/tools/ndk_build.sh                       # → native/build/ndk-arm64
   native/tools/arm64_qemu.sh <golden 目錄>         # nr_core_tests ＋ nr_golden_check
   ```

   這個建置（`NR_STATIC_TESTS`）的 `Buf` 用 malloc＋memset 而不是 calloc：scudo 把 `madvise(MADV_DONTNEED)` 過的大塊當成全 0、qemu-user 7.2 之前的
   madvise 卻什麼都不做，大塊 calloc 在模擬器上會拿到舊資料。真機沒有這個問題，產品與手機探針都走 calloc。

   `nr_golden_check` 也檢查 golden 完整：6 個檔都在、每個純量函式都有且分塊從 0 起連續、合計至少 `--min-cases`（預設 100 萬）、
   陣列三種、整表六張、像素都有，缺一項就失敗。本機用 `-PnativeCases` 縮小案例數時，直接跑 `nr_golden_check --min-cases <N>`；
   只想比一部分就加 `--partial`。

4. **sanitizer**：

   ```
   native/tools/build_host.sh native/build/asan -DNR_SANITIZE=address,undefined,float-cast-overflow -DNR_HOST_JNI=OFF
   native/tools/build_host.sh native/build/tsan -DNR_SANITIZE=thread -DNR_HOST_JNI=OFF
   ```

   TSan 在新核心（`vm.mmap_rnd_bits` > 28）上會報 `unexpected memory mapping`：用 `setarch "$(uname -m)" -R` 跑，
   或 `sudo sysctl vm.mmap_rnd_bits=28`（CI runner）。

5. **守門檢查**（native-guard）：

   ```
   native/tools/fma_scan.sh                        # arm64 物件不得有 fmadd/fmsub/fnmadd/fnmsub/fmla/fmls；主機 -mfma 版不得有 vfmadd 系列；不得參照 OpenMP
   native/tools/check_compile_flags.py native/build/ndk-arm64 native/build/host   # 最後的 -O 是 -O2、逐位元旗標沒被蓋掉、arm64 只有 armv8-a
   ```

## 手機探針（引擎 sandbox 的「夜讀 C++ 探針」）

探針在 ART 上做同行程比對，用到的都在這個 repo：

- 數值核心：`NightNumericProbe`（純量函式、陣列、常數表，參考答案表跟 `CppJmathParityTest` 同一張）。案例產生器在 C++，所以需要測試橋；
  引擎用 `-Pyakuyomi.nightProbe=true` 建置時才傳 `-DNR_PROBE_JNI=ON`，`test_jni.cpp`、`cases.cpp` 才進 `.so`。沒開時 `NightNumericProbe.available()` 回 false。
- 5 族原語：`NightPrimWorkload`、`NightNativePrims`。

## 參數（`tools/gen_params.py`）

C++ 不自己決定參數：產品路徑由 Kotlin 把 `NightReadParams` 攤平（`ParamsFlat.toFlat`，一個 `IntArray`＋一個 `DoubleArray`）
經 JNI 傳進來，C++ 照同一張欄位表還原（`nr::params::fromFlat`）。兩邊的程式都由 `gen_params.py` 從 `Params.kt` 產生、入庫：

```
python3 native/tools/gen_params.py            # 改了 Params.kt、RULES_VERSION 或 RulesVersionGuardTest 的摘要表之後重跑
python3 native/tools/gen_params.py --check    # 只檢查是不是最新的
```

- 產物：`core/params_gen.h`、`nightread/.../ParamsFlat.kt`、`include/nightread/nr.h` 的 `NR_RULES_VERSION` 那一行。
- 攤平格式：ints＝Int、Boolean（0／1）、enum（ordinal）、Int?（有無＋值兩格）；doubles＝Double、Float（精確轉 double）。
- schema 雜湊只看欄位的名稱、型別與順序；`NightNative.available()` 要 `nr_params_schema()` 等於 `ParamsFlat.SCHEMA_HASH` 才用 C++。
- 驗證：`ParamsFlatTest`（fast 組，反射逐欄比）、`CppParamsParityTest`（native 組：每回合 379 欄各不相同，Kotlin → C++ 與
  C++ → Kotlin 都用名稱逐欄比，另比預設值與不合法輸入）、`nr_core_tests` 的 `testParams`。CI 的 native-guard 重跑後 `git diff` 必須是空的。

## 錄出與重放（`NativeDumpTest`、`nr_dump_check`）

之後各階段搬一個模組，就拿 Kotlin 錄下的真頁資料與原語案例重放、逐位元比，不必等上游模組搬完。

```
./gradlew :nightread:testDebugUnitTest -PtestGroup=dump -PnativeDump=<目錄>     # 平常不跑；沒給 -PnativeDump 就略過
native/build/host/nr_dump_check [--expect-families 族,…] [--expect-ops 運算,…] [--expect-pages N] [--expect-prim-pages N] <目錄>   # 驗格式、重算摘要、重放已搬好的部分
```

- 錄出內容與格式見錄出目錄的 `README.txt`（`NativeDump.kt` 寫）：`prims/`（Cv 原語的隨機與邊界案例，輸入輸出原樣）、
  `pages/<set>/<頁>/manifest.tsv`（輸入、參數與每種跑法每個成分的摘要）、選了才有的原始資料（頁輸入 NRIN、攤平參數、任意成分）。
- 開關：`-PnativeDumpPages`（`fixtures`、頁名、本機 NRIN 目錄）、`-PnativeDumpVariants`（`all`＝五檔的 plain／diag 都錄）、
  `-PnativeDumpRaw`（`input,params` 加成分名，`*` 萬用）、`-PnativeDumpPrims`（`false` 或族名清單）、
  `-PnativeDumpPrimPages`（真頁跑原語步驟表，寫法同 `-PnativeDumpPages`；預設不跑）、`-PtestHeap`（大頁用）。
- **本機語料（c362／c371 等）的錄出不進 repo**。
- 現在 `nr_dump_check` 會重放的：`packbits`、`kernel`、`morph` 的 `dilatePacked`／`dilateBits`、`cc` 的 `ccStats`／`ccRuns`、`dist` 的 `distanceL2`、
  `gray` 的 `gaussQ16`／`morphGrayGather`（Kotlin 丟例外的案例 C++ 也要丟），`primpages/` 的每一步（真頁尺寸，逐步比輸出摘要），
  以及每頁的輸入摘要、參數（C++ fromFlat／toFlat）與原始資料的摘要。其他運算在搬到 C++ 的那一階加上重放。
- 一項都沒驗到、或某一族 0 筆時一律失敗；`--expect-families` 列的族、`--expect-ops` 列的運算一定要真的重放到、`--expect-pages N` 至少 N 頁
  真的重算了東西、`--expect-prim-pages N` 至少 N 頁跑完步驟表（CI 全部都給，期望值在 `ci.yml` 的 `NR_DUMP_EXPECT`）。
  `-PnativeDumpPrims` 的族名打錯，錄出器直接失敗。

## 5 族原語與探針（`NightNativePrims`、`NightPrimWorkload`）

手機探針（引擎 sandbox）與本機測試用同一份東西：

- `NightNativePrims`：每個原語的 Kotlin 綁定，回 `Timed(value, nanos, peakBytes)`。`nanos` 只算原語本身：輸入已複製進原生記憶體、輸出的配置算在內、
  複製回 Java 不算，對應 Kotlin 版「輸入陣列已存在、配置輸出」。呼叫前先問 `NightNative.available()`；可以在不同執行緒同時呼叫。
- `NightPrimWorkload`：一頁的步驟表（`program`）、差分（`differential`：每個原語 Kotlin 與 C++ 交錯跑 N 次，比位元、取時間中位數）與加權合計（`summarize`）。
  權重＝一頁產品兩檔實際呼叫幾次（2026-11-02 掛計數器量 6 頁的中位數；來由寫在檔頭），改了步驟或權重就加 `VERSION`。
- 用法：`differential(gray, seg, charMask, repeat = 1)` 空跑暖機，再 `differential(…, repeat = 3) { 逐步寫紀錄 }`，多頁的結果接起來給 `summarize`。

## CI（`.github/workflows/ci.yml`）

原本的四組（fast、guard、parallel、profile）之外：

| job | 內容 |
|---|---|
| `native` | 主機建置＋ctest、同 JVM 差分（`-PtestGroup=native`，寫 golden）、錄出器＋`nr_dump_check`（原語 6 族＋2 張真頁的步驟表）；golden 與錄出交給下面兩個 |
| `native-sanitize` | ASan＋UBSan（ctest、golden、錄出重放含 5 族原語）、TSan（並行測試含原語） |
| `native-guard` | `gen_params.py` 重跑後無變動；NDK 28.2 arm64 Debug／Release 建置、FMA／OpenMP 掃描與匯出符號、編譯旗標；qemu-aarch64 跑 `nr_core_tests`、golden、錄出重放（含 5 族原語與真頁步驟表） |

## 接進產品

由引擎 `inference-core` 的 CMake `add_subdirectory` 本目錄，把 `nightread_core` 與 `nightread_jni` 編進唯一的
`libyakuyomi_ncnn.so`（`NR_BUILD_TESTS`／`NR_HOST_JNI` 在非頂層專案時預設關）。Kotlin 端的 `NightNative` 不自己載入程式庫，
呼叫前先問 `NightNative.available()`（握手：`abi()` 的 ABI 版本與規則版本、`paramsSchema()` 的參數 schema 都要相同）。
