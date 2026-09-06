import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle")
}

// Master switch for Sentry (crash/performance reporting).
// Flip it in gradle.properties (sentryEnabled=true/false) or per build with
// -PsentryEnabled=true. When false Sentry is not merely inert, it is absent from
// the artefact altogether:
//   - the SDK is never added to the classpath (see autoInstallation below), so
//     none of its integrations ship and neither do libsentry.so /
//     libsentry-android.so, ~2.6 MB that would otherwise reach every device
//     because abi.enableSplit is off,
//   - the SentryInitProvider / SentryPerformanceProvider ContentProviders are not
//     merged into the manifest, because they come from the library,
//   - no bytecode is instrumented and no Sentry assets are generated,
//   - the manifest carries no DSN, because those entries live in the overlay
//     below, which is attached only when the switch is on, and
//   - no build-time symbol/source upload happens, so no Sentry auth token is
//     needed and the build stays fast.
val sentryEnabled = (project.findProperty("sentryEnabled") as String? ?: "false").toBoolean()

// Sentry's manifest entries. Attached to each build type's source set further
// down, only when sentryEnabled is true; a build type manifest is an overlay
// merged on top of src/main, which is what keeps src/main free of Sentry.
val sentryManifest = file("src/sentry/AndroidManifest.xml")

// Verbose file logging (debugLine), the log-export menu and the stealth leak
// check in RELEASE builds. Off by default so production never writes message
// content to disk; enable only for a Play-installable troubleshooting build:
//   gradlew bundleRelease -PreleaseLogging=true
// Such a build reports its version name as "... (log)" so it is recognisable
// in Options -> About.
val releaseLogging = (project.findProperty("releaseLogging") as String? ?: "false").toBoolean()

// Disables payment enforcement: the app keeps working after the 30-day trial
// without a subscription. All the subscription messaging (onboarding screen,
// trial dialog, countdown banner) still appears exactly as in a real build,
// only the blocking is lifted. For test devices only:
//   gradlew bundleRelease -PnoPay=true
// The version name gets a "(nopay)" suffix so such a build is recognisable in
// Options -> About and can never be shipped by accident.
val noPay = (project.findProperty("noPay") as String? ?: "false").toBoolean()

// Forces the ICE path, for testing only. Three values:
//   all     production. The field is not touched at all, behaviour as always.
//   nohost  host candidates dropped, so two phones on the same Wi-Fi are made to
//           go out and come back through STUN/TURN like two real users would.
//   relay   everything through TURN. One peer in relay is enough to pull the
//           whole session onto the relay, which is how the cost of TURN gets
//           measured against a direct leg in the same run.
//
// Debug builds default to nohost, because installing from Android Studio onto a
// bench of phones on one LAN is exactly the case this exists for. Release
// defaults to all, so nothing can leak into production by omission; asking for a
// non-default mode explicitly stamps the version name, the way noPay does.
val iceModeProperty = project.findProperty("iceMode") as String?
val iceMode = iceModeProperty ?: "all"
val iceModeDebug = iceModeProperty ?: "nohost"
require(iceMode in listOf("all", "nohost", "relay")) {
    "iceMode must be all, nohost or relay (got \"$iceMode\")"
}

// True for any build carrying a test flag, i.e. exactly the ones that also get a
// suffix stamped on the version name below. Derived from the same three
// conditions so the two can never drift apart.
val isTestBuild = releaseLogging || noPay ||
        (iceModeProperty != null && iceMode != "all")

// One project, two destinations, no second working copy.
//
// Play refuses two uploads sharing a version code ANYWHERE in the app, not just
// within one track, and serves each user the highest code they are entitled to
// across the tracks they belong to. A troubleshooting build therefore cannot
// reuse the production code, and has to sit ABOVE it, or the testers keep being
// served the production bundle instead of the one built for them.
//
// So a plain build takes the base code and a flagged build takes base + 1:
//
//   gradlew bundleRelease                                    -> 1052  Production
//   gradlew bundleRelease -PreleaseLogging=true -PnoPay=true  -> 1053  Internal testing
//
// Bump baseVersionCode by TWO each cycle, keeping it even, so the odd number
// stays reserved for that cycle's tester build.
val baseVersionCode = 1066
val appVersionCode = if (isTestBuild) baseVersionCode + 1 else baseVersionCode

