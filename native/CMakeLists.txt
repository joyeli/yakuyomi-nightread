# 夜讀 C++ 核心（native/）。同一份給兩種建置：
#   - Android：由引擎 inference-core 的 CMake add_subdirectory 進來，nightread_core＋nightread_jni 編進唯一的 libyakuyomi_ncnn.so
#     （「引擎只有一個 .so」；只匯出 JNI，由引擎那邊的 -Wl,--exclude-libs,ALL 與可見度 hidden 保證）。
#   - 主機（x86_64 Linux／WSL）：本 repo 的原生測試（ctest）與 JUnit 用的 libnightread_host.so（同 JVM 差分）。
#   - NDK arm64 靜態測試檔：在 qemu-aarch64 上跑數值核心（native/tools/arm64_qemu.sh）。
#
# 逐位元規則（cpp-eval 報告第 5、7 節；docs/DECISIONS.md）：
#   -O2                    強制。target 的編譯選項排在 CMAKE_CXX_FLAGS(_DEBUG) 之後，蓋掉 AGP Debug 的 -O0
#                          （-O0 的 C++ 在 debug APK 會比 Kotlin 還慢）；tools/check_compile_flags.py 檢查。
#   -ffp-contract=off      不准把 a*b+c 合成 FMA（NDK clang 預設會合；arm64 上 fdlibm 與格溝找線段都會差 1 ulp）。
#   -fno-fast-math         不准重排浮點運算。
#   -fwrapv                有號整數溢位照 Java 環繞。
#   -fno-strict-aliasing   fdlibm 用指標轉型讀寫 double 的兩半。
#   -fno-builtin           不讓編譯器把數學呼叫換成它自己的版本（超越函數一律走 third_party/fdlibm）。
#   arm64 只用 armv8-a 基本指令（不開 dotprod、fp16 等擴充），不連 OpenMP，不開執行緒。
cmake_minimum_required(VERSION 3.22.1)
project(nightread_native C CXX)

option(NR_BUILD_TESTS "建原生測試（ctest）與 golden_check" ${PROJECT_IS_TOP_LEVEL})
option(NR_HOST_JNI "主機上建 libnightread_host.so（JUnit 同 JVM 差分用）" ${PROJECT_IS_TOP_LEVEL})
option(NR_STATIC_TESTS "測試執行檔靜態連結（NDK arm64 給 qemu-aarch64 跑）" OFF)
set(NR_SANITIZE "" CACHE STRING "sanitizer 清單，例如 address,undefined 或 thread（只給主機測試用）")
set(NR_GOLDEN_DIR "" CACHE PATH "JUnit 寫出的 golden 目錄；有給就加 nr_golden 這個 ctest")

if(PROJECT_IS_TOP_LEVEL)
  set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
endif()

set(NR_BITEXACT_FLAGS -O2 -ffp-contract=off -fno-fast-math -fwrapv -fno-strict-aliasing -fno-builtin)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
  list(APPEND NR_BITEXACT_FLAGS -march=armv8-a)
endif()

if(NR_SANITIZE)
  add_compile_options(-fsanitize=${NR_SANITIZE} -fno-omit-frame-pointer -fno-sanitize-recover=all -g)
  add_link_options(-fsanitize=${NR_SANITIZE})
endif()

# ── fdlibm 5.3（netlib 原檔，一字不改；改名與小端設定由強制引入的標頭負責）────────────
set(NR_FDLIBM_SOURCES
  third_party/fdlibm/e_atan2.c third_party/fdlibm/e_exp.c third_party/fdlibm/e_pow.c third_party/fdlibm/e_rem_pio2.c
  third_party/fdlibm/e_sqrt.c third_party/fdlibm/k_cos.c third_party/fdlibm/k_rem_pio2.c third_party/fdlibm/k_sin.c
  third_party/fdlibm/s_atan.c third_party/fdlibm/s_copysign.c third_party/fdlibm/s_cos.c third_party/fdlibm/s_fabs.c
  third_party/fdlibm/s_floor.c third_party/fdlibm/s_scalbn.c third_party/fdlibm/s_sin.c)
