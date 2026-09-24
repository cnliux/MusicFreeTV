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
            val f = rootProject.file("keystore.properties")
            if (f.exists() && f.isFile) {
                val lines = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                fun prop(key: String): String? =
                    lines.firstOrNull { it.startsWith("$key=") }?.substringAfter("=")?.trim()
                val sf = prop("storeFile")?.let { rootProject.file(it) }
                if (sf != null && sf.exists() && sf.isFile) {
                    storeFile = sf
                    storePassword = prop("storePassword")
                    keyAlias = prop("keyAlias")
                    keyPassword = prop("keyPassword")
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.tvmusic"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"

        vectorDrawables { useSupportLibrary = true }

        ndk {
            // 只构建模拟器/常见 TV 盒所需 ABI，加快构建
            abiFilters += listOf("arm64-v8a", "x86_64")
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
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.okhttp)
    implementation(libs.coroutines.android)

    implementation(libs.quickjs.android)

    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
}