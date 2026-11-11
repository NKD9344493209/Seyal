plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.seyal.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seyal.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.3.0"
    }

    // Same debug key on every build, so a new APK installs over the old one (settings kept).
    signingConfigs {
        getByName("debug") {
            storeFile = file("seyal-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    // lite: installs from a browser download (no Accessibility / Notification access, which
    //       Play Protect blocks for downloaded apps in India).
    // full: everything; install over USB from Android Studio or adb.
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            isDefault = true
            versionNameSuffix = "-lite"
        }
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf("/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt", "/META-INF/LICENSE", "/META-INF/NOTICE")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Reading Gmail over IMAP (app password).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
