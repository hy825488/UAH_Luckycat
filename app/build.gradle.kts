plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdk = 34

    defaultConfig {
        applicationId = "com.luckycat.fp"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "11.0.0-realfp"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 簽名，方便 LSPosed 直接安裝
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    namespace = "com.luckycat.fp"
}

dependencies {
    // Xposed API (透過 JitPack，比已關閉的 JCenter 穩定)
    compileOnly("de.robv.android.xposed:api:82")
    
    // OkHttp3 (compileOnly，運行時由目標 App 提供)
    compileOnly("com.squareup.okhttp3:okhttp:4.9.3")
}
