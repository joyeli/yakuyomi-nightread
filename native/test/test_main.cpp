// 夜讀 C++ 核心的原生單元測試（ctest：nr_core_tests）。不需要 JVM。
//
// 全部測試都在一條 256 KB 堆疊的執行緒上跑（手機上 C++ 跑在 Kotlin 的工作執行緒上，堆疊約 1 MB；核心規定不遞迴、不在堆疊放大陣列，
// 這裡用更小的堆疊抓違規）。並行測試另開執行緒（TSan 建置用它抓資料競爭）。
//
// 這裡只放「不用 Java 也能確定答案」的檢查：已知值、不變量、錯誤碼、邊界。逐位元對 Java 的比對在 JUnit
// （CppJmathParityTest 等）與 golden_check（arm64 用）。
#include <pthread.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <thread>
#include <vector>

#include "../core/convert.h"
#include "../core/jmath.h"
#include "../core/params_gen.h"
#include "../core/prims.h"
#include "../core/tables.h"
#include "../core/types.h"
#include "cases.h"
#include "nightread/nr.h"

namespace {

// 用 using 宣告（不是 using namespace）：libc++ 會把 ::round、::abs… 放進全域，using namespace 會讓呼叫變成有歧義。
using nr::jm::dbits;
using nr::jm::dfrom;
using nr::jm::fbits;
using nr::jm::ffrom;
using nr::jm::isNaN;
using nr::jm::rint;
using nr::jm::round;
using nr::jm::roundToInt;
using nr::jm::d2i;
using nr::jm::d2l;
using nr::jm::f2i;
using nr::jm::f2l;
using nr::jm::bdHalfEven;
using nr::jm::floorDiv;
using nr::jm::floorMod;
using nr::jm::shl;
using nr::jm::shr;
using nr::jm::ushr;
using nr::jm::wmul;
using nr::jm::wadd;
using nr::jm::coerceIn;
using nr::jm::mod;
using nr::jm::min;
using nr::jm::max;
using nr::jm::compare;
using nr::jm::signum;
using nr::jm::sin;
using nr::jm::cos;
using nr::jm::exp;
using nr::jm::pow;
using nr::jm::atan;
using nr::jm::atan2;
using nr::jm::sqrt;
using nr::jm::npSum;
using nr::jm::npSumChunked;
using nr::jm::npArgsort;
using nr::jm::npArgsortT;
using nr::jm::eigh2;


int g_fail = 0;
int g_checks = 0;

#define CHECK(cond)                                                                    \
    do {                                                                               \
        g_checks++;                                                                    \
        if (!(cond)) { g_fail++; std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } \
    } while (0)

#define CHECK_BITS(a, b)                                                                                        \
    do {                                                                                                        \
        g_checks++;                                                                                             \
        const double _a = (a), _b = (b);                                                                        \
        if (dbits(_a) != dbits(_b)) {                                                                           \
            g_fail++;                                                                                           \
            std::printf("FAIL %s:%d: %s = %.17g (0x%016llx) != %.17g (0x%016llx)\n", __FILE__, __LINE__, #a, _a, \
                        (unsigned long long)dbits(_a), _b, (unsigned long long)dbits(_b));                     \
        }                                                                                                       \
    } while (0)

double D(uint64_t b) { return dfrom(b); }
const double NaN = D(0x7ff8000000000000ULL);
const double INF = D(0x7ff0000000000000ULL);

void testRounding() {
    CHECK_BITS(rint(2.5), 2.0);
    CHECK_BITS(rint(3.5), 4.0);
    CHECK_BITS(rint(-2.5), -2.0);
    CHECK_BITS(rint(-0.4), -0.0);
    CHECK_BITS(rint(-0.5), -0.0);
    CHECK_BITS(rint(0.5), 0.0);
    CHECK_BITS(rint(1e300), 1e300);
    CHECK_BITS(rint(4503599627370495.5), 4503599627370496.0);
    CHECK(round(0.49999999999999994) == 0);
    CHECK(round(-0.5) == 0);
    CHECK(round(-2.5) == -2);
    CHECK(round(2.5) == 3);
    CHECK(round(NaN) == 0);
    CHECK(round(1e20) == INT64_MAX);
    CHECK(round(-1e20) == INT64_MIN);
    CHECK(round(4503599627370497.0) == 4503599627370497LL);
    CHECK(round(0.49999997f) == 0);
    CHECK(round(-0.5f) == 0);
    CHECK(round(2.5f) == 3);
    CHECK(round(1e10f) == INT32_MAX);
    CHECK(round(ffrom(0x7fc00000U)) == 0);
    CHECK(roundToInt(3e9) == INT32_MAX);
    CHECK(roundToInt(-3e9) == INT32_MIN);
    CHECK(roundToInt(2.5) == 3);
    CHECK(roundToInt(-2.5) == -2);
    CHECK(roundToInt(-2147483648.6) == INT32_MIN);
    CHECK(d2i(NaN) == 0);
    CHECK(d2i(1e10) == INT32_MAX);
    CHECK(d2i(-1e10) == INT32_MIN);
    CHECK(d2i(-2147483648.9) == INT32_MIN);
    CHECK(d2i(2147483647.9) == INT32_MAX);
    CHECK(d2i(-0.9) == 0);
    CHECK(d2l(9.3e18) == INT64_MAX);
    CHECK(d2l(-9.3e18) == INT64_MIN);
    CHECK(d2l(NaN) == 0);
    CHECK(f2i(3e9f) == INT32_MAX);
    CHECK(f2l(-1e19f) == INT64_MIN);
}

void testBigDecimal() {
    CHECK_BITS(bdHalfEven(0.125, 2), 0.12);
    CHECK_BITS(bdHalfEven(0.375, 2), 0.38);
    CHECK_BITS(bdHalfEven(2.5, 0), 2.0);
    CHECK_BITS(bdHalfEven(3.5, 0), 4.0);
    CHECK_BITS(bdHalfEven(-0.004, 2), 0.0);          // BigDecimal 沒有 −0
    CHECK_BITS(bdHalfEven(-0.0, 3), 0.0);
    CHECK_BITS(bdHalfEven(0.0005, 3), 0.001);        // 0.0005 的二進位值略大於 0.0005
    CHECK_BITS(bdHalfEven(1.0005, 3), 1.0);          // 1.0005 的二進位值略小
    CHECK_BITS(bdHalfEven(-2.675, 2), -2.67);
    CHECK_BITS(bdHalfEven(1e-320, 3), 0.0);
    CHECK_BITS(bdHalfEven(1e20, 2), 1e20);
    CHECK_BITS(bdHalfEven(2251799813685248.5, 9), 2251799813685248.5);   // 大商路徑
    CHECK_BITS(bdHalfEven(0.1, 18), 0.1);                                 // 大商路徑
    CHECK(isNaN(bdHalfEven(NaN, 1)));
    CHECK(isNaN(bdHalfEven(INF, 1)));
    CHECK(isNaN(bdHalfEven(1.0, 19)));
}

void testIntegers() {
    CHECK(floorDiv(-7, 2) == -4);
    CHECK(floorMod(-7, 2) == 1);
    CHECK(floorMod(7, -2) == -1);
    CHECK(floorDiv(INT32_MIN, -1) == INT32_MIN);
    CHECK(floorMod(INT32_MIN, -1) == 0);
    CHECK(nr::jm::div(INT32_MIN, -1) == INT32_MIN);
    CHECK(nr::jm::rem(INT32_MIN, -1) == 0);
    CHECK(nr::jm::div(INT64_MIN, int64_t(-1)) == INT64_MIN);
    CHECK(nr::jm::rem(-7, 2) == -1);
    CHECK(floorDiv(int64_t(-7), int64_t(2)) == -4);
    CHECK(shl(1, 33) == 2);
    CHECK(shl(1, -1) == INT32_MIN);
    CHECK(ushr(-1, 28) == 15);
    CHECK(shr(-16, 2) == -4);
    CHECK(shl(int64_t(1), 65) == 2);
    CHECK(ushr(int64_t(-1), 60) == 15);
    CHECK(wmul(65536, 65536) == 0);
    CHECK(wadd(INT32_MAX, 1) == INT32_MIN);
    CHECK(coerceIn(5, 0, 3) == 3);
    CHECK(coerceIn(-5, 0, 3) == 0);
}

void testCompare() {
    CHECK_BITS(mod(-1.0, 3.0), 2.0);
    CHECK_BITS(mod(1.0, -3.0), -2.0);
    CHECK_BITS(mod(-0.0, 3.0), -0.0);
    CHECK_BITS(mod(5.0, INF), 5.0);
    CHECK_BITS(mod(-5.0, INF), INF);
    CHECK_BITS(mod(-190.0, 180.0), 170.0);
    CHECK_BITS(min(-0.0, 0.0), -0.0);
    CHECK_BITS(min(0.0, -0.0), -0.0);
    CHECK_BITS(max(-0.0, 0.0), 0.0);
    CHECK_BITS(max(0.0, -0.0), 0.0);
    CHECK(isNaN(min(NaN, 1.0)) && isNaN(min(1.0, NaN)) && isNaN(max(1.0, NaN)));
    CHECK(fbits(min(-0.0f, 0.0f)) == 0x80000000U);
    CHECK(compare(0.0, -0.0) == 1);
    CHECK(compare(-0.0, 0.0) == -1);
    CHECK(compare(NaN, D(0xfff8000000000001ULL)) == 0);
    CHECK(compare(NaN, INF) == 1);
    CHECK(compare(1.0, 2.0) == -1);
    CHECK(compare(0.0f, -0.0f) == 1);
    CHECK_BITS(signum(-0.0), -0.0);
    CHECK_BITS(signum(-3.0), -1.0);
    CHECK(isNaN(coerceIn(NaN, 0.0, 1.0)));
    CHECK_BITS(coerceIn(-1.0, 0.0, 1.0), 0.0);
}

void testFdlibm() {
    CHECK_BITS(sin(0.0), 0.0);
    CHECK_BITS(sin(-0.0), -0.0);
    CHECK_BITS(cos(0.0), 1.0);
    CHECK_BITS(exp(0.0), 1.0);
    CHECK_BITS(exp(1.0), D(0x4005BF0A8B14576AULL));             // StrictMath.exp(1.0)：比 Math.E 大 1 ulp（HotSpot 的 Math.exp 內建版回 Math.E）
    CHECK_BITS(pow(2.0, 10.0), 1024.0);
    CHECK_BITS(pow(3.0, 2.0), 9.0);
    CHECK_BITS(pow(0.25, 0.5), 0.5);
    CHECK_BITS(atan2(0.0, -0.0), D(0x400921FB54442D18ULL));     // π
    CHECK_BITS(atan2(-0.0, -0.0), D(0xC00921FB54442D18ULL));    // −π
    CHECK_BITS(atan(INF), D(0x3FF921FB54442D18ULL));            // π/2
    CHECK_BITS(sin(D(0x400921FB54442D18ULL)), 1.2246467991473532e-16);
    CHECK(isNaN(pow(-8.0, 1.0 / 3.0)));
    CHECK(isNaN(sin(INF)));
    CHECK_BITS(sqrt(2.0), D(0x3FF6A09E667F3BCDULL));
}

void testArrays() {
    std::vector<double> a(1000);
    for (int i = 0; i < 1000; i++) a[i] = i + 1;
    CHECK_BITS(npSum(a.data(), 1000), 500500.0);
    const double z3[] = {-0.0, -0.0, -0.0};
    CHECK_BITS(npSum(z3, 3), 0.0);                               // n < 8：從 +0.0 開始加
    const double z8[] = {-0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0, -0.0};
    CHECK_BITS(npSum(z8, 8), -0.0);                              // 八路累加器從元素本身開始
    CHECK_BITS(npSum(nullptr, 0), 0.0);
    std::vector<double> ones(20000, 1.0);
    CHECK_BITS(npSumChunked(ones.data(), 20000), 20000.0);
    CHECK_BITS(npSumChunked(nullptr, 0), 0.0);
    // 很長的陣列（顯式堆疊夠深）
    std::vector<double> big(5000000, 0.5);
    CHECK_BITS(npSum(big.data(), static_cast<int64_t>(big.size())), 2500000.0);

    // npArgsort：結果是排列、且依值不遞減
    for (int64_t idx = 0; idx < 300; idx++) {
        const std::vector<int32_t> v = nrt::genArgsortArray(idx);
        std::vector<int32_t> ts(v.size());
        npArgsort(v.data(), static_cast<int32_t>(v.size()), ts.data());
        std::vector<uint8_t> seen(v.size(), 0);
        bool ok = true;
        for (size_t i = 0; i < ts.size(); i++) {
            if (ts[i] < 0 || static_cast<size_t>(ts[i]) >= v.size() || seen[ts[i]]) { ok = false; break; }
            seen[ts[i]] = 1;
            if (i > 0 && v[ts[i - 1]] > v[ts[i]]) { ok = false; break; }
        }
        CHECK(ok);
    }
    // McIlroy 對手要真的逼出 heapsort，否則 npArgsort 的深度保護路徑沒被測到
    for (int32_t n : {200, 1000, 5000}) {
        int heaps = 0;
        const std::vector<int32_t> v = nrt::antiqsort(n, &heaps);
        CHECK(heaps > 0);
        int heaps2 = 0;
        std::vector<int32_t> ts(v.size());
        npArgsortT(n, ts.data(), [&](int32_t i, int32_t j) { return v[i] < v[j]; }, [&] { heaps2++; });
        CHECK(heaps2 == heaps);                                  // 用它當輸入，同樣走到 heapsort
        std::printf("  antiqsort n=%d: heapsort 進入 %d 次\n", n, heaps);
    }

    double e[6];
    eigh2(1.0, 0.0, 1.0, e);
    CHECK_BITS(e[0], 1.0); CHECK_BITS(e[1], 1.0); CHECK_BITS(e[2], 1.0); CHECK_BITS(e[3], 0.0); CHECK_BITS(e[4], 0.0); CHECK_BITS(e[5], 1.0);
    eigh2(3.0, 0.0, 1.0, e);                                     // 對角、需交換
    CHECK_BITS(e[0], 1.0); CHECK_BITS(e[1], 3.0); CHECK_BITS(e[2], 0.0); CHECK_BITS(e[3], 1.0);
}

void testTables() {
    for (double sigma : {0.3, 1.0, 2.5, 7.77, 10.0}) {
        const std::vector<int32_t> w = nr::tables::gaussW(sigma);
        int64_t s = 0;
        for (int32_t v : w) s += v;
        CHECK(s == 65536);
        CHECK(w.size() % 2 == 1);
        bool sym = true;
        for (size_t i = 0; i < w.size() / 2; i++) if (w[i] != w[w.size() - 1 - i]) sym = false;
        CHECK(sym);
    }
    CHECK(nr::tables::gaussW(1.0).size() == 9);
    CHECK(nr::tables::gaussianBlurKsize(1.0) == 9);
    CHECK(nr::tables::gaussianBlurKsize(0.1) == 3);
    float lut[256];
    nr::tables::textLut(16, 240, lut);
    CHECK(lut[0] == 240.0f && lut[255] == 16.0f);
    nr::tables::inkLut(16, 170, false, lut);
    CHECK(lut[0] == 16.0f && lut[255] == 170.0f);
    std::vector<std::vector<int32_t>> off;
    CHECK(nr::tables::lineOffsets(1, off) && off.size() == 8 && off[0].size() == 2 && off[0][0] == 0 && off[0][1] == 0);
    CHECK(!nr::tables::lineOffsets(2, off) && off.empty());       // 偶數長度：Kotlin 丟 ArrayIndexOutOfBounds
    CHECK(nr::tables::lineOffsets(17, off) && off.size() == 8);
    CHECK(off[0].size() == 2 * 17);                              // 水平方向：17 個點
}

void testTypes() {
    nr::Budget b;
    {
        nr::Mask m(1351, 1920, &b);
        bool zero = true;
        for (size_t i = 0; i < m.data.size(); i++) if (m.data[i]) { zero = false; break; }
        CHECK(zero);
        CHECK(b.cur.load() == 1351 * 1920);
        nr::ImgI32 g(10, 10, &b);
        CHECK(b.cur.load() == 1351 * 1920 + 400);
    }
    CHECK(b.cur.load() == 0);
    CHECK(b.peak.load() == 1351 * 1920 + 400);
    bool threw = false;
    try { nr::Mask bad(-1, 3); } catch (const std::length_error&) { threw = true; }
    CHECK(threw);
    threw = false;
    try { nr::Mask bad(65536, 65536); } catch (const std::length_error&) { threw = true; }
    CHECK(threw);
    nr::Mask empty(0, 5);
    CHECK(empty.data.size() == 0 && empty.data.data() == nullptr);

    const uint8_t cross[] = {0, 1, 0, 1, 1, 1, 0, 1, 0};
    nr::Kernel k(3, 3, cross);
    CHECK(k.ax == 1 && k.ay == 1);
    CHECK(k.runStart[0] == 1 && k.runEnd[0] == 2 && k.runStart[1] == 0 && k.runEnd[1] == 3);
    const uint8_t holes[] = {1, 0, 1, 0, 0, 0};
    nr::Kernel k2(3, 2, holes);
    CHECK(k2.runStart[0] == 0 && k2.runEnd[0] == 3 && k2.runStart[1] == 0 && k2.runEnd[1] == 0);

    // 列打包：寬度 63／64／65／127／128／129 的來回，末字超出寬度的位元一律 0
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129, 200}) {
        nr::Mask m(w, 3);
        uint64_t s = 0x1234567 + static_cast<uint64_t>(w);
        for (size_t i = 0; i < m.data.size(); i++) { s = s * 6364136223846793005ULL + 1442695040888963407ULL; m.data[i] = static_cast<uint8_t>((s >> 62) & 1); }
        const nr::PackedBits p = nr::packBits(m);
        CHECK(p.nw == (w + 63) / 64);
        if (w % 64 != 0) {
            const uint64_t tailMask = ~((uint64_t(1) << (w % 64)) - 1);
            bool clean = true;
            for (int32_t y = 0; y < 3; y++) if (p.words[static_cast<size_t>(y) * p.nw + p.nw - 1] & tailMask) clean = false;
            CHECK(clean);
        }
        const nr::Mask back = nr::unpackBits(p);
        CHECK(std::memcmp(back.data.data(), m.data.data(), m.data.size()) == 0);
    }
}

