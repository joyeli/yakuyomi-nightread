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
    // 測試 JVM 的 heap（Gradle 預設 512 MB）：ParallelRenderTest 在 demo04（7.2 MPx）上開 4 條頁內池，尖峰約 430 MB 再加上留著比對
    // 的依序版成品，512 MB 是擦邊——2026-10-07 OOM 過一次（之前兩輪都過）。產品的記憶體估算另有最低可跑 heap 的量測（DECISIONS），
    // 不靠這個上限。
    maxHeapSize = "1g"
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        // CI 紀錄裡要看得到斷言訊息與堆疊（預設 SHORT 只有例外類別），不然失敗時得下載報告才知道差在哪
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // CI 分組（.github/workflows/ci.yml 的 matrix 各跑一組、平行）：-PtestGroup=<組>。幾支整頁的重測試各自成組，
    // 其餘全部歸 fast——新加的測試類別不用登記就落在 fast，不會漏跑；重組裡的類別改名時 include 找不到測試會直接失敗。
    // 沒給 testGroup（本機的平常用法）＝全部跑。
    providers.gradleProperty("testGroup").orNull?.let { group ->
        val heavy = mapOf(
            "guard" to listOf("RulesVersionGuardTest", "SharedTierTest"),
            "parallel" to listOf("ParallelRenderTest"),
            "profile" to listOf("ProfileTest"),
        )
        filter {
            when (group) {
                "fast" -> heavy.values.flatten().forEach { excludeTestsMatching("li.joye.yakuyomi.nightread.$it") }
                in heavy -> heavy.getValue(group).forEach { includeTestsMatching("li.joye.yakuyomi.nightread.$it") }
                else -> error("未知的 testGroup：$group（可用 fast、${heavy.keys.joinToString("、")}）")
            }
        }
    }
}
