# Media3 and ML Kit ship their own consumer rules. Keep our small app classes readable in crash logs.
-keepattributes SourceFile,LineNumberTable
-keep class com.promptcut.app.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