void testPage() {
    CHECK(nr_abi() == ((NR_ABI_VERSION << 16) | 4));
    nr_page* p = nullptr;
    CHECK(nr_create(0, 1, &p) == NR_E_ARG && p == nullptr);
    CHECK(nr_create(65536, 65536, &p) == NR_E_ARG);
    CHECK(nr_create(3, 2, nullptr) == NR_E_ARG);
    CHECK(nr_create(3, 2, &p) == NR_OK && p != nullptr);
    CHECK(nr_width(p) == 3 && nr_height(p) == 2);
    const int32_t px[] = {static_cast<int32_t>(0xFF102030U), static_cast<int32_t>(0x00FFFFFFU), 0, -1, 0x7F808080, 0x12345678};
    int32_t out[6];
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 1, out, 3) == NR_E_STATE);
    CHECK(nr_put_rows(p, 1, 2, px, 3) == NR_E_ARG);
    CHECK(nr_put_rows(p, 0, 2, px, 2) == NR_E_ARG);              // stride < w
    CHECK(nr_put_rows(p, 0, 1, px, 3) == NR_OK);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 2, out, 3) == NR_E_STATE);
    CHECK(nr_put_rows(p, 1, 1, px + 3, 3) == NR_OK);
    int32_t g[6], c[6], argb[6];
    nr::argbToGrayChroma(px, 6, g, c);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY, 0, 2, out, 3) == NR_OK && std::memcmp(out, g, sizeof g) == 0);
    CHECK(nr_get_rows(p, NR_PLANE_CHROMA, 0, 2, out, 3) == NR_OK && std::memcmp(out, c, sizeof c) == 0);
    nr::grayToArgb(g, 6, argb);
    CHECK(nr_get_rows(p, NR_PLANE_GRAY_ARGB, 0, 2, out, 3) == NR_OK && std::memcmp(out, argb, sizeof argb) == 0);
    CHECK(nr_get_rows(p, 7, 0, 1, out, 3) == NR_E_ARG);
    CHECK(g[0] == (0x10 * 299 + 0x20 * 587 + 0x30 * 114 + 500) / 1000 && c[0] == 0x20);
    CHECK(g[3] == 255 && c[3] == 0);
    CHECK(nr_peak_bytes(p) == 3 * 2 * 4 * 2 + 2);
    CHECK(nr_is_cancelled(p) == 0);
    nr_cancel(p);
    CHECK(nr_is_cancelled(p) == 1);
    nr_free(p);
    nr_free(nullptr);
    int32_t clamp[] = {-5, 0, 255, 256, 128};
    int32_t ca[5];
    nr::grayToArgb(clamp, 5, ca);
    CHECK(ca[0] == static_cast<int32_t>(0xFF000000U) && ca[3] == -1 && ca[4] == static_cast<int32_t>(0xFF808080U));
}

