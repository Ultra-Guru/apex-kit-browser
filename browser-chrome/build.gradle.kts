import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.apex.browser.chrome"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // F2：强制所有资源加 browser_ 前缀，防止与宿主 R 类冲突
        resourcePrefix = "browser_"
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 项目级 compilerOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // chrome 层公开 API 暴露 engine 类型（BrowserEngine.BrowserSessionState 等）
    api(project(":browser-engine"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.savedstate)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.browser"
version = "2.0.0"

mavenPublishing {
    // Android 项目用 vanniktech 0.30 自动配置（Empty javadoc + sources + release variant）
    coordinates(
        groupId = "com.apex.browser",
        artifactId = "browser-chrome",
        version = "2.0.0",
    )
    pom {
        name.set("Apex Kit Browser :: Chrome")
        description.set("Compose browser chrome UI: address pill, tab strip, find bar, download shelf, JS dialog & permission hosts, overlay window")
        url.set("https://github.com/Ultra-Guru/apex-kit-browser")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("Ultra-Guru") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-kit-browser.git")
            url.set("https://github.com/Ultra-Guru/apex-kit-browser")
        }
    }
}
