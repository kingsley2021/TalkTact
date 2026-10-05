plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.goutou.wingman"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.shibry88_netizen.talktact"
        minSdk = 31  // Android 12+：液态玻璃的真实背景模糊走 RenderEffect
        targetSdk = 34
        versionCode = 23
        versionName = "0.6.2"
    }
    /**
     * 固定签名。
     *
     * 为什么需要：GitHub Actions 每次跑在全新 runner 上，默认的 ~/.android/debug.keystore
     * 是**每次重建**的 —— 于是每个版本的签名都不一样，覆盖安装会直接报
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE (-7)。把钥匙固定下来（提交在本仓库里），
     * 本地和 CI 签出来就是同一份，升级才装得上。
     *
     * 注意：这把 key 是公开的，它只用来保证「同一个应用能连续升级」，**不构成任何安全边界**。
     */
    signingConfigs {
        create("stable") {
            storeFile = file("../keystore/talktact.p12")
            storePassword = "talktact"
            keyAlias = "talktact"
            keyPassword = "talktact"
            storeType = "PKCS12"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("stable") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // 注入到微信进程里的那部分（config/llm/wechat）刻意只用系统 API + kotlin stdlib，
    // 不引 OkHttp / 序列化库 —— 免得和微信自带的同名库撞车（parent-first 类加载会拿到它那份）。
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
}
