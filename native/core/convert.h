// 夜讀 C++ 核心：像素進出的轉換（Bitmap 的 ARGB_8888 以 Java int 表示：0xAARRGGBB）。
// 移植自引擎 nightread-android 的 NightReadRenderer（li/joye/yakuyomi/engine/NightReadRenderer.kt @ engine f990b45）：
//   toInput 的灰階／彩度迴圈、writeArgb。
#pragma once

#include <cstdint>

namespace nr {

/**
 * NightReadRenderer.toInput：gray＝BT.601 `(R*299 + G*587 + B*114 + 500) / 1000`（＝cv2.imread 灰階，契約第 1 條），
 * chroma＝max(R,G,B) − min(R,G,B)。alpha 不看。n 個像素。
 */
void argbToGrayChroma(const int32_t* argb, int64_t n, int32_t* gray, int32_t* chroma) noexcept;

/** NightReadRenderer.writeArgb：值夾進 0..255，寫成不透明灰 ARGB（0xFF 灰 灰 灰）。 */
void grayToArgb(const int32_t* g, int64_t n, int32_t* argb) noexcept;

}  // namespace nr
