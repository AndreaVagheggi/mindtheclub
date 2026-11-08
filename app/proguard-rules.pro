# KEEP BASIC
-keepclasseswithmembernames class * { native <methods>;}
-keepattributes Signature
-keepattributes *Annotation*

# KEEP WebRTC
-keep class org.webrtc.** { *; }

-keepclassmembers class * {
    @org.webrtc.CalledByNative *;
    @org.webrtc.CalledByNativeUnchecked *;
}

-keep class org.webrtc.WebRtcClassLoader { *; }
-keep class org.webrtc.IceCandidate { *; }
-keepclassmembers class org.webrtc.SessionDescription {
    public <fields>;
}

# kotlinx-serialization references kotlin.uuid builtins it may not actually use; ignore if absent
-dontwarn kotlin.uuid.**

# ===== KEEP USER CLASSES IMPLEMENTING WEBRTC INTERFACES =====
-keep class * implements org.webrtc.PeerConnection$Observer { *; }
-keep class * implements org.webrtc.DataChannel$Observer { *; }
-keep class * implements org.webrtc.SdpObserver { *; }
-keep class * implements org.webrtc.StatsObserver { *; }
-keep class * extends org.webrtc.CameraVideoCapturer { *; }
-keep class * extends org.webrtc.VideoSink { *; }
-keep class org.jni_zero.** { *; }

# KEEP firebase
-keep class com.google.firebase.** { *; }
-keep class com.bolimot.mindtheclub.firebase.MyFirebaseMessagingService { *; }

-dontwarn javax.servlet.**
-dontwarn org.ietf.jgss.**

-dontwarn sun.misc.Unsafe
-dontwarn javax.naming.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn androidx.appcompat.view.ContextThemeWrapper

# ===== ADDITIONAL WEBRTC RULES (Critical for JNI) =====
# Keep WebRTC observers - these are called from native code
-keep class org.webrtc.PeerConnection$Observer { *; }
-keep class org.webrtc.DataChannel$Observer { *; }
-keep class org.webrtc.SdpObserver { *; }
-keep class org.webrtc.StatsObserver { *; }
-keep class org.webrtc.MediaStreamTrack { *; }
-keep class org.webrtc.RtpReceiver { *; }
-keep class org.webrtc.RtpSender { *; }

# Keep all WebRTC inner classes
-keepattributes InnerClasses
-keepclassmembers class org.webrtc.** {
    *;
}

# Keep descriptor classes for WebRTC
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


# ===== KOTLIN COROUTINES =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ===== KOTLIN SERIALIZATION =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.bolimot.mindtheclub.**$$serializer { *; }
-keepclassmembers class com.bolimot.mindtheclub.** {
    *** Companion;
}
-keepclasseswithmembers class com.bolimot.mindtheclub.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ===== ROOM DATABASE =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ===== ML KIT / CAMERA =====
-keep class com.google.mlkit.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**

# ===== GLIDE =====
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# ===== OKHTTP =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ===== SENTRY =====
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ===== PRESERVE LINE NUMBERS FOR CRASH REPORTS =====
-keepattributes SourceFile,LineNumberTable,Exceptions,EnclosingMethod
-renamesourcefileattribute SourceFile

# ===== GENERAL WARNINGS TO SUPPRESS =====
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# ===== MEDIA3 (video compression): no class merging =====
# R8 horizontal class merging fused Media3 Transformer classes with unrelated ones and
# inserted a check-cast to android.media.metrics.LogSessionId (API 31+) on a path that
# runs on every device. ART before Android 12 resolves a cast's class even for a null
# value, so every video compression crashed on Android 11 and older
# (NoClassDefFoundError, Poco X3 NFC, 2 Nov 2026). Known R8 bug, androidx/media#2535.
# Keeping the classes stops the merging; names are still obfuscated and unused code
# still removed.
# Members too (8 Nov 2026): with only the classes kept, R8 still rewrote method bodies.
# It inlined GlProgram's delegating constructor into GlProgram(Context, String, String);
# on Android 11 and older that rewritten constructor hands a corrupt Context to
# Util.loadAsset, and every compression died with SIGSEGV in the effect thread (Huawei
# Android 9 and Poco Android 11, identical traces; fine on Android 14 and 16). Members
# kept without allowoptimization are neither inlined nor rewritten; still renamed and,
# when unused, removed.
-keep,allowobfuscation,allowshrinking class androidx.media3.** { *; }
