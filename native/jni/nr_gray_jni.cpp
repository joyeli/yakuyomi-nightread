// 第 3b 階影像原語的 JNI（對應 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/NightNativeGray.kt）。只給差分測試、錄放與手機探針用
// （產品路徑之後是整頁的 handle，nr.h）。規則同 nr_bin_jni.cpp：
//   - 輸入用 Get*ArrayRegion 複製進原生緩衝、輸出用 Set*ArrayRegion 複製回去；不持有 critical。就地改輸入的（line、fillPoly）改的是
//     原生的複本，回新陣列，不動 Java 的輸入。
//   - stats（可為 null，長度 ≥ 2）：stats[0]＝原語本身的耗時（奈秒，不含 JNI 的複製進出）、stats[1]＝原生記憶體峰值（位元組，含輸出）。
//   - 陣列長度或尺寸不合丟 IllegalArgumentException；原語本身丟的 C++ 例外（Kotlin 也會丟的輸入）→ IllegalStateException、
//     配置失敗 → OutOfMemoryError（nr_jni_util.h 的 guarded）。
// 同形狀的原語共用一個進入點，用運算代碼分（代碼與 NightNativeGray.kt 的常數同步）。
#include <jni.h>
#include <time.h>

#include <vector>

#include "../core/jmath.h"
#include "../core/prims.h"
#include "../core/tables.h"
#include "../core/types.h"
#include "nr_jni_util.h"

using nr::jni::guarded;
using nr::jni::throwJava;

#define GRAY(ret, name) NR_JNI_EXPORT ret JNICALL Java_li_joye_yakuyomi_nightread_NightNativeGray_##name

namespace {

// 運算代碼（NightNativeGray.kt 的 OP_*）
enum : jint {
    OP_DILATE_GRAY = 1, OP_ERODE_GRAY = 2, OP_DILATE_GRAY_I = 3, OP_ERODE_GRAY_I = 4, OP_BLACKHAT = 5, OP_MEDIAN8 = 6,
    OP_GAUSSIAN_BLUR = 10, OP_BOX_BLUR = 11,
    OP_LINES = 20, OP_FILL_POLY = 21,
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

nr::ImgI32 grayIn(JNIEnv* env, jintArray a, jint w, jint h) {
    nr::ImgI32 g(w, h);
    if (g.data.size() > 0) env->GetIntArrayRegion(a, 0, static_cast<jsize>(g.data.size()), reinterpret_cast<jint*>(g.data.data()));
    return g;
}

nr::Mask maskIn(JNIEnv* env, jbooleanArray a, jint w, jint h) {
    nr::Mask m(w, h);
    if (m.data.size() > 0) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(m.data.size()), reinterpret_cast<jboolean*>(m.data.data()));
    return m;
}

nr::Kernel kernelIn(JNIEnv* env, jbooleanArray a, jint kw, jint kh) {
    std::vector<uint8_t> d(static_cast<size_t>(kw) * static_cast<size_t>(kh));
    if (!d.empty()) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(d.size()), reinterpret_cast<jboolean*>(d.data()));
    return nr::Kernel(kw, kh, d.data());
}

std::vector<int32_t> intsIn(JNIEnv* env, jintArray a) {
    std::vector<int32_t> v(a == nullptr ? 0 : static_cast<size_t>(env->GetArrayLength(a)));
    if (!v.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jint*>(v.data()));
    return v;
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

/** gaussQ16Sparse 交給 sink 的列串成 Cv.SparseRec 的格式：y、段數 n、n 對 [s, e)、各段的值。 */
class RecSink final : public nr::prims::RowSink {
public:
    std::vector<int32_t> out;
    void row(int32_t y, const int32_t* v, const int32_t* rs, const int32_t* re, int32_t n) override {
        out.push_back(y);
        out.push_back(n);
        for (int32_t q = 0; q < n; q++) { out.push_back(rs[q]); out.push_back(re[q]); }
        for (int32_t q = 0; q < n; q++) for (int32_t x = rs[q]; x < re[q]; x++) out.push_back(v[x]);
    }
};

}  // namespace

/**
 * 灰階 → 灰階：DILATE_GRAY／ERODE_GRAY／DILATE_GRAY_I／ERODE_GRAY_I／BLACKHAT（要核 kd／kw／kh）、MEDIAN8（iarg＝ksize，kd 可為 null）。
 */
