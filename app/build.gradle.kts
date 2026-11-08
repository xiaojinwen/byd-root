import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名统一走 E:/project/apk-keys/app.jks（alias xjwkey），debug/release 同一签名，
// 保证同一包名下可以直接覆盖升级。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xiaojw.BydRoot"
    // miuix 0.8.x / Compose 1.10 要求 compileSdk 36
    compileSdk = 36

    defaultConfig {
        applicationId = "xiaojw.BydRoot"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "1.5.14"
    }

    signingConfigs {
        if (keystoreProps["storeFile"] != null) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps["storeFile"] != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystoreProps["storeFile"] != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // miuix 0.8.x / Compose 1.10 由 Kotlin 2.3.20 编译，统一 JVM 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            val suffix = if (variantName.equals("debug", ignoreCase = true)) "-debug" else ""
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "BydRoot_${defaultConfig.versionName}${suffix}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// UI 用 KernelSU Manager 同款组件库 miuix 0.8.8（HyperOS / 澎湃风格）+ Jetpack Compose。
// 底层 ADB / fastboot / 全分区备份协议仍是已通过协议自测的 Java 实现，Java-Kotlin 混编。
// 全部依赖均在本地 Gradle 缓存中，gradlew --offline 可直接重建。
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    // 显式锁定本地缓存里的 1.10.2：Compose 1.10 传递依赖的 1.9.0 未缓存，离线构建会失败
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")

    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")
}
