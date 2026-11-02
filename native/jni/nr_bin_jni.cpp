// 第 3a 階影像原語的 JNI（對應 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/NightNativeBin.kt）。只給差分測試、錄放與手機探針用：
// 之後的產品路徑是整頁的 handle（nr.h），不會每個原語都過一次 JNI。規則同 nr_prims_jni.cpp：
//   - 輸入用 Get*ArrayRegion 複製進原生緩衝、輸出用 Set*ArrayRegion 複製回去；不持有 critical。
//   - stats（可為 null，長度 ≥ 2）：stats[0]＝原語本身的耗時（奈秒，不含 JNI 的複製進出）、stats[1]＝原生記憶體峰值（位元組，含輸出）。
//   - 陣列長度或尺寸不合丟 IllegalArgumentException；原語本身丟的 C++ 例外（Kotlin 也會丟的輸入）→ IllegalStateException、
//     配置失敗 → OutOfMemoryError（nr_jni_util.h 的 guarded）。
// 同形狀的原語共用一個進入點，用運算代碼分（代碼與 NightNativeBin.kt 的常數同步）。
#include <jni.h>
#include <time.h>

#include <vector>

#include "../core/bits.h"
#include "../core/prims.h"
#include "../core/types.h"
#include "nr_jni_util.h"

using nr::jni::guarded;
using nr::jni::throwJava;

#define BIN(ret, name) NR_JNI_EXPORT ret JNICALL Java_li_joye_yakuyomi_nightread_NightNativeBin_##name

namespace {

// 運算代碼（NightNativeBin.kt 的 OP_*）
enum : jint {
    OP_DILATE = 1, OP_ERODE = 2, OP_OPEN = 3, OP_CLOSE = 4, OP_ERODE_PACKED = 5, OP_OPEN_PACKED = 6, OP_CLOSE_PACKED = 7,
    OP_DILATE_RECT_SEP = 8, OP_CLOSE_RECT_SEP = 9, OP_HOLES = 10, OP_MEDIAN = 11, OP_RESIZE_NEAREST = 12,
    OP_DISTANCE_SQ = 20, OP_CHAMFER5_PADDED = 21,
    OP_PACK = 30, OP_PACK_NOT = 31, OP_RING_PACK = 32,
    OP_UNPACK = 40, OP_UNPACK_NOT = 41,
    OP_NOT_IN_PLACE = 50, OP_ERODE_BITS = 51,
    OP_RESIZE_AREA = 60, OP_RESIZE_BILINEAR = 61, OP_RESIZE_AREA_OF = 62,
    OP_ELLIPSE = 70, OP_RECT = 71,
};

inline int64_t nowNs() {
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<int64_t>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

void badArg(JNIEnv* env, const char* msg) { throwJava(env, "java/lang/IllegalArgumentException", msg); }

bool dimsOk(JNIEnv* env, jarray a, jint w, jint h) {
    if (a == nullptr || w < 0 || h < 0 ||
        static_cast<long long>(w) * h != static_cast<long long>(env->GetArrayLength(a))) {
        badArg(env, "nightread: array size does not match w*h");
        return false;
    }
    return true;
}

/** 列打包陣列（Cv.packBits 格式）的長度要是 ((w+63)/64)·h。 */
bool packedOk(JNIEnv* env, jlongArray a, jint w, jint h) {
    if (a == nullptr || w < 0 || h < 0 ||
        ((static_cast<long long>(w) + 63) >> 6) * h != static_cast<long long>(env->GetArrayLength(a))) {
        badArg(env, "nightread: packed array size does not match ((w+63)/64)*h");
        return false;
    }
    return true;
}

nr::Mask maskIn(JNIEnv* env, jbooleanArray a, jint w, jint h) {
    nr::Mask m(w, h);
    if (m.data.size() > 0) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(m.data.size()), reinterpret_cast<jboolean*>(m.data.data()));
    return m;
}

nr::PackedBits packedIn(JNIEnv* env, jlongArray a, jint w, jint h) {
    nr::PackedBits p(w, h);
    if (p.words.size() > 0) env->GetLongArrayRegion(a, 0, static_cast<jsize>(p.words.size()), reinterpret_cast<jlong*>(p.words.data()));
    return p;
}

nr::Kernel kernelIn(JNIEnv* env, jbooleanArray a, jint kw, jint kh) {
    std::vector<uint8_t> d(static_cast<size_t>(kw) * static_cast<size_t>(kh));
    if (!d.empty()) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(d.size()), reinterpret_cast<jboolean*>(d.data()));
    return nr::Kernel(kw, kh, d.data());
}

