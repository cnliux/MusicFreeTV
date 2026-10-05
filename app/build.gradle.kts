import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tvmusic"
    compileSdk = 35

    signingConfigs {
        create("release") {
            // M21：改用 java.util.Properties 解析（原手写 split("=") 遇到值里含 '=' 或空格会解析错）
            val f = rootProject.file("keystore.properties")
            if (f.exists() && f.isFile) {
                val props = Properties()
                FileInputStream(f).use { props.load(it) }
                fun prop(key: String): String? = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                val sf = prop("storeFile")?.let { rootProject.file(it) }
                if (sf != null && sf.exists() && sf.isFile) {
                    storeFile = sf
                    storePassword = prop("storePassword")
                    keyAlias = prop("keyAlias")
                    keyPassword = prop("keyPassword")
                    // 字段缺失时构建期就会失败，提前给出明确提示而不是构建时的模糊报错
                    val missing = listOfNotNull(
                        if (storePassword == null) "storePassword" else null,
                        if (keyAlias == null) "keyAlias" else null,
                        if (keyPassword == null) "keyPassword" else null
                    )
                    if (missing.isNotEmpty()) {
                        throw GradleException("keystore.properties 缺少字段: ${missing.joinToString()}")
                    }
                } else {
                    logger.warn("keystore.properties 的 storeFile 不存在: ${prop("storeFile")}, release 将不签名")
                }
            } else {
                logger.warn("keystore.properties 不存在, release 将不签名（CI 无 Secrets 时的预期退化）")
            }
        }
    }

    defaultConfig {
        applicationId = "com.tvmusic"
        minSdk = 24
        targetSdk = 35
        versionCode = 27
        versionName = "0.3.22"

        vectorDrawables { useSupportLibrary = true }

        ndk {
            // arm64 + 32 位老 TV 盒 + 模拟器；quickjs aar 自带全部这四个 ABI
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有生产密钥时签名；没有时生成未签名 Release，避免把 Debug 证书产物误当正式包发布。
            if (signingConfigs.getByName("release").storeFile?.exists() == true) {
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
        buildConfig = true
    }

    packaging {
        // L5：补充排除 LICENSE/NOTICE/DEPENDENCIES/kotlin_module，消除重复资源冲突与体积冗余
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/*.kotlin_module"
        )
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            // TV 播放器固定横屏；第三方音源与局域网控制台需要兼容 HTTP。
            "DiscouragedApi",
            "InsecureBaseConfiguration",
            // compile/target SDK 35 是当前项目已验证基线；升级需单独做 TV 兼容回归。
            "OldTargetApi",
            // L4：恢复 UnusedResources；启动图标源素材改用 res/raw/keep.xml 的 tools:keep 显式保留。
            "IconLauncherShape",
            "IconLocation"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // L3：源码未使用 ProcessLifecycleOwner，移除 lifecycle-process
    implementation(platform(libs.compose.bom))
    // L1：ui/foundation/runtime 由 material3 传递依赖引入，仅保留显式用到的坐标
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    implementation(libs.quickjs.android)

    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
}