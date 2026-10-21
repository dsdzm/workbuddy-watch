plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.workbuddy.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.workbuddy.watch"
        // Android 9 = API 28。WearOS 与非 WearOS 的 Android 9 手表都 >= 28。
        minSdk = 28
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.9"
    }

    // Release 专用签名：手表上侧载安装需要固定签名，便于后续覆盖升级。
    signingConfigs {
        create("release") {
            storeFile = file("../keystore/workbuddy-release.jks")
            storePassword = "workbuddy2026"
            keyAlias = "workbuddy"
            keyPassword = "workbuddy2026"
        }
    }

    buildTypes {
        release {
            // 手表资源紧张：开启代码收缩 + 资源收缩，并保留可读的混淆映射便于排查
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // 设置页与引导页要显示版本号（BuildConfig.VERSION_NAME）
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // 列表项用的线性图标（历史记录 / 搜索 / 下载 / 设置 / 关于 等）
    implementation("androidx.compose.material:material-icons-extended")

    // Wear OS：Wear Compose Material3（药丸卡片、ScalingLazyColumn、TimeText、分页指示器、滑动返回）
    implementation("androidx.wear.compose:compose-material3:1.5.5")
    implementation("androidx.wear.compose:compose-foundation:1.5.5")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
