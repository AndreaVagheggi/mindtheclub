// MindTheClub for Windows.
//
// Versions are pinned to the ones the Android app uses (C:\mtc\build.gradle.kts and
// C:\mtc\app\build.gradle.kts), on purpose:
//   - Kotlin 2.0.0, KSP 2.0.0-1.0.21, kotlinx-serialization 1.8.1, coroutines 1.10.2 and
//     Room 2.7.2, so files copied from the app compile here unchanged and the database is
//     built by the same Room.
//   - Tink 1.21.0, the JVM twin of the app's tink-android 1.21.0. An identity fingerprint is
//     the SHA-256 of the public keyset's JSON TEXT (crypto/KeyManager.kt, fingerprintOf), so
//     the two must serialise a keyset alike. Do not bump one without the other.

plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    // The Compose compiler, which since Kotlin 2.0 ships with Kotlin itself.
    kotlin("plugin.compose") version "2.0.0"
    id("com.google.devtools.ksp") version "2.0.0-1.0.21"
    application
}

group = "com.bolimot.mindtheclub"
version = "0.0.1"

// Same pin as the Android app: kotlinx-serialization 1.8.1 asks for a newer
// stdlib than the 2.0.0 compiler, and this is the combination known to work.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.0")
    }
}

ksp {
    // Room for anything but Android generates Kotlin, against the SQLite driver API.
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation("com.google.crypto.tink:tink:1.21.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // The database: Room's desktop JVM build, with SQLite compiled into the program
    // (the recommended driver, the same library on every platform). Paging is here only
    // because the app's DAO files, copied unchanged, declare paged queries.
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-paging:2.7.2")
    implementation("androidx.paging:paging-common:3.3.6")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("androidx.annotation:annotation:1.9.1")
    ksp("androidx.room:room-compiler:2.7.2")

    // The user interface: Compose for Desktop, the Windows build, and Material 3. Version
    // 1.6.11 is the line that goes with Kotlin 2.0.0. Declared directly, without the
    // org.jetbrains.compose Gradle plugin, which is only needed to package an installer.
    implementation("org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.6.11")
    implementation("org.jetbrains.compose.material3:material3:1.6.11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // org.json, which Android ships built in and several of the app's engine files use
    // (the recovery trackers store their entries as JSON).
    implementation("org.json:json:20240303")

    // Windows Data Protection API, to seal the identity key (desktop/storage/KeyVault.kt).
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    // WebRTC for the desktop JVM: the Java classes, plus the Windows native library, which
    // ships as the same artifact with a classifier. Not transitive on purpose: the library's
    // POM names its native part through a Maven property (${platform.classifier}) that only
    // Maven fills in, and it has no other dependency.
    implementation("dev.onvoid.webrtc:webrtc-java:1.0.0") { isTransitive = false }
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:1.0.0:windows-x86_64") { isTransitive = false }
}

// What "run" starts: the application itself (MindTheClub.bat), or with -Pprova=N one of the
// numbered checks (provaN.bat).
val prova = project.findProperty("prova") as String?

application {
    mainClass.set(
        if (prova == null) "com.bolimot.mindtheclub.desktop.ui.MainKt"
        else "com.bolimot.mindtheclub.desktop.spike.Spike${prova}Kt"
    )
}

// The checks ask for a file and a password on the keyboard.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
