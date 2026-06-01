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

-dontwarn com.google.firebase.crashlytics.buildtools.reloc.**
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

# ===== PLAY INTEGRITY (App Check) =====
-keep class com.google.android.play.core.integrity.** { *; }
-keep class com.google.firebase.appcheck.** { *; }
-keep class com.google.firebase.appcheck.playintegrity.** { *; }
-keep class com.google.firebase.appcheck.debug.** { *; }
-dontwarn com.google.android.play.core.integrity.**

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