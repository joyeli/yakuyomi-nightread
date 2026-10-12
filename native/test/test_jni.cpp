// 只給主機測試用的 JNI（對應 nightread/src/test/kotlin/.../NativeTestBridge.kt）：把數值核心、常數表、型別與測試案例產生器
// 暴露給 JUnit，在同一個 JVM 裡跟 Kotlin／Java 逐位元比。只編進 libnightread_host.so，不進產品的 .so。
#include <jni.h>

#include <cstdio>
#include <stdexcept>
#include <vector>

#include "../core/convert.h"
#include "../core/jmath.h"
#include "../core/params_gen.h"
#include "../core/tables.h"
#include "../core/types.h"
#include "../jni/nr_jni_util.h"
#include "cases.h"

using nr::jni::guarded;
using nr::jni::guardedVoid;
using nr::jni::throwJava;

#define BRIDGE(ret, name) NR_JNI_EXPORT ret JNICALL Java_li_joye_yakuyomi_nightread_NativeTestBridge_##name

namespace {

jintArray toJ(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
    if (a != nullptr && !v.empty()) env->SetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jint*>(v.data()));
    return a;
}
jdoubleArray toJ(JNIEnv* env, const std::vector<double>& v) {
    jdoubleArray a = env->NewDoubleArray(static_cast<jsize>(v.size()));
    if (a != nullptr && !v.empty()) env->SetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}
std::vector<double> fromJ(JNIEnv* env, jdoubleArray a) {
    std::vector<double> v(static_cast<size_t>(env->GetArrayLength(a)));
    if (!v.empty()) env->GetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}
std::vector<int32_t> fromJ(JNIEnv* env, jintArray a) {
    std::vector<int32_t> v(static_cast<size_t>(env->GetArrayLength(a)));
    if (!v.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jint*>(v.data()));
    return v;
}
std::vector<uint8_t> fromJ(JNIEnv* env, jbooleanArray a) {
    std::vector<uint8_t> v(static_cast<size_t>(env->GetArrayLength(a)));
    if (!v.empty()) env->GetBooleanArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jboolean*>(v.data()));
    return v;
}

}  // namespace

// ── 純量函式表 ─────────────────────────────────────────────────────────

BRIDGE(jint, fnCount)(JNIEnv*, jclass) { return nrt::fnCount(); }

BRIDGE(jstring, fnName)(JNIEnv* env, jclass, jint fn) { return env->NewStringUTF(nrt::fnSpec(fn).name); }

/** (nIn shl 16) or (nOut shl 8) or outKind */
BRIDGE(jint, fnArity)(JNIEnv*, jclass, jint fn) {
    const nrt::FnSpec& f = nrt::fnSpec(fn);
    return (f.nIn << 16) | (f.nOut << 8) | static_cast<int>(f.out);
}

/** 引數型別，每個 4 位元（第 k 個在 4k 位） */
BRIDGE(jint, fnInKinds)(JNIEnv*, jclass, jint fn) {
    const nrt::FnSpec& f = nrt::fnSpec(fn);
    int v = 0;
    for (int k = 0; k < f.nIn; k++) v |= static_cast<int>(f.in[k]) << (4 * k);
    return v;
}

BRIDGE(void, genScalar)(JNIEnv* env, jclass, jint fn, jlong start, jint n, jlongArray args) {
    guardedVoid(env, [&] {
        const nrt::FnSpec& f = nrt::fnSpec(fn);
        if (env->GetArrayLength(args) < static_cast<jsize>(n) * f.nIn) { throwJava(env, "java/lang/IllegalArgumentException", "args too short"); return; }
        std::vector<uint64_t> a(static_cast<size_t>(n) * f.nIn);
        nrt::genScalar(fn, start, n, a.data());
        env->SetLongArrayRegion(args, 0, static_cast<jsize>(a.size()), reinterpret_cast<const jlong*>(a.data()));
    });
}

BRIDGE(void, evalScalar)(JNIEnv* env, jclass, jint fn, jlongArray args, jint n, jlongArray out) {
    guardedVoid(env, [&] {
        const nrt::FnSpec& f = nrt::fnSpec(fn);
        const size_t na = static_cast<size_t>(n) * f.nIn, no = static_cast<size_t>(n) * (f.nOut + 1);
        if (static_cast<size_t>(env->GetArrayLength(args)) < na || static_cast<size_t>(env->GetArrayLength(out)) < no) {
            throwJava(env, "java/lang/IllegalArgumentException", "array too short");
            return;
        }
        std::vector<uint64_t> a(na), o(no);
        env->GetLongArrayRegion(args, 0, static_cast<jsize>(na), reinterpret_cast<jlong*>(a.data()));
        nrt::evalScalar(fn, a.data(), n, o.data());
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(no), reinterpret_cast<const jlong*>(o.data()));
    });
}

