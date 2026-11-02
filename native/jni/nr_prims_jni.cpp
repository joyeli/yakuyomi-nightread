// 第一批影像原語的 JNI（對應 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/NightNativePrims.kt）。只給差分測試與手機探針用：
// 之後的產品路徑是整頁的 handle（nr.h），不會每個原語都過一次 JNI。
//
// - 輸入用 Get*ArrayRegion 複製進原生緩衝、輸出用 Set*ArrayRegion 複製回去；不用 GetPrimitiveArrayCritical
//   （原語在全頁上要跑幾十到幾百毫秒，持有 critical 會擋住 ART 的 GC）。
// - stats（可為 null，長度 ≥ 2）：stats[0]＝原語本身的耗時（奈秒，CLOCK_MONOTONIC，不含 JNI 的複製進出）、stats[1]＝原生記憶體峰值（位元組，含輸出）。
//   輸入在開始計時前就已經在原生記憶體裡，對應 Kotlin 版「輸入陣列已經存在」；輸出的配置算在時間裡，Kotlin 版也是。
// - 參數不合法丟 IllegalArgumentException；C++ 例外不穿過 JNI（nr_jni_util.h）。
#include <jni.h>
#include <time.h>

#include <vector>

#include "../core/prims.h"
#include "../core/types.h"
#include "nr_jni_util.h"

using nr::jni::guarded;
using nr::jni::throwJava;

#define PRIM(ret, name) NR_JNI_EXPORT ret JNICALL Java_li_joye_yakuyomi_nightread_NightNativePrims_##name

namespace {

inline int64_t nowNs() {
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return static_cast<int64_t>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

bool dimsOk(JNIEnv* env, jarray a, jint w, jint h) {
    if (a == nullptr || w < 0 || h < 0 ||
        static_cast<long long>(w) * h != static_cast<long long>(env->GetArrayLength(a))) {
        throwJava(env, "java/lang/IllegalArgumentException", "nightread: array size does not match w*h");
        return false;
    }
    return true;
}

nr::Mask maskIn(JNIEnv* env, jbooleanArray a, jint w, jint h) {
    nr::Mask m(w, h);
    if (m.data.size() > 0) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(m.data.size()), reinterpret_cast<jboolean*>(m.data.data()));
    return m;
}

nr::ImgI32 grayIn(JNIEnv* env, jintArray a, jint w, jint h) {
    nr::ImgI32 g(w, h);
    if (g.data.size() > 0) env->GetIntArrayRegion(a, 0, static_cast<jsize>(g.data.size()), reinterpret_cast<jint*>(g.data.data()));
    return g;
}

nr::Kernel kernelIn(JNIEnv* env, jbooleanArray a, jint kw, jint kh) {
    std::vector<uint8_t> d(static_cast<size_t>(kw) * static_cast<size_t>(kh));
    if (!d.empty()) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(d.size()), reinterpret_cast<jboolean*>(d.data()));
    return nr::Kernel(kw, kh, d.data());
}

jintArray intsOut(JNIEnv* env, const int32_t* v, size_t n) {
    jintArray a = env->NewIntArray(static_cast<jsize>(n));
    if (a != nullptr && n > 0) env->SetIntArrayRegion(a, 0, static_cast<jsize>(n), reinterpret_cast<const jint*>(v));
    return a;
}

void statsOut(JNIEnv* env, jlongArray stats, int64_t ns, const nr::Budget& b) {
    if (stats == nullptr) return;
    if (env->GetArrayLength(stats) < 2) {
        throwJava(env, "java/lang/IllegalArgumentException", "nightread: stats must have at least 2 elements");
        return;
    }
    const jlong v[2] = {ns, b.peak.load(std::memory_order_relaxed)};
    env->SetLongArrayRegion(stats, 0, 2, v);
}

/** int[][]：每個元素一個 int[]。任何一步失敗回 nullptr（例外已在等）。 */
jobjectArray intArrays(JNIEnv* env, int n) {
    jclass c = env->FindClass("[I");
    if (c == nullptr) return nullptr;
    jobjectArray a = env->NewObjectArray(n, c, nullptr);
    env->DeleteLocalRef(c);
    return a;
}

bool setInts(JNIEnv* env, jobjectArray a, int i, const int32_t* v, size_t n) {
    jintArray x = intsOut(env, v, n);
    if (x == nullptr) return false;
    env->SetObjectArrayElement(a, i, x);
    env->DeleteLocalRef(x);
    return !env->ExceptionCheck();
}

}  // namespace

PRIM(jbooleanArray, nDilatePacked)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jlongArray stats) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        if (!dimsOk(env, m, w, h) || !dimsOk(env, kd, kw, kh)) return nullptr;
        const nr::Mask in = maskIn(env, m, w, h);
        const nr::Kernel k = kernelIn(env, kd, kw, kh);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::Mask out = nr::prims::dilatePacked(in, k, &b);
        const int64_t dt = nowNs() - t0;
        jbooleanArray r = env->NewBooleanArray(static_cast<jsize>(out.data.size()));
        if (r == nullptr) return nullptr;
        if (out.data.size() > 0) env->SetBooleanArrayRegion(r, 0, static_cast<jsize>(out.data.size()), reinterpret_cast<const jboolean*>(out.data.data()));
        statsOut(env, stats, dt, b);
        return r;
    });
}

