import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.apex.browser.engine"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// KGP 2.2：kotlinOptions DSL 已 deprecation（KGP 3.0 移除）—— 项目级 compilerOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 引擎的公开 API 暴露 core 类型（DomElement / PageSnapshot / DomParser.SnapshotStrategy）
    api(project(":browser-core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    // OverlayLifecycleOwner：浮窗 ComposeView 的独立 Lifecycle + SavedStateRegistry
    implementation(libs.lifecycle.runtime)
    implementation(libs.savedstate)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.browser"
version = "1.9.0"

mavenPublishing {
    // Android 项目用 vanniktech 0.30 自动配置（Empty javadoc + sources + release variant）
    coordinates(
        groupId = "com.apex.browser",
        artifactId = "browser-engine",
        version = "1.9.0",
    )
    pom {
        name.set("Apex Kit Browser :: Engine")
        description.set("Headless WebView automation engine: state machine, tab management, physical touch injection, retry/circuit-breaker")
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