// ── 陣列 ─────────────────────────────────────────────────────────────────

BRIDGE(jdoubleArray, genSumArray)(JNIEnv* env, jclass, jboolean chunked, jlong idx) {
    return guarded<jdoubleArray>(env, nullptr, [&] { return toJ(env, nrt::genSumArray(chunked != JNI_FALSE, idx)); });
}

BRIDGE(jdouble, npSum)(JNIEnv* env, jclass, jdoubleArray a) {
    return guarded<jdouble>(env, 0.0, [&] { const auto v = fromJ(env, a); return nr::jm::npSum(v.data(), static_cast<int64_t>(v.size())); });
}

BRIDGE(jdouble, npSumChunked)(JNIEnv* env, jclass, jdoubleArray a) {
    return guarded<jdouble>(env, 0.0, [&] { const auto v = fromJ(env, a); return nr::jm::npSumChunked(v.data(), static_cast<int64_t>(v.size())); });
}

BRIDGE(jintArray, genArgsortArray)(JNIEnv* env, jclass, jlong idx) {
    return guarded<jintArray>(env, nullptr, [&] { return toJ(env, nrt::genArgsortArray(idx)); });
}

BRIDGE(jintArray, npArgsort)(JNIEnv* env, jclass, jintArray v) {
    return guarded<jintArray>(env, nullptr, [&] {
        const auto a = fromJ(env, v);
        std::vector<int32_t> ts(a.size());
        nr::jm::npArgsort(a.data(), static_cast<int32_t>(a.size()), ts.data());
        return toJ(env, ts);
    });
}

BRIDGE(jintArray, antiqsort)(JNIEnv* env, jclass, jint n) {
    return guarded<jintArray>(env, nullptr, [&] { return toJ(env, nrt::antiqsort(n)); });
}

// ── 常數表 ───────────────────────────────────────────────────────────────

BRIDGE(jintArray, gaussW)(JNIEnv* env, jclass, jdouble sigma) {
    return guarded<jintArray>(env, nullptr, [&] { return toJ(env, nr::tables::gaussW(sigma)); });
}
BRIDGE(jint, gaussianBlurKsize)(JNIEnv*, jclass, jdouble sigma) { return nr::tables::gaussianBlurKsize(sigma); }
BRIDGE(jdoubleArray, gaussianKernel)(JNIEnv* env, jclass, jint ks, jdouble sigma) {
    return guarded<jdoubleArray>(env, nullptr, [&] { return toJ(env, nr::tables::gaussianKernel(ks, sigma)); });
}
BRIDGE(jdoubleArray, ringGaussKernel)(JNIEnv* env, jclass, jdouble sigma) {
    return guarded<jdoubleArray>(env, nullptr, [&] { return toJ(env, nr::tables::ringGaussKernel(sigma)); });
}
BRIDGE(jfloatArray, textLut)(JNIEnv* env, jclass, jint bg, jint ink) {
    float lut[256];
    nr::tables::textLut(bg, ink, lut);
    jfloatArray a = env->NewFloatArray(256);
    if (a != nullptr) env->SetFloatArrayRegion(a, 0, 256, lut);
    return a;
}
BRIDGE(jfloatArray, inkLut)(JNIEnv* env, jclass, jint bg, jint top, jboolean inv) {
    float lut[256];
    nr::tables::inkLut(bg, top, inv != JNI_FALSE, lut);
    jfloatArray a = env->NewFloatArray(256);
    if (a != nullptr) env->SetFloatArrayRegion(a, 0, 256, lut);
    return a;
}
/** null＝C++ 判定 Kotlin 會丟例外。 */
BRIDGE(jobjectArray, lineOffsets)(JNIEnv* env, jclass, jint len) {
    return guarded<jobjectArray>(env, nullptr, [&]() -> jobjectArray {
        std::vector<std::vector<int32_t>> out;
        if (!nr::tables::lineOffsets(len, out)) return nullptr;
        jclass intArr = env->FindClass("[I");
        if (intArr == nullptr) return nullptr;
        jobjectArray res = env->NewObjectArray(static_cast<jsize>(out.size()), intArr, nullptr);
        for (size_t d = 0; res != nullptr && d < out.size(); d++) {
            jintArray a = toJ(env, out[d]);
            env->SetObjectArrayElement(res, static_cast<jsize>(d), a);
            env->DeleteLocalRef(a);
        }
        return res;
    });
}

