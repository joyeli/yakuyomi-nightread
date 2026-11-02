// 夜讀 C++ 核心：第 3a 階的其餘二值原語。移植自 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/Cv.kt @ a951b7c：
//   結構元素（ellipse、rect）、縮放（resizeArea、resizeBilinear、resizeNearest、resizeNearestGray；resizeAreaOf 在 prims_resize.inl）、
//   補洞（holes）、輪廓長（totalContourLength）、多邊形面積（polygonArea）、測地生長（geodesicDistance、geodesicGrow、dilate3Bits）、
//   二值中值（medianBlurMask）。
// 逐步照抄 Kotlin 的算法與運算順序；Kotlin 會丟例外的輸入（越界、coerceIn 的空範圍、負的陣列長度）這裡在同樣的情況丟 C++ 例外。
#include <cstring>
#include <stdexcept>
#include <utility>

#include "bits.h"
#include "jmath.h"
#include "prims.h"

namespace nr {
namespace prims {

namespace {

template <class T>
inline void copyN(T* dst, const T* src, size_t n) {
    if (n > 0) std::memcpy(dst, src, n * sizeof(T));
}

inline int32_t imin(int32_t a, int32_t b) { return a < b ? a : b; }
inline int32_t imax(int32_t a, int32_t b) { return a > b ? a : b; }

void requireSameSize(const Mask& a, const Mask& b, const char* what) {
    if (a.w != b.w || a.h != b.h) throw std::invalid_argument(what);
}

/** Cv.dilate3Bits：打包位元就地 3×3 膨脹（影像外當 0）；hor 是同尺寸的工作緩衝。寬 0、高 > 0 丟（Kotlin 越界）。 */
void dilate3Bits(PackedBits& a, PackedBits& hor) {
    const int32_t w = a.w, h = a.h, nw = a.nw;
    if (nw == 0 && h > 0) throw std::invalid_argument("geodesicGrow: zero width");
    const uint64_t tail = (w & 63) == 0 ? ~uint64_t(0) : (uint64_t(1) << (w & 63)) - 1;
    uint64_t* ad = a.words.data();
    uint64_t* hd = hor.words.data();
    for (int32_t y = 0; y < h; y++) {
        const size_t b = static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw; i++) {
            const uint64_t v = ad[b + i];
            uint64_t l = v << 1;                       // out[x] |= in[x − 1]
            if (i > 0) l |= ad[b + i - 1] >> 63;
            uint64_t r = v >> 1;                       // out[x] |= in[x + 1]
            if (i + 1 < nw) r |= ad[b + i + 1] << 63;
            hd[b + i] = v | l | r;
        }
        hd[b + nw - 1] &= tail;
    }
    for (int32_t y = 0; y < h; y++) {
        const size_t b = static_cast<size_t>(y) * nw;
        for (int32_t i = 0; i < nw; i++) {
            uint64_t v = hd[b + i];
            if (y > 0) v |= hd[b - nw + i];
            if (y < h - 1) v |= hd[b + nw + i];
            ad[b + i] = v;
        }
    }
}

}  // namespace

// ── 結構元素 ─────────────────────────────────────────────────────────────

Kernel ellipse(int32_t size, Budget* b) {
    if (size < 0) throw std::length_error("ellipse: negative size");
    const int32_t r = size / 2;
    const int32_t c = size / 2;
    Buf<uint8_t> data(pixelCount(size, size));
    const double invR2 = r != 0 ? 1.0 / (static_cast<double>(r) * r) : 0.0;
    for (int32_t i = 0; i < size; i++) {
        const int32_t dy = i - r;
        if ((dy < 0 ? -dy : dy) > r) continue;
        const double v = (static_cast<double>(r) * r - static_cast<double>(dy) * dy) * invR2;
        const int32_t dx = jm::roundToInt(static_cast<double>(c) * jm::sqrt(v));
        const int32_t j1 = imax(c - dx, 0);
        const int32_t j2 = imin(c + dx + 1, size);
        for (int32_t j = j1; j < j2; j++) data[static_cast<size_t>(i) * size + j] = 1;
    }
    return Kernel(size, size, data.data(), b);
}

Kernel rect(int32_t kw, int32_t kh, Budget* b) {
    Buf<uint8_t> data(pixelCount(kw, kh));
    if (data.size() > 0) std::memset(data.data(), 1, data.size());
    return Kernel(kw, kh, data.data(), b);
}

// ── 縮放 ─────────────────────────────────────────────────────────────────

ImgF32 resizeArea(const ImgF32& f, int32_t nw, int32_t nh, Budget* b) {
    if (nw == f.w && nh == f.h) {
        ImgF32 out(f.w, f.h, b);
        copyN(out.data.data(), f.data.data(), f.data.size());
        return out;
    }
    const float* d = f.data.data();
    return resizeAreaOf(f.w, f.h, nw, nh, [d](int32_t i) { return d[i]; }, b);
}

ImgF32 resizeBilinear(const ImgF32& f, int32_t nw, int32_t nh, Budget* b) {
    if (nw == f.w && nh == f.h) {
        ImgF32 out(f.w, f.h, b);
        copyN(out.data.data(), f.data.data(), f.data.size());
        return out;
    }
    ImgF32 outImg(nw, nh, b);
    float* out = outImg.data.data();
    const float* fd = f.data.data();
    const int32_t fw = f.w, fh = f.h;
    const double sx = static_cast<double>(fw) / nw;
    const double sy = static_cast<double>(fh) / nh;
    for (int32_t y = 0; y < nh; y++) {
        // Kotlin 的 Double.coerceIn(0.0, h − 1) 在 h − 1 < 0 時丟 IllegalArgumentException
        if (fh - 1 < 0) throw std::invalid_argument("resizeBilinear: empty source");
        const double fy = jm::coerceIn((static_cast<double>(y) + 0.5) * sy - 0.5, 0.0, static_cast<double>(fh - 1));
        const int32_t y0 = jm::d2i(fy);
        const int32_t y1 = jm::min(y0 + 1, fh - 1);
        const float wy = static_cast<float>(fy - static_cast<double>(y0));
        for (int32_t x = 0; x < nw; x++) {
            if (fw - 1 < 0) throw std::invalid_argument("resizeBilinear: empty source");
            const double fx = jm::coerceIn((static_cast<double>(x) + 0.5) * sx - 0.5, 0.0, static_cast<double>(fw - 1));
            const int32_t x0 = jm::d2i(fx);
            const int32_t x1 = jm::min(x0 + 1, fw - 1);
            const float wx = static_cast<float>(fx - static_cast<double>(x0));
            const float a = fd[static_cast<size_t>(y0) * fw + x0] * (1.0f - wx) + fd[static_cast<size_t>(y0) * fw + x1] * wx;
            const float bb = fd[static_cast<size_t>(y1) * fw + x0] * (1.0f - wx) + fd[static_cast<size_t>(y1) * fw + x1] * wx;
            out[static_cast<size_t>(y) * nw + x] = a * (1.0f - wy) + bb * wy;
        }
    }
    return outImg;
}

namespace {

/** resizeNearest／resizeNearestGray 共用：Kotlin 的 data[iy·w + ix]（Int 運算）越界就丟。 */
template <class T>
void nearestInto(const T* src, size_t srcSize, int32_t w, int32_t h, T* dst, int32_t nw, int32_t nh) {
    const double sx = static_cast<double>(w) / nw;
    const double sy = static_cast<double>(h) / nh;
    for (int32_t oy = 0; oy < nh; oy++) {
        const int32_t iy = jm::min(h - 1, jm::d2i(jm::floor(static_cast<double>(oy) * sy)));
        for (int32_t ox = 0; ox < nw; ox++) {
            const int32_t ix = jm::min(w - 1, jm::d2i(jm::floor(static_cast<double>(ox) * sx)));
            const int32_t idx = iy * w + ix;
            if (idx < 0 || static_cast<size_t>(idx) >= srcSize) throw std::out_of_range("resizeNearest: index out of range");
            dst[static_cast<size_t>(oy) * nw + ox] = src[idx];
        }
    }
}

}  // namespace

Mask resizeNearest(const Mask& m, int32_t nw, int32_t nh, Budget* b) {
    Mask out(nw, nh, b);
    if (nw == m.w && nh == m.h) {
        copyN(out.data.data(), m.data.data(), m.data.size());
        return out;
    }
    nearestInto(m.data.data(), m.data.size(), m.w, m.h, out.data.data(), nw, nh);
    return out;
}

ImgI32 resizeNearestGray(const ImgI32& g, int32_t nw, int32_t nh, Budget* b) {
    ImgI32 out(nw, nh, b);
    if (nw == g.w && nh == g.h) {
        copyN(out.data.data(), g.data.data(), g.data.size());
        return out;
    }
    nearestInto(g.data.data(), g.data.size(), g.w, g.h, out.data.data(), nw, nh);
    return out;
}

// ── 洞／輪廓 ─────────────────────────────────────────────────────────────

Mask holes(const Mask& m, Budget* b) {
    const int32_t w = m.w, h = m.h;
    const size_t n = m.data.size();
    const uint8_t* md = m.data.data();
    Buf<uint8_t> reachedB(n, b);
    Buf<int32_t> stackB(n, b);
    uint8_t* reached = reachedB.data();
    int32_t* stack = stackB.data();
    size_t sp = 0;
    auto push = [&](int32_t x, int32_t y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        const int32_t i = y * w + x;
        if (reached[i] || md[i]) return;
        reached[i] = 1;
        stack[sp++] = i;
    };
    for (int32_t x = 0; x < w; x++) { push(x, 0); push(x, h - 1); }
    for (int32_t y = 0; y < h; y++) { push(0, y); push(w - 1, y); }
    while (sp > 0) {
        const int32_t i = stack[--sp];
        const int32_t x = i % w;
        const int32_t y = i / w;
        push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1);
    }
    Mask out(w, h, b);
    for (size_t i = 0; i < n; i++) out.data[i] = (!md[i] && !reached[i]) ? 1 : 0;
    return out;
}

