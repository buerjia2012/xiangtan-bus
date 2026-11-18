# release 包 R8 规则：只保留真正会被系统/反射回调的入口，
# 其余（含 mapsforge、androidx）允许裁剪和改名以缩小 dex。
-dontwarn **
-dontnote **

# 自己的代码体量很小，全量保留，避免自定义 View / 内部回调被改坏
-keep class com.xtbus.** { *; }

# 系统按类名实例化的组件
-keep public class * extends android.app.Activity { public *; }
-keep public class * extends android.app.Application { public *; }
-keep public class * extends android.app.Service { public *; }
-keep public class * extends android.content.BroadcastReceiver { public *; }
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 布局里的自定义 View（XML 里按全名反射创建）
-keep class com.xtbus.WalkMapPanel { *; }
-keep class com.xtbus.WalkCompassView { *; }

# mapsforge 内部有 Class.newInstance 反射（rendertheme 工厂），
# 允许裁剪未用到的类，但保留已保留类的公共成员名
-keep,allowobfuscation,allowshrinking class org.mapsforge.** { public *; }
-keep,allowobfuscation,allowshrinking class org.mapsforge.map.rendertheme.** { *; }

# 序列化 / Parcelable 等按字段名反射的逻辑
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable { *; }
