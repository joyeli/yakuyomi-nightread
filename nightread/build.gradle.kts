// Yakuyomi fork 以 Gradle composite build（includeBuild）接此模組，靠 group:name 替換依賴
plugins {
    alias(libs.plugins.android.library)   // AGP 9 內建 Kotlin，不再套 kotlin-android
}

group = "li.joye.yakuyomi"
version = "0.1.0"

// 夜讀重繪核心：純 Kotlin 影像原語 + 分區重繪管線。**不依賴 android.graphics**（JVM 單元測試可跑，
// 拿 research/ 的 Python fixture 逐位元比對）；Bitmap 轉換交給呼叫端（engine / fork）。
android {
    namespace = "li.joye.yakuyomi.nightread"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

// parity 測試會印出逐頁的差異統計，沒有這段就只看得到「失敗」而看不到數字
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
