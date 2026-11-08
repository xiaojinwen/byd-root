plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Kotlin 2.x 起 Compose 编译器随 Kotlin 一起发布
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