double totalContourLength(const Mask& m, Budget* b) {
    const int32_t w = m.w, h = m.h;
    const int32_t pw = w + 2;
    const int32_t ph = h + 2;
    // 0＝背景、1＝未追、2＝已追、-126＝已追且右鄰是追蹤時檢查過的 0（cv2 的 nbd | -128）
    Buf<int32_t> imgB(pixelCount(pw, ph), b);
    int32_t* img = imgB.data();
    const uint8_t* md = m.data.data();
    for (int32_t y = 0; y < h; y++) {
        const size_t src = static_cast<size_t>(y) * w;
        const size_t dst = static_cast<size_t>(y + 1) * pw + 1;
        for (int32_t x = 0; x < w; x++) if (md[src + x]) img[dst + x] = 1;
    }
    // 方向 0..7＝右、右上、上、左上、左、左下、下、右下（cv2 的 icvCodeDeltas；y 向下）
    const int32_t delta[8] = {1, 1 - pw, -pw, -pw - 1, -1, pw - 1, pw, pw + 1};
    const double sqrt2 = 1.4142135623730951;

    // 從起點 i0 追一圈，回傳這條輪廓的閉合折線長（Kotlin 的區域函式 fetch，逐行照抄）
    auto fetch = [&](int32_t i0, bool isHole) -> double {
        const int32_t sStart = isHole ? 0 : 4;
        int32_t s = sStart;
        int32_t i1;
        do {
            s = (s - 1) & 7;
            i1 = i0 + delta[s];
        } while (img[i1] == 0 && s != sStart);
        if (s == sStart) {              // 單像素：輪廓只有一點、長 0
            img[i0] = -126;
            return 0.0;
        }
        int32_t i3 = i0;
        double len = 0.0;
        for (;;) {
            const int32_t sEnd = s;
            int32_t i4;
            do {
                s++;
                i4 = i3 + delta[s & 7];
            } while (img[i4] == 0);
            s = s & 7;
            // 找到的方向落在 1..sEnd ＝ 逆時針搜尋時經過了方向 0（右鄰）且它是 0 ⇒ 標「右界」
            if (s >= 1 && s - 1 < sEnd) img[i3] = -126;
            else if (img[i3] == 1) img[i3] = 2;
            len += (s & 1) == 0 ? 1.0 : sqrt2;
            if (i4 == i0 && i3 == i1) break;
            i3 = i4;
            s = (s + 4) & 7;
        }
        return len;
    };

    double total = 0.0;
    const int32_t width = pw - 1;
    for (int32_t y = 1; y < ph - 1; y++) {
        const int32_t row = y * pw;
        int32_t x = 1;
        int32_t prev = 0;
        while (x < width) {
            while (x < width && img[row + x] == prev) x++;
            if (x >= width) break;
            const int32_t p = img[row + x];
            bool isHole = false;
            bool skip = false;
            if (!(prev == 0 && p == 1)) {
                if (p != 0 || prev < 1) skip = true;
                else isHole = true;
            }
            if (!skip) total += fetch(row + x - (isHole ? 1 : 0), isHole);
            prev = img[row + x];         // cv2 每追完一條就 return，下次進來以（已標記的）img[x-1] 重讀 prev
            x++;
        }
    }
    return total;
}

