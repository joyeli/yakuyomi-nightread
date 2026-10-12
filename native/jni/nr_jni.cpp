// 夜讀 C++ 核心的 JNI（對應 nightread/src/main/kotlin/li/joye/yakuyomi/nightread/NightNative.kt）。
//
// - 只用 Java 陣列（int[]）傳像素；不碰 Bitmap（不連 jnigraphics），Bitmap 的 getPixels／setPixels 留在 Kotlin 分條做。
// - 陣列用 GetPrimitiveArrayCritical 直接取（不複製）；持有期間不呼叫任何 JNI 函式，放掉之後才丟例外。
// - handle 是 nr_page* 轉成 jlong；0 視為不合法。
// - C++ 例外不穿過 JNI：std::bad_alloc → java.lang.OutOfMemoryError（nr_jni_util.h）。
// - 這個檔案由引擎的 CMake 編進唯一的 libyakuyomi_ncnn.so（只匯出 JNI 符號）；本 repo 的主機測試則編進 libnightread_host.so。
#include <jni.h>

#include "nightread/nr.h"
#include "nr_jni_util.h"

using nr::jni::guarded;
using nr::jni::guardedVoid;
using nr::jni::spanFits;
using nr::jni::throwForCode;
using nr::jni::throwJava;

namespace {

nr_page* page(JNIEnv* env, jlong h) {
    if (h == 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "nightread: null page handle");
        return nullptr;
    }
    return reinterpret_cast<nr_page*>(static_cast<intptr_t>(h));
}

}  // namespace

NR_JNI_EXPORT jint JNICALL Java_li_joye_yakuyomi_nightread_NightNative_abi(JNIEnv*, jclass) { return nr_abi(); }

NR_JNI_EXPORT jlong JNICALL Java_li_joye_yakuyomi_nightread_NightNative_paramsSchema(JNIEnv*, jclass) {
    return nr_params_schema();
}

NR_JNI_EXPORT jlong JNICALL Java_li_joye_yakuyomi_nightread_NightNative_create(JNIEnv* env, jclass, jint w, jint h) {
    return guarded<jlong>(env, 0, [&]() -> jlong {
        nr_page* p = nullptr;
        if (throwForCode(env, nr_create(w, h, &p), "nightread: create failed")) return 0;
        return static_cast<jlong>(reinterpret_cast<intptr_t>(p));
    });
}

NR_JNI_EXPORT void JNICALL Java_li_joye_yakuyomi_nightread_NightNative_free(JNIEnv*, jclass, jlong h) {
    nr_free(reinterpret_cast<nr_page*>(static_cast<intptr_t>(h)));
}

NR_JNI_EXPORT void JNICALL Java_li_joye_yakuyomi_nightread_NightNative_putRows(JNIEnv* env, jclass, jlong h, jint y0, jint rows,
                                                                          jintArray argb, jint offset, jint stride) {
    guardedVoid(env, [&] {
        nr_page* p = page(env, h);
        if (p == nullptr) return;
        if (argb == nullptr || !spanFits(env->GetArrayLength(argb), offset, rows, stride, nr_width(p))) {
            throwJava(env, "java/lang/IllegalArgumentException", "nightread: putRows array span out of bounds");
            return;
        }
        auto* a = static_cast<jint*>(env->GetPrimitiveArrayCritical(argb, nullptr));
        if (a == nullptr) return;   // 已有 OutOfMemoryError 在等
        const int32_t rc = nr_put_rows(p, y0, rows, reinterpret_cast<const int32_t*>(a) + offset, stride);
        env->ReleasePrimitiveArrayCritical(argb, a, JNI_ABORT);
        throwForCode(env, rc, "nightread: putRows failed");
    });
}

NR_JNI_EXPORT void JNICALL Java_li_joye_yakuyomi_nightread_NightNative_getRows(JNIEnv* env, jclass, jlong h, jint plane, jint y0,
                                                                          jint rows, jintArray out, jint offset, jint stride) {
    guardedVoid(env, [&] {
        nr_page* p = page(env, h);
        if (p == nullptr) return;
        if (out == nullptr || !spanFits(env->GetArrayLength(out), offset, rows, stride, nr_width(p))) {
            throwJava(env, "java/lang/IllegalArgumentException", "nightread: getRows array span out of bounds");
            return;
        }
        auto* a = static_cast<jint*>(env->GetPrimitiveArrayCritical(out, nullptr));
        if (a == nullptr) return;
        const int32_t rc = nr_get_rows(p, plane, y0, rows, reinterpret_cast<int32_t*>(a) + offset, stride);
        env->ReleasePrimitiveArrayCritical(out, a, 0);
        throwForCode(env, rc, "nightread: getRows failed");
    });
}

NR_JNI_EXPORT jlong JNICALL Java_li_joye_yakuyomi_nightread_NightNative_peakBytes(JNIEnv* env, jclass, jlong h) {
    nr_page* p = page(env, h);
    return p == nullptr ? 0 : nr_peak_bytes(p);
}

NR_JNI_EXPORT void JNICALL Java_li_joye_yakuyomi_nightread_NightNative_cancel(JNIEnv* env, jclass, jlong h) {
    nr_page* p = page(env, h);
    if (p != nullptr) nr_cancel(p);
}
