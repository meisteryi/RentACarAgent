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
    }
}

dependencies {
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

