plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mdviewer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mdviewer"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            // Apache Commons libraries each ship the same license files.
            excludes += setOf("META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    // Comic book archives: ZIP and 7z (LZMA via xz), RAR 4 and 5.
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