/** 多頁同時（不同執行緒各自一頁）＋另一條執行緒一直取消：結果必須正確、TSan 不得報資料競爭。 */
void testConcurrency() {
    const int T = 8;
    const int32_t W = 257, H = 64;
    std::vector<nr_page*> pages(T, nullptr);
    for (int t = 0; t < T; t++) CHECK(nr_create(W, H, &pages[t]) == NR_OK);
    std::atomic<bool> stop{false};
    std::atomic<int> bad{0};
    std::thread canceller([&] {
        while (!stop.load()) for (int t = 0; t < T; t++) { nr_cancel(pages[t]); (void)nr_peak_bytes(pages[t]); }
    });
    std::vector<std::thread> workers;
    for (int t = 0; t < T; t++) {
        workers.emplace_back([&, t] {
            const std::vector<int32_t> px = nrt::genPixels(static_cast<int64_t>(W) * H, static_cast<uint64_t>(t));
            std::vector<int32_t> g(px.size()), c(px.size()), out(px.size());
            nr::argbToGrayChroma(px.data(), static_cast<int64_t>(px.size()), g.data(), c.data());
            for (int32_t y = 0; y < H; y += 16) nr_put_rows(pages[t], y, 16, px.data() + static_cast<size_t>(y) * W, W);
            if (nr_get_rows(pages[t], NR_PLANE_GRAY, 0, H, out.data(), W) != NR_OK || out != g) bad++;
            if (nr_get_rows(pages[t], NR_PLANE_CHROMA, 0, H, out.data(), W) != NR_OK || out != c) bad++;
        });
    }
    for (auto& w : workers) w.join();
    stop = true;
    canceller.join();
    CHECK(bad.load() == 0);
    for (int t = 0; t < T; t++) { CHECK(nr_is_cancelled(pages[t]) == 1); nr_free(pages[t]); }
}

