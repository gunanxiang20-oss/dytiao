# Xposed 入口类由 assets/xposed_init 里写死的类名反射创建，必须保留
-keep class com.byxiaopeng.dytiao.AdSkipModule { *; }
-keepnames class com.byxiaopeng.dytiao.AdSkipModule

# 模块里被 Xposed 框架通过接口回调的类
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# Xposed API 是 compileOnly，运行时由框架提供
-dontwarn de.robv.android.xposed.**
-dontwarn android.app.AndroidAppHelper

# 混淆强度
-repackageclasses 'o'
-allowaccessmodification
-optimizationpasses 5

# 保留必要的泛型/注解信息（反射调用用不到自己的类，但保守一点）
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
