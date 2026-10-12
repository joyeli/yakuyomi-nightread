# 夜讀 C++ 核心的 JNI 綁定（NightNative）：保留類別名與 native 方法名，否則 R8 改名後 JNI 依名字找不到
# Java_li_joye_yakuyomi_nightread_NightNative_* 符號，runtime 丟 UnsatisfiedLinkError（available() 會回 false、默默退回 Kotlin 版）。
-keepclasseswithmembernames,includedescriptorclasses class li.joye.yakuyomi.nightread.NightNative {
    native <methods>;
}