// ── 型別 ─────────────────────────────────────────────────────────────────

BRIDGE(jlongArray, packBits)(JNIEnv* env, jclass, jbooleanArray mask, jint w, jint h) {
    return guarded<jlongArray>(env, nullptr, [&]() -> jlongArray {
        const auto src = fromJ(env, mask);
        nr::Mask m(w, h);
        if (m.data.size() != src.size()) { throwJava(env, "java/lang/IllegalArgumentException", "mask size"); return nullptr; }
        for (size_t i = 0; i < src.size(); i++) m.data[i] = src[i] ? 1 : 0;
        const nr::PackedBits p = nr::packBits(m);
        jlongArray a = env->NewLongArray(static_cast<jsize>(p.words.size()));
        if (a != nullptr && p.words.size() > 0) env->SetLongArrayRegion(a, 0, static_cast<jsize>(p.words.size()), reinterpret_cast<const jlong*>(p.words.data()));
        return a;
    });
}

BRIDGE(jbooleanArray, unpackBits)(JNIEnv* env, jclass, jlongArray words, jint w, jint h) {
    return guarded<jbooleanArray>(env, nullptr, [&]() -> jbooleanArray {
        nr::PackedBits p(w, h);
        if (static_cast<size_t>(env->GetArrayLength(words)) != p.words.size()) { throwJava(env, "java/lang/IllegalArgumentException", "words size"); return nullptr; }
        if (p.words.size() > 0) env->GetLongArrayRegion(words, 0, static_cast<jsize>(p.words.size()), reinterpret_cast<jlong*>(p.words.data()));
        const nr::Mask m = nr::unpackBits(p);
        jbooleanArray a = env->NewBooleanArray(static_cast<jsize>(m.data.size()));
        if (a != nullptr && m.data.size() > 0) env->SetBooleanArrayRegion(a, 0, static_cast<jsize>(m.data.size()), reinterpret_cast<const jboolean*>(m.data.data()));
        return a;
    });
}

/** [ax, ay, runStart[0..h), runEnd[0..h)] */
BRIDGE(jintArray, kernelRuns)(JNIEnv* env, jclass, jbooleanArray data, jint w, jint h) {
    return guarded<jintArray>(env, nullptr, [&] {
        const auto src = fromJ(env, data);
        const nr::Kernel k(w, h, src.data());
        std::vector<int32_t> v{k.ax, k.ay};
        for (int32_t y = 0; y < h; y++) v.push_back(k.runStart[y]);
        for (int32_t y = 0; y < h; y++) v.push_back(k.runEnd[y]);
        return toJ(env, v);
    });
}

// ── 例外轉換（nr_jni_util.h：產品 JNI 用同一套）──────────────────────────────────

/** 在 guarded 裡丟 std::bad_alloc（0）、std::runtime_error（1）、其他（2）：Java 端要分別收到 OutOfMemoryError／IllegalStateException。 */
BRIDGE(void, throwCpp)(JNIEnv* env, jclass, jint kind) {
    guardedVoid(env, [&] {
        if (kind == 0) throw std::bad_alloc();
        if (kind == 1) throw std::runtime_error("nightread test");
        throw 42;
    });
}

/** C API 錯誤碼 → Java 例外（NR_E_NOMEM 要是 OutOfMemoryError）。 */
BRIDGE(void, throwForCode)(JNIEnv* env, jclass, jint code) { nr::jni::throwForCode(env, code, "nightread test"); }

// ── 像素 ─────────────────────────────────────────────────────────────────

BRIDGE(jintArray, genPixels)(JNIEnv* env, jclass, jint n, jlong seed) {
    return guarded<jintArray>(env, nullptr, [&] { return toJ(env, nrt::genPixels(n, static_cast<uint64_t>(seed))); });
}

