// 數值核心的測試案例：產生器、C++ 端求值、雜湊。
//
// 同一份產生器給三個地方用，所以三邊看到的輸入一定相同：
//   1. JUnit（CppJmathParityTest，經 test_jni.cpp）：取輸入 → Kotlin／Java 算參考答案 → C++ 求值 → 逐位元比。
//   2. JUnit 開 -Dnightread.goldenOut=<dir> 時，把 Java 答案的分塊雜湊寫成 golden 檔。
//   3. golden_check（主機或 qemu-aarch64 上的 NDK arm64 靜態檔）：用同一個產生器重產輸入（先比輸入雜湊），C++ 求值，比 golden。
// 產生器只用整數與位元運算（少數幾處 IEEE 精確的 double 運算），x86_64 與 arm64 產生一模一樣的輸入。
// 第 i 個案例只由（函式名稱、i）決定，可以任意分塊、平行。
#pragma once

#include <cstdint>
#include <vector>

namespace nrt {

enum InKind : uint8_t { KD, KF, KI, KL, KS, KSH };   // double、float、int、long、scale（0..18）、位移量（int）
enum OutKind : uint8_t { OD, OF, OI, OL };            // double、float、int（符號延伸成 64 位）、long

struct FnSpec {
    const char* name;
    int nIn;
    InKind in[3];
    int nOut;
    OutKind out;
    int hint;
};

int fnCount();
const FnSpec& fnSpec(int fn);
int fnIndex(const char* name);

/**
 * 第 start..start+n−1 個案例的引數，每案例 nIn 個 64 位元字：double＝原始位元、float＝低 32 位元是位元（高位 0）、
 * int／scale／位移量＝符號延伸、long＝原值。
 */
void genScalar(int fn, int64_t start, int64_t n, uint64_t* args);

/**
 * C++ 求值。每案例寫 nOut＋1 個字：nOut 個輸出（編碼同上）＋狀態（0＝正常；1＝Kotlin 在這個輸入會丟例外，輸出全 0）。
 */
void evalScalar(int fn, const uint64_t* args, int64_t n, uint64_t* out);

/** 比對用的正規化：double／float 的 NaN 一律收成標準 NaN（Java 規格不定義運算結果的 NaN 位元）。 */
uint64_t canon(OutKind k, uint64_t v);

/** 逐字 FNV-1a 變體：h = (h ^ v) · 0x100000001b3。Kotlin 端同式。 */
constexpr uint64_t FNV_INIT = 0xcbf29ce484222325ULL;
inline uint64_t fnv(uint64_t h, uint64_t v) { return (h ^ v) * 0x100000001b3ULL; }

// ── 陣列案例 ────────────────────────────────────────────────────────────

/** npSum（chunked＝false）／npSumChunked（true）的第 idx 個輸入陣列。 */
std::vector<double> genSumArray(bool chunked, int64_t idx);
/** npArgsort 的第 idx 個輸入陣列（含大量同值、已排序、反序、McIlroy 對手產生的逼 heapsort 輸入）。 */
std::vector<int32_t> genArgsortArray(int64_t idx);
/** McIlroy「antiqsort」對手：對 npArgsortT 造出讓三數取中一直切壞的輸入（值 0..n−1）。heapCalls 回報對手跑的那次進了幾次 heapsort。 */
std::vector<int32_t> antiqsort(int32_t n, int* heapCalls = nullptr);
/** ARGB 測試像素：前 648 個是各通道 {0,1,127,128,254,255} 的組合 × alpha {0,128,255}，其餘隨機。 */
std::vector<int32_t> genPixels(int64_t n, uint64_t seed);

}  // namespace nrt
