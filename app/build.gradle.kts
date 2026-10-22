import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val appVersion = providers.gradleProperty("oneryl.version").get()

// 1.2.3 -> 10203, so every new version gets a larger versionCode.
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "Minor and patch versions must stay below 100" }
    major * 10_000 + minor * 100 + patch
}

// CI passes the permanent release key through these variables; without them
// release builds fall back to the debug key and cannot update a released APK.
val releaseKeystore = System.getenv("ONERYL_KEYSTORE")?.takeIf { it.isNotBlank() }

// The Xray core for connecting through the user's own server: libv2ray from
// 2dust/AndroidLibXrayLite (LGPL-3.0), the build v2rayNG uses. At 62 MB it is
// downloaded at build time and checked against its SHA-256 instead of kept in git.
val xrayVersion = "v26.9.30"
val xrayAarSha256 = "cf71680b776b9ca583747ba652f816b047a655eab875d8951e6141636d88bbd6"
val xrayAar = layout.projectDirectory.file("libs/libv2ray-$xrayVersion.aar").asFile

val fetchXrayCore by tasks.registering {
    val target = xrayAar
    val source = "https://github.com/2dust/AndroidLibXrayLite/releases/download/$xrayVersion/libv2ray.aar"
    val expected = xrayAarSha256
    outputs.file(target)
    doLast {
        fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (target.isFile && sha256(target) == expected) return@doLast
        target.parentFile.mkdirs()
        val part = File(target.path + ".part")
        URI(source).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(sha256(part) == expected) { "The Xray core from $source does not match its SHA-256" }
        check(part.renameTo(target)) { "Could not move the Xray core into place" }
    }
}
tasks.named("preBuild") { dependsOn(fetchXrayCore) }

android {
    namespace = "com.oneryl.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.oneryl.music"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        // Phones are arm64; x86_64 is for the emulator test. The Xray core is 35 MB per ABI.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // The Xray core's geo databases serve routing rules the app does not use.
    androidResources {
        ignoreAssetsPatterns += listOf("!geoip.dat", "!geosite.dat", "!geoip-only-cn-private.dat")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ONERYL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ONERYL_KEY_ALIAS")
                keyPassword = System.getenv("ONERYL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
    }

    packaging {
        // Compressed in the APK: stored uncompressed, the Xray core alone would add 70 MB.
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/*.md"
            excludes += "META-INF/INDEX.LIST"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Live tests hit YouTube Music and SoundCloud; run them with -Ponline.
tasks.withType<Test>().configureEach {
    systemProperty("oneryl.online", providers.gradleProperty("online").isPresent.toString())
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.webkit)
    implementation(files(xrayAar))

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)

    implementation(libs.newpipe.extractor)
    implementation(libs.innertubex)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    coreLibraryDesugaring(libs.desugar)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
