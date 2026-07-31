plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.jarvis.copilot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.copilot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // ---------------------------------------------------------------
        // CREDENTIAL PLACEHOLDER — relay server base URL.
        // Fill this in with wherever you deploy relay-server/main.py
        // e.g. "https://your-relay-app.onrender.com/"
        // ---------------------------------------------------------------
        buildConfigField("String", "RELAY_BASE_URL", "\"FILL_IN_YOUR_RELAY_URL_HERE\"")

        // ---------------------------------------------------------------
        // CREDENTIAL PLACEHOLDER — shared secret, must match relay's
        // APP_SHARED_SECRET env var exactly.
        // ---------------------------------------------------------------
        buildConfigField("String", "APP_SHARED_SECRET", "\"_S7zlgToBjAkbZLuxrZbahd5frftrCNISbPEatN6-5s\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (relay client)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Room (local Notification History / Media Vault state)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // WorkManager (backup queueing)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // No Firebase — photos, voice audio, and voice transcripts all upload to
    // the relay server (see relay-server/main.py), which is the only thing
    // that talks to Backblaze B2. The client never holds a B2 key.

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