add_library(nightread_fdlibm OBJECT ${NR_FDLIBM_SOURCES})
set_target_properties(nightread_fdlibm PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
target_compile_options(nightread_fdlibm PRIVATE ${NR_BITEXACT_FLAGS} -w
  -include ${CMAKE_CURRENT_SOURCE_DIR}/core/fdlibm_rename.h)

# ── 核心 ─────────────────────────────────────────────────────────────────
add_library(nightread_core STATIC
  core/jmath.cpp core/types.cpp core/tables.cpp core/convert.cpp core/page.cpp core/params.cpp
  $<TARGET_OBJECTS:nightread_fdlibm>)
target_include_directories(nightread_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
set_target_properties(nightread_core PROPERTIES
  POSITION_INDEPENDENT_CODE ON CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF
  CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_compile_options(nightread_core PRIVATE ${NR_BITEXACT_FLAGS} -fexceptions -Wall -Wextra)

# ── JNI ─────────────────────────────────────────────────────────────────
# Android：jni.h 在 NDK sysroot。主機：從 JAVA_HOME（或 -DNR_JAVA_HOME）找標頭；只要標頭，不連 libjvm。
set(NR_HAVE_JNI OFF)
if(ANDROID)
  set(NR_HAVE_JNI ON)
else()
  set(NR_JAVA_HOME "$ENV{JAVA_HOME}" CACHE PATH "主機 JDK（取 jni.h）")
  find_path(NR_JNI_INCLUDE jni.h HINTS "${NR_JAVA_HOME}/include" NO_CMAKE_SYSTEM_PATH)
  if(NR_JNI_INCLUDE)
    set(NR_HAVE_JNI ON)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
      set(NR_JNI_MD "${NR_JNI_INCLUDE}/linux")
    elseif(APPLE)
      set(NR_JNI_MD "${NR_JNI_INCLUDE}/darwin")
    endif()
  else()
    message(STATUS "nightread: 找不到 jni.h（設 JAVA_HOME 或 -DNR_JAVA_HOME），不建 JNI 目標")
  endif()
endif()

if(NR_HAVE_JNI)
  add_library(nightread_jni OBJECT jni/nr_jni.cpp)
  set_target_properties(nightread_jni PROPERTIES
    POSITION_INDEPENDENT_CODE ON CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
  target_compile_options(nightread_jni PRIVATE ${NR_BITEXACT_FLAGS} -fexceptions -Wall -Wextra)
  target_link_libraries(nightread_jni PUBLIC nightread_core)
  if(NOT ANDROID)
    target_include_directories(nightread_jni PRIVATE ${NR_JNI_INCLUDE} ${NR_JNI_MD})
  endif()
endif()

# ── 測試 ─────────────────────────────────────────────────────────────────
if(NR_BUILD_TESTS)
  enable_testing()
  find_package(Threads REQUIRED)

  add_library(nightread_testcases STATIC test/cases.cpp)
  set_target_properties(nightread_testcases PROPERTIES POSITION_INDEPENDENT_CODE ON CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
  # 產生器也用逐位元旗標：輸入要在 x86_64 與 arm64 上一模一樣
  target_compile_options(nightread_testcases PRIVATE ${NR_BITEXACT_FLAGS} -fexceptions)
  target_link_libraries(nightread_testcases PUBLIC nightread_core)

  foreach(t nr_core_tests nr_golden_check nr_dump_check)
    if(t STREQUAL "nr_core_tests")
      add_executable(${t} test/test_main.cpp)
    elseif(t STREQUAL "nr_golden_check")
      add_executable(${t} test/golden_check.cpp)
    else()
      # Kotlin 錄出器（NativeDumpTest）的錄出：驗格式、重放已搬到 C++ 的部分（native/README.md）
      add_executable(${t} test/dump_check.cpp)
    endif()
    set_target_properties(${t} PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
    target_compile_options(${t} PRIVATE ${NR_BITEXACT_FLAGS} -fexceptions -Wall)
    target_link_libraries(${t} PRIVATE nightread_testcases nightread_core Threads::Threads)
    if(NR_STATIC_TESTS)
      target_link_options(${t} PRIVATE -static)
    endif()
  endforeach()

  add_test(NAME nr_core_tests COMMAND nr_core_tests)
  if(NR_GOLDEN_DIR)
    add_test(NAME nr_golden COMMAND nr_golden_check ${NR_GOLDEN_DIR})
  endif()
  set(NR_DUMP_DIR "" CACHE PATH "NativeDumpTest 的錄出目錄；有給就加 nr_dump 這個 ctest")
  if(NR_DUMP_DIR)
    add_test(NAME nr_dump COMMAND nr_dump_check ${NR_DUMP_DIR})
  endif()

  # Android：照引擎的方式（核心＋JNI 物件、-Wl,--exclude-libs,ALL）連一個 .so，給 fma_scan.sh 檢查只匯出 JNI 符號
  if(ANDROID AND NR_HAVE_JNI)
    add_library(nightread_export_probe SHARED $<TARGET_OBJECTS:nightread_jni>)
    target_link_libraries(nightread_export_probe PRIVATE nightread_core)
    target_link_options(nightread_export_probe PRIVATE -Wl,--exclude-libs,ALL)
  endif()

  # JUnit 用的主機 .so：產品 JNI＋測試橋（test/test_jni.cpp）＋核心，一個檔
  if(NR_HOST_JNI AND NR_HAVE_JNI AND NOT ANDROID)
    add_library(nightread_host SHARED test/test_jni.cpp $<TARGET_OBJECTS:nightread_jni>)
    set_target_properties(nightread_host PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON CXX_VISIBILITY_PRESET hidden)
    target_compile_options(nightread_host PRIVATE ${NR_BITEXACT_FLAGS} -fexceptions -Wall)
    target_include_directories(nightread_host PRIVATE ${NR_JNI_INCLUDE} ${NR_JNI_MD})
    target_link_libraries(nightread_host PRIVATE nightread_testcases nightread_core)
  endif()
endif()
