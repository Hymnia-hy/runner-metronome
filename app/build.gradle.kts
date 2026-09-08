import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名口令优先从 local.properties（已 gitignore）读取，避免硬编码进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.runmetronome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.runmetronome"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.12"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD") ?: "REDACTED"
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: "REDACTED"
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD") ?: "REDACTED"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    testImplementation("junit:junit:4.13.2")
}
