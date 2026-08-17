plugins {
    id("com.android.application")
    //id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lxmusic.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lxmusic.tv"
        minSdk = 23
        targetSdk = 37
        versionCode = 246
        versionName = "2.9.202608171355"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

//
    buildTypes {
        release {
            ndk {
                abiFilters.clear()
                abiFilters.add("arm64-v8a")
            }

            optimization {
                enable = true // Enables code and resource optimizations.
            }

            // ✅ 使用旧版 DSL 显式开启混淆和资源压缩
//            isMinifyEnabled = true
//            isShrinkResources = true
//
//            // ✅ 显式引入你原来的 proguard 规则文件（注意要用 proguard-android-optimize.txt）
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 禁用已知会导致 Lint 分析崩溃的检测器（Kotlin Analysis API 兼容性 bug）
        disable += "NullSafeMutableLiveData"
        // Lint 分析崩溃时不要中止构建
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")   // 1.9.x 由 Kotlin 2.0 编译（元数据 2.0.0）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")   // 1.7.x 由 Kotlin 2.0 编译（元数据 2.0.0）

    // Core Android
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.9.3")
    // 二维码生成（搜索页扫码推送文字，2.8）
    implementation("com.google.zxing:core:3.5.3")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    // Compose for TV (Leanback Compose)
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.1.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // Lifecycle & ViewModel Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")

    // ExoPlayer (legacy - matches current source code imports)
    implementation("com.google.android.exoplayer:exoplayer-core:2.19.1")
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    implementation("com.google.android.exoplayer:extension-okhttp:2.19.1")
    // 注：SimpleCache/CacheDataSource 在 exoplayer-datasource、StandaloneDatabaseProvider 在
    // exoplayer-database——均由 exoplayer-core 传递依赖引入，无需显式声明
    // （⚠️ 不存在 com.google.android.exoplayer:database 这个坐标，勿加）

    // Room Database
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // NanoHTTPD (lightweight HTTP server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QuickJS (JavaScript engine，参考 lx-music-mobile 的 whl quickjs-wrapper)
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")

    // OkHttp (network requests)
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // JSON parsing
    implementation("org.json:json:20260719")

    // Baseline Profile：将手写 baseline-prof.txt 编译进 APK 的 dexopt 规则，
    // 让 Compose Lazy 列表等热路径在 Android TV 上获得 AOT 预热，缓解滚动掉帧
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Debug dependencies
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}