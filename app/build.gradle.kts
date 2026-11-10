import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Everything secret or machine-specific lives in local.properties (git-ignored).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun local(key: String): String = localProps.getProperty(key, "")
fun quoted(key: String): String = "\"" + local(key).replace("\"", "\\\"") + "\""

android {
    namespace = "dev.pfeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pfeed"
        minSdk = 26
        targetSdk = 36
        // Always higher than the last CI build, so Android accepts every new APK as an update.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "3.27.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API source adapter configuration (see SOURCES.md). Empty = adapter stays disabled.
        buildConfigField("String", "API_DISPLAY_NAME", quoted("api.displayName"))
        buildConfigField("String", "API_BASE_URL", quoted("api.baseUrl"))
        buildConfigField("String", "API_AUTH_URL", quoted("api.authUrl"))
        buildConfigField("String", "API_TOKEN_URL", quoted("api.tokenUrl"))
        buildConfigField("String", "API_CLIENT_ID", quoted("api.clientId"))
        buildConfigField("String", "API_SCOPES", quoted("api.scopes"))
    }

    signingConfigs {
        // One fixed key for every debug build (CI and local), so each new APK installs over the last
        // one. Without it, each CI runner signs with its own throwaway key and Android refuses the
        // update. The repo is public, so anyone could sign with this key; the owner accepted that.
        getByName("debug") {
            storeFile = rootProject.file("keystore/pfeed.jks")
            storePassword = "pfeed-sideload"
            keyAlias = "pfeed"
            keyPassword = "pfeed-sideload"
        }
        create("release") {
            val path = local("release.storeFile")
            if (path.isNotBlank()) {
                storeFile = file(path)
                storePassword = local("release.storePassword")
                keyAlias = local("release.keyAlias")
                keyPassword = local("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
        debug {
            applicationIdSuffix = ".debug"
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
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