/** 參數（params_gen.h）：預設值攤平來回、逐欄存取與槽位一致、拒收不合法的攤平、與 nr.h 的規則版本／schema 握手。 */
void testParams() {
    namespace P = nr::params;
    CHECK(P::kFieldCount == 379);
    CHECK(nr_params_schema() == static_cast<int64_t>(P::kSchemaHash));
    CHECK(P::kRulesVersion == NR_RULES_VERSION && P::kRulesDigests[P::kRulesDigestCount - 1].version == NR_RULES_VERSION);
    for (int32_t k = 0; k < P::kRulesDigestCount; k++) CHECK(P::kRulesDigests[k].version == k + 1 && std::strlen(P::kRulesDigests[k].sha256) == 64);
    std::vector<int32_t> ints(P::kIntSlots), ints2(P::kIntSlots);
    std::vector<double> dbls(P::kDoubleSlots), dbls2(P::kDoubleSlots);
    const P::NightReadParams def{};
    P::toFlat(def, ints.data(), dbls.data());
    P::NightReadParams back;
    CHECK(P::fromFlat(ints.data(), P::kIntSlots, dbls.data(), P::kDoubleSlots, &back));
    P::toFlat(back, ints2.data(), dbls2.data());
    CHECK(ints == ints2 && std::memcmp(dbls.data(), dbls2.data(), dbls.size() * sizeof(double)) == 0);
    CHECK(def.bg == 16 && def.glowStrength == 55.0f && def.stickerMode == P::StickerMode::ALL && !def.bubbleSeal.cutGeo.present);
    CHECK(def.obj.fx.cosJoin == 0.9945218953682733 && def.sep.peakMax == 800 && !def.more.enabled && def.ring.raySigma == 2.0);
    // 每個欄位：setField 設一個跟欄位序號有關的值 → toFlat 的槽位上要看得到、getField 讀回相同
    P::NightReadParams p;
    int bad = 0;
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::Value v{0, 0.0, true};
        switch (f.kind) {
            case P::Kind::Int: v.i = 100000 + k; break;
            case P::Kind::Double: v.d = 1000.25 + k; break;
            case P::Kind::Float: v.d = 0.5 + k; break;
            case P::Kind::Bool: v.i = k & 1; break;
            case P::Kind::OptInt: v.i = 200000 + k; break;
            case P::Kind::Enum: v.i = k % P::kStickerModeCount; break;
        }
        if (!P::setField(&p, k, v)) bad++;
        P::Value r{};
        if (!P::getField(p, k, &r) || r.i != v.i || dbits(r.d) != dbits(v.d) || r.present != v.present) bad++;
    }
    CHECK(bad == 0);
    P::toFlat(p, ints.data(), dbls.data());
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::Value r{};
        P::getField(p, k, &r);
        if (f.doubleSlot >= 0) {
            if (f.intSlot != -1 || dbits(dbls[static_cast<size_t>(f.doubleSlot)]) != dbits(r.d)) bad++;
        } else if (f.kind == P::Kind::OptInt) {
            if (ints[static_cast<size_t>(f.intSlot)] != 1 || ints[static_cast<size_t>(f.intSlot) + 1] != r.i) bad++;
        } else if (ints[static_cast<size_t>(f.intSlot)] != r.i) {
            bad++;
        }
    }
    CHECK(bad == 0);
    // 不合法的值：Bool 2、enum 超出範圍、Float 轉不回、Int 超出 int32、OptInt 沒有值卻帶值
    for (int32_t k = 0; k < P::kFieldCount; k++) {
        const P::Field& f = P::kFields[k];
        P::NightReadParams q;
        if (f.kind == P::Kind::Bool) CHECK(!P::setField(&q, k, P::Value{2, 0.0, true}));
        if (f.kind == P::Kind::Enum) CHECK(!P::setField(&q, k, P::Value{P::kStickerModeCount, 0.0, true}));
        if (f.kind == P::Kind::Float) CHECK(!P::setField(&q, k, P::Value{0, 0.1, true}) && !P::setField(&q, k, P::Value{0, 1e300, true}));
        if (f.kind == P::Kind::Int) CHECK(!P::setField(&q, k, P::Value{int64_t(INT32_MAX) + 1, 0.0, true}));
        if (f.kind == P::Kind::OptInt) CHECK(!P::setField(&q, k, P::Value{5, 0.0, false}) && P::setField(&q, k, P::Value{0, 0.0, false}));
    }
    P::toFlat(def, ints.data(), dbls.data());
    CHECK(!P::fromFlat(ints.data(), P::kIntSlots - 1, dbls.data(), P::kDoubleSlots, &back));
    CHECK(!P::fromFlat(ints.data(), P::kIntSlots, dbls.data(), P::kDoubleSlots + 1, &back));
    CHECK(!P::fromFlat(nullptr, P::kIntSlots, dbls.data(), P::kDoubleSlots, &back));
}

