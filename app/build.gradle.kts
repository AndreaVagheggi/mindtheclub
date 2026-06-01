plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.devtools.ksp")
    id("io.sentry.android.gradle")
}

extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.bolimot.mindtheclub"
    compileSdk = 35

    ndkVersion = "28.1.13356709"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.bolimot.mindtheclub"
        minSdk = 26
        targetSdk = 35
        versionCode = 910
        versionName = "pre-release 0.910"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    @Suppress("UnstableApiUsage")
    bundle {
        abi {
            enableSplit = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystores/mtc_key.jks")
            storePassword = "Esperanto@64"
            keyAlias = "MindTheClub"
            keyPassword = "Esperanto@64"
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
        }

        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            buildConfigField("Boolean", "ENABLE_DEBUG_TOOLS", "false")
        }

        create("staging") {
            initWith(getByName("release"))

            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".staging"
            buildConfigField("Boolean", "ENABLE_DEBUG_TOOLS", "true")
        }
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
    implementation("androidx.room:room-runtime-android:2.7.2")
    implementation("com.google.firebase:firebase-crashlytics-buildtools:3.0.4")
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

    //FIREBASE
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-appcheck")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-auth")
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

    // VARIOUS
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

    // this will upload your source code to Sentry to show it as part of the stack traces
    // disable if you don't want to expose your sources
    includeSourceContext.set(true)
}