BRIDGE(void, argbToGrayChroma)(JNIEnv* env, jclass, jintArray px, jintArray gray, jintArray chroma) {
    guardedVoid(env, [&] {
        const auto p = fromJ(env, px);
        std::vector<int32_t> g(p.size()), c(p.size());
        nr::argbToGrayChroma(p.data(), static_cast<int64_t>(p.size()), g.data(), c.data());
        env->SetIntArrayRegion(gray, 0, static_cast<jsize>(g.size()), reinterpret_cast<const jint*>(g.data()));
        env->SetIntArrayRegion(chroma, 0, static_cast<jsize>(c.size()), reinterpret_cast<const jint*>(c.data()));
    });
}

BRIDGE(jintArray, grayToArgb)(JNIEnv* env, jclass, jintArray g) {
    return guarded<jintArray>(env, nullptr, [&] {
        const auto v = fromJ(env, g);
        std::vector<int32_t> o(v.size());
        nr::grayToArgb(v.data(), static_cast<int64_t>(v.size()), o.data());
        return toJ(env, o);
    });
}

// ── 參數（params_gen.h；CppParamsParityTest）────────────────────────────────

namespace {

namespace P = nr::params;

/** 攤平的兩個陣列 → 結構；長度或值不合法回 false（不丟例外）。 */
bool paramsFrom(JNIEnv* env, jintArray ints, jdoubleArray dbls, P::NightReadParams* p) {
    const auto i = fromJ(env, ints);
    const auto d = fromJ(env, dbls);
    return P::fromFlat(i.data(), static_cast<int32_t>(i.size()), d.data(), static_cast<int32_t>(d.size()), p);
}

void paramsTo(JNIEnv* env, const P::NightReadParams& p, jintArray ints, jdoubleArray dbls) {
    std::vector<int32_t> i(P::kIntSlots);
    std::vector<double> d(P::kDoubleSlots);
    P::toFlat(p, i.data(), d.data());
    env->SetIntArrayRegion(ints, 0, P::kIntSlots, reinterpret_cast<const jint*>(i.data()));
    env->SetDoubleArrayRegion(dbls, 0, P::kDoubleSlots, d.data());
}

bool lengthsOk(JNIEnv* env, jintArray ints, jdoubleArray dbls) {
    return env->GetArrayLength(ints) == P::kIntSlots && env->GetArrayLength(dbls) == P::kDoubleSlots;
}

}  // namespace

BRIDGE(jlong, paramsSchema)(JNIEnv*, jclass) { return static_cast<jlong>(P::kSchemaHash); }
BRIDGE(jint, paramsFieldCount)(JNIEnv*, jclass) { return P::kFieldCount; }
BRIDGE(jint, paramsIntSlots)(JNIEnv*, jclass) { return P::kIntSlots; }
BRIDGE(jint, paramsDoubleSlots)(JNIEnv*, jclass) { return P::kDoubleSlots; }
BRIDGE(jint, paramsRulesVersion)(JNIEnv*, jclass) { return P::kRulesVersion; }

BRIDGE(jstring, paramsFieldName)(JNIEnv* env, jclass, jint idx) {
    if (idx < 0 || idx >= P::kFieldCount) { throwJava(env, "java/lang/IllegalArgumentException", "field index"); return nullptr; }
    return env->NewStringUTF(P::kFields[idx].name);
}

BRIDGE(jint, paramsFieldKind)(JNIEnv* env, jclass, jint idx) {
    if (idx < 0 || idx >= P::kFieldCount) { throwJava(env, "java/lang/IllegalArgumentException", "field index"); return -1; }
    return static_cast<jint>(P::kFields[idx].kind);
}

/** 規則版本摘要表：第 k 筆＝"<版本> <sha256> <delivered 0/1>"；k 超出範圍回 null。 */
BRIDGE(jstring, paramsRulesDigest)(JNIEnv* env, jclass, jint k) {
    if (k < 0 || k >= P::kRulesDigestCount) return nullptr;
    char buf[128];
    std::snprintf(buf, sizeof buf, "%d %s %d", P::kRulesDigests[k].version, P::kRulesDigests[k].sha256,
                  P::kRulesDigests[k].delivered ? 1 : 0);
    return env->NewStringUTF(buf);
}

/** C++ 預設建構的結構攤平（與 Kotlin 的 NightReadParams() 比）。 */
BRIDGE(void, paramsDefaults)(JNIEnv* env, jclass, jintArray ints, jdoubleArray dbls) {
    guardedVoid(env, [&] {
        if (!lengthsOk(env, ints, dbls)) { throwJava(env, "java/lang/IllegalArgumentException", "flat lengths"); return; }
        paramsTo(env, P::NightReadParams{}, ints, dbls);
    });
}