// ── 第一批影像原語：對照逐像素的參考算法（逐位元對 Kotlin 的比對在 CppPrimsParityTest、nr_dump_check）─────────────

uint64_t lcg(uint64_t& s) { s = s * 6364136223846793005ULL + 1442695040888963407ULL; return s >> 33; }

nr::Mask randomMask(int32_t w, int32_t h, uint64_t seed, uint32_t per1024) {
    nr::Mask m(w, h);
    for (size_t i = 0; i < m.data.size(); i++) m.data[i] = (lcg(seed) & 1023) < per1024 ? 1 : 0;
    return m;
}

/** 幾種核：全滿、十字、整段在錨點左／右側、遠離錨點、有空列、中間有洞、不對稱。 */
std::vector<nr::Kernel> testKernels() {
    std::vector<nr::Kernel> out;
    auto add = [&](int32_t w, int32_t h, auto f) {
        std::vector<uint8_t> d(static_cast<size_t>(w) * h);
        for (int32_t y = 0; y < h; y++) for (int32_t x = 0; x < w; x++) d[static_cast<size_t>(y) * w + x] = f(x, y) ? 1 : 0;
        out.emplace_back(w, h, d.data());
    };
    for (int32_t n : {1, 3, 9, 15}) add(n, n, [](int32_t, int32_t) { return true; });
    add(65, 1, [](int32_t, int32_t) { return true; });
    add(1, 9, [](int32_t, int32_t) { return true; });
    add(129, 3, [](int32_t, int32_t) { return true; });
    add(5, 5, [](int32_t x, int32_t y) { return x == 2 || y == 2; });
    add(9, 3, [](int32_t x, int32_t) { return x >= 6; });
    add(9, 3, [](int32_t x, int32_t) { return x <= 2; });
    add(141, 1, [](int32_t x, int32_t) { return x >= 135; });
    add(141, 1, [](int32_t x, int32_t) { return x <= 5; });
    add(5, 5, [](int32_t, int32_t) { return false; });
    add(5, 5, [](int32_t, int32_t y) { return y == 2; });
    add(7, 5, [](int32_t x, int32_t y) { return y == 0 ? x <= 2 : (y == 4 ? x >= 3 : y == 2); });
    add(7, 5, [](int32_t x, int32_t y) { return y == 0 ? x <= 2 : (y == 4 ? x >= 4 : y == 2); });   // 上下同寬、偏移不同（不能併成一趟）
    add(4, 6, [](int32_t x, int32_t y) { return (x + y) % 3 != 0; });
    return out;
}

