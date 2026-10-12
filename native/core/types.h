// 夜讀 C++ 核心：影像與結果型別（nr）。
//
// 先照 Kotlin 版的配置「照原樣搬」（cpp-eval 報告第 7 節第 7 條：逐項對照通過之後才瘦身）：
//   Kotlin Mask（BooleanArray）  → Mask（每像素 1 位元組，0／1）
//   Kotlin Gray（IntArray 0..255）→ ImgI32
//   Kotlin FImg（FloatArray）     → ImgF32
//   Kotlin Kernel／CC／Cv.CcRuns  → Kernel／CC／CcRuns（欄位一一對應）
//   Cv.packBits 的列打包         → PackedBits（每列 (w+63)/64 個 64 位元字，像素 x 在第 x>>6 字的第 x&63 位，末字超出寬度的位元一律 0）
// GrayU8 是之後瘦身用的 uint8 灰階（報告 3.3「C++ 瘦身後」），現在只有型別。
//
// 記憶體：所有像素緩衝都是 Buf<T>（零初始化，同 Kotlin 新陣列）。給了 Budget 就把位元組數記進去，nr_peak_bytes 報的就是它的峰值。
// 配置失敗丟 std::bad_alloc，由 C API／JNI 邊界轉成錯誤碼／Java 的 OutOfMemoryError（不讓 C++ 直接終止 app）。
// 尺寸規則同 Kotlin 陣列：w、h ≥ 0 且 w·h ≤ Int.MAX_VALUE，否則丟 std::length_error。
// 沒有全域可變狀態；不同頁可以在不同執行緒同時用。
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <new>
#include <stdexcept>
#include <type_traits>

namespace nr {

/** 一頁的原生記憶體帳：目前與峰值（位元組）。多執行緒同時配置也正確（原子操作）。 */
struct Budget {
    std::atomic<int64_t> cur{0};
    std::atomic<int64_t> peak{0};
    void add(int64_t n) noexcept {
        const int64_t now = cur.fetch_add(n, std::memory_order_relaxed) + n;
        int64_t p = peak.load(std::memory_order_relaxed);
        while (now > p && !peak.compare_exchange_weak(p, now, std::memory_order_relaxed)) {}
    }
    void sub(int64_t n) noexcept { cur.fetch_sub(n, std::memory_order_relaxed); }
};

/** 零初始化、只能搬移的緩衝。T 必須是可平凡複製的型別（像素、索引）。 */
template <class T>
class Buf {
    static_assert(std::is_trivially_copyable<T>::value, "Buf 只放可平凡複製的型別");

public:
    Buf() noexcept = default;
    explicit Buf(size_t n, Budget* budget = nullptr);
    ~Buf() { reset(); }
    Buf(Buf&& o) noexcept : p_(o.p_), n_(o.n_), b_(o.b_) { o.p_ = nullptr; o.n_ = 0; o.b_ = nullptr; }
    Buf& operator=(Buf&& o) noexcept {
        if (this != &o) { reset(); p_ = o.p_; n_ = o.n_; b_ = o.b_; o.p_ = nullptr; o.n_ = 0; o.b_ = nullptr; }
        return *this;
    }
    Buf(const Buf&) = delete;
    Buf& operator=(const Buf&) = delete;

