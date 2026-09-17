// Yakuyomi fork 以 Gradle composite build（includeBuild）接此模組，靠 group:name 替換依賴
plugins {
    alias(libs.plugins.android.library)
}

group = "li.joye.yakuyomi"
version = "0.1.0"

// 人物語意遮罩的上機推論：ONNX Runtime 跑 yoloseg 與 cseg，輸出餵給 :nightread 的管線。
// 刻意與 :nightread 分開——核心管線不依賴任何推論框架，JVM 測試才跑得起來。
android {
    namespace = "li.joye.yakuyomi.nightread.ort"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":nightread"))
    implementation(libs.onnxruntime.android)
}