double polygonArea(const float* xs, const float* ys, int32_t n) noexcept {
    double a = 0.0;
    for (int32_t i = 0; i < n; i++) {
        const int32_t j = (i + 1) % n;
        a += static_cast<double>(xs[i]) * static_cast<double>(ys[j]) - static_cast<double>(xs[j]) * static_cast<double>(ys[i]);
    }
    return jm::abs(a) / 2.0;
}

// ── 測地生長 ─────────────────────────────────────────────────────────────

ImgI32 geodesicDistance(const Mask& seed, const Mask& within, int32_t step, int32_t maxIters, Budget* b) {
    requireSameSize(seed, within, "geodesicDistance: seed and within differ in size");
    const int32_t w = seed.w, h = seed.h;
    const size_t n = seed.data.size();
    const uint8_t* sd = seed.data.data();
    const uint8_t* wd = within.data.data();
    ImgI32 distImg(w, h, b);
    int32_t* dist = distImg.data.data();
    for (size_t i = 0; i < n; i++) dist[i] = -1;
    size_t count = 0;
    for (size_t i = 0; i < n; i++) if (wd[i]) count++;
    Buf<int32_t> queueB(count, b);   // 每個像素一生只進一次，而且只有 within 內的像素會進
    int32_t* queue = queueB.data();
    size_t qEnd = 0;
    for (size_t i = 0; i < n; i++) {
        if (sd[i] && wd[i]) { dist[i] = 0; queue[qEnd++] = static_cast<int32_t>(i); }
    }
    size_t qStart = 0;
    int32_t done = 0;
    while (done < maxIters && qStart < qEnd) {
        const int32_t nn = jm::min(step, maxIters - done);
        done += nn;
        const size_t batchEnd = qEnd;
        while (qStart < batchEnd) {
            const int32_t idx = queue[qStart++];
            const int32_t cx = idx % w;
            const int32_t cy = idx / w;
            const int32_t y1 = jm::min(h - 1, cy + nn);
            const int32_t x0 = jm::max(0, cx - nn);
            const int32_t x1 = jm::min(w - 1, cx + nn);
            for (int32_t yy = jm::max(0, cy - nn); yy <= y1; yy++) {
                const size_t base = static_cast<size_t>(yy) * w;
                for (int32_t xx = x0; xx <= x1; xx++) {
                    const size_t j = base + xx;
                    if (dist[j] < 0 && wd[j]) { dist[j] = done; queue[qEnd++] = static_cast<int32_t>(j); }
                }
            }
        }
    }
    return distImg;
}