jbooleanArray boolsOut(JNIEnv* env, const uint8_t* v, size_t n) {
    jbooleanArray a = env->NewBooleanArray(static_cast<jsize>(n));
    if (a != nullptr && n > 0) env->SetBooleanArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jboolean*>(v));
    return a;
}

jintArray intsOut(JNIEnv* env, const int32_t* v, size_t n) {
    jintArray a = env->NewIntArray(static_cast<jsize>(n));
    if (a != nullptr && n > 0) env->SetIntArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jint*>(v));
    return a;
}

jlongArray longsOut(JNIEnv* env, const uint64_t* v, size_t n) {
    jlongArray a = env->NewLongArray(static_cast<jsize>(n));
    if (a != nullptr && n > 0) env->SetLongArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jlong*>(v));
    return a;
}

jfloatArray floatsOut(JNIEnv* env, const float* v, size_t n) {
    jfloatArray a = env->NewFloatArray(static_cast<jsize>(n));
    if (a != nullptr && n > 0) env->SetFloatArrayRegion(a, 0, static_cast<jsize>(n), v);
    return a;
}

void statsOut(JNIEnv* env, jlongArray stats, int64_t ns, const nr::Budget& b) {
    if (stats == nullptr) return;
    if (env->GetArrayLength(stats) < 2) {
        badArg(env, "nightread: stats must have at least 2 elements");
        return;
    }
    const jlong v[2] = {ns, b.peak.load(std::memory_order_relaxed)};
    env->SetLongArrayRegion(stats, 0, 2, v);
}

/** args 至少要 n 個。 */
bool argsOk(JNIEnv* env, jintArray args, jsize n) {
    if (n == 0) return true;
    if (args == nullptr || env->GetArrayLength(args) < n) { badArg(env, "nightread: args too short"); return false; }
    return true;
}

std::vector<int32_t> argsIn(JNIEnv* env, jintArray args) {
    std::vector<int32_t> v(args == nullptr ? 0 : static_cast<size_t>(env->GetArrayLength(args)));
    if (!v.empty()) env->GetIntArrayRegion(args, 0, static_cast<jsize>(v.size()), reinterpret_cast<jint*>(v.data()));
    return v;
}

}  // namespace

/**
 * 遮罩 → 遮罩。args：DILATE／ERODE＝{iterations}、DILATE_RECT_SEP＝{kw, kh}、CLOSE_RECT_SEP＝{n}、MEDIAN＝{r}、RESIZE_NEAREST＝{nw, nh}。
 * 要核的（DILATE…CLOSE_PACKED）給 kd／kw／kh，其他 kd 可以是 null。輸出尺寸：RESIZE_NEAREST 是 nw×nh，其他同輸入。
 */
