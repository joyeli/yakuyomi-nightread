// 夜讀 C++ 核心：nr.h 的實作（階段 1 骨架：像素進出、記憶體峰值、取消旗標）。
#include "nightread/nr.h"

#include <atomic>
#include <new>

#include "convert.h"
#include "types.h"

struct nr_page {
    int32_t w;
    int32_t h;
    nr::Budget budget;             // 先宣告：緩衝都在它之後解構
    nr::ImgI32 gray;
    nr::ImgI32 chroma;
    nr::Buf<uint8_t> rowFilled;    // 每列放過沒有
    std::atomic<int32_t> cancelled{0};

    nr_page(int32_t w_, int32_t h_)
        : w(w_), h(h_), gray(w_, h_, &budget), chroma(w_, h_, &budget), rowFilled(static_cast<size_t>(h_), &budget) {}
};

namespace {

bool rowsInRange(const nr_page* p, int32_t y0, int32_t rows) {
    return y0 >= 0 && rows >= 0 && static_cast<int64_t>(y0) + rows <= p->h;
}

}  // namespace

extern "C" {

int32_t nr_abi(void) { return (NR_ABI_VERSION << 16) | NR_RULES_VERSION; }

int32_t nr_create(int32_t w, int32_t h, nr_page** out) {
    if (out == nullptr) return NR_E_ARG;
    *out = nullptr;
    if (w < 1 || h < 1 || static_cast<int64_t>(w) * h > INT32_MAX) return NR_E_ARG;
    try {
        *out = new nr_page(w, h);
        return NR_OK;
    } catch (const std::bad_alloc&) {
        return NR_E_NOMEM;
    } catch (...) {
        return NR_E_INTERNAL;
    }
}

void nr_free(nr_page* p) { delete p; }

int32_t nr_width(const nr_page* p) { return p == nullptr ? 0 : p->w; }
int32_t nr_height(const nr_page* p) { return p == nullptr ? 0 : p->h; }

int32_t nr_put_rows(nr_page* p, int32_t y0, int32_t rows, const int32_t* argb, int64_t stride) {
    if (p == nullptr || !rowsInRange(p, y0, rows) || stride < p->w || (rows > 0 && argb == nullptr)) return NR_E_ARG;
    for (int32_t r = 0; r < rows; r++) {
        const size_t o = static_cast<size_t>(y0 + r) * static_cast<size_t>(p->w);
        nr::argbToGrayChroma(argb + static_cast<int64_t>(r) * stride, p->w, p->gray.data.data() + o, p->chroma.data.data() + o);
        p->rowFilled[static_cast<size_t>(y0 + r)] = 1;
    }
    return NR_OK;
}

int32_t nr_get_rows(const nr_page* p, int32_t plane, int32_t y0, int32_t rows, int32_t* out, int64_t stride) {
    if (p == nullptr || !rowsInRange(p, y0, rows) || stride < p->w || (rows > 0 && out == nullptr)) return NR_E_ARG;
    if (plane != NR_PLANE_GRAY && plane != NR_PLANE_CHROMA && plane != NR_PLANE_GRAY_ARGB) return NR_E_ARG;
    for (int32_t r = 0; r < rows; r++) if (!p->rowFilled[static_cast<size_t>(y0 + r)]) return NR_E_STATE;
    for (int32_t r = 0; r < rows; r++) {
        const size_t o = static_cast<size_t>(y0 + r) * static_cast<size_t>(p->w);
        int32_t* dst = out + static_cast<int64_t>(r) * stride;
        const int32_t* src = (plane == NR_PLANE_CHROMA ? p->chroma : p->gray).data.data() + o;
        if (plane == NR_PLANE_GRAY_ARGB) {
            nr::grayToArgb(src, p->w, dst);
        } else {
            for (int32_t x = 0; x < p->w; x++) dst[x] = src[x];
        }
    }
    return NR_OK;
}

int64_t nr_peak_bytes(const nr_page* p) { return p == nullptr ? 0 : p->budget.peak.load(std::memory_order_relaxed); }

void nr_cancel(nr_page* p) {
    if (p != nullptr) p->cancelled.store(1, std::memory_order_relaxed);
}

int32_t nr_is_cancelled(const nr_page* p) { return p == nullptr ? 0 : p->cancelled.load(std::memory_order_relaxed); }

}  // extern "C"
