# LiteRT-LM talks to its native (C++) engine through JNI and reads tool classes by reflection:
# keep its classes and members exactly as they are.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**
# LiteRT-LM uses Gson for messages.
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Our demo bridge is called from JavaScript by name.
-keepclassmembers class com.offlinegemma.app.** {
    @android.webkit.JavascriptInterface <methods>;
}

# PDF reader (pdfbox-android): optional image codecs it can use but we don't ship.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.**

# WorkManager (the background download) builds its Room database by reflection.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { <init>(...); }
