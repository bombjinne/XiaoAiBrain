plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoaibrain"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiaoaibrain"
        minSdk = 24
        targetSdk = 34
        versionCode = 28
        versionName = "1.0.28"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 8.x 默认关闭 BuildConfig 生成，需显式开启，否则 BuildConfig.VERSION_NAME 不可用
    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Xposed API 只在编译期提供，运行期由 LSPosed 注入。
    // 直接引用本地 libs/api-82.jar，避免依赖专用仓库（api.xposed.info）在受限网络下解析失败。
    compileOnly(files("libs/api-82.jar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