BIN(jbooleanArray, nMask)(JNIEnv* env, jclass, jint op, jbooleanArray m, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jintArray args, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        const bool needK = op >= OP_DILATE && op <= OP_CLOSE_PACKED;
        if (needK && !dimsOk(env, kd, kw, kh)) return nullptr;
        jsize nargs = 0;
        switch (op) {
            case OP_DILATE: case OP_ERODE: case OP_CLOSE_RECT_SEP: case OP_MEDIAN: nargs = 1; break;
            case OP_DILATE_RECT_SEP: case OP_RESIZE_NEAREST: nargs = 2; break;
            case OP_OPEN: case OP_CLOSE: case OP_ERODE_PACKED: case OP_OPEN_PACKED: case OP_CLOSE_PACKED: case OP_HOLES: break;
            default: badArg(env, "nightread: unknown mask op"); return nullptr;
        }
        if (!argsOk(env, args, nargs)) return nullptr;
        const std::vector<int32_t> a = argsIn(env, args);
        const nr::Mask in = maskIn(env, m, w, h);
        const nr::Kernel k = needK ? kernelIn(env, kd, kw, kh) : nr::Kernel();
        nr::Budget b;
        const int64_t t0 = nowNs();
        nr::Mask out;
        switch (op) {
            case OP_DILATE: out = nr::prims::dilate(in, k, a[0], &b); break;
            case OP_ERODE: out = nr::prims::erode(in, k, a[0], &b); break;
            case OP_OPEN: out = nr::prims::open(in, k, &b); break;
            case OP_CLOSE: out = nr::prims::close(in, k, &b); break;
            case OP_ERODE_PACKED: out = nr::prims::erodePacked(in, k, &b); break;
            case OP_OPEN_PACKED: out = nr::prims::openPacked(in, k, &b); break;
            case OP_CLOSE_PACKED: out = nr::prims::closePacked(in, k, &b); break;
            case OP_DILATE_RECT_SEP: out = nr::prims::dilateRectSep(in, a[0], a[1], &b); break;
            case OP_CLOSE_RECT_SEP: out = nr::prims::closeRectSep(in, a[0], &b); break;
            case OP_HOLES: out = nr::prims::holes(in, &b); break;
            case OP_MEDIAN: out = nr::prims::medianBlurMask(in, a[0], &b); break;
            default: out = nr::prims::resizeNearest(in, a[0], a[1], &b); break;
        }
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = boolsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** 遮罩 → Int 陣列：DISTANCE_SQ（w·h）、CHAMFER5_PADDED（(w+4)·(h+4)）。 */
BIN(jintArray, nMaskInts)(JNIEnv* env, jclass, jint op, jbooleanArray m, jint w, jint h, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        if (op != OP_DISTANCE_SQ && op != OP_CHAMFER5_PADDED) { badArg(env, "nightread: unknown op"); return nullptr; }
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = op == OP_DISTANCE_SQ ? nr::prims::distanceSq(in, &b) : nr::prims::chamfer5Padded(in, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

BIN(jfloatArray, nDistanceChamfer)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jint maskSize, jlongArray stats) {
    return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgF32 out = nr::prims::distanceChamfer(in, maskSize, &b);
        const int64_t dt = nowNs() - t0;
        jfloatArray r = floatsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

BIN(jdouble, nTotalContourLength)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jlongArray stats) {
    return guarded<jdouble>(env, 0.0, [&]() -> jdouble {
        if (!dimsOk(env, m, w, h)) return 0.0;
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const double v = nr::prims::totalContourLength(in, &b);
        statsOut(env, stats, nowNs() - t0, b);
        return v;
    });
}

/** seed、within 同尺寸 w×h。GROW：args＝{iters, step, bfs（-1＝null、0、1）}，回遮罩。 */
BIN(jbooleanArray, nGeodesicGrow)(JNIEnv* env, jclass, jbooleanArray seed, jbooleanArray within, jint w, jint h, jint iters, jint step, jint bfs, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (!dimsOk(env, seed, w, h) || !dimsOk(env, within, w, h)) return nullptr;
        const nr::Mask s = maskIn(env, seed, w, h);
        const nr::Mask wi = maskIn(env, within, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::Mask out = nr::prims::geodesicGrow(s, wi, iters, step, bfs < 0 ? -1 : (bfs != 0 ? 1 : 0), &b);
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = boolsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

BIN(jintArray, nGeodesicDistance)(JNIEnv* env, jclass, jbooleanArray seed, jbooleanArray within, jint w, jint h, jint step, jint maxIters, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, seed, w, h) || !dimsOk(env, within, w, h)) return nullptr;
        const nr::Mask s = maskIn(env, seed, w, h);
        const nr::Mask wi = maskIn(env, within, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = nr::prims::geodesicDistance(s, wi, step, maxIters, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** 遮罩 → 位元：PACK／PACK_NOT（列打包）、RING_PACK（整頁位元集合，Ring.packBits）。 */
BIN(jlongArray, nPack)(JNIEnv* env, jclass, jint op, jbooleanArray m, jint w, jint h, jlongArray stats) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        if (op != OP_PACK && op != OP_PACK_NOT && op != OP_RING_PACK) { badArg(env, "nightread: unknown op"); return nullptr; }
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        jlongArray r;
        if (op == OP_RING_PACK) {
            const nr::FlatBits f = nr::packFlat(in, &b);
            const int64_t dt = nowNs() - t0;
            r = longsOut(env, f.words.data(), f.words.size());
            if (r == nullptr) return nullptr;
            statsOut(env, stats, dt, b);
            return r;
        }
        const nr::PackedBits p = op == OP_PACK ? nr::packBits(in, &b) : nr::packBitsNot(in, &b);
        const int64_t dt = nowNs() - t0;
        r = longsOut(env, p.words.data(), p.words.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** 列打包 → 遮罩：UNPACK、UNPACK_NOT。 */
BIN(jbooleanArray, nUnpack)(JNIEnv* env, jclass, jint op, jlongArray a, jint w, jint h, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (!packedOk(env, a, w, h)) return nullptr;
        if (op != OP_UNPACK && op != OP_UNPACK_NOT) { badArg(env, "nightread: unknown op"); return nullptr; }
        const nr::PackedBits in = packedIn(env, a, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::Mask out = op == OP_UNPACK ? nr::unpackBits(in, &b) : nr::unpackBitsNot(in, &b);
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = boolsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** 列打包 → 列打包：NOT_IN_PLACE（回新陣列，Kotlin 是就地改）、ERODE_BITS（要核）。 */
BIN(jlongArray, nBits)(JNIEnv* env, jclass, jint op, jlongArray a, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jlongArray stats) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        if (!packedOk(env, a, w, h)) return nullptr;
        if (op != OP_NOT_IN_PLACE && op != OP_ERODE_BITS) { badArg(env, "nightread: unknown op"); return nullptr; }
        if (op == OP_ERODE_BITS && !dimsOk(env, kd, kw, kh)) return nullptr;
        nr::PackedBits in = packedIn(env, a, w, h);
        const nr::Kernel k = op == OP_ERODE_BITS ? kernelIn(env, kd, kw, kh) : nr::Kernel();
        nr::Budget b;
        const int64_t t0 = nowNs();
        jlongArray r;
        if (op == OP_NOT_IN_PLACE) {
            nr::notBitsInPlace(in);
            const int64_t dt = nowNs() - t0;
            r = longsOut(env, in.words.data(), in.words.size());
            if (r == nullptr) return nullptr;
            statsOut(env, stats, dt, b);
            return r;
        }
        const nr::PackedBits out = nr::prims::erodeBits(in, k, &b);
        const int64_t dt = nowNs() - t0;
        r = longsOut(env, out.words.data(), out.words.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** int[5][]：{ {n, bgLeft, bgTop, bgRight, bgBottom, fg 高 32 位, fg 低 32 位}, rs, re, rowFirst, lab }（同 NightNativePrims.nCcRuns）。 */
BIN(jobjectArray, nCcRunsBits)(JNIEnv* env, jclass, jlongArray a, jint w, jint h, jint connectivity, jlongArray stats) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        if (!packedOk(env, a, w, h)) return nullptr;
        const nr::PackedBits in = packedIn(env, a, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::CcRuns c = nr::prims::ccRunsBits(in, connectivity, &b);
        const int64_t dt = nowNs() - t0;
        jclass ic = env->FindClass("[I");
        if (ic == nullptr) return nullptr;
        jobjectArray r = env->NewObjectArray(5, ic, nullptr);
        env->DeleteLocalRef(ic);
        if (r == nullptr) return nullptr;
        const int32_t meta[7] = {c.n, c.bgLeft, c.bgTop, c.bgRight, c.bgBottom, static_cast<int32_t>(c.fg >> 32), static_cast<int32_t>(c.fg)};
        const int32_t* parts[5] = {meta, c.rs.data(), c.re.data(), c.rowFirst.data(), c.lab.data()};
        const size_t lens[5] = {7, c.rs.size(), c.re.size(), c.rowFirst.size(), c.lab.size()};
        for (int i = 0; i < 5; i++) {
            jintArray x = intsOut(env, parts[i], lens[i]);
            if (x == nullptr) return nullptr;
            env->SetObjectArrayElement(r, i, x);
            env->DeleteLocalRef(x);
            if (env->ExceptionCheck()) return nullptr;
        }
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** int[2][]：{ rs, re }（只有段數那麼長）。bits 從第 off 字起取 nw 字；越界丟 IllegalArgumentException。 */
BIN(jobjectArray, nBitRuns)(JNIEnv* env, jclass, jlongArray a, jint off, jint nw) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        if (a == nullptr || off < 0 || nw < 0 || static_cast<long long>(off) + nw > env->GetArrayLength(a)) {
            badArg(env, "nightread: bitRuns range out of bounds");
            return nullptr;
        }
        std::vector<uint64_t> bits(static_cast<size_t>(nw));
        if (nw > 0) env->GetLongArrayRegion(a, off, nw, reinterpret_cast<jlong*>(bits.data()));
        const size_t cap = static_cast<size_t>((64LL * nw + 1) / 2 + 1);
        std::vector<int32_t> rs(cap), re(cap);
        const int32_t cnt = nr::bitRuns(bits.data(), 0, nw, rs.data(), re.data());
        jclass ic = env->FindClass("[I");
        if (ic == nullptr) return nullptr;
        jobjectArray r = env->NewObjectArray(2, ic, nullptr);
        env->DeleteLocalRef(ic);
        if (r == nullptr) return nullptr;
        for (int i = 0; i < 2; i++) {
            jintArray x = intsOut(env, (i == 0 ? rs : re).data(), static_cast<size_t>(cnt));
            if (x == nullptr) return nullptr;
            env->SetObjectArrayElement(r, i, x);
            env->DeleteLocalRef(x);
            if (env->ExceptionCheck()) return nullptr;
        }
        return r;
    });
}

/** 前置條件（0 ≤ s、e ≤ 64·(len − off)）不合丟 IllegalArgumentException。 */
BIN(jboolean, nAnyBits)(JNIEnv* env, jclass, jlongArray a, jint off, jint s, jint e) {
    return guarded<jboolean>(env, JNI_FALSE, [&]() -> jboolean {
        if (a == nullptr || off < 0 || off > env->GetArrayLength(a)) { badArg(env, "nightread: anyBits offset out of bounds"); return JNI_FALSE; }
        const jsize n = env->GetArrayLength(a) - off;
        if (s < e && (s < 0 || static_cast<long long>(e) > 64LL * n)) { badArg(env, "nightread: anyBits range out of bounds"); return JNI_FALSE; }
        std::vector<uint64_t> bits(static_cast<size_t>(n));
        if (n > 0) env->GetLongArrayRegion(a, off, n, reinterpret_cast<jlong*>(bits.data()));
        return nr::anyBits(bits.data(), 0, s, e) ? JNI_TRUE : JNI_FALSE;
    });
}

/** n ≤ 0 丟 IllegalArgumentException（Kotlin 不會結束）。 */
BIN(jint, nReflect101)(JNIEnv* env, jclass, jint i, jint n) {
    if (n <= 0) { badArg(env, "nightread: reflect101 needs n >= 1"); return 0; }
    return nr::reflect101(i, n);
}

/** Ring.has（a 可為 null）；i 越界丟 IllegalArgumentException。 */
BIN(jboolean, nRingHas)(JNIEnv* env, jclass, jlongArray a, jint i) {
    if (a == nullptr) return JNI_FALSE;
    if (i < 0 || (static_cast<long long>(i) >> 6) >= env->GetArrayLength(a)) { badArg(env, "nightread: bit index out of bounds"); return JNI_FALSE; }
    jlong w = 0;
    env->GetLongArrayRegion(a, i >> 6, 1, &w);
    const uint64_t word = static_cast<uint64_t>(w);
    return nr::hasBit(&word, i & 63) ? JNI_TRUE : JNI_FALSE;
}

/** Ring.set：回改好的新陣列（不改 a）。 */
BIN(jlongArray, nRingSet)(JNIEnv* env, jclass, jlongArray a, jint i) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        if (a == nullptr || i < 0 || (static_cast<long long>(i) >> 6) >= env->GetArrayLength(a)) { badArg(env, "nightread: bit index out of bounds"); return nullptr; }
        std::vector<uint64_t> v(static_cast<size_t>(env->GetArrayLength(a)));
        env->GetLongArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jlong*>(v.data()));
        nr::setBit(v.data(), i);
        return longsOut(env, v.data(), v.size());
    });
}

/** ELLIPSE（a＝size）、RECT（a＝kw、b＝kh）：回核的資料（kw·kh 個 boolean）。 */
BIN(jbooleanArray, nKernel)(JNIEnv* env, jclass, jint op, jint a, jint b2) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (op != OP_ELLIPSE && op != OP_RECT) { badArg(env, "nightread: unknown op"); return nullptr; }
        const nr::Kernel k = op == OP_ELLIPSE ? nr::prims::ellipse(a) : nr::prims::rect(a, b2);
        return boolsOut(env, k.data.data(), k.data.size());
    });
}