Mask geodesicGrow(const Mask& seed, const Mask& within, int32_t iters, int32_t step, int32_t bfs, Budget* b) {
    requireSameSize(seed, within, "geodesicGrow: seed and within differ in size");
    const int32_t w = seed.w, h = seed.h;
    const size_t n = seed.data.size();
    if (iters <= 0) {
        Mask out(w, h, b);
        for (size_t i = 0; i < n; i++) out.data[i] = (seed.data[i] && within.data[i]) ? 1 : 0;
        return out;
    }
    // 實測 BFS 每次查表比迭代每像素貴 ~3×（隨機存取），門檻放 3 倍：iters > (2·step+1)²/3
    const bool useBfs = bfs < 0 ? (static_cast<int64_t>(iters) * 3 > (2LL * step + 1) * (2LL * step + 1)) : bfs != 0;
    if (useBfs) {
        const ImgI32 dist = geodesicDistance(seed, within, step, iters, b);
        Mask out(w, h, b);
        for (size_t i = 0; i < n; i++) out.data[i] = dist.data[i] >= 0 ? 1 : 0;
        return out;
    }
    // 迭代版（位元打包）：連做 n 次 3×3 膨脹後才與 within 交集一次；長不動就停
    const PackedBits withinB = packBits(within, b);
    PackedBits cur = packBits(seed, b);
    const size_t nwords = cur.words.size();
    for (size_t i = 0; i < nwords; i++) cur.words[i] &= withinB.words[i];
    PackedBits nxt(w, h, b);
    PackedBits hor(w, h, b);
    int32_t done = 0;
    while (done < iters) {
        const int32_t nn = jm::min(step, iters - done);
        copyN(nxt.words.data(), cur.words.data(), nwords);
        for (int32_t r = 0; r < nn; r++) dilate3Bits(nxt, hor);
        for (size_t i = 0; i < nwords; i++) nxt.words[i] &= withinB.words[i];
        done += nn;
        const bool same = nwords == 0 || std::memcmp(nxt.words.data(), cur.words.data(), nwords * sizeof(uint64_t)) == 0;
        std::swap(cur, nxt);
        if (same) break;
    }
    return unpackBits(cur, b);
}

