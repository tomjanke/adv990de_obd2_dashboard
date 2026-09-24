plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.adv990de.obd2"
    compileSdk = 36

    val appVersionCode = 61
    val appVersionName = "1.16.15"

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
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "de.adv990de.obd2.HiltTestRunner"
    }

    buildTypes {
        debug {
            // Eigene applicationId: Debug- und Release-Build liegen dadurch parallel auf dem
            // Geraet, jeder mit eigenen Daten. Nur im Debug-Build erscheint der ECU-Simulator
            // in der Geraeteliste (siehe BluetoothDeviceScanner.bondedDevices).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    buildFeatures { compose = true; buildConfig = true }

    testOptions {
        unitTests { isReturnDefaultValues = true }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
            )
        }
    }

    sourceSets {
        // Exposes the exported Room schema JSONs (app/schemas/…) as instrumentation-test
        // assets so androidx.room.testing.MigrationTestHelper can load them by version.
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
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
    testImplementation(libs.kotlin.test)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    constraints {
        // room-testing (androidTest-only) needs kotlinx-serialization-json 1.8.1 to deserialize
        // exported Room schema JSONs (MigrationTestHelper). androidx.lifecycle:lifecycle-viewmodel-
        // savedstate-android (main classpath, pulled in via compose/activity) depends on the older
        // kotlinx-serialization-core 1.7.3, and AGP's cross-variant "consistent resolution" then
        // forces that same 1.7.3 onto the androidTest classpath too — breaking Room's precompiled
        // SchemaBundle serializers (AbstractMethodError: GeneratedSerializer.typeParametersSerializers,
        // a method added in 1.7.x that Room's serializer classes, built against 1.8.1, implement but
        // an older loaded core interface does not declare). Forcing the newer, additive-only version
        // here resolves both classpaths to 1.8.1 consistently.
        implementation(libs.kotlinx.serialization.core)
    }
}
