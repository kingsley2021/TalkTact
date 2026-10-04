plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.goutou.wingman"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.goutou.wingman"
        minSdk = 31  // Android 12+：液态玻璃的真实背景模糊走 RenderEffect
        targetSdk = 34
        versionCode = 17
        versionName = "0.5.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
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
