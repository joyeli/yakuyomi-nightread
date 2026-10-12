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
    defaultConfig {
        minSdk = 26
        // NightNative 的 native 方法名要留著（JNI 依名字找符號）；隨 AAR 帶給使用這個函式庫的 app
        consumerProguardFiles("consumer-rules.pro")
    }
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
    // -PtestHeap＝覆寫（例如本機大語料的錄出：-PtestGroup=dump -PtestHeap=8g）
    maxHeapSize = providers.gradleProperty("testHeap").orNull ?: "1g"
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        // CI 紀錄裡要看得到斷言訊息與堆疊（預設 SHORT 只有例外類別），不然失敗時得下載報告才知道差在哪
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // 夜讀 C++ 核心的同 JVM 差分（Cpp*ParityTest）：-PnativeLib=<主機 .so>（native/tools/build_host.sh 產生）才跑，沒給就略過。
    // 給了就要求一定載得起來（requireNative），避免「全部略過」被當成通過。-PnativeCases＝每個純量函式的案例數（預設 200 萬）；
    // -PgoldenOut=<目錄>＝另外寫 golden 檔給 qemu-aarch64 上的 arm64 檢查（native/tools/arm64_qemu.sh）。
    // .so 的內容與 golden 目錄要登記成任務的輸入／輸出：Gradle 判斷要不要重跑只看類別路徑與系統屬性的字串，不登記的話
    // 改了 C++、重建 .so 之後再跑同一行指令會被判成 UP-TO-DATE（一個差分測試都沒跑卻印 BUILD SUCCESSFUL），刪掉 golden 也不會重寫。
    providers.gradleProperty("nativeLib").orNull?.let {
        val lib = rootProject.file(it)
        systemProperty("nightread.nativeLib", lib.absolutePath)
        systemProperty("nightread.requireNative", "true")
        inputs.file(lib).withPathSensitivity(PathSensitivity.NONE).withPropertyName("nativeLib")
    }
    providers.gradleProperty("nativeCases").orNull?.let { systemProperty("nightread.nativeCases", it) }
    providers.gradleProperty("goldenOut").orNull?.let {
        val dir = rootProject.file(it)
        systemProperty("nightread.goldenOut", dir.absolutePath)
        outputs.dir(dir).withPropertyName("goldenOut")
    }

    // CI 分組（.github/workflows/ci.yml 的 matrix 各跑一組、平行）：-PtestGroup=<組>。幾支整頁的重測試各自成組，
    // 其餘全部歸 fast——新加的測試類別不用登記就落在 fast，不會漏跑。C++ 差分一律命名 Cpp*：native 組用萬用字元收、fast 組排除，
    // 新加的差分測試不用改這裡；用到原生庫卻沒取 Cpp 開頭的類別，在其他組會直接失敗（NativeLib.assumeLoaded），不會被略過。
    // 每組列名的類別在跑完後檢查真的跑到了（Gradle 只在整個篩選一個測試都沒跑到時才失敗，組裡有好幾個類別時改名一個不會報錯）。
    // 沒給 testGroup（本機的平常用法）＝全部跑。
    providers.gradleProperty("testGroup").orNull?.let { group ->
        val heavy = mapOf(
            "guard" to listOf("RulesVersionGuardTest", "SharedTierTest"),
            "parallel" to listOf("ParallelRenderTest"),
            "profile" to listOf("ProfileTest"),
            // C++ 差分：要先建主機 .so（CI 的 native job），所以不放 fast
            "native" to listOf("Cpp*"),
        )
        // 一定要跑到的類別：列名的直接算；native 另列現有的四個（萬用字元收不到任何一個時也會在這裡失敗）
        val mustRun = heavy.mapValues { (_, v) -> v.filter { '*' !in it } } +
            ("native" to listOf("CppJmathParityTest", "CppTablesParityTest", "CppPageParityTest", "CppParamsParityTest"))
        systemProperty("nightread.testGroup", group)
        // native 組一定要真的載到原生庫（沒給 -PnativeLib 就失敗，不是略過）
        if (group == "native") systemProperty("nightread.requireNative", "true")
        filter {
            when (group) {
                "fast" -> heavy.values.flatten().forEach { excludeTestsMatching("li.joye.yakuyomi.nightread.$it") }
                in heavy -> heavy.getValue(group).forEach { includeTestsMatching("li.joye.yakuyomi.nightread.$it") }
                else -> error("未知的 testGroup：$group（可用 fast、${heavy.keys.joinToString("、")}）")
            }
        }
        val required = mustRun[group].orEmpty()
        if (required.isNotEmpty()) {
            val seen = HashSet<String>()
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
                override fun beforeTest(testDescriptor: TestDescriptor) {}
                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                    testDescriptor.className?.let { synchronized(seen) { seen += it.substringAfterLast('.') } }
                }
            })
            doLast {
                val missing = synchronized(seen) { required.filter { it !in seen } }
                if (missing.isNotEmpty()) {
                    throw GradleException("testGroup=$group：這些類別一個測試都沒跑到：$missing（改名了就要改 build.gradle.kts 的分組）")
                }
            }
        }
    }
}