PRIM(jlongArray, nDilateBits)(JNIEnv* env, jclass, jlongArray src, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jlongArray stats) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        if (!dimsOk(env, kd, kw, kh)) return nullptr;
        if (src == nullptr || w < 0 || h < 0) {
            throwJava(env, "java/lang/IllegalArgumentException", "nightread: bad dilateBits input");
            return nullptr;
        }
        nr::PackedBits in(w, h);
        if (static_cast<size_t>(env->GetArrayLength(src)) != in.words.size()) {
            throwJava(env, "java/lang/IllegalArgumentException", "nightread: array size does not match nw*h");
            return nullptr;
        }
        if (in.words.size() > 0) env->GetLongArrayRegion(src, 0, static_cast<jsize>(in.words.size()), reinterpret_cast<jlong*>(in.words.data()));
        const nr::Kernel k = kernelIn(env, kd, kw, kh);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::PackedBits out = nr::prims::dilateBits(in, k, &b);
        const int64_t dt = nowNs() - t0;
        jlongArray r = env->NewLongArray(static_cast<jsize>(out.words.size()));
        if (r == nullptr) return nullptr;
        if (out.words.size() > 0) env->SetLongArrayRegion(r, 0, static_cast<jsize>(out.words.size()), reinterpret_cast<const jlong*>(out.words.data()));
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** int[7][]：{ {n}, labels, left, top, width, height, area }。 */
PRIM(jobjectArray, nCcStats)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jint connectivity, jlongArray stats) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::CC c = nr::prims::ccStats(in, connectivity, &b);
        const int64_t dt = nowNs() - t0;
        jobjectArray r = intArrays(env, 7);
        if (r == nullptr) return nullptr;
        const int32_t n = c.n;
        const size_t sn = static_cast<size_t>(n);
        if (!setInts(env, r, 0, &n, 1) || !setInts(env, r, 1, c.labels.data(), c.labels.size()) || !setInts(env, r, 2, c.left.data(), sn) ||
            !setInts(env, r, 3, c.top.data(), sn) || !setInts(env, r, 4, c.width.data(), sn) || !setInts(env, r, 5, c.height.data(), sn) ||
            !setInts(env, r, 6, c.area.data(), sn)) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

/** int[5][]：{ {n, bgLeft, bgTop, bgRight, bgBottom, fg 高 32 位, fg 低 32 位}, rs, re, rowFirst, lab }。 */
PRIM(jobjectArray, nCcRuns)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jint connectivity, jlongArray stats) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::CcRuns c = nr::prims::ccRuns(in, connectivity, &b);
        const int64_t dt = nowNs() - t0;
        jobjectArray r = intArrays(env, 5);
        if (r == nullptr) return nullptr;
        const int32_t meta[7] = {c.n, c.bgLeft, c.bgTop, c.bgRight, c.bgBottom, static_cast<int32_t>(c.fg >> 32), static_cast<int32_t>(c.fg)};
        if (!setInts(env, r, 0, meta, 7) || !setInts(env, r, 1, c.rs.data(), c.rs.size()) || !setInts(env, r, 2, c.re.data(), c.re.size()) ||
            !setInts(env, r, 3, c.rowFirst.data(), c.rowFirst.size()) || !setInts(env, r, 4, c.lab.data(), c.lab.size())) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

PRIM(jfloatArray, nDistanceL2)(JNIEnv* env, jclass, jbooleanArray m, jint w, jint h, jlongArray stats) {
    return guarded<jfloatArray>(env, nullptr, [&]() -> jfloatArray {
        if (!dimsOk(env, m, w, h)) return nullptr;
        const nr::Mask in = maskIn(env, m, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgF32 out = nr::prims::distanceL2(in, &b);
        const int64_t dt = nowNs() - t0;
        jfloatArray r = env->NewFloatArray(static_cast<jsize>(out.data.size()));
        if (r == nullptr) return nullptr;
        if (out.data.size() > 0) env->SetFloatArrayRegion(r, 0, static_cast<jsize>(out.data.size()), out.data.data());
        statsOut(env, stats, dt, b);
        return r;
    });
}

PRIM(jintArray, nGaussQ16)(JNIEnv* env, jclass, jintArray g, jint w, jint h, jdouble sigma, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, w, h)) return nullptr;
        const nr::ImgI32 in = grayIn(env, g, w, h);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = nr::prims::gaussQ16(in, sigma, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}

PRIM(jintArray, nMorphGrayGather)(JNIEnv* env, jclass, jintArray g, jint w, jint h, jbooleanArray kd, jint kw, jint kh, jboolean wantMax, jlongArray stats) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        if (!dimsOk(env, g, w, h) || !dimsOk(env, kd, kw, kh)) return nullptr;
        const nr::ImgI32 in = grayIn(env, g, w, h);
        const nr::Kernel k = kernelIn(env, kd, kw, kh);
        nr::Budget b;
        const int64_t t0 = nowNs();
        const nr::ImgI32 out = nr::prims::morphGrayGather(in, k, wantMax != JNI_FALSE, &b);
        const int64_t dt = nowNs() - t0;
        jintArray r = intsOut(env, out.data.data(), out.data.size());
        if (r == nullptr) return nullptr;
        statsOut(env, stats, dt, b);
        return r;
    });
}
