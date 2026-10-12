// 數值核心的測試案例。說明見 cases.h。
#include "cases.h"

#include <cstring>

#include "../core/jmath.h"

namespace nrt {

// 用 using 宣告（不是 using namespace）：libc++ 會把 ::round、::abs… 放進全域，using namespace 會讓呼叫變成有歧義。
using nr::jm::dbits;
using nr::jm::dfrom;
using nr::jm::fbits;
using nr::jm::ffrom;
using nr::jm::isNaN;
using nr::jm::abs;
using nr::jm::sin;
using nr::jm::cos;
using nr::jm::atan;
using nr::jm::atan2;
using nr::jm::exp;
using nr::jm::pow;
using nr::jm::sqrt;
using nr::jm::floor;
using nr::jm::ceil;
using nr::jm::rem;
using nr::jm::mod;
using nr::jm::signum;
using nr::jm::rint;
using nr::jm::round;
using nr::jm::roundToInt;
using nr::jm::d2i;
using nr::jm::d2l;
using nr::jm::f2i;
using nr::jm::f2l;
using nr::jm::d2f;
using nr::jm::l2d;
using nr::jm::l2f;
using nr::jm::i2f;
using nr::jm::bdHalfEven;
using nr::jm::div;
using nr::jm::floorDiv;
using nr::jm::floorMod;
using nr::jm::min;
using nr::jm::max;
using nr::jm::compare;
using nr::jm::shl;
using nr::jm::shr;
using nr::jm::ushr;
using nr::jm::coerceIn;
using nr::jm::wmul;
using nr::jm::eigh2;
using nr::jm::npArgsortT;

namespace {

// ── 亂數（SplitMix64，無狀態地由 (種子, i) 產生）───────────────────────────

inline uint64_t mix(uint64_t z) {
    z += 0x9E3779B97F4A7C15ULL;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() { s += 0x9E3779B97F4A7C15ULL; uint64_t z = s; z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL; z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL; return z ^ (z >> 31); }
    uint32_t below(uint32_t n) { return static_cast<uint32_t>(next() % n); }
    bool coin() { return (next() & 1) != 0; }
    /** [0, 2^p)，p 0..64 */
    uint64_t bits(int p) { return p <= 0 ? 0 : (p >= 64 ? next() : next() >> (64 - p)); }
    /** [0, 1)，53 位精度（精確） */
    double unit() { return static_cast<double>(next() >> 11) * 1.1102230246251565e-16; }
    /** [lo, hi) 均勻 */
    double uniform(double lo, double hi) { return lo + (hi - lo) * unit(); }
    int32_t range(int32_t lo, int32_t hi) { return lo + static_cast<int32_t>(below(static_cast<uint32_t>(hi - lo + 1))); }
};

uint64_t nameSeed(const char* name) {
    uint64_t h = FNV_INIT;
    for (const char* p = name; *p; p++) h = fnv(h, static_cast<uint8_t>(*p));
    return mix(h);
}

inline Rng caseRng(uint64_t seed, int64_t i) { return Rng(mix(seed ^ mix(static_cast<uint64_t>(i)))); }

// ── 浮點小工具（只用位元）────────────────────────────────────────────────

double nextUp(double d) {
    if (isNaN(d) || d == dfrom(0x7ff0000000000000ULL)) return d;
    if (d == 0.0) return dfrom(1);
    const uint64_t b = dbits(d);
    return dfrom(d > 0 ? b + 1 : b - 1);
}
double nextDown(double d) {
    if (isNaN(d) || d == dfrom(0xfff0000000000000ULL)) return d;
    if (d == 0.0) return dfrom(0x8000000000000001ULL);
    const uint64_t b = dbits(d);
    return dfrom(d > 0 ? b - 1 : b + 1);
}
double nudge(double d, int k) {
    for (; k > 0; k--) d = nextUp(d);
    for (; k < 0; k++) d = nextDown(d);
    return d;
}
float nextUpF(float f) {
    if (isNaN(f) || f == ffrom(0x7f800000U)) return f;
    if (f == 0.0f) return ffrom(1);
    const uint32_t b = fbits(f);
    return ffrom(f > 0 ? b + 1 : b - 1);
}
float nextDownF(float f) {
    if (isNaN(f) || f == ffrom(0xff800000U)) return f;
    if (f == 0.0f) return ffrom(0x80000001U);
    const uint32_t b = fbits(f);
    return ffrom(f > 0 ? b - 1 : b + 1);
}
float nudgeF(float f, int k) {
    for (; k > 0; k--) f = nextUpF(f);
    for (; k < 0; k++) f = nextDownF(f);
    return f;
}
double pow2(int e) {   // 2^e，e ∈ [−1074, 1023]
    if (e >= -1022) return dfrom(static_cast<uint64_t>(e + 1023) << 52);
    return dfrom(uint64_t(1) << (e + 1074));
}
double withSign(Rng& r, double d) { return r.coin() ? -d : d; }
// 注意：同一個運算式裡不能有兩個以上的 r 呼叫（引數、運算元的求值順序在 C++ 未指定，gcc 與 clang 會產生不同輸入）。
// 下面的小工具把多次呼叫拆成依序的敘述。
/** 2^[lo,hi] × [1,2)，正數。 */
double magnitude(Rng& r, int lo, int hi) { const int e = r.range(lo, hi); const double m = 1.0 + r.unit(); return pow2(e) * m; }
/** d 移動 [lo,hi] 個 ulp。 */
double nudgeR(Rng& r, double d, int lo, int hi) { const int k = r.range(lo, hi); return nudge(d, k); }

// ── 邊界值 ────────────────────────────────────────────────────────────

const uint64_t EDGE_D[] = {
    0x0000000000000000ULL, 0x8000000000000000ULL,                         // ±0
    0x3ff0000000000000ULL, 0xbff0000000000000ULL,                         // ±1
    0x3fe0000000000000ULL, 0xbfe0000000000000ULL,                         // ±0.5
    0x3ff8000000000000ULL, 0xbff8000000000000ULL,                         // ±1.5
    0x4004000000000000ULL, 0xc004000000000000ULL,                         // ±2.5
    0x3fdfffffffffffffULL, 0xbfdfffffffffffffULL,                         // ±0.49999999999999994
    0x0000000000000001ULL, 0x8000000000000001ULL,                         // ±MIN_VALUE
    0x000fffffffffffffULL, 0x800fffffffffffffULL,                         // ±最大次正規數
    0x0010000000000000ULL, 0x8010000000000000ULL,                         // ±MIN_NORMAL
    0x7fefffffffffffffULL, 0xffefffffffffffffULL,                         // ±MAX_VALUE
    0x7ff0000000000000ULL, 0xfff0000000000000ULL,                         // ±∞
    0x7ff8000000000000ULL, 0xfff8000000000000ULL, 0x7ff0000000000001ULL, 0x7ff4000000000000ULL, 0x7fffffffffffffffULL,  // NaN
    0x41dfffffffc00000ULL, 0x41e0000000000000ULL, 0xc1e0000000000000ULL,  // 2³¹−1、2³¹、−2³¹
    0xc1e0000000200000ULL, 0x41dfffffffe00000ULL, 0xc1e0000000100000ULL,  // −2³¹−1、2³¹−0.5、−2³¹−0.5
    0x41dfffffff800000ULL,                                               // 2³¹−2
    0x4330000000000000ULL, 0x432fffffffffffffULL, 0x4330000000000001ULL,  // 2⁵²、2⁵²−0.5、2⁵²+1
    0x4340000000000000ULL, 0x4340000000000001ULL,                         // 2⁵³、2⁵³+2
    0x43e0000000000000ULL, 0xc3e0000000000000ULL, 0x43dfffffffffffffULL,  // 2⁶³、−2⁶³、2⁶³ 的前一個
    0x43f0000000000000ULL,                                               // 2⁶⁴
    0x400921fb54442d18ULL, 0x3ff921fb54442d18ULL, 0x3fd921fb54442d18ULL,  // π、π/2、π/8
    0x406fe00000000000ULL, 0x406ff00000000000ULL, 0x40f0000000000000ULL,  // 255、255.5、65536
    0x3ff6666666666666ULL, 0x3fd6666666666666ULL, 0x3fe4cccccccccccdULL,  // 1.4、0.35、0.65
    0x4066800000000000ULL, 0x4076800000000000ULL,                         // 180、360
    0x7e37e43c8800759cULL, 0xfe37e43c8800759cULL, 0x01a56e1fc2f8f359ULL,  // ±1e300、1e−300
    0x40862e42fefa39efULL, 0xc0874910d52d3051ULL,                         // exp 的溢位／下溢門檻
};
constexpr int N_EDGE_D = sizeof(EDGE_D) / sizeof(EDGE_D[0]);

const uint32_t EDGE_F[] = {
    0x00000000U, 0x80000000U, 0x3f800000U, 0xbf800000U, 0x3f000000U, 0xbf000000U, 0x3fc00000U, 0xbfc00000U,
    0x40200000U, 0xc0200000U, 0x3effffffU, 0xbeffffffU,                   // ±0.49999997
    0x00000001U, 0x80000001U, 0x007fffffU, 0x00800000U, 0x80800000U, 0x7f7fffffU, 0xff7fffffU,
    0x7f800000U, 0xff800000U, 0x7fc00000U, 0xffc00000U, 0x7f800001U, 0x7fa00000U,
    0x4f000000U, 0xcf000000U, 0x4effffffU, 0xcf000001U,                   // 2³¹、−2³¹、2³¹−128、−2³¹−256
    0x4b000000U, 0x4affffffU, 0x4b800000U, 0x4b7fffffU,                   // 2²³、2²³−0.5、2²⁴、2²⁴−1
    0x5f000000U, 0xdf000000U, 0x5effffffU, 0x5f800000U,                   // 2⁶³、−2⁶³、2⁶³ 前一個、2⁶⁴
    0x437f0000U, 0x477fff00U,                                             // 255、65535
};
constexpr int N_EDGE_F = sizeof(EDGE_F) / sizeof(EDGE_F[0]);

const int64_t EDGE_I[] = {
    0, 1, -1, 2, -2, 3, -3, INT32_MIN, INT32_MAX, INT32_MIN + 1, INT32_MAX - 1, 255, 256, -256, 1000, -1000,
    65535, 65536, 0x40000000, -0x40000000, 31, 32, 33, 63, 64, 65, 7, 8, 16, -7, -8,
};
constexpr int N_EDGE_I = sizeof(EDGE_I) / sizeof(EDGE_I[0]);

const int64_t EDGE_L[] = {
    0, 1, -1, 2, -2, INT64_MIN, INT64_MAX, INT64_MIN + 1, INT64_MAX - 1, INT32_MIN, INT32_MAX,
    int64_t(1) << 31, int64_t(1) << 32, (int64_t(1) << 53), (int64_t(1) << 53) + 1, -(int64_t(1) << 53) - 1,
    int64_t(1) << 62, (int64_t(1) << 24) + 1, (int64_t(1) << 25) - 1, 9007199254740993LL, -9007199254740993LL,
    0x7fffffffffffff00LL, 255, 1000, -1000,
};
constexpr int N_EDGE_L = sizeof(EDGE_L) / sizeof(EDGE_L[0]);

// ── 一般產生器 ──────────────────────────────────────────────────────────

double genD(Rng& r) {
    const uint32_t sel = r.below(16);
    switch (sel) {
        case 0: case 1: case 2: case 3:
            return dfrom(r.next());                                            // 任意位元（含 NaN 酬載、次正規、極大極小）
        case 4: {
            double d = dfrom(EDGE_D[r.below(N_EDGE_D)]);
            if (r.below(4) == 0) d = nudge(d, r.range(-2, 2));
            return d;
        }
        case 5: {                                                              // k + 0.5（半數）
            const int p = static_cast<int>(r.below(53));
            return withSign(r, static_cast<double>(r.bits(p)) + 0.5);
        }
        case 6: {                                                              // 半數的鄰居
            const int p = static_cast<int>(r.below(53));
            const double h = static_cast<double>(r.bits(p)) + 0.5;
            const int k = r.coin() ? r.range(1, 3) : -r.range(1, 3);
            return withSign(r, nudge(h, k));
        }
        case 7: {                                                              // 整數值
            const int p = static_cast<int>(r.below(65));
            return withSign(r, static_cast<double>(r.bits(p)));
        }
        case 8:
        {
            const uint64_t s = r.next() & 0x8000000000000000ULL;            // 次正規數
            return dfrom(s | r.bits(52));
        }
        case 9: {                                                              // 飽和邊界附近
            static const int EXPS[] = {31, 32, 52, 53, 62, 63, 64, 24, 0, -1};
            double base = pow2(EXPS[r.below(10)]);
            switch (r.below(4)) {
                case 0: base = nudge(base, r.range(-4, 4)); break;
                case 1: base = base + (r.coin() ? 0.5 : -0.5); break;
                case 2: base = base + (r.coin() ? 1.0 : -1.0); break;
                default: break;
            }
            return withSign(r, base);
        }
        case 10: case 11: case 12: {                                          // [−R, R)，R＝2^(−20..60)
            const double R = pow2(static_cast<int>(r.below(81)) - 20);
            return r.uniform(-R, R);
        }
        case 13:
            return r.unit();
        case 14:                                                               // k/8（二進位上的整數與半數）
            return static_cast<double>(r.range(-4096, 4096)) / 8.0;
        default: {                                                             // k/10^d（像程式裡的比例）
            static const double P10[] = {1, 10, 100, 1000, 10000, 100000, 1000000};
            const double k = static_cast<double>(r.range(-1000000, 1000000));
            return k / P10[r.below(7)];
        }
    }
}

float genF(Rng& r) {
    const uint32_t sel = r.below(12);
    switch (sel) {
        case 0: case 1: case 2:
            return ffrom(static_cast<uint32_t>(r.next()));
        case 3: {
            float f = ffrom(EDGE_F[r.below(N_EDGE_F)]);
            if (r.below(4) == 0) f = nudgeF(f, r.range(-2, 2));
            return f;
        }
        case 4: {
            const int p = static_cast<int>(r.below(24));
            const float f = static_cast<float>(r.bits(p)) + 0.5f;            // p ≤ 23：精確
            return r.coin() ? -f : f;
        }
        case 5: {
            const int p = static_cast<int>(r.below(24));
            float f = static_cast<float>(r.bits(p)) + 0.5f;
            f = nudgeF(f, r.coin() ? r.range(1, 3) : -r.range(1, 3));
            return r.coin() ? -f : f;
        }
        case 6: {
            const int p = static_cast<int>(r.below(65));
            const float f = static_cast<float>(r.bits(p));
            return r.coin() ? -f : f;
        }
        case 7:
        {
            const uint32_t s = static_cast<uint32_t>(r.next()) & 0x80000000U;
            return ffrom(s | static_cast<uint32_t>(r.bits(23)));
        }
        case 8: {
            static const int EXPS[] = {31, 32, 23, 24, 63, 64, 0, -1};
            float base = static_cast<float>(pow2(EXPS[r.below(8)]));
            base = nudgeF(base, r.range(-4, 4));
            return r.coin() ? -base : base;
        }
        case 9: case 10: {
            const double R = pow2(static_cast<int>(r.below(61)) - 20);
            return static_cast<float>(r.uniform(-R, R));
        }
        default:
            return static_cast<float>(static_cast<double>(r.range(-4096, 4096)) / 8.0);
    }
}

int32_t genI(Rng& r) {
    switch (r.below(6)) {
        case 0: case 1: return static_cast<int32_t>(r.next());
        case 2: return static_cast<int32_t>(EDGE_I[r.below(N_EDGE_I)]);
        case 3: return r.range(-1000, 1000);
        case 4: { const int p = static_cast<int>(r.below(32)); const int32_t v = static_cast<int32_t>(r.bits(p)); return r.coin() ? -v : v; }
        default: return r.range(-16, 16);
    }
}

int64_t genL(Rng& r) {
    switch (r.below(6)) {
        case 0: case 1: return static_cast<int64_t>(r.next());
        case 2: return EDGE_L[r.below(N_EDGE_L)];
        case 3: return r.range(-1000, 1000);
        case 4: { const int p = static_cast<int>(r.below(64)); const int64_t v = static_cast<int64_t>(r.bits(p)); return r.coin() ? -v : v; }
        default: return static_cast<int64_t>(genI(r));
    }
}

// ── 函式表 ───────────────────────────────────────────────────────────────

enum Hint {
    H_GEN, H_TRIG, H_ATAN, H_ATAN2, H_EXP, H_POW, H_BD, H_DIV, H_MOD, H_PAIR, H_SHIFT, H_COERCE, H_EIGH, H_SQRT, H_LUTPOW,
};

const FnSpec FNS[] = {
    {"sin", 1, {KD}, 1, OD, H_TRIG},
    {"cos", 1, {KD}, 1, OD, H_TRIG},
    {"atan", 1, {KD}, 1, OD, H_ATAN},
    {"atan2", 2, {KD, KD}, 1, OD, H_ATAN2},
    {"exp", 1, {KD}, 1, OD, H_EXP},
    {"pow", 2, {KD, KD}, 1, OD, H_POW},
    {"sqrt", 1, {KD}, 1, OD, H_SQRT},
    {"floor", 1, {KD}, 1, OD, H_GEN},
    {"ceil", 1, {KD}, 1, OD, H_GEN},
    {"abs_d", 1, {KD}, 1, OD, H_GEN},
    {"rem_d", 2, {KD, KD}, 1, OD, H_MOD},
    {"mod_d", 2, {KD, KD}, 1, OD, H_MOD},
    {"signum_d", 1, {KD}, 1, OD, H_GEN},
    {"rint", 1, {KD}, 1, OD, H_GEN},
    {"round_d", 1, {KD}, 1, OL, H_GEN},
    {"round_f", 1, {KF}, 1, OI, H_GEN},
    {"roundToInt_d", 1, {KD}, 1, OI, H_GEN},
    {"roundToInt_f", 1, {KF}, 1, OI, H_GEN},
    {"d2i", 1, {KD}, 1, OI, H_GEN},
    {"d2l", 1, {KD}, 1, OL, H_GEN},
    {"f2i", 1, {KF}, 1, OI, H_GEN},
    {"f2l", 1, {KF}, 1, OL, H_GEN},
    {"d2f", 1, {KD}, 1, OF, H_GEN},
    {"l2d", 1, {KL}, 1, OD, H_GEN},
    {"l2f", 1, {KL}, 1, OF, H_GEN},
    {"i2f", 1, {KI}, 1, OF, H_GEN},
    {"bd_half_even", 2, {KD, KS}, 1, OD, H_BD},
    {"div_i", 2, {KI, KI}, 1, OI, H_DIV},
    {"rem_i", 2, {KI, KI}, 1, OI, H_DIV},
    {"floorDiv_i", 2, {KI, KI}, 1, OI, H_DIV},
    {"floorMod_i", 2, {KI, KI}, 1, OI, H_DIV},
    {"div_l", 2, {KL, KL}, 1, OL, H_DIV},
    {"rem_l", 2, {KL, KL}, 1, OL, H_DIV},
    {"floorDiv_l", 2, {KL, KL}, 1, OL, H_DIV},
    {"floorMod_l", 2, {KL, KL}, 1, OL, H_DIV},
    {"min_d", 2, {KD, KD}, 1, OD, H_PAIR},
    {"max_d", 2, {KD, KD}, 1, OD, H_PAIR},
    {"min_f", 2, {KF, KF}, 1, OF, H_PAIR},
    {"max_f", 2, {KF, KF}, 1, OF, H_PAIR},
    {"cmp_d", 2, {KD, KD}, 1, OI, H_PAIR},
    {"cmp_f", 2, {KF, KF}, 1, OI, H_PAIR},
    {"shl_i", 2, {KI, KSH}, 1, OI, H_SHIFT},
    {"shr_i", 2, {KI, KSH}, 1, OI, H_SHIFT},
    {"ushr_i", 2, {KI, KSH}, 1, OI, H_SHIFT},
    {"shl_l", 2, {KL, KSH}, 1, OL, H_SHIFT},
    {"shr_l", 2, {KL, KSH}, 1, OL, H_SHIFT},
    {"ushr_l", 2, {KL, KSH}, 1, OL, H_SHIFT},
    {"coerceIn_i", 3, {KI, KI, KI}, 1, OI, H_COERCE},
    {"coerceIn_d", 3, {KD, KD, KD}, 1, OD, H_COERCE},
    {"wmul_i", 2, {KI, KI}, 1, OI, H_GEN},
    {"eigh2", 3, {KD, KD, KD}, 6, OD, H_EIGH},
    {"pow_lut", 2, {KD, KD}, 1, OD, H_LUTPOW},
};
constexpr int N_FNS = sizeof(FNS) / sizeof(FNS[0]);

uint64_t encD(double d) { return dbits(d); }
uint64_t encF(float f) { return static_cast<uint64_t>(fbits(f)); }
uint64_t encI(int32_t v) { return static_cast<uint64_t>(static_cast<int64_t>(v)); }
uint64_t encL(int64_t v) { return static_cast<uint64_t>(v); }
double argD(uint64_t a) { return dfrom(a); }
float argF(uint64_t a) { return ffrom(static_cast<uint32_t>(a)); }
int32_t argI(uint64_t a) { return static_cast<int32_t>(static_cast<int64_t>(a)); }
int64_t argL(uint64_t a) { return static_cast<int64_t>(a); }

// 依提示產生一組引數（a[0..nIn)）
void genArgs(const FnSpec& f, Rng& r, uint64_t* a) {
    switch (f.hint) {
        case H_TRIG: {
            double x;
            switch (r.below(8)) {
                case 0: case 1: x = genD(r); break;
                case 2: x = r.uniform(-4, 4); break;
                case 3: x = r.uniform(-400, 400); break;
                case 4: x = r.uniform(-1e6, 1e6); break;
                case 5: { const double b = static_cast<double>(r.range(-64, 64)) * 0.39269908169872414; x = nudgeR(r, b, -2, 2); break; }
                case 6: { const double b = static_cast<double>(r.range(-1048576, 1048576)) * 1.5707963267948966; x = nudgeR(r, b, -2, 2); break; }
                default: x = withSign(r, magnitude(r, 20, 1022)); break;     // 大參數（rem_pio2 的大數路徑）
            }
            a[0] = encD(x);
            break;
        }
        case H_ATAN: {
            double x;
            switch (r.below(8)) {
                case 0: case 1: x = genD(r); break;
                case 2: x = r.uniform(-1, 1); break;
                case 3: { int32_t d = r.range(-2000, 2000); if (d == 0) d = 1; x = static_cast<double>(r.range(-2000, 2000)) / d; break; }
                case 4: {
                    static const double T[] = {0.4375, 0.6875, 1.1875, 2.4375, 7.378697629483821e19, 7.450580596923828e-9, 1.0};
                    x = withSign(r, nudgeR(r, T[r.below(7)], -3, 3));
                    break;
                }
                case 5: x = r.uniform(-100, 100); break;
                case 6: x = withSign(r, magnitude(r, -40, 80)); break;
                default: x = r.uniform(-1e6, 1e6); break;
            }
            a[0] = encD(x);
            break;
        }
        case H_ATAN2: {
            double y, x;
            switch (r.below(8)) {
                case 0: case 1: y = r.range(-2000, 2000); x = r.range(-2000, 2000); break;
                case 2: y = r.uniform(-2000, 2000); x = r.uniform(-2000, 2000); break;
                case 3: y = genD(r); x = genD(r); break;
                case 4: {
                    static const uint64_t S[] = {0x0ULL, 0x8000000000000000ULL, 0x7ff0000000000000ULL, 0xfff0000000000000ULL,
                                                 0x7ff8000000000000ULL, 0x3ff0000000000000ULL, 0xbff0000000000000ULL, 0x1ULL};
                    y = dfrom(S[r.below(8)]);
                    x = r.coin() ? dfrom(S[r.below(8)]) : genD(r);
                    if (r.coin()) { const double t = y; y = x; x = t; }
                    break;
                }
                case 5: x = genD(r); y = r.coin() ? x : -x; break;
                case 6: y = r.coin() ? 0.0 : -0.0; x = r.uniform(-10, 10); if (r.coin()) { const double t = y; y = x; x = t; } break;
                default: y = r.range(-50, 50); x = r.range(-50, 50); break;
            }
            a[0] = encD(y);
            a[1] = encD(x);
            break;
        }
        case H_EXP: {
            double x;
            switch (r.below(8)) {
                case 0: case 1: x = genD(r); break;
                case 2: x = r.uniform(-745.2, 709.8); break;
                case 3: x = r.uniform(-60, 5); break;
                case 4: {
                    const double d = r.range(0, 100);
                    const double sigma = static_cast<double>(r.range(30, 1000)) / 100.0;
                    x = -(d * d) / (2.0 * sigma * sigma);
                    break;
                }
                case 5: {
                    static const double T[] = {709.782712893384, -745.1332191019411, 0.34657359027997264, 1.0397207708399179,
                                               3.725290298461914e-9, 0.6931471805599453, -708.3964185322641};
                    x = withSign(r, nudgeR(r, T[r.below(7)], -3, 3));
                    break;
                }
                case 6: x = r.uniform(-9.5367431640625e-7, 9.5367431640625e-7); break;
                default: { const double b = static_cast<double>(r.range(-1000, 1000)) * 0.6931471805599453; x = nudgeR(r, b, -2, 2); break; }
            }
            a[0] = encD(x);
            break;
        }
        case H_POW: {
            double x, y;
            static const double SY[] = {0.0, -0.0, 0.5, -0.5, 2.0, 1.0, -1.0, 3.0, -2.0, 1.4,
                                        dfrom(0x7ff0000000000000ULL), dfrom(0xfff0000000000000ULL), dfrom(0x7ff8000000000000ULL),
                                        nudge(0.5, 1), nudge(0.5, -1), nudge(2.0, 1)};
            static const double SX[] = {0.0, -0.0, 1.0, -1.0, 2.0, 0.5, dfrom(0x7ff0000000000000ULL), dfrom(0xfff0000000000000ULL),
                                        dfrom(0x7ff8000000000000ULL), nudge(1.0, 1), nudge(1.0, -1), nudge(-1.0, 1), nudge(-1.0, -1)};
            switch (r.below(10)) {
                case 0: x = r.unit(); y = 1.4; break;
                case 1: { const int32_t k = r.range(0, 255); x = r.coin() ? (255.0 - k) / 255.0 : k / 255.0; y = 1.4; break; }
                case 2: x = r.unit(); y = r.uniform(0.3, 3.3); break;
                case 3: x = genD(r); y = genD(r); break;
                case 4: {
                    x = r.coin() ? nudge(1.0, r.range(-1000, 1000)) : 1.0 + r.uniform(-9.5367431640625e-7, 9.5367431640625e-7);
                    if (r.coin()) x = -x;
                    y = r.coin() ? withSign(r, magnitude(r, 20, 63)) : genD(r);
                    break;
                }
                case 5: {
                    x = -abs(genD(r));
                    switch (r.below(3)) {
                        case 0: y = r.range(-100, 100); break;
                        case 1: { const double b = pow2(r.range(52, 63)); const double k = static_cast<double>(r.range(0, 3) * 2); y = withSign(r, b + k); break; }
                        default: y = genD(r); break;
                    }
                    break;
                }
                case 6: x = genD(r); y = SY[r.below(sizeof(SY) / sizeof(SY[0]))]; break;
                case 7: x = SX[r.below(sizeof(SX) / sizeof(SX[0]))]; y = r.coin() ? genD(r) : SY[r.below(sizeof(SY) / sizeof(SY[0]))]; break;
                case 8: x = r.uniform(0, 1000); y = r.uniform(-10, 10); break;
                default: x = magnitude(r, -1000, 999); y = r.uniform(-2, 2); break;
            }
            a[0] = encD(x);
            a[1] = encD(y);
            break;
        }
        case H_LUTPOW: {                                                   // textLut／inkLut 實際用到的 pow 參數（整表）
            const int64_t k = static_cast<int64_t>(r.below(512));
            const int32_t it = static_cast<int32_t>(k & 255);
            a[0] = encD((k & 256) ? (255.0 - it) / 255.0 : it / 255.0);
            a[1] = encD(1.4);
            break;
        }
        case H_SQRT: {
            double x = genD(r);
            if (r.below(3) == 0) x = abs(x);
            if (r.below(4) == 0) { const double s = r.uniform(0, 1e4); x = r.coin() ? s * s : nudge(s * s, r.range(-2, 2)); }
            a[0] = encD(x);
            break;
        }
        case H_BD: {
            double v;
            switch (r.below(8)) {
                case 0: v = r.unit(); break;
                case 1: { const double k = static_cast<double>(r.bits(20)); v = k / pow2(r.range(1, 20)); break; }   // 二進位上的精確半數
                case 2: { const double k = static_cast<double>(r.range(0, 100000)); v = k / (1 + r.range(0, 100000)); break; }
                case 3: v = -r.unit() * 50; break;
                case 4: { const double b = (r.range(0, 1999) + 0.5) / 1000.0; v = nudge(b, r.coin() ? 1 : -1); break; }
                case 5: v = (r.range(0, 1999) + 0.5) / 1000.0; break;
                case 6: v = genD(r); break;
                default: v = r.coin() ? r.uniform(-1.152921504606847e18, 1.152921504606847e18)
                                      : withSign(r, magnitude(r, -1074, 1022)); break;
            }
            const uint32_t sp = r.below(10);
            const int32_t s = sp < 7 ? r.range(1, 4) : (sp < 9 ? r.range(0, 9) : r.range(0, 18));
            a[0] = encD(v);
            a[1] = encI(s);
            break;
        }
        case H_DIV: {
            const bool isLong = f.in[0] == KL;
            const uint32_t sel = r.below(100);
            if (isLong) {
                a[0] = encL(genL(r));
                int64_t b;
                if (sel < 30) { b = r.range(-16, 16); if (b == 0) b = 1; }
                else if (sel < 40) b = -1;
                else if (sel < 43) b = 0;
                else if (sel < 53) b = INT64_MIN;
                else b = genL(r);
                if (r.below(10) == 0) a[0] = encL(INT64_MIN);
                a[1] = encL(b);
            } else {
                a[0] = encI(genI(r));
                int32_t b;
                if (sel < 30) { b = r.range(-16, 16); if (b == 0) b = 1; }
                else if (sel < 40) b = -1;
                else if (sel < 43) b = 0;
                else if (sel < 53) b = INT32_MIN;
                else b = genI(r);
                if (r.below(10) == 0) a[0] = encI(INT32_MIN);
                a[1] = encI(b);
            }
            break;
        }
        case H_MOD: {
            double x = r.coin() ? genD(r) : r.uniform(-1000, 1000);
            double y;
            switch (r.below(8)) {
                case 0: case 1: {
                    static const double S[] = {180.0, 360.0, 1.0, -1.0, 6.283185307179586, 0.5, 3.0, -180.0};
                    y = S[r.below(8)];
                    break;
                }
                case 2: case 3: y = genD(r); break;
                case 4: y = r.uniform(-10, 10); break;
                case 5: {
                    static const uint64_t S[] = {0x0ULL, 0x8000000000000000ULL, 0x7ff0000000000000ULL, 0xfff0000000000000ULL,
                                                 0x7ff8000000000000ULL};
                    y = dfrom(S[r.below(5)]);
                    break;
                }
                default: y = r.uniform(-1000, 1000); break;
            }
            if (r.below(8) == 0) {                                             // 整除（餘數 ±0）
                x = y * static_cast<double>(r.range(-5, 5));
                // ∞·0 的 NaN 位元依平台而異（x86 回 0xfff8…、arm64 回 0x7ff8…）：產生器的輸出要兩個平台一樣，收成標準 NaN
                if (isNaN(x)) x = dfrom(0x7ff8000000000000ULL);
            }
            a[0] = encD(x);
            a[1] = encD(y);
            break;
        }
        case H_PAIR: {
            if (f.in[0] == KD) {
                const double x = genD(r);
                double y;
                switch (r.below(10)) {
                    case 0: case 1: case 2: case 3: y = genD(r); break;
                    case 4: case 5: y = x; break;
                    case 6: y = -x; break;
                    case 7: y = r.coin() ? nextUp(x) : nextDown(x); break;
                    case 8: y = r.coin() ? 0.0 : -0.0; break;
                    default: y = dfrom(r.coin() ? 0x7ff8000000000000ULL : 0xfff8000000000001ULL); break;
                }
                if (r.below(8) == 0) { const double t = r.coin() ? 0.0 : -0.0; a[0] = encD(t); a[1] = encD(r.coin() ? 0.0 : -0.0); }
                else if (r.coin()) { a[0] = encD(x); a[1] = encD(y); }
                else { a[0] = encD(y); a[1] = encD(x); }
            } else {
                const float x = genF(r);
                float y;
                switch (r.below(10)) {
                    case 0: case 1: case 2: case 3: y = genF(r); break;
                    case 4: case 5: y = x; break;
                    case 6: y = -x; break;
                    case 7: y = r.coin() ? nextUpF(x) : nextDownF(x); break;
                    case 8: y = r.coin() ? 0.0f : -0.0f; break;
                    default: y = ffrom(r.coin() ? 0x7fc00000U : 0xffc00001U); break;
                }
                if (r.below(8) == 0) { a[0] = encF(r.coin() ? 0.0f : -0.0f); a[1] = encF(r.coin() ? 0.0f : -0.0f); }
                else if (r.coin()) { a[0] = encF(x); a[1] = encF(y); }
                else { a[0] = encF(y); a[1] = encF(x); }
            }
            break;
        }
        case H_SHIFT: {
            a[0] = f.in[0] == KL ? encL(genL(r)) : encI(genI(r));
            a[1] = encI(r.coin() ? static_cast<int32_t>(r.next()) : r.range(-70, 140));
            break;
        }
        case H_COERCE: {
            if (f.in[0] == KI) {
                int32_t lo = genI(r), hi = genI(r);
                if (r.below(5) == 0) hi = lo;
                if (lo > hi) { const int32_t t = lo; lo = hi; hi = t; }
                const uint32_t sel = r.below(4);
                const int32_t v = sel == 0 ? lo : (sel == 1 ? hi : genI(r));
                a[0] = encI(v); a[1] = encI(lo); a[2] = encI(hi);
            } else {
                double lo = genD(r), hi = genD(r);
                if (r.below(5) == 0) hi = lo;
                if (lo > hi) { const double t = lo; lo = hi; hi = t; }
                const uint32_t sel = r.below(4);
                const double v = sel == 0 ? lo : (sel == 1 ? hi : genD(r));
                a[0] = encD(v); a[1] = encD(lo); a[2] = encD(hi);
            }
            break;
        }
        case H_EIGH: {
            double x, y, z;
            switch (r.below(8)) {
                case 0: x = r.uniform(0, 1e4); y = r.uniform(-1e4, 1e4); z = r.uniform(0, 1e4); break;
                case 1: x = r.range(0, 50); y = r.range(-50, 50); z = r.range(0, 50); break;
                case 2: {
                    x = r.uniform(0, 1e4); z = r.uniform(0, 1e4);
                    if (r.coin()) { y = 0.0; } else { const double t = x * pow2(-r.range(0, 69)); y = r.coin() ? t : -t; }
                    break;
                }
                case 3: x = r.uniform(-1e4, 1e4); z = x; y = r.coin() ? r.uniform(-1e4, 1e4) : 0.0; break;
                case 4: x = genD(r); y = genD(r); z = genD(r); break;
                case 5: x = r.uniform(0, 1e8); y = r.uniform(-1e8, 1e8); z = r.uniform(0, 1e8); break;
                case 6: {
                    x = withSign(r, magnitude(r, -300, 300));
                    y = withSign(r, magnitude(r, -300, 300));
                    z = withSign(r, magnitude(r, -300, 300));
                    break;
                }
                default: {
                    static const uint64_t S[] = {0x0ULL, 0x8000000000000000ULL, 0x3ff0000000000000ULL, 0xbff0000000000000ULL,
                                                 0x7ff0000000000000ULL, 0x7ff8000000000000ULL, 0x4000000000000000ULL};
                    x = dfrom(S[r.below(7)]); y = dfrom(S[r.below(7)]); z = dfrom(S[r.below(7)]);
                    break;
                }
            }
            a[0] = encD(x); a[1] = encD(y); a[2] = encD(z);
            break;
        }
        default: {                                                         // H_GEN：依引數型別
            for (int k = 0; k < f.nIn; k++) {
                switch (f.in[k]) {
                    case KD: a[k] = encD(genD(r)); break;
                    case KF: a[k] = encF(genF(r)); break;
                    case KI: a[k] = encI(genI(r)); break;
                    case KL: a[k] = encL(genL(r)); break;
                    case KS: a[k] = encI(r.range(0, 18)); break;
                    case KSH: a[k] = encI(r.range(-70, 140)); break;
                }
            }
            break;
        }
    }
}

}  // namespace

int fnCount() { return N_FNS; }
const FnSpec& fnSpec(int fn) { return FNS[fn]; }
int fnIndex(const char* name) {
    for (int i = 0; i < N_FNS; i++) if (std::strcmp(FNS[i].name, name) == 0) return i;
    return -1;
}

void genScalar(int fn, int64_t start, int64_t n, uint64_t* args) {
    const FnSpec& f = FNS[fn];
    const uint64_t seed = nameSeed(f.name);
    for (int64_t i = 0; i < n; i++) {
        Rng r = caseRng(seed, start + i);
        genArgs(f, r, args + i * f.nIn);
    }
}

uint64_t canon(OutKind k, uint64_t v) {
    if (k == OD && isNaN(dfrom(v))) return 0x7ff8000000000000ULL;
    if (k == OF && isNaN(ffrom(static_cast<uint32_t>(v)))) return 0x7fc00000ULL;
    return v;
}

void evalScalar(int fn, const uint64_t* args, int64_t n, uint64_t* out) {
    const FnSpec& f = FNS[fn];
    const int stride = f.nOut + 1;
    for (int64_t i = 0; i < n; i++) {
        const uint64_t* a = args + i * f.nIn;
        uint64_t* o = out + i * stride;
        for (int k = 0; k < stride; k++) o[k] = 0;
        uint64_t& st = o[f.nOut];
        const char* nm = f.name;
        // 依名稱分派（測試用，速度不重要；名稱與 Kotlin 端的參考實作一一對應）
        if (!std::strcmp(nm, "sin")) o[0] = encD(sin(argD(a[0])));
        else if (!std::strcmp(nm, "cos")) o[0] = encD(cos(argD(a[0])));
        else if (!std::strcmp(nm, "atan")) o[0] = encD(atan(argD(a[0])));
        else if (!std::strcmp(nm, "atan2")) o[0] = encD(atan2(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "exp")) o[0] = encD(exp(argD(a[0])));
        else if (!std::strcmp(nm, "pow") || !std::strcmp(nm, "pow_lut")) o[0] = encD(pow(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "sqrt")) o[0] = encD(sqrt(argD(a[0])));
        else if (!std::strcmp(nm, "floor")) o[0] = encD(floor(argD(a[0])));
        else if (!std::strcmp(nm, "ceil")) o[0] = encD(ceil(argD(a[0])));
        else if (!std::strcmp(nm, "abs_d")) o[0] = encD(abs(argD(a[0])));
        else if (!std::strcmp(nm, "rem_d")) o[0] = encD(rem(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "mod_d")) o[0] = encD(mod(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "signum_d")) o[0] = encD(signum(argD(a[0])));
        else if (!std::strcmp(nm, "rint")) o[0] = encD(rint(argD(a[0])));
        else if (!std::strcmp(nm, "round_d")) o[0] = encL(round(argD(a[0])));
        else if (!std::strcmp(nm, "round_f")) o[0] = encI(round(argF(a[0])));
        else if (!std::strcmp(nm, "roundToInt_d")) {
            const double x = argD(a[0]);
            if (isNaN(x)) st = 1; else o[0] = encI(roundToInt(x));
        } else if (!std::strcmp(nm, "roundToInt_f")) {
            const float x = argF(a[0]);
            if (isNaN(x)) st = 1; else o[0] = encI(roundToInt(x));
        }
        else if (!std::strcmp(nm, "d2i")) o[0] = encI(d2i(argD(a[0])));
        else if (!std::strcmp(nm, "d2l")) o[0] = encL(d2l(argD(a[0])));
        else if (!std::strcmp(nm, "f2i")) o[0] = encI(f2i(argF(a[0])));
        else if (!std::strcmp(nm, "f2l")) o[0] = encL(f2l(argF(a[0])));
        else if (!std::strcmp(nm, "d2f")) o[0] = encF(d2f(argD(a[0])));
        else if (!std::strcmp(nm, "l2d")) o[0] = encD(l2d(argL(a[0])));
        else if (!std::strcmp(nm, "l2f")) o[0] = encF(l2f(argL(a[0])));
        else if (!std::strcmp(nm, "i2f")) o[0] = encF(i2f(argI(a[0])));
        else if (!std::strcmp(nm, "bd_half_even")) {
            const double v = argD(a[0]);
            if (isNaN(v) || abs(v) == dfrom(0x7ff0000000000000ULL)) st = 1;
            else o[0] = encD(bdHalfEven(v, argI(a[1])));
        }
        else if (!std::strcmp(nm, "div_i")) { if (argI(a[1]) == 0) st = 1; else o[0] = encI(div(argI(a[0]), argI(a[1]))); }
        else if (!std::strcmp(nm, "rem_i")) { if (argI(a[1]) == 0) st = 1; else o[0] = encI(rem(argI(a[0]), argI(a[1]))); }
        else if (!std::strcmp(nm, "floorDiv_i")) { if (argI(a[1]) == 0) st = 1; else o[0] = encI(floorDiv(argI(a[0]), argI(a[1]))); }
        else if (!std::strcmp(nm, "floorMod_i")) { if (argI(a[1]) == 0) st = 1; else o[0] = encI(floorMod(argI(a[0]), argI(a[1]))); }
        else if (!std::strcmp(nm, "div_l")) { if (argL(a[1]) == 0) st = 1; else o[0] = encL(div(argL(a[0]), argL(a[1]))); }
        else if (!std::strcmp(nm, "rem_l")) { if (argL(a[1]) == 0) st = 1; else o[0] = encL(rem(argL(a[0]), argL(a[1]))); }
        else if (!std::strcmp(nm, "floorDiv_l")) { if (argL(a[1]) == 0) st = 1; else o[0] = encL(floorDiv(argL(a[0]), argL(a[1]))); }
        else if (!std::strcmp(nm, "floorMod_l")) { if (argL(a[1]) == 0) st = 1; else o[0] = encL(floorMod(argL(a[0]), argL(a[1]))); }
        else if (!std::strcmp(nm, "min_d")) o[0] = encD(min(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "max_d")) o[0] = encD(max(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "min_f")) o[0] = encF(min(argF(a[0]), argF(a[1])));
        else if (!std::strcmp(nm, "max_f")) o[0] = encF(max(argF(a[0]), argF(a[1])));
        else if (!std::strcmp(nm, "cmp_d")) o[0] = encI(compare(argD(a[0]), argD(a[1])));
        else if (!std::strcmp(nm, "cmp_f")) o[0] = encI(compare(argF(a[0]), argF(a[1])));
        else if (!std::strcmp(nm, "shl_i")) o[0] = encI(shl(argI(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "shr_i")) o[0] = encI(shr(argI(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "ushr_i")) o[0] = encI(ushr(argI(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "shl_l")) o[0] = encL(shl(argL(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "shr_l")) o[0] = encL(shr(argL(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "ushr_l")) o[0] = encL(ushr(argL(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "coerceIn_i")) o[0] = encI(coerceIn(argI(a[0]), argI(a[1]), argI(a[2])));
        else if (!std::strcmp(nm, "coerceIn_d")) o[0] = encD(coerceIn(argD(a[0]), argD(a[1]), argD(a[2])));
        else if (!std::strcmp(nm, "wmul_i")) o[0] = encI(wmul(argI(a[0]), argI(a[1])));
        else if (!std::strcmp(nm, "eigh2")) {
            double r6[6];
            eigh2(argD(a[0]), argD(a[1]), argD(a[2]), r6);
            for (int k = 0; k < 6; k++) o[k] = encD(r6[k]);
        }
        else st = 2;   // 沒有實作（測試會報錯）
    }
}

// ── 陣列案例 ────────────────────────────────────────────────────────────

namespace {

double sumValue(Rng& r, int style) {
    switch (style) {
        case 0: return r.uniform(-1, 1);
        case 1: { const double u = r.uniform(-1, 1); return u * pow2(r.range(-60, 60)); }
        case 2: return static_cast<double>(r.range(-100, 100));
        case 3: return genD(r);
        case 4: return r.coin() ? (r.coin() ? 1e16 : -1e16) : r.uniform(-1, 1);
        default: return r.coin() ? -0.0 : (r.below(8) == 0 ? 0.0 : -0.0);
    }
}

}  // namespace

std::vector<double> genSumArray(bool chunked, int64_t idx) {
    Rng r = caseRng(nameSeed(chunked ? "npSumChunked" : "npSum"), idx);
    int64_t n;
    if (chunked) {
        static const int64_t L[] = {0, 1, 8191, 8192, 8193, 16383, 16384, 16385, 24576, 40000, 49152, 57344};
        n = r.below(3) == 0 ? L[r.below(12)] : r.range(0, 60000);
    } else {
        static const int64_t L[] = {0, 1, 7, 8, 9, 15, 16, 17, 127, 128, 129, 135, 136, 255, 256, 257, 1023, 1024, 1025, 8191, 8192, 8193};
        switch (r.below(4)) {
            case 0: n = r.range(0, 16); break;
            case 1: n = r.range(0, 300); break;
            case 2: n = r.range(0, 20000); break;
            default: { const int64_t b = L[r.below(22)]; n = b + (r.coin() ? 0 : r.range(0, 3)); break; }
        }
    }
    const int style = static_cast<int>(r.below(6));
    std::vector<double> a(static_cast<size_t>(n));
    for (auto& v : a) v = sumValue(r, style);
    return a;
}

std::vector<int32_t> antiqsort(int32_t n, int* heapCalls) {
    std::vector<int32_t> val(static_cast<size_t>(n), n);   // n＝gas（比任何已凍結的值都大）
    const int32_t gas = n;
    int32_t nsolid = 0;
    int32_t candidate = 0;
    auto less = [&](int32_t x, int32_t y) {
        if (val[x] == gas && val[y] == gas) {
            if (x == candidate) val[x] = nsolid++; else val[y] = nsolid++;
        }
        if (val[x] == gas) candidate = x;
        else if (val[y] == gas) candidate = y;
        return val[x] < val[y];
    };
    std::vector<int32_t> ts(static_cast<size_t>(n));
    int heaps = 0;
    if (n > 0) npArgsortT(n, ts.data(), less, [&] { heaps++; });
    for (auto& v : val) if (v == gas) v = nsolid++;
    if (heapCalls != nullptr) *heapCalls = heaps;
    return val;
}

std::vector<int32_t> genArgsortArray(int64_t idx) {
    Rng r = caseRng(nameSeed("npArgsort"), idx);
    int32_t n;
    switch (r.below(8)) {
        case 0: case 1: n = r.range(0, 40); break;
        case 2: case 3: case 4: n = r.range(0, 2000); break;
        case 5: { static const int32_t L[] = {15, 16, 17, 31, 32, 33, 63, 64, 65}; n = L[r.below(9)]; break; }
        case 6: n = r.range(2000, 6000); break;
        default: n = r.below(16) == 0 ? r.range(10000, 100000) : r.range(0, 500); break;
    }
    const int style = static_cast<int>(r.below(9));
    if (style == 8 && n <= 6000) {
        std::vector<int32_t> v = antiqsort(n);
        if (r.coin()) for (auto& x : v) x = x * 3 - 7;                    // 平移縮放不改順序
        return v;
    }
    std::vector<int32_t> v(static_cast<size_t>(n));
    switch (style) {
        case 0: for (auto& x : v) x = static_cast<int32_t>(r.next()); break;
        case 1: { static const int32_t K[] = {1, 2, 3, 5, 10, 100}; const int32_t k = K[r.below(6)]; for (auto& x : v) x = r.range(0, k - 1); break; }
        case 2: for (int32_t i = 0; i < n; i++) v[i] = i / (1 + static_cast<int32_t>(r.below(3))); break;
        case 3: for (int32_t i = 0; i < n; i++) v[i] = n - i; break;
        case 4: for (int32_t i = 0; i < n; i++) v[i] = i < n / 2 ? i : n - i; break;
        case 5: { const int32_t c = genI(r); for (auto& x : v) x = c; break; }
        case 6: { const int32_t m = 1 + static_cast<int32_t>(r.below(20)); for (int32_t i = 0; i < n; i++) v[i] = i % m; break; }
        case 7: for (auto& x : v) x = genI(r); break;
        default: for (auto& x : v) x = r.range(-1000, 1000); break;
    }
    return v;
}

std::vector<int32_t> genPixels(int64_t n, uint64_t seed) {
    std::vector<int32_t> px(static_cast<size_t>(n));
    static const int32_t C[] = {0, 1, 127, 128, 254, 255};
    static const uint32_t A[] = {0x00U, 0x80U, 0xFFU};
    int64_t i = 0;
    for (int a = 0; a < 3 && i < n; a++)
        for (int rr = 0; rr < 6 && i < n; rr++)
            for (int g = 0; g < 6 && i < n; g++)
                for (int b = 0; b < 6 && i < n; b++, i++)
                    px[static_cast<size_t>(i)] = static_cast<int32_t>((A[a] << 24) | (static_cast<uint32_t>(C[rr]) << 16) |
                                                                     (static_cast<uint32_t>(C[g]) << 8) | static_cast<uint32_t>(C[b]));
    Rng r(mix(seed));
    for (; i < n; i++) px[static_cast<size_t>(i)] = static_cast<int32_t>(r.next());
    return px;
}

}  // namespace nrt
