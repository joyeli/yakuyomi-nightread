// 夜讀 C++ 核心：Java／Kotlin 語意的數值原語（nr::jm）。
//
// 規則（docs 與 cpp-eval 報告第 5、7 節）：C++ 版的夜讀要跟 Kotlin 版逐位元相同，所以凡是 Java 與 C++ 語意不同、或平台會給不同
// 答案的地方，一律經過這裡：
//   - 超越函數：StrictMath 的規格就是 fdlibm 5.3 ⇒ 一律呼叫 third_party/fdlibm（改名成 nr_fd_*），絕不呼叫平台 libm 的 sin/exp/pow。
//   - 捨入：Math.rint（五成雙、保留 −0.0）、Math.round（半數往 +∞、飽和）、Kotlin roundToInt（另外夾在 Int 範圍）、
//     BigDecimal(v).setScale(s, HALF_EVEN).toDouble()（對精確二進位值的十進位五成雙；零一律 +0.0）。
//   - 轉型：Java 的 d2i/d2l/f2i/f2l 是飽和、NaN→0；C++ 的 (int) 在範圍外是未定義行為。
//   - 整數：除以 −1 的溢位、位移量取低 5／6 位元、floorDiv／floorMod、溢位環繞。
//   - 比較：Math.min/max 對 ±0 與 NaN 的處理、Double.compare 的全序（−0.0 < 0.0、NaN 最大且等於自己）。
//   - 加總：numpy 的成對加總（npSum）與 8192 分段（npSumChunked）、numpy 的 introsort（npArgsort）、LAPACK 2×2（eigh2）。
// 每個函式註明對應的 Kotlin／Java 來源；行為一律以 Kotlin 版為準（CppJmathParityTest 在同一個 JVM 裡逐位元比）。
//
// 會讓 Kotlin 丟例外的輸入（NaN 的 roundToInt、除以 0、BigDecimal 的非有限值…）在這裡是前置條件：函式回傳固定值、不丟例外，
// 呼叫端（之後各階段的移植）要在 Kotlin 會丟例外的地方自己檢查。
//
// 編譯旗標（CMakeLists.txt）：-O2 -ffp-contract=off -fno-fast-math -fwrapv -fno-strict-aliasing -fno-builtin；
// 只有 x86_64（SSE2 基線）與 arm64（armv8-a 基線）兩種平台，double 運算都是 IEEE 754 binary64、預設捨入五成雙。
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

extern "C" {
double nr_fd_sin(double);
double nr_fd_cos(double);
double nr_fd_atan(double);
double nr_fd_ieee754_atan2(double, double);
double nr_fd_ieee754_exp(double);
double nr_fd_ieee754_pow(double, double);
}