/** fromFlat 收不收（負面案例用）。 */
BRIDGE(jboolean, paramsAccepts)(JNIEnv* env, jclass, jintArray ints, jdoubleArray dbls) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        P::NightReadParams p;
        return paramsFrom(env, ints, dbls, &p) ? JNI_TRUE : JNI_FALSE;
    });
}

/** Kotlin → C++ → Kotlin：fromFlat 後再 toFlat 寫回 out*；fromFlat 拒收回 false。 */
BRIDGE(jboolean, paramsRoundTrip)(JNIEnv* env, jclass, jintArray ints, jdoubleArray dbls, jintArray outInts, jdoubleArray outDbls) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (!lengthsOk(env, outInts, outDbls)) { throwJava(env, "java/lang/IllegalArgumentException", "flat lengths"); return JNI_FALSE; }
        P::NightReadParams p;
        if (!paramsFrom(env, ints, dbls, &p)) return JNI_FALSE;
        paramsTo(env, p, outInts, outDbls);
        return JNI_TRUE;
    });
}

/**
 * Kotlin → C++（逐欄）：fromFlat 後用 getField（直接讀結構成員、不經槽位）取出每個欄位的值，依 kFields 順序寫進
 * outI（Int／Bool／Enum／OptInt 的值）、outD（Double／Float）、outPresent（OptInt 有沒有值；其餘 1）。fromFlat 拒收回 false。
 */
BRIDGE(jboolean, paramsGetAll)(JNIEnv* env, jclass, jintArray ints, jdoubleArray dbls, jlongArray outI, jdoubleArray outD,
                               jintArray outPresent) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        P::NightReadParams p;
        if (!paramsFrom(env, ints, dbls, &p)) return JNI_FALSE;
        std::vector<int64_t> vi(P::kFieldCount);
        std::vector<double> vd(P::kFieldCount);
        std::vector<int32_t> vp(P::kFieldCount);
        for (int32_t k = 0; k < P::kFieldCount; k++) {
            P::Value v{};
            if (!P::getField(p, k, &v)) throw std::runtime_error("getField");
            vi[k] = v.i;
            vd[k] = v.d;
            vp[k] = v.present ? 1 : 0;
        }
        env->SetLongArrayRegion(outI, 0, P::kFieldCount, reinterpret_cast<const jlong*>(vi.data()));
        env->SetDoubleArrayRegion(outD, 0, P::kFieldCount, vd.data());
        env->SetIntArrayRegion(outPresent, 0, P::kFieldCount, reinterpret_cast<const jint*>(vp.data()));
        return JNI_TRUE;
    });
}

/**
 * C++ → Kotlin（逐欄）：從預設結構開始，用 setField（直接寫結構成員）把第 k 個欄位設成 (inI[k], inD[k], inPresent[k])，
 * 再 toFlat 寫進 out*。某欄的值不合型別回 false。
 */
BRIDGE(jboolean, paramsSetAll)(JNIEnv* env, jclass, jlongArray inI, jdoubleArray inD, jintArray inPresent, jintArray outInts,
                               jdoubleArray outDbls) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        if (!lengthsOk(env, outInts, outDbls) || env->GetArrayLength(inI) != P::kFieldCount ||
            env->GetArrayLength(inD) != P::kFieldCount || env->GetArrayLength(inPresent) != P::kFieldCount) {
            throwJava(env, "java/lang/IllegalArgumentException", "lengths");
            return JNI_FALSE;
        }
        std::vector<int64_t> vi(P::kFieldCount);
        std::vector<double> vd(P::kFieldCount);
        std::vector<int32_t> vp(P::kFieldCount);
        env->GetLongArrayRegion(inI, 0, P::kFieldCount, reinterpret_cast<jlong*>(vi.data()));
        env->GetDoubleArrayRegion(inD, 0, P::kFieldCount, vd.data());
        env->GetIntArrayRegion(inPresent, 0, P::kFieldCount, reinterpret_cast<jint*>(vp.data()));
        P::NightReadParams p;
        for (int32_t k = 0; k < P::kFieldCount; k++) {
            if (!P::setField(&p, k, P::Value{vi[k], vd[k], vp[k] != 0})) return JNI_FALSE;
        }
        paramsTo(env, p, outInts, outDbls);
        return JNI_TRUE;
    });
}
