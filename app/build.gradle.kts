plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 37

        // CI 构建时通过 -PCI_BUILD_ID=<github.run_id> 注入，用于自动更新版本对比。
        // 本地手动构建时为 0。
        val ciBuildId = (project.findProperty("CI_BUILD_ID") as String?)?.toLongOrNull() ?: 0L
        buildConfigField("long", "CI_BUILD_ID", ciBuildId.toString())

        // versionCode 必须随 CI 递增，否则新版 APK（不同签名）无法覆盖安装旧版：
        // Android 对「相同版本号 + 不同签名」一律判为签名冲突，拒绝覆盖。
        //
        // CI 构建时 versionCode 优先取 -PCI_VERSION_CODE=<github.run_number>：
        // run_number 是仓库内严格递增的小整数，保证每次 Release 版本号必然变大。
        // 注意不能用 run_id 直接 toInt()——run_id 是 31 位的超大数，截断为低 32 位
        // 可能变负数或与旧版本号不单调，导致构建失败或仍无法覆盖安装。
        // 本地手动构建时为 1。
        val ciVersionCode = (project.findProperty("CI_VERSION_CODE") as String?)?.toLongOrNull() ?: 0L
        if (ciVersionCode in 1..Int.MAX_VALUE) {
            versionCode = ciVersionCode.toInt()
            versionName = "1.0.$ciBuildId"
        } else if (ciBuildId > 0L) {
            // 兼容旧 workflow（未传 CI_VERSION_CODE）：退化为 run_id 低 32 位。
            versionCode = ciBuildId.toInt()
            versionName = "1.0.$ciBuildId"
        } else {
            versionCode = 1
            versionName = "1.0"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// 单元测试固定跑在 Java 17 上（Gradle 通过 foojay 自动获取工具链）。
// 若沿用 daemon JVM（Android Studio 自带 JBR 25），Robolectric 的 ASM 无法解析新版字节码，
// 会报 "Unsupported class file major version 69"。Robolectric 稳定支持 JDK 17。
tasks.withType<Test>().configureEach {
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    // JVM 单元测试：真实 org.json 实现（默认 android.jar 里的是抛异常的 stub）
    testImplementation(libs.json)
    // SettingsStore 依赖 SharedPreferences，用 Robolectric 在 JVM 上模拟 Android 运行时
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}