namespace nr {
namespace jm {

static_assert(std::numeric_limits<double>::is_iec559 && std::numeric_limits<float>::is_iec559, "需要 IEEE 754 浮點");
static_assert((-8 >> 1) == -4 && (int64_t(-8) >> 1) == -4, "需要算術右移（gcc／clang 皆是）");

// ── 位元 ──────────────────────────────────────────────────────────────

inline uint64_t dbits(double d) noexcept { uint64_t b; std::memcpy(&b, &d, 8); return b; }
inline double dfrom(uint64_t b) noexcept { double d; std::memcpy(&d, &b, 8); return d; }
inline uint32_t fbits(float f) noexcept { uint32_t b; std::memcpy(&b, &f, 4); return b; }
inline float ffrom(uint32_t b) noexcept { float f; std::memcpy(&f, &b, 4); return f; }

constexpr uint64_t D_SIGN = 0x8000000000000000ULL;
constexpr uint32_t F_SIGN = 0x80000000U;

inline bool isNaN(double d) noexcept { return d != d; }
inline bool isNaN(float f) noexcept { return f != f; }

// Double.doubleToLongBits／Float.floatToIntBits：NaN 一律收成標準 NaN（0x7ff8000000000000／0x7fc00000）。
inline uint64_t doubleToLongBits(double d) noexcept { return isNaN(d) ? 0x7ff8000000000000ULL : dbits(d); }
inline uint32_t floatToIntBits(float f) noexcept { return isNaN(f) ? 0x7fc00000U : fbits(f); }

// ── StrictMath（fdlibm 5.3）────────────────────────────────────────────

inline double sin(double x) noexcept { return nr_fd_sin(x); }
inline double cos(double x) noexcept { return nr_fd_cos(x); }
inline double atan(double x) noexcept { return nr_fd_atan(x); }
inline double atan2(double y, double x) noexcept { return nr_fd_ieee754_atan2(y, x); }
inline double exp(double x) noexcept { return nr_fd_ieee754_exp(x); }
inline double pow(double x, double y) noexcept { return nr_fd_ieee754_pow(x, y); }

// ── 正確捨入或精確的運算（Math.sqrt／floor／ceil／abs、Java 的 %）────────────────
// sqrt 是 IEEE 正確捨入（x86 sqrtsd、arm64 fsqrt 與 Java 的定義相同）；floor／ceil／fmod 是精確運算，任何正確的實作都同值。

inline double sqrt(double x) noexcept { return __builtin_sqrt(x); }
inline double floor(double x) noexcept { return __builtin_floor(x); }
inline double ceil(double x) noexcept { return __builtin_ceil(x); }
/** Math.abs(double)（JDK 18 起）：清掉符號位元，NaN 也一樣。 */
inline double abs(double x) noexcept { return dfrom(dbits(x) & ~D_SIGN); }
inline float abs(float x) noexcept { return ffrom(fbits(x) & ~F_SIGN); }
/** Java 的 double `%`（drem）＝C 的 fmod：截斷商、餘數同被除數的號、精確。 */
inline double rem(double a, double b) noexcept { return __builtin_fmod(a, b); }

/** Math.copySign(magnitude, sign)：直接取 sign 的符號位元（NaN 的符號也照取；不是 StrictMath.copySign）。 */
inline double copySign(double m, double s) noexcept { return dfrom((dbits(m) & ~D_SIGN) | (dbits(s) & D_SIGN)); }
/** Math.signum（＝Kotlin Double.sign）：±0 與 NaN 原樣、其餘 ±1.0。 */
inline double signum(double d) noexcept { return (d == 0.0 || isNaN(d)) ? d : copySign(1.0, d); }

// ── 轉型（JLS 5.1.3：截斷、NaN→0、超出範圍飽和）───────────────────────────────

inline int32_t d2i(double d) noexcept {
    if (isNaN(d)) return 0;
    if (d >= 2147483648.0) return INT32_MAX;
    if (d <= -2147483649.0) return INT32_MIN;
    return static_cast<int32_t>(d);
}
inline int64_t d2l(double d) noexcept {
    if (isNaN(d)) return 0;
    if (d >= 9223372036854775808.0) return INT64_MAX;
    if (d <= -9223372036854775808.0) return INT64_MIN;
    return static_cast<int64_t>(d);
}
inline int32_t f2i(float f) noexcept {
    if (isNaN(f)) return 0;
    if (f >= 2147483648.0f) return INT32_MAX;
    if (f <= -2147483648.0f) return INT32_MIN;
    return static_cast<int32_t>(f);
}
inline int64_t f2l(float f) noexcept {
    if (isNaN(f)) return 0;
    if (f >= 9223372036854775808.0f) return INT64_MAX;
    if (f <= -9223372036854775808.0f) return INT64_MIN;
    return static_cast<int64_t>(f);
}
// 以下是 IEEE 五成雙的單次捨入，C++ 的轉型在兩個平台上就是 Java 的 d2f／l2d／l2f／i2f（l2f 不經過 double，沒有二次捨入）。
inline float d2f(double d) noexcept { return static_cast<float>(d); }
inline double l2d(int64_t v) noexcept { return static_cast<double>(v); }
inline float l2f(int64_t v) noexcept { return static_cast<float>(v); }
inline float i2f(int32_t v) noexcept { return static_cast<float>(v); }

// ── 捨入 ────────────────────────────────────────────────────────────────

/** Math.rint（StrictMath.rint 的演算法逐字）：五成雙、保留 −0.0（rint(−0.4)＝−0.0）、|a| ≥ 2⁵² 原樣。 */
inline double rint(double a) noexcept {
    const double twoToThe52 = 4503599627370496.0;
    double sign = copySign(1.0, a);
    a = abs(a);
    if (a < twoToThe52) a = ((twoToThe52 + a) - twoToThe52);
    return sign * a;
}

/** Math.round(double)（OpenJDK 位元演算法逐字）：floor(a + 1/2) 的精確值；NaN→0、超出 long 飽和。 */
inline int64_t round(double a) noexcept {
    const int64_t longBits = static_cast<int64_t>(dbits(a));
    const int64_t biasedExp = (longBits & 0x7FF0000000000000LL) >> 52;
    const int64_t shift = (53 - 2 + 1023) - biasedExp;
    if ((shift & -64) == 0) {
        int64_t r = (longBits & 0x000FFFFFFFFFFFFFLL) | (0x000FFFFFFFFFFFFFLL + 1);
        if (longBits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    return d2l(a);
}

/** Math.round(float)：同上的 float 版，回傳 int。 */
inline int32_t round(float a) noexcept {
    const int32_t intBits = static_cast<int32_t>(fbits(a));
    const int32_t biasedExp = (intBits & 0x7F800000) >> 23;
    const int32_t shift = (24 - 2 + 127) - biasedExp;
    if ((shift & -32) == 0) {
        int32_t r = (intBits & 0x007FFFFF) | (0x007FFFFF + 1);
        if (intBits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    return f2i(a);
}

/** Kotlin Double.roundToInt()：先夾到 Int 範圍再 Math.round。前置條件：非 NaN（Kotlin 丟 IllegalArgumentException）。 */
inline int32_t roundToInt(double x) noexcept {
    if (x > 2147483647.0) return INT32_MAX;
    if (x < -2147483648.0) return INT32_MIN;
    return static_cast<int32_t>(round(x));
}
/** Kotlin Float.roundToInt()＝Math.round(float)。前置條件：非 NaN。 */
inline int32_t roundToInt(float x) noexcept { return round(x); }

/**
 * `BigDecimal(v).setScale(scale, RoundingMode.HALF_EVEN).toDouble()`：對 v 的**精確**二進位值做十進位五成雙，再正確捨入回
 * double（python 的 round(x, n)）。BigDecimal 沒有 −0：結果是零一律回 +0.0。
 * 前置條件：v 有限（Kotlin 丟 NumberFormatException；這裡回 NaN）、0 ≤ scale ≤ 18（超出回 NaN）。
 */
double bdHalfEven(double v, int scale) noexcept;

// ── 整數（Java 語意）────────────────────────────────────────────────────

inline int32_t wadd(int32_t a, int32_t b) noexcept { return static_cast<int32_t>(static_cast<uint32_t>(a) + static_cast<uint32_t>(b)); }
inline int32_t wsub(int32_t a, int32_t b) noexcept { return static_cast<int32_t>(static_cast<uint32_t>(a) - static_cast<uint32_t>(b)); }
inline int32_t wmul(int32_t a, int32_t b) noexcept { return static_cast<int32_t>(static_cast<uint32_t>(a) * static_cast<uint32_t>(b)); }
inline int64_t wadd(int64_t a, int64_t b) noexcept { return static_cast<int64_t>(static_cast<uint64_t>(a) + static_cast<uint64_t>(b)); }
inline int64_t wsub(int64_t a, int64_t b) noexcept { return static_cast<int64_t>(static_cast<uint64_t>(a) - static_cast<uint64_t>(b)); }
inline int64_t wmul(int64_t a, int64_t b) noexcept { return static_cast<int64_t>(static_cast<uint64_t>(a) * static_cast<uint64_t>(b)); }

/** Java 的 int `/`：截斷；MIN / −1＝MIN（C++ 未定義、x86 會 SIGFPE）。前置條件：b ≠ 0（Java 丟 ArithmeticException；這裡回 0）。 */
inline int32_t div(int32_t a, int32_t b) noexcept { return b == 0 ? 0 : b == -1 ? wsub(0, a) : a / b; }
/** Java 的 int `%`：餘數同被除數的號；MIN % −1＝0。前置條件：b ≠ 0。 */
inline int32_t rem(int32_t a, int32_t b) noexcept { return (b == 0 || b == -1) ? 0 : a % b; }
inline int64_t div(int64_t a, int64_t b) noexcept { return b == 0 ? 0 : b == -1 ? wsub(int64_t(0), a) : a / b; }
inline int64_t rem(int64_t a, int64_t b) noexcept { return (b == 0 || b == -1) ? 0 : a % b; }

/** Math.floorDiv(int, int)。前置條件：y ≠ 0。 */
inline int32_t floorDiv(int32_t x, int32_t y) noexcept {
    const int32_t q = div(x, y);
    if ((x ^ y) < 0 && wmul(q, y) != x) return wsub(q, 1);
    return q;
}
/** Math.floorMod(int, int)：結果與 y 同號。前置條件：y ≠ 0。 */
inline int32_t floorMod(int32_t x, int32_t y) noexcept {
    const int32_t r = rem(x, y);
    if ((x ^ y) < 0 && r != 0) return wadd(r, y);
    return r;
}
inline int64_t floorDiv(int64_t x, int64_t y) noexcept {
    const int64_t q = div(x, y);
    if ((x ^ y) < 0 && wmul(q, y) != x) return wsub(q, int64_t(1));
    return q;
}
inline int64_t floorMod(int64_t x, int64_t y) noexcept {
    const int64_t r = rem(x, y);
    if ((x ^ y) < 0 && r != 0) return wadd(r, y);
    return r;
}

// 位移：Java 只取位移量的低 5（int）／6（long）位元；C++ 位移量 ≥ 位寬是未定義行為。
inline int32_t shl(int32_t x, int32_t n) noexcept { return static_cast<int32_t>(static_cast<uint32_t>(x) << (n & 31)); }
inline int32_t shr(int32_t x, int32_t n) noexcept { return x >> (n & 31); }
inline int32_t ushr(int32_t x, int32_t n) noexcept { return static_cast<int32_t>(static_cast<uint32_t>(x) >> (n & 31)); }
inline int64_t shl(int64_t x, int32_t n) noexcept { return static_cast<int64_t>(static_cast<uint64_t>(x) << (n & 63)); }
inline int64_t shr(int64_t x, int32_t n) noexcept { return x >> (n & 63); }
inline int64_t ushr(int64_t x, int32_t n) noexcept { return static_cast<int64_t>(static_cast<uint64_t>(x) >> (n & 63)); }

/** Kotlin Double.mod(Double)：r＝a % b；r ≠ 0 且 r 與 b 的 sign 不同 ⇒ r + b。 */
inline double mod(double a, double b) noexcept {
    const double r = rem(a, b);
    return (r != 0.0 && signum(r) != signum(b)) ? r + b : r;
}

// ── 比較 ────────────────────────────────────────────────────────────────

/** Math.min(double, double)：a 是 NaN 回 a；兩個都是零時 −0.0 較小；其餘 a ≤ b ? a : b（b 是 NaN 時回 b）。 */
inline double min(double a, double b) noexcept {
    if (a != a) return a;
    if (a == 0.0 && b == 0.0 && dbits(b) == D_SIGN) return b;
    return (a <= b) ? a : b;
}
/** Math.max(double, double)。 */
inline double max(double a, double b) noexcept {
    if (a != a) return a;
    if (a == 0.0 && b == 0.0 && dbits(a) == D_SIGN) return b;
    return (a >= b) ? a : b;
}
inline float min(float a, float b) noexcept {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && fbits(b) == F_SIGN) return b;
    return (a <= b) ? a : b;
}
inline float max(float a, float b) noexcept {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && fbits(a) == F_SIGN) return b;
    return (a >= b) ? a : b;
}
inline int32_t min(int32_t a, int32_t b) noexcept { return a <= b ? a : b; }
inline int32_t max(int32_t a, int32_t b) noexcept { return a >= b ? a : b; }
inline int64_t min(int64_t a, int64_t b) noexcept { return a <= b ? a : b; }
inline int64_t max(int64_t a, int64_t b) noexcept { return a >= b ? a : b; }

/** Double.compare（＝Kotlin Double.compareTo、sortedBy 的比較）：全序，−0.0 < 0.0，NaN 最大且等於自己。回傳 −1／0／1。 */
inline int32_t compare(double a, double b) noexcept {
    if (a < b) return -1;
    if (a > b) return 1;
    const int64_t x = static_cast<int64_t>(doubleToLongBits(a));
    const int64_t y = static_cast<int64_t>(doubleToLongBits(b));
    return x == y ? 0 : (x < y ? -1 : 1);
}
inline int32_t compare(float a, float b) noexcept {
    if (a < b) return -1;
    if (a > b) return 1;
    const int32_t x = static_cast<int32_t>(floatToIntBits(a));
    const int32_t y = static_cast<int32_t>(floatToIntBits(b));
    return x == y ? 0 : (x < y ? -1 : 1);
}

/** Kotlin Int.coerceIn。前置條件：lo ≤ hi（Kotlin 丟 IllegalArgumentException）。 */
inline int32_t coerceIn(int32_t v, int32_t lo, int32_t hi) noexcept { return v < lo ? lo : (v > hi ? hi : v); }
/** Kotlin Double.coerceIn：v 是 NaN 回 NaN；界線是 NaN 時對應的比較為假。前置條件：!(lo > hi)。 */
inline double coerceIn(double v, double lo, double hi) noexcept { return v < lo ? lo : (v > hi ? hi : v); }

// ── numpy／LAPACK 相容 ─────────────────────────────────────────────────

/** Cv.npSum：numpy 對連續 float64 的成對加總（n < 8 逐項；≤ 128 八路累加；更長對半、切點取 8 的倍數）。不用遞迴。 */
double npSum(const double* a, int64_t n) noexcept;
/** BgObjects.npSumChunked：numpy 的 8192 分段，每段 [npSum]、段與段依序相加。 */
double npSumChunked(const double* a, int64_t n) noexcept;

/**
 * Cv.npArgsort 的本體（numpy 1.26 `aquicksort_`：三數取中、≤ 16 插入排序、深度超過 2·⌊log2 n⌋ 改 heapsort）。
 * [less](i, j)＝「元素 i 小於元素 j」（int 版即 v[i] < v[j]）。ts 長 n，回傳時是排序後的索引。
 * 範本化是為了讓測試能用 McIlroy 的對手比較子造出逼 heapsort 的輸入；[onHeap] 每次進 heapsort 呼叫一次（產品傳空函式）。
 */
template <class Less, class OnHeap>
void npArgsortT(int32_t num, int32_t* ts, Less less, OnHeap onHeap);

/** Cv.npArgsort(IntArray)：ts 長 n。 */
void npArgsort(const int32_t* v, int32_t n, int32_t* ts) noexcept;

/** Cv.eigh2(a, b, c)：`np.linalg.eigh([[a, b], [b, c]])`（LAPACK dsteqr 的 2×2 路徑），out＝[w0, w1, v00, v10, v01, v11]。 */
void eigh2(double a, double b, double c, double out[6]) noexcept;

// ── npArgsortT 實作（範本，放標頭）────────────────────────────────────────

namespace detail {
template <class Less>
void aheapsort(int32_t* ts, int32_t off, int32_t n0, Less less) {
    int32_t* const base = ts + off - 1;   // 1-based
    int32_t n = n0;
    for (int32_t l = n >> 1; l > 0; l--) {
        const int32_t tmp = base[l];
        int32_t i = l;
        int32_t j = l << 1;
        while (j <= n) {
            if (j < n && less(base[j], base[j + 1])) j += 1;
            if (less(tmp, base[j])) { base[i] = base[j]; i = j; j += j; } else break;
        }
        base[i] = tmp;
    }
    while (n > 1) {
        const int32_t tmp = base[n];
        base[n] = base[1];
        n -= 1;
        int32_t i = 1;
        int32_t j = 2;
        while (j <= n) {
            if (j < n && less(base[j], base[j + 1])) j++;
            if (less(tmp, base[j])) { base[i] = base[j]; i = j; j += j; } else break;
        }
        base[i] = tmp;
    }
}
}  // namespace detail

template <class Less, class OnHeap>
void npArgsortT(int32_t num, int32_t* ts, Less less, OnHeap onHeap) {
    for (int32_t i = 0; i < num; i++) ts[i] = i;
    if (num <= 1) return;
    const int32_t small = 15;
    int32_t pl = 0;
    int32_t pr = num - 1;
    int32_t stack[256];
    int32_t sp = 0;
    int32_t depth[128];
    int32_t dp = 0;
    int32_t cdepth = 0;
    for (int32_t u = num; u > 1; u >>= 1) cdepth++;
    cdepth *= 2;
    auto swap = [ts](int32_t a, int32_t b) { const int32_t t = ts[a]; ts[a] = ts[b]; ts[b] = t; };
    for (;;) {
        if (cdepth < 0) {
            onHeap();
            detail::aheapsort(ts, pl, pr - pl + 1, less);
        } else {
            while (pr - pl > small) {
                const int32_t pm = pl + ((pr - pl) >> 1);
                if (less(ts[pm], ts[pl])) swap(pm, pl);
                if (less(ts[pr], ts[pm])) swap(pr, pm);
                if (less(ts[pm], ts[pl])) swap(pm, pl);
                const int32_t vp = ts[pm];   // Kotlin 取的是值 v[ts[pm]]；swap(pm, pj) 之後 ts[pj]＝這個索引，值不變
                int32_t pi = pl;
                int32_t pj = pr - 1;
                swap(pm, pj);
                for (;;) {
                    do { ++pi; } while (less(ts[pi], vp));
                    do { --pj; } while (less(vp, ts[pj]));
                    if (pi >= pj) break;
                    swap(pi, pj);
                }
                const int32_t pk = pr - 1;
                swap(pi, pk);
                if (pi - pl < pr - pi) {
                    stack[sp++] = pi + 1; stack[sp++] = pr;
                    pr = pi - 1;
                } else {
                    stack[sp++] = pl; stack[sp++] = pi - 1;
                    pl = pi + 1;
                }
                depth[dp++] = --cdepth;
            }
            // 插入排序
            for (int32_t pi = pl + 1; pi <= pr; pi++) {
                const int32_t vi = ts[pi];
                int32_t pj = pi;
                int32_t pk = pi - 1;
                while (pj > pl && less(vi, ts[pk])) { ts[pj--] = ts[pk--]; }
                ts[pj] = vi;
            }
        }
        if (sp == 0) break;
        pr = stack[--sp];
        pl = stack[--sp];
        cdepth = depth[--dp];
    }
}

}  // namespace jm
}  // namespace nr