GRAY(jintArray, nGray)(JNIEnv* env, jclass, jint op, jintArray g, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jint iarg, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, w, h)) return nullptr;
        const bool needK = op >= OP_DILATE_GRAY && op <= OP_BLACKHAT;
        if (!needK && op != OP_MEDIAN8) { badArg(env, "nightread: unknown gray op"); return nullptr; }
        if (needK && !dimsOk(env, kd, kw, kh)) return nullptr;
        const nr::ImgI32 in = grayIn(env, g, w, h);
        const nr::Kernel k = needK ? kernelIn(env, kd, kw, kh) : nr::Kernel();
        nr::Budget b;
        const int64_t t0 = nowNs();
        nr::ImgI32 out;
        switch (op) {
            case OP_DILATE_GRAY: out = nr::prims::dilateGray(in, k, &b); break;
            case OP_ERODE_GRAY: out = nr::prims::erodeGray(in, k, &b); break;
            case OP_DILATE_GRAY_I: out = nr::prims::dilateGrayI(in, k, &b); break;
            case OP_ERODE_GRAY_I: out = nr::prims::erodeGrayI(in, k, &b); break;
            case OP_BLACKHAT: out = nr::prims::blackhat(in, k, &b); break;
            default: out = nr::prims::medianBlur8(in, iarg, &b); break;
        }
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** 浮點影像濾波：GAUSSIAN_BLUR（darg＝σ）、BOX_BLUR（iarg＝k）。 */
GRAY(jfloatArray, nFilter)(JNIEnv* env, jclass, jint op, jfloatArray f, jint w, jint h, jdouble darg, jint iarg, jlongArray stats) {
    return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
        if (!dimsOk(env, f, w, h)) return nullptr;
        if (op != OP_GAUSSIAN_BLUR && op != OP_BOX_BLUR) { badArg(env, "nightread: unknown filter op"); return nullptr; }
        nr::ImgF32 in(w, h);
        if (in.data.size() > 0) env->GetFloatArrayRegion(f, 0, static_cast<jsize>(in.data.size()), in.data.data());
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgF32 out = op == OP_GAUSSIAN_BLUR ? nr::prims::gaussianBlur(in, darg, &b) : nr::prims::boxBlur(in, iarg, &b);
        const int64_t dt = nowNs() - t0;
        jfloatArray r = floatsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/**
 * 繪圖（在 m 的複本上畫，回新陣列）：LINES（segs＝每 5 個一段 {x0, y0, x1, y1, thickness}，依序畫）、FILL_POLY（xs、ys）。
 * LINES 的 segs 長度不是 5 的倍數丟 IllegalArgumentException。
 */
GRAY(jbooleanArray, nDraw)(JNIEnv* env, jclass, jint op, jbooleanArray m, jint w, jint h, jintArray segs, jintArray xs, jintArray ys, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        if (op != OP_LINES && op != OP_FILL_POLY) { badArg(env, "nightread: unknown draw op"); return nullptr; }
        if (op == OP_LINES && (segs == nullptr || env->GetArrayLength(segs) % 5 != 0)) { badArg(env, "nightread: segs must be 5 ints per line"); return nullptr; }
        if (op == OP_FILL_POLY && (xs == nullptr || ys == nullptr)) { badArg(env, "nightread: xs/ys must not be null"); return nullptr; }
        nr::Mask out = maskIn(env, m, w, h);
        const std::vector<int32_t> s = intsIn(env, op == OP_LINES ? segs : xs);
        const std::vector<int32_t> yv = op == OP_FILL_POLY ? intsIn(env, ys) : std::vector<int32_t>();
        nr::Budget b;
        const int64_t t0 = nowNs();
        if (op == OP_LINES) {
            for (size_t i = 0; i + 4 < s.size(); i += 5) nr::prims::line(out, s[i], s[i + 1], s[i + 2], s[i + 3], s[i + 4]);
        } else {
            nr::prims::fillPoly(out, s.data(), static_cast<int32_t>(s.size()), yv.data(), static_cast<int32_t>(yv.size()), &b);
        }
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = boolsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** Cv.canny（src 長度至少 w·h；不足丟 IllegalArgumentException）。 */
GRAY(jbooleanArray, nCanny)(JNIEnv* env, jclass, jintArray src, jint w, jint h, jint shift, jint lo, jint hi, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (src == nullptr || w < 0 || h < 0 || static_cast<long long>(w) * h > env->GetArrayLength(src)) {
            badArg(env, "nightread: canny src shorter than w*h");
            return nullptr;
        }
        const std::vector<int32_t> s = intsIn(env, src);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::Mask out = nr::prims::canny(s.data(), s.size(), w, h, shift, lo, hi, &b);
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = boolsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** Cv.maxFilterWrapRows（rows、cols、nr 不得為負；s 長度至少 rows·cols）。 */
GRAY(jintArray, nMaxFilterWrapRows)(JNIEnv* env, jclass, jintArray s, jint rows, jint cols, jint nt, jint nr, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (s == nullptr || rows < 0 || cols < 0 || nr < 0 || static_cast<long long>(rows) * cols > env->GetArrayLength(s)) {
            badArg(env, "nightread: maxFilterWrapRows arguments");
            return nullptr;
        }
        const std::vector<int32_t> v = intsIn(env, s);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = nr::prims::maxFilterWrapRows(v.data(), v.size(), rows, cols, nt, nr, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** Cv.gaussAt 在多個點上（xs、ys 同長）：回每點的值。 */
GRAY(jintArray, nGaussAt)(JNIEnv* env, jclass, jintArray g, jint w, jint h, jintArray k, jintArray xs, jintArray ys, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, w, h)) return nullptr;
        if (k == nullptr || xs == nullptr || ys == nullptr || env->GetArrayLength(xs) != env->GetArrayLength(ys)) {
            badArg(env, "nightread: gaussAt arguments");
            return nullptr;
        }
        const nr::ImgI32 in = grayIn(env, g, w, h);
        const std::vector<int32_t> kv = intsIn(env, k), xv = intsIn(env, xs), yv = intsIn(env, ys);
        std::vector<int32_t> out(xv.size());
        nr::Budget b;
        const int64_t t0 = nowNs();
        for (size_t i = 0; i < xv.size(); i++) out[i] = nr::prims::gaussAt(in, kv.data(), static_cast<int32_t>(kv.size()), xv[i], yv[i]);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data(), out.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/**
 * Cv.gaussQ16Sparse：來源＝gw×gh 的灰階 g，ksize 0＝GrayRows、否則 MedianRows(g, ksize)。回 Cv.SparseRec 的格式。
 * need 長度不足 nrows·((w+63)/64) 時丟 IllegalArgumentException（Kotlin 是越界）。
 */
GRAY(jintArray, nGaussQ16Sparse)(JNIEnv* env, jclass, jint w, jint h, jintArray k, jlongArray need, jint ny0, jint nrows, jintArray g, jint gw, jint gh,
                                 jint ksize, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (k == nullptr || need == nullptr || !dimsOk(env, g, gw, gh)) {
            if (!env->ExceptionCheck()) badArg(env, "nightread: gaussQ16Sparse arguments");
            return nullptr;
        }
        if (ksize < 0) { badArg(env, "nightread: gaussQ16Sparse source ksize < 0"); return nullptr; }
        const nr::ImgI32 src = grayIn(env, g, gw, gh);
        const std::vector<int32_t> kv = intsIn(env, k);
        std::vector<uint64_t> nv(static_cast<size_t>(env->GetArrayLength(need)));
        if (!nv.empty()) env->GetLongArrayRegion(need, 0, static_cast<jsize>(nv.size()), reinterpret_cast<jlong*>(nv.data()));
        nr::Budget b;
        RecSink sink;
        const int64_t t0 = nowNs();
        if (ksize == 0) {
            nr::prims::GrayRows rows(src);
            nr::prims::gaussQ16Sparse(w, h, kv.data(), static_cast<int32_t>(kv.size()), nv.data(), nv.size(), ny0, nrows, rows, sink, &b);
        } else {
            nr::prims::MedianRows rows(src, ksize, &b);
            nr::prims::gaussQ16Sparse(w, h, kv.data(), static_cast<int32_t>(kv.size()), nv.data(), nv.size(), ny0, nrows, rows, sink, &b);
        }
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, sink.out.data(), sink.out.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/**
 * Cv.MedianRows(g, ksize).fill 連續多次：spec＝每次 {r, n, rs[0..n), re[0..n)}；dst 長 gw（同產品：gaussQ16Sparse 的頁寬＝g 的寬）。
 * 回各次各段的值依序串起來。spec 格式不對丟 IllegalArgumentException。
 */
GRAY(jintArray, nMedianRows)(JNIEnv* env, jclass, jintArray g, jint gw, jint gh, jint ksize, jintArray spec, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, gw, gh)) return nullptr;
        if (spec == nullptr) { badArg(env, "nightread: medianRows spec"); return nullptr; }
        const nr::ImgI32 src = grayIn(env, g, gw, gh);
        const std::vector<int32_t> sp = intsIn(env, spec);
        // 先驗格式：每次 2 + 2n 個
        for (size_t p = 0; p < sp.size();) {
            if (p + 2 > sp.size() || sp[p + 1] < 0 || p + 2 + 2 * static_cast<size_t>(sp[p + 1]) > sp.size()) {
                badArg(env, "nightread: medianRows spec is malformed");
                return nullptr;
            }
            p += 2 + 2 * static_cast<size_t>(sp[p + 1]);
        }
        nr::Budget b;
        std::vector<int32_t> out;
        const int64_t t0 = nowNs();
        nr::prims::MedianRows rows(src, ksize, &b);
        nr::Buf<int32_t> dst(static_cast<size_t>(gw), &b);
        for (size_t p = 0; p < sp.size();) {
            const int32_t r = sp[p];
            const int32_t n = sp[p + 1];
            const int32_t* rs = sp.data() + p + 2;
            const int32_t* re = rs + n;
            rows.fill(r, rs, re, n, dst.data(), dst.size());
            for (int32_t q = 0; q < n; q++) for (int32_t x = rs[q]; x < re[q]; x++) out.push_back(dst[static_cast<size_t>(x)]);
            p += 2 + 2 * static_cast<size_t>(n);
        }
        const int64_t dt = nowNs() - t0;
        jintArray rr = intsOut(env, out.data(), out.size());
        if (rr == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return rr;
    });
}

/** Cv.npSum(a, off, n)（範圍越界丟 IllegalArgumentException；n < 0 回 0，同 Kotlin）。 */
GRAY(jdouble, nNpSum)(JNIEnv* env, jclass, jdoubleArray a, jint off, jint n) {
    return guarded<jdouble>(env, 0.0, [&]() -> jdouble {
        if (a == nullptr) { badArg(env, "nightread: npSum null"); return 0.0; }
        if (n <= 0) return 0.0;   // Kotlin：一個元素都不讀，off 是什麼都不丟
        if (off < 0 || static_cast<long long>(off) + n > env->GetArrayLength(a)) {
            badArg(env, "nightread: npSum range out of bounds");
            return 0.0;
        }
        std::vector<double> v(static_cast<size_t>(n));
        env->GetDoubleArrayRegion(a, off, n, v.data());
        return nr::jm::npSum(v.data(), n);
    });
}

GRAY(jintArray, nNpArgsort)(JNIEnv* env, jclass, jintArray v) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (v == nullptr) { badArg(env, "nightread: npArgsort null"); return nullptr; }
        const std::vector<int32_t> a = intsIn(env, v);
        std::vector<int32_t> ts(a.size());
        nr::jm::npArgsort(a.data(), static_cast<int32_t>(a.size()), ts.data());
        return intsOut(env, ts.data(), ts.size());
    });
}

GRAY(jdoubleArray, nEigh2)(JNIEnv* env, jclass, jdouble a, jdouble b, jdouble c) {
    return guarded<jdoubleArray>(env, nullptr, [&]() -> jdoubleArray {
        double o[6];
        nr::jm::eigh2(a, b, c, o);
        jdoubleArray r = env->NewDoubleArray(6);
        if (r != nullptr) env->SetDoubleArrayRegion(r, 0, 6, o);
        return r;
    });
}

GRAY(jintArray, nGaussW)(JNIEnv* env, jclass, jdouble sigma) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        const std::vector<int32_t> k = nr::tables::gaussW(sigma);
        return intsOut(env, k.data(), k.size());
    });
}
