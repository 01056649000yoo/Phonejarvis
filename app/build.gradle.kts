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
        versionCode = 24
        versionName = "0.9.1"
    }

    // 🔐 배포용 서명 키: 저장소에 두지 않고 GitHub Secrets → 빌드할 때만 환경 변수로 받음
    //   SSAMTOX_KEYSTORE(파일 경로), SSAMTOX_STORE_PASSWORD, SSAMTOX_KEY_ALIAS
    val keystorePath = System.getenv("SSAMTOX_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = file(keystorePath)
            storeType = "pkcs12"
            storePassword = System.getenv("SSAMTOX_STORE_PASSWORD")
            keyAlias = System.getenv("SSAMTOX_KEY_ALIAS") ?: "ssamtox"
            keyPassword = System.getenv("SSAMTOX_STORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            // 배포용: 디버깅 불가(다른 PC에 연결해도 앱 데이터를 꺼낼 수 없음)
            isDebuggable = false
            // 난독화(R8)는 온디바이스 AI 런타임(JNI)과의 호환을 확인한 뒤 켜기
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 배포 빌드 때 lint 경고로 빌드가 멈추지 않게 (검사는 따로)
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
