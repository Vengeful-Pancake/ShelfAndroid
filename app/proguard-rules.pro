# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Room generated
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# JavaScript bridge used by the reader WebView
-keepclassmembers class com.shelf.reader.ui.reader.ReaderBridge {
    public *;
}
