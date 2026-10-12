// 夜讀 JNI 共用小工具：丟 Java 例外、把 C++ 例外擋在 JNI 邊界（不讓它穿過 JNI 讓 app 直接終止）。
#pragma once

#include <jni.h>

#include <exception>
#include <new>

#include "nightread/nr.h"

#define NR_JNI_EXPORT extern "C" JNIEXPORT

namespace nr {
namespace jni {

/** 丟一個 Java 例外；已經有例外在等著就不再丟（JNI 規定）。 */
inline void throwJava(JNIEnv* env, const char* cls, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass c = env->FindClass(cls);
    if (c != nullptr) {
        env->ThrowNew(c, msg);
        env->DeleteLocalRef(c);
    }
}

/** C API 錯誤碼 → Java 例外。NR_OK 不丟。回傳是否丟了。 */
inline bool throwForCode(JNIEnv* env, int32_t code, const char* what) {
    switch (code) {
        case NR_OK: return false;
        case NR_E_ARG: throwJava(env, "java/lang/IllegalArgumentException", what); return true;
        case NR_E_NOMEM: throwJava(env, "java/lang/OutOfMemoryError", what); return true;
        case NR_E_STATE: throwJava(env, "java/lang/IllegalStateException", what); return true;
        case NR_E_CANCELLED: throwJava(env, "java/util/concurrent/CancellationException", what); return true;
        default: throwJava(env, "java/lang/IllegalStateException", what); return true;
    }
}

/**
 * 跑 f()，把 C++ 例外轉成 Java 例外：std::bad_alloc → java.lang.OutOfMemoryError（沿用 app 既有的每頁 OOM 補跑路徑），
 * 其他 → java.lang.IllegalStateException。例外時回 fallback。
 */
template <class R, class F>
R guarded(JNIEnv* env, R fallback, F&& f) {
    try {
        return f();
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "nightread: native allocation failed");
    } catch (const std::exception& e) {
        throwJava(env, "java/lang/IllegalStateException", e.what());
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "nightread: unknown native error");
    }
    return fallback;
}

template <class F>
void guardedVoid(JNIEnv* env, F&& f) {
    guarded<int>(env, 0, [&] { f(); return 0; });
}

/** 陣列區段 [offset, offset + (rows−1)·stride + w) 是否在長度 len 之內（rows ≥ 0、stride ≥ w ≥ 1）。 */
inline bool spanFits(jsize len, jint offset, jint rows, jint stride, int32_t w) {
    if (offset < 0 || rows < 0 || stride < w) return false;
    if (rows == 0) return offset <= len;
    const long long need = static_cast<long long>(offset) + static_cast<long long>(rows - 1) * stride + w;
    return need <= len;
}

}  // namespace jni
}  // namespace nr
