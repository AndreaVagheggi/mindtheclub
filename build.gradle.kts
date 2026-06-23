// Top-level build file where you can add configuration options common to all sub-projects/modules.
// The modern 'plugins' block replaces the old 'buildscript' block entirely.

plugins {
    // This is the only place you should define the Android Gradle Plugin version.
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false

    // We also need to define the Google Services plugin here.
    id("com.google.gms.google-services") version "4.4.2" apply false

    // All other plugins from your original file:
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false // Aligned with AGP 8.4.1 for better compatibility
    id("com.google.firebase.appdistribution") version "5.1.1" apply false
}