// Both commands build the SAME build type, so without this they would both write
// app/build/outputs/bundle/release/app-release.aab and the second would silently
// overwrite the first. Uploading the (nopay) bundle to production by mistake
// would hand the app to the whole world for free, so the two artefacts are given
// names that cannot be confused:
//
//   mtc-1052-release.aab        plain build, goes to Production
//   mtc-1053-test-release.aab   flagged build, goes to Internal testing
base {
    archivesName.set(
        if (isTestBuild) "mtc-$appVersionCode-test" else "mtc-$appVersionCode"
    )
}

// Release signing credentials. This repository is public, so no password may
// appear in this file: they live in keystore.properties at the project root,
// which is listed in .gitignore and never committed. The file holds four keys:
//
//   storeFile=keystores/mtc_key.jks
//   storePassword=...
//   keyAlias=MindTheClub
//   keyPassword=...
//
// If it is missing or incomplete the build still configures, but release
// artefacts come out unsigned and the message below says so.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningReady = keystorePropertiesFile.exists() &&
    !keystoreProperties.getProperty("storeFile").isNullOrBlank() &&
    !keystoreProperties.getProperty("storePassword").isNullOrBlank() &&
    !keystoreProperties.getProperty("keyAlias").isNullOrBlank() &&
    !keystoreProperties.getProperty("keyPassword").isNullOrBlank()
