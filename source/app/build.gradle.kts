plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.parkcoach"
    compileSdk = 35
    // android.car.* (vehicle speed and dimensions) comes from the car library of the Android SDK.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.parkcoach"
        minSdk = 28
        // Pleos Connect Emulator v2.1.0 runs Android 14 (API 34).
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so anyone can build and install a release APK.
            signingConfig = signingConfigs.getByName("debug")
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
        // BuildConfig.DEBUG decides whether the signal page is offered.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The Pleos SDKs are called by name (vehicle/Sdk.kt), so the app also builds without them:
//   ./gradlew assembleDebug -PpleosSdk=false
// Speed and gear still come from Android Automotive; steering, the door and the car's voice need the SDKs.
val pleosSdk = (findProperty("pleosSdk") as String?)?.toBoolean() ?: true

dependencies {
    // Pleos Connect SDK
    if (pleosSdk) {
        implementation(libs.pleos.vehicle)
        implementation(libs.pleos.tts)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}

// The Pleos Virtual Console is a web page served from inside the emulator. This brings it to
// http://127.0.0.1:8088 on this computer; it has to be done again after every emulator start.
tasks.register<Exec>("consoleForward") {
    group = "pleos"
    description = "adb forward tcp:8088 tcp:8088, for the Pleos Virtual Console"
    val adb = androidComponents.sdkComponents.adb
    // The SDK's own adb, so nothing has to be on the PATH.
    executable = "adb"
    doFirst { executable = adb.get().asFile.absolutePath }
    args("forward", "tcp:8088", "tcp:8088")
    // No emulator running is not a reason to fail a build.
    isIgnoreExitValue = true
}

// The small guidance window that stays up over the rear camera needs "display over other apps".
// A car's settings give that; on the emulator this does, for the copy of the app already installed.
// (The very first install has nothing to give it to yet: press Run once more.)
tasks.register<Exec>("overlayAllow") {
    group = "pleos"
    description = "Lets the installed debug app draw over other apps on the emulator"
    val adb = androidComponents.sdkComponents.adb
    executable = "adb"
    doFirst { executable = adb.get().asFile.absolutePath }
    args("shell", "appops", "set", "com.parkcoach", "SYSTEM_ALERT_WINDOW", "allow")
    isIgnoreExitValue = true
}

// A debug build is nearly always headed for the emulator, so it brings the console across and
// allows the small window as well: pressing Run in Android Studio is then all it takes.
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("consoleForward", "overlayAllow") }
