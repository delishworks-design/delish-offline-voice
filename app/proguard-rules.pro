# MediaPipe GenAI
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-dontwarn com.google.mediapipe.**

# Vosk
-keep class org.vosk.** { *; }
-keep class org.vosk.android.** { *; }
-dontwarn org.vosk.**

# JavaScript bridge
-keepclassmembers class com.delish.app.WebAppInterface {
    public *;
}