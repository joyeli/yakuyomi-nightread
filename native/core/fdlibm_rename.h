/*
 * 夜讀 C++ 核心：編 third_party/fdlibm 時以 -include 強制先讀的設定（fdlibm 原始碼保持 netlib 原樣、不改一個字）。
 *
 * 1. 小端：fdlibm.h 只認 i386 等舊巨集，x86_64／aarch64 都要自己定義 __LITTLE_ENDIAN，否則 __HI／__LO 取錯半邊。
 * 2. 改名：fdlibm 定義的全是 libm 的名字（sin、floor、scalbn…）。靜態連結（qemu 跑的 arm64 測試檔、主機測試）時會跟平台
 *    libm 撞名；動態連結時也可能被平台 libm 搶先解析。一律加 nr_fd_ 前綴，C++ 端只透過 core/jmath.h 的宣告呼叫。
 *    e_pow.c 對 y＝0.5 呼叫的 sqrt 也導到 fdlibm 自己的 e_sqrt.c（逐位元演算法，與正確捨入的硬體 sqrt 結果相同）。
 * 3. 只編 IEEE 核心（e_*.c 的 __ieee754_*），不編 w_*.c 包裝：Java 的 StrictMath 對應的就是核心的語意，包裝層的
 *    _LIB_VERSION／matherr 錯誤處理不在 Java 規格內。
 */
#ifndef NR_FDLIBM_RENAME_H
#define NR_FDLIBM_RENAME_H

#ifndef __LITTLE_ENDIAN
#define __LITTLE_ENDIAN
#endif

#define __ieee754_atan2 nr_fd_ieee754_atan2
#define __ieee754_exp nr_fd_ieee754_exp
#define __ieee754_pow nr_fd_ieee754_pow
#define __ieee754_rem_pio2 nr_fd_ieee754_rem_pio2
#define __ieee754_sqrt nr_fd_ieee754_sqrt
#define __kernel_cos nr_fd_kernel_cos
#define __kernel_sin nr_fd_kernel_sin
#define __kernel_rem_pio2 nr_fd_kernel_rem_pio2
#define atan nr_fd_atan
#define sin nr_fd_sin
#define cos nr_fd_cos
#define fabs nr_fd_fabs
#define scalbn nr_fd_scalbn
#define copysign nr_fd_copysign
#define floor nr_fd_floor
#define sqrt nr_fd_ieee754_sqrt

#endif
