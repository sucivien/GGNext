import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.littlecodesauce.ggnext"
    compileSdk = 35
    val ndkProp = (project.findProperty("ndkVersion") as String?)?.trim().orEmpty()
    if (ndkProp.isNotEmpty()) {
        ndkVersion = ndkProp
    }

    defaultConfig {
        applicationId = "com.littlecodesauce.ggnext"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "0.10.0"
        vectorDrawables.useSupportLibrary = true

        val abiProp = (project.findProperty("abiFilters") as String?)?.trim().orEmpty()
        val resolvedAbis = if (abiProp.isNotEmpty()) {
            abiProp.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        ndk {
            abiFilters += resolvedAbis
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-fno-strict-aliasing")
                arguments += listOf(
                    "-DMEMENGINE_BUILD_TESTS=OFF",
                    "-DMEMENGINE_BUILD_JNI=ON",
                    "-DANDROID_STL=c++_static"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties().apply {
                if (propsFile.isFile) propsFile.inputStream().use { load(it) }
            }
            val storePath = props.getProperty("storeFile")
                ?: System.getenv("GGNEXT_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank() && file(storePath).isFile) {
                storeFile = file(storePath)
                storePassword = props.getProperty("storePassword")
                    ?: System.getenv("GGNEXT_KEYSTORE_PASSWORD")
                    ?: ""
                keyAlias = props.getProperty("keyAlias")
                    ?: System.getenv("GGNEXT_KEY_ALIAS")
                    ?: ""
                keyPassword = props.getProperty("keyPassword")
                    ?: System.getenv("GGNEXT_KEY_PASSWORD")
                    ?: ""
            }
        }
    }

    buildTypes {
        release {
            // R8：代码压缩 + 混淆 + 资源裁剪
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 关闭调试符号，减小体积
            isDebuggable = false
            // 若配置了 keystore.properties 或 GGNEXT_KEYSTORE_* 环境变量则使用正式签名，否则回退 debug 签名
            val relCfg = signingConfigs.getByName("release")
            signingConfig = if (relCfg.storeFile != null) relCfg else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
