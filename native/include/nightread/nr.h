/*
 * 夜讀 C++ 核心的 C API（骨架，階段 1）。
 *
 * 形狀（cpp-eval 報告第 7 節第 2 條）：以「頁」為單位的 handle，分段呼叫——進像素 → （之後的階段：灰階前置 → 設定偵測與人物結果
 * → 分析 → 各檔合成）→ 取像素。「分開」模式會用一次全跑的包裝、「合併」模式用分段呼叫，兩者跑同一份程式碼。
 * 階段 1 只有像素進出：nr_put_rows 把 ARGB 列轉成灰階與彩度存在頁裡，nr_get_rows 取回（之後取的是合成好的檔位）。
 *
 * 規則：
 *   - 不丟 C++ 例外到呼叫端：配置失敗回 NR_E_NOMEM（JNI 轉成 java.lang.OutOfMemoryError），參數錯回 NR_E_ARG。
 *   - 不開執行緒、不用 OpenMP、沒有全域可變狀態：不同頁可在不同執行緒同時用；同一頁同時只給一條執行緒寫
 *     （之後的分段合成會明訂哪些段可並行）。nr_cancel／nr_peak_bytes 可以從任何執行緒呼叫。
 *   - 像素是 Java int 的 ARGB_8888（0xAARRGGBB），與 Bitmap.getPixels／setPixels 同格式。
 *   - 這些符號只給同一個 .so 裡的 JNI 用（可見度 hidden）；對外只匯出 JNI。
 */
#ifndef NIGHTREAD_NR_H
#define NIGHTREAD_NR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ABI 版本：C API／JNI 簽名有不相容的改動就加 1。nr_abi() 回傳 (NR_ABI_VERSION << 16) | NR_RULES_VERSION。 */
#define NR_ABI_VERSION 1
/* 夜讀規則版本：必須等於 Kotlin 的 NightRead.RULES_VERSION（tools/gen_params.py 會同步；NightNative.available() 握手時比對）。 */
#define NR_RULES_VERSION 4

/* 錯誤碼 */
#define NR_OK 0
#define NR_E_ARG (-1)       /* 參數不合法（尺寸、範圍、空指標） */
#define NR_E_NOMEM (-2)     /* 原生配置失敗 */
#define NR_E_STATE (-3)     /* 呼叫順序不對（例如還沒放完像素就取結果） */
#define NR_E_CANCELLED (-4) /* 已取消 */
#define NR_E_INTERNAL (-5)  /* 其他內部錯誤 */

/* nr_get_rows 的平面 */
#define NR_PLANE_GRAY 0      /* 灰階（BT.601，0..255），每像素一個 int */
#define NR_PLANE_CHROMA 1    /* 彩度 max(R,G,B) − min(R,G,B)，每像素一個 int */
#define NR_PLANE_GRAY_ARGB 2 /* 灰階寫成不透明灰 ARGB（NightReadRenderer.writeArgb） */

typedef struct nr_page nr_page;

int32_t nr_abi(void);

/*
 * 參數攤平格式的 schema 雜湊（native/core/params_gen.h 的 kSchemaHash，由 tools/gen_params.py 從 Params.kt 產生；
 * 只看欄位的名稱、型別與順序）。必須等於 Kotlin 的 ParamsFlat.SCHEMA_HASH（NightNative.available() 握手時比對）。
 */
int64_t nr_params_schema(void);

/* 建一頁（w、h ≥ 1，w·h ≤ 2³¹−1）。成功回 NR_OK 並把 handle 寫進 *out。 */
int32_t nr_create(int32_t w, int32_t h, nr_page** out);
/* 釋放；p 可以是 NULL。 */
void nr_free(nr_page* p);
/* 頁的寬、高（p 為 NULL 回 0）。 */
int32_t nr_width(const nr_page* p);
int32_t nr_height(const nr_page* p);

/* 放入第 y0 起的 rows 列 ARGB；第 r 列從 argb + r·stride 開始、長 w。stride ≥ w。 */
int32_t nr_put_rows(nr_page* p, int32_t y0, int32_t rows, const int32_t* argb, int64_t stride);
/* 取出第 y0 起的 rows 列（plane 見上）；第 r 列寫到 out + r·stride、長 w。被取的列必須都放過。 */
int32_t nr_get_rows(const nr_page* p, int32_t plane, int32_t y0, int32_t rows, int32_t* out, int64_t stride);

/* 這頁的原生記憶體峰值（位元組，只算頁裡的緩衝）。 */
int64_t nr_peak_bytes(const nr_page* p);

/* 要求取消：之後的長段運算在段與段之間檢查、回 NR_E_CANCELLED。可從任何執行緒呼叫。 */
void nr_cancel(nr_page* p);
int32_t nr_is_cancelled(const nr_page* p);

#ifdef __cplusplus
}
#endif

#endif /* NIGHTREAD_NR_H */
