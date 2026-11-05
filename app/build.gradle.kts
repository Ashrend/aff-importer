plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

/**
 * 发布签名配置。
 *
 * 口令与密钥库都不入库：密钥库放在 keystore/ 下，口令放在 keystore/keystore.properties，
 * 两者都已被 .gitignore 排除。该文件不存在时 release 仍然可以构建，只是产物未签名。
 */
val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "aff.importer.tool"
    compileSdk = 34

    defaultConfig {
        applicationId = "aff.importer.tool"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 开启 R8 代码压缩与资源压缩
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
    // Kotlin 2.0 使用 compose 编译器插件，不需要 composeOptions
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * APK 产物收集到仓库根目录的 bin/，方便直接取用（bin/ 已被 .gitignore 排除）。
 *
 * 结果是 bin/app-debug.apk 与 bin/app-release.apk。
 *
 * 为什么不直接把 AGP 的打包输出目录改成 bin/：AGP 8.x 没有公开 API 支持改打包输出目录，
 * 强行改会和它内部的「APK 列表文件重定向」任务冲突 —— 那个任务指向的 output-metadata.json
 * 路径在任务创建时就被写死并 finalize 了，改不动。所以让 AGP 照常产出，再复制一份到 bin/。
 */
val collectApk by tasks.registering(Copy::class) {
    group = "build"
    description = "把 APK 产物复制到仓库根目录的 bin/"
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.layout.projectDirectory.dir("bin"))
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    finalizedBy(collectApk)
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    
    // DocumentFile for SAF operations
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material Design 3
    implementation("androidx.compose.material3:material3")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.11.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}