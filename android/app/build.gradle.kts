import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 固定发布证书：先找 ~/.android-keys/getmedia.properties，再找项目根的 keystore.properties。
// 找不到就**直接构建失败**——绝不能悄悄用调试证书签一个 release 包出去，
// 否则装到手机上又会撞上「签名不一致，无法覆盖安装」。
// 新机器/新 Agent 环境先把密钥装回来：
//   GETMEDIA_KEY_SRC=<你的本机密钥目录> sh tools/setup-release-key.sh   # 从你指定的本机路径复制，不涉及服务器
val releaseSigning: Properties? = sequenceOf(
    File(System.getProperty("user.home"), ".android-keys/getmedia.properties"),
    rootProject.file("keystore.properties"),
).mapNotNull { file ->
    file.takeIf { it.isFile }?.let { props -> Properties().apply { props.inputStream().use { load(it) } } }
}.firstOrNull { props ->
    props.getProperty("storeFile")?.let { File(it).isFile } == true
}

// 只在"真的要产出 release 包"时才强制要密钥。
// 配置阶段会跑在任何任务之前，所以不能无条件 throw——否则 CI 里的 assembleDebug / testDebugUnitTest
// （CI 上按设计没有密钥）也会被一起打断。IDE 同步时 taskNames 为空，同样放行。
val releaseTaskRequested: Boolean = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':')
    name.contains("assembleRelease", ignoreCase = true) ||
        name.contains("bundleRelease", ignoreCase = true) ||
        name.equals("build", ignoreCase = true) ||
        name.equals("assemble", ignoreCase = true)
}

if (releaseSigning == null && releaseTaskRequested && providers.gradleProperty("allowDebugSignRelease").orNull != "true") {
    throw GradleException(
        """
        找不到固定的发布密钥，已中止 release 构建。

        请把项目密钥安装到本机后再构建：
            GETMEDIA_KEY_SRC=<你的本机密钥目录> sh tools/setup-release-key.sh
        或手工执行：
            mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
            chmod 600 ~/.android-keys/*
        （密钥只保存在你的 Mac 和你自己的离线加密备份里，不在服务器、不在仓库。）

        详见 docs/android-release-signing.md。
        确实只是本机临时调试、不打算发布时，才加 -PallowDebugSignRelease=true 绕过。
        """.trimIndent()
    )
}

// 包名常量来自 android/app-ids.properties
val appIds: Properties = Properties().apply {
    rootProject.file("app-ids.properties").inputStream().use { load(it) }
}

val appConfig: Properties = Properties().apply {
    rootProject.file("app-config.properties").inputStream().use { load(it) }
}
val serverBase: String = (providers.gradleProperty("getmediaServerBase").orNull ?: appConfig.getProperty("serverBase")).trim()
require(serverBase.startsWith("https://") && !serverBase.endsWith("/")) {
    "serverBase 必须以 https:// 开头且不以 / 结尾：当前值不合法"
}

android {
    namespace = "com.getmedia.app"
    compileSdk = 34

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = File(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        minSdk = 26          // Media3 和后台播放在 8.0 以下坑太多
        testInstrumentationRunner = "com.getmedia.app.BatchDeleteInstrumentation"
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("String", "SERVER_BASE", "\"$serverBase\"")
    }

    // 免费版与完整版是两个独立安装包（包名不同，可同机并存）。任务一只构建 free；
    // full 仅保留空壳，真实的重型功能在任务二放进 src/full。
    flavorDimensions += "edition"
    productFlavors {
        create("free") {
            dimension = "edition"
            applicationId = appIds.getProperty("freeApplicationId")
        }
        create("full") {
            dimension = "edition"
            applicationId = appIds.getProperty("fullApplicationId")
        }
    }

    // 预览版可与正式版并存：包名加 .preview，桌面名称带「新版」，方便在保留旧数据的前提下试新版本
    val previewBuild = providers.gradleProperty("getmediaPreview").orNull == "true"

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (previewBuild) {
                applicationIdSuffix = ".preview"
                manifestPlaceholders["appLabel"] = "GetMedia 新版"
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
            if (previewBuild) {
                applicationIdSuffix = ".preview"
                manifestPlaceholders["appLabel"] = "GetMedia 新版"
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 本地库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 播放
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")

    // 图片（封面）
    implementation("io.coil-kt:coil-compose:2.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
