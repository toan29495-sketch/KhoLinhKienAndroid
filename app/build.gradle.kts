import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    val localConfig = Properties()
    rootProject.file("local.properties").inputStream().use {
        localConfig.load(it)
    }

    defaultConfig {
        buildConfigField("String", "SUPABASE_URL", "\"${localConfig.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${localConfig.getProperty("SUPABASE_PUBLISHABLE_KEY", "")}\"")
    }signingConfigs {
            create("release") {
                        storeFile = rootProject.file("release-key.jks")
                                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                                        keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                                                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
    }

    buildTypes {
            getByName("release") {
                        signingConfig = signingConfigs.getByName("release")
                                isMinifyEnabled = false
            }
    }
            }
    }
    namespace = "vn.kholinhkien"
    compileSdk = 35
    defaultConfig {
        applicationId = "vn.kholinhkien"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.1"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-android:3.0.0")

}
