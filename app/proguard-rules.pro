# Compose + Kotlin 默认规则已由 proguard-android-optimize.txt 提供，这里只补本工程特有的反射入口。

# WebView 的 JS 桥（WebLoginScreen.JsBridge）靠注解 + 反射调用，必须保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.workbuddy.watch.ui.screens.WebLoginScreenKt$JsBridge { *; }

# 打开已下载产物时通过 FileProvider 暴露 URI，Provider 由 AGP 生成规则保留
-keep class androidx.core.content.FileProvider { *; }

# 去掉 R8 对可选依赖的告警
-dontwarn org.jetbrains.annotations.**

# 混淆映射输出，便于线上崩溃定位（构建产物里会生成 mapping.txt）
-printmapping build/outputs/mapping/release/mapping.txt
