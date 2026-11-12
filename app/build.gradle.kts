import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.hyundai.rentalhelper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hyundai.rentalhelper"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // RENDY LLM intent routing. Defaults keep the rule-based engine. Values come only from a
        // Gradle -P flag or the git-ignored local.properties; a Gemini API key must never be set here.
        val rendyLocal = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        fun rendyProperty(name: String, default: String): String =
            (project.findProperty(name) as String?) ?: rendyLocal.getProperty(name) ?: default
        fun escapeForBuildConfig(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("boolean", "RENDY_LLM_ENABLED",
            rendyProperty("rendy.llm.enabled", "false").toBoolean().toString())
        buildConfigField("String", "RENDY_LLM_PROXY_ENDPOINT",
            "\"${escapeForBuildConfig(rendyProperty("rendy.llm.proxyEndpoint", ""))}\"")
        buildConfigField("String", "RENDY_LLM_PROXY_TOKEN",
            "\"${escapeForBuildConfig(rendyProperty("rendy.llm.proxyToken", ""))}\"")
        // Wake word: derive the grammar-only "lite G" Vosk model (same decisions, less native memory). See RendyWakeWordLiteGraph.
        buildConfigField("boolean", "RENDY_WAKEWORD_LITE_GRAPH",
            rendyProperty("rendy.wakeword.liteGraph", "true").toBoolean().toString())
        // Ready cue after confirmed STT capture: "off" (default) or "after_capture". See RendyListeningCue.
        buildConfigField("String", "RENDY_READY_CUE",
            "\"${escapeForBuildConfig(rendyProperty("rendy.voice.readyCue", "off"))}\"")
        buildConfigField("String", "RENDY_LLM_VERIFIED_INTENTS",
            "\"${escapeForBuildConfig(rendyProperty("rendy.llm.verifiedIntents", ""))}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Android Automotive OS (AAOS) Car API integration
    useLibrary("android.car")

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)

    // Pleos Connect SDK (현대 SDV / AAOS 차량 연결 SDK)
    // 실제 Pleos Connect SDK 배포 라이브러리가 로컬 AAR 또는 사내 Maven 저장소에 있을 경우 연결:
    // compileOnly(files("libs/pleos-connect-sdk-release.aar"))
    // implementation("com.pleos.connect:vehicle-sdk:1.0.0")

    // Automotive Car UI library (Optional, Google Car UI library)
    // implementation("androidx.car.app:app:1.4.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

