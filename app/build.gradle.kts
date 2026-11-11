plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.phone"
        minSdk = 31          // Galaxy Z Fold4 (Android 14+) 기준
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.1"
    }

    // 개인용 고정 서명 키: 빌드마다 같은 서명 → 앱을 지우지 않고 업데이트 설치 가능
    signingConfigs {
        create("personal") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis1234"
            keyAlias = "jarvis"
            keyPassword = "jarvis1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    // 온디바이스 LLM 런타임 (Gemma 4 .litertlm 실행)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
