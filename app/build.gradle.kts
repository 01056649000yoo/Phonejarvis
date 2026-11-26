plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.phone"   // 2026-11-25: com.ssamtox 로 바꿨다가 되돌림 — 새 패키지는 첫 설치라 Play 프로텍트 '앱 차단됨'(알림 접근 앱)에 걸리고, 지금 쓰는 선생님들이 갈아타야 해서
        minSdk = 31          // Galaxy Z Fold4 (Android 14+) 기준
        targetSdk = 36
        versionCode = 93
        versionName = "1.18.0"
        // 🏷 빌드(=업데이트) 시각 — CI 가 BUILD_TIME(ms)을 넣음. 앱에서 'v1.14.0 · 11월 25일 22:53 업데이트'로 보임(2026-11-25 선생님)
        buildConfigField("long", "BUILD_TIME", "${System.getenv("BUILD_TIME")?.takeIf { it.all(Char::isDigit) } ?: "0"}L")
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
        buildConfig = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

android.testOptions.unitTests.isReturnDefaultValues = true
android.testOptions.unitTests.all { test ->
    // 시뮬레이션: OLLAMA_MODEL=gemma4:e2b ./gradlew testDebugUnitTest — 없으면 건너뜀
    test.environment("OLLAMA_MODEL", System.getenv("OLLAMA_MODEL") ?: "")
    test.testLogging { showStandardStreams = true }
}

dependencies {
    // 온디바이스 LLM 런타임 (Gemma 4 .litertlm 실행)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // 🏠 홈 화면 위젯(Compose 와 같은 방식)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")   // 위젯도 쌤톡스 색(앱과 같은 colorScheme)
    // 맥미니에서 요약 규칙 시험·시뮬레이션(src/test)
    testImplementation("junit:junit:4.13.2")
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
