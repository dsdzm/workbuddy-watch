plugins {
    id("com.android.application") version "8.7.3" apply false
    // Wear Compose Material3 1.5.x 的 AAR 用 Kotlin 2.1 metadata 编译，编译器要跟上
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