    T* data() noexcept { return p_; }
    const T* data() const noexcept { return p_; }
    size_t size() const noexcept { return n_; }
    T& operator[](size_t i) noexcept { return p_[i]; }
    const T& operator[](size_t i) const noexcept { return p_[i]; }
    void reset() noexcept;

private:
    T* p_ = nullptr;
    size_t n_ = 0;
    Budget* b_ = nullptr;
};

void* bufAlloc(size_t n, size_t elemSize, Budget* budget);   // calloc；溢位或失敗丟 std::bad_alloc
void bufFree(void* p, size_t bytes, Budget* budget) noexcept;

template <class T>
Buf<T>::Buf(size_t n, Budget* budget) : p_(static_cast<T*>(bufAlloc(n, sizeof(T), budget))), n_(n), b_(budget) {}

template <class T>
void Buf<T>::reset() noexcept {
    if (p_ != nullptr) bufFree(p_, n_ * sizeof(T), b_);
    p_ = nullptr;
    n_ = 0;
    b_ = nullptr;
}

/** w·h 的像素數；尺寸不合 Kotlin 陣列規則丟 std::length_error。 */
size_t pixelCount(int32_t w, int32_t h);

/** Kotlin Mask：每像素一個位元組（0／1），row-major、索引 y·w＋x。 */
struct Mask {
    int32_t w = 0, h = 0;
    Buf<uint8_t> data;
    Mask() = default;
    Mask(int32_t w, int32_t h, Budget* b = nullptr) : w(w), h(h), data(pixelCount(w, h), b) {}
};

/** Kotlin Gray（IntArray，值 0..255；中間結果可能超出）。 */
struct ImgI32 {
    int32_t w = 0, h = 0;
    Buf<int32_t> data;
    ImgI32() = default;
    ImgI32(int32_t w, int32_t h, Budget* b = nullptr) : w(w), h(h), data(pixelCount(w, h), b) {}
};

/** uint8 灰階（瘦身階段用；目前沒有呼叫端）。 */
struct GrayU8 {
    int32_t w = 0, h = 0;
    Buf<uint8_t> data;
    GrayU8() = default;
    GrayU8(int32_t w, int32_t h, Budget* b = nullptr) : w(w), h(h), data(pixelCount(w, h), b) {}
};

/** Kotlin FImg（FloatArray）。 */
struct ImgF32 {
    int32_t w = 0, h = 0;
    Buf<float> data;
    ImgF32() = default;
    ImgF32(int32_t w, int32_t h, Budget* b = nullptr) : w(w), h(h), data(pixelCount(w, h), b) {}
};

/**
 * Kotlin Kernel（結構元素）：錨點 (w/2, h/2)；runStart／runEnd＝每列連續 run 的半開區間（相對核座標），空列兩者皆 0。
 * 計算照 Kotlin init 逐字：第一個與最後一個前景的位置（中間有洞也當成一段，同 Kotlin）。
 */
struct Kernel {
    int32_t w = 0, h = 0, ax = 0, ay = 0;
    Buf<uint8_t> data;
    Buf<int32_t> runStart, runEnd;
    Kernel() = default;
    /** data 長 w·h（0／1），複製進來。 */
    Kernel(int32_t w, int32_t h, const uint8_t* data, Budget* b = nullptr);
};

/** Kotlin CC（cv2.connectedComponentsWithStats）：labels 與影像同尺寸，其餘長 n（含背景 0 號）。 */
struct CC {
    int32_t n = 0;
    Buf<int32_t> labels, left, top, width, height, area;
};

/** Kotlin Cv.CcRuns：游程本體。rs／re＝游程起訖（含）、rowFirst 長 h＋1、lab＝游程的最終標號、fg＝前景像素數、bg*＝背景 bbox。 */
struct CcRuns {
    int32_t n = 0;
    Buf<int32_t> rs, re, rowFirst, lab;
    int64_t fg = 0;
    int32_t bgLeft = INT32_MAX, bgTop = INT32_MAX, bgRight = INT32_MIN, bgBottom = INT32_MIN;
};

/** Cv.packBits 的列打包。 */
struct PackedBits {
    int32_t w = 0, h = 0, nw = 0;
    Buf<uint64_t> words;
    PackedBits() = default;
    PackedBits(int32_t w, int32_t h, Budget* b = nullptr);
};

/** Cv.packBits：位元組遮罩 → 列打包。 */
PackedBits packBits(const Mask& m, Budget* b = nullptr);
/** Cv.unpackBits：列打包 → 位元組遮罩（只讀每列前 w 位元）。 */
Mask unpackBits(const PackedBits& a, Budget* b = nullptr);

}  // namespace nr