/** 浮點影像縮放：RESIZE_AREA、RESIZE_BILINEAR、RESIZE_AREA_OF（resizeAreaOf 本體，同尺寸也照算，不走複本的捷徑）。 */
BIN(jfloatArray, nResizeF)(JNIEnv* env, jclass, jint op, jfloatArray f, jint w, jint h, jint nw, jint nh, jlongArray stats) {
    return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
        if (!dimsOk(env, f, w, h)) return nullptr;
        if (op != OP_RESIZE_AREA && op != OP_RESIZE_BILINEAR && op != OP_RESIZE_AREA_OF) { badArg(env, "nightread: unknown op"); return nullptr; }
        nr::ImgF32 in(w, h);
        if (in.data.size() > 0) env->GetFloatArrayRegion(f, 0, static_cast<jsize>(in.data.size()), in.data.data());
        const float* d = in.data.data();
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgF32 out = op == OP_RESIZE_AREA ? nr::prims::resizeArea(in, nw, nh, &b)
            : op == OP_RESIZE_BILINEAR ? nr::prims::resizeBilinear(in, nw, nh, &b)
            : nr::prims::resizeAreaOf(w, h, nw, nh, [d](int32_t i) { return d[i]; }, &b);
        const int64_t dt = nowNs() - t0;
        jfloatArray r = floatsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

BIN(jintArray, nResizeNearestGray)(JNIEnv* env, jclass, jintArray g, jint w, jint h, jint nw, jint nh, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, w, h)) return nullptr;
        nr::ImgI32 in(w, h);
        if (in.data.size() > 0) env->GetIntArrayRegion(g, 0, static_cast<jsize>(in.data.size()), reinterpret_cast<jint*>(in.data.data()));
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = nr::prims::resizeNearestGray(in, nw, nh, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** xs、ys 同長（不同丟 IllegalArgumentException；Kotlin 只在 ys 比較短時越界）。 */
BIN(jdouble, nPolygonArea)(JNIEnv* env, jclass, jfloatArray xs, jfloatArray ys) {
    return guarded<jdouble>(env, 0.0, [&]() -> jdouble {
        if (xs == nullptr || ys == nullptr || env->GetArrayLength(xs) != env->GetArrayLength(ys)) { badArg(env, "nightread: xs and ys differ in length"); return 0.0; }
        const jsize n = env->GetArrayLength(xs);
        std::vector<float> x(static_cast<size_t>(n)), y(static_cast<size_t>(n));
        if (n > 0) {
            env->GetFloatArrayRegion(xs, 0, n, x.data());
            env->GetFloatArrayRegion(ys, 0, n, y.data());
        }
        return nr::prims::polygonArea(x.data(), y.data(), n);
    });
}