// ── 二值中值 ─────────────────────────────────────────────────────────────

Mask medianBlurMask(const Mask& m, int32_t r, Budget* b) {
    const int32_t w = m.w, h = m.h;
    const int32_t rad = r / 2;
    const int32_t need = (r * r) / 2 + 1;
    const int32_t pw = w + 2 * rad;
    const int32_t ph = h + 2 * rad;
    const int32_t stride = pw + 1;
    // replicate pad 後的積分圖：integral[(y+1)*(pw+1) + (x+1)] ＝ [0,y]×[0,x] 的 true 數。長度照 Kotlin 的 Int 運算（負的丟）
    const int32_t isz = stride * (ph + 1);
    if (isz < 0) throw std::invalid_argument("medianBlurMask: negative array size");
    Buf<int32_t> integralB(static_cast<size_t>(isz), b);
    int32_t* integral = integralB.data();
    const uint8_t* md = m.data.data();
    for (int32_t py = 0; py < ph; py++) {
        if (h - 1 < 0) throw std::invalid_argument("medianBlurMask: empty image");   // Kotlin 的 coerceIn(0, h − 1)
        const int32_t sy = jm::coerceIn(py - rad, 0, h - 1);
        int32_t rowSum = 0;
        const int32_t base = sy * w;
        for (int32_t px = 0; px < pw; px++) {
            if (w - 1 < 0) throw std::invalid_argument("medianBlurMask: empty image");
            const int32_t sx = jm::coerceIn(px - rad, 0, w - 1);
            if (md[base + sx]) rowSum++;
            integral[(py + 1) * stride + (px + 1)] = integral[py * stride + (px + 1)] + rowSum;
        }
    }
    Mask out(w, h, b);
    auto at = [&](int32_t i) -> int32_t {
        if (i < 0 || i >= isz) throw std::out_of_range("medianBlurMask: index out of range");
        return integral[i];
    };
    for (int32_t y = 0; y < h; y++) {
        for (int32_t x = 0; x < w; x++) {
            // 原圖 (x,y) 的窗在 padded 座標是 [x, x+r) × [y, y+r)
            const int32_t a = x, bb = y, c = x + r, d = y + r;
            const int32_t cnt = at(d * stride + c) - at(bb * stride + c) - at(d * stride + a) + at(bb * stride + a);
            out.data[static_cast<size_t>(y) * w + x] = cnt >= need ? 1 : 0;
        }
    }
    return out;
}

}  // namespace prims
}  // namespace nr
