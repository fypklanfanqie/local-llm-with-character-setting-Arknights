# 基础属性
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Application / Activity / Worker（框架反射实例化）
-keep public class com.rhodesisland.terminal.RhodesApp { public <init>(...); }
-keep public class com.rhodesisland.terminal.MainActivity { public <init>(...); }
-keep class com.rhodesisland.terminal.work.GreetingWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# JNI 桥接：类名、native 方法名、被 C 代码访问的静态成员都不能混淆
-keep class com.chatbyyourside.llm.backend.MnnBridge { *; }
-keep class com.chatbyyourside.llm.CpuSysBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }

# LLM / Provider / Repository / Config / Model / Manager
-keep class com.rhodesisland.terminal.llm.** { *; }
-keep class com.rhodesisland.terminal.provider.** { *; }
-keep class com.rhodesisland.terminal.data.model.** { *; }
-keep class com.rhodesisland.terminal.data.local.** { *; }
-keep class com.rhodesisland.terminal.data.repository.** { *; }
-keep class com.rhodesisland.terminal.data.remote.** { *; }
-keep class com.rhodesisland.terminal.config.** { *; }
-keep class com.rhodesisland.terminal.notification.** { *; }
-keep class com.rhodesisland.terminal.manager.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Retrofit
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Kotlin Serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements kotlinx.serialization.KSerializer { *; }

# kotlinx.serialization 官方规则（app 侧补全）：包级 keep 未覆盖的 @Serializable 类
# （affinity.SpecialEventScript、download、tts、video 等）靠运行时 serializer 反射查找，
# R8 下 companion/serializer() 被重命名会抛 SerializationException（release 静默失效）。
-keep,includedescriptorclasses class com.rhodesisland.terminal.**$$serializer { *; }
-keepclassmembers class com.rhodesisland.terminal.** { *** Companion; }
-keepclasseswithmembers class com.rhodesisland.terminal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# 枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 常见库警告压制
-dontwarn java.lang.invoke.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.**
-dontwarn androidx.compose.material3.**