void testPrimsDilate() {
    const std::vector<nr::Kernel> ks = testKernels();
    uint64_t seed = 1;
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129}) for (int32_t h : {1, 3, 7}) for (uint32_t d : {0U, 30U, 512U, 1024U}) {
        const nr::Mask m = randomMask(w, h, seed++, d);
        for (const nr::Kernel& k : ks) {
            const nr::Mask got = nr::prims::dilatePacked(m, k);
            bool ok = got.w == w && got.h == h;
            for (int32_t y = 0; ok && y < h; y++) for (int32_t x = 0; x < w; x++) {
                uint8_t v = 0;
                for (int32_t ky = 0; !v && ky < k.h; ky++) {
                    const int32_t sy = y - (ky - k.ay);
                    if (sy < 0 || sy >= h) continue;
                    for (int32_t o = k.runStart[ky] - k.ax; o <= k.runEnd[ky] - 1 - k.ax; o++) {
                        const int32_t sx = x + o;
                        if (sx >= 0 && sx < w && m.data[static_cast<size_t>(sy) * w + sx]) { v = 1; break; }
                    }
                }
                if (got.data[static_cast<size_t>(y) * w + x] != v) { ok = false; break; }
            }
            CHECK(ok);
        }
    }
    // 寬 0：核有整段在錨點左側的列時 Kotlin 丟例外，這裡也丟
    const nr::Mask empty(0, 3);
    bool threw = false;
    try { nr::prims::dilatePacked(empty, ks[9]); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);
    CHECK(nr::prims::dilatePacked(empty, ks[8]).h == 3);
}

void testPrimsCc() {
    uint64_t seed = 77;
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129}) for (int32_t h : {1, 3, 7, 65}) for (uint32_t d : {0U, 60U, 512U, 900U, 1024U}) for (int32_t conn : {4, 8}) {
        const nr::Mask m = randomMask(w, h, seed++, d);
        const nr::CC c = nr::prims::ccStats(m, conn);
        const nr::CcRuns r = nr::prims::ccRuns(m, conn);
        CHECK(r.n == c.n);
        // 參考：掃描序首見、BFS（顯式佇列）
        const size_t n = m.data.size();
        std::vector<int32_t> lab(n, 0), q;
        int32_t next = 1;
        std::vector<int32_t> left{0}, top{0}, right{-1}, bottom{-1}, area{0};
        for (size_t i = 0; i < n; i++) {
            if (!m.data[i] || lab[i] != 0) continue;
            const int32_t l = next++;
            left.push_back(INT32_MAX); top.push_back(INT32_MAX); right.push_back(INT32_MIN); bottom.push_back(INT32_MIN); area.push_back(0);
            lab[i] = l;
            q.assign(1, static_cast<int32_t>(i));
            while (!q.empty()) {
                const int32_t p = q.back();
                q.pop_back();
                const int32_t x = p % w, y = p / w;
                area[l]++;
                if (x < left[l]) left[l] = x;
                if (x > right[l]) right[l] = x;
                if (y < top[l]) top[l] = y;
                if (y > bottom[l]) bottom[l] = y;
                for (int32_t dy = -1; dy <= 1; dy++) for (int32_t dx = -1; dx <= 1; dx++) {
                    if ((dx == 0 && dy == 0) || (conn == 4 && dx != 0 && dy != 0)) continue;
                    const int32_t nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    const size_t ni = static_cast<size_t>(ny) * w + nx;
                    if (m.data[ni] && lab[ni] == 0) { lab[ni] = l; q.push_back(static_cast<int32_t>(ni)); }
                }
            }
        }
        CHECK(c.n == next);
        bool ok = true;
        for (size_t i = 0; i < n; i++) if (c.labels[i] != lab[i]) ok = false;
        for (int32_t l = 1; ok && l < next; l++) {
            if (c.left[l] != left[l] || c.top[l] != top[l] || c.width[l] != right[l] - left[l] + 1 || c.height[l] != bottom[l] - top[l] + 1 || c.area[l] != area[l]) ok = false;
        }
        CHECK(ok);
        // 背景（0 號）：面積、外接框
        int64_t bg = 0;
        int32_t bl = INT32_MAX, bt = INT32_MAX, br = INT32_MIN, bb = INT32_MIN;
        for (size_t i = 0; i < n; i++) {
            if (m.data[i]) continue;
            const int32_t x = static_cast<int32_t>(i) % w, y = static_cast<int32_t>(i) / w;
            bg++;
            if (x < bl) bl = x;
            if (x > br) br = x;
            if (y < bt) bt = y;
            if (y > bb) bb = y;
        }
        CHECK(c.area[0] == bg);
        CHECK(r.fg == static_cast<int64_t>(n) - bg);
        CHECK(r.bgLeft == bl && r.bgTop == bt && r.bgRight == br && r.bgBottom == bb);
        // 游程容量與 Kotlin 相同：max(64, 2h) 起倍增
        size_t cap = static_cast<size_t>(h * 2 > 64 ? h * 2 : 64);
        while (cap < static_cast<size_t>(r.rowFirst[h])) cap *= 2;
        CHECK(r.rs.size() == cap && r.re.size() == cap && r.lab.size() == static_cast<size_t>(r.rowFirst[h]));
    }
    // 0 寬／0 高
    const nr::Mask e0(0, 0), e1(0, 5), e2(5, 0);
    for (const nr::Mask* m : {&e0, &e1, &e2}) {
        const nr::CC c = nr::prims::ccStats(*m, 8);
        CHECK(c.n == 1 && c.area[0] == 0 && c.left[0] == 0 && c.top[0] == 0 && c.width[0] == 0 && c.height[0] == 0);
    }
}

