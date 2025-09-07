plugins {
    id("com.android.application")
    id("android")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp").version("1.9.22-1.0.17")
}

apply(from = "scan.gradle")

android {
    namespace = "com.xslczx.vdownload"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xslczx.vdownload"
        minSdk = 26
        targetSdk = 32
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            lint {
                checkReleaseBuilds = false // 不在 release build 里强制跑 lint
                abortOnError = false       // 即便有错误也不失败
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation("io.github.h07000223:flycoTabLayout:3.0.0")
    implementation("com.blankj:utilcodex:1.31.1")

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.14.0")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.14.0")

    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.kongzue.dialogx:DialogX:0.0.49")
}