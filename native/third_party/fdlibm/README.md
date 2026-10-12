# fdlibm 5.3（netlib 原樣）

夜讀 C++ 核心的 `sin`／`cos`／`atan`／`atan2`／`exp`／`pow` 一律走這份 fdlibm，因為 Kotlin 版用的是 Java 的 `StrictMath`，而
`StrictMath` 的規格就是 fdlibm 的結果。平台 libm（glibc、bionic）在約 1–10% 的參數上跟 fdlibm 差 1 ulp（評估量測），不能用。

- 來源：<https://www.netlib.org/fdlibm/>（FDLIBM 5.3）。授權見同目錄 `LICENSE`（Sun 的寬鬆授權，保留聲明即可散布）。
- **檔案一字不改**。編譯設定在 `native/CMakeLists.txt`：
  - 以 `-include core/fdlibm_rename.h` 強制先讀：定義 `__LITTLE_ENDIAN`，把所有對外符號改名成 `nr_fd_*`（避免跟平台 libm 撞名）。
  - 只編 IEEE 核心（`e_*.c` 的 `__ieee754_*`、`s_sin.c`、`s_cos.c`、`s_atan.c` 與它們用到的 `k_*.c`、`s_*.c`），不編 `w_*.c` 包裝。
  - 與核心其餘部分同一組逐位元旗標（`-O2 -ffp-contract=off -fno-fast-math -fwrapv -fno-strict-aliasing -fno-builtin`）。
    `-fno-strict-aliasing` 對 fdlibm 是必要的：`__HI(x)`／`__LO(x)` 用指標轉型讀寫 double 的兩半（netlib readme 的
    「NOT FIXED YET 3」）。`-ffp-contract=off` 也是必要的：開了乘加合併，arm64 上六個函式在 0.04–0.6% 的隨機參數上差 1 ulp（評估 `fd_compare_summary.txt`）。
- 驗證：`nightread/src/test/kotlin/.../CppJmathParityTest.kt` 在同一個 JVM 裡逐位元比對 `StrictMath`；
  `native/tools/arm64_qemu.sh` 在 qemu-aarch64 上比對同一份 Java 答案。

| 檔案 | sha256（netlib 原檔） |
|---|---|
| fdlibm.h | 64113c472c2676499288bc35a821b0867916552930bc52c7445d2c50dddf0e71 |
| e_atan2.c | 61dbf9edc927468280ccde872ecfb7cb1ea7cbaf6e1b28e7021c968abe5f7c6e |
| e_exp.c | 04c96195b7ced914d659b5fd204724c182f091af8631fe42016ad6e27cb302ef |
| e_pow.c | 763b86baa63eb3518b43f7f3e44e5ed10992b62650f4a45c3a2ea6015e6d5122 |
| e_rem_pio2.c | 3a08dd4a38f9f9fc9478a33ca05cb81a6566f1010ab14412e97cae091bede16b |
| e_sqrt.c | 2a6e784d1089eb6351df7e59037de4240d11a5c61967611732f81ad96fe869ce |
| k_cos.c | 841086c75288f3f9fef03bae2a9dc24e99c3cdb2035e170c69452013a7b5befc |
| k_rem_pio2.c | 5796209ea3fe1af88e22badeaed0f6a6aa923914e097e227c410527cb5815740 |
| k_sin.c | 75d391f3c124806ce14893cd038ae5dd10663e40430ffa174d5fddcb33012167 |
| s_atan.c | 8583c253aed6318dee2a48135e3f3b1328d838a390372a69414ac41eb5ce5ce1 |
| s_copysign.c | 459c18858c944a4e7062d38ab4dfb596edbe76706d24029c2dac766dcd2be3fd |
| s_cos.c | bdcbea3c83b2d4aa521b9a5341f3e58b8cb9c8d326fcc4abb715f3704cf17799 |
| s_fabs.c | c6389f3e0472ec0157d648b3e4e80b405038acfaf3e85b961363ea483e3259f1 |
| s_floor.c | fbe8779a58d46879222ba6b390931b13118037c5123b8e89a73cf0231f643b4a |
| s_scalbn.c | 9ad1bb374fc2951ec086474c82373b45bccfdecd34d631974dccf8633f40d99e |
| s_sin.c | 4d5edb75368e4f8e0ac18682d3ae4b6944ba06801637cf0df6f378d6211f4c81 |