void testPrimsDistance() {
    uint64_t seed = 5;
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129}) for (int32_t h : {1, 3, 7, 65}) for (uint32_t d : {40U, 512U, 1000U}) {
        const nr::Mask m = randomMask(w, h, seed++, d);
        const nr::ImgF32 f = nr::prims::distanceL2(m);
        bool anyBg = false;
        for (size_t i = 0; i < m.data.size(); i++) if (!m.data[i]) { anyBg = true; break; }
        bool ok = true;
        for (int32_t y = 0; y < h; y++) for (int32_t x = 0; x < w; x++) {
            const float got = f.data[static_cast<size_t>(y) * w + x];
            if (!m.data[static_cast<size_t>(y) * w + x]) { if (got != 0.0f) ok = false; continue; }
            if (!anyBg) { if (got < 9e9f) ok = false; continue; }
            int64_t best = INT64_MAX;
            for (int32_t yy = 0; yy < h; yy++) for (int32_t xx = 0; xx < w; xx++) {
                if (m.data[static_cast<size_t>(yy) * w + xx]) continue;
                const int64_t dd = static_cast<int64_t>(x - xx) * (x - xx) + static_cast<int64_t>(y - yy) * (y - yy);
                if (dd < best) best = dd;
            }
            if (got != __builtin_sqrtf(static_cast<float>(best))) ok = false;
        }
        CHECK(ok);
    }
    // float 的 sqrt＝double 的 sqrt 再轉 float（Kotlin 的寫法）：邊界值加隨機位元型樣
    uint64_t s = 99;
    int bad = 0;
    for (int i = 0; i < 4000000; i++) {
        const uint32_t b = i < 2048 ? static_cast<uint32_t>(i) : static_cast<uint32_t>(lcg(s)) & 0x7fffffffU;
        const float x = ffrom(b);
        const float viaDouble = static_cast<float>(sqrt(static_cast<double>(x)));
        if (fbits(__builtin_sqrtf(x)) != fbits(viaDouble) && !(x != x)) bad++;
    }
    CHECK(bad == 0);
}

void testPrimsGauss() {
    uint64_t seed = 11;
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129}) for (int32_t h : {1, 3, 7, 65}) for (double sigma : {0.3, 1.5, 2.0, 5.0}) {
        nr::ImgI32 g(w, h);
        for (size_t i = 0; i < g.data.size(); i++) g.data[i] = static_cast<int32_t>(lcg(seed) & 255);
        const nr::ImgI32 got = nr::prims::gaussQ16(g, sigma);
        const std::vector<int32_t> k = nr::tables::gaussW(sigma);
        const int32_t c = static_cast<int32_t>(k.size()) / 2;
        auto refl = [](int32_t i, int32_t n) {
            if (n == 1) return 0;
            while (i < 0 || i >= n) { if (i < 0) i = -i; if (i >= n) i = 2 * (n - 1) - i; }
            return i;
        };
        bool ok = true;
        for (int32_t y = 0; ok && y < h; y++) for (int32_t x = 0; x < w; x++) {
            int64_t acc = 0;
            for (int32_t j = -c; j <= c; j++) {
                const int32_t sy = refl(y + j, h);
                int32_t row = 0;
                for (int32_t i = -c; i <= c; i++) row += k[i + c] * g.data[static_cast<size_t>(sy) * w + refl(x + i, w)];
                acc += static_cast<int64_t>(k[j + c]) * row;
            }
            if (got.data[static_cast<size_t>(y) * w + x] != static_cast<int32_t>((acc + 32768) >> 16)) { ok = false; break; }
        }
        CHECK(ok);
    }
    nr::ImgI32 zero(0, 3);
    bool threw = false;
    try { nr::prims::gaussQ16(zero, 2.0); } catch (const std::invalid_argument&) { threw = true; }
    CHECK(threw);
}

void testPrimsMorph() {
    const std::vector<nr::Kernel> ks = testKernels();
    uint64_t seed = 21;
    for (int32_t w : {1, 63, 64, 65, 127, 128, 129}) for (int32_t h : {1, 3, 7, 16}) {
        nr::ImgI32 g(w, h);
        for (size_t i = 0; i < g.data.size(); i++) g.data[i] = static_cast<int32_t>(lcg(seed)) - (1 << 30);
        for (const nr::Kernel& k : ks) for (bool mx : {true, false}) {
            const nr::ImgI32 got = nr::prims::morphGrayGather(g, k, mx);
            bool ok = true;
            for (int32_t y = 0; ok && y < h; y++) for (int32_t x = 0; x < w; x++) {
                int32_t v = mx ? INT32_MIN : INT32_MAX;
                for (int32_t ky = 0; ky < k.h; ky++) {
                    if (k.runEnd[ky] <= k.runStart[ky]) continue;
                    const int32_t sy = y + (ky - k.ay);
                    if (sy < 0 || sy >= h) continue;
                    for (int32_t o = k.runStart[ky] - k.ax; o < k.runEnd[ky] - k.ax; o++) {
                        const int32_t sx = x + o;
                        if (sx < 0 || sx >= w) continue;
                        const int32_t u = g.data[static_cast<size_t>(sy) * w + sx];
                        if (mx ? u > v : u < v) v = u;
                    }
                }
                if (got.data[static_cast<size_t>(y) * w + x] != v) { ok = false; break; }
            }
            CHECK(ok);
        }
    }
}

