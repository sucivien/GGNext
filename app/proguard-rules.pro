# ============================================================================
# GGNext R8 / ProGuard 规则
# ============================================================================

# ---------- 保留行号与源文件名（便于崩溃栈定位；不想暴露可注释掉） ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- 注解与泛型签名（Compose / 序列化需要） ----------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# ---------- 入口组件（AndroidManifest 里注册的类） ----------
# R8 会依据 Manifest 自动保留，这里显式声明以防 Manifest 解析异常
-keep class com.littlecodesauce.ggnext.MainActivity { <init>(...); }
-keep class com.littlecodesauce.ggnext.service.FloatingIconService { <init>(...); }

# ---------- JNI 桥接与 Native 方法 ----------
-keep class com.littlecodesauce.ggnext.engine.NativeMemBridge { *; }
-keep class com.littlecodesauce.ggnext.lua.LuaScriptEngine$JniBridgeCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- 枚举 ----------
# values() / valueOf() 通过反射调用，R8 可能裁剪或改名为非法值
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 自定义 View（在 XML 或 WindowManager 中实例化） ----------
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# ---------- Service / Application / Activity 生命周期方法 ----------
-keepclassmembers class * extends android.app.Service {
    public <init>(...);
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
    public void onDestroy();
}

# ---------- Compose ----------
# Compose 编译器插件已生成足够信息，仅需保证 runtime 不被裁剪
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# ---------- Kotlin ----------
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ---------- Kotlin 协程 ----------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------- DataStore / Protobuf-Lite（DataStore Preferences 间接依赖） ----------
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-dontwarn androidx.datastore.**

# ---------- Lifecycle / ViewModel ----------
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---------- SavedState ----------
-keep class androidx.savedstate.** { *; }
-dontwarn androidx.savedstate.**

# ---------- 反射 / 序列化兜底 ----------
# 项目中未使用反射式序列化，保留通用构造器以防第三方库依赖
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# ---------- 其他警告抑制（仅针对已知可选注解，禁止使用全局 -ignorewarnings） ----------
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

