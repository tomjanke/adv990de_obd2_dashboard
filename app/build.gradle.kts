plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.adv990de.obd2"
    compileSdk = 35

    val appVersionCode = 12
    val appVersionName = "1.4.5"

    val keystoreFile = rootProject.file("keystore.jks")
    fun prop(key: String) = (findProperty(key) ?: System.getenv(key))?.toString()

    signingConfigs {
        create("release") {
            storeFile     = keystoreFile
            storePassword = prop("KEYSTORE_PASSWORD")
            keyAlias      = prop("KEY_ALIAS")
            keyPassword   = prop("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "de.adv990de.obd2"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["sentryDsn"] = prop("SENTRY_DSN") ?: ""
            manifestPlaceholders["buildEnvironment"] = "debug"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign if keystore.jks exists — CI/dev builds without the keystore produce
            // an unsigned APK rather than failing the build.
            signingConfig = if (keystoreFile.exists()) signingConfigs["release"] else null
            manifestPlaceholders["sentryDsn"] = prop("SENTRY_DSN") ?: ""
            manifestPlaceholders["buildEnvironment"] = "production"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    testOptions {
        unitTests { isReturnDefaultValues = true }
    }
}

ksp {
    // Room schema export — version history is tracked under app/schemas/
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    implementation(libs.room.paging)
    implementation(libs.sentry.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.tooling)
}