/** 真頁尺寸的合成圖：大塊、細線、雜點（這支測試跑在 256 KB 堆疊的執行緒上；原語不能在堆疊放大陣列）。 */
nr::ImgI32 fakePage(int32_t w, int32_t h, uint64_t seed) {
    nr::ImgI32 g(w, h);
    for (int32_t y = 0; y < h; y++) for (int32_t x = 0; x < w; x++) {
        int32_t v = 240;
        if ((x + y) % 97 < 3) v = 20;
        if (((x / 200) + (y / 300)) % 3 == 0 && (x % 5 == 0)) v = 120;
        if ((lcg(seed) & 255) == 0) v = 0;
        g.data[static_cast<size_t>(y) * w + x] = v;
    }
    return g;
}

nr::Mask thresh(const nr::ImgI32& g, int32_t th) {
    nr::Mask m(g.w, g.h);
    for (size_t i = 0; i < m.data.size(); i++) m.data[i] = g.data[i] >= th ? 1 : 0;
    return m;
}

void testPrimsBudgetAndStack() {
    const int32_t W = 1351, H = 1920;
    const nr::ImgI32 g = fakePage(W, H, 3);
    const nr::Mask m = thresh(g, 200);
    std::vector<uint8_t> full(11 * 11, 1);
    const nr::Kernel k(11, 11, full.data());
    const int64_t px = static_cast<int64_t>(W) * H;
    {
        nr::Budget b;
        { const nr::Mask out = nr::prims::dilatePacked(m, k, &b); CHECK(b.cur.load() == px); CHECK(b.peak.load() > px); }
        CHECK(b.cur.load() == 0);
    }
    {
        nr::Budget b;
        { const nr::CC c = nr::prims::ccStats(m, 8, &b); CHECK(b.cur.load() == px * 4 + 5 * 4 * static_cast<int64_t>(c.n)); }
        CHECK(b.cur.load() == 0);
    }
    {
        nr::Budget b;
        { const nr::CcRuns r = nr::prims::ccRuns(m, 8, &b); CHECK(b.cur.load() > 0 && b.peak.load() >= b.cur.load()); }
        CHECK(b.cur.load() == 0);
    }
    {
        nr::Budget b;
        { const nr::ImgF32 f = nr::prims::distanceL2(m, &b); CHECK(b.cur.load() == px * 4); CHECK(b.peak.load() > px * 4); }
        CHECK(b.cur.load() == 0);
    }
    {
        nr::Budget b;
        { const nr::ImgI32 o = nr::prims::gaussQ16(g, 5.0, &b); CHECK(b.cur.load() == px * 4); CHECK(b.peak.load() > px * 4); }
        CHECK(b.cur.load() == 0);
    }
    {
        nr::Budget b;
        { const nr::ImgI32 o = nr::prims::morphGrayGather(g, k, true, &b); CHECK(b.cur.load() == px * 4); CHECK(b.peak.load() > px * 4); }
        CHECK(b.cur.load() == 0);
    }
}

/** 多條執行緒同時用同一份唯讀輸入（TSan 建置抓資料競爭）：結果要跟依序版相同。 */
void testPrimsConcurrent() {
    const int32_t W = 257, H = 96;
    const nr::ImgI32 g = fakePage(W, H, 9);
    const nr::Mask m = thresh(g, 200);
    std::vector<uint8_t> full(9 * 9, 1);
    const nr::Kernel k(9, 9, full.data());
    const nr::Mask wd = nr::prims::dilatePacked(m, k);
    const nr::CC wc = nr::prims::ccStats(m, 8);
    const nr::ImgF32 wf = nr::prims::distanceL2(m);
    const nr::ImgI32 wg = nr::prims::gaussQ16(g, 2.0);
    const nr::ImgI32 wm = nr::prims::morphGrayGather(g, k, true);
    std::atomic<int> bad{0};
    std::vector<std::thread> ts;
    for (int t = 0; t < 8; t++) {
        ts.emplace_back([&] {
            for (int i = 0; i < 3; i++) {
                const nr::Mask d = nr::prims::dilatePacked(m, k);
                const nr::CC c = nr::prims::ccStats(m, 8);
                const nr::ImgF32 f = nr::prims::distanceL2(m);
                const nr::ImgI32 o = nr::prims::gaussQ16(g, 2.0);
                const nr::ImgI32 mm = nr::prims::morphGrayGather(g, k, true);
                if (std::memcmp(d.data.data(), wd.data.data(), wd.data.size()) != 0 || c.n != wc.n ||
                    std::memcmp(c.labels.data(), wc.labels.data(), wc.labels.size() * 4) != 0 ||
                    std::memcmp(f.data.data(), wf.data.data(), wf.data.size() * 4) != 0 ||
                    std::memcmp(o.data.data(), wg.data.data(), wg.data.size() * 4) != 0 ||
                    std::memcmp(mm.data.data(), wm.data.data(), wm.data.size() * 4) != 0) bad++;
            }
        });
    }
    for (auto& t : ts) t.join();
    CHECK(bad.load() == 0);
}

void* runAll(void*) {
    testRounding();
    testBigDecimal();
    testIntegers();
    testCompare();
    testFdlibm();
    testArrays();
    testTables();
    testTypes();
    testPage();
    testParams();
    testConcurrency();
    testPrimsDilate();
    testPrimsCc();
    testPrimsDistance();
    testPrimsGauss();
    testPrimsMorph();
    testPrimsBudgetAndStack();
    testPrimsConcurrent();
    return nullptr;
}

}  // namespace

int main() {
    // 256 KB 堆疊的執行緒（PTHREAD_STACK_MIN 以上）
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    if (pthread_attr_setstacksize(&attr, 256 * 1024) != 0) { std::printf("setstacksize failed\n"); return 2; }
    pthread_t th;
    if (pthread_create(&th, &attr, runAll, nullptr) != 0) { std::printf("pthread_create failed\n"); return 2; }
    pthread_join(th, nullptr);
    pthread_attr_destroy(&attr);
    std::printf("nr_core_tests: %d checks, %d failed\n", g_checks, g_fail);
    return g_fail == 0 ? 0 : 1;
}
