import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---------- release 签名配置 ----------
// 数据来源优先级：
//   1. 项目根目录 keystore.properties（本地签名用，勿提交到 git）
//   2. 环境变量 KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
//      （GitHub Actions secrets，KEYSTORE_BASE64 是 keystore 文件的 base64 内容）
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun secret(name: String): String? = keystoreProperties.getProperty(name) ?: System.getenv(name)

data class SigningInfo(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadReleaseSigning(): SigningInfo? {
    val storePassword = secret("KEYSTORE_PASSWORD") ?: return null
    val keyAlias = secret("KEY_ALIAS") ?: return null
    val keyPassword = secret("KEY_PASSWORD") ?: return null
    val storeFile = secret("KEYSTORE_FILE")?.let { rootProject.file(it) }
        ?: secret("KEYSTORE_BASE64")?.let { base64 ->
            File.createTempFile("keystore", ".jks").apply {
                writeBytes(Base64.getDecoder().decode(base64))
            }
        }
        ?: return null
    if (!storeFile.exists()) return null
    return SigningInfo(storeFile, storePassword, keyAlias, keyPassword)
}

android {
    namespace = "xyz.asitanokibou.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "xyz.asitanokibou.player"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 百度网盘开放平台注册的应用名，决定网盘媒体根目录 /apps/<app_name>/movies
        buildConfigField("String", "BAIDU_APP_NAME", "\"asitanokibou\"")
    }

    // 仅当提供完整签名信息（keystore.properties 或环境变量）时才创建 release 签名配置
    signingConfigs {
        val signing = loadReleaseSigning()
        if (signing != null) {
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有签名配置则签名，否则产出未签名 APK
            signingConfig = signingConfigs.findByName("release")
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
        // Kotlin 1.9.x 走旧的 Compose 编译器扩展方式
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    // AndroidX / Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    debugImplementation(libs.androidx.ui.tooling)

    // 图片加载(Coil)
    implementation(libs.coil.compose)

    // 配置存储
    implementation(libs.androidx.datastore.preferences)

    // 播放器 Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // 本地代理服务 + 百度下载客户端 (Ktor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // 协程 / 序列化
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
