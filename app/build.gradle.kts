plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freezer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freezer.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Shizuku 仅用于一次性激活设备所有者 / 兜底冻结，不参与日常 UI
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
