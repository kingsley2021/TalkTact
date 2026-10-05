plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.goutou.wingman"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.shibry88_netizen.talktact"
        minSdk = 31  // Android 12+：液态玻璃的真实背景模糊走 RenderEffect
        targetSdk = 34
        versionCode = 34
        versionName = "0.8.7"
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // 让 resources/ 下的 META-INF/xposed/* 原样进 APK —— 框架就是靠这三个文件认模块的，
        // 少一个 LSPosed 就当它不是模块（列表里都不出现）
        resources.merges += "META-INF/xposed/*"
    }
}

dependencies {
    // 注入到微信进程里的那部分（config/llm/wechat）刻意只用系统 API + kotlin stdlib，
    // 不引 OkHttp / 序列化库 —— 免得和微信自带的同名库撞车（parent-first 类加载会拿到它那份）。
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // 折叠菜单的展开/收起动画（AnimatedVisibility）。material3/foundation 一般会把它带进来，
    // 但那是传递依赖，说不准哪天就没了 —— 这里显式写一条，版本仍由上面的 BOM 管。
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // 「每天中午 12:00 提炼说话风格」用它的周期任务：App 没开、手机重启过都照跑，
    // 也不必申请精确闹钟权限（AlarmManager 那条路 Android 12+ 要额外权限）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 现代 Xposed API（libxposed）。必须是 compileOnly：这些类由框架在运行时提供，
    // 打进 APK 反而会和框架自己那份撞车。
    compileOnly("io.github.libxposed:api:102.0.0")

    // service 是「模块 App ↔ 框架」的那一侧：写入 remote preferences 走它。
    // 必须是 implementation —— 它带一个 ContentProvider，运行时得真的存在。
    // 代价是它的 AAR 声明了 minCompileSdk=37，所以上面的 compileSdk 必须跟到 37。
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation("junit:junit:4.13.2")
}