if (!releaseSigningReady) {
    project.logger.lifecycle(
        "keystore.properties missing or incomplete: release builds will not be signed."
    )
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.bolimot.mindtheclub"
    compileSdk = 36

    ndkVersion = "28.1.13356709"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.bolimot.mindtheclub"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "Release 1.66" +
                (if (releaseLogging) " (log)" else "") +
                (if (noPay) " (nopay)" else "") +
                (if (iceModeProperty != null && iceMode != "all") " ($iceMode)" else "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Exposed to code (BuildConfig.SENTRY_ENABLED), driven by the flag above.
        // There is no manifest placeholder any more: io.sentry.auto-init now lives
        // in src/sentry/AndroidManifest.xml, merged only when Sentry is enabled.
        buildConfigField("Boolean", "SENTRY_ENABLED", sentryEnabled.toString())
        buildConfigField("Boolean", "NO_PAY", noPay.toString())
        buildConfigField("String", "ICE_MODE", "\"$iceMode\"")

        // Soak test: a fake text message sent to the single paired contact every
        // 30 minutes, to measure delivery latency without waiting for a real
        // tester to write. Now false in EVERY build type, debug included: the
        // automatic traffic is no longer wanted. The worker and its call sites
        // are untouched: putting the line in the debug block back to true is all
        // it takes to soak the two dedicated handsets again.
        buildConfigField("Boolean", "SOAK_TEST", "false")
    }

    @Suppress("UnstableApiUsage")
    bundle {
        abi {
            enableSplit = false
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes.add("**/dump_syms.bin")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("Boolean", "ENABLE_DEBUG_TOOLS", "true")
            buildConfigField("String", "ICE_MODE", "\"$iceModeDebug\"")
            // Soak disabled in every build type. The worker is untouched: flipping
            // this single line back to true re-enables it on the two test handsets.
            buildConfigField("Boolean", "SOAK_TEST", "false")
        }

        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            buildConfigField("Boolean", "ENABLE_DEBUG_TOOLS", releaseLogging.toString())
        }

        create("debugMinified") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            // initWith copies buildConfigField from debug, where SOAK_TEST is false.
            // Restated here so this type stays soak-free even if the debug block is
            // flipped back on for a test run.
            buildConfigField("Boolean", "SOAK_TEST", "false")
        }

        create("staging") {
            initWith(getByName("release"))

            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".staging"
            buildConfigField("Boolean", "ENABLE_DEBUG_TOOLS", "true")
            buildConfigField("Boolean", "SOAK_TEST", "false")
        }
    }

    // Must come after buildTypes: the debugMinified and staging source sets only
    // exist once their build types have been created. Every build type gets the
    // same overlay, so -PsentryEnabled=true works for a bundle and for an install
    // from Android Studio alike, while omitting the flag leaves all of them clean.
    if (sentryEnabled) {
        sourceSets.getByName("debug").manifest.srcFile(sentryManifest)
        sourceSets.getByName("release").manifest.srcFile(sentryManifest)
        sourceSets.getByName("debugMinified").manifest.srcFile(sentryManifest)
        sourceSets.getByName("staging").manifest.srcFile(sentryManifest)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.0")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val roomVersion = "2.7.2"
val pagingVersion = "3.2.1"

dependencies {
    implementation("com.google.mlkit:vision-common:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // Video transcoding before sending (see functions/VideoCompressor.kt). The
    // official AndroidX path: writing this on raw MediaCodec plus MediaMuxer
    // means hundreds of lines of rotation, timestamp and end-of-stream handling.
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")

    implementation("androidx.room:room-runtime-android:2.7.2")
    implementation("androidx.compose.foundation:foundation-android:1.8.3")
    implementation("androidx.emoji2:emoji2-bundled:1.5.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.datastore:datastore-core-android:1.1.7")
    implementation("com.google.firebase:firebase-functions-ktx:21.2.1")
    implementation("androidx.compose.material3:material3-android:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("com.google.android.gms:play-services-base:18.4.0")

    //TEST
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    // STANDARD
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")

    // WEBRTC
    implementation("io.github.webrtc-sdk:android:144.7559.01")

    // Provides the real Guava ListenableFuture (addListener, etc.). This was
    // previously pulled in transitively by firebase-analytics; declared
    // explicitly now so WorkManager / ML Kit / Play Integrity APIs resolve it.
    implementation("com.google.guava:guava:32.1.3-android")

    //FIREBASE
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-appcheck")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-storage-ktx")

    // COROUTINES
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // LIFECYCLE
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")

    // ROOM
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("androidx.room:room-paging:$roomVersion")
    implementation("androidx.paging:paging-runtime-ktx:3.3.6")

    // GLIDE
    implementation("com.github.bumptech.glide:glide:4.13.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    //CAMERA
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // GOOGLE PLAY BILLING (subscriptions: mtc_standard / mtc_stealth)
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // VARIOUS
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.getstream:photoview:1.0.3")
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("com.google.android.play:integrity")
    implementation("com.google.crypto.tink:tink-android:1.21.0")

    // TELCO
    implementation("androidx.core:core-telecom:1.0.1")
}

sentry {
    org.set("private-0l5")
    projectName.set("mindtheclub")

    // Every contribution the plugin can make to the artefact is tied to the same
    // master switch, so a build without -PsentryEnabled=true carries no trace of
    // Sentry at all.

    // The big one. Auto installation is what silently adds io.sentry:sentry-android
    // and its integrations (fragment, sqlite, okhttp, compose, navigation, replay
    // and ndk) to the runtime classpath. The ndk integration alone packages
    // libsentry.so plus libsentry-android.so for all four ABIs.
    autoInstallation { enabled.set(sentryEnabled) }

    // Bytecode weaving into Room, OkHttp and file I/O. Dead weight without the SDK.
    tracingInstrumentation { enabled.set(sentryEnabled) }

    // Writes sentry-debug-meta.properties into the assets and injects the
    // io.sentry.proguard-uuid meta-data into the merged manifest.
    includeProguardMapping.set(sentryEnabled)

    // Writes sentry-external-modules.txt into the assets.
    includeDependenciesReport.set(sentryEnabled)

    // The plugin's own build-time telemetry, which reports to Sentry while Gradle
    // runs. It concerns the build rather than the app, but off means off.
    telemetry.set(sentryEnabled)

    // Upload source context and the ProGuard/R8 mapping (for de-obfuscated release
    // stack traces) only when Sentry is enabled. When disabled these are skipped,
    // so the build needs no auth token and stays fast.
    includeSourceContext.set(sentryEnabled)
    autoUploadProguardMapping.set(sentryEnabled)
}