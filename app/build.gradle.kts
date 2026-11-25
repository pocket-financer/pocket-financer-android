import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

data class SemanticAppVersion(
    val name: String,
    val code: Int,
)

fun readAppVersion(): SemanticAppVersion {
    val versionFile = rootProject.file("version.txt")
    require(versionFile.isFile) {
        "Missing ${versionFile.path}. The app version must be defined in version.txt."
    }

    val versionName = versionFile.readText().trim()
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(versionName)
        ?: error("version.txt must contain a stable semantic version such as 1.2.3.")
    val (major, minor, patch) = match.destructured.toList().map { component ->
        component.toLongOrNull()
            ?: error("version.txt contains a semantic version component that is too large.")
    }

    require(minor in 0..999 && patch in 0..999) {
        "Minor and patch versions must each be between 0 and 999."
    }

    val versionCode = major * 1_000_000L + minor * 1_000L + patch
    require(versionCode in 1..2_100_000_000L) {
        "The semantic version $versionName produces an invalid Android versionCode."
    }

    return SemanticAppVersion(versionName, versionCode.toInt())
}

val appVersion = readAppVersion()
val releaseSigningEnvironment = mapOf(
    "ANDROID_RELEASE_KEYSTORE_PATH" to providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull,
    "ANDROID_RELEASE_STORE_PASSWORD" to providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull,
    "ANDROID_RELEASE_KEY_ALIAS" to providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull,
    "ANDROID_RELEASE_KEY_PASSWORD" to providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull,
)
val missingReleaseSigningValues = releaseSigningEnvironment
    .filterValues { it.isNullOrBlank() }
    .keys
val hasReleaseSigningConfiguration = missingReleaseSigningValues.isEmpty()

android {
    namespace = "com.pocketfinancer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketfinancer"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseSigningConfiguration) {
            create("release") {
                val keystorePath = checkNotNull(releaseSigningEnvironment["ANDROID_RELEASE_KEYSTORE_PATH"])
                val keystore = rootProject.file(keystorePath)
                require(keystore.isFile) {
                    "ANDROID_RELEASE_KEYSTORE_PATH does not point to a readable file."
                }
                storeFile = keystore
                storePassword = releaseSigningEnvironment["ANDROID_RELEASE_STORE_PASSWORD"]
                keyAlias = releaseSigningEnvironment["ANDROID_RELEASE_KEY_ALIAS"]
                keyPassword = releaseSigningEnvironment["ANDROID_RELEASE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigningConfiguration) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

gradle.taskGraph.whenReady {
    val signedReleaseArtifactRequested = allTasks.any { task ->
        task.path in setOf(
            ":app:assembleRelease",
            ":app:bundleRelease",
            ":app:packageRelease",
            ":app:signReleaseBundle",
        )
    }
    if (signedReleaseArtifactRequested && !hasReleaseSigningConfiguration) {
        throw GradleException(
            "A signed release artifact was requested, but these environment variables are missing: " +
                missingReleaseSigningValues.sorted().joinToString() +
                ". See docs/releasing.md.",
        )
    }
}

dependencies {
    // Modules
    implementation(project(":inference"))
    implementation(project(":data"))
    implementation(project(":sms"))
    implementation(project(":hardware"))
    implementation(project(":pipeline"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Activity
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.runtime)
